package com.weartak.plugin.crm.service

import android.app.Application
import com.weartak.plugin.sdk.MainUiActionContract
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
    fun hostedMainUiExposesActionForOpeningReserveScreen() {
        val actionCard = CrmPluginRuntime.getMainUiSchema().cards.single {
            it.cardType == MainUiCardTypes.ACTION
        }

        assertEquals(
            "crm.open",
            actionCard.content.getString(MainUiActionContract.ACTION_ID),
        )
        assertEquals(
            "Open CRM",
            actionCard.content.getString(MainUiActionContract.ACTION_LABEL),
        )
    }
}
