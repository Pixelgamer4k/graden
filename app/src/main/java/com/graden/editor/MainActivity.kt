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
        TextFonts.assets = applicationContext.assets
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Ask for the panel's highest refresh rate so the timeline scrolls at 90/120 Hz where available.
        @Suppress("DEPRECATION") val display = windowManager.defaultDisplay
        val current = display.mode
        display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate }
            ?.let { best -> window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId } }
        GradeGl.probeHdr(this)
        ShaderCache.warmUp(this)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) watchdog()
        setContent { EditorScreen(vm) }
    }

    /**
     * Debug builds only: `adb shell am start -f 0x20000000 -n <app>/.MainActivity --es debugImport <file in the app's
     * own storage, or its folder in Android/data>` imports that file into the open project, for testing without the picker.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Development builds only: debuggable, or the optimised "fast" build (profileable); never a release.
        val dev = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 || applicationInfo.isProfileableByShell
        if (!dev) return
        if (intent.getBooleanExtra("colourTest", false)) { ColorSelfTest.run(); return }
        intent.getStringExtra("stabBench")?.let { path ->
            Thread {
                Stabilizer.analyse(this, path, 0, 0L, Long.MAX_VALUE / 4, false, "bench_${System.currentTimeMillis()}", {}, { false })
            }.start()
            return
        }
        val path = intent.getStringExtra("debugImport") ?: return
        val f = java.io.File(path)
        val ext = getExternalFilesDir(null)?.canonicalPath
        if (f.exists() && (f.canonicalPath.startsWith(filesDir.canonicalPath) || (ext != null && f.canonicalPath.startsWith(ext))))
            vm.import(listOf(android.net.Uri.fromFile(f)))
    }

    override fun onStop() {
        super.onStop()
        vm.pause()
        // Leaving the app (home, switching apps, locking the phone) writes the project there and then,
        // so closing it from the recent-apps screen afterwards loses nothing.
        vm.flush()
    }

    /** Debug builds only: logs where the main thread is whenever it has been stuck for over two seconds. */
    private fun watchdog() {
        val main = android.os.Looper.getMainLooper().thread
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            while (true) {
                val ticked = java.util.concurrent.atomic.AtomicBoolean(false)
                h.post { ticked.set(true) }
                Thread.sleep(2000)
                if (!ticked.get()) android.util.Log.w("GradenWatch", "main thread blocked:\n" + main.stackTrace.take(30).joinToString("\n") { "  at $it" })
            }
        }, "watchdog").apply { isDaemon = true }.start()
    }
}
