package com.noop.util

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Thread-safe LRU with access-order eviction, the multiplatform stand-in for
 * java.util.LinkedHashMap(capacity, 0.75f, accessOrder = true) plus
 * evict-eldest-on-overflow. Kotlin's LinkedHashMap keeps insertion order and
 * K2 cannot compile JDK-collection subclasses in a KMP module, so recency is
 * maintained by remove-and-reinsert, which moves the key to the map's tail.
 */
class MpLru<K : Any, V : Any>(private val capacity: Int) : SynchronizedObject() {
    init { require(capacity > 0) { "capacity must be positive" } }

    private val map = LinkedHashMap<K, V>()

    val size: Int get() = synchronized(this) { map.size }

    fun get(key: K): V? = synchronized(this) {
        val value = map.remove(key) ?: return null
        map[key] = value
        value
    }

    fun put(key: K, value: V) {
        synchronized(this) {
            map.remove(key)
            map[key] = value
            if (map.size > capacity) {
                map.remove(map.keys.first())
            }
        }
    }
}
