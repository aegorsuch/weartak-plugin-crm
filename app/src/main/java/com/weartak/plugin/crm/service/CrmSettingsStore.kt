package com.weartak.plugin.crm.service

import android.content.Context
import android.os.Bundle
import com.weartak.plugin.crm.data.CrmPluginSettings
import com.weartak.plugin.crm.data.DEFAULT_CRM_MODERATE_MAX_THRESHOLD
import com.weartak.plugin.crm.data.DEFAULT_CRM_POOR_MAX_THRESHOLD
import com.weartak.plugin.sdk.model.SettingsApplyMode
import com.weartak.plugin.sdk.model.SettingsApplyResultParcel
import com.weartak.plugin.sdk.model.SettingsFieldParcel
import com.weartak.plugin.sdk.model.SettingsFieldTypes
import com.weartak.plugin.sdk.model.SettingsSchemaParcel
import com.weartak.plugin.sdk.model.SettingsValidationErrorParcel
import com.weartak.plugin.sdk.model.HostSettingsParcel
import com.weartak.plugin.sdk.validation.ContractValidationResult
import com.weartak.plugin.sdk.validation.SettingsApplyValidator
import com.weartak.plugin.sdk.validation.SettingsSchemaContractValidator

class CrmSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun current(): CrmPluginSettings = synchronized(lock) { readSettingsLocked() }

    fun values(): Bundle = synchronized(lock) { toBundle(readSettingsLocked()) }

    fun apply(
        incoming: Bundle?,
        applyMode: Int,
        hostSettings: HostSettingsParcel?,
    ): SettingsApplyResultParcel = synchronized(lock) {
        val previousSettings = readSettingsLocked()
        val previousValues = toBundle(previousSettings)
        if (incoming == null) {
            return@synchronized failure(applyMode, previousValues, "settings", "null_bundle", "Settings bundle is null.")
        }

        val hasMigrationMarker = incoming.containsKey(FIELD_MIGRATION_COMPLETE)
        if (hasMigrationMarker && incoming.get(FIELD_MIGRATION_COMPLETE) != true) {
            return@synchronized failure(
                applyMode,
                previousValues,
                FIELD_MIGRATION_COMPLETE,
                "invalid_migration_marker",
                "Legacy CRM migration marker may only be set by a complete migration.",
            )
        }
        val migrationRequested = hasMigrationMarker
        if (migrationRequested && previousSettings.legacyMigrationComplete) {
            return@synchronized failure(
                applyMode,
                previousValues,
                FIELD_MIGRATION_COMPLETE,
                "migration_already_complete",
                "Legacy CRM migration was already completed; no settings were changed.",
            )
        }

        val explicitFields = incoming.keySet().intersect(EDITABLE_FIELDS)
        if (migrationRequested && explicitFields != EDITABLE_FIELDS) {
            return@synchronized failure(
                applyMode,
                previousValues,
                FIELD_MIGRATION_COMPLETE,
                "incomplete_migration",
                "Migration must include all CRM settings in one patch.",
            )
        }

        val validated = SettingsApplyValidator.applySettingsWithResult(
            schema = schema,
            previous = previousValues,
            incoming = incoming,
            hostSettings = hostSettings,
            applyMode = applyMode,
        )
        if (!validated.success) return@synchronized validated

        val merged = validated.values
        val enabled = merged.getBoolean(FIELD_ENABLED)
        val poorValue = merged.getDouble(FIELD_POOR_MAX)
        val moderateValue = merged.getDouble(FIELD_MODERATE_MAX)
        if (!poorValue.isFinite() || poorValue % 1.0 != 0.0 || poorValue !in 0.0..98.0) {
            return@synchronized failure(
                applyMode,
                previousValues,
                FIELD_POOR_MAX,
                "invalid_threshold",
                "Poor threshold must be an integer from 0 through 98.",
            )
        }
        val poorMax = poorValue.toInt()
        if (!moderateValue.isFinite() || moderateValue % 1.0 != 0.0 ||
            moderateValue !in (poorMax + 1).toDouble()..100.0
        ) {
            return@synchronized failure(
                applyMode,
                previousValues,
                FIELD_MODERATE_MAX,
                "invalid_threshold",
                "Moderate threshold must be an integer above poor threshold and no greater than 100.",
            )
        }
        val moderateMax = moderateValue.toInt()
        val migrationComplete = previousSettings.legacyMigrationComplete ||
            migrationRequested ||
            (applyMode != SettingsApplyMode.HOST_DRIVEN_RECOMPUTE && explicitFields.isNotEmpty())

        val committed = preferences.edit()
            .putBoolean(FIELD_ENABLED, enabled)
            .putInt(FIELD_POOR_MAX, poorMax)
            .putInt(FIELD_MODERATE_MAX, moderateMax)
            .putBoolean(FIELD_MIGRATION_COMPLETE, migrationComplete)
            .commit()
        if (!committed) {
            return@synchronized failure(
                applyMode,
                previousValues,
                "settings",
                "persistence_failed",
                "CRM settings could not be persisted.",
            )
        }

        val currentValues = toBundle(
            CrmPluginSettings(
                enabled = enabled,
                poorMax = poorMax,
                moderateMax = moderateMax,
                legacyMigrationComplete = migrationComplete,
            ),
        )
        validated.copy(
            values = currentValues,
            anyValueChanged = validated.anyValueChanged ||
                previousSettings.legacyMigrationComplete != migrationComplete,
        )
    }

    private fun readSettingsLocked() = CrmPluginSettings(
        enabled = preferences.getBoolean(FIELD_ENABLED, false),
        poorMax = preferences.getInt(FIELD_POOR_MAX, DEFAULT_CRM_POOR_MAX_THRESHOLD),
        moderateMax = preferences.getInt(FIELD_MODERATE_MAX, DEFAULT_CRM_MODERATE_MAX_THRESHOLD),
        legacyMigrationComplete = preferences.getBoolean(FIELD_MIGRATION_COMPLETE, false),
    )

    private fun toBundle(settings: CrmPluginSettings) = Bundle().apply {
        putBoolean(FIELD_ENABLED, settings.enabled)
        putDouble(FIELD_POOR_MAX, settings.poorMax.toDouble())
        putDouble(FIELD_MODERATE_MAX, settings.moderateMax.toDouble())
        putBoolean(FIELD_MIGRATION_COMPLETE, settings.legacyMigrationComplete)
    }

    private fun failure(
        applyMode: Int,
        previous: Bundle,
        fieldId: String,
        code: String,
        message: String,
    ) = SettingsApplyResultParcel(
        success = false,
        applyMode = applyMode,
        values = Bundle(previous),
        errors = listOf(SettingsValidationErrorParcel(fieldId, code, message)),
    )

    companion object {
        const val FIELD_ENABLED = "enable_crm"
        const val FIELD_POOR_MAX = "crm_poor_max"
        const val FIELD_MODERATE_MAX = "crm_moderate_max"
        const val FIELD_MIGRATION_COMPLETE = "crm_legacy_migration_complete"

        private const val PREFERENCES_NAME = "crm_plugin_settings"
        private val EDITABLE_FIELDS = setOf(FIELD_ENABLED, FIELD_POOR_MAX, FIELD_MODERATE_MAX)

        val schema: SettingsSchemaParcel = SettingsSchemaParcel(
            title = "CRM",
            fields = listOf(
                SettingsFieldParcel(
                    fieldId = FIELD_ENABLED,
                    label = "Enable CRM",
                    fieldType = SettingsFieldTypes.TOGGLE,
                    required = true,
                    defaultValueLiteral = "false",
                ),
                SettingsFieldParcel(
                    fieldId = FIELD_POOR_MAX,
                    label = "Poor maximum",
                    fieldType = SettingsFieldTypes.NUMBER,
                    required = true,
                    defaultValueLiteral = DEFAULT_CRM_POOR_MAX_THRESHOLD.toString(),
                ),
                SettingsFieldParcel(
                    fieldId = FIELD_MODERATE_MAX,
                    label = "Moderate maximum",
                    fieldType = SettingsFieldTypes.NUMBER,
                    required = true,
                    defaultValueLiteral = DEFAULT_CRM_MODERATE_MAX_THRESHOLD.toString(),
                ),
                SettingsFieldParcel(
                    fieldId = FIELD_MIGRATION_COMPLETE,
                    label = "Legacy migration complete",
                    fieldType = SettingsFieldTypes.TOGGLE,
                    required = true,
                    defaultValueLiteral = "false",
                ),
            ),
        ).let { raw ->
            when (val result = SettingsSchemaContractValidator.validate(raw)) {
                is ContractValidationResult.Ok -> result.value
                is ContractValidationResult.Err -> error("Invalid CRM settings schema: ${result.issues}")
            }
        }
    }
}
