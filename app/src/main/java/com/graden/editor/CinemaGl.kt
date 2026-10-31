package com.graden.editor

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import com.graden.editor.cinema.GlRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * Cinema's picture, shared by the preview and export: Depth Studio's dof.frag, one pass, on the frame as it comes in and
 * before the grade. While the picture moves it runs smaller and with fewer samples; a frame that holds still, and export,
 * get the full render.
 */
class CinemaGl(private val context: Context) {
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    private var prog = 0
    private var failed = false
    private var depthTex = 0; private var fgTex = 0
    private var outTex = 0; private var outFbo = 0; private var outW = 0; private var outH = 0
    private var loaded: Any? = null
    private var loadedIndex = -1
    private var lastUs = Long.MIN_VALUE

    private fun program(): Int {
        if (prog != 0) return prog
        fun asset(n: String) = context.assets.open("cinema/$n").use { String(it.readBytes()) }
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) { android.util.Log.w("CinemaGl", GLES20.glGetShaderInfoLog(s)); failed = true }
            return s
        }
        // The one change from dof.frag: Graden's frames arrive in either row order, and the result keeps that order.
        val frag = asset("dof.frag")
            .replace("uniform int uPortrait,", "uniform int uTop;\nuniform int uPortrait,")
            // Preview-only views: the depth map with the focus plane marked in gold, and what stays sharp lit over a dimmed picture.
            .replace("if(uShowDepth==1){frag=vec4(vec3(d),1);return;}", "if(uShowDepth==1){frag=vec4(mix(vec3(d),vec3(.96,.77,.32),.9*(1.-smoothstep(.006,.016,abs(d-uFocus)))),1);return;}if(uShowDepth==2){float z=1.-smoothstep(.25,1.,coc(d));frag=vec4(mix(original*.3,mix(original,vec3(.96,.77,.32),.3),z),1);return;}")
            .replace("vec3 source(vec2 p){return texture(uImage,vec2(p.x,1.-p.y)).rgb;}", "vec3 source(vec2 p){return texture(uImage,vec2(p.x,uTop==1?p.y:1.-p.y)).rgb;}")
            .replace("in vec2 uv;out vec4 frag;", "in vec2 vPos;out vec4 frag;\nvec2 uv;")
            .replace("void main(){\n", "void main(){\n uv=vec2(vPos.x,uTop==1?vPos.y:1.-vPos.y);\n")
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, "#version 300 es\nin vec2 aPosition;out vec2 vPos;void main(){vPos=aPosition*.5+.5;gl_Position=vec4(aPosition,0.,1.);}"))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, frag))
        GLES20.glBindAttribLocation(p, 0, "aPosition")
        GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { android.util.Log.w("CinemaGl", GLES20.glGetProgramInfoLog(p)); failed = true }
        prog = p
        return p
    }

    private fun tex(): Int {
        val t = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return t
    }

    /**
     * Draws the lens over [src] ([sw] × [sh], RGBA) for file time [us], and returns the result. [top]: the texture's first
     * row is the top of the picture. Keeps the frame buffer, viewport and blending it found.
     */
    fun render(src: Int, sw: Int, sh: Int, top: Boolean, run: CinemaRun, us: Long, export: Boolean, view: Int): Int {
        val p = program()
        if (failed) return src
        val i = run.index(us)
        val maps = run.maps(i)
        val fb = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, it, 0) }[0]
        val vp = IntArray(4).also { GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, it, 0) }
        val blend = GLES20.glIsEnabled(GLES20.GL_BLEND); val scissor = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST)
        val was = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, it, 0) }[0]
        GLES20.glDisable(GLES20.GL_BLEND); GLES20.glDisable(GLES20.GL_SCISSOR_TEST)

        val settled = export || us == lastUs
        lastUs = us
        val k = if (settled) 1f else minOf(1f, 960f / max(sw, sh))
        val w = max((sw * k).toInt(), 8); val h = max((sh * k).toInt(), 8)
        if (w != outW || h != outH) {
            if (outTex != 0) { GLES20.glDeleteTextures(1, intArrayOf(outTex), 0); GLES20.glDeleteFramebuffers(1, intArrayOf(outFbo), 0) }
            outTex = tex()
            GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, w, h)
            outFbo = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, outTex, 0)
            outW = w; outH = h
        }
        if (depthTex == 0) { depthTex = tex(); fgTex = tex() }
        if (loaded !== run || loadedIndex != i) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
            GlRenderer.upload(depthTex, maps.depth, false)
            GlRenderer.upload(fgTex, maps.foreground, true)
            loaded = run; loadedIndex = i
        }
        val s = run.settings
        GLES20.glUseProgram(p)
        fun u(n: String) = GLES20.glGetUniformLocation(p, n)
        fun bind(unit: Int, t: Int, n: String) { GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t); GLES20.glUniform1i(u(n), unit) }
        bind(0, src, "uImage"); bind(1, depthTex, "uDepth"); bind(2, fgTex, "uForeground")
        GLES20.glUniform2f(u("uSize"), w.toFloat(), h.toFloat())
        GLES20.glUniform2f(u("uMapSize"), maps.foreground.width.toFloat(), maps.foreground.height.toFloat())
        GLES20.glUniform1f(u("uFocus"), run.track.focus[i])
        GLES20.glUniform1f(u("uStrength"), s.strength)
        GLES20.glUniform1f(u("uBand"), s.band)
        GLES20.glUniform1f(u("uRotation"), s.rotation)
        GLES20.glUniform1f(u("uHighlight"), s.highlight)
        GLES20.glUniform1f(u("uRim"), s.rim)
        GLES20.glUniform1f(u("uBreathing"), s.breathing)
        GLES20.glUniform1i(u("uShape"), s.shape)
        GLES20.glUniform1i(u("uSamples"), if (settled) s.samples else 24)
        GLES20.glUniform1i(u("uPortrait"), if (s.portrait) 1 else 0)
        GLES20.glUniform1i(u("uShowDepth"), view)
        GLES20.glUniform1i(u("uOriginal"), 0)
        GLES20.glUniform1i(u("uTop"), if (top) 1 else 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outFbo)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUseProgram(was)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb)
        GLES20.glViewport(vp[0], vp[1], vp[2], vp[3])
        if (blend) GLES20.glEnable(GLES20.GL_BLEND)
        if (scissor) GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        // A frame that stops moving is drawn again at full quality.
        if (!settled) settle?.invoke()
        return outTex
    }

    fun release() {
        if (outTex != 0) { GLES20.glDeleteTextures(1, intArrayOf(outTex), 0); GLES20.glDeleteFramebuffers(1, intArrayOf(outFbo), 0) }
        if (depthTex != 0) GLES20.glDeleteTextures(2, intArrayOf(depthTex, fgTex), 0)
        outTex = 0; depthTex = 0; fgTex = 0; outW = 0; outH = 0; loaded = null; loadedIndex = -1
    }

    companion object {
        /** What the preview shows: 0 the picture, 1 the depth map, 2 the zone in focus. */
        @Volatile var view = 0
        /** Asks the preview for a fresh frame (the view changed while paused). */
        @Volatile var redraw: (() -> Unit)? = null
        /** Asks for this frame again soon, so a frame that holds still comes up at full quality. */
        @Volatile var settle: (() -> Unit)? = null
    }
}
