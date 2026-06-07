package org.zeplinko.logplay.client.worker

import java.util.concurrent.ConcurrentHashMap
import org.zeplinko.logplay.client.handler.HandlerRegistration

internal class HandlerRegistry {
    private val map = ConcurrentHashMap<String, HandlerRegistration<*, *>>()

    fun register(reg: HandlerRegistration<*, *>) {
        val previous = map.putIfAbsent(reg.type, reg)
        require(previous == null) { "Handler already registered for type '${reg.type}'" }
    }

    fun get(type: String): HandlerRegistration<*, *>? = map[type]

    fun snapshotTypes(): List<String> = map.keys.toList()

    fun isEmpty(): Boolean = map.isEmpty()
}
