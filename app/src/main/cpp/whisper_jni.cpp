// Speech to text for Graden's captions: whisper.cpp (whisper/, MIT) behind a small JNI API used by
// com.graden.editor.Whisper. One handle is one loaded model; a handle is used from one thread at a time.

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cmath>
#include <string>
#include <vector>
#include "whisper.h"

namespace {
    struct Job { JNIEnv* env; jobject cb; jmethodID onProgress; };
    // Set from any thread to stop the transcription running (one runs at a time); read by ggml's worker threads.
    std::atomic<bool> g_cancel(false);

    void log_cb(enum ggml_log_level level, const char* text, void*) {
        if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, "Whisper", "%s", text);
    }

    /** A number JSON can hold: NaN and infinity (a model can give them) become 0. */
    double num(double v) { return std::isfinite(v) ? v : 0.0; }

    /** Whether [s] ends part-way through a UTF-8 letter. */
    bool incomplete(const std::string& s) {
        int n = static_cast<int>(s.size());
        for (int back = 1; back <= 4 && back <= n; back++) {
            unsigned char c = static_cast<unsigned char>(s[n - back]);
            if ((c & 0xC0) == 0x80) continue;                 // a continuation byte: look further back
            int need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 1;
            return back < need;
        }
        return false;
    }

    /** [s] as valid UTF-8: any broken sequence becomes U+FFFD. */
    std::string clean(const std::string& s) {
        std::string o;
        size_t i = 0;
        while (i < s.size()) {
            unsigned char c = static_cast<unsigned char>(s[i]);
            int need = c < 0x80 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 0;
            bool ok = need > 0 && i + need <= s.size();
            for (int k = 1; ok && k < need; k++) ok = (static_cast<unsigned char>(s[i + k]) & 0xC0) == 0x80;
            if (ok) { o.append(s, i, need); i += need; } else { o += "\xEF\xBF\xBD"; i++; }
        }
        return o;
    }

    std::string esc(const char* s) {
        std::string o;
        for (const char* p = s; *p; ++p) {
            unsigned char c = static_cast<unsigned char>(*p);
            if (c == '"' || c == '\\') { o += '\\'; o += static_cast<char>(c); }
            else if (c < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", c); o += b; }
            else o += static_cast<char>(c);
        }
        return o;
    }
}

extern "C" {

JNIEXPORT jlong JNICALL Java_com_graden_editor_Whisper_nativeInit(JNIEnv* env, jclass, jstring path, jint aheads) {
    whisper_log_set(log_cb, nullptr);
    const char* p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    cp.flash_attn = false;               // word timing (DTW) needs the decoder's attention weights (the encoder
                                         // still uses flash attention: see the Graden note in whisper.cpp)
    cp.dtw_token_timestamps = aheads > 0;
    cp.dtw_aheads_preset = static_cast<whisper_alignment_heads_preset>(aheads);
    whisper_context* ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL Java_com_graden_editor_Whisper_nativeFree(JNIEnv*, jclass, jlong h) {
    if (h) whisper_free(reinterpret_cast<whisper_context*>(h));
}

JNIEXPORT void JNICALL Java_com_graden_editor_Whisper_nativeCancel(JNIEnv*, jclass) { g_cancel.store(true); }

JNIEXPORT jboolean JNICALL Java_com_graden_editor_Whisper_nativeMultilingual(JNIEnv*, jclass, jlong h) {
    return h && whisper_is_multilingual(reinterpret_cast<whisper_context*>(h)) ? JNI_TRUE : JNI_FALSE;
}

/**
 * Where someone speaks or sings in 16 kHz mono [pcm]: Silero VAD (the ggml model at [path]) run by whisper.cpp.
 * Returns start, end pairs in seconds; null when the model couldn't be used.
 */
JNIEXPORT jfloatArray JNICALL Java_com_graden_editor_Whisper_nativeVad(JNIEnv* env, jclass, jstring path, jfloatArray pcm, jint threads) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    whisper_vad_context_params cp = whisper_vad_default_context_params();
    cp.n_threads = threads;
    cp.use_gpu = false;
    whisper_vad_context* vctx = whisper_vad_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    if (!vctx) return nullptr;
    jsize n = env->GetArrayLength(pcm);
    std::vector<float> samples(static_cast<size_t>(n));
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());
    whisper_vad_params vp = whisper_vad_default_params();
    vp.threshold = 0.35f;                // inclusive: soft and sung voices count
    vp.min_speech_duration_ms = 200;
    vp.min_silence_duration_ms = 300;
    vp.speech_pad_ms = 200;
    whisper_vad_segments* segs = whisper_vad_segments_from_samples(vctx, vp, samples.data(), n);
    jfloatArray out = nullptr;
    if (segs) {
        int k = whisper_vad_segments_n_segments(segs);
        std::vector<float> t(static_cast<size_t>(k) * 2);
        // whisper.cpp gives hundredths of a second.
        for (int i = 0; i < k; i++) { t[2 * i] = whisper_vad_segments_get_segment_t0(segs, i) / 100.0f; t[2 * i + 1] = whisper_vad_segments_get_segment_t1(segs, i) / 100.0f; }
        out = env->NewFloatArray(static_cast<jsize>(t.size()));
        env->SetFloatArrayRegion(out, 0, static_cast<jsize>(t.size()), t.data());
        whisper_vad_free_segments(segs);
    }
    whisper_vad_free(vctx);
    return out;
}

