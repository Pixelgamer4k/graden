package com.graden.editor.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's icon set: thin, round-capped line icons on a 24-unit grid, in the style of the
 * design reference. Each is built from SVG path data and takes its colour from the tint.
 */
object Lucide {
    private fun icon(vararg paths: String, filled: Boolean = false): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            for (d in paths) addPath(
                pathData = addPathNodes(d),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} $cy a$r $r 0 1 0 ${2 * r} 0 a$r $r 0 1 0 ${-2 * r} 0 Z"

    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} $y h${w - 2 * r} a$r $r 0 0 1 $r $r v${h - 2 * r} a$r $r 0 0 1 ${-r} $r h${-(w - 2 * r)} a$r $r 0 0 1 ${-r} ${-r} v${-(h - 2 * r)} a$r $r 0 0 1 $r ${-r} Z"

    // Top bars
    val X by lazy { icon("M18 6 6 18", "M6 6 18 18") }
    val Folder by lazy {
        icon("M20 20 a2 2 0 0 0 2 -2 V8 a2 2 0 0 0 -2 -2 h-7.9 a2 2 0 0 1 -1.69 -0.9 L9.6 3.9 A2 2 0 0 0 7.93 3 H4 a2 2 0 0 0 -2 2 v13 a2 2 0 0 0 2 2 Z", "M2 10 h20")
    }
    val Globe by lazy { icon(circle(12f, 12f, 10f), "M12 2 a14.5 14.5 0 0 0 0 20 a14.5 14.5 0 0 0 0 -20", "M2 12 h20") }
    val Upload by lazy { icon("M21 15 v4 a2 2 0 0 1 -2 2 H5 a2 2 0 0 1 -2 -2 v-4", "M17 8 l-5 -5 l-5 5", "M12 3 v12") }
    val Info by lazy { icon(circle(12f, 12f, 10f), "M12 16 v-4", "M12 8 h0.01") }

    // Transport
    // Transport: solid shapes, as on a hardware deck.
    val PrevCut by lazy {
        icon("M5.5 5 h2.4 v14 h-2.4 Z", "M19 6 v12 a0.8 0.8 0 0 1 -1.25 0.66 L9.6 12.66 a0.8 0.8 0 0 1 0 -1.32 L17.75 5.34 A0.8 0.8 0 0 1 19 6 Z", filled = true)
    }
    val NextCut by lazy {
        icon("M16.1 5 h2.4 v14 h-2.4 Z", "M5 6 v12 a0.8 0.8 0 0 0 1.25 0.66 L14.4 12.66 a0.8 0.8 0 0 0 0 -1.32 L6.25 5.34 A0.8 0.8 0 0 0 5 6 Z", filled = true)
    }
    val FrameBack by lazy {
        icon("M15.5 7.5 v9 a0.7 0.7 0 0 1 -1.1 0.57 L8.4 12.57 a0.7 0.7 0 0 1 0 -1.14 L14.4 6.93 A0.7 0.7 0 0 1 15.5 7.5 Z", "M17.6 7 h1.5 v10 h-1.5 Z", filled = true)
    }
    val FrameForward by lazy {
        icon("M8.5 7.5 v9 a0.7 0.7 0 0 0 1.1 0.57 L15.6 12.57 a0.7 0.7 0 0 0 0 -1.14 L9.6 6.93 A0.7 0.7 0 0 0 8.5 7.5 Z", "M4.9 7 h1.5 v10 h-1.5 Z", filled = true)
    }
    val ChevronDown by lazy { icon("M6 9 l6 6 l6 -6") }
    val ChevronRight by lazy { icon("M9 6 l6 6 l-6 6") }
    val Share by lazy { icon("M4 12 v7 a1 1 0 0 0 1 1 h14 a1 1 0 0 0 1 -1 v-7", "M16 6 l-4 -4 l-4 4", "M12 2 v13") }
    val Alert by lazy { icon("M10.3 3.9 L1.8 18 a2 2 0 0 0 1.7 3 h17 a2 2 0 0 0 1.7 -3 L13.7 3.9 a2 2 0 0 0 -3.4 0 Z", "M12 9 v4", "M12 17 h.01") }
    val SkipBack by lazy { icon("M19 20 L9 12 L19 4 Z", "M5 19 V5") }
    val SkipForward by lazy { icon("M5 4 L15 12 L5 20 Z", "M19 5 V19") }
    val ChevronsLeft by lazy { icon("M11 17 l-5 -5 l5 -5", "M18 17 l-5 -5 l5 -5") }
    val ChevronsRight by lazy { icon("M6 17 l5 -5 l-5 -5", "M13 17 l5 -5 l-5 -5") }
    val Play by lazy { icon("M6 3 L20 12 L6 21 Z", filled = true) }
    val Pause by lazy { icon(rect(5f, 4f, 4f, 16f, 1f), rect(15f, 4f, 4f, 16f, 1f), filled = true) }
    val Undo by lazy { icon("M9 14 L4 9 l5 -5", "M4 9 h10.5 a5.5 5.5 0 0 1 5.5 5.5 a5.5 5.5 0 0 1 -5.5 5.5 H11") }
    val Redo by lazy { icon("M15 14 l5 -5 l-5 -5", "M20 9 H9.5 A5.5 5.5 0 0 0 4 14.5 A5.5 5.5 0 0 0 9.5 20 H13") }

    // Timeline
    val Lock by lazy { icon(rect(3f, 11f, 18f, 11f, 2f), "M7 11 V7 a5 5 0 0 1 10 0 v4") }
    val LockOpen by lazy { icon(rect(3f, 11f, 18f, 11f, 2f), "M7 11 V7 a5 5 0 0 1 9.9 -1") }
    val Eye by lazy {
        icon("M2.06 12.35 a1 1 0 0 1 0 -0.7 a10.75 10.75 0 0 1 19.88 0 a1 1 0 0 1 0 0.7 a10.75 10.75 0 0 1 -19.88 0 Z", circle(12f, 12f, 3f))
    }
    val EyeOff by lazy {
        icon(
            "M10.73 5.08 A10.43 10.43 0 0 1 12 5 c7 0 10 7 10 7 a13.16 13.16 0 0 1 -1.67 2.68",
            "M6.61 6.61 A13.53 13.53 0 0 0 2 12 s3 7 10 7 a9.74 9.74 0 0 0 5.39 -1.61",
            "M2 2 L22 22", "M14.12 14.12 a3 3 0 1 1 -4.24 -4.24",
        )
    }
    private const val Speaker = "M11 4.7 a0.7 0.7 0 0 0 -1.2 -0.5 L6.41 7.59 A1.4 1.4 0 0 1 5.42 8 H3 a1 1 0 0 0 -1 1 v6 a1 1 0 0 0 1 1 h2.42 a1.4 1.4 0 0 1 1 0.41 l3.38 3.39 A0.7 0.7 0 0 0 11 19.3 Z"
    val Volume by lazy { icon(Speaker, "M16 9 a5 5 0 0 1 0 6", "M19.36 18.36 a9 9 0 0 0 0 -12.73") }
    val VolumeX by lazy { icon(Speaker, "M22 9 l-6 6", "M16 9 l6 6") }
    val Plus by lazy { icon("M5 12 h14", "M12 5 v14") }
    val Magnet by lazy {
        icon("M6 15 l-4 -4 l6.75 -6.77 a7.79 7.79 0 0 1 11 11 L13 22 l-4 -4 l6.39 -6.36 a2.14 2.14 0 0 0 -3 -3 L6 15", "M5 8 l4 4", "M12 15 l4 4")
    }

    // Clip kinds
    val Film by lazy {
        icon(rect(3f, 3f, 18f, 18f, 2f), "M7 3 v18", "M3 7.5 h4", "M3 12 h18", "M3 16.5 h4", "M17 3 v18", "M17 7.5 h4", "M17 16.5 h4")
    }
    val Image by lazy { icon(rect(3f, 3f, 18f, 18f, 2f), circle(9f, 9f, 2f), "M21 15 l-3.09 -3.09 a2 2 0 0 0 -2.82 0 L6 21") }

    // Tools
    val Scissors by lazy { icon(circle(6f, 6f, 3f), circle(6f, 18f, 3f), "M8.12 8.12 L12 12", "M20 4 L8.12 15.88", "M14.8 14.8 L20 20") }
    val Wand by lazy {
        icon(
            "M21.64 3.64 l-1.28 -1.28 a1.21 1.21 0 0 0 -1.72 0 L2.36 18.64 a1.21 1.21 0 0 0 0 1.72 l1.28 1.28 a1.2 1.2 0 0 0 1.72 0 L21.64 5.36 a1.2 1.2 0 0 0 0 -1.72 Z",
            "M14 7 l3 3", "M5 6 v4", "M19 14 v4", "M10 2 v2", "M7 8 H3", "M21 16 h-4", "M11 3 H9",
        )
    }
    val Split by lazy { icon("M16 3 h5 v5", "M8 3 H3 v5", "M12 22 v-8.3 a4 4 0 0 0 -1.17 -2.87 L3 3", "M15 9 l6 -6") }
    val Trash by lazy {
        icon(
            "M3 6 h18", "M19 6 v14 a2 2 0 0 1 -2 2 H7 a2 2 0 0 1 -2 -2 V6", "M8 6 V4 a2 2 0 0 1 2 -2 h4 a2 2 0 0 1 2 2 v2",
            "M10 11 v6", "M14 11 v6",
        )
    }
    /** A lens aperture: the camera's own (RAW) data. */
    val Aperture by lazy {
        icon(circle(12f, 12f, 10f), "M14.31 8 l5.74 9.94", "M9.69 8 h11.48", "M7.38 12 l5.74 -9.94", "M9.69 16 L3.95 6.06", "M14.31 16 H2.83", "M16.62 12 l-5.74 9.94")
    }
    /** A frame whose wobble is straightened: stabilisation. */
    val Stabilize by lazy {
        icon(
            "M3 7 V5 a2 2 0 0 1 2 -2 h2", "M17 3 h2 a2 2 0 0 1 2 2 v2", "M21 17 v2 a2 2 0 0 1 -2 2 h-2", "M7 21 H5 a2 2 0 0 1 -2 -2 v-2",
            "M7 12 c1.5 -3 3.5 -3 5 0 s3.5 3 5 0",
        )
    }
    /** A level: lock horizon. */
    val Horizon by lazy { icon("M2 12 h4", "M18 12 h4", "M12 8 a4 4 0 1 1 0 8 a4 4 0 1 1 0 -8", "M8 12 h8") }
    /** A tripod's lock: camera lock. */
    val Tripod by lazy { icon(rect(7f, 3f, 10f, 7f, 2f), "M12 10 v3", "M12 13 L6 21", "M12 13 L18 21", "M12 13 v8") }
    val MoreVertical by lazy { icon(circle(12f, 5f, 1f), circle(12f, 12f, 1f), circle(12f, 19f, 1f), filled = true) }
    val Music by lazy { icon("M9 18 V5 l12 -2 v13", circle(6f, 18f, 3f), circle(18f, 16f, 3f)) }
    val Users by lazy { icon("M16 21 v-2 a4 4 0 0 0 -4 -4 H6 a4 4 0 0 0 -4 4 v2", circle(9f, 7f, 4f), "M22 21 v-2 a4 4 0 0 0 -3 -3.87", "M16 3.13 a4 4 0 0 1 0 7.75") }
    val AudioWave by lazy { icon("M2 13 a2 2 0 0 0 2 -2 V7 a2 2 0 0 1 4 0 v13 a2 2 0 0 0 4 0 V4 a2 2 0 0 1 4 0 v13 a2 2 0 0 0 4 0 v-4 a2 2 0 0 1 2 -2") }
    val Captions by lazy { icon(rect(3f, 5f, 18f, 14f, 2f), "M7 15 h4", "M15 15 h2", "M7 11 h2", "M13 11 h4") }
    /** Order: arrows up and down. */
    val ArrowUpDown by lazy { icon("m21 16 -4 4 -4 -4", "M17 20 V4", "m3 8 4 -4 4 4", "M7 4 v16") }
    /** Font: a big A and a small a. */
    val FontAa by lazy { icon("M3 19 L8.5 5 L14 19", "M5 14 h7", "M21 19 v-6.5 a3 3 0 0 0 -6 0", "M21 15.5 a3 3 0 1 1 -3 -3") }
    val TypeT by lazy { icon("M4 7 V4 H20 V7", "M9 20 h6", "M12 4 v16") }
    val Download by lazy { icon("M21 15 v4 a2 2 0 0 1 -2 2 H5 a2 2 0 0 1 -2 -2 v-4", "M7 10 l5 5 l5 -5", "M12 15 V3") }
    val Activity by lazy { icon("M22 12 h-4 l-3 9 L9 3 l-3 9 H2") }
    /** A gyroscope: the visual gyro stabiliser. */
    val Gyro by lazy { icon("M2 12 a10 4 0 1 0 20 0 a10 4 0 1 0 -20 0", "M12 2 a4 10 0 1 0 0 20 a4 10 0 1 0 0 -20", circle(12f, 12f, 1.6f)) }
    /** Stacked flat layers: the classic (2D) stabiliser methods. */
    val Planes by lazy { icon("M12 3 L22 8 L12 13 L2 8 Z", "M2 13 L12 18 L22 13", "M2 17.5 L12 22.5 L22 17.5") }
    val Crop by lazy { icon("M6 2 v14 a2 2 0 0 0 2 2 h14", "M18 22 V8 a2 2 0 0 0 -2 -2 H2") }
    val Flip by lazy {
        icon(
            "M8 3 H5 a2 2 0 0 0 -2 2 v14 a2 2 0 0 0 2 2 h3", "M16 3 h3 a2 2 0 0 1 2 2 v14 a2 2 0 0 1 -2 2 h-3",
            "M12 20 v2", "M12 14 v2", "M12 8 v2", "M12 2 v2",
        )
    }
    val RotateCcw by lazy { icon("M3 12 a9 9 0 1 0 9 -9 a9.75 9.75 0 0 0 -6.74 2.74 L3 8", "M3 3 v5 h5") }
    val Copy by lazy { icon(rect(8f, 8f, 14f, 14f, 2f), "M4 16 a2 2 0 0 1 -2 -2 V4 a2 2 0 0 1 2 -2 h10 a2 2 0 0 1 2 2") }

    val AnimIn by lazy { icon("M15 3 h4 a2 2 0 0 1 2 2 v14 a2 2 0 0 1 -2 2 h-4", "M10 17 l5 -5 l-5 -5", "M15 12 H3") }
    val AnimOut by lazy { icon("M9 21 H5 a2 2 0 0 1 -2 -2 V5 a2 2 0 0 1 2 -2 h4", "M16 17 l5 -5 l-5 -5", "M21 12 H9") }
    val Check by lazy { icon("M20 6 L9 17 l-5 -5") }
    val Ban by lazy { icon(circle(12f, 12f, 10f), "M4.9 4.9 l14.2 14.2") }

    val Bot by lazy { icon("M12 8 V4 H8", rect(4f, 8f, 16f, 12f, 2f), "M2 14 h2", "M20 14 h2", "M15 13 v2", "M9 13 v2") }

    val Save by lazy {
        icon(
            "M15.2 3 a2 2 0 0 1 1.4 0.6 l3.8 3.8 a2 2 0 0 1 0.6 1.4 V19 a2 2 0 0 1 -2 2 H5 a2 2 0 0 1 -2 -2 V5 a2 2 0 0 1 2 -2 Z",
            "M17 21 v-7 a1 1 0 0 0 -1 -1 H8 a1 1 0 0 0 -1 1 v7", "M7 3 v4 a1 1 0 0 0 1 1 h7",
        )
    }

    val FlipV by lazy {
        icon("M3 8 V5 a2 2 0 0 1 2 -2 h14 a2 2 0 0 1 2 2 v3", "M3 16 v3 a2 2 0 0 0 2 2 h14 a2 2 0 0 0 2 -2 v-3", "M4 12 H2", "M10 12 H8", "M16 12 h-2", "M22 12 h-2")
    }
    val Droplet by lazy { icon("M12 22 a7 7 0 0 0 7 -7 c0 -2 -1 -3.9 -3 -5.5 s-3.5 -4 -4 -6.5 c-0.5 2.5 -2 4.9 -4 6.5 C6 11.1 5 13 5 15 a7 7 0 0 0 7 7 Z") }
    val UserCut by lazy { icon(circle(12f, 8f, 4f), "M4 21 v-1 a6 6 0 0 1 6 -6 h4 a6 6 0 0 1 6 6 v1", "M2 2 l3 3", "M22 2 l-3 3") }
    val Circle by lazy { icon(circle(12f, 12f, 9f)) }
    val Feather by lazy { icon("M12.67 19 a2 2 0 0 0 1.416 -0.588 l6.154 -6.172 a6 6 0 0 0 -8.49 -8.49 L5.586 9.914 A2 2 0 0 0 5 11.328 V18 a1 1 0 0 0 1 1 Z", "M16 8 L2 22", "M17.5 15 H9") }

    val Gauge by lazy { icon("m12 14 4 -4", "M3.34 19 a10 10 0 1 1 17.32 0") }

    val Pencil by lazy { icon("M21.17 6.81 a1 1 0 0 0 -3.99 -3.99 L3.84 16.17 a2 2 0 0 0 -0.5 0.83 l-1.32 4.35 a0.5 0.5 0 0 0 0.62 0.62 l4.35 -1.32 a2 2 0 0 0 0.83 -0.5 Z", "m15 5 4 4") }

    // Pages
    val Palette by lazy {
        icon(
            "M12 2 C6.5 2 2 6.5 2 12 s4.5 10 10 10 c0.93 0 1.65 -0.75 1.65 -1.69 c0 -0.44 -0.18 -0.83 -0.44 -1.12 c-0.29 -0.29 -0.44 -0.65 -0.44 -1.13 a1.64 1.64 0 0 1 1.67 -1.67 h2 c3.05 0 5.55 -2.5 5.55 -5.55 C21.97 6.01 17.46 2 12 2 Z",
            circle(13.5f, 6.5f, 0.6f), circle(17.5f, 10.5f, 0.6f), circle(8.5f, 7.5f, 0.6f), circle(6.5f, 12.5f, 0.6f),
        )
    }
    val Sparkles by lazy {
        icon(
            "M9.94 15.5 A2 2 0 0 0 8.5 14.06 l-6.14 -1.58 a0.5 0.5 0 0 1 0 -0.96 L8.5 9.94 A2 2 0 0 0 9.94 8.5 l1.58 -6.14 a0.5 0.5 0 0 1 0.96 0 L14.06 8.5 A2 2 0 0 0 15.5 9.94 l6.14 1.58 a0.5 0.5 0 0 1 0 0.96 L15.5 14.06 a2 2 0 0 0 -1.44 1.44 l-1.58 6.14 a0.5 0.5 0 0 1 -0.96 0 Z",
            "M20 3 v4", "M22 5 h-4", "M4 17 v2", "M5 18 H3",
        )
    }
    val AudioLines by lazy { icon("M2 10 v3", "M6 6 v11", "M10 3 v18", "M14 8 v7", "M18 5 v13", "M22 10 v3") }

    // Color page
    val Settings by lazy {
        icon("M20 7 h-9", "M14 17 H5", circle(17f, 17f, 3f), circle(7f, 7f, 3f))
    }
    val Maximize by lazy { icon("M15 3 h6 v6", "M9 21 H3 v-6", "M21 3 l-7 7", "M3 21 l7 -7") }
    val Minimize by lazy { icon("M4 14 h6 v6", "M20 10 h-6 V4", "M14 10 l7 -7", "M3 21 l7 -7") }
    val SplitView by lazy { icon(rect(3f, 3f, 18f, 18f, 2f), "M12 3 v18") }
}
