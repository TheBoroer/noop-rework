package com.noop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Multiplatform READ side of the `settings.json` payload inside a `.noopbak` backup (#1000): the
 * KMP twin of the app-side `BackupSettingsCodec` (which keeps the WRITE/encode side this phase,
 * because the export path and its SharedPreferences snapshot stay app-only).
 *
 * The whitelist is the contract, mirrored byte-for-byte across the Android `BackupSettingsCodec`
 * and the Apple `BackupSettings.whitelist`: same canonical key strings, same kinds. Unknown keys
 * are dropped; malformed JSON or wrong-typed values degrade to "fewer keys applied", never an
 * error, because a bad settings entry must not fail a restore whose DB half is fine.
 *
 * [BackupRestore.restore]'s `applySettings` hook hands the RAW payload to the platform caller;
 * this codec is what a shared/iOS consumer uses to decode it down to the safe subset before
 * writing anything into platform preferences.
 */
object BackupSettingsCodecKmp {

    /** Canonical entry name inside the `.noopbak` ZIP. Matches both platforms' exporters. */
    const val ENTRY_NAME = "settings.json"

    /** The JSON kind a whitelisted key must decode to. Anything else is dropped, never guessed at. */
    enum class Kind { INT, DOUBLE, STRING }

    /** THE whitelist: mirrors `BackupSettingsCodec.WHITELIST` (Android) key-for-key, kind-for-kind. */
    val WHITELIST: Map<String, Kind> = linkedMapOf(
        "profile.age" to Kind.INT,
        "profile.sex" to Kind.STRING,
        "profile.weightKg" to Kind.DOUBLE,
        "profile.heightCm" to Kind.DOUBLE,
        "profile.waistCm" to Kind.DOUBLE,
        "profile.hrMax" to Kind.INT,
        "units.system" to Kind.STRING,
        "units.temperature" to Kind.STRING,
        "effort.scale" to Kind.STRING,
    )

    /**
     * Decode a `settings.json` payload down to its whitelisted, correctly-typed subset
     * (Int / Double / String values keyed by canonical name, in whitelist order).
     */
    fun decode(json: String): Map<String, Any> {
        val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
            ?: return emptyMap()
        val out = LinkedHashMap<String, Any>()
        for ((key, kind) in WHITELIST) {
            coerce(obj[key], kind)?.let { out[key] = it }
        }
        return out
    }

    /**
     * Coerce a JSON value to the whitelist's declared kind, or null. Matches the org.json
     * semantics of the Android codec: JSON numbers satisfy INT and DOUBLE (doubles truncate to
     * INT), quoted strings satisfy STRING only, and booleans satisfy nothing (`true` can never
     * become age 1; the Apple side refuses NSNumber-booleans for the same reason).
     */
    private fun coerce(value: kotlinx.serialization.json.JsonElement?, kind: Kind): Any? {
        val p = value as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return when (kind) {
            Kind.STRING -> if (p.isString) p.content else null
            Kind.INT -> p.jsonNumberOrNull()?.toInt()
            Kind.DOUBLE -> p.jsonNumberOrNull()
        }
    }

    /** The primitive as a JSON NUMBER, or null when it is a string, boolean, or unparseable. */
    private fun JsonPrimitive.jsonNumberOrNull(): Double? =
        if (isString || booleanOrNull != null) null else doubleOrNull
}
