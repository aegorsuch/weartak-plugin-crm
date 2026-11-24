package com.weartak.plugin.crm.service

import android.app.Application
import android.os.Bundle
import com.weartak.plugin.crm.BuildConfig
import com.weartak.plugin.crm.data.CrmDataRepository
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleMachine
import com.weartak.plugin.sdk.model.MainUiCardTypes
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.model.PluginLifecycleState
import com.weartak.plugin.sdk.ppg.v1.IPpgCallbackV1
import com.weartak.plugin.sdk.ppg.v1.IPpgHostV1
import com.weartak.plugin.sdk.ppg.v1.PpgBatchV1
import com.weartak.plugin.sdk.ppg.v1.PpgContractV1
import com.weartak.plugin.sdk.ppg.v1.PpgStateV1
import com.weartak.plugin.sdk.v2.WearTakPluginV2Contract
import com.weartak.plugin.sdk.v2.aidl.IWearTakPluginHostControlV2
import com.weartak.plugin.sdk.v2.model.CepRuleStreamEventParcel
import com.weartak.plugin.sdk.v2.model.DetectionEventParcel
import com.weartak.plugin.sdk.v2.model.TaskCommandCompletionParcel
import com.weartak.plugin.sdk.v2.model.TaskCommandProgressParcel
import com.weartak.plugin.sdk.v2.model.TaskCommandSubmitParcel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CrmPluginRuntimeTest {
    @Test
    fun v2InitializationNegotiatesAndSubscribesToHostPpg() {
        val context = RuntimeEnvironment.getApplication()
        val repository = CrmDataRepository()
        CrmPluginRuntime.initialize(context, CrmSettingsStore(context), repository)
        val lifecycle = PluginLifecycleMachine(PluginLifecycleState.BOUND)
        val binder = CrmPluginBinder(lifecycle)
        val ppgHost = RecordingPpgHost()
        val extras = Bundle().apply {
            putInt(PpgContractV1.EXTRA_VERSION, PpgContractV1.VERSION)
            putBinder(PpgContractV1.EXTRA_CONTROL, ppgHost.asBinder())
        }

        try {
            assertEquals(WearTakPluginV2Contract.CONTRACT_API_VERSION, binder.getApiVersion())
            binder.initializePluginV2(
                PluginInitParcel("com.weartak.host", 1000, BuildConfig.APPLICATION_ID, extras),
                null,
                UnusedV2Control(),
            )
            assertEquals(PluginLifecycleState.READY, lifecycle.snapshot())
            assertEquals(CrmDataRepository.CrmSamplingState.IDLE, repository.samplingState.value)
            val source = CrmPluginRuntime.currentPpgSource()
            assertTrue(source is CrmPpgHostSource)
            assertEquals(PpgContractV1.OK, source!!.subscribe(object : PpgSource.Listener {
                override fun onState(state: PpgStateV1) = Unit
                override fun onSamples(batch: PpgBatchV1) = Unit
                override fun onFailure(reason: String) = Unit
            }))
            assertTrue(ppgHost.subscribed)
        } finally {
            CrmPluginRuntime.detachHost()
        }
        assertTrue(ppgHost.unsubscribed.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun hostedMainUiExposesReserveAndThresholdActions() {
        val actionCards = CrmPluginRuntime.getMainUiSchema().cards.filter {
            it.cardType == MainUiCardTypes.ACTION
        }.map { it.cardId }.toSet()

        assertEquals(setOf("crm-open", "crm-thresholds"), actionCards)
    }

    private class RecordingPpgHost : IPpgHostV1.Stub() {
        var subscribed = false
        val unsubscribed = CountDownLatch(1)

        override fun subscribe(subscriptionId: String, callback: IPpgCallbackV1): Int {
            subscribed = true
            return PpgContractV1.OK
        }

        override fun unsubscribe(subscriptionId: String): Int {
            unsubscribed.countDown()
            return PpgContractV1.OK
        }
    }

    private class UnusedV2Control : IWearTakPluginHostControlV2.Stub() {
        override fun applyCepRuleEvent(event: CepRuleStreamEventParcel) = unexpected()
        override fun publishDetectionEvent(event: DetectionEventParcel) = unexpected()
        override fun submitTaskCommand(request: TaskCommandSubmitParcel) = unexpected()
        override fun reportTaskCommandProgress(progress: TaskCommandProgressParcel) = unexpected()
        override fun reportTaskCommandCompletion(completion: TaskCommandCompletionParcel) = unexpected()
        override fun getHostCapabilities() = unexpected()

        private fun unexpected(): Nothing = throw AssertionError("CRM does not use V2 event families")
    }
}
