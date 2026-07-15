package com.noop.ble

import com.juul.kable.Scanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Phase 2c-2 Task 9 (flow 1: scan/discover), ported from `BLEManager.swift`.
 *
 * The CoreBluetooth discovery path scanned with `withServices: [model.scanService]` — i.e. the
 * radio-level filter is the advertised primary service UUID of the selected WHOOP family
 * (BLEManager.swift:446-447, 1281-1283). On top of that, `didDiscover` applied exactly two
 * rules before connecting (BLEManager.swift:2654+):
 *
 *  1. Display name: advertised local name, else the peripheral name, else `"unknown"`.
 *  2. Preferred-peripheral pin: when a specific strap is pinned, any OTHER discovered WHOOP
 *     is ignored and scanning continues; when no pin is set, first discovery wins.
 *
 * Those rules live in [WhoopAdvertisementFilter] as pure, unit-testable predicates; [BleScanner]
 * is the thin Kable wiring around them.
 */

/** WHOOP family → advertised primary service UUID (BLEManager.swift:446-447). */
enum class WhoopModel(val scanServiceUuid: String) {
    /** WHOOP 4.0 custom service. */
    WHOOP4("61080001-8d6d-82b8-614a-1c8cb0f8dcc6"),

    /** WHOOP 5.0 / MG service. */
    WHOOP5("fd4b0001-cce1-4033-93ce-002d5875f58a"),
}

/** One scan hit, normalised for the coordinator layer. */
data class DiscoveredWhoop(
    /** Platform peripheral identifier (UUID string on Apple, MAC on Android). */
    val identifier: String,
    /** Resolved per [WhoopAdvertisementFilter.displayName]. */
    val name: String,
    val rssi: Int,
    /** Which family's service UUID was seen in the advertisement, when reported. */
    val model: WhoopModel?,
)

/** Pure discovery predicates — the unit-tested port of `didDiscover`'s filter rules. */
object WhoopAdvertisementFilter {

    /** Advertised local name ?? peripheral name ?? "unknown" (BLEManager.swift:2657). */
    fun displayName(advertisedName: String?, peripheralName: String? = null): String =
        advertisedName ?: peripheralName ?: "unknown"

    /** True when the advertisement carries the family's scan service UUID. */
    fun matchesService(advertisedServiceUuids: List<String>, model: WhoopModel): Boolean =
        advertisedServiceUuids.any { it.equals(model.scanServiceUuid, ignoreCase = true) }

    /** First family whose service UUID appears in the advertisement, else null. */
    fun modelFor(advertisedServiceUuids: List<String>): WhoopModel? =
        WhoopModel.entries.firstOrNull { matchesService(advertisedServiceUuids, it) }

    /**
     * Multi-WHOOP preferred-peripheral pin (BLEManager.swift didDiscover): when
     * [preferredIdentifier] is set, only that strap passes; when null (single-WHOOP
     * default) everything passes.
     */
    fun passesPreferredFilter(identifier: String, preferredIdentifier: String?): Boolean =
        preferredIdentifier == null || identifier.equals(preferredIdentifier, ignoreCase = true)
}

/** Kable-backed scanner. Cold flow: scanning starts on collect, stops on cancel. */
class BleScanner {

    /**
     * Discover WHOOPs advertising any of [models]' service UUIDs, applying the
     * preferred-peripheral pin when [preferredIdentifier] is set.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun discoveries(
        models: Set<WhoopModel> = setOf(WhoopModel.WHOOP4),
        preferredIdentifier: String? = null,
    ): Flow<DiscoveredWhoop> =
        Scanner {
            filters {
                // Multiple match blocks OR together — mirrors scanning one family per
                // CoreBluetooth scan while letting the harness watch both at once.
                models.forEach { model ->
                    match { services = listOf(Uuid.parse(model.scanServiceUuid)) }
                }
            }
        }.advertisements
            .map { adv ->
                DiscoveredWhoop(
                    identifier = adv.identifier.toString(),
                    name = WhoopAdvertisementFilter.displayName(adv.name),
                    rssi = adv.rssi,
                    model = WhoopAdvertisementFilter.modelFor(adv.uuids.map { it.toString() }),
                )
            }
            .filter { WhoopAdvertisementFilter.passesPreferredFilter(it.identifier, preferredIdentifier) }
}
