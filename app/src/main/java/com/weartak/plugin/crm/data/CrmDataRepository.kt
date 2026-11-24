package com.weartak.plugin.crm.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

class CrmDataRepository {
    private val _latestCrmInference = MutableStateFlow(CrmInferenceState.idle())
    val latestCrmInference: StateFlow<CrmInferenceState> = _latestCrmInference.asStateFlow()

    private val _rawPpgSamples = MutableSharedFlow<CrmRawPpgSample>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val rawPpgSamples: SharedFlow<CrmRawPpgSample> = _rawPpgSamples.asSharedFlow()

    private val _thresholdRanges = MutableStateFlow(CrmThresholdRanges())
    val thresholdRanges: StateFlow<CrmThresholdRanges> = _thresholdRanges.asStateFlow()

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _isDeviceWorn = MutableStateFlow(false)
    val isDeviceWorn: StateFlow<Boolean> = _isDeviceWorn.asStateFlow()

    private val _samplingState = MutableStateFlow(CrmSamplingState.IDLE)
    val samplingState: StateFlow<CrmSamplingState> = _samplingState.asStateFlow()

    fun applySettings(settings: CrmPluginSettings) {
        _isEnabled.value = settings.enabled
        _thresholdRanges.value = sanitizeCrmThresholdRanges(settings.poorMax, settings.moderateMax)
    }

    fun updateDeviceWorn(worn: Boolean) {
        _isDeviceWorn.value = worn
    }

    fun updateSamplingState(state: CrmSamplingState) {
        _samplingState.value = state
    }

    fun tryPublish(state: CrmInferenceState): Boolean = _latestCrmInference.tryEmit(state)

    fun tryPublishRawPpgSample(sample: CrmRawPpgSample): Boolean = _rawPpgSamples.tryEmit(sample)

    enum class CrmSamplingState {
        IDLE,
        STARTING,
        SAMPLING,
        STOPPING,
        NOT_WORN,
        UNAVAILABLE,
        ERROR,
    }
}

data class CrmRawPpgSample(
    val uptimeMillis: Long,
    val rawValue: Float?,
    val sourceAvailable: Boolean,
    val deviceWorn: Boolean,
    val emittedAtMillis: Long = System.currentTimeMillis(),
    val reason: String? = null,
)

enum class CrmInferenceStatus {
    IDLE,
    ACCEPTED,
    REJECTED,
    ERROR,
    NOT_WORN,
}

data class CrmInferenceState(
    val status: CrmInferenceStatus = CrmInferenceStatus.IDLE,
    val value: Float? = null,
    val updatedAtMillis: Long = 0L,
    val quality: Float? = null,
    val rejectionReason: String? = null,
    val sourceSampleCount: Int = 0,
    val inputWindowDurationMs: Long = 0L,
    val inferenceLatencyMs: Long? = null,
) {
    val normalizedValue: Float?
        get() = value?.coerceIn(0f, 1f)

    val percentValue: Float?
        get() = normalizedValue?.times(100f)

    val displayScore: Int?
        get() = percentValue?.roundToInt()?.coerceIn(0, 100)

    companion object {
        fun idle(updatedAtMillis: Long = System.currentTimeMillis()) = CrmInferenceState(
            status = CrmInferenceStatus.IDLE,
            updatedAtMillis = updatedAtMillis,
        )
    }
}

data class CrmThresholdRanges(
    val poorMax: Int = DEFAULT_CRM_POOR_MAX_THRESHOLD,
    val moderateMax: Int = DEFAULT_CRM_MODERATE_MAX_THRESHOLD,
) {
    fun stateFor(score: Int): CrmDisplayState = when (score.coerceIn(0, 100)) {
        in 0..poorMax -> CrmDisplayState.POOR
        in (poorMax + 1)..moderateMax -> CrmDisplayState.MODERATE
        else -> CrmDisplayState.GOOD
    }
}

enum class CrmDisplayState {
    POOR,
    MODERATE,
    GOOD,
}

data class CrmPluginSettings(
    val enabled: Boolean = false,
    val poorMax: Int = DEFAULT_CRM_POOR_MAX_THRESHOLD,
    val moderateMax: Int = DEFAULT_CRM_MODERATE_MAX_THRESHOLD,
    val legacyMigrationComplete: Boolean = false,
)

const val DEFAULT_CRM_POOR_MAX_THRESHOLD = 33
const val DEFAULT_CRM_MODERATE_MAX_THRESHOLD = 66

fun sanitizeCrmThresholdRanges(
    poorMax: Int,
    moderateMax: Int,
): CrmThresholdRanges {
    val cleanPoorMax = poorMax.coerceIn(0, 98)
    val cleanModerateMax = moderateMax.coerceIn(cleanPoorMax + 1, 100)
    return CrmThresholdRanges(cleanPoorMax, cleanModerateMax)
}
