package com.graden.editor

import android.os.Handler
import android.os.Looper
import android.util.Log

/** Diagnostic: counts how often each part of the screen recomposes, logged every two seconds. */
object RecomposeProbe {
    private val counts = HashMap<String, Int>()
    private val main = Handler(Looper.getMainLooper())
    private val report = object : Runnable {
        override fun run() {
            if (counts.isNotEmpty()) Log.d("PlayProbe", "RECOMPOSE " + counts.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" })
            counts.clear()
            main.postDelayed(this, 2000)
        }
    }

    init {
        main.postDelayed(report, 2000)
    }

    fun hit(name: String) {
        counts[name] = (counts[name] ?: 0) + 1
    }
}
