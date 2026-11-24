package com.weartak.plugin.crm

import android.app.Application
import com.weartak.plugin.crm.data.CrmDataRepository
import com.weartak.plugin.crm.service.CrmPluginRuntime
import com.weartak.plugin.crm.service.CrmSettingsStore

class CrmApplication : Application() {
    lateinit var settingsStore: CrmSettingsStore
        private set

    lateinit var crmRepository: CrmDataRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settingsStore = CrmSettingsStore(this)
        crmRepository = CrmDataRepository().apply { applySettings(settingsStore.current()) }
        CrmPluginRuntime.initialize(this, settingsStore, crmRepository)
    }
}
