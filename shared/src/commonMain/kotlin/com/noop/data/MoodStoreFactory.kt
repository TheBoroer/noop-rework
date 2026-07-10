package com.noop.data

/**
 * Convenience pseudo-constructor binding [MoodStore] to the real [WhoopRepository]. Split out of
 * MoodStore.kt (Task 3, kotlinx-datetime adoption) so the class itself could hoist to commonMain
 * while [WhoopRepository] was still androidMain-only; Task 6 hoisted the repository, so the
 * factory now lives in commonMain too (was androidMain MoodStoreAndroid.kt). Call sites (e.g.
 * `MoodStore(vm.repo)` in MindSection.kt) are unaffected: Kotlin resolves a top-level function
 * with the same name as a class exactly like a constructor call.
 */
fun MoodStore(repo: WhoopRepository): MoodStore = MoodStore(
    { rows -> repo.upsertMetricSeries(rows) },
    { deviceId, key, from, to -> repo.metricSeries(deviceId, key, from, to) },
)
