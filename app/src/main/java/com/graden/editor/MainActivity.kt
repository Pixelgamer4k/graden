package com.graden.editor

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.graden.editor.ui.EditorScreen

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Ask for the panel's highest refresh rate so the timeline scrolls at 90/120 Hz where available.
        @Suppress("DEPRECATION") val display = windowManager.defaultDisplay
        val current = display.mode
        display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate }
            ?.let { best -> window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId } }
        setContent { EditorScreen(vm) }
    }

    override fun onStop() {
        super.onStop()
        vm.pause()
        // Leaving the app (home, switching apps, locking the phone) writes the project there and then,
        // so closing it from the recent-apps screen afterwards loses nothing.
        vm.flush()
    }
}
