@file:OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)

package com.noop.ble

import com.juul.kable.Characteristic
import com.juul.kable.NotConnectedException
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import com.noop.protocol.CommandNumber
import com.noop.protocol.DeviceFamily
import com.noop.protocol.Framing
import com.noop.protocol.ParsedFrame
import com.noop.protocol.Reassembler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Phase 2c-2 Task 10 (flow 2: connect/session + hello/version/clock), ported from
 * `Strand/BLE/BLEManager.swift`.
 *
 * Layering mirrors the Swift file:
 *
 *  - [MarginalRadioDetector], [PostBondTimeoutLoopDetector], [BondRefusalGiveUp] — the three pure,
 *    unit-testable reconnect-policy value types at the top of BLEManager.swift (lines 24-71, 87-137,
 *    150-205). Ported field-for-field; Kotlin classes instead of Swift mutating structs.
 *  - [ClockSync] — the SET_CLOCK payload builders (BLEManager.swift:3322-3356, issue #120).
 *  - [ReconnectPolicy] — one holder for the three detectors plus the reconnect timing constants,
 *    so the coordinator layer owns exactly one policy object per strap.
 *  - [BleSession] — the thin Kable wiring: connect, bond trick, notify subscription, the
 *    once-per-connection hello/version/clock handshake, and the reassembled inbound frame stream.
 *
 * NOT ported here (later flows per the plan): realtime arming/standard-HR fallback execution
 * (flow 4), backfill/offload (flow 3 — `beginBackfill`, GET_DATA_RANGE watchdog, keep-alive), and
 * the scan/adopt/preferred-pin orchestration that lives in `connectCore` above the per-peripheral
 * work (that stays in the coordinator; scanning itself is Task 9's [BleScanner]).
 */

/**
 * Detects a radio that buckles right after the R10/R11 realtime arm burst, so the caller can fall
 * back to standard-HR-only. Port of `MarginalRadioDetector` (BLEManager.swift:24-71).
 *
 * The tell is a CONNECTION TIMEOUT that lands shortly after we armed realtime: arm → die → rescan →
 * arm → die. One drop is noise; [tripThreshold] (2) in a row right after arming is the radio
 * buckling under the burst. A drop more than [quickTimeoutWindowSeconds] after arming is unrelated
 * and must NOT be blamed on the arm (that would mis-trip a good radio whose link merely flaps
 * later).
 */
class MarginalRadioDetector(
    /** Consecutive arm-then-quick-timeout cycles before falling back to standard-HR-only. */
    val tripThreshold: Int = 2,
    /** A timeout only counts as "right after arming" if it lands within this window (seconds). */
    val quickTimeoutWindowSeconds: Double = 20.0,
) {
    var consecutiveArmTimeouts: Int = 0
        private set

    /** True once tripped: the next connect should skip the R10/R11 arm and run standard-HR-only. */
    var tripped: Boolean = false
        private set

    /**
     * A connection ended. [wasArmed] = we had armed R10/R11 this connection; [secondsSinceArm] =
     * how long after arming the link ended (null if we never armed); [timedOut] = the drop looks
     * like a connection timeout (vs an intentional disconnect, a bond reset, etc.). Returns true if
     * THIS event tripped the fallback (a freshly-crossed threshold), so the caller can log/surface
     * it exactly once.
     */
    fun connectionEnded(wasArmed: Boolean, secondsSinceArm: Double?, timedOut: Boolean): Boolean {
        // Only a timeout that lands within the window after we actually armed the burst is evidence
        // the radio choked on the arm. Anything else (clean session that later flapped, non-timeout
        // error, never armed) breaks the streak — a single healthy spell should clear prior suspicion.
        val armCausedTimeout = wasArmed && timedOut &&
            (secondsSinceArm?.let { it <= quickTimeoutWindowSeconds } ?: false)
        if (!armCausedTimeout) {
            consecutiveArmTimeouts = 0
            return false
        }
        consecutiveArmTimeouts += 1
        if (!tripped && consecutiveArmTimeouts >= tripThreshold) {
            tripped = true
            return true // freshly tripped — caller logs/surfaces once
        }
        return false
    }

    /**
     * Clear all suspicion: a clean session is flowing, or the user explicitly re-requested the full
     * stream. Lets a transient radio hiccup recover instead of permanently pinning standard-HR mode.
     */
    fun reset() {
        consecutiveArmTimeouts = 0
        tripped = false
    }
}

/**
 * Detects the post-bond quick-timeout loop (issue #617): the strap "connects" every ~3s, bonds,
 * then drops with a CONNECTION TIMEOUT ~1s later, endlessly draining the battery. Port of
 * `PostBondTimeoutLoopDetector` (BLEManager.swift:87-137). Once tripped, the caller surfaces the
 * EXISTING forget-and-re-pair guide instead of watching a silent loop.
 *
 * The window (8s) is generous vs the radio detector's 20s... inverted: the loop's signature is a
 * near-immediate (~1s) drop, but pre-loop links can limp a few seconds before timing out.
 */
class PostBondTimeoutLoopDetector(
    /** Consecutive bond-then-quick-timeout cycles before surfacing the re-pair guide. */
    val tripThreshold: Int = 2,
    /** A timeout only counts as "right after bonding" if it lands within this window (seconds). */
    val quickTimeoutWindowSeconds: Double = 8.0,
) {
    var consecutiveBondTimeouts: Int = 0
        private set

    /** True once tripped: the caller has surfaced (or should surface) the re-pair guide. */
    var tripped: Boolean = false
        private set

    /**
     * A connection ended. [wasBonded] = the link reached a genuine encrypted bond this connection;
     * [secondsSinceBond] = how long after bonding the link ended (null if we never bonded);
     * [timedOut] = the drop looks like a connection timeout. Returns true if THIS event tripped the
     * loop (a freshly-crossed threshold), so the caller can surface the guide exactly once.
     */
    fun connectionEnded(wasBonded: Boolean, secondsSinceBond: Double?, timedOut: Boolean): Boolean {
        // Only a timeout that lands within the window after we actually bonded is evidence of the
        // loop. Anything else (never bonded, non-timeout close, a drop long after a healthy bond)
        // breaks the streak — a single healthy spell should clear prior suspicion.
        val bondThenQuickTimeout = wasBonded && timedOut &&
            (secondsSinceBond?.let { it <= quickTimeoutWindowSeconds } ?: false)
        if (!bondThenQuickTimeout) {
            consecutiveBondTimeouts = 0
            return false
        }
        consecutiveBondTimeouts += 1
        if (!tripped && consecutiveBondTimeouts >= tripThreshold) {
            tripped = true
            return true // freshly tripped — caller surfaces the re-pair guide once
        }
        return false
    }

    /** Clear all suspicion: a clean session is flowing, or the user explicitly disconnected. */
    fun reset() {
        consecutiveBondTimeouts = 0
        tripped = false
    }
}

/**
 * Decides when a strap that keeps REFUSING the encrypted bond has refused enough times that
 * hammering it further is pointless. Port of `BondRefusalGiveUp` (BLEManager.swift:150-205,
 * issues #747 / #750):
 *
 *  - #747 PAUSE: after [giveUpThreshold] consecutive refusals the auto-reconnect should STOP
 *    re-kicking (it can't bond without the user freeing the strap / re-pairing).
 *  - #750 EPITAPH: at the same moment, emit ONE summary "epitaph" line recording how the bond
 *    attempt died (the streak + an opaque, install-local id) — no PII (no MAC, no serial).
 *
 * The streak accumulates across the reconnect loop (a disconnect does NOT reset it) and is cleared
 * only by a genuine bond or an explicit user reconnect.
 */
class BondRefusalGiveUp(
    /**
     * Consecutive bond refusals before pausing auto-reconnect + writing the epitaph. 5 (not 2,
     * where the pairing HINT already shows): the hint asks the user to act; we give them several
     * reconnect cycles to do it before we stop hammering.
     */
    val giveUpThreshold: Int = 5,
) {
    var refusals: Int = 0
        private set

    /**
     * True once [giveUpThreshold] is reached: auto-reconnect should pause and the epitaph has been
     * (or should be) written. Stays true until [reset] so the pause holds across the loop.
     */
    var gaveUp: Boolean = false
        private set

    /**
     * Record one bond refusal. Returns true if THIS refusal freshly crossed the give-up threshold
     * (so the caller pauses the reconnect + writes the epitaph exactly once).
     */
    fun recordRefusal(): Boolean {
        refusals += 1
        if (!gaveUp && refusals >= giveUpThreshold) {
            gaveUp = true
            return true
        }
        return false
    }

    /** Clear the streak: a genuine bond landed, or the user explicitly reconnected. */
    fun reset() {
        refusals = 0
        gaveUp = false
    }

    companion object {
        /**
         * #750: the one-line bond-refusal EPITAPH. Byte-identical to the Swift/Android twins
         * (BLEManager.swift:186-188) — records the streak + an OPAQUE install-local id only, never
         * a MAC or serial. No em-dash (project rule).
         */
        fun epitaphLine(refusals: Int, opaqueId: String): String =
            "Bond epitaph: the strap [$opaqueId] refused the encrypted bond ${refusals}x in a row " +
                "with no successful bond - giving up auto-reconnect to stop hammering it. It is " +
                "almost certainly held by the official WHOOP app or a stale phone pairing. Free it " +
                "(close the WHOOP app, put the strap in pairing mode, forget it in Bluetooth " +
                "settings) then reconnect in NOOP."

        /** #747: the honest user-facing hint shown when auto-reconnect pauses. No em-dash. */
        fun pausedHint(): String =
            "NOOP stopped retrying because your strap keeps refusing to pair. It is likely still " +
                "held by the official WHOOP app, or your phone is holding an old pairing. Close " +
                "the WHOOP app, put the strap in pairing mode (tap until the LEDs flash blue), " +
                "and if it is listed in your Bluetooth settings choose Forget This Device. Then " +
                "tap Connect to try again."

        /**
         * #750: a short OPAQUE token from a platform-local peripheral UUID for the epitaph. The id
         * is per-install (NOT the hardware MAC); keep only its first 8 hex chars so the token is
         * stable within a log but carries no device-identifying PII.
         */
        fun opaqueId(fromLocalUuid: String): String =
            fromLocalUuid.replace("-", "").lowercase().take(8)
    }
}

/**
 * Minimum seconds since the bond-loop pause tripped (or since the last probe re-stamped it)
 * before another salvage probe may fire. Port of `BLEManager.bondLoopSalvageFloorSeconds`
 * (BLEManager.swift:919, #78 hole-4). 10 minutes: long enough that a still-held strap sees a
 * handful of bounded attempts per day, short enough that a strap the user freed reconnects on
 * the next natural app open.
 */
const val BOND_LOOP_SALVAGE_FLOOR_SECONDS: Long = 10 * 60

/**
 * Pure gate for the one-shot bond-loop salvage probe (Swift `shouldSalvageProbe`,
 * BLEManager.swift:925-932, #78 hole-4): probe ONLY while the pause is latched, with no live
 * link, no user teardown in force, and at least [BOND_LOOP_SALVAGE_FLOOR_SECONDS] since the
 * pause tripped (or since the previous probe re-stamped it). Null seconds = no trip timestamp
 * = never probe. Pure so the never-hammer contract is pinned by unit tests without a
 * CoreBluetooth seam.
 */
fun shouldSalvageProbe(
    pausedForBondLoop: Boolean,
    connected: Boolean,
    intentionalDisconnect: Boolean,
    secondsSincePauseTripped: Long?,
): Boolean {
    if (!pausedForBondLoop || connected || intentionalDisconnect) return false
    val s = secondsSincePauseTripped ?: return false
    return s >= BOND_LOOP_SALVAGE_FLOOR_SECONDS
}

/**
 * SET_CLOCK / GET_CLOCK payload forms (issue #120). Port of `BLEManager.setClockPayload` /
 * `setClockPayloadLegacy` / `sendSetClockBothForms` (BLEManager.swift:3322-3356).
 *
 * SET_CLOCK is MANDATORY: a strap that misses the set keeps an invalid RTC and stops banking sensor
 * data to flash entirely, which surfaces as endless console-only syncs and no sleep/recovery (#120).
 */
object ClockSync {

    /**
     * SET_CLOCK(10) payload, 8-byte form `[seconds u32 LE][4 zero]` — latched by newer WHOOP 4
     * firmware and the only hardware-validated form on WHOOP 5/MG.
     */
    fun setClockPayload(nowUnixSeconds: Long): ByteArray {
        val now = nowUnixSeconds and 0xFFFFFFFFL
        return byteArrayOf(
            (now and 0xFF).toByte(), ((now shr 8) and 0xFF).toByte(),
            ((now shr 16) and 0xFF).toByte(), ((now shr 24) and 0xFF).toByte(),
            0, 0, 0, 0,
        )
    }

    /**
     * SET_CLOCK(10) payload, legacy 9-byte form `[seconds u32 LE][5 zero]` required by WHOOP 4
     * fw 41.17.x, which ignores the 8-byte form. On newer firmware this form is ack'd but NOT
     * latched, so sending it after the 8-byte form is a no-op there — both forms carry the same
     * seconds, so whichever one latches sets the same time (#120).
     */
    fun setClockPayloadLegacy(nowUnixSeconds: Long): ByteArray =
        setClockPayload(nowUnixSeconds) + byteArrayOf(0)

    /**
     * Every SET_CLOCK payload form this family is known to accept, in send order — the port of
     * `sendSetClockBothForms()`: WHOOP 4 gets both the 8-byte and legacy 9-byte forms (each a no-op
     * on the other firmware); WHOOP 5/MG keeps its single hardware-validated 8-byte send (the
     * 9-byte form is unverified on that family).
     */
    fun setClockPayloads(family: DeviceFamily, nowUnixSeconds: Long): List<ByteArray> = when (family) {
        DeviceFamily.WHOOP4 -> listOf(setClockPayload(nowUnixSeconds), setClockPayloadLegacy(nowUnixSeconds))
        DeviceFamily.WHOOP5 -> listOf(setClockPayload(nowUnixSeconds))
    }

    /**
     * GET_CLOCK payload forms to send, in order. GET_CLOCK's payload length is firmware-specific,
     * exactly like SET_CLOCK's: newer WHOOP 4 firmware answers the EMPTY form and ignores `[0x00]`,
     * while fw 41.17.x answers `[0x00]` and ignores the empty form (#120). Send both on WHOOP 4 —
     * the strap answers whichever its firmware accepts and a `clockRef == nil` correlation guard
     * upstream makes a second reply a no-op. WHOOP 5/MG uses the single empty form
     * (BLEManager.swift:3231).
     */
    fun getClockPayloads(family: DeviceFamily): List<ByteArray> = when (family) {
        DeviceFamily.WHOOP4 -> listOf(byteArrayOf(), byteArrayOf(0x00))
        DeviceFamily.WHOOP5 -> listOf(byteArrayOf())
    }
}

/** What a completed connection teardown freshly tripped, so the caller surfaces each exactly once. */
data class ConnectionEndEvents(
    /** Marginal-radio fallback freshly tripped: next connect should be standard-HR-only. */
    val marginalRadioTripped: Boolean,
    /** Post-bond timeout loop freshly tripped (#617): surface the forget-and-re-pair guide. */
    val postBondLoopTripped: Boolean,
)

/**
 * The reconnect policy for one strap: the three pure detectors plus the timing constants the
 * reconnect loop uses. The coordinator owns one instance per strap and feeds it from the session's
 * teardown path, exactly like BLEManager's `marginalRadio` / `postBondLoop` / `bondGiveUp` fields.
 */
class ReconnectPolicy(
    val marginalRadio: MarginalRadioDetector = MarginalRadioDetector(),
    val postBondLoop: PostBondTimeoutLoopDetector = PostBondTimeoutLoopDetector(),
    val bondGiveUp: BondRefusalGiveUp = BondRefusalGiveUp(),
) {
    /**
     * #747: true while auto-reconnect is paused because the strap kept refusing the bond
     * ([BondRefusalGiveUp.gaveUp]). Swift twin: `autoReconnectPausedForBondLoop`.
     */
    val autoReconnectPaused: Boolean get() = bondGiveUp.gaveUp

    /**
     * A connection ended (Swift `didDisconnectPeripheral`, BLEManager.swift:2752-2790). [timedOut]
     * must already be "the OS classified the drop as a connection timeout AND it was not an
     * intentional disconnect" — Swift reads `CBError.connectionTimeout && !intentionalDisconnect`
     * (BLEManager.swift:2773-2777); Kable callers derive it from the disconnect status.
     */
    fun connectionEnded(
        wasArmed: Boolean,
        secondsSinceArm: Double?,
        wasBonded: Boolean,
        secondsSinceBond: Double?,
        timedOut: Boolean,
    ): ConnectionEndEvents = ConnectionEndEvents(
        marginalRadioTripped = marginalRadio.connectionEnded(wasArmed, secondsSinceArm, timedOut),
        postBondLoopTripped = postBondLoop.connectionEnded(wasBonded, secondsSinceBond, timedOut),
    )

    /** A genuine encrypted bond landed: clears the refusal streak (and the #747 pause). */
    fun noteGenuineBond() = bondGiveUp.reset()

    /** The user explicitly disconnected / reconnected: clear ALL suspicion. */
    fun userReset() {
        marginalRadio.reset()
        postBondLoop.reset()
        bondGiveUp.reset()
    }

    companion object {
        /** Auto-rescan delay after an unexpected drop ("rescanning in 3s", BLEManager.swift:2865). */
        const val RECONNECT_DELAY_SECONDS: Double = 3.0

        /**
         * Scan fallback: if the preferred/persisted strap yields no discovery within this window,
         * widen the scan (BLEManager.swift:524, `scanFallbackDelaySeconds`).
         */
        const val SCAN_FALLBACK_DELAY_SECONDS: Double = 8.0
    }
}

/**
 * Classify a write/subscribe failure as CoreBluetooth "insufficient encryption/authentication"
 * (Swift `isInsufficientAuthError`, BLEManager.swift:1125-1141).
 *
 * #78 hole-1 parity: classify by ATT error CODE first (insufficientEncryption = 15,
 * insufficientAuthentication = 5) because Foundation LOCALIZES CoreBluetooth error strings — a
 * string-only check silently never matches on a non-English device. Kable surfaces the NSError as
 * an exception whose message embeds `Error Domain=CBATTErrorDomain Code=15 "Encryption is
 * insufficient."`, so the code check is a domain+code substring match over the message/cause
 * chain. The English string match is kept as a FALLBACK only, never replaced: some CoreBluetooth
 * paths surface plain NSErrors outside the CBATTError domain, and the code check must be additive
 * so English-device detection can't regress. Pure so a unit test pins both routes without a
 * CoreBluetooth seam.
 */
fun isInsufficientAuthError(t: Throwable): Boolean {
    var cur: Throwable? = t
    var depth = 0
    while (cur != null && depth < 8) { // bounded walk; cause chains can self-reference
        val msg = cur.message
        if (msg != null) {
            // Code-first, locale-proof route (CBATTError insufficientEncryption/Authentication).
            if (msg.contains("CBATTErrorDomain") &&
                (msg.contains("Code=15") || msg.contains("Code=5"))
            ) return true
            // English string fallback (#78: additive only). First letter dropped so it matches
            // regardless of capitalization ("Encryption is insufficient." vs mid-sentence).
            if (msg.contains("ncryption is insufficient") ||
                msg.contains("uthentication is insufficient")
            ) return true
        }
        cur = cur.cause
        depth += 1
    }
    return false
}

/** One reassembled inbound frame, tagged with the notify characteristic it arrived on. */
data class SessionFrame(
    /** Lowercase UUID string of the notify characteristic the fragment(s) arrived on. */
    val characteristicUuid: String,
    /** The complete raw frame (envelope + payload + CRC trailer). */
    val raw: ByteArray,
    /** Decoded view of [raw] (`Framing.parseFrame`). */
    val parsed: ParsedFrame,
) {
    // ByteArray needs manual equality; frames are compared in tests/logs only.
    override fun equals(other: Any?): Boolean =
        other is SessionFrame && characteristicUuid == other.characteristicUuid &&
            raw.contentEquals(other.raw) && parsed == other.parsed

    override fun hashCode(): Int =
        31 * (31 * characteristicUuid.hashCode() + raw.contentHashCode()) + parsed.hashCode()
}

/**
 * Kable-backed WHOOP connection/session (flow 2): connect, bond, subscribe, then the
 * once-per-connection hello/version/clock handshake, exposing reassembled frames as a flow.
 *
 * Port map (Strand/BLE/BLEManager.swift):
 *  - `connect(model:)` / `connectCore` (921-1020) → [connect]. The scan/adopt/preferred-pin logic
 *    stays in the coordinator; this class starts from an already-discovered [Peripheral].
 *  - `didConnect` (2689-2745) → [connect]: bumps [connectGeneration] (#711 — a monotonic
 *    per-connection token so timers/cleared state can never leak across reconnect cycles) and
 *    resets the per-connection [Reassembler] (fresh per connection, `connectCore`).
 *  - characteristic discovery + notify setup (3025-3075) → [connect]/`startObserving`. WHOOP 4
 *    subscribes at discovery time; WHOOP 5/MG subscribes only after the CLIENT_HELLO write is
 *    acked — the strap rejects subscriptions with "Authentication is insufficient" until the link
 *    is encrypted (issue #17). Kable's `observe()` subscribes on first collect, so ordering the
 *    collect launch after the hello write reproduces that.
 *  - Bond trick: WHOOP 4 writes one acked GET_BATTERY_LEVEL command frame (3035-3037); WHOOP 5/MG
 *    writes the static CLIENT_HELLO `.withResponse` (3045-3055) — a single confirmed write makes
 *    CoreBluetooth run just-works bonding.
 *  - hello/version/clock (3195-3290) → [runHandshakeOnce], guarded by `connectHandshakeDone`
 *    exactly like Swift (3249, and the #-guard at :606): `didWriteValueFor` re-fires on EVERY
 *    later `.withResponse` write (every SEND_HISTORICAL, every HISTORY_END ack), and without the
 *    guard those re-entries re-sent hello/SET_CLOCK at the strap DURING the offload and stopped
 *    it from streaming type-47. The handshake must run at most once per connection.
 *
 * Connection timeouts: Swift has no explicit connect timer — CoreBluetooth itself classifies a
 * drop as `CBError.connectionTimeout`, which the caller maps to `timedOut` for
 * [ReconnectPolicy.connectionEnded]. Same here: Kable surfaces the platform status in
 * `State.Disconnected`; the coordinator/harness derives `timedOut` from it.
 */
class BleSession(
    private val peripheral: Peripheral,
    val family: DeviceFamily,
    /** Wall-clock seam so tests can pin SET_CLOCK payloads. */
    private val nowUnixSeconds: () -> Long = { Clock.System.now().epochSeconds },
    private val log: (String) -> Unit = {},
) {
    /**
     * #711: monotonic per-connection token (Swift `connectGeneration`, BLEManager.swift:560,
     * 2710-2723). Bumped on every [connect]; deferred work captures the value and re-checks it so
     * a timer armed on connection N can never act on connection N+1 (e.g. the post-bond-loop
     * "survived the window" reset only fires if the SAME continuous connection is still up).
     */
    var connectGeneration: Int = 0
        private set

    /**
     * Once-per-connection handshake guard (Swift `connectHandshakeDone`, BLEManager.swift:608,
     * 3249). Reset on connect and on disconnect (2816); latched by [runHandshakeOnce].
     */
    var connectHandshakeDone: Boolean = false
        private set

    /** Command sequence number, u8 wraparound, monotonic within the session (Swift `seq`). */
    private var seq = 0

    /** Fresh per connection (`connectCore`): no stale bytes carry across a reconnect. */
    private var reassembler = Reassembler(family)

    /** Serialises reassembler feeds — notify collectors run concurrently per characteristic. */
    private val reassemblerLock = Mutex()

    /** Monotonic mark of the moment the bond write was acked; drives post-bond-loop classification. */
    private var bondedAtMark: TimeSource.Monotonic.ValueTimeMark? = null

    /** Monotonic mark of the last realtime arm (flow 4 sets it via [noteRealtimeArmed]). */
    private var armedAtMark: TimeSource.Monotonic.ValueTimeMark? = null

    private var observeJobs: List<Job> = emptyList()

    private val frameFlow = MutableSharedFlow<SessionFrame>(extraBufferCapacity = 256)

    /** Every reassembled inbound frame from every subscribed notify characteristic. */
    val frames: SharedFlow<SessionFrame> = frameFlow

    /** Kable connection state, re-exposed for the coordinator/harness. */
    val state: StateFlow<State> get() = peripheral.state

    /** Reconnect policy detectors for this strap (fed by [noteConnectionEnded]). */
    val reconnectPolicy = ReconnectPolicy()

    private val serviceUuid = Uuid.parse(family.serviceUuidString)

    private val commandCharacteristic: Characteristic =
        characteristicOf(serviceUuid, Uuid.parse(family.commandCharacteristicUuidString))

    /**
     * The family's notify characteristics (…0003 command-response, …0004 event, …0005 data, plus
     * …0007 on 5/MG). The WHOOP 4 standard profiles (heart rate 2A37, battery 2A19) are flow-4
     * realtime concerns and are NOT subscribed here.
     */
    private val notifyCharacteristics: List<Pair<String, Characteristic>> =
        family.characteristicUuidStrings
            .filter { it != family.commandCharacteristicUuidString }
            .map { it to characteristicOf(serviceUuid, Uuid.parse(it)) }

    /**
     * Connect and run the WHOOP-faithful session bring-up. Suspends until the handshake commands
     * have been written (replies stream into [frames] asynchronously). [scope] hosts the notify
     * collectors; cancel it (or call [disconnect]) to tear the session down.
     */
    suspend fun connect(scope: CoroutineScope) {
        connectGeneration += 1 // #711
        connectHandshakeDone = false
        reassembler = Reassembler(family) // fresh per connection (connectCore)
        bondedAtMark = null
        armedAtMark = null

        log("connect gen=$connectGeneration family=$family")
        peripheral.connect()

        when (family) {
            DeviceFamily.WHOOP4 -> {
                // Discovery-time notify setup (BLEManager.swift:3060-3075).
                startObserving(scope)
                // Bond trick (3035-3037): ONE acked command write triggers just-works bonding.
                // GET_BATTERY_LEVEL is harmless and always answered.
                bondRaceRetry("bond write (GET_BATTERY_LEVEL)") {
                    send(CommandNumber.GET_BATTERY_LEVEL, byteArrayOf(0x00), WriteType.WithResponse)
                }
                bondedAtMark = TimeSource.Monotonic.markNow()
                log("bond write acked (GET_BATTERY_LEVEL) — verifying notify path")
                // The write ack proves the BOND, not the notify pipe: the CCCD subscribe from
                // startObserving() may still be in flight, and a handshake fired before it lands
                // loses every reply (incl. the GET_CLOCK correlation → zero realtime HR rows).
                verifyNotifyPathLive()
                runHandshakeOnce()
            }
            DeviceFamily.WHOOP5 -> {
                // CLIENT_HELLO is a complete framed command written raw, `.withResponse` so the OS
                // runs just-works bonding AND we learn when it is acked (issue #17, 3045-3055).
                val hello = family.clientHello
                    ?: error("WHOOP5 must define a clientHello frame")
                bondRaceRetry("CLIENT_HELLO write") {
                    peripheral.write(commandCharacteristic, hello, WriteType.WithResponse)
                }
                bondedAtMark = TimeSource.Monotonic.markNow()
                log("CLIENT_HELLO acked — subscribing puffin notify chars")
                // Post-hello notify setup: subscribing before the link is encrypted fails with
                // "Authentication is insufficient" (issue #17, 3081).
                startObserving(scope)
                // Same CCCD race as WHOOP4 — don't burst the handshake into a dead notify pipe.
                verifyNotifyPathLive()
                runHandshakeOnce()
            }
        }
    }

    /** Intentional teardown: cancels the notify collectors and closes the link. */
    suspend fun disconnect() {
        log("disconnect (intentional)")
        reconnectPolicy.userReset() // explicit user action clears all suspicion (Swift disconnect())
        stopObserving()
        peripheral.disconnect()
    }

    /**
     * Feed the reconnect detectors after the link dropped (Swift `didDisconnectPeripheral`,
     * 2752-2790). [timedOut] = the platform classified the drop as a connection timeout AND it was
     * not an intentional disconnect. Also resets [connectHandshakeDone] (2816) so the next connect
     * re-runs the handshake.
     */
    fun noteConnectionEnded(timedOut: Boolean): ConnectionEndEvents {
        val events = reconnectPolicy.connectionEnded(
            wasArmed = armedAtMark != null,
            secondsSinceArm = armedAtMark?.elapsedNow()?.inWholeMilliseconds?.let { it / 1000.0 },
            wasBonded = bondedAtMark != null,
            secondsSinceBond = bondedAtMark?.elapsedNow()?.inWholeMilliseconds?.let { it / 1000.0 },
            timedOut = timedOut,
        )
        connectHandshakeDone = false
        stopObserving()
        return events
    }

    /** Flow 4 hook: realtime was armed on this connection (starts the marginal-radio window). */
    fun noteRealtimeArmed() {
        armedAtMark = TimeSource.Monotonic.markNow()
    }

    /**
     * Build + write one command frame on the family's transport: WHOOP 4 CRC8 command frames,
     * WHOOP 5/MG puffin CRC16 frames (Swift `send`, BLEManager.swift:1344; framing per family).
     * Default payload `[0x00]` matches the Swift default. Sequence increments per send (u8 wrap).
     */
    suspend fun send(
        cmd: CommandNumber,
        payload: ByteArray = byteArrayOf(0x00),
        writeType: WriteType = WriteType.WithoutResponse,
    ) {
        seq = (seq + 1) and 0xFF
        val frame = when (family) {
            DeviceFamily.WHOOP4 -> Framing.buildCommand(cmd, payload, seq)
            DeviceFamily.WHOOP5 -> Framing.puffinCommandFrame(cmd.rawValue, seq, payload)
        }
        peripheral.write(commandCharacteristic, frame, writeType)
    }

    /**
     * The once-per-connection hello/version/clock handshake (BLEManager.swift:3195-3290).
     *
     * WHOOP-faithful connect lifecycle: hello → set RTC, then offload. Hello is NOT strictly
     * required to serve — verified on a real strap via the Mac ground-truth test: plain
     * SEND_HISTORICAL_DATA serves type-47 with no hello and no high-freq-sync. We still exchange
     * hello to mirror WHOOP exactly (3252-3256).
     *
     * Guarded by [connectHandshakeDone] (3249; see also the :606 comment): later `.withResponse`
     * acks (the bond write, every SEND_HISTORICAL, every HISTORY_END ack) re-enter the Swift
     * callback, and without this guard they re-sent hello/SET_CLOCK at the strap DURING the
     * offload and stopped it from streaming type-47.
     */
    private suspend fun runHandshakeOnce() {
        if (connectHandshakeDone) return
        connectHandshakeDone = true
        val now = nowUnixSeconds()
        when (family) {
            DeviceFamily.WHOOP4 -> {
                send(CommandNumber.GET_HELLO_HARVARD)
                send(CommandNumber.GET_ADVERTISING_NAME)
                // One-shot firmware-version read for the Devices card; decodes to fw_harvard (3262-3264).
                send(CommandNumber.REPORT_VERSION_INFO)
                // SET_CLOCK in every form this family accepts (#120, sendSetClockBothForms).
                ClockSync.setClockPayloads(family, now).forEach { send(CommandNumber.SET_CLOCK, it) }
                // GET_CLOCKs ride behind both SET_CLOCKs, so the reply reflects the corrected clock
                // (#120). The clockRef-correlation guard lives upstream (ClockPolicy, flow 3).
                ClockSync.getClockPayloads(family).forEach { send(CommandNumber.GET_CLOCK, it) }
                // Stop the type-43 realtime flood (BLE airtime/battery; re-armed by flow 4 on demand).
                send(CommandNumber.SEND_R10_R11_REALTIME, byteArrayOf(0x00))
                // Deferred here vs Swift: send(.getDataRange) + the rate-limited offload kick are
                // flow 3 (backfill watchdog) and are NOT part of this session layer.
            }
            DeviceFamily.WHOOP5 -> {
                // Version read: GET_HELLO(145) decodes to device name + fw_version on 5/MG.
                send(CommandNumber.GET_HELLO)
                // Single hardware-validated 8-byte SET_CLOCK, then the empty GET_CLOCK (3222-3231).
                // SET_CLOCK/GET_CLOCK are MANDATORY before history on 5/MG: an un-clocked WHOOP 5
                // doesn't save records (#120 twin).
                ClockSync.setClockPayloads(family, now).forEach { send(CommandNumber.SET_CLOCK, it) }
                ClockSync.getClockPayloads(family).forEach { send(CommandNumber.GET_CLOCK, it) }
            }
        }
        log("handshake sent (hello/version/clock) family=$family")
    }

    /**
     * Prove the notify path is LIVE before the handshake burst goes out.
     *
     * [startObserving] only launches the Kable collectors; the CCCD subscribe happens
     * asynchronously on first collect. A handshake fired before the command-response
     * characteristic subscription lands gets every reply silently dropped by the OS — on WHOOP4
     * that loses the GET_CLOCK correlation, so flow-3 realtime frames buffer pre-clock forever
     * and no HR row is ever persisted (hardware-observed: t11 gate run, 284 frames / 0 rows).
     *
     * Sends a harmless GET_BATTERY_LEVEL probe and waits for ANY reassembled frame to echo back
     * (an EVENT counts — it proves the notify pipe). Probe is (re)sent only after the [frames]
     * subscription is active so the echo cannot be missed. Bounded like [bondRaceRetry]; on
     * exhaustion we log and proceed rather than brick the connection.
     */
    private suspend fun verifyNotifyPathLive() {
        var attempt = 1
        while (true) {
            val echoed = withTimeoutOrNull(NOTIFY_ECHO_TIMEOUT_MS) {
                frames
                    .onSubscription { send(CommandNumber.GET_BATTERY_LEVEL, byteArrayOf(0x00)) }
                    .first()
            } != null
            if (echoed) {
                log("notify path live (frame echo, probe $attempt/$NOTIFY_ECHO_ATTEMPTS)")
                return
            }
            if (attempt >= NOTIFY_ECHO_ATTEMPTS) {
                log(
                    "notify echo never arrived ($attempt probes) — proceeding; " +
                        "handshake replies may be lost",
                )
                return
            }
            attempt += 1
        }
    }

    /**
     * Bounded insufficient-auth retry around the first acked write of a connection (Swift
     * `didWriteValueFor`, BLEManager.swift:3085-3160).
     *
     * On macOS/iOS the first `.withResponse` write to an encrypted characteristic RACES bond
     * establishment: CoreBluetooth kicks just-works pairing and refuses the in-flight write with
     * CBATTError insufficientEncryption (15). A SINGLE refusal is that transient race (#74), not a
     * strap that refuses to pair — retry the same write. Every refusal is recorded into
     * [ReconnectPolicy.bondGiveUp] (Swift `bondRefusalStreak`) so a strap that keeps refusing
     * still walks toward the #747 give-up; a write that eventually succeeds is a genuine bond and
     * clears the streak (Swift didBond path). Attempts are bounded at [BOND_WRITE_ATTEMPTS]
     * (mirrors the `pinBondRefusalLimit = 3` idiom); non-auth errors propagate unchanged.
     */
    private suspend fun bondRaceRetry(what: String, write: suspend () -> Unit) {
        var attempt = 1
        while (true) {
            try {
                write()
                if (attempt > 1) {
                    log("$what succeeded on attempt $attempt — genuine bond, clearing refusal streak")
                }
                reconnectPolicy.noteGenuineBond()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (!isInsufficientAuthError(t)) throw t
                reconnectPolicy.bondGiveUp.recordRefusal()
                if (attempt >= BOND_WRITE_ATTEMPTS) throw t
                log(
                    "$what refused (insufficient encryption) — bond race (#74), " +
                        "retry $attempt/${BOND_WRITE_ATTEMPTS - 1} in ${BOND_RETRY_DELAY_MS}ms",
                )
                delay(BOND_RETRY_DELAY_MS)
                attempt += 1
            }
        }
    }

    private fun startObserving(scope: CoroutineScope) {
        stopObserving()
        observeJobs = notifyCharacteristics.map { (uuid, characteristic) ->
            scope.launch {
                // #17 twin of the write path: subscribing to an encrypted notify characteristic
                // can hit the same just-works race (WHOOP4 cmd notify …0003 subscribes before the
                // bond write). Same bounded retry + refusal accounting as [bondRaceRetry].
                var attempt = 1
                while (true) {
                    try {
                        peripheral.observe(characteristic).collect { fragment ->
                            onNotify(uuid, fragment)
                        }
                        break // flow completed (link closed)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: NotConnectedException) {
                        // Kable observe flows THROW NotConnectedException when the link closes —
                        // they do not complete normally. This fires on BOTH intentional
                        // disconnects (stopObserving()'s cancel() is async and can lose the race
                        // with the actual link drop) and unexpected drops. Either way it is
                        // normal collector termination, not a failure: Swift parity is
                        // CoreBluetooth simply ceasing didUpdateValueFor callbacks. Reconnect
                        // handling is driven by the state flow / noteConnectionEnded, not here.
                        // Rethrowing would fail the job and abort the process on Kotlin/Native
                        // (no CoroutineExceptionHandler in the session scope).
                        log("notify collector $uuid ended — link closed")
                        break
                    } catch (t: Throwable) {
                        if (!isInsufficientAuthError(t)) throw t
                        reconnectPolicy.bondGiveUp.recordRefusal()
                        if (attempt >= BOND_WRITE_ATTEMPTS) throw t
                        log(
                            "notify subscribe $uuid refused (insufficient auth) — " +
                                "retry $attempt/${BOND_WRITE_ATTEMPTS - 1} in ${BOND_RETRY_DELAY_MS}ms",
                        )
                        delay(BOND_RETRY_DELAY_MS)
                        attempt += 1
                    }
                }
            }
        }
    }

    private fun stopObserving() {
        observeJobs.forEach { it.cancel() }
        observeJobs = emptyList()
    }

    private suspend fun onNotify(characteristicUuid: String, fragment: ByteArray) {
        // One reassembler serves all notify characteristics, like Swift's single per-connection
        // instance fed from didUpdateValueFor; the lock keeps concurrent collectors from
        // interleaving fragments mid-frame.
        val frames = reassemblerLock.withLock { reassembler.feed(fragment) }
        for (frame in frames) {
            frameFlow.emit(SessionFrame(characteristicUuid, frame, Framing.parseFrame(frame, family)))
        }
    }

    companion object {
        /**
         * Max attempts per bond-racing write/subscribe (initial + 2 retries). Mirrors the Swift
         * `pinBondRefusalLimit = 3` idiom (BLEManager.swift:3150-3160).
         */
        const val BOND_WRITE_ATTEMPTS: Int = 3

        /** Pause between insufficient-auth retries — long enough for just-works pairing to land. */
        const val BOND_RETRY_DELAY_MS: Long = 1_000L

        /** Max GET_BATTERY_LEVEL probes proving the notify pipe before the handshake burst. */
        const val NOTIFY_ECHO_ATTEMPTS: Int = 3

        /** Per-probe wait for any frame echo — a CCCD subscribe completes well inside this. */
        const val NOTIFY_ECHO_TIMEOUT_MS: Long = 1_500L
    }
}
