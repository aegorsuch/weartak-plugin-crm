package com.weartak.plugin.crm.service

import android.os.Bundle
import android.util.Log
import com.weartak.plugin.crm.BuildConfig
import com.weartak.plugin.sdk.WearTakPluginContract
import com.weartak.plugin.sdk.aidl.IWearTakHostCallback
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.binder.PluginBinderErrorReporter
import com.weartak.plugin.sdk.binder.PluginBinderGuard
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleMachine
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleTransition
import com.weartak.plugin.sdk.model.PluginCapabilitiesParcel
import com.weartak.plugin.sdk.model.PluginErrorCodes
import com.weartak.plugin.sdk.model.PluginErrorParcel
import com.weartak.plugin.sdk.model.PluginInfoParcel
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.model.PluginLifecycleState
import com.weartak.plugin.sdk.model.PluginMainUiSchemaParcel
import com.weartak.plugin.sdk.model.PluginStatusParcel
import com.weartak.plugin.sdk.model.SettingsApplyMode
import com.weartak.plugin.sdk.model.SettingsApplyResultParcel
import com.weartak.plugin.sdk.model.SettingsSchemaParcel
import com.weartak.plugin.sdk.validation.ContractValidationResult
import com.weartak.plugin.sdk.validation.PluginMetadataValidator
import com.weartak.plugin.sdk.v2.aidl.IWearTakPluginHostControlV2
import com.weartak.plugin.sdk.v3.aidl.IWearTakPluginHostControlV3

