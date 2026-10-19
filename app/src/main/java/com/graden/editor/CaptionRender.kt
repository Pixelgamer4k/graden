package com.graden.editor

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Texts (captions and titles) drawn on the video: the picture showing, not the whole canvas, so a wide video in a tall
 * reel has its captions on the video. Sizes follow the video's shorter side, so a text looks the same on a phone's
 * preview, a 1080p export and a 4K one; preview and export both draw here. With the default look a caption is drawn
 * as YouTube and Netflix show subtitles: white, a soft dark edge and shadow, centred near the bottom.
 */
object CaptionRender {
    /**
     * Where the video is on a [cw] × [ch] canvas at [us]: the top picture showing (its fit or fill, place, size and
     * crop), within the canvas; the whole canvas when no picture shows.
     */
    fun videoRect(tracks: List<Track>, assets: Map<Int, MediaAsset>, hidden: Set<Int>, us: Long, cw: Float, ch: Float, fill: Boolean): RectF {
        for (t in tracks.asReversed()) {
            if (!t.pictures || t.id in hidden) continue
            val c = t.clipAt(us) ?: continue
            val a = assets[c.assetId] ?: continue
            if (a.isAudio) continue
            val aspect = if (a.width > 0 && a.height > 0) a.width.toFloat() / a.height else 16f / 9f
            val (bw, bh) = CanvasGeoMath.base(cw, ch, aspect, fill)
            val tf = c.tf
            val fw = bw * tf.scale; val fh = bh * tf.scale
            val w = fw * (1 - tf.cropL - tf.cropR); val h = fh * (1 - tf.cropT - tf.cropB)
            val x = cw / 2 + tf.x * cw + fw * (tf.cropL - tf.cropR) / 2
            val y = ch / 2 + tf.y * ch + fh * (tf.cropT - tf.cropB) / 2
            val r = RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
            if (r.intersect(0f, 0f, cw, ch) && r.width() > 8 && r.height() > 8) return r
        }
        return RectF(0f, 0f, cw, ch)
    }

    /** The second speaker's line: a softer grey, as two-speaker subtitles are shown. */
    private const val SECOND = 0xFFB4B8C2.toInt()

    /** The text's size in pixels on a video [area]. */
    fun textSize(cap: Caption, area: RectF) = min(area.width(), area.height()) * 0.052f * Caption.SCALES[cap.size.coerceIn(0, 4)] * cap.scale

    /** A text laid out: its two layouts (fill, edge), size, and where its centre goes. */
    private class Laid(val fill: StaticLayout, val edge: StaticLayout?, val size: Float, val cx: Float, val cy: Float, val w: Float, val h: Float)

