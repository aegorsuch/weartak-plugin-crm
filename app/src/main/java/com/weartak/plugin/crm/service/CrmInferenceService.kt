package com.weartak.plugin.crm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.weartak.plugin.crm.BuildConfig
import com.weartak.plugin.crm.CrmApplication
import com.weartak.plugin.crm.R
import com.weartak.plugin.crm.data.CrmDataRepository
import com.weartak.plugin.crm.data.CrmInferenceState
import com.weartak.plugin.crm.data.CrmInferenceStatus
import com.weartak.plugin.crm.data.CrmRawPpgSample
import com.weartak.plugin.crm.service.crm.CrmInferenceCoordinator
import com.weartak.plugin.crm.service.crm.CrmSessionCsvLogger
import com.weartak.plugin.crm.service.crm.TfliteCrmModelRunner
import com.weartak.plugin.sdk.ppg.v1.PpgBatchV1
import com.weartak.plugin.sdk.ppg.v1.PpgContractV1
import com.weartak.plugin.sdk.ppg.v1.PpgSampleV1
import com.weartak.plugin.sdk.ppg.v1.PpgStateV1
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

class CrmInferenceService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sessionMutex = Mutex()
    private val sessionLock = Any()
    private val commandChannel = Channel<ServiceCommand>(Channel.UNLIMITED)
    private lateinit var repository: CrmDataRepository
    private lateinit var csvLogger: CrmSessionCsvLogger
    private lateinit var coordinator: CrmInferenceCoordinator
    private var inferenceObserver: Job? = null
    private var eventConsumer: Job? = null
    private var activeSource: PpgSource? = null
    private var activeEvents: Channel<PpgEvent>? = null
    private var sessionGeneration = 0L
    private var queueOverflow = AtomicBoolean(false)
    private var lastRawLogUptimeMillis = 0L
    private var lastWaveformDropLogUptimeMillis = 0L

    override fun onCreate() {
        super.onCreate()
        val app = application as CrmApplication
        repository = app.crmRepository
        csvLogger = CrmSessionCsvLogger(this)
        coordinator = CrmInferenceCoordinator(
            crmRepo = repository,
            csvLogger = csvLogger,
            runnerFactory = { TfliteCrmModelRunner(applicationContext) },
        )
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        isRunning.set(true)
        serviceScope.launch {
            for (command in commandChannel) {
                when (command) {
                    is ServiceCommand.Start -> startSession(command.startId)
                    is ServiceCommand.Stop -> stopSession(command.reason, command.startId)
                }
            }
        }
        inferenceObserver = serviceScope.launch {
            coordinator.warmUpModel()
            repository.latestCrmInference.collect {
                CrmPluginRuntime.publishCurrentStatus()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = when (intent?.action) {
            ACTION_STOP -> ServiceCommand.Stop("user_stopped", startId)
            else -> ServiceCommand.Start(startId)
        }
        if (commandChannel.trySend(command).isFailure) {
            Log.e(TAG, "CRM service command channel is closed")
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val (source, channel, consumer) = synchronized(sessionLock) {
            sessionGeneration += 1
            val current = Triple(activeSource, activeEvents, eventConsumer)
            activeSource = null
            activeEvents = null
            eventConsumer = null
            current
        }
        channel?.close()
        commandChannel.close()
        consumer?.cancel()
        source?.close()
        if (source != null) {
            coordinator.reset("service_destroyed")
            csvLogger.stopSession("service_destroyed")
            repository.updateDeviceWorn(false)
            repository.updateSamplingState(CrmDataRepository.CrmSamplingState.IDLE)
            repository.tryPublish(
                CrmInferenceState(
                    status = CrmInferenceStatus.IDLE,
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
            CrmPluginRuntime.publishCurrentStatus()
        }
        coordinator.close()
        csvLogger.close()
        inferenceObserver?.cancel()
        serviceScope.cancel()
        isRunning.set(false)
        super.onDestroy()
    }

    private suspend fun startSession(startId: Int) {
        var noSource = false
        val result = sessionMutex.withLock {
            if (synchronized(sessionLock) { activeSource != null }) return@withLock null

            val source = CrmPluginRuntime.currentPpgSource()
            if (source == null) {
                setUnavailable("ppg_host_unavailable")
                noSource = true
                return@withLock null
            }

            val events = Channel<PpgEvent>(CALLBACK_QUEUE_CAPACITY)
            val generation = synchronized(sessionLock) {
                sessionGeneration += 1
                queueOverflow.set(false)
                activeSource = source
                activeEvents = events
                sessionGeneration
            }
            repository.updateSamplingState(CrmDataRepository.CrmSamplingState.STARTING)
            repository.updateDeviceWorn(false)
            coordinator.reset("sampling_started")
            repository.tryPublish(
                CrmInferenceState(
                    status = CrmInferenceStatus.IDLE,
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
            csvLogger.startSession(
                channel = "ppg.v1",
                trackerType = "host_owned",
                nominalSampleRateHz = 100,
                wearTakUid = "",
                callsignAtSessionStart = "",
                appVersionName = BuildConfig.VERSION_NAME,
                appVersionCode = BuildConfig.VERSION_CODE,
                gitBranch = "",
                gitCommit = "",
                buildType = BuildConfig.BUILD_TYPE,
            )
            CrmPluginRuntime.publishCurrentStatus()

            val consumer = serviceScope.launch {
                for (event in events) {
                    if (!isCurrentSession(generation)) break
                    when (event) {
                        is PpgEvent.State -> handlePpgState(event.value)
                        is PpgEvent.Samples -> handlePpgBatch(event.value)
                        is PpgEvent.Failure ->
                            serviceScope.launch { failSession(generation, event.reason) }
                    }
                }
            }
            synchronized(sessionLock) { eventConsumer = consumer }

            SubscribeResult(
                generation = generation,
                result = source.subscribe(createListener(generation, events)),
            )
        }

        if (noSource) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return
        }
        if (result != null && result.result != PpgContractV1.OK) {
            failSession(result.generation, "subscribe_rejected_${result.result}")
        }
    }

    private suspend fun stopSession(reason: String, startId: Int? = null) = sessionMutex.withLock {
        val (source, events, consumer) = synchronized(sessionLock) {
            val current = Triple(activeSource, activeEvents, eventConsumer)
            if (activeSource == null) {
                if (startId != null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
                return@withLock
            }
            sessionGeneration += 1
            activeSource = null
            activeEvents = null
            eventConsumer = null
            current
        }
        repository.updateSamplingState(CrmDataRepository.CrmSamplingState.STOPPING)
        events?.close()
        consumer?.cancelAndJoin()
        coordinator.reset(reason)
        source?.unsubscribe()
        csvLogger.stopSession(reason)
        repository.updateDeviceWorn(false)
        repository.updateSamplingState(CrmDataRepository.CrmSamplingState.IDLE)
        repository.tryPublish(
            CrmInferenceState(
                status = CrmInferenceStatus.IDLE,
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
        CrmPluginRuntime.publishCurrentStatus()
        if (startId != null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    private fun createListener(
        generation: Long,
        channel: Channel<PpgEvent>,
    ): PpgSource.Listener = object : PpgSource.Listener {
        override fun onState(state: PpgStateV1) = enqueue(generation, channel, PpgEvent.State(state))

        override fun onSamples(batch: PpgBatchV1) = enqueue(generation, channel, PpgEvent.Samples(batch))

        override fun onFailure(reason: String) = enqueue(generation, channel, PpgEvent.Failure(reason))
    }

    private fun enqueue(generation: Long, channel: Channel<PpgEvent>, event: PpgEvent) {
        val failed = synchronized(sessionLock) {
            if (generation != sessionGeneration || activeEvents !== channel) return
            channel.trySend(event).isFailure
        }
        if (failed && queueOverflow.compareAndSet(false, true)) {
            serviceScope.launch { failSession(generation, "callback_queue_full") }
        }
    }

    private suspend fun handlePpgState(state: PpgStateV1) {
        when {
            state.worn == PpgContractV1.NOT_WORN || state.state == PpgContractV1.STATE_NOT_WORN -> {
                repository.updateDeviceWorn(false)
                repository.updateSamplingState(CrmDataRepository.CrmSamplingState.NOT_WORN)
                coordinator.reset(state.reason ?: "device_not_worn")
                repository.tryPublish(
                    CrmInferenceState(
                        status = CrmInferenceStatus.NOT_WORN,
                        updatedAtMillis = System.currentTimeMillis(),
                        rejectionReason = state.reason,
                    ),
                )
            }
            state.worn != PpgContractV1.WORN || state.source != PpgContractV1.SOURCE_SAMSUNG_PPG_ON_DEMAND -> {
                repository.updateDeviceWorn(false)
                repository.updateSamplingState(CrmDataRepository.CrmSamplingState.UNAVAILABLE)
                coordinator.reset(state.reason ?: "ppg_state_unavailable")
                repository.tryPublish(
                    CrmInferenceState(
                        status = CrmInferenceStatus.ERROR,
                        updatedAtMillis = System.currentTimeMillis(),
                        rejectionReason = state.reason ?: "ppg_state_unavailable",
                    ),
                )
            }
            else -> {
                repository.updateDeviceWorn(true)
                when (state.state) {
                    PpgContractV1.IDLE -> repository.updateSamplingState(CrmDataRepository.CrmSamplingState.IDLE)
                    PpgContractV1.STARTING -> repository.updateSamplingState(CrmDataRepository.CrmSamplingState.STARTING)
                    PpgContractV1.SAMPLING -> repository.updateSamplingState(CrmDataRepository.CrmSamplingState.SAMPLING)
                    PpgContractV1.STOPPING -> repository.updateSamplingState(CrmDataRepository.CrmSamplingState.STOPPING)
                    PpgContractV1.STATE_UNAVAILABLE -> {
                        repository.updateSamplingState(CrmDataRepository.CrmSamplingState.UNAVAILABLE)
                        coordinator.reset(state.reason ?: "ppg_state_unavailable")
                        repository.tryPublish(
                            CrmInferenceState(
                                status = CrmInferenceStatus.ERROR,
                                updatedAtMillis = System.currentTimeMillis(),
                                rejectionReason = state.reason ?: "ppg_state_unavailable",
                            ),
                        )
                    }
                    PpgContractV1.ERROR -> {
                        repository.updateSamplingState(CrmDataRepository.CrmSamplingState.ERROR)
                        coordinator.reset(state.reason ?: "ppg_host_error")
                        repository.tryPublish(
                            CrmInferenceState(
                                status = CrmInferenceStatus.ERROR,
                                updatedAtMillis = System.currentTimeMillis(),
                                rejectionReason = state.reason ?: "ppg_host_error",
                            ),
                        )
                    }
                    else -> Unit
                }
            }
        }
        CrmPluginRuntime.publishCurrentStatus()
    }

    private suspend fun handlePpgBatch(batch: PpgBatchV1) {
        for (sample in batch.samples) {
            handlePpgSample(sample)
        }
    }

    private fun handlePpgSample(sample: PpgSampleV1) {
        repository.updateDeviceWorn(sample.deviceWorn)
        val waveformPublished = repository.tryPublishRawPpgSample(
            CrmRawPpgSample(
                uptimeMillis = sample.uptimeMillis,
                rawValue = sample.rawValue,
                sourceAvailable = sample.sourceAvailable,
                deviceWorn = sample.deviceWorn,
                emittedAtMillis = sample.emittedAtMillis,
                reason = sample.reason,
            ),
        )
        if (!waveformPublished) {
            val now = SystemClock.uptimeMillis()
            if (now - lastWaveformDropLogUptimeMillis >= RAW_LOG_PERIOD_MS) {
                lastWaveformDropLogUptimeMillis = now
                Log.w(TAG, "CRM waveform samples dropped because UI buffer is full")
            }
        }
        val rawValue = sample.rawValue
        if (!sample.sourceAvailable || !sample.deviceWorn || rawValue == null || !rawValue.isFinite()) {
            val reason = sample.reason ?: when {
                !sample.deviceWorn -> "device_not_worn"
                !sample.sourceAvailable -> "ppg_source_unavailable"
                else -> "invalid_ppg_sample"
            }
            coordinator.reset(reason)
            if (!sample.deviceWorn) {
                repository.tryPublish(
                    CrmInferenceState(
                        status = CrmInferenceStatus.NOT_WORN,
                        updatedAtMillis = sample.emittedAtMillis,
                        rejectionReason = reason,
                    ),
                )
                repository.updateSamplingState(CrmDataRepository.CrmSamplingState.NOT_WORN)
            }
            CrmPluginRuntime.publishCurrentStatus()
            return
        }

        maybeLogRawSample(sample)
        csvLogger.logRawPpg(
            uptimeMillis = sample.uptimeMillis,
            rawValue = rawValue,
            channel = "ppg.v1",
            trackerType = "host_owned",
            nominalSampleRateHz = 100,
            deviceWorn = sample.deviceWorn,
        )
        coordinator.onRawPpgSample(
            sampleUptimeMillis = sample.uptimeMillis,
            rawValue = rawValue,
            sourceAvailable = true,
        )
    }

    private suspend fun failSession(generation: Long, reason: String) = sessionMutex.withLock {
        val (source, events, consumer) = synchronized(sessionLock) {
            if (generation != sessionGeneration || activeSource == null) return@withLock
            val current = Triple(activeSource, activeEvents, eventConsumer)
            sessionGeneration += 1
            activeSource = null
            activeEvents = null
            eventConsumer = null
            current
        }
        Log.w(TAG, "PPG session failed: $reason")
        events?.close()
        consumer?.cancelAndJoin()
        source?.unsubscribe()
        coordinator.reset(reason)
        csvLogger.stopSession(reason)
        repository.updateSamplingState(
            if (reason == "host_disconnected" || reason.startsWith("subscribe_rejected_")) {
                CrmDataRepository.CrmSamplingState.UNAVAILABLE
            } else {
                CrmDataRepository.CrmSamplingState.ERROR
            },
        )
        repository.tryPublish(
            CrmInferenceState(
                status = CrmInferenceStatus.ERROR,
                updatedAtMillis = System.currentTimeMillis(),
                rejectionReason = reason,
            ),
        )
        CrmPluginRuntime.publishCurrentStatus()
    }

    private fun setUnavailable(reason: String) {
        repository.updateDeviceWorn(false)
        repository.updateSamplingState(CrmDataRepository.CrmSamplingState.UNAVAILABLE)
        coordinator.reset(reason)
        repository.tryPublish(
            CrmInferenceState(
                status = CrmInferenceStatus.ERROR,
                updatedAtMillis = System.currentTimeMillis(),
                rejectionReason = reason,
            ),
        )
        CrmPluginRuntime.publishCurrentStatus()
    }

    private fun isCurrentSession(generation: Long) =
        synchronized(sessionLock) { generation == sessionGeneration && activeSource != null }

    private fun maybeLogRawSample(sample: PpgSampleV1) {
        val now = SystemClock.uptimeMillis()
        if (now - lastRawLogUptimeMillis < RAW_LOG_PERIOD_MS) return
        lastRawLogUptimeMillis = now
        Log.d(
            TAG,
            "CRM_PPG_SAMPLE uptimeMs=${sample.uptimeMillis} emittedAtMs=${sample.emittedAtMillis}",
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_crm),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.weartak.plugin.crm.presentation.CrmReserveActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.notification_crm))
            .setContentText(getString(R.string.crm_reserve))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private sealed interface PpgEvent {
        data class State(val value: PpgStateV1) : PpgEvent
        data class Samples(val value: PpgBatchV1) : PpgEvent
        data class Failure(val reason: String) : PpgEvent
    }

    private sealed interface ServiceCommand {
        data class Start(val startId: Int) : ServiceCommand
        data class Stop(val reason: String, val startId: Int) : ServiceCommand
    }

    private data class SubscribeResult(
        val generation: Long,
        val result: Int,
    )

    companion object {
        private const val TAG = "WearTAK-CRM-Service"
        private const val ACTION_START = "com.weartak.plugin.crm.action.START"
        private const val ACTION_STOP = "com.weartak.plugin.crm.action.STOP"
        private const val NOTIFICATION_ID = 921
        private const val NOTIFICATION_CHANNEL_ID = "crm_inference"
        private const val RAW_LOG_PERIOD_MS = 2_000L
        private const val CALLBACK_QUEUE_CAPACITY = 64

        val isRunning = AtomicBoolean(false)

        fun start(context: Context) {
            val intent = Intent(context, CrmInferenceService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CrmInferenceService::class.java).setAction(ACTION_STOP)
            context.applicationContext.startService(intent)
        }

        fun stopForHost(context: Context) {
            context.applicationContext.stopService(Intent(context, CrmInferenceService::class.java))
        }
    }
}