class CrmPluginBinder(
    private val lifecycle: PluginLifecycleMachine,
) : IWearTakPlugin.Stub() {
    private var hostCallback: IWearTakHostCallback? = null
    private val guard by lazy { PluginBinderGuard(TAG, lifecycle) }

    override fun getPluginInfo(): PluginInfoParcel {
        log("getPluginInfo")
        return CrmPluginRuntime.getPluginInfo()
    }

    override fun getCapabilities(): PluginCapabilitiesParcel {
        log("getCapabilities")
        return CrmPluginRuntime.getCapabilities()
    }

    override fun getApiVersion(): Int = WearTakPluginContract.CONTRACT_API_VERSION

    override fun getStatus(): PluginStatusParcel = guard.query(
        operation = "getStatus",
        fallback = PluginStatusParcel(lifecycle.snapshot(), message = "shutdown"),
    ) { CrmPluginRuntime.getStatus(lifecycle.snapshot()) }

    override fun initializePlugin(init: PluginInitParcel?, pluginControl: IWearTakPluginControl?) = Unit

    override fun initializePluginV2(
        init: PluginInitParcel?,
        pluginControl: IWearTakPluginControl?,
        pluginControlV2: IWearTakPluginHostControlV2?,
    ) {
        log("initializePluginV2 host=${init?.hostPackageName} uid=${init?.hostUid}")
        guard.command(
            operation = "initializePluginV2",
            onShutdown = { emitIllegalState("initializePluginV2") },
        ) {
            val validation = init?.let {
                PluginMetadataValidator.validateInitAgainstPlugin(
                    it,
                    declaredPluginId = BuildConfig.APPLICATION_ID,
                )
            }
            when (validation) {
                is ContractValidationResult.Err -> {
                    Log.w(TAG, "init validation failed: ${validation.issues}")
                    CrmPluginRuntime.detachHost()
                }
                is ContractValidationResult.Ok -> CrmPluginRuntime.attachHost(init, hostCallback)
                null -> CrmPluginRuntime.detachHost()
            }
            logTransition(lifecycle.dispatch(PluginLifecycleEvent.INITIALIZED))
            CrmPluginRuntime.updateLifecycle(lifecycle.snapshot())
        }
    }

    override fun initializePluginV3(
        init: PluginInitParcel?,
        pluginControl: IWearTakPluginControl?,
        pluginControlV2: IWearTakPluginHostControlV2?,
        pluginControlV3: IWearTakPluginHostControlV3?,
    ) = initializePluginV2(init, pluginControl, pluginControlV2)

    override fun startPlugin() {
        log("startPlugin")
        guard.command(
            operation = "startPlugin",
            onShutdown = { emitIllegalState("startPlugin") },
        ) {
            logTransition(lifecycle.dispatch(PluginLifecycleEvent.HOST_STARTED))
            CrmPluginRuntime.updateLifecycle(lifecycle.snapshot())
        }
    }

    override fun stopPlugin() {
        log("stopPlugin")
        guard.command(
            operation = "stopPlugin",
            onShutdown = { emitIllegalState("stopPlugin") },
        ) {
            CrmPluginRuntime.stopSampling()
            logTransition(lifecycle.dispatch(PluginLifecycleEvent.HOST_STOPPED))
            CrmPluginRuntime.updateLifecycle(lifecycle.snapshot())
        }
    }

    override fun shutdownPlugin() {
        log("shutdownPlugin")
        guard.command(
            operation = "shutdownPlugin",
            onShutdown = { emitIllegalState("shutdownPlugin") },
        ) {
            logTransition(lifecycle.dispatch(PluginLifecycleEvent.HOST_SHUTDOWN))
            hostCallback = null
            CrmPluginRuntime.detachHost()
            CrmPluginRuntime.updateLifecycle(lifecycle.snapshot())
        }
    }

    override fun registerHostCallback(callback: IWearTakHostCallback?) {
        log("registerHostCallback")
        guard.command(
            operation = "registerHostCallback",
            onShutdown = { emitIllegalState("registerHostCallback") },
        ) {
            hostCallback = callback
            CrmPluginRuntime.setHostCallback(callback)
            logTransition(lifecycle.dispatch(PluginLifecycleEvent.HOST_CALLBACK_REGISTERED))
        }
    }

    override fun unregisterHostCallback(callback: IWearTakHostCallback?) {
        log("unregisterHostCallback")
        guard.command(
            operation = "unregisterHostCallback",
            onShutdown = { emitIllegalState("unregisterHostCallback") },
        ) {
            if (hostCallback === callback) {
                hostCallback = null
                CrmPluginRuntime.setHostCallback(null)
            }
        }
    }

    override fun getSettingsSchema(): SettingsSchemaParcel = CrmPluginRuntime.getSettingsSchema()

    override fun getSettingsValues(): Bundle = CrmPluginRuntime.getSettingsValues()

    override fun onHostSettingsChanged(hostSettings: com.weartak.plugin.sdk.model.HostSettingsParcel?) {
        CrmPluginRuntime.updateHostSettings(hostSettings)
    }

    override fun applySettingsWithResult(
        values: Bundle?,
        applyMode: Int,
    ): SettingsApplyResultParcel = CrmPluginRuntime.applySettings(values, applyMode)

    override fun applySettings(values: Bundle?) {
        val result = CrmPluginRuntime.applySettings(values, SettingsApplyMode.PARTIAL_PATCH)
        if (!result.success) {
            Log.w(TAG, "applySettings rejected: ${result.errors}")
        }
    }

    override fun getMainUiSchema(): PluginMainUiSchemaParcel = CrmPluginRuntime.getMainUiSchema()

    override fun getMainUiState(): Bundle = CrmPluginRuntime.getMainUiState()

    override fun invokeMainUiAction(actionId: String?, args: Bundle?) {
        if (!CrmPluginRuntime.invokeMainUiAction(actionId)) {
            Log.w(TAG, "Unknown or failed main UI action: $actionId")
        }
    }

    private fun emitIllegalState(operation: String) {
        PluginBinderErrorReporter.report(
            tag = TAG,
            operation = operation,
            error = PluginErrorParcel(
                code = PluginErrorCodes.ILLEGAL_STATE,
                message = "$operation ignored after shutdown",
                fatal = false,
            ),
            callback = hostCallback,
        )
    }

    private fun logTransition(transition: PluginLifecycleTransition) {
        when (transition) {
            is PluginLifecycleTransition.Moved ->
                Log.d(TAG, "lifecycle ${transition.previous} -> ${transition.state}")
            is PluginLifecycleTransition.Rejected ->
                Log.w(TAG, "lifecycle rejected: ${transition.reason}")
            is PluginLifecycleTransition.Unchanged -> Unit
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
    }

    companion object {
        private const val TAG = "WearTAK-CRM-Plugin"
    }
}
