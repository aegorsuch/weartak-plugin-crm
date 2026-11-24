package com.weartak.plugin.crm.service

import android.content.Context
import android.os.Bundle
import com.weartak.plugin.sdk.model.SettingsApplyMode
import com.weartak.plugin.sdk.model.SettingsFieldTypes
import com.weartak.plugin.sdk.validation.ContractValidationResult
import com.weartak.plugin.sdk.validation.SettingsApplyValidator
import com.weartak.plugin.sdk.validation.SettingsSchemaContractValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrmSettingsStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("crm_plugin_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun schemaValidatesAndHostValidatorAcceptsCompleteMigrationPatch() {
        val schema = CrmSettingsStore.schema
        val validation = SettingsSchemaContractValidator.validate(schema)
        assertTrue("Schema validation failed: $validation", validation is ContractValidationResult.Ok)
        assertEquals(
            mapOf(
                CrmSettingsStore.FIELD_ENABLED to SettingsFieldTypes.TOGGLE,
                CrmSettingsStore.FIELD_POOR_MAX to SettingsFieldTypes.NUMBER,
                CrmSettingsStore.FIELD_MODERATE_MAX to SettingsFieldTypes.NUMBER,
                CrmSettingsStore.FIELD_MIGRATION_COMPLETE to SettingsFieldTypes.TOGGLE,
            ),
            schema.fields.associate { it.fieldId to it.fieldType },
        )

        val result = SettingsApplyValidator.applySettingsWithResult(
            schema = schema,
            previous = CrmSettingsStore(context).values(),
            incoming = migrationPatch(enabled = true, poorMax = 30.0, moderateMax = 72.0),
            hostSettings = null,
            applyMode = SettingsApplyMode.PARTIAL_PATCH,
        )
        assertTrue("Host validation failed: ${result.errors}", result.success)
        assertTrue(result.values.getBoolean(CrmSettingsStore.FIELD_MIGRATION_COMPLETE))
        assertTrue(result.values.getBoolean(CrmSettingsStore.FIELD_ENABLED))
        assertEquals(30.0, result.values.getDouble(CrmSettingsStore.FIELD_POOR_MAX), 0.0)
        assertEquals(72.0, result.values.getDouble(CrmSettingsStore.FIELD_MODERATE_MAX), 0.0)
    }

    @Test
    fun migrationPersistsSettingsAndMarkerAsOnePatch() {
        val result = CrmSettingsStore(context).apply(
            migrationPatch(enabled = true, poorMax = 30.0, moderateMax = 72.0),
            SettingsApplyMode.PARTIAL_PATCH,
            hostSettings = null,
        )

        assertTrue(result.success)
        assertTrue(result.values.getBoolean(CrmSettingsStore.FIELD_MIGRATION_COMPLETE))
        assertTrue(result.values.getBoolean(CrmSettingsStore.FIELD_ENABLED))
        assertEquals(30.0, result.values.getDouble(CrmSettingsStore.FIELD_POOR_MAX), 0.0)
        assertEquals(72.0, result.values.getDouble(CrmSettingsStore.FIELD_MODERATE_MAX), 0.0)

        val persisted = CrmSettingsStore(context).current()
        assertTrue(persisted.legacyMigrationComplete)
        assertTrue(persisted.enabled)
        assertEquals(30, persisted.poorMax)
        assertEquals(72, persisted.moderateMax)
    }

    @Test
    fun markerCannotBeEditedSeparatelyOrReverted() {
        val store = CrmSettingsStore(context)
        val incomplete = store.apply(
            Bundle().apply { putBoolean(CrmSettingsStore.FIELD_MIGRATION_COMPLETE, true) },
            SettingsApplyMode.PARTIAL_PATCH,
            hostSettings = null,
        )
        assertFalse(incomplete.success)
        assertFalse(store.current().legacyMigrationComplete)

        assertTrue(store.apply(
            migrationPatch(enabled = true, poorMax = 30.0, moderateMax = 72.0),
            SettingsApplyMode.PARTIAL_PATCH,
            hostSettings = null,
        ).success)
        val revert = store.apply(
            Bundle().apply { putBoolean(CrmSettingsStore.FIELD_MIGRATION_COMPLETE, false) },
            SettingsApplyMode.PARTIAL_PATCH,
            hostSettings = null,
        )
        assertFalse(revert.success)
        assertTrue(store.current().legacyMigrationComplete)
    }

    @Test
    fun explicitEditMarksMigrationCompleteAndRepeatedMigrationDoesNotOverwrite() {
        val store = CrmSettingsStore(context)
        val editResult = store.apply(
            Bundle().apply { putBoolean(CrmSettingsStore.FIELD_ENABLED, true) },
            SettingsApplyMode.PARTIAL_PATCH,
            hostSettings = null,
        )

        assertTrue(editResult.success)
        assertTrue(editResult.values.getBoolean(CrmSettingsStore.FIELD_MIGRATION_COMPLETE))

        val repeatedMigration = store.apply(
            migrationPatch(enabled = false, poorMax = 20.0, moderateMax = 80.0),
            SettingsApplyMode.PARTIAL_PATCH,
            hostSettings = null,
        )

        assertFalse(repeatedMigration.success)
        assertTrue(repeatedMigration.values.getBoolean(CrmSettingsStore.FIELD_ENABLED))
        assertEquals(33.0, repeatedMigration.values.getDouble(CrmSettingsStore.FIELD_POOR_MAX), 0.0)
        assertEquals(66.0, repeatedMigration.values.getDouble(CrmSettingsStore.FIELD_MODERATE_MAX), 0.0)
    }

    @Test
    fun invalidMigrationDoesNotPersistAnyFieldOrMarker() {
        val store = CrmSettingsStore(context)

        val result = store.apply(
            migrationPatch(enabled = true, poorMax = 99.0, moderateMax = 100.0),
            SettingsApplyMode.PARTIAL_PATCH,
            hostSettings = null,
        )

        assertFalse(result.success)
        assertFalse(result.values.getBoolean(CrmSettingsStore.FIELD_MIGRATION_COMPLETE))
        assertFalse(result.values.getBoolean(CrmSettingsStore.FIELD_ENABLED))
        assertEquals(33.0, result.values.getDouble(CrmSettingsStore.FIELD_POOR_MAX), 0.0)
        assertEquals(66.0, result.values.getDouble(CrmSettingsStore.FIELD_MODERATE_MAX), 0.0)
    }

    private fun migrationPatch(
        enabled: Boolean,
        poorMax: Double,
        moderateMax: Double,
    ) = Bundle().apply {
        putBoolean(CrmSettingsStore.FIELD_ENABLED, enabled)
        putDouble(CrmSettingsStore.FIELD_POOR_MAX, poorMax)
        putDouble(CrmSettingsStore.FIELD_MODERATE_MAX, moderateMax)
        putBoolean(CrmSettingsStore.FIELD_MIGRATION_COMPLETE, true)
    }
}
