# WearTAK CRM Plugin

CRM (Compensatory Reserve Measure) is a standalone Wear OS plugin that preserves Core CRM's
measurement screen and inference workflow while receiving raw PPG only from a negotiated
WearTAK host stream.

## Purpose and Capabilities

- Displays CRM score, reserve band, signal status, sample progress, and a 5-second PPG waveform.
- Processes 500-sample, 100 Hz windows with Core's 5-second window, 1-second inference cadence,
  preprocessing, quality checks, and bundled TFLite model.
- Uses the Core defaults: disabled, poor maximum 33, moderate maximum 66.
- Provides hosted status/measurement cards with Open CRM and Edit thresholds actions, plus hosted
  settings for enablement and threshold configuration.
- Emits host status events using sensor key `crm_status_v1`; it does not dispatch alerts or CoT.
- Logs measurement sessions to the app-specific Documents directory.

The app and measurement screen can launch without ATAK Companion. PPG measurement requires a
WearTAK host that implements the optional PPG v1 contract; this plugin never opens a sensor or
falls back to local sensor access.

## Status

CRM sources, UI, model, settings, and host bridge are being extracted from Core. The combined
SDK AAR is pinned to a verified TAK.gov Linux CI hash. Local Windows builds remain disabled
because no verified Windows artifact or runner is available.

## Point of Contacts

Maintainer and support contacts: undocumented.

## Ports Required

No plugin-owned network listener or Internet permission. Host communication uses Android Binder
through the signature-protected WearTAK plugin service.

## Equipment Required

- Wear OS watch running Android API 30 or later.
- For PPG measurements and hosted settings, a compatible WearTAK host implementing plugin SDK
  transport and optional PPG v1.
- For CI, JDK 17 and the TAK.gov GitLab runner environment.

## Equipment Supported

The manifest requires watch hardware and targets API 34 with minimum API 30. Device models and
firmware have not been certified in this repository.

## Compilation

Canonical validation uses TAK.gov CI. The pipeline builds the combined SDK AAR from pinned SDK
source plus the verified Core transport overlay, verifies its hash, then runs unit tests and the
debug build. No local build has been used for this extraction.

## Developer Notes

### Host integration contract

The plugin negotiates `com.weartak.plugin.sdk.ppg.v1` using `PluginInitParcel.hostExtras`:

- `com.weartak.plugin.ppg.version`: integer version `1`.
- `com.weartak.plugin.ppg.v1`: `IPpgHostV1` Binder.

The host owns the tracker and streams ordered `PpgBatchV1` data. The plugin checks subscription
identity, batch sequence, state values, bounded batch size, and host death. It processes callbacks
through a bounded serial queue and unsubscribes on stop, disable, detach, or failure.
`STATE_NOT_WORN` and `STATE_UNAVAILABLE` remain subscribed for recovery; `ERROR`, callback
validation failures, and delivery-queue overflow terminate the session and unsubscribe.

CRM warning events use `IWearTakHostCallback.onSensorEvent` with `sensorKey=crm_status_v1`,
monotonic per-connection sequence, and Bundle values:

- `status`: `ACCEPTED`, `REJECTED`, `ERROR`, `NOT_WORN`, or `IDLE`.
- `value`: `Double` in `[0,1]`, present only for `ACCEPTED`.
- `enabled`: `Boolean`.

Accepted event timestamps use inference update time so Core can expire stale readings. The host
classifies warning bands using plugin settings and actual worn state. The plugin does not call
`requestAlert`.

### Settings and migration

Plugin-owned settings are `enable_crm` (false), `crm_poor_max` (33), and `crm_moderate_max` (66).
Threshold fields are sent as SDK `NUMBER` / Bundle `Double` values and stored only after integral
range validation. `crm_legacy_migration_complete` is persisted and returned in settings values.
Legacy migration applies all three values and the marker atomically; any explicit setting edit
also marks migration complete. A repeated or incomplete migration is rejected without modifying
stored values.

### SDK and release pipeline

`ci/plugin-sdk.json` pins SDK commit `53ce7ae49740978c46f9c3f40896139b3286935d`, its source tree,
Core transport overlay hashes, and the Linux combined-AAR SHA-256 verified by Core CI
[pipeline 1883692](https://git.tak.gov/core/weartak-core/wearos-tak-civ/-/pipelines/1883692)
and [job 8012322](https://git.tak.gov/core/weartak-core/wearos-tak-civ/-/jobs/8012322). No
verified Windows hash exists; both Gradle configuration and SDK preparation fail closed on
Windows with guidance to use TAK.gov Linux CI. CI preparation follows the pinned Core builder
and verifies the separate host-services SDK contract before Gradle consumes either AAR.

Protected default-branch CI signs the release APK with WearTAK production credentials, verifies
the signer, and publishes the versioned CRM APK to the configured rolling release mirror:
`WearTAK-Plugin-CRM-<version>-p<pipeline>.apk`. Signing credentials and GitHub release tokens
must remain masked/protected CI variables; never commit them.

## License

Original project code is licensed under the GNU General Public License, version 3 only
(`GPL-3.0-only`). See [LICENSE](LICENSE) for the complete GPLv3 text.

This declaration covers original project code only. Preserve third-party licensing obligations
for dependencies, SDK artifacts, and the bundled model.
