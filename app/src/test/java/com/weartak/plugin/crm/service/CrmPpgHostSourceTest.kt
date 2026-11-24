package com.weartak.plugin.crm.service

import android.app.Application
import com.weartak.plugin.sdk.ppg.v1.IPpgCallbackV1
import com.weartak.plugin.sdk.ppg.v1.IPpgHostV1
import com.weartak.plugin.sdk.ppg.v1.PpgContractV1
import com.weartak.plugin.sdk.ppg.v1.PpgStateV1
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CrmPpgHostSourceTest {
    @Test
    fun errorStateFailsAndUnsubscribesSubscription() {
        val host = RecordingPpgHost()
        val source = CrmPpgHostSource(host)
        val failure = CountDownLatch(1)
        var failureReason: String? = null

        assertEquals(
            PpgContractV1.OK,
            source.subscribe(recordingListener(onFailureListener = {
                failureReason = it
                failure.countDown()
            })),
        )

        host.callback!!.onState(
            PpgStateV1(
                host.subscriptionId!!,
                PpgContractV1.ERROR,
                PpgContractV1.SOURCE_SAMSUNG_PPG_ON_DEMAND,
                PpgContractV1.WORN,
                "sensor_error",
            ),
        )

        assertTrue(failure.await(1, TimeUnit.SECONDS))
        assertEquals("sensor_error", failureReason)
        assertTrue(host.unsubscribe.await(1, TimeUnit.SECONDS))
        assertEquals(1, host.unsubscribeCount)
        assertFalse(source.canRetrySubscribe())
        source.close()
    }

    @Test
    fun unavailableSubscribeCanBeRetriedWithoutRecreatingHostSource() {
        val host = RecordingPpgHost().apply {
            subscribeResult = PpgContractV1.UNAVAILABLE
        }
        val source = CrmPpgHostSource(host)
        val listener = recordingListener()

        assertEquals(PpgContractV1.UNAVAILABLE, source.subscribe(listener))
        assertTrue(source.canRetrySubscribe())

        host.subscribeResult = PpgContractV1.OK
        assertEquals(PpgContractV1.OK, source.subscribe(listener))
        assertFalse(source.canRetrySubscribe())
        assertEquals(2, host.subscribeCount)

        source.unsubscribe()
        source.close()
    }

    @Test
    fun unavailableAndNotWornStatesRemainRecoverable() {
        val host = RecordingPpgHost()
        val source = CrmPpgHostSource(host)
        val stateCount = CountDownLatch(2)
        var failureCount = 0

        assertEquals(
            PpgContractV1.OK,
            source.subscribe(recordingListener(
                stateListener = { stateCount.countDown() },
                onFailureListener = { failureCount += 1 },
            )),
        )

        host.callback!!.onState(
            PpgStateV1(
                host.subscriptionId!!,
                PpgContractV1.STATE_UNAVAILABLE,
                PpgContractV1.SOURCE_SAMSUNG_PPG_ON_DEMAND,
                PpgContractV1.WORN,
                "sensor_unavailable",
            ),
        )
        host.callback!!.onState(
            PpgStateV1(
                host.subscriptionId!!,
                PpgContractV1.STATE_NOT_WORN,
                PpgContractV1.SOURCE_SAMSUNG_PPG_ON_DEMAND,
                PpgContractV1.NOT_WORN,
                "not_worn",
            ),
        )

        assertTrue(stateCount.await(1, TimeUnit.SECONDS))
        assertEquals(0, failureCount)
        assertEquals(0, host.unsubscribeCount)
        source.unsubscribe()
        assertEquals(1, host.unsubscribeCount)
        source.close()
    }

    private fun recordingListener(
        stateListener: (PpgStateV1) -> Unit = {},
        onFailureListener: (String) -> Unit = {},
    ) = object : PpgSource.Listener {
        override fun onState(state: PpgStateV1) = stateListener.invoke(state)
        override fun onSamples(batch: com.weartak.plugin.sdk.ppg.v1.PpgBatchV1) = Unit
        override fun onFailure(reason: String) = onFailureListener.invoke(reason)
    }

    private class RecordingPpgHost : IPpgHostV1.Stub() {
        var subscriptionId: String? = null
            private set
        var callback: IPpgCallbackV1? = null
            private set
        var unsubscribeCount = 0
            private set
        var subscribeCount = 0
            private set
        var subscribeResult = PpgContractV1.OK
        val unsubscribe = CountDownLatch(1)

        override fun subscribe(subscriptionId: String, callback: IPpgCallbackV1): Int {
            subscribeCount += 1
            this.subscriptionId = subscriptionId
            this.callback = callback
            return subscribeResult
        }

        override fun unsubscribe(subscriptionId: String): Int {
            assertEquals(this.subscriptionId, subscriptionId)
            unsubscribeCount += 1
            unsubscribe.countDown()
            return PpgContractV1.OK
        }
    }
}
