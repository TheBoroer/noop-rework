package com.noop.data

/**
 * Decoded streams to persist in one transaction. Android mirror of the Swift `Streams`
 * struct (Packages/WhoopProtocol/Sources/WhoopProtocol/Streams.swift) carrying the rows
 * for a single flush/backfill chunk. All `ts` values are wall-clock unix seconds (Long).
 *
 * The protocol/decoder layer builds one of these (deviceId stamped at insert time, not
 * stored on the per-row sample models , it is supplied to [WhoopRepository.insert]).
 *
 * Extracted out of WhoopRepository.kt (Task 8 data hoist): this shape plus the device-agnostic
 * decoded rows below are pure (no Room/Android/JVM dependency), so they hoisted to commonMain ahead
 * of [WhoopRepository] itself, which at the time still had androidMain-only usage
 * (java.time/System.currentTimeMillis/org.json/Context). [WhoopRepository] has since moved to
 * commonMain too (commit 9c90a983); only its Context-owning factory wiring stays androidMain now.
 */
data class StreamBatch(
    val hr: List<HrRow> = emptyList(),
    val rr: List<RrRow> = emptyList(),
    val events: List<EventEntry> = emptyList(),
    val battery: List<BatteryRow> = emptyList(),
    val spo2: List<Spo2Row> = emptyList(),
    val skinTemp: List<SkinTempRow> = emptyList(),
    val resp: List<RespRow> = emptyList(),
    val gravity: List<GravityRow> = emptyList(),
    val steps: List<StepRow> = emptyList(),
    /**
     * The strap's OWN band sleep_state per record (#175), carried verbatim off @81's high nibble. Optional
     * signal (only 5/MG v18 records emit it; a WHOOP 4.0 leaves it empty), consumed by the H7 re-onset
     * CONFIRM guard and shown as a Deep Timeline track. Never overrides the derived stage.
     */
    val sleepState: List<SleepStateRow> = emptyList(),
    /** HR derived from the WHOOP 5/MG v26 optical PPG waveform (autocorrelation). (#156) */
    val ppgHr: List<PpgHrRow> = emptyList(),
    /**
     * #547: how many historical records this batch DROPPED because their timestamp was implausible
     * (older than 2023-11 or more than a day ahead of now) , a bad strap clock/flash artefact. A
     * diagnostic counter only, NOT decoded data, so it is deliberately excluded from [isEmpty]. The
     * Backfiller surfaces it once per session via its existing strap-log seam so a bad-clock strap is
     * visible in a shared log. Defaulted so every existing constructor/copy call site is unchanged.
     */
    val droppedImplausibleTs: Int = 0,
    /**
     * #324: oldest/newest OWN-timestamp among the dropped records — the poisoned-range epoch span, in
     * the strap's own claimed unix seconds. On a bad-clock strap this is the wrong base its RTC jumped
     * to (e.g. both years ahead, #928), and logging the span tells a future-clock strap from a stale
     * one at a glance. null when nothing was dropped. Diagnostic only, excluded from [isEmpty];
     * defaulted so every existing constructor/copy call site is unchanged.
     */
    val droppedOldestTs: Long? = null,
    val droppedNewestTs: Long? = null,
) {
    val isEmpty: Boolean
        get() = hr.isEmpty() && rr.isEmpty() && events.isEmpty() && battery.isEmpty() &&
            spo2.isEmpty() && skinTemp.isEmpty() && resp.isEmpty() && gravity.isEmpty() &&
            steps.isEmpty() && sleepState.isEmpty() && ppgHr.isEmpty()
}

// Device-agnostic decoded rows (deviceId attached when inserted). Mirror Streams.swift shapes.
data class HrRow(val ts: Long, val bpm: Int)
data class RrRow(val ts: Long, val rrMs: Int)

/** payloadJSON is the deterministic sorted-keys JSON for the remaining parsed fields. */
data class EventEntry(val ts: Long, val kind: String, val payloadJSON: String)
data class BatteryRow(val ts: Long, val soc: Double?, val mv: Int?, val charging: Boolean? = null)
data class Spo2Row(val ts: Long, val red: Int, val ir: Int)
data class SkinTempRow(val ts: Long, val raw: Int)
/**
 * Cumulative u16 step/motion counter at [ts] (WHOOP5 step_motion_counter@57). deviceId attached on insert. (#78)
 * [activityClass] is the per-record activity-class enum from @63 (community finding #316): 0=still, 1=walk,
 * 2=run; null when the byte was 0xFF/invalid or absent. Optional + defaulted so existing call sites and the
 * persisted store (which carries only ts/counter today) are unchanged.
 */
data class StepRow(val ts: Long, val counter: Int, val activityClass: Int? = null)
/**
 * The strap's OWN @81 high-nibble band sleep_state at [ts] (0 wake/1 still/2 asleep/3 up), decoded and
 * streamed but dropped at storage until #175. deviceId attached on insert. Swift `SleepStateSample`.
 */
data class SleepStateRow(val ts: Long, val state: Int)
data class RespRow(val ts: Long, val raw: Int)
data class GravityRow(val ts: Long, val x: Double, val y: Double, val z: Double)
/** HR derived from the v26 PPG waveform: [ts] window-centre sec, [bpm], [conf] in 0…1. (#156) */
data class PpgHrRow(val ts: Long, val bpm: Int, val conf: Double)

/** Count of rows ACTUALLY inserted per stream (mirrors WhoopStore.insert return tuple). */
data class InsertCounts(
    val hr: Int = 0,
    val rr: Int = 0,
    val events: Int = 0,
    val battery: Int = 0,
    val spo2: Int = 0,
    val skinTemp: Int = 0,
    val steps: Int = 0,
    val resp: Int = 0,
    val gravity: Int = 0,
)
