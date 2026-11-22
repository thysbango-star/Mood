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
 * Whole-screen mirroring, honestly scoped.
 *
 * Android does not let ordinary apps drive Google Cast screen mirroring or
 * Miracast: the Cast SDK only casts media, and wireless display is controlled
 * by the system (Quick Settings "Screen Cast" / Samsung "Smart View"). So Mood
 * hands off to the system picker and reports only what Android tells it —
 * an external presentation display appearing or disappearing.
 *
 * A Mood-native mirror (MediaProjection → stream to the Mood Web Receiver) is a
 * separate pathway that needs the web receiver; it plugs into CastAdapter.
 */
class SystemMirroring(context: Context) {
    private val app = context.applicationContext
    private val displays = app.getSystemService(DisplayManager::class.java)

    private val _externalDisplay = MutableStateFlow<String?>(null)
    /** Name of an external/wireless display Android currently reports, if any. */
    val externalDisplay: StateFlow<String?> = _externalDisplay.asStateFlow()

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
        _externalDisplay.value = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
            ?.name
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
