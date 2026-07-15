@file:OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)

package com.noop.ble

import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.noop.protocol.CommandNumber
import com.noop.protocol.DeviceFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Phase 2c-2 Task 15a: single facade the Swift shim (T15b) talks to. Composes the pieces the
 * HW-gated harnesses proved individually — [BleScanner]-style discovery (flow 1), [BleSession]
 * connect/handshake (flow 2), realtime arming via [RealtimePolicy] (flow 3), and
 * [CommandChannel] sends (flow 5) — behind string identifiers and flows of shared data classes,
 * so no Kable type ever crosses the Kotlin↔Swift boundary.
 *
 * Backfill (flow 4) is NOT owned here: [Backfiller] needs persistence callbacks that belong to
 * the store layer, so callers wire it against [session] via [withSession].
 *
 * Threading: all mutation happens on [scope] (single-threaded confinement is NOT assumed — Kable
 * flows deliver on Dispatchers of their choosing — but every mutable field is only touched from
 * coroutines launched into [scope], mirroring the harness pattern).
 */
class WhoopBleClient(
    parentScope: CoroutineScope? = null,
    // Runtime-mutable (was ctor-fixed): the Settings experimental toggles flip deep-data /
    // Broadcast-HR mid-connection, so [setDeepDataAllowed]/[setBroadcastHrAllowed] must reach a
    // LIVE channel too. Initial values still come from the ctor for parity with T15a callers.
    private var deepDataEnabled: Boolean = false,
    private var broadcastHrEnabled: Boolean = false,
    private val nowUnixSeconds: () -> Long = { Clock.System.now().epochSeconds },
    private val log: (String) -> Unit = {},
) {

    /** Simple connection lifecycle for the UI layer (Swift `LiveState.connectionPhase`). */
    sealed class ConnectionState {
        data object Idle : ConnectionState()

        data class Connecting(val identifier: String) : ConnectionState()

        data class Connected(val identifier: String, val family: DeviceFamily) : ConnectionState()

        /** [reason] is human-readable; null on clean [disconnect]. */
        data class Disconnected(val identifier: String, val reason: String?) : ConnectionState()
    }

    private val scope: CoroutineScope =
        CoroutineScope((parentScope?.coroutineContext ?: kotlinx.coroutines.Dispatchers.Default) + SupervisorJob())

    // ---- Discovery (flow 1) --------------------------------------------------------------

    /** Latest advertisement per identifier — connect() needs the raw Kable object. */
    private val advertisements = mutableMapOf<String, Advertisement>()

    private val discoveredFlow = MutableStateFlow<List<DiscoveredWhoop>>(emptyList())

    /** Deduped-by-identifier scan results, newest advertisement wins. */
    val discovered: StateFlow<List<DiscoveredWhoop>> = discoveredFlow.asStateFlow()

    private val scanningFlow = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = scanningFlow.asStateFlow()

    private var scanJob: Job? = null

    /**
     * Start (or restart) scanning for [models]. Results accumulate in [discovered] until the
     * next [startScan] or [stopScan]. [preferredIdentifier] applies the
     * [WhoopAdvertisementFilter] pin exactly like [BleScanner.discoveries].
     */
    @OptIn(ExperimentalUuidApi::class)
    fun startScan(
        models: Set<WhoopModel> = setOf(WhoopModel.WHOOP4, WhoopModel.WHOOP5),
        preferredIdentifier: String? = null,
    ) {
        stopScan()
        advertisements.clear()
        discoveredFlow.value = emptyList()
        scanningFlow.value = true
        scanJob = Scanner {
            filters {
                models.forEach { model ->
                    match { services = listOf(Uuid.parse(model.scanServiceUuid)) }
                }
            }
        }.advertisements
            .onEach { adv ->
                val identifier = adv.identifier.toString()
                if (!WhoopAdvertisementFilter.passesPreferredFilter(identifier, preferredIdentifier)) return@onEach
                advertisements[identifier] = adv
                val hit = DiscoveredWhoop(
                    identifier = identifier,
                    name = WhoopAdvertisementFilter.displayName(adv.name),
                    rssi = adv.rssi,
                    model = WhoopAdvertisementFilter.modelFor(adv.uuids.map { it.toString() }),
                )
                discoveredFlow.value =
                    discoveredFlow.value.filterNot { it.identifier == identifier } + hit
            }
            .launchIn(scope)
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        scanningFlow.value = false
    }

    // ---- Session (flow 2) ----------------------------------------------------------------

    private val connectionFlow = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connection: StateFlow<ConnectionState> = connectionFlow.asStateFlow()

    private val frameFlow = MutableSharedFlow<SessionFrame>(extraBufferCapacity = 256)

    /** Every decoded frame from the current session — survives reconnects (relay re-attached). */
    val frames: SharedFlow<SessionFrame> = frameFlow.asSharedFlow()

    private var session: BleSession? = null
    private var commands: CommandChannel? = null
    private var sessionScope: CoroutineScope? = null
    private val realtimePolicy = RealtimePolicy(minuteOfDay = {
        val local = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        local.hour * 60 + local.minute
    })

    /**
     * Connect to a previously discovered WHOOP. Fails fast if [identifier] was never seen by
     * [startScan] (mirrors CoreBluetooth: you cannot connect what you have not discovered —
     * retrieval-by-UUID reconnect is a later concern, tracked in T15c).
     */
    suspend fun connect(identifier: String) {
        val adv = advertisements[identifier]
            ?: error("connect($identifier): not in scan results — call startScan() first")
        disconnect() // one session at a time, Swift BLEManager invariant
        val model = adv.uuids.map { it.toString() }.let(WhoopAdvertisementFilter::modelFor)
        val family = when (model) {
            WhoopModel.WHOOP5 -> DeviceFamily.WHOOP5
            else -> DeviceFamily.WHOOP4
        }
        connectionFlow.value = ConnectionState.Connecting(identifier)

        val newScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
        val newSession = BleSession(
            peripheral = Peripheral(adv),
            family = family,
            nowUnixSeconds = nowUnixSeconds,
            log = log,
        )
        sessionScope = newScope
        session = newSession
        commands = CommandChannel(
            session = newSession,
            family = family,
            deepDataEnabled = deepDataEnabled,
            broadcastHrEnabled = broadcastHrEnabled,
            log = log,
        )

        // Relay frames + state before connect so nothing during handshake is missed.
        newScope.launch { newSession.frames.collect { frameFlow.emit(it) } }
        newSession.state
            .onEach { state ->
                when (state) {
                    is State.Connected ->
                        connectionFlow.value = ConnectionState.Connected(identifier, family)
                    is State.Disconnected ->
                        if (connectionFlow.value !is ConnectionState.Idle) {
                            connectionFlow.value =
                                ConnectionState.Disconnected(identifier, state.status?.toString())
                        }
                    else -> Unit // Connecting/Disconnecting transitions stay as-is
                }
            }
            .launchIn(newScope)

        newSession.connect(newScope)
    }

    /** Clean teardown; safe to call when already idle. */
    suspend fun disconnect() {
        val s = session ?: return
        session = null
        commands = null
        runCatching { s.disconnect() }
        sessionScope?.cancel()
        sessionScope = null
        realtimePolicy.onDisconnect()
        connectionFlow.value = ConnectionState.Idle
    }

    // ---- Realtime (flow 3) ---------------------------------------------------------------

    /** Arm realtime streams per [RealtimePolicy.startRealtime]. No-op when not connected. */
    suspend fun startRealtime() = applyRealtime(realtimePolicy.startRealtime())

    suspend fun stopRealtime() = applyRealtime(realtimePolicy.stopRealtime())

    private suspend fun applyRealtime(actions: List<RealtimeAction>) {
        val s = session ?: return
        FrameTransport.applyRealtimeActions(
            family = s.family,
            actions = actions,
            send = { cmd: CommandNumber, payload: ByteArray -> s.send(cmd, payload) },
            noteArmed = s::noteRealtimeArmed,
        )
    }

    // ---- Commands (flow 5) ---------------------------------------------------------------

    /** Run planned command sends through [CommandChannel]. No-op when not connected. */
    suspend fun sendCommands(planned: List<PlannedSend>) {
        commands?.run(planned) ?: log("sendCommands: dropped ${planned.size} — not connected")
    }

    /**
     * Keep-realtime-for-data preference (legacy `BLEManager.setKeepRealtimeForData`): applies the
     * [RealtimePolicy] arm/disarm actions the flip produces. No-op when not connected (the policy
     * still records the preference for the next connect).
     */
    suspend fun setKeepRealtimeForData(keep: Boolean, overnightOnly: Boolean) =
        applyRealtime(realtimePolicy.setKeepRealtimeForData(keep, overnightOnly))

    /**
     * Settings → Experimental toggle mirrors: update the SET_CONFIG / SET_DEVICE_CONFIG gating on
     * the LIVE channel (and the value the next connect's channel is built with). Pure gating —
     * nothing is sent here.
     */
    fun setDeepDataAllowed(allowed: Boolean) {
        deepDataEnabled = allowed
        commands?.deepDataEnabled = allowed
    }

    fun setBroadcastHrAllowed(allowed: Boolean) {
        broadcastHrEnabled = allowed
        commands?.broadcastHrEnabled = allowed
    }

    /**
     * Send the 15-flag enable_r22 deep-data sequence (legacy `BLEManager.enableWhoop5DeepData`,
     * #174), 80 ms between writes like the legacy `asyncAfter` stagger. The legacy guards
     * (5/MG family, encrypted bond, on-wrist, experiment toggle) stay with the Swift caller,
     * which owns that state and the user-facing log lines.
     */
    suspend fun enableWhoop5DeepData() {
        CommandPlanner.planDeepDataEnable().forEach { planned ->
            sendCommands(listOf(planned))
            delay(80)
        }
    }

    /**
     * Write the Broadcast-HR device-config flag (legacy `BLEManager.setBroadcastHr`, #181).
     * Family/connect/bond guards stay with the Swift caller, like the legacy method's log lines.
     */
    suspend fun setBroadcastHr(on: Boolean) =
        sendCommands(listOf(CommandPlanner.planBroadcastHr(on)))

    // ---- Escape hatch --------------------------------------------------------------------

    /**
     * Access the live [BleSession] for wiring that stays in Kotlin (e.g. [Backfiller] hookup by
     * the store layer). Returns null when disconnected. NOT exported to Swift usefully — the
     * shim must never touch this.
     */
    fun <T> withSession(block: (BleSession) -> T): T? = session?.let(block)

    /** Cancel everything owned by this client (scan, session, relays). */
    fun close() {
        stopScan()
        scope.launch { disconnect() }
        scope.cancel()
    }
}
