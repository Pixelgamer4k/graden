// JNI for com.graden.editor.AudioEngine: graden_audio (audio.cpp's C API) for Kotlin.

#include <jni.h>
#include <string>
#include <vector>

#include "graden_audio.h"

namespace {
    std::string str(JNIEnv* env, jstring s) {
        if (!s) return "";
        const char* c = env->GetStringUTFChars(s, nullptr);
        std::string o(c);
        env->ReleaseStringUTFChars(s, c);
        return o;
    }
    jstring out(JNIEnv* env, const std::string& s) { return s.empty() ? nullptr : env->NewStringUTF(s.c_str()); }
    std::vector<float> floats(JNIEnv* env, jfloatArray a) {
        std::vector<float> v(static_cast<size_t>(env->GetArrayLength(a)));
        env->GetFloatArrayRegion(a, 0, static_cast<jsize>(v.size()), v.data());
        return v;
    }
    struct Cb { JNIEnv* env; jobject obj; jmethodID m; };
}

extern "C" {

JNIEXPORT jstring JNICALL Java_com_graden_editor_AudioEngine_nativeOpen(JNIEnv* env, jclass, jstring lib, jstring tmp) {
    return out(env, graden_audio::open(str(env, lib).c_str(), str(env, tmp).c_str()));
}

JNIEXPORT jstring JNICALL Java_com_graden_editor_AudioEngine_nativeLastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(graden_audio::last_error().c_str());
}

/** [opts]: key, value, key, value, ... */
JNIEXPORT jlong JNICALL Java_com_graden_editor_AudioEngine_nativeLoad(JNIEnv* env, jclass, jstring path, jstring family, jstring task,
                                                                     jstring mode, jint threads, jobjectArray opts) {
    std::vector<std::pair<std::string, std::string>> o;
    jsize n = opts ? env->GetArrayLength(opts) : 0;
    for (jsize i = 0; i + 1 < n; i += 2) {
        auto k = static_cast<jstring>(env->GetObjectArrayElement(opts, i));
        auto v = static_cast<jstring>(env->GetObjectArrayElement(opts, i + 1));
        o.emplace_back(str(env, k), str(env, v));
        env->DeleteLocalRef(k); env->DeleteLocalRef(v);
    }
    return reinterpret_cast<jlong>(graden_audio::load(str(env, path).c_str(), str(env, family).c_str(), str(env, task).c_str(),
                                                      str(env, mode).c_str(), threads, o));
}

JNIEXPORT void JNICALL Java_com_graden_editor_AudioEngine_nativeFree(JNIEnv*, jclass, jlong h) {
    graden_audio::free(reinterpret_cast<graden_audio::Engine*>(h));
}

JNIEXPORT jstring JNICALL Java_com_graden_editor_AudioEngine_nativeAsr(JNIEnv* env, jclass, jlong h, jfloatArray pcm, jstring lang) {
    auto v = floats(env, pcm);
    return out(env, graden_audio::asr(reinterpret_cast<graden_audio::Engine*>(h), v.data(), v.size(), str(env, lang).c_str()));
}

/** [cb].onProgress(float): Boolean, false to stop. Called on this thread. */
JNIEXPORT jstring JNICALL Java_com_graden_editor_AudioEngine_nativeDiar(JNIEnv* env, jclass, jlong h, jfloatArray pcm, jobject cb) {
    auto v = floats(env, pcm);
    Cb c{env, cb, env->GetMethodID(env->GetObjectClass(cb), "onProgress", "(F)Z")};
    auto prog = [](float d, void* u) -> bool {
        auto* c = static_cast<Cb*>(u);
        jboolean go = c->env->CallBooleanMethod(c->obj, c->m, d);
        if (c->env->ExceptionCheck()) { c->env->ExceptionClear(); return false; }
        return go == JNI_TRUE;
    };
    return out(env, graden_audio::diar(reinterpret_cast<graden_audio::Engine*>(h), v.data(), v.size(), prog, &c));
}

JNIEXPORT jstring JNICALL Java_com_graden_editor_AudioEngine_nativeCls(JNIEnv* env, jclass, jlong h, jfloatArray pcm, jint topK) {
    auto v = floats(env, pcm);
    return out(env, graden_audio::cls(reinterpret_cast<graden_audio::Engine*>(h), v.data(), v.size(), topK));
}

}