/**
 * Transcribes 16 kHz mono [pcm] in [lang] ("auto" to detect), with [threads] threads; calls cb.onProgress(int 0..100);
 * nativeCancel stops it. Returns JSON: {"lang":"hi","segments":[{"t0":cs,"t1":cs,"ns":p,
 * "tokens":[{"t":"text","t0":cs,"t1":cs,"d":cs,"p":prob}]}]} (cs: hundredths of a second), or null.
 */
JNIEXPORT jbyteArray JNICALL Java_com_graden_editor_Whisper_nativeTranscribe(JNIEnv* env, jclass, jlong h, jfloatArray pcm, jstring jlang,
                                                                        jint threads, jstring jprompt, jobject cb) {
    auto* ctx = reinterpret_cast<whisper_context*>(h);
    if (!ctx) return nullptr;
    jsize n = env->GetArrayLength(pcm);
    std::vector<float> samples(static_cast<size_t>(n));
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());
    const char* lang = env->GetStringUTFChars(jlang, nullptr);
    const char* prompt = jprompt ? env->GetStringUTFChars(jprompt, nullptr) : nullptr;

    jclass cls = env->GetObjectClass(cb);
    jmethodID onProgress = env->GetMethodID(cls, "onProgress", "(I)V");
    g_cancel.store(false);
    Job job{env, cb, onProgress};

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.n_threads = threads;
    wp.language = lang;
    wp.detect_language = false;
    wp.translate = false;
    wp.no_context = true;                // windows don't carry text over: no repeated lines
    wp.token_timestamps = true;
    wp.thold_pt = 0.01f;
    wp.suppress_blank = true;
    wp.suppress_nst = true;              // no "[Music]" / "(laughs)": music is told apart by the sound classifier
    wp.no_speech_thold = 0.6f;
    wp.print_progress = false; wp.print_realtime = false; wp.print_timestamps = false; wp.print_special = false;
    wp.initial_prompt = prompt;
    wp.progress_callback = [](whisper_context*, whisper_state*, int progress, void* ud) {
        auto* j = static_cast<Job*>(ud);
        j->env->CallVoidMethod(j->cb, j->onProgress, progress);
    };
    wp.progress_callback_user_data = &job;
    // Checked often while computing, on ggml's own threads: only the flag, never JNI.
    wp.abort_callback = [](void*) -> bool { return g_cancel.load(); };
    wp.encoder_begin_callback = [](whisper_context*, whisper_state*, void*) -> bool { return !g_cancel.load(); };

    int rc = whisper_full(ctx, wp, samples.data(), n);
    env->ReleaseStringUTFChars(jlang, lang);
    if (prompt) env->ReleaseStringUTFChars(jprompt, prompt);
    if (rc != 0 || g_cancel.load()) return nullptr;

    std::string out = "{\"lang\":\"";
    out += esc(whisper_lang_str(whisper_full_lang_id(ctx)));
    out += "\",\"segments\":[";
    int ns = whisper_full_n_segments(ctx);
    for (int s = 0; s < ns; s++) {
        if (s) out += ',';
        char b[160];
        snprintf(b, sizeof b, "{\"t0\":%lld,\"t1\":%lld,\"ns\":%.3f,\"tokens\":[", (long long) whisper_full_get_segment_t0(ctx, s),
                 (long long) whisper_full_get_segment_t1(ctx, s), num(whisper_full_get_segment_no_speech_prob(ctx, s)));
        out += b;
        int nt = whisper_full_n_tokens(ctx, s);
        bool first = true;
        // Whisper's tokens are bytes: a letter of Devanagari, Bengali or CJK can be split between two of them. A piece
        // that ends part-way through a letter waits for the next one, and goes out with it (the first one's start, the
        // last one's end), so every piece is whole UTF-8.
        std::string pending;
        whisper_token_data from{};
        auto emit = [&](const whisper_token_data& d0, const whisper_token_data& d1, const std::string& text) {
            if (!first) out += ',';
            first = false;
            snprintf(b, sizeof b, "{\"t0\":%lld,\"t1\":%lld,\"d\":%lld,\"p\":%.3f,\"t\":\"", (long long) d0.t0, (long long) d1.t1, (long long) d0.t_dtw, num(d0.p));
            out += b;
            out += esc(clean(text).c_str());
            out += "\"}";
        };
        for (int t = 0; t < nt; t++) {
            whisper_token_data d = whisper_full_get_token_data(ctx, s, t);
            if (d.id >= whisper_token_eot(ctx)) continue;   // special tokens (timestamps, end)
            const char* text = whisper_full_get_token_text(ctx, s, t);
            if (pending.empty()) from = d;
            pending += text ? text : "";
            if (incomplete(pending) && pending.size() < 16) continue;
            emit(from, d, pending);
            pending.clear();
        }
        if (!pending.empty()) emit(from, from, pending);
        out += "]}";
    }
    out += "]}";
    // As bytes: Java's own string conversion takes modified UTF-8, which four-byte letters (emoji) are not.
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte*>(out.data()));
    return arr;
}

}
