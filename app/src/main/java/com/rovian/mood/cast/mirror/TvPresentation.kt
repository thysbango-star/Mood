package com.rovian.mood.cast.mirror

import android.app.Presentation
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.rovian.mood.ui.theme.MoodTheme
import com.rovian.mood.ui.tv.TvScreen

/**
 * Mood's window on the mirrored TV. It is laid out with the TV display's own
 * metrics, so the interface is rendered at the TV's resolution and aspect ratio
 * rather than being a scaled copy of the phone.
 */
class TvPresentation(
    private val activity: ComponentActivity,
    display: Display,
    private val adapter: DisplayAdapter,
) : Presentation(activity, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setContent { MoodTheme { TvScreen(adapter, display) } }
        }
        setContentView(view)
    }
}
