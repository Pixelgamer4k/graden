// Point tracking for the visual gyro (VGyro.kt): each decoded frame's luma, as the sensor stored it, shrunk to the
// measuring size; corners found in one frame and followed into the next by pyramidal Lucas-Kanade (OpenCV,
// Apache-2.0), both ways. Each pair of frames is tracked on its own, so several pairs run at once on long-lived
// threads (short-lived ones would stay on the slow cores).
#include <jni.h>
#include <android/log.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/video/tracking.hpp>
#include <atomic>
#include <chrono>
#include <cmath>
#include <random>
#include <vector>

namespace {

struct Tracker {
    int w = 0, h = 0;
    int maxPts = 500;
    std::mt19937 rnd{7};
};

std::atomic<long long> gShrinkUs{0}, gPyrUs{0}, gFlowUs{0}, gCornerUs{0};
std::atomic<int> gPairs{0};

long long nowUs() { return std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch()).count(); }

/** The plane [src] (row stride [rs], sample stride [ps]; 2: 16-bit samples, the high byte read) shrunk to [w]×[h]. */
void shrink(const uint8_t* src, int sw, int sh, int rs, int ps, int w, int h, cv::Mat& out) {
    const int hi = ps >= 2 ? 1 : 0;
    // Average of 2×2 samples across each output pixel's footprint: enough against aliasing for tracking, and the
    // source (a decoder buffer, often uncached) is read as little as possible.
    const float fx = float(sw) / w, fy = float(sh) / h;
    const int nx = std::min(2, std::max(1, int(fx))), ny = std::min(2, std::max(1, int(fy)));
    std::vector<int> col(size_t(w) * nx);
    for (int x = 0; x < w; ++x) for (int i = 0; i < nx; ++i) col[x * nx + i] = std::min(sw - 1, int((x + (i + 0.5f) / nx) * fx)) * ps + hi;
    for (int y = 0; y < h; ++y) {
        uint8_t* o = out.ptr<uint8_t>(y);
        const uint8_t* rows[2] = {nullptr, nullptr};
        for (int j = 0; j < ny; ++j) rows[j] = src + size_t(std::min(sh - 1, int((y + (j + 0.5f) / ny) * fy))) * rs;
        for (int x = 0; x < w; ++x) {
            int sum = 0;
            for (int j = 0; j < ny; ++j) for (int i = 0; i < nx; ++i) sum += rows[j][col[x * nx + i]];
            o[x] = uint8_t(sum / (nx * ny));
        }
    }
}

/**
 * How far each pair is from one similarity (turn, zoom and shift: what a camera's rotation does between two frames,
 * near enough), found by RANSAC with inliers within [thr] px.
 */
std::vector<float> agree(const std::vector<cv::Point2f>& a, const std::vector<cv::Point2f>& b, float thr, std::mt19937& rnd) {
    const int n = int(a.size());
    std::vector<float> res(n, 0.f);
    if (n < 6) return res;
    std::uniform_int_distribution<int> pick(0, n - 1);
    const float t2 = thr * thr;
    int bestC = -1; float bs[4] = {1, 0, 0, 0};
    for (int it = 0; it < 150; ++it) {
        int i = pick(rnd), j = pick(rnd);
        if (i == j) continue;
        float ax = a[j].x - a[i].x, ay = a[j].y - a[i].y, bx = b[j].x - b[i].x, by = b[j].y - b[i].y;
        float d = ax * ax + ay * ay;
        if (d < 400.f) continue;
        // b = [p -q; q p] a + t
        float p = (ax * bx + ay * by) / d, q = (ax * by - ay * bx) / d;
        float tx = b[i].x - (p * a[i].x - q * a[i].y), ty = b[i].y - (q * a[i].x + p * a[i].y);
        int c = 0;
        for (int k = 0; k < n; ++k) {
            float ex = p * a[k].x - q * a[k].y + tx - b[k].x, ey = q * a[k].x + p * a[k].y + ty - b[k].y;
            if (ex * ex + ey * ey < t2) ++c;
        }
        if (c > bestC) { bestC = c; bs[0] = p; bs[1] = q; bs[2] = tx; bs[3] = ty; }
    }
    for (int k = 0; k < n; ++k) {
        float ex = bs[0] * a[k].x - bs[1] * a[k].y + bs[2] - b[k].x, ey = bs[1] * a[k].x + bs[0] * a[k].y + bs[3] - b[k].y;
        res[k] = std::sqrt(ex * ex + ey * ey);
    }
    return res;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_graden_editor_VGyroNative_create(JNIEnv*, jclass, jint w, jint h, jint maxPts) {
    auto* t = new Tracker();
    t->w = w; t->h = h; t->maxPts = maxPts;
    cv::setNumThreads(1);   // pairs run side by side instead
    return reinterpret_cast<jlong>(t);
}

JNIEXPORT void JNICALL Java_com_graden_editor_VGyroNative_release(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Tracker*>(handle);
}

/** Shrinks a decoded frame's luma plane (direct buffer [buf], from byte [off]) into [dst] (a direct buffer, w×h). */
JNIEXPORT void JNICALL Java_com_graden_editor_VGyroNative_shrink(JNIEnv* env, jclass, jlong handle, jobject buf, jint off, jint sw, jint sh,
                                                                 jint rs, jint ps, jobject dst) {
    auto* t = reinterpret_cast<Tracker*>(handle);
    auto* base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buf));
    auto* d = static_cast<uint8_t*>(env->GetDirectBufferAddress(dst));
    if (!base || !d) return;
    cv::Mat out(t->h, t->w, CV_8U, d);
    long long c0 = nowUs();
    shrink(base + off, sw, sh, rs, ps, t->w, t->h, out);
    gShrinkUs += nowUs() - c0;
}