    private fun lay(cap: Caption, area: RectF, reveal: Float): Laid? {
        val w = area.width(); val h = area.height()
        val l = cap.look
        val plain = if (l.caps) cap.text.uppercase() else cap.text
        if (plain.isBlank() || w <= 1 || h <= 1) return null
        val size = textSize(cap, area)
        val firstBreak = plain.indexOf('\n')
        fun spans(edge: Boolean): CharSequence {
            val s = SpannableString(plain)
            if (!edge && cap.tintFrom in 1 until plain.length) s.setSpan(ForegroundColorSpan(SECOND), cap.tintFrom, plain.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (l.sub != 1f && firstBreak > 0) {
                s.setSpan(RelativeSizeSpan(l.sub), firstBreak + 1, plain.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (!edge) s.setSpan(ForegroundColorSpan(l.subColor), firstBreak + 1, plain.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            // Typewriter: the letters not typed yet are there (so nothing moves) but unseen.
            val shown = (plain.length * reveal).roundToInt()
            if (shown < plain.length) s.setSpan(ForegroundColorSpan(Color.TRANSPARENT), shown, plain.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return s
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = TextFonts.typeface(l.font, l.weight); textSize = size; letterSpacing = l.letter
            isFakeBoldText = TextFonts.fakeBold(l.font, l.weight); textSkewX = if (l.italic) -0.22f else 0f
        }
        val maxW = (w * 0.9f).roundToInt().coerceAtLeast(1)
        val align = when (l.align) { 0 -> Layout.Alignment.ALIGN_NORMAL; 2 -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_CENTER }
        fun build(text: CharSequence, p: TextPaint, width: Int) = StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setAlignment(align).setLineSpacing(0f, l.line).setIncludePad(false).build()
        val fillText = spans(false)
        // As wide as its longest line, so left and right alignment line up with the block's own edges.
        val probe = build(fillText, paint, maxW)
        var widest = 0f
        for (i in 0 until probe.lineCount) widest = max(widest, probe.getLineWidth(i))
        val bw = ceil(widest).toInt().coerceIn(1, maxW)
        val fill = build(fillText, TextPaint(paint).apply { color = l.color }, bw)
        val edge = if (l.stroke > 0f) build(spans(true), TextPaint(paint).apply {
            color = l.strokeColor; style = Paint.Style.STROKE; strokeWidth = size * l.stroke; strokeJoin = Paint.Join.ROUND
        }, bw) else null
        val bh = fill.height.toFloat()
        val cx: Float; val cy: Float
        if (cap.placed) { cx = area.left + cap.x * w; cy = area.top + cap.y * h }
        else {
            // The subtitle place: above the video's bottom by 7% of its height (12% on a tall video, clear of the apps' buttons).
            cx = area.centerX(); cy = area.bottom - h * (if (h > w) 0.12f else 0.07f) - bh / 2
        }
        return Laid(fill, edge, size, cx, cy, bw.toFloat(), bh)
    }

    /** Where [cap] is drawn on [area] (not turned), with room for its box and decoration: for touching it. */
    fun bounds(cap: Caption, area: RectF): RectF? {
        val d = lay(cap, area, 1f) ?: return null
        val pad = d.size * 0.5f
        return RectF(d.cx - d.w / 2 - pad, d.cy - d.h / 2 - pad, d.cx + d.w / 2 + pad, d.cy + d.h / 2 + pad)
    }

    /** Draws [cap] through [c] on the video at [area] (pixels of the canvas [c] draws on), [t] µs into its [durUs]. */
    fun draw(c: Canvas, cap: Caption, area: RectF, t: Long = Long.MAX_VALUE / 4, durUs: Long = Long.MAX_VALUE / 2) {
        val a = if (t == Long.MAX_VALUE / 4) TextAnim.State() else TextAnim.at(cap, t, durUs)
        val alpha = a.alpha * cap.look.opacity
        if (alpha <= 0.003f || a.wipe <= 0f) return
        val d = lay(cap, area, a.reveal) ?: return
        val l = cap.look
        val size = d.size
        c.save()
        c.translate(d.cx + a.dx * size, d.cy + a.dy * size)
        if (cap.rot + a.rot != 0f) c.rotate(cap.rot + a.rot)
        if (a.scale != 1f) c.scale(a.scale, a.scale)
        // From here the text's own box, centred on its middle.
        val left = -d.w / 2; val top = -d.h / 2
        val pad = size * 0.6f
        if (a.wipe < 1f) c.clipRect(left - pad, top - pad, left - pad + (d.w + 2 * pad) * a.wipe, top + d.h + pad)
        val layer = if (alpha < 0.999f) c.saveLayerAlpha(left - pad * 2, top - pad * 2, left + d.w + pad * 2, top + d.h + pad * 2, (alpha * 255).roundToInt()) else -1
        // A box behind each line.
        if (Color.alpha(l.box) > 0) {
            val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = l.box }
            val px = size * 0.32f; val py = size * 0.10f; val r = size * l.boxRound
            for (i in 0 until d.fill.lineCount) {
                val lw = d.fill.getLineWidth(i); if (lw <= 0f) continue
                val ll = left + d.fill.getLineLeft(i)
                c.drawRoundRect(ll - px, top + d.fill.getLineTop(i) - py, ll + lw + px, top + d.fill.getLineBottom(i) + py, r, r, bp)
            }
        }
        decor(c, l, size, left, top, d.w, d.h)
        c.translate(left, top)
        d.edge?.draw(c)
        if (l.shadow > 0f) d.fill.paint.setShadowLayer(size * l.shadow, 0f, size * 0.05f, l.shadowColor)
        d.fill.draw(c)
        if (layer >= 0) c.restoreToCount(layer)
        c.restore()
    }

    private fun decor(c: Canvas, l: TextLook, size: Float, x: Float, y: Float, w: Float, h: Float) {
        if (l.decor == TextLook.DECOR_NONE) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = l.decorColor }
        when (l.decor) {
            TextLook.DECOR_UNDERLINE -> c.drawRoundRect(x, y + h + size * 0.14f, x + w, y + h + size * 0.24f, size * 0.03f, size * 0.03f, p)
            TextLook.DECOR_RULES -> {
                val t = size * 0.035f; val ext = size * 0.6f; val gap = size * 0.32f
                c.drawRect(x - ext, y - gap - t, x + w + ext, y - gap, p)
                c.drawRect(x - ext, y + h + gap, x + w + ext, y + h + gap + t, p)
            }
            TextLook.DECOR_FRAME -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.045f
                val pd = size * 0.42f
                c.drawRect(x - pd, y - pd * 0.7f, x + w + pd, y + h + pd * 0.7f, p)
            }
            TextLook.DECOR_SIDEBAR -> c.drawRect(x - size * 0.42f, y, x - size * 0.30f, y + h, p)
        }
    }

    /** A plain caption [plain] at [scale] (the caption page's size preview). */
    fun draw(c: Canvas, plain: String, area: RectF, scale: Float, tintFrom: Int = -1) =
        draw(c, Caption(plain, tintFrom = tintFrom, size = 2, scale = scale), area)
}
