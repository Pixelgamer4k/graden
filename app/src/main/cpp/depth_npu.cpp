// Opens Qualcomm's NPU runtime, downloaded to the app's own folder, as a TensorFlow Lite delegate. The runtime is not
// linked or shipped with the app: it comes from Maven Central when Cinema DOF is set up, and only on phones that can use it.
#include <jni.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <android/log.h>
#include <string>
#include <vector>

#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "DepthNpu", __VA_ARGS__)

using Create = void* (*)(const char* const*, const char* const*, size_t, void (*)(const char*));
using Destroy = void (*)(void*);

static Destroy destroyDelegate = nullptr;

static void onError(const char* m) { LOGW("%s", m); }

extern "C" JNIEXPORT jlong JNICALL
Java_com_graden_editor_cinema_AiEngine_nOpen(JNIEnv* env, jclass, jstring jdir, jstring jv, jstring jcache, jstring jtoken) {
    const char* d = env->GetStringUTFChars(jdir, nullptr);
    const char* v = env->GetStringUTFChars(jv, nullptr);
    const char* c = env->GetStringUTFChars(jcache, nullptr);
    const char* t = env->GetStringUTFChars(jtoken, nullptr);
    std::string dir(d), ver(v), cache(c), token(t);
    env->ReleaseStringUTFChars(jtoken, t);
    env->ReleaseStringUTFChars(jdir, d);
    env->ReleaseStringUTFChars(jv, v);
    env->ReleaseStringUTFChars(jcache, c);
    setenv("ADSP_LIBRARY_PATH", (dir + ";/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp").c_str(), 1);
    setenv("LD_LIBRARY_PATH", (dir + ":/vendor/lib64").c_str(), 1);
    for (const std::string& n : {std::string("libQnnSystem.so"), std::string("libQnnHtpPrepare.so"), std::string("libQnnHtp.so"), "libQnnHtp" + ver + "Stub.so"}) {
        if (!dlopen((dir + "/" + n).c_str(), RTLD_NOW | RTLD_GLOBAL)) { LOGW("%s: %s", n.c_str(), dlerror()); return 0; }
    }
    void* lib = dlopen((dir + "/libQnnTFLiteDelegate.so").c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!lib) { LOGW("delegate: %s", dlerror()); return 0; }
    auto create = reinterpret_cast<Create>(dlsym(lib, "tflite_plugin_create_delegate"));
    destroyDelegate = reinterpret_cast<Destroy>(dlsym(lib, "tflite_plugin_destroy_delegate"));
    if (!create) return 0;
    std::string libPath = dir + "/libQnnHtp.so";
    std::vector<const char*> keys = {"backend_type", "library_path", "skel_library_dir", "htp_performance_mode", "htp_precision", "cache_dir", "model_token"};
    std::vector<const char*> vals = {"htp", libPath.c_str(), dir.c_str(), "2", "1", cache.c_str(), token.c_str()};
    return reinterpret_cast<jlong>(create(keys.data(), vals.data(), keys.size(), onError));
}

extern "C" JNIEXPORT void JNICALL
Java_com_graden_editor_cinema_AiEngine_nClose(JNIEnv*, jclass, jlong h) {
    if (h && destroyDelegate) destroyDelegate(reinterpret_cast<void*>(h));
}

// Frame copies in native code: the same pixels as the original's Java loops, at memory speed.
#include <android/bitmap.h>
#include <cstring>
#include <cmath>

extern "C" JNIEXPORT void JNICALL
Java_com_graden_editor_cinema_AiEngine_nRgb(JNIEnv* env, jclass, jobject bmp, jobject dst) {
    AndroidBitmapInfo i; void* p;
    AndroidBitmap_getInfo(env, bmp, &i); AndroidBitmap_lockPixels(env, bmp, &p);
    float* o = static_cast<float*>(env->GetDirectBufferAddress(dst));
    for (uint32_t y = 0; y < i.height; y++) {
        const uint8_t* r = static_cast<const uint8_t*>(p) + y * i.stride;
        for (uint32_t x = 0; x < i.width; x++, r += 4, o += 3) { o[0] = r[0] / 255.f; o[1] = r[1] / 255.f; o[2] = r[2] / 255.f; }
    }
    AndroidBitmap_unlockPixels(env, bmp);
}

static inline uint8_t q8(float v) { v = v < 0 ? 0 : v > 1 ? 1 : v; return static_cast<uint8_t>(std::lround(v * 255)); }

extern "C" JNIEXPORT void JNICALL
Java_com_graden_editor_cinema_AiEngine_nPack(JNIEnv* env, jclass, jobject alpha, jobject fg, jobject bmp) {
    AndroidBitmapInfo i; void* p;
    AndroidBitmap_getInfo(env, bmp, &i); AndroidBitmap_lockPixels(env, bmp, &p);
    const float* a = static_cast<const float*>(env->GetDirectBufferAddress(alpha));
    const float* f = static_cast<const float*>(env->GetDirectBufferAddress(fg));
    for (uint32_t y = 0; y < i.height; y++) {
        uint8_t* r = static_cast<uint8_t*>(p) + y * i.stride;
        for (uint32_t x = 0; x < i.width; x++, r += 4, a++, f += 3) { r[0] = q8(f[0]); r[1] = q8(f[1]); r[2] = q8(f[2]); r[3] = q8(*a); }
    }
    AndroidBitmap_unlockPixels(env, bmp);
}

// GL rows run bottom first; an opaque bitmap, top first.
extern "C" JNIEXPORT void JNICALL
Java_com_graden_editor_cinema_AiEngine_nFlip(JNIEnv* env, jclass, jobject src, jobject bmp) {
    AndroidBitmapInfo i; void* p;
    AndroidBitmap_getInfo(env, bmp, &i); AndroidBitmap_lockPixels(env, bmp, &p);
    const uint8_t* s = static_cast<const uint8_t*>(env->GetDirectBufferAddress(src));
    for (uint32_t y = 0; y < i.height; y++) {
        uint8_t* d = static_cast<uint8_t*>(p) + y * i.stride;
        std::memcpy(d, s + (i.height - 1 - y) * i.width * 4, i.width * 4);
        for (uint32_t x = 0; x < i.width; x++) d[x * 4 + 3] = 255;
    }
    AndroidBitmap_unlockPixels(env, bmp);
}