/**
 * Tracks one pair of shrunk frames ([a] then [b], w×h bytes each): corners of [a] followed into [b], written to [out]
 * as px, py, qx, qy (pixels) and how far (px) the pair is from the pair's overall motion - at most out.length / 5.
 * Returns how many; -1 when almost nothing could be followed (a cut). Each thread uses its own [handle].
 */
JNIEXPORT jint JNICALL Java_com_graden_editor_VGyroNative_pair(JNIEnv* env, jclass, jlong handle, jobject ja, jobject jb, jfloatArray out) {
    auto* t = reinterpret_cast<Tracker*>(handle);
    auto* da = static_cast<uint8_t*>(env->GetDirectBufferAddress(ja));
    auto* db = static_cast<uint8_t*>(env->GetDirectBufferAddress(jb));
    if (!da || !db) return 0;
    const cv::Mat A(t->h, t->w, CV_8U, da), Bm(t->h, t->w, CV_8U, db);
    const cv::Size win(15, 15);
    // The analysis resolution is chosen by the user (480 … 3840 px on the long side; 960 by default). The pyramid gets
    // a level more for each doubling over 960, so the same shake (in angle) is followed at any resolution; corners
    // are found on the level nearest 480 px, so finding them costs the same at any resolution.
    const double longSide = double(std::max(t->w, t->h));
    const int lv = std::min(6, std::max(2, 3 + int(std::lround(std::log2(longSide / 960.0)))));
    const int dl = std::max(0, int(std::lround(std::log2(longSide / 480.0))));
    long long c0 = nowUs();
    std::vector<cv::Mat> pa, pb;
    cv::buildOpticalFlowPyramid(A, pa, win, lv, true);
    cv::buildOpticalFlowPyramid(Bm, pb, win, lv, true);
    long long c1 = nowUs();
    // Corners spread over the picture, found on a reduced level (the tracker refines from full size).
    std::vector<cv::Point2f> p0;
    const size_t di = std::min(size_t(2 * dl), (pa.size() / 2 - 1) * 2);
    const cv::Mat& half = di > 0 ? pa[di] : A;   // with derivatives the pyramid holds image, derivative per level
    const float sc = float(A.cols) / half.cols;
    cv::goodFeaturesToTrack(half, p0, t->maxPts, 0.01, std::max(4.f, t->w / 80.f / sc), cv::noArray(), 3);
    for (auto& p : p0) { p.x = (p.x + 0.5f) * sc - 0.5f; p.y = (p.y + 0.5f) * sc - 0.5f; }
    long long c2 = nowUs();
    int count = 0;
    if (!p0.empty()) {
        std::vector<cv::Point2f> nx, back;
        std::vector<uchar> s1, s2;
        std::vector<float> e1, e2;
        const cv::TermCriteria tc(cv::TermCriteria::COUNT | cv::TermCriteria::EPS, 20, 0.03);
        cv::calcOpticalFlowPyrLK(pa, pb, p0, nx, s1, e1, win, lv, tc);
        cv::calcOpticalFlowPyrLK(pb, pa, nx, back, s2, e2, win, lv, tc);
        std::vector<cv::Point2f> a, b;
        const float m = 2.f;
        for (size_t i = 0; i < nx.size(); ++i) {
            if (!s1[i] || !s2[i]) continue;
            float dx = back[i].x - p0[i].x, dy = back[i].y - p0[i].y;
            if (dx * dx + dy * dy > 0.16f) continue;
            if (nx[i].x < m || nx[i].y < m || nx[i].x > t->w - 1 - m || nx[i].y > t->h - 1 - m) continue;
            a.push_back(p0[i]); b.push_back(nx[i]);
        }
        if (p0.size() >= 30 && a.size() < std::max<size_t>(10, p0.size() * 15 / 100)) {
            count = -1;
        } else {
            std::vector<float> res = agree(a, b, 0.006f * t->w, t->rnd);
            jsize cap = env->GetArrayLength(out) / 5;
            std::vector<float> o;
            o.reserve(std::min<size_t>(a.size(), cap) * 5);
            for (size_t k = 0; k < a.size() && int(o.size() / 5) < cap; ++k) {
                o.push_back(a[k].x); o.push_back(a[k].y); o.push_back(b[k].x); o.push_back(b[k].y); o.push_back(res[k]);
            }
            count = int(o.size() / 5);
            if (count > 0) env->SetFloatArrayRegion(out, 0, jsize(o.size()), o.data());
        }
    }
    long long c3 = nowUs();
    gPyrUs += c1 - c0; gCornerUs += c2 - c1; gFlowUs += c3 - c2;
    int n = ++gPairs;
    if (n % 100 == 0)
        __android_log_print(ANDROID_LOG_INFO, "VGyro", "native per pair: shrink %.1f pyramids %.1f corners %.1f flow %.1f ms",
                            gShrinkUs / 1000.0 / n, gPyrUs / 1000.0 / n, gCornerUs / 1000.0 / n, gFlowUs / 1000.0 / n);
    return count;
}

}  // extern "C"
