package com.noop.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

// MARK: - PaletteTokens — the per-scheme colour set behind `object Palette`
//
// Compose has no OS-dynamic colour (unlike iOS UIColor(light:dark:)), so the light theme is built
// the same way conceptually: ONE set of colour tokens, swapped wholesale per scheme. `Palette.active`
// is snapshot state, so every `Palette.X` read (in a composable OR a Canvas DrawScope) re-resolves
// automatically when the theme flips — ZERO call-site changes across the ~1,740 references.
//
// Dark values mirror StrandPalette.swift's dark; light values are the approved "Warm Paper" set
// (docs/superpowers/specs/2026-06-16-light-theme-design.md). Names/order match the Swift palette.

data class PaletteTokens(
    val surfaceBase: Color,
    val surfaceRaised: Color,
    val surfaceOverlay: Color,
    val surfaceInset: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val glowAmbient: Color,
    val accent: Color,
    val accentHover: Color,
    val accentMuted: Color,
    val focusRing: Color,
    val recovery000: Color,
    val recovery030: Color,
    val recovery055: Color,
    val recovery078: Color,
    val recovery100: Color,
    val strain000: Color,
    val strain033: Color,
    val strain066: Color,
    val strain100: Color,
    val sleepAwake: Color,
    val sleepLight: Color,
    val sleepDeep: Color,
    val sleepREM: Color,
    val zone1: Color,
    val zone2: Color,
    val zone3: Color,
    val zone4: Color,
    val zone5: Color,
    val statusPositive: Color,
    val statusWarning: Color,
    val statusCritical: Color,
    val metricCyan: Color,
    val metricPurple: Color,
    val metricAmber: Color,
    val metricRose: Color,
    val chargeColor: Color,
    val chargeDeep: Color,
    val chargeBright: Color,
    val chargeGlow: Color,
    val effortColor: Color,
    val effortDeep: Color,
    val effortBright: Color,
    val effortGlow: Color,
    val restColor: Color,
    val restDeep: Color,
    val restBright: Color,
    val restGlow: Color,
    val stressColor: Color,
    val stressDeep: Color,
    val stressBright: Color,
    val stressGlow: Color,
    val scenicCenter: Color,
    val scenicEdge: Color,
    val scenicStar: Color,
    val cardFillTop: Color,
    val cardFillBottom: Color,
    val gold: Color,
    val goldLight: Color,
    val goldDeep: Color,
    val goldDeepText: Color,
    val signalYellow: Color,
    val titaniumTop: Color,
    val titaniumMid: Color,
    val titaniumLow: Color,
    val titaniumDeep: Color,
    // The bright gauge-tip / sparkline-head core: white reads as a highlight on dark; on light it
    // would vanish into the white card, so it flips to a deep ink (crisp centre on the coloured bead).
    val tipCore: Color,
)

val DarkTokens = PaletteTokens(
    surfaceBase = Color(0xFF070C16), surfaceRaised = Color(0xFF111B2A), surfaceOverlay = Color(0xFF15243C),
    surfaceInset = Color(0xFF16202F), hairline = Color(0xFF21304A), hairlineStrong = Color(0xFF2E3C57),
    textPrimary = Color(0xFFF4F6F8), textSecondary = Color(0xFFC8CFD8), textTertiary = Color(0xFF8A94A4),
    glowAmbient = Color(0xFF3A2D0A),
    accent = Color(0xFFE8B84B), accentHover = Color(0xFFFCEBA8), accentMuted = Color(0xFF2A2210), focusRing = Color(0xFFE8B84B),
    recovery000 = Color(0xFFC8902F), recovery030 = Color(0xFFD9A23E), recovery055 = Color(0xFFE8B84B),
    recovery078 = Color(0xFFF2CE6E), recovery100 = Color(0xFFFCEBA8),
    strain000 = Color(0xFF9C5A14), strain033 = Color(0xFFC2762A), strain066 = Color(0xFFD98A3D), strain100 = Color(0xFFF0A85A),
    sleepAwake = Color(0xFFC2CCDA), sleepLight = Color(0xFF4A90E2), sleepDeep = Color(0xFF2F6FCB), sleepREM = Color(0xFF6FA8E8),
    zone1 = Color(0xFF4A90E2), zone2 = Color(0xFF3FA9C9), zone3 = Color(0xFFE8B84B), zone4 = Color(0xFFD98A3D), zone5 = Color(0xFFE0662F),
    statusPositive = Color(0xFFE8B84B), statusWarning = Color(0xFFD98A3D), statusCritical = Color(0xFFE0662F),
    metricCyan = Color(0xFF3FA9C9), metricPurple = Color(0xFF4A90E2), metricAmber = Color(0xFFD98A3D), metricRose = Color(0xFFE0662F),
    chargeColor = Color(0xFFE8B84B), chargeDeep = Color(0xFFC8902F), chargeBright = Color(0xFFFCEBA8), chargeGlow = Color(0xFFE8B84B),
    effortColor = Color(0xFFD98A3D), effortDeep = Color(0xFF9C5A14), effortBright = Color(0xFFF0A85A), effortGlow = Color(0xFFD98A3D),
    restColor = Color(0xFF4A90E2), restDeep = Color(0xFF2F6FCB), restBright = Color(0xFF6FA8E8), restGlow = Color(0xFF4A90E2),
    stressColor = Color(0xFFE8B84B), stressDeep = Color(0xFF4A90E2), stressBright = Color(0xFFE0662F), stressGlow = Color(0xFFE8B84B),
    scenicCenter = Color(0xFF15243C), scenicEdge = Color(0xFF0A1322), scenicStar = Color(0xFFC8CFD8),
    cardFillTop = Color(0xFF15243C), cardFillBottom = Color(0xFF0B1424),
    gold = Color(0xFFE8B84B), goldLight = Color(0xFFFCEBA8), goldDeep = Color(0xFFC8902F),
    goldDeepText = Color(0xFF3A2708), signalYellow = Color(0xFFFFD63D),
    titaniumTop = Color(0xFFF1F3F5), titaniumMid = Color(0xFFC9CFD4), titaniumLow = Color(0xFF969DA4), titaniumDeep = Color(0xFF6B737B),
    tipCore = Color(0xFFFFFFFF),
)

