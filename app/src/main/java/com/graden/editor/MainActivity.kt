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
        // Phones stay upright; tablets and unfolded foldables turn freely and get the wide layout on their side.
        requestedOrientation = if (resources.configuration.smallestScreenWidthDp >= 600) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        // Ask for the panel's highest refresh rate so the timeline scrolls at 90/120 Hz where available.
        @Suppress("DEPRECATION") val display = windowManager.defaultDisplay
        val current = display.mode
        display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate }
            ?.let { best -> window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId } }
        GradeGl.probeHdr(this)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) watchdog()
        setContent { EditorScreen(vm) }
        if (savedInstanceState == null) shared(intent)
        // Older Adreno drivers (6xx, 2021) let one shader compile at a time across the whole app. Warming the large
        // grade shader during launch kept the UI's own first frame waiting until Android closed the app, so the warm-up
        // starts only once the first frame has reached the screen, and a little after.
        ShaderCache.dir = java.io.File(cacheDir, "shaders")
        val root = window.decorView
        root.viewTreeObserver.registerFrameCommitCallback(object : Runnable {
            override fun run() {
                root.viewTreeObserver.unregisterFrameCommitCallback(this)
                root.postDelayed({ ShaderCache.warmUp(this@MainActivity) }, 600)
            }
        })
    }

    /**
     * Debug builds only: `adb shell am start -f 0x20000000 -n <app>/.MainActivity --es debugImport <file in the app's
     * own storage, or its folder in Android/data>` imports that file into the open project, for testing without the picker.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (shared(intent)) return
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

    /** Media shared to Graden opens in a new project. True if [intent] was a share. */
    private fun shared(intent: android.content.Intent?): Boolean {
        val uris = when (intent?.action) {
            android.content.Intent.ACTION_SEND -> listOfNotNull(intent.parcelable(android.content.Intent.EXTRA_STREAM))
            android.content.Intent.ACTION_SEND_MULTIPLE -> intent.parcelables(android.content.Intent.EXTRA_STREAM)
            else -> return false
        }
        vm.openShared(uris)
        return true
    }

    @Suppress("DEPRECATION")
    private fun android.content.Intent.parcelable(key: String): android.net.Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, android.net.Uri::class.java) else getParcelableExtra(key)

    @Suppress("DEPRECATION")
    private fun android.content.Intent.parcelables(key: String): List<android.net.Uri> =
        (if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, android.net.Uri::class.java) else getParcelableArrayListExtra(key)).orEmpty()

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // A foldable opened or closed: the same rule as at start.
        requestedOrientation = if (newConfig.smallestScreenWidthDp >= 600) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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
