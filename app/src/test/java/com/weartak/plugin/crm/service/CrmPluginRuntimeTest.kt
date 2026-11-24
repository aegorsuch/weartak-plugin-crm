package com.weartak.plugin.crm.service

import android.app.Application
import com.weartak.plugin.sdk.MainUiActionContract
import com.weartak.plugin.sdk.model.MainUiCardTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        }.associateBy { it.content.getString(MainUiActionContract.ACTION_ID) }

        assertEquals(
            "crm.open",
            actionCards["crm.open"]?.content?.getString(MainUiActionContract.ACTION_ID),
        )
        assertEquals(
            "Open CRM",
            actionCards["crm.open"]?.content?.getString(MainUiActionContract.ACTION_LABEL),
        )
        assertEquals(
            "crm.thresholds",
            actionCards["crm.thresholds"]?.content?.getString(MainUiActionContract.ACTION_ID),
        )
        assertEquals(
            "Edit thresholds",
            actionCards["crm.thresholds"]?.content?.getString(MainUiActionContract.ACTION_LABEL),
        )
        assertTrue(actionCards.containsKey("crm.open"))
        assertTrue(actionCards.containsKey("crm.thresholds"))
    }
}
