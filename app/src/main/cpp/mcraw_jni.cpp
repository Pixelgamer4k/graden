// MotionCam RAW (.mcraw) for Graden: the MotionCam decoder (motioncam/, Apache-2.0) behind a small JNI API used by
// com.graden.editor.McRaw. One handle is one open file; a handle is used from one thread at a time.

#include <jni.h>
#include <android/log.h>
#include <motioncam/Decoder.hpp>

#include <cstdio>
#include <cstring>
#include <memory>
#include <unistd.h>

namespace {
    struct Handle {
        std::unique_ptr<motioncam::Decoder> decoder;
        std::vector<uint8_t> frame;
    };

    void fail(JNIEnv* env, const char* what) {
        __android_log_print(ANDROID_LOG_WARN, "McRaw", "%s", what);
        jclass c = env->FindClass("java/io/IOException");
        if (c) env->ThrowNew(c, what);
    }

    Handle* of(jlong h) { return reinterpret_cast<Handle*>(h); }
}

extern "C" {

JNIEXPORT jlong JNICALL Java_com_graden_editor_McRaw_nativeOpen(JNIEnv* env, jclass, jint fd) {
    int own = dup(fd);
    if (own < 0) { fail(env, "Couldn't open the file"); return 0; }
    FILE* f = fdopen(own, "rb");
    if (!f) { close(own); fail(env, "Couldn't open the file"); return 0; }
    try {
        auto h = new Handle();
        h->decoder = std::make_unique<motioncam::Decoder>(f);
        return reinterpret_cast<jlong>(h);
    } catch (const std::exception& e) {
        // The decoder closes the file when it fails.
        fail(env, e.what());
        return 0;
    }
}

JNIEXPORT void JNICALL Java_com_graden_editor_McRaw_nativeClose(JNIEnv*, jclass, jlong h) {
    delete of(h);
}

JNIEXPORT jlongArray JNICALL Java_com_graden_editor_McRaw_nativeFrames(JNIEnv* env, jclass, jlong h) {
    const auto& fr = of(h)->decoder->getFrames();
    jlongArray out = env->NewLongArray(static_cast<jsize>(fr.size()));
    if (!fr.empty()) env->SetLongArrayRegion(out, 0, static_cast<jsize>(fr.size()), reinterpret_cast<const jlong*>(fr.data()));
    return out;
}

JNIEXPORT jstring JNICALL Java_com_graden_editor_McRaw_nativeMetadata(JNIEnv* env, jclass, jlong h) {
    return env->NewStringUTF(of(h)->decoder->getContainerMetadata().dump().c_str());
}

JNIEXPORT jstring JNICALL Java_com_graden_editor_McRaw_nativeFrameMetadata(JNIEnv* env, jclass, jlong h, jlong ts) {
    try {
        nlohmann::json m;
        of(h)->decoder->loadFrameMetadata(ts, m);
        return env->NewStringUTF(m.dump().c_str());
    } catch (const std::exception& e) { fail(env, e.what()); return nullptr; }
}

/** Decodes frame [ts] into [out] (a direct buffer of width × height 16-bit samples); returns its metadata. */
JNIEXPORT jstring JNICALL Java_com_graden_editor_McRaw_nativeLoadFrame(JNIEnv* env, jclass, jlong h, jlong ts, jobject out) {
    try {
        auto* hd = of(h);
        nlohmann::json m;
        hd->decoder->loadFrame(ts, hd->frame, m);
        auto* dst = static_cast<uint8_t*>(env->GetDirectBufferAddress(out));
        jlong cap = env->GetDirectBufferCapacity(out);
        if (!dst || cap < static_cast<jlong>(hd->frame.size())) { fail(env, "Frame buffer too small"); return nullptr; }
        std::memcpy(dst, hd->frame.data(), hd->frame.size());
        return env->NewStringUTF(m.dump().c_str());
    } catch (const std::exception& e) { fail(env, e.what()); return nullptr; }
}

JNIEXPORT jint JNICALL Java_com_graden_editor_McRaw_nativeAudioRate(JNIEnv*, jclass, jlong h) {
    try { return of(h)->decoder->audioSampleRateHz(); } catch (...) { return 0; }
}

JNIEXPORT jint JNICALL Java_com_graden_editor_McRaw_nativeAudioChannels(JNIEnv*, jclass, jlong h) {
    try { return of(h)->decoder->numAudioChannels(); } catch (...) { return 0; }
}

/** All the audio, as interleaved 16-bit samples, with the time (ns, frame timeline) of its first sample in [start]. */
JNIEXPORT jshortArray JNICALL Java_com_graden_editor_McRaw_nativeAudio(JNIEnv* env, jclass, jlong h, jlongArray start) {
    try {
        std::vector<motioncam::AudioChunk> chunks;
        of(h)->decoder->loadAudio(chunks);
        size_t n = 0;
        for (auto& c : chunks) n += c.second.size();
        jshortArray out = env->NewShortArray(static_cast<jsize>(n));
        size_t at = 0;
        for (auto& c : chunks) {
            if (!c.second.empty()) env->SetShortArrayRegion(out, static_cast<jsize>(at), static_cast<jsize>(c.second.size()), c.second.data());
            at += c.second.size();
        }
        jlong t = chunks.empty() ? -1 : chunks.front().first;
        env->SetLongArrayRegion(start, 0, 1, &t);
        return out;
    } catch (const std::exception& e) { fail(env, e.what()); return nullptr; }
}

/** The gyroscope, as (time ns, x, y, z rad/s) per sample, flattened; empty when the file has none. */
JNIEXPORT jdoubleArray JNICALL Java_com_graden_editor_McRaw_nativeGyro(JNIEnv* env, jclass, jlong h) {
    try {
        std::vector<motioncam::MotionSample> s;
        if (of(h)->decoder->hasGyroData()) of(h)->decoder->loadGyroData(s);
        jdoubleArray out = env->NewDoubleArray(static_cast<jsize>(s.size() * 4));
        std::vector<double> v(s.size() * 4);
        for (size_t i = 0; i < s.size(); i++) {
            v[i * 4] = static_cast<double>(s[i].timestampNs); v[i * 4 + 1] = s[i].x; v[i * 4 + 2] = s[i].y; v[i * 4 + 3] = s[i].z;
        }
        if (!v.empty()) env->SetDoubleArrayRegion(out, 0, static_cast<jsize>(v.size()), v.data());
        return out;
    } catch (const std::exception& e) { fail(env, e.what()); return nullptr; }
}

}
