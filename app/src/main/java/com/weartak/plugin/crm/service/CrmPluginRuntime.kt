package com.weartak.plugin.crm.service

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import com.weartak.plugin.crm.data.CrmDataRepository
import com.weartak.plugin.crm.data.CrmInferenceState
import com.weartak.plugin.crm.data.CrmInferenceStatus
import com.weartak.plugin.crm.data.CrmPluginSettings
import com.weartak.plugin.crm.presentation.CrmReserveActivity
import com.weartak.plugin.sdk.aidl.IWearTakHostCallback
import com.weartak.plugin.sdk.binder.PluginHostNotifier
import com.weartak.plugin.sdk.model.HostSettingsParcel
import com.weartak.plugin.sdk.model.MainUiCardParcel
import com.weartak.plugin.sdk.model.MainUiCardTypes
import com.weartak.plugin.sdk.model.PluginCapabilitiesParcel
import com.weartak.plugin.sdk.model.PluginInfoParcel
import com.weartak.plugin.sdk.model.PluginLifecycleState
import com.weartak.plugin.sdk.model.PluginMainUiSchemaParcel
import com.weartak.plugin.sdk.model.PluginStatusParcel
import com.weartak.plugin.sdk.model.SensorEventParcel
import com.weartak.plugin.sdk.model.SettingsApplyMode
import com.weartak.plugin.sdk.model.SettingsApplyResultParcel
import com.weartak.plugin.sdk.model.SettingsSchemaParcel
import com.weartak.plugin.sdk.ppg.v1.IPpgHostV1
import com.weartak.plugin.sdk.ppg.v1.PpgContractV1
import com.weartak.plugin.sdk.validation.ContractValidationResult
import com.weartak.plugin.sdk.validation.MainUiSchemaContractValidator
import com.weartak.plugin.sdk.v2.aidl.IWearTakPluginHostControlV2
import com.weartak.plugin.crm.BuildConfig
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object CrmPluginRuntime {
    private const val TAG = "WearTAK-CRM-Runtime"
    private const val SENSOR_KEY = "crm_status_v1"
    private const val ACTION_OPEN_CRM = "crm.open"

    private val lock = Any()
    private val eventExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        { runnable -> Thread(runnable, "crm-host-events").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private var appContext: Context? = null
    private var settingsStore: CrmSettingsStore? = null
    private var repository: CrmDataRepository? = null
    private var ppgSource: CrmPpgHostSource? = null
    private var hostCallback: IWearTakHostCallback? = null
    private var hostSettings: HostSettingsParcel? = null
    private var lifecycleState = PluginLifecycleState.BOUND
    private var hostPackageName: String? = null
    private var hostUid: Int? = null
    private var connectionGeneration = 0L
    private var eventSequence = 0L
    private var eventSequenceExhausted = false
    private var eventRevision = 0L
    private var sentRevision = -1L
    private var eventDrainQueued = false
    private var latestEvent = SensorSnapshot("IDLE", null, false, System.currentTimeMillis())

    private val mainUiSchema: PluginMainUiSchemaParcel = run {
        val raw = PluginMainUiSchemaParcel(
            cards = listOf(
                MainUiCardParcel(
                    cardType = MainUiCardTypes.STATUS,
                    cardId = "crm-status",
                    title = "CRM",
                    subtitle = "Compensatory Reserve Measure",
                ),
                MainUiCardParcel(
                    cardType = MainUiCardTypes.KEY_VALUE_LIST,
                    cardId = "crm-values",
                    title = "Measurement",
                    subtitle = "Current state",
                ),
            ),
        )
        when (val result = MainUiSchemaContractValidator.validate(raw, strictV1CardTypesOnly = true)) {
            is ContractValidationResult.Ok -> result.value
            is ContractValidationResult.Err -> {
                Log.w(TAG, "CRM main UI schema validation failed: ${result.issues}")
                MainUiSchemaContractValidator.sanitize(raw)
            }
        }
    }

    fun initialize(context: Context, store: CrmSettingsStore, crmRepository: CrmDataRepository) {
        synchronized(lock) {
            appContext = context.applicationContext
            settingsStore = store
            repository = crmRepository
            crmRepository.applySettings(store.current())
        }
        publishCurrentStatus()
    }

    fun attachHost(
        init: com.weartak.plugin.sdk.model.PluginInitParcel?,
        callback: IWearTakHostCallback?,
    ) {
        val candidate = createPpgSource(init?.hostExtras)
        val (previous, context) = synchronized(lock) {
            connectionGeneration += 1
            eventSequence = 0L
            eventSequenceExhausted = false
            sentRevision = -1L
            hostPackageName = init?.hostPackageName
            hostUid = init?.hostUid
            hostCallback = callback
            hostSettings = null
            ppgSource.also { ppgSource = candidate } to appContext
        }
        if (previous != null) context?.let { CrmInferenceService.stopForHost(it) }
        previous?.close()
        val repo = synchronized(lock) { repository }
        repo?.updateSamplingState(
            if (candidate == null) {
                CrmDataRepository.CrmSamplingState.UNAVAILABLE
            } else {
                CrmDataRepository.CrmSamplingState.IDLE
            },
        )
        publishCurrentStatus()
    }

    fun detachHost() {
        val (source, context) = synchronized(lock) {
            connectionGeneration += 1
            hostCallback = null
            hostPackageName = null
            hostUid = null
            hostSettings = null
            val oldSource = ppgSource
            ppgSource = null
            oldSource to appContext
        }
        context?.let { CrmInferenceService.stopForHost(it) }
        source?.close()
        synchronized(lock) { repository }?.apply {
            updateDeviceWorn(false)
            updateSamplingState(CrmDataRepository.CrmSamplingState.UNAVAILABLE)
            tryPublish(
                CrmInferenceState(
                    status = CrmInferenceStatus.ERROR,
                    updatedAtMillis = System.currentTimeMillis(),
                    rejectionReason = "host_disconnected",
                ),
            )
        }
    }

    fun setHostCallback(callback: IWearTakHostCallback?) {
        synchronized(lock) { hostCallback = callback }
        publishCurrentStatus()
    }

    fun updateLifecycle(state: PluginLifecycleState) {
        synchronized(lock) { lifecycleState = state }
        notifyHostStatus()
    }

    fun updateHostSettings(settings: HostSettingsParcel?) {
        synchronized(lock) { hostSettings = settings }
        notifyHostStatus()
    }

    fun getPluginInfo() = PluginInfoParcel(
        pluginId = BuildConfig.APPLICATION_ID,
        displayName = "CRM",
        versionName = BuildConfig.VERSION_NAME,
        packageName = BuildConfig.APPLICATION_ID,
        description = "Compensatory Reserve Measure plugin.",
    )

    fun getCapabilities() = PluginCapabilitiesParcel(
        hostedSettingsUi = true,
        hostedMainUi = true,
        externalSettingsActivity = false,
        externalMainActivity = true,
        cotReadyEvents = false,
        mapLayers = false,
        deviceIntegration = false,
        sharedMemory = false,
    )

    fun getStatus(state: PluginLifecycleState): PluginStatusParcel {
        val settings = currentSettings()
        val inference = currentInference()
        val extras = Bundle().apply {
            putBoolean("crm.enabled", settings.enabled)
            putString("crm.status", inference.status.name)
            inference.value?.let { putFloat("crm.value", it) }
            putLong("crm.updatedAtMillis", inference.updatedAtMillis)
            putBoolean("host.attached", synchronized(lock) { hostPackageName != null })
            putString("host.package", synchronized(lock) { hostPackageName })
            putInt("host.uid", synchronized(lock) { hostUid ?: -1 })
        }
        return PluginStatusParcel(
            state = state,
            message = "CRM ${inference.status.name.lowercase()}",
            detailCode = if (settings.enabled) 1 else 0,
            extras = extras,
        )
    }

    fun getSettingsSchema(): SettingsSchemaParcel = CrmSettingsStore.schema

    fun getSettingsValues(): Bundle = settingsStoreOrThrow().values()

    fun applySettings(values: Bundle?, applyMode: Int): SettingsApplyResultParcel {
        val store = settingsStoreOrThrow()
        val result = store.apply(values, applyMode, synchronized(lock) { hostSettings })
        if (result.success) {
            val settings = store.current()
            repositoryOrThrow().applySettings(settings)
            if (!settings.enabled) CrmInferenceService.stopForHost(appContextOrThrow())
            notifyHostSettingsChanged()
            publishCurrentStatus()
        }
        return result
    }

    fun getMainUiSchema(): PluginMainUiSchemaParcel = mainUiSchema

    fun getMainUiState(): Bundle {
        val settings = currentSettings()
        val inference = currentInference()
        val repo = repositoryOrThrow()
        return Bundle().apply {
            putString("kv_row1_label", "CRM")
            putString("kv_row1_value", inference.status.name)
            putString("kv_row2_label", "Score")
            putString("kv_row2_value", inference.displayScore?.toString() ?: "--")
            putString("kv_row3_label", "Sampling")
            putString("kv_row3_value", repo.samplingState.value.name)
            putString("kv_row4_label", "Enabled")
            putString("kv_row4_value", if (settings.enabled) "on" else "off")
        }
    }

    fun invokeMainUiAction(actionId: String?): Boolean {
        if (actionId != ACTION_OPEN_CRM) return false
        val context = appContextOrThrow()
        return try {
            context.startActivity(
                Intent(context, CrmReserveActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Could not open CRM activity", e)
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "CRM activity launch was denied", e)
            false
        }
    }

    fun currentPpgSource(): PpgSource? = synchronized(lock) { ppgSource }

    fun stopSampling() {
        synchronized(lock) { appContext }?.let { CrmInferenceService.stopForHost(it) }
    }

    fun publishCurrentStatus() {
        val settings = currentSettings()
        val inference = currentInference()
        val status = when {
            !settings.enabled -> "IDLE"
            inference.status == CrmInferenceStatus.ACCEPTED &&
                inference.value != null &&
                inference.value.isFinite() &&
                inference.value in 0f..1f -> "ACCEPTED"
            inference.status == CrmInferenceStatus.REJECTED -> "REJECTED"
            inference.status == CrmInferenceStatus.ERROR -> "ERROR"
            inference.status == CrmInferenceStatus.NOT_WORN -> "NOT_WORN"
            else -> "IDLE"
        }
        val timestamp = if (status == "ACCEPTED" || status == "REJECTED" ||
            status == "ERROR" || status == "NOT_WORN"
        ) {
            inference.updatedAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
        } else {
            System.currentTimeMillis()
        }
        synchronized(lock) {
            if (eventRevision == Long.MAX_VALUE) {
                Log.e(TAG, "CRM event revision exhausted")
                return
            }
            latestEvent = SensorSnapshot(
                status = status,
                value = if (status == "ACCEPTED") inference.value?.toDouble() else null,
                enabled = settings.enabled,
                timestampUtcMillis = timestamp,
            )
            eventRevision += 1
            scheduleEventDrainLocked()
        }
    }

    private fun createPpgSource(extras: Bundle?): CrmPpgHostSource? {
        if (extras == null) return null
        val version = extras.get(PpgContractV1.EXTRA_VERSION) as? Int
        val controlBinder = extras.getBinder(PpgContractV1.EXTRA_CONTROL)
        if (version != PpgContractV1.VERSION || controlBinder == null) {
            Log.i(TAG, "Host did not negotiate PPG v1")
            return null
        }
        val ppgHost = IPpgHostV1.Stub.asInterface(controlBinder)
        if (ppgHost == null) {
            Log.w(TAG, "Host supplied an invalid PPG v1 binder")
            return null
        }
        return CrmPpgHostSource(ppgHost)
    }

    private fun scheduleEventDrainLocked() {
        if (eventDrainQueued || hostCallback == null) return
        eventDrainQueued = true
        try {
            eventExecutor.execute(::drainEvents)
        } catch (e: RejectedExecutionException) {
            eventDrainQueued = false
            Log.e(TAG, "CRM host event queue rejected status update", e)
        }
    }

    private fun drainEvents() {
        while (true) {
            val pending = synchronized(lock) {
                val callback = hostCallback
                if (callback == null || sentRevision == eventRevision) {
                    eventDrainQueued = false
                    return
                }
                if (eventSequenceExhausted) {
                    eventDrainQueued = false
                    Log.e(TAG, "CRM host event sequence exhausted")
                    return
                }
                val sequence = eventSequence
                if (eventSequence == Long.MAX_VALUE) {
                    eventSequenceExhausted = true
                } else {
                    eventSequence += 1
                }
                Triple(callback, eventRevision, buildSensorEvent(latestEvent, sequence))
            }
            try {
                pending.first.onSensorEvent(pending.third)
            } catch (e: RemoteException) {
                Log.w(TAG, "Could not deliver CRM status event", e)
                if (!pending.first.asBinder().isBinderAlive) {
                    synchronized(lock) {
                        if (hostCallback === pending.first) hostCallback = null
                    }
                }
            }
            synchronized(lock) {
                if (pending.second > sentRevision) sentRevision = pending.second
            }
        }
    }

    private fun buildSensorEvent(snapshot: SensorSnapshot, sequence: Long) =
        SensorEventParcel(
            sensorKey = SENSOR_KEY,
            timestampUtcMillis = snapshot.timestampUtcMillis,
            sequence = sequence,
            values = Bundle().apply {
                putString("status", snapshot.status)
                if (snapshot.status == "ACCEPTED" && snapshot.value != null) {
                    putDouble("value", snapshot.value)
                }
                putBoolean("enabled", snapshot.enabled)
            },
        )

    private fun notifyHostSettingsChanged() {
        val callback = synchronized(lock) { hostCallback }
        PluginHostNotifier.settingsValuesChanged(TAG, callback)
        PluginHostNotifier.mainUiStateChanged(TAG, callback)
        notifyHostStatus()
    }

    private fun notifyHostStatus() {
        val callback = synchronized(lock) { hostCallback }
        val state = synchronized(lock) { lifecycleState }
        PluginHostNotifier.statusChanged(TAG, callback, getStatus(state))
        PluginHostNotifier.mainUiStateChanged(TAG, callback)
    }

    private fun currentSettings(): CrmPluginSettings = settingsStoreOrThrow().current()

    private fun currentInference(): CrmInferenceState =
        repositoryOrThrow().latestCrmInference.value

    private fun settingsStoreOrThrow() =
        synchronized(lock) { settingsStore } ?: error("CRM settings store is not initialized")

    private fun repositoryOrThrow() =
        synchronized(lock) { repository } ?: error("CRM repository is not initialized")

    private fun appContextOrThrow() =
        synchronized(lock) { appContext } ?: error("CRM runtime is not initialized")

    private data class SensorSnapshot(
        val status: String,
        val value: Double?,
        val enabled: Boolean,
        val timestampUtcMillis: Long,
    )
}
