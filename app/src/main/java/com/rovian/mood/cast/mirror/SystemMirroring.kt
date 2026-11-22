package com.rovian.mood.cast.mirror

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whole-screen mirroring.
 *
 * Android does not let ordinary apps drive Google Cast screen mirroring or
 * Miracast: the Cast SDK only casts media, and wireless display is controlled
 * by the system (Quick Settings "Screen Cast" / Samsung "Smart View"). So Mood
 * hands off to the system picker and reports only what Android tells it —
 * an external presentation display appearing or disappearing. When it does,
 * [com.rovian.mood.cast.mirror.DisplayAdapter] takes over that display with a
 * TV-native interface instead of a copy of the phone.
 */
class SystemMirroring(context: Context) {
    private val app = context.applicationContext
    private val displays = app.getSystemService(DisplayManager::class.java)

    private val _externalDisplay = MutableStateFlow<String?>(null)
    /** Name of an external/wireless display Android currently reports, if any. */
    val externalDisplay: StateFlow<String?> = _externalDisplay.asStateFlow()

    private val _presentation = MutableStateFlow<Display?>(null)
    /**
     * The display a mirrored TV appears as. When the system mirrors the phone to a
     * TV (Miracast/Smart View/Screen Cast), Android exposes it as a presentation
     * display that apps may draw their own content on, at the TV's own resolution.
     */
    val presentationDisplay: StateFlow<Display?> = _presentation.asStateFlow()

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    fun start() {
        displays.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        refresh()
    }

    private fun refresh() {
        val display = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
        _presentation.value = display
        _externalDisplay.value = display?.name
    }

    /** Every display Android reports, for the network check. */
    fun describeDisplays(): List<String> = displays.displays.map { d ->
        val mode = d.mode
        val presentation = (d.flags and Display.FLAG_PRESENTATION) != 0
        "#${d.displayId} ${d.name}: ${mode.physicalWidth}×${mode.physicalHeight} @${mode.refreshRate.toInt()}Hz" +
            if (presentation) " (presentation)" else ""
    }

    val canOpenSystemPicker: Boolean
        get() = castSettingsIntent().resolveActivity(app.packageManager) != null

    /** Opens the system's own screen-cast picker. Returns false if this phone has none Mood can open. */
    fun openSystemPicker(activity: Activity): Boolean = try {
        activity.startActivity(castSettingsIntent())
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun castSettingsIntent() = Intent(ACTION_CAST_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private companion object {
        /** android.provider.Settings.ACTION_CAST_SETTINGS (public constant, value is stable). */
        const val ACTION_CAST_SETTINGS = "android.settings.CAST_SETTINGS"
    }
}
