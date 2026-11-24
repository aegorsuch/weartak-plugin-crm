package com.weartak.plugin.crm.service

import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.weartak.plugin.sdk.ppg.v1.IPpgCallbackV1
import com.weartak.plugin.sdk.ppg.v1.IPpgHostV1
import com.weartak.plugin.sdk.ppg.v1.PpgBatchV1
import com.weartak.plugin.sdk.ppg.v1.PpgContractV1
import com.weartak.plugin.sdk.ppg.v1.PpgStateV1
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

interface PpgSource {
    interface Listener {
        fun onState(state: PpgStateV1)
        fun onSamples(batch: PpgBatchV1)
        fun onFailure(reason: String)
    }

    fun subscribe(listener: Listener): Int
    fun canRetrySubscribe(): Boolean = false
    fun unsubscribe()
    fun close()
}

class CrmPpgHostSource(
    private val host: IPpgHostV1,
) : PpgSource {
    private val lock = Any()
    private val cleanupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "crm-ppg-cleanup").apply { isDaemon = true }
    }
    private val connectionId = UUID.randomUUID().toString().replace("-", "")
    private val binder = host.asBinder()
    private var nextSubscriptionNumber = 0L
    private var activeSubscriptionId: String? = null
    private var expectedBatchSequence = 0L
    private var listener: PpgSource.Listener? = null
    private var closed = false
    private var failed = false

    private val deathRecipient = IBinder.DeathRecipient {
        fail("host_disconnected")
    }

    private val callback = object : IPpgCallbackV1.Stub() {
        override fun onState(state: PpgStateV1?) {
            if (state == null) {
                fail("invalid_state")
                return
            }
            val target = synchronized(lock) {
                if (closed || failed || state.subscriptionId != activeSubscriptionId) null else listener
            } ?: return
            if ((state.reason?.length ?: 0) > PpgContractV1.MAX_REASON_CHARS ||
                state.source !in setOf(
                    PpgContractV1.SOURCE_UNKNOWN,
                    PpgContractV1.SOURCE_SAMSUNG_PPG_ON_DEMAND,
                ) ||
                state.worn !in setOf(PpgContractV1.WORN_UNKNOWN, PpgContractV1.WORN, PpgContractV1.NOT_WORN) ||
                state.state !in setOf(
                    PpgContractV1.IDLE,
                    PpgContractV1.STARTING,
                    PpgContractV1.SAMPLING,
                    PpgContractV1.STOPPING,
                    PpgContractV1.STATE_NOT_WORN,
                    PpgContractV1.STATE_UNAVAILABLE,
                    PpgContractV1.ERROR,
                )
            ) {
                fail("invalid_state")
                return
            }
            if (state.state == PpgContractV1.ERROR) {
                fail(state.reason ?: "ppg_host_error")
                return
            }
            try {
                target.onState(state)
            } catch (e: RuntimeException) {
                Log.e(TAG, "PPG state listener failed", e)
                fail("listener_failed")
            }
        }

        override fun onSamples(batch: PpgBatchV1?) {
            if (batch == null) {
                fail("invalid_batch")
                return
            }
            val target = synchronized(lock) {
                if (closed || failed || batch.subscriptionId != activeSubscriptionId) return
                if (batch.sequence != expectedBatchSequence ||
                    expectedBatchSequence == Long.MAX_VALUE ||
                    batch.samples.size > PpgContractV1.MAX_SAMPLES_PER_BATCH ||
                    batch.samples.any { sample ->
                        (sample.reason?.length ?: 0) > PpgContractV1.MAX_REASON_CHARS ||
                            sample.uptimeMillis < 0L
                    }
                ) {
                    null
                } else {
                    expectedBatchSequence += 1
                    listener
                }
            }
            if (target == null) {
                fail("invalid_batch")
                return
            }
            try {
                target.onSamples(batch)
            } catch (e: RuntimeException) {
                Log.e(TAG, "PPG sample listener failed", e)
                fail("listener_failed")
            }
        }
    }

    init {
        try {
            binder.linkToDeath(deathRecipient, 0)
        } catch (_: RemoteException) {
            failed = true
        }
    }

    override fun subscribe(listener: PpgSource.Listener): Int {
        val subscriptionId = synchronized(lock) {
            if (closed || failed || !binder.isBinderAlive || activeSubscriptionId != null ||
                nextSubscriptionNumber == Long.MAX_VALUE
            ) {
                return PpgContractV1.UNAVAILABLE
            }
            val id = "crm-$connectionId-${nextSubscriptionNumber++}"
            activeSubscriptionId = id
            expectedBatchSequence = 0L
            this.listener = listener
            id
        }

        val result = try {
            host.subscribe(subscriptionId, callback)
        } catch (_: RemoteException) {
            fail("host_disconnected")
            return PpgContractV1.UNAVAILABLE
        }
        if (result != PpgContractV1.OK) {
            synchronized(lock) {
                if (activeSubscriptionId == subscriptionId) {
                    activeSubscriptionId = null
                    this.listener = null
                }
            }
        }
        return result
    }

    override fun canRetrySubscribe(): Boolean = synchronized(lock) {
        !closed && !failed && binder.isBinderAlive && activeSubscriptionId == null
    }

    override fun unsubscribe() {
        val (subscriptionId, target) = synchronized(lock) {
            val current = activeSubscriptionId ?: return
            activeSubscriptionId = null
            current to listener
        }
        try {
            val result = host.unsubscribe(subscriptionId)
            if (result != PpgContractV1.OK) {
                Log.w(TAG, "PPG unsubscribe was rejected with code=$result")
                fail("unsubscribe_rejected")
            } else {
                synchronized(lock) {
                    if (listener === target) listener = null
                }
            }
        } catch (_: RemoteException) {
            fail("host_disconnected")
        }
    }

    override fun close() {
        val subscriptionId = synchronized(lock) {
            if (closed) return
            closed = true
            listener = null
            activeSubscriptionId.also { activeSubscriptionId = null }
        }
        binder.unlinkToDeath(deathRecipient, 0)
        if (subscriptionId != null && binder.isBinderAlive) {
            cleanupExecutor.execute {
                try {
                    val result = host.unsubscribe(subscriptionId)
                    if (result != PpgContractV1.OK) {
                        Log.w(TAG, "PPG cleanup unsubscribe was rejected with code=$result")
                    }
                } catch (_: RemoteException) {
                    Log.w(TAG, "PPG cleanup unsubscribe failed because host disconnected")
                }
            }
        }
        cleanupExecutor.shutdown()
    }

    private fun fail(reason: String) {
        val (failedListener, subscriptionId) = synchronized(lock) {
            if (closed || failed) return
            failed = true
            val current = listener
            listener = null
            val id = activeSubscriptionId
            activeSubscriptionId = null
            current to id
        }
        if (subscriptionId != null && binder.isBinderAlive) enqueueUnsubscribe(subscriptionId)
        failedListener?.onFailure(reason)
    }

    private fun enqueueUnsubscribe(subscriptionId: String) {
        try {
            cleanupExecutor.execute {
                try {
                    val result = host.unsubscribe(subscriptionId)
                    if (result != PpgContractV1.OK) {
                        Log.w(TAG, "PPG failure cleanup rejected with code=$result")
                    }
                } catch (_: RemoteException) {
                    Log.w(TAG, "PPG failure cleanup failed because host disconnected")
                }
            }
        } catch (_: RejectedExecutionException) {
            Log.w(TAG, "PPG cleanup executor is closed")
        }
    }

    companion object {
        private const val TAG = "WearTAK-CRM-PPG"
    }
}
