package com.rovian.mood.startup

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** A unit of work Mood needs warmed up at launch (network state, discovery, sessions…). */
class StartupTask(val name: String, val run: suspend () -> Unit)

/**
 * Runs every [StartupTask] concurrently off the main thread as soon as the
 * process starts — in parallel with the launch animation, never behind it.
 * [ready] flips once all tasks have completed (or failed; a failing task is
 * logged and never blocks the UI).
 */
class MoodStartup(private val tasks: List<StartupTask>) {
    private val started = AtomicBoolean(false)
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.Default) {
            tasks.map { task ->
                async {
                    val began = System.nanoTime()
                    runCatching { task.run() }
                        .onSuccess { Log.d(TAG, "${task.name} ready in ${(System.nanoTime() - began) / 1_000_000} ms") }
                        .onFailure { Log.w(TAG, "${task.name} failed during startup", it) }
                }
            }.awaitAll()
            _ready.value = true
        }
    }

    private companion object {
        const val TAG = "MoodStartup"
    }
}
