// Software video encoders for export: x264 (H.264) and x265 (H.265), 8 or 10 bit, fed with rendered RGBA frames.
//
// Frames arrive as read back from GL: bottom row first, RGBA, either 8 bits per channel or half floats (for a 10-bit
// render). They are converted to Rec.709 limited-range YUV 4:2:0 in the layout the encoder wants, on several threads.

#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <math.h>
#include <thread>
#include <vector>
#include <algorithm>
#include <android/log.h>

extern "C" {
#include "x264.h"
}
#include "x265.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "SoftEnc", __VA_ARGS__)

namespace {

// ---- colour ------------------------------------------------------------------------------------------------

inline float half_to_float(uint16_t h) {
    uint32_t s = (h >> 15) & 1, e = (h >> 10) & 0x1f, m = h & 0x3ff;
    uint32_t f;
    if (e == 0) {
        if (m == 0) f = s << 31;
        else { e = 127 - 15 + 1; while (!(m & 0x400)) { m <<= 1; e--; } m &= 0x3ff; f = (s << 31) | (e << 23) | (m << 13); }
    } else if (e == 31) f = (s << 31) | 0x7f800000 | (m << 13);
    else f = (s << 31) | ((e + 127 - 15) << 23) | (m << 13);
    float r; memcpy(&r, &f, 4); return r;
}

/** Where the converted frame goes. mode 0: 8-bit planar; 1: 16-bit planar (10-bit values); 2: P010 (Y then UV, 16-bit, value << 6). */
struct Dest { uint8_t* y; uint8_t* u; uint8_t* v; int ys, us, vs; int mode; };

/** Rec.709, limited range. Rows [r0, r1) of the output (pairs of rows for chroma). */
/** fmt: 0 RGBA 8-bit, 1 RGBA half float, 2 packed 10-bit RGB. */
void convert_rows(const uint8_t* src, int fmt, int w, int h, int r0, int r1, Dest d) {
    const float kr = 0.2126f, kb = 0.0722f, kg = 1.f - kr - kb;
    const int depth = d.mode == 0 ? 8 : 10;
    const float maxv = (float)((1 << depth) - 1);
    const float ys = 219.f * (1 << (depth - 8)), yo = 16.f * (1 << (depth - 8));
    const float cs = 224.f * (1 << (depth - 8)), co = 128.f * (1 << (depth - 8));
    std::vector<float> rgb(w * 6);
    for (int y = r0; y < r1; y += 2) {
        // Two output rows; the source is stored bottom row first.
        for (int k = 0; k < 2; k++) {
            int oy = std::min(y + k, h - 1);
            int sy = h - 1 - oy;
            float* o = rgb.data() + k * w * 3;
            if (fmt == 1) {
                const uint16_t* p = (const uint16_t*)src + (size_t)sy * w * 4;
                for (int x = 0; x < w; x++) { o[x * 3] = half_to_float(p[x * 4]); o[x * 3 + 1] = half_to_float(p[x * 4 + 1]); o[x * 3 + 2] = half_to_float(p[x * 4 + 2]); }
            } else if (fmt == 2) {
                // RGB10_A2 read back as UNSIGNED_INT_2_10_10_10_REV: red in the low ten bits.
                const uint32_t* p = (const uint32_t*)src + (size_t)sy * w;
                for (int x = 0; x < w; x++) { uint32_t v = p[x]; o[x * 3] = (v & 0x3ff) / 1023.f; o[x * 3 + 1] = ((v >> 10) & 0x3ff) / 1023.f; o[x * 3 + 2] = ((v >> 20) & 0x3ff) / 1023.f; }
            } else {
                const uint8_t* p = src + (size_t)sy * w * 4;
                for (int x = 0; x < w; x++) { o[x * 3] = p[x * 4] / 255.f; o[x * 3 + 1] = p[x * 4 + 1] / 255.f; o[x * 3 + 2] = p[x * 4 + 2] / 255.f; }
            }
            if (y + k >= h) break;
            for (int x = 0; x < w; x++) {
                float r = std::min(std::max(o[x * 3], 0.f), 1.f), g = std::min(std::max(o[x * 3 + 1], 0.f), 1.f), b = std::min(std::max(o[x * 3 + 2], 0.f), 1.f);
                float yy = kr * r + kg * g + kb * b;
                int Y = (int)lrintf(std::min(std::max(yo + ys * yy, 0.f), maxv));
                if (d.mode == 0) d.y[(size_t)(y + k) * d.ys + x] = (uint8_t)Y;
                else ((uint16_t*)(d.y + (size_t)(y + k) * d.ys))[x] = (uint16_t)(d.mode == 2 ? Y << 6 : Y);
            }
        }
        // Chroma from the 2×2 average.
        const float* a = rgb.data();
        const float* b2 = rgb.data() + ((y + 1 < h) ? w * 3 : 0);
        int cy = y / 2;
        for (int x = 0; x < w; x += 2) {
            int x1 = std::min(x + 1, w - 1);
            float r = (a[x * 3] + a[x1 * 3] + b2[x * 3] + b2[x1 * 3]) * 0.25f;
            float g = (a[x * 3 + 1] + a[x1 * 3 + 1] + b2[x * 3 + 1] + b2[x1 * 3 + 1]) * 0.25f;
            float bb = (a[x * 3 + 2] + a[x1 * 3 + 2] + b2[x * 3 + 2] + b2[x1 * 3 + 2]) * 0.25f;
            r = std::min(std::max(r, 0.f), 1.f); g = std::min(std::max(g, 0.f), 1.f); bb = std::min(std::max(bb, 0.f), 1.f);
            float yy = kr * r + kg * g + kb * bb;
            float cb = (bb - yy) / 1.8556f, cr = (r - yy) / 1.5748f;
            int U = (int)lrintf(std::min(std::max(co + cs * cb, 0.f), maxv));
            int V = (int)lrintf(std::min(std::max(co + cs * cr, 0.f), maxv));
            int cx = x / 2;
            if (d.mode == 0) { d.u[(size_t)cy * d.us + cx] = (uint8_t)U; d.v[(size_t)cy * d.vs + cx] = (uint8_t)V; }
            else if (d.mode == 1) { ((uint16_t*)(d.u + (size_t)cy * d.us))[cx] = (uint16_t)U; ((uint16_t*)(d.v + (size_t)cy * d.vs))[cx] = (uint16_t)V; }
            else { uint16_t* uv = (uint16_t*)(d.u + (size_t)cy * d.us); uv[cx * 2] = (uint16_t)(U << 6); uv[cx * 2 + 1] = (uint16_t)(V << 6); }
        }
    }
}

void convert(const uint8_t* src, int fmt, int w, int h, Dest d) {
    int n = std::max(1u, std::min(8u, std::thread::hardware_concurrency()));
    int rows = ((h + 1) / 2 + n - 1) / n * 2;
    std::vector<std::thread> t;
    for (int i = 0; i < n; i++) {
        int r0 = i * rows, r1 = std::min(h, r0 + rows);
        if (r0 >= h) break;
        t.emplace_back(convert_rows, src, fmt, w, h, r0, r1, d);
    }
    for (auto& x : t) x.join();
}

// ---- encoders ----------------------------------------------------------------------------------------------

struct Enc {
    int codec;  // 0 x264, 1 x265
    int w, h, depth;
    x264_t* x4 = nullptr;
    x264_picture_t pic4;
    const x265_api* api = nullptr;
    x265_encoder* x5 = nullptr;
    x265_param* p5 = nullptr;
    x265_picture* pic5 = nullptr;
    std::vector<uint8_t> yuv;
    std::vector<uint8_t> headers;
    int64_t frame = 0;
};

/** Copies [n] NALs (Annex B, start codes included) into [out]; returns the byte count, or -1 if it does not fit. */
template <typename N>
int put_nals(N* nals, int n, uint8_t* out, int cap) {
    int size = 0;
    for (int i = 0; i < n; i++) {
        int s = nals[i].i_payload;
        if (size + s > cap) return -1;
        memcpy(out + size, nals[i].p_payload, s);
        size += s;
    }
    return size;
}

int put_nals5(x265_nal* nals, uint32_t n, uint8_t* out, int cap) {
    int size = 0;
    for (uint32_t i = 0; i < n; i++) {
        int s = (int)nals[i].sizeBytes;
        if (size + s > cap) return -1;
        memcpy(out + size, nals[i].payload, s);
        size += s;
    }
    return size;
}

}  // namespace

