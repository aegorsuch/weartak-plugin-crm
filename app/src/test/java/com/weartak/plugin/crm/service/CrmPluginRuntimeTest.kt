package com.weartak.plugin.crm.service

import android.app.Application
import com.weartak.plugin.sdk.model.MainUiCardTypes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CrmPluginRuntimeTest {
    @Test
    fun hostedMainUiExposesReserveAndThresholdActions() {
        val actionCards = CrmPluginRuntime.getMainUiSchema().cards.filter {
            it.cardType == MainUiCardTypes.ACTION
        }.map { it.cardId }.toSet()

        assertEquals(setOf("crm-open", "crm-thresholds"), actionCards)
    }
}
