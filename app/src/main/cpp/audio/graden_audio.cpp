#include "graden_audio.h"

#include <dlfcn.h>
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <mutex>

#include "audiocpp.h"

namespace graden_audio {
namespace {

// The C API, looked up in the library that was loaded.
struct Api {
#define F(name) decltype(&::name) name = nullptr;
    F(audiocpp_abi_version) F(audiocpp_last_error)
    F(audiocpp_options_create) F(audiocpp_options_set) F(audiocpp_options_free)
    F(audiocpp_registry_create) F(audiocpp_registry_free)
    F(audiocpp_model_load) F(audiocpp_model_free)
    F(audiocpp_session_create) F(audiocpp_session_free) F(audiocpp_session_run)
    F(audiocpp_request_create) F(audiocpp_request_free) F(audiocpp_request_set_audio) F(audiocpp_request_set_option)
    F(audiocpp_request_set_text_language)
    F(audiocpp_result_free) F(audiocpp_result_text) F(audiocpp_result_word_count) F(audiocpp_result_word)
    F(audiocpp_result_speaker_turn_count) F(audiocpp_result_speaker_turn) F(audiocpp_result_custom_json)
    F(audiocpp_stream_policy) F(audiocpp_stream_start) F(audiocpp_stream_push) F(audiocpp_stream_finish)
    F(audiocpp_event_free)
#undef F
};

Api api;
audiocpp_registry* registry = nullptr;
std::mutex lock;
thread_local std::string error;

std::string esc(const char* s) {
    std::string o;
    for (const char* p = s; p && *p; ++p) {
        unsigned char c = static_cast<unsigned char>(*p);
        if (c == '"' || c == '\\') { o += '\\'; o += static_cast<char>(c); }
        else if (c < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", c); o += b; }
        else o += static_cast<char>(c);
    }
    return o;
}

bool check(audiocpp_status st, const char* what) {
    if (st == AUDIOCPP_OK) return true;
    error = std::string(what) + ": " + (api.audiocpp_last_error ? api.audiocpp_last_error() : "");
    return false;
}

}  // namespace

struct Engine {
    audiocpp_model* model = nullptr;
    audiocpp_session* session = nullptr;
};

std::string open(const char* lib, const char* tmp) {
    std::lock_guard<std::mutex> g(lock);
    if (registry) return "";
    // Model packages unpack their metadata into the temporary directory, which Android doesn't have.
    if (tmp && *tmp) setenv("TMPDIR", tmp, 1);
    void* h = dlopen(lib, RTLD_NOW | RTLD_LOCAL);
    if (!h) return std::string("dlopen: ") + dlerror();
    bool ok = true;
#define F(name) api.name = reinterpret_cast<decltype(api.name)>(dlsym(h, #name)); ok = ok && api.name;
    F(audiocpp_abi_version) F(audiocpp_last_error)
    F(audiocpp_options_create) F(audiocpp_options_set) F(audiocpp_options_free)
    F(audiocpp_registry_create) F(audiocpp_registry_free)
    F(audiocpp_model_load) F(audiocpp_model_free)
    F(audiocpp_session_create) F(audiocpp_session_free) F(audiocpp_session_run)
    F(audiocpp_request_create) F(audiocpp_request_free) F(audiocpp_request_set_audio) F(audiocpp_request_set_option)
    F(audiocpp_request_set_text_language)
    F(audiocpp_result_free) F(audiocpp_result_text) F(audiocpp_result_word_count) F(audiocpp_result_word)
    F(audiocpp_result_speaker_turn_count) F(audiocpp_result_speaker_turn) F(audiocpp_result_custom_json)
    F(audiocpp_stream_policy) F(audiocpp_stream_start) F(audiocpp_stream_push) F(audiocpp_stream_finish)
    F(audiocpp_event_free)
#undef F
    if (!ok) { dlclose(h); api = Api(); return "the library lacks part of the C API"; }
    if ((api.audiocpp_abi_version() >> 16) != 0) { dlclose(h); api = Api(); return "unexpected C API version"; }
    if (!check(api.audiocpp_registry_create(nullptr, &registry), "registry")) { std::string e = error; dlclose(h); api = Api(); return e; }
    return "";
}

std::string last_error() { return error; }

Engine* load(const char* path, const char* family, const char* task, const char* mode, int threads,
             const std::vector<std::pair<std::string, std::string>>& opts) {
    if (!registry) { error = "audio.cpp is not loaded"; return nullptr; }
    auto* e = new Engine();
    audiocpp_model_config mc{family, nullptr, nullptr, nullptr};
    if (!check(api.audiocpp_model_load(registry, path, &mc, nullptr, &e->model), "load")) { delete e; return nullptr; }
    audiocpp_options* so = api.audiocpp_options_create();
    for (auto& kv : opts) api.audiocpp_options_set(so, kv.first.c_str(), kv.second.c_str());
    audiocpp_backend_config bc{"cpu", 0, threads};
    bool ok = check(api.audiocpp_session_create(e->model, task, mode, &bc, so, &e->session), "session");
    api.audiocpp_options_free(so);
    if (!ok) { free(e); return nullptr; }
    return e;
}

void free(Engine* e) {
    if (!e) return;
    if (e->session) api.audiocpp_session_free(e->session);
    if (e->model) api.audiocpp_model_free(e->model);
    delete e;
}

std::string asr(Engine* e, const float* pcm, size_t n, const char* language) {
    if (!e) return "";
    audiocpp_request* rq = api.audiocpp_request_create();
    api.audiocpp_request_set_audio(rq, pcm, n, 16000, 1);
    if (language && *language) api.audiocpp_request_set_option(rq, "language", language);
    audiocpp_result* rs = nullptr;
    bool ok = check(api.audiocpp_session_run(e->session, rq, &rs), "asr");
    api.audiocpp_request_free(rq);
    if (!ok) return "";
    const char* text = "";
    api.audiocpp_result_text(rs, &text, nullptr);
    std::string out = "{\"text\":\"" + esc(text) + "\",\"words\":[";
    size_t k = api.audiocpp_result_word_count(rs);
    for (size_t i = 0; i < k; i++) {
        const char* w = ""; int64_t s = 0, t = 0;
        api.audiocpp_result_word(rs, i, &w, &s, &t, nullptr);
        if (i) out += ',';
        out += "[\"" + esc(w) + "\"," + std::to_string(s) + "," + std::to_string(t) + "]";
    }
    out += "]}";
    api.audiocpp_result_free(rs);
    return out;
}

std::string diar(Engine* e, const float* pcm, size_t n, Progress progress, void* user) {
    if (!e) return "";
    int64_t chunk = 0;
    api.audiocpp_stream_policy(e->session, nullptr, nullptr, &chunk, nullptr);
    if (chunk <= 0) chunk = 16000;
    chunk = std::max<int64_t>(chunk, 16000 * 5);   // fewer, larger pushes: the profile buffers 30 s anyway
    if (!check(api.audiocpp_stream_start(e->session, nullptr), "stream")) return "";
    for (size_t at = 0; at < n; at += static_cast<size_t>(chunk)) {
        size_t len = std::min(static_cast<size_t>(chunk), n - at);
        audiocpp_event* ev = nullptr;
        if (!check(api.audiocpp_stream_push(e->session, pcm + at, len, 16000, 1, static_cast<int64_t>(at), &ev), "push")) return "";
        api.audiocpp_event_free(ev);
        if (progress && !progress(static_cast<float>(at + len) / static_cast<float>(n), user)) { error = "cancelled"; return ""; }
    }
    audiocpp_result* rs = nullptr;
    if (!check(api.audiocpp_stream_finish(e->session, &rs), "finish")) return "";
    std::string out = "{\"turns\":[";
    size_t k = api.audiocpp_result_speaker_turn_count(rs);
    for (size_t i = 0; i < k; i++) {
        int64_t s = 0, t = 0; const char* id = "";
        api.audiocpp_result_speaker_turn(rs, i, &s, &t, &id, nullptr, nullptr);
        if (i) out += ',';
        out += "[" + std::to_string(s) + "," + std::to_string(t) + ",\"" + esc(id) + "\"]";
    }
    out += "]}";
    api.audiocpp_result_free(rs);
    return out;
}

std::string cls(Engine* e, const float* pcm, size_t n, int top_k) {
    if (!e) return "";
    audiocpp_request* rq = api.audiocpp_request_create();
    api.audiocpp_request_set_audio(rq, pcm, n, 16000, 1);
    api.audiocpp_request_set_option(rq, "top_k", std::to_string(top_k).c_str());
    audiocpp_result* rs = nullptr;
    bool ok = check(api.audiocpp_session_run(e->session, rq, &rs), "cls");
    api.audiocpp_request_free(rq);
    if (!ok) return "";
    const char* json = "";
    std::string out = check(api.audiocpp_result_custom_json(rs, &json), "cls output") ? json : "";
    api.audiocpp_result_free(rs);
    return out;
}

}  // namespace graden_audio
