package com.noop.data

/**
 * Result of importing an external data source (WHOOP export, Apple Health export, or
 * Health Connect) into the local Room store. Returned by every importer so the UI can
 * show one consistent "imported N days / M workouts" toast.
 */
data class ImportSummary(
    /** Human label of the source: "WHOOP", "Apple Health", "Health Connect". */
    val source: String,
    /** Rows actually upserted, keyed by table name (e.g. "dailyMetric" -> 1200). */
    val counts: Map<String, Int>,
    /** Earliest day touched, "YYYY-MM-DD" (null if nothing imported). */
    val firstDay: String? = null,
    /** Latest day touched, "YYYY-MM-DD". */
    val lastDay: String? = null,
    /** One-line human summary for a Toast / status line. */
    val message: String,
) {
    val totalRows: Int get() = counts.values.sum()

    companion object {
        /** A failed/empty import carrying a reason. */
        fun failure(source: String, reason: String) =
            ImportSummary(source = source, counts = emptyMap(), message = reason)
    }
}
