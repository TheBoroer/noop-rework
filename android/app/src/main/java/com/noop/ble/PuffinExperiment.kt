package com.noop.ble

import android.content.Context
import android.content.SharedPreferences

/**
 * Opt-in switch for the EXPERIMENTAL WHOOP 5.0/MG ("puffin") protocol probes.
 *
 * Direct port of the macOS `PuffinExperiment` (Strand/BLE/PuffinExperiment.swift). Live HR on a
 * 5/MG strap already works over the standard profile after CLIENT_HELLO. These probes go further —
 * sending puffin-framed commands (e.g. asking the strap to start its realtime stream) to learn what
 * a real 5/MG strap responds to. They are guesses, so they are OFF by default and only ever written
 * to the puffin command characteristic (fd4b0002). A 5/MG owner can flip this on under Settings →
 * Experimental to help map the protocol; everyone else is unaffected. It never touches WHOOP 4.0.
 *
 * The macOS app stored this in `UserDefaults` under the key `noopPuffinExperiments`; the Android
 * equivalent is [SharedPreferences]. The same key name is reused for parity.
 */
class PuffinExperiment(private val prefs: SharedPreferences) {

    /** True if the user opted in to the WHOOP 5/MG protocol probes (default false). */
    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY, false)
        set(v) = prefs.edit().putBoolean(KEY, v).apply()

    /** True if the user opted in to recording raw 5/MG backfill frames to a shareable JSONL file
     *  (default false). SEPARATE from [isEnabled]: probes SEND commands at the strap; capture only
     *  RECORDS what arrives — different risk profiles, so different switches. (#78 fork) */
    var isCaptureEnabled: Boolean
        get() = prefs.getBoolean(KEY_CAPTURE, false)
        set(v) = prefs.edit().putBoolean(KEY_CAPTURE, v).apply()

    /** True if the user opted in to the WHOOP 5/MG "R22" deep-data unlock — the one probe that WRITES
     *  a persistent feature flag to the strap (the `enable_r22_*` SET_CONFIG sequence). Kept distinct
     *  from [isEnabled] because it changes strap state; reversible, default false. Mirrors the macOS
     *  `PuffinExperiment.deepDataKey`. Driven only from `AndroidWhoopBleClient.enableWhoop5DeepData()`. (#174) */
    var isDeepDataEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEEP_DATA, false)
        set(v) = prefs.edit().putBoolean(KEY_DEEP_DATA, v).apply()

    /** True if the user opted in to "Broadcast heart rate": NOOP writes the device-config flag
     *  whoop_live_hr_in_adv_ind_pkt="1" so the strap advertises the standard Heart Rate Service
     *  (0x180D) + its live HR, pairable by a Garmin/Zwift/gym HR client. Reversible. Default false.
     *  Mirrors the macOS `PuffinExperiment.broadcastHrKey`. (#181) */
    var broadcastHr: Boolean
        get() = prefs.getBoolean(KEY_BROADCAST_HR, false)
        set(v) = prefs.edit().putBoolean(KEY_BROADCAST_HR, v).apply()

    /** True if the user opted in to "Experimental sleep staging (V2)": detected nights are re-staged with
     *  [com.noop.analytics.SleepStagerV2] (the transparent cardiorespiratory recipe, reimplemented from
     *  contributor PR #600) instead of the default V1 [com.noop.analytics.SleepStager]. Pure analysis switch
     *  — it changes ONLY which staging engine runs over an already-detected sleep window; detection, scoring
     *  and the default V1 path are untouched. Model-agnostic (works on WHOOP 4 and 5). Default false.
     *  Mirrors the macOS `PuffinExperiment.experimentalSleepV2Key`. */
    var experimentalSleepV2: Boolean
        get() = prefs.getBoolean(KEY_EXPERIMENTAL_SLEEP_V2, false)
        set(v) = prefs.edit().putBoolean(KEY_EXPERIMENTAL_SLEEP_V2, v).apply()

    /** True if the user opted in to "Faster history sync": escalate the GATT connection priority to
     *  HIGH for the bounded historical-offload burst, back to BALANCED when it ends (#533, upstream
     *  5b1b31d5). Spends radio for speed — a shorter connection interval moves the same bytes in less
     *  radio-on wall-clock. NOT hardware-validated (BLE can't be CI-tested), hence opt-in, default
     *  false. Live HR deliberately never escalates: realtime stays armed for the whole overnight
     *  continuous-HRV window, and pinning an ~11 ms interval for hours to carry a 1 Hz stream that
     *  BALANCED already serves is sustained drain for no throughput gain. */
    var fastHistorySync: Boolean
        get() = prefs.getBoolean(KEY_FAST_HISTORY_SYNC, false)
        set(v) = prefs.edit().putBoolean(KEY_FAST_HISTORY_SYNC, v).apply()

    /** True if the user opted in to "Prefer 2M Bluetooth": ask the controller to prefer the LE 2M PHY
     *  for the offload burst (#533, upstream 73972540). 2M doubles the symbol rate, so the same bytes
     *  spend HALF the air-time — the better-behaved of the two sync-speed levers (less radio energy
     *  per byte, not more), but it trades range for speed. SEPARATE from [fastHistorySync] on purpose:
     *  opposite battery profiles, so bundling them would make a field report un-attributable. The mask
     *  always keeps 1M so the controller can fall back. Android-only (CoreBluetooth exposes no app-side
     *  PHY API — no Swift twin, mirrors the #477 precedent). Default false; off is byte-for-byte today. */
    var fastLinkPhy: Boolean
        get() = prefs.getBoolean(KEY_FAST_LINK_PHY, false)
        set(v) = prefs.edit().putBoolean(KEY_FAST_LINK_PHY, v).apply()

    companion object {
        /** Persisted preferences file. */
        private const val PREFS = "noop_experiments"

        /** Shared key name with the macOS build (`PuffinExperiment.defaultsKey`). */
        const val KEY = "noopPuffinExperiments"

        /** 5/MG raw backfill capture (research aid for the puffin biometric decode). */
        const val KEY_CAPTURE = "noopWhoop5Capture"

        /** 5/MG R22 deep-data unlock opt-in (mirrors macOS `PuffinExperiment.deepDataKey`). */
        const val KEY_DEEP_DATA = "noopWhoop5DeepData"

        /** "Broadcast heart rate" opt-in (mirrors macOS `PuffinExperiment.broadcastHrKey`). */
        const val KEY_BROADCAST_HR = "noopBroadcastHr"

        /** "Experimental sleep staging (V2)" opt-in (mirrors macOS `PuffinExperiment.experimentalSleepV2Key`). */
        const val KEY_EXPERIMENTAL_SLEEP_V2 = "noopExperimentalSleepV2"

        /** "Faster history sync" opt-in — offload-burst GATT priority escalation (#533). */
        const val KEY_FAST_HISTORY_SYNC = "noopFastHistorySync"

        /** "Prefer 2M Bluetooth" opt-in — LE 2M PHY preference for the offload burst (#533). */
        const val KEY_FAST_LINK_PHY = "noopFastLinkPhy"

        fun from(context: Context): PuffinExperiment =
            PuffinExperiment(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}
