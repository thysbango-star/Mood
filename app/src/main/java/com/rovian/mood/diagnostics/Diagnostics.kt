package com.rovian.mood.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Live facts the casting pathways record about themselves (no user data), shown
 * in the network check so a failed discovery can be explained instead of guessed.
 */
object Diagnostics {
    private val facts = ConcurrentHashMap<String, String>()
    private val events = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun set(key: String, value: String) {
        facts[key] = value
    }

    fun event(message: String) = synchronized(events) {
        events.addLast("${time.format(Date())} $message")
        while (events.size > 40) events.removeFirst()
    }

    fun facts(): Map<String, String> = facts.toSortedMap()
    fun recentEvents(): List<String> = synchronized(events) { events.toList() }
}