val LightTokens = PaletteTokens(
    surfaceBase = Color(0xFFF4F1EA), surfaceRaised = Color(0xFFFFFFFF), surfaceOverlay = Color(0xFFFFFFFF),
    surfaceInset = Color(0xFFECE7DC), hairline = Color(0xFFE4DECF), hairlineStrong = Color(0xFFD2C9B6),
    textPrimary = Color(0xFF1A2230), textSecondary = Color(0xFF4C5564), textTertiary = Color(0xFF7C8696),
    glowAmbient = Color(0xFFF0E4C0),
    accent = Color(0xFFB07D17), accentHover = Color(0xFF946612), accentMuted = Color(0xFFF4E8C8), focusRing = Color(0xFFC8902F),
    recovery000 = Color(0xFF8F6212), recovery030 = Color(0xFFA87718), recovery055 = Color(0xFFC28E26),
    recovery078 = Color(0xFFD2A23A), recovery100 = Color(0xFFE0B44C),
    strain000 = Color(0xFF7E460E), strain033 = Color(0xFFA4621B), strain066 = Color(0xFFC2792E), strain100 = Color(0xFFD89240),
    sleepAwake = Color(0xFF97A2B2), sleepLight = Color(0xFF3A80D6), sleepDeep = Color(0xFF234F9E), sleepREM = Color(0xFF5790DA),
    zone1 = Color(0xFF3A80D6), zone2 = Color(0xFF2E92B4), zone3 = Color(0xFFC28E26), zone4 = Color(0xFFC2792E), zone5 = Color(0xFFC84E1E),
    statusPositive = Color(0xFFB07D17), statusWarning = Color(0xFFC2792E), statusCritical = Color(0xFFC84E1E),
    metricCyan = Color(0xFF2E92B4), metricPurple = Color(0xFF3A80D6), metricAmber = Color(0xFFC2792E), metricRose = Color(0xFFC84E1E),
    chargeColor = Color(0xFFB88421), chargeDeep = Color(0xFF8F6212), chargeBright = Color(0xFFE0B44C), chargeGlow = Color(0xFFC8902F),
    effortColor = Color(0xFFB26A1C), effortDeep = Color(0xFF7E460E), effortBright = Color(0xFFD89240), effortGlow = Color(0xFFB26A1C),
    restColor = Color(0xFF3A80D6), restDeep = Color(0xFF234F9E), restBright = Color(0xFF5790DA), restGlow = Color(0xFF3A80D6),
    stressColor = Color(0xFFB88421), stressDeep = Color(0xFF3A80D6), stressBright = Color(0xFFC84E1E), stressGlow = Color(0xFFB88421),
    scenicCenter = Color(0xFFFBF6EA), scenicEdge = Color(0xFFEDE6D6), scenicStar = Color(0xFFD8CDB6),
    cardFillTop = Color(0xFFFFFFFF), cardFillBottom = Color(0xFFFAF7F0),
    gold = Color(0xFFDBA52A), goldLight = Color(0xFFECC766), goldDeep = Color(0xFF9A6B12),
    goldDeepText = Color(0xFF3A2708), signalYellow = Color(0xFFE8A800),
    titaniumTop = Color(0xFFDDE1E6), titaniumMid = Color(0xFFBBC2C9), titaniumLow = Color(0xFF98A0A8), titaniumDeep = Color(0xFF6B737B),
    tipCore = Color(0xFF241B06),
)

// MARK: - Appearance preference (System / Light / Dark)

enum class AppearanceMode(val storageValue: String, val label: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromStorage(raw: String?): AppearanceMode =
            entries.firstOrNull { it.storageValue == raw } ?: SYSTEM
    }
}

/** Theme preference, persisted in `noop_prefs` and mirrored in snapshot state so the toggle is live.
 *  [load] is called once from MainActivity before first composition (no flash); [set] writes + flips. */
object AppearancePrefs {
    private const val FILE = "noop_prefs"
    private const val KEY = "theme.appearance"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Live appearance mode read by NoopTheme; defaults to System until [load] runs. */
    var mode by mutableStateOf(AppearanceMode.SYSTEM)
        private set

    fun load(ctx: Context) {
        mode = AppearanceMode.fromStorage(prefs(ctx).getString(KEY, AppearanceMode.SYSTEM.storageValue))
    }

    fun set(ctx: Context, value: AppearanceMode) {
        mode = value
        prefs(ctx).edit().putString(KEY, value.storageValue).apply()
    }
}
