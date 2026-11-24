package com.weartak.plugin.crm.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleMachine
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleTransition

class WearTakPluginCrmService : Service() {
    private val lifecycle = PluginLifecycleMachine()
    private val binder by lazy { CrmPluginBinder(lifecycle) }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind action=${intent?.action}")
        logTransition(lifecycle.dispatch(PluginLifecycleEvent.BIND_ATTACHED))
        return binder
    }

    override fun onDestroy() {
        CrmPluginRuntime.detachHost()
        logTransition(lifecycle.dispatch(PluginLifecycleEvent.RESET))
        super.onDestroy()
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

    companion object {
        private const val TAG = "WearTAK-CRM-PluginService"
    }
}