extern "C" {

/**
 * Opens an encoder. codec 0 x264, 1 x265. rc: 0 VBR (kbps), 1 CBR (kbps), 2 constant quality (CRF). preset: "medium"
 * etc. profile: "" for the default. Returns a handle, or 0.
 */
JNIEXPORT jlong JNICALL Java_com_graden_editor_export_SoftEncoder_nativeOpen(
        JNIEnv* env, jclass, jint codec, jint w, jint h, jint fpsNum, jint fpsDen, jint depth, jint rc, jint value, jint keyint, jstring jpreset) {
    const char* preset = env->GetStringUTFChars(jpreset, nullptr);
    Enc* e = new Enc();
    e->codec = codec; e->w = w; e->h = h; e->depth = depth;
    bool ok = false;
    if (codec == 0) {
        x264_param_t p;
        if (x264_param_default_preset(&p, preset, nullptr) == 0) {
            p.i_bitdepth = depth;
            p.i_csp = X264_CSP_I420 | (depth > 8 ? X264_CSP_HIGH_DEPTH : 0);
            p.i_width = w; p.i_height = h;
            p.i_fps_num = fpsNum; p.i_fps_den = fpsDen;
            p.i_timebase_num = fpsDen; p.i_timebase_den = fpsNum;
            p.b_vfr_input = 0;
            p.i_keyint_max = keyint > 0 ? keyint : (fpsNum + fpsDen - 1) / fpsDen;
            p.b_repeat_headers = 0;
            p.b_annexb = 1;
            // Frames in display order: Android's MP4 writer only takes display times, and with reordered (B) frames it
            // writes decode times a fraction of a millisecond apart, which strict tools reject.
            p.i_bframe = 0;
            p.i_log_level = X264_LOG_WARNING;
            p.vui.i_colorprim = 1; p.vui.i_transfer = 1; p.vui.i_colmatrix = 1; p.vui.b_fullrange = 0;
            if (rc == 2) { p.rc.i_rc_method = X264_RC_CRF; p.rc.f_rf_constant = (float)value; }
            else {
                p.rc.i_rc_method = X264_RC_ABR; p.rc.i_bitrate = value;
                if (rc == 1) { p.rc.i_vbv_max_bitrate = value; p.rc.i_vbv_buffer_size = value; p.i_nal_hrd = X264_NAL_HRD_CBR; }
                else { p.rc.i_vbv_max_bitrate = value * 3 / 2; p.rc.i_vbv_buffer_size = value * 2; }
            }
            x264_param_apply_profile(&p, depth > 8 ? "high10" : "high");
            e->x4 = x264_encoder_open(&p);
            if (e->x4) {
                x264_picture_init(&e->pic4);
                x264_nal_t* nals; int n;
                x264_encoder_headers(e->x4, &nals, &n);
                for (int i = 0; i < n; i++) e->headers.insert(e->headers.end(), nals[i].p_payload, nals[i].p_payload + nals[i].i_payload);
                ok = true;
            }
        }
    } else {
        e->api = x265_api_get(depth);
        if (e->api) {
            e->p5 = e->api->param_alloc();
            if (e->api->param_default_preset(e->p5, preset, nullptr) == 0) {
                x265_param* p = e->p5;
                p->sourceWidth = w; p->sourceHeight = h;
                p->fpsNum = fpsNum; p->fpsDenom = fpsDen;
                p->internalCsp = X265_CSP_I420;
                p->keyframeMax = keyint > 0 ? keyint : (fpsNum + fpsDen - 1) / fpsDen;
                p->bRepeatHeaders = 0;
                p->bAnnexB = 1;
                p->bframes = 0;  // see x264 above
                p->logLevel = X265_LOG_WARNING;
                p->vui.bEnableVideoSignalTypePresentFlag = 1;
                p->vui.bEnableColorDescriptionPresentFlag = 1;
                p->vui.colorPrimaries = 1; p->vui.transferCharacteristics = 1; p->vui.matrixCoeffs = 1;
                p->vui.bEnableVideoFullRangeFlag = 0;
                if (rc == 2) { p->rc.rateControlMode = X265_RC_CRF; p->rc.rfConstant = value; }
                else {
                    p->rc.rateControlMode = X265_RC_ABR; p->rc.bitrate = value;
                    if (rc == 1) { p->rc.vbvMaxBitrate = value; p->rc.vbvBufferSize = value; }
                    else { p->rc.vbvMaxBitrate = value * 3 / 2; p->rc.vbvBufferSize = value * 2; }
                }
                e->api->param_apply_profile(p, depth > 8 ? "main10" : "main");
                e->x5 = e->api->encoder_open(p);
                if (e->x5) {
                    e->pic5 = e->api->picture_alloc();
                    e->api->picture_init(p, e->pic5);
                    x265_nal* nals; uint32_t n;
                    e->api->encoder_headers(e->x5, &nals, &n);
                    for (uint32_t i = 0; i < n; i++) e->headers.insert(e->headers.end(), nals[i].payload, nals[i].payload + nals[i].sizeBytes);
                    ok = true;
                }
            }
        }
    }
    env->ReleaseStringUTFChars(jpreset, preset);
    if (!ok) {
        LOG("open failed: codec %d %dx%d depth %d", codec, w, h, depth);
        delete e;
        return 0;
    }
    int bps = depth > 8 ? 2 : 1;
    e->yuv.resize((size_t)w * h * bps * 3 / 2 + 64);
    return (jlong)e;
}

/** The stream's parameter sets (SPS/PPS, and VPS for H.265), Annex B. */
JNIEXPORT jbyteArray JNICALL Java_com_graden_editor_export_SoftEncoder_nativeHeaders(JNIEnv* env, jclass, jlong h) {
    Enc* e = (Enc*)h;
    jbyteArray a = env->NewByteArray((jsize)e->headers.size());
    env->SetByteArrayRegion(a, 0, (jsize)e->headers.size(), (const jbyte*)e->headers.data());
    return a;
}

/**
 * Encodes one frame (or, with a null frame, drains what is still inside). Writes the compressed data to [out] and its
 * frame number and keyframe flag to meta[0], meta[1]; returns the number of bytes (0 when nothing came out yet).
 */
JNIEXPORT jint JNICALL Java_com_graden_editor_export_SoftEncoder_nativeEncode(
        JNIEnv* env, jclass, jlong h, jobject frame, jint fmt, jobject out, jlongArray meta) {
    Enc* e = (Enc*)h;
    uint8_t* dst = (uint8_t*)env->GetDirectBufferAddress(out);
    int cap = (int)env->GetDirectBufferCapacity(out);
    jlong m[2] = {0, 0};
    int size = 0;
    int bps = e->depth > 8 ? 2 : 1;
    uint8_t* Y = e->yuv.data();
    uint8_t* U = Y + (size_t)e->w * e->h * bps;
    uint8_t* V = U + (size_t)(e->w / 2) * (e->h / 2) * bps;
    if (frame) {
        const uint8_t* src = (const uint8_t*)env->GetDirectBufferAddress(frame);
        Dest d{Y, U, V, e->w * bps, e->w / 2 * bps, e->w / 2 * bps, e->depth > 8 ? 1 : 0};
        convert(src, fmt, e->w, e->h, d);
    }
    if (e->codec == 0) {
        x264_nal_t* nals; int n; x264_picture_t pout;
        int r;
        if (frame) {
            e->pic4.img.i_csp = X264_CSP_I420 | (e->depth > 8 ? X264_CSP_HIGH_DEPTH : 0);
            e->pic4.img.i_plane = 3;
            e->pic4.img.plane[0] = Y; e->pic4.img.plane[1] = U; e->pic4.img.plane[2] = V;
            e->pic4.img.i_stride[0] = e->w * bps; e->pic4.img.i_stride[1] = e->w / 2 * bps; e->pic4.img.i_stride[2] = e->w / 2 * bps;
            e->pic4.i_pts = e->frame++;
            e->pic4.i_type = X264_TYPE_AUTO;
            r = x264_encoder_encode(e->x4, &nals, &n, &e->pic4, &pout);
        } else {
            if (!x264_encoder_delayed_frames(e->x4)) return -1;
            r = x264_encoder_encode(e->x4, &nals, &n, nullptr, &pout);
        }
        if (r > 0) {
            size = put_nals(nals, n, dst, cap);
            m[0] = pout.i_pts; m[1] = pout.b_keyframe ? 1 : 0;
        } else if (!frame && r == 0) return x264_encoder_delayed_frames(e->x4) ? 0 : -1;
    } else {
        x265_nal* nals; uint32_t n = 0;
        x265_picture pout;
        e->api->picture_init(e->p5, &pout);
        int r;
        if (frame) {
            x265_picture* p = e->pic5;
            p->planes[0] = Y; p->planes[1] = U; p->planes[2] = V;
            p->stride[0] = e->w * bps; p->stride[1] = e->w / 2 * bps; p->stride[2] = e->w / 2 * bps;
            p->bitDepth = e->depth;
            p->colorSpace = X265_CSP_I420;
            p->pts = e->frame++;
            r = e->api->encoder_encode(e->x5, &nals, &n, p, &pout);
        } else {
            r = e->api->encoder_encode(e->x5, &nals, &n, nullptr, &pout);
            if (r == 0) return -1;
        }
        if (r > 0 && n > 0) {
            size = put_nals5(nals, n, dst, cap);
            m[0] = pout.pts; m[1] = IS_X265_TYPE_I(pout.sliceType) ? 1 : 0;
        }
    }
    env->SetLongArrayRegion(meta, 0, 2, m);
    return size;
}

/** Converts a frame for a hardware encoder that takes 10-bit P010 input buffers. */
JNIEXPORT void JNICALL Java_com_graden_editor_export_SoftEncoder_nativeToP010(
        JNIEnv* env, jclass, jobject frame, jint fmt, jint w, jint h, jobject out, jint stride, jint sliceHeight) {
    const uint8_t* src = (const uint8_t*)env->GetDirectBufferAddress(frame);
    uint8_t* dst = (uint8_t*)env->GetDirectBufferAddress(out);
    Dest d{dst, dst + (size_t)stride * sliceHeight, nullptr, stride, stride, stride, 2};
    convert(src, fmt, w, h, d);
}

JNIEXPORT void JNICALL Java_com_graden_editor_export_SoftEncoder_nativeClose(JNIEnv*, jclass, jlong h) {
    Enc* e = (Enc*)h;
    if (!e) return;
    if (e->x4) x264_encoder_close(e->x4);
    if (e->x5) e->api->encoder_close(e->x5);
    if (e->pic5) e->api->picture_free(e->pic5);
    if (e->p5) e->api->param_free(e->p5);
    delete e;
}

}  // extern "C"
