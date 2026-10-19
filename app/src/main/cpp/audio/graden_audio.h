// Graden's use of audio.cpp (Apache-2.0, github.com/0xShug0/audio.cpp): speech to text with NVIDIA Nemotron 3.5 ASR,
// who speaks when with NVIDIA Nemotron 3 Diarization, and sound tagging with CED. audio.cpp is loaded at run time
// (dlopen) as one of two builds, so the phone's processor picks the one it can run; every call goes through its C API.

#pragma once

#include <atomic>
#include <string>
#include <utility>
#include <vector>

namespace graden_audio {

/** Loads the audio.cpp library [lib] (a file name or path); [tmp] is where it may unpack model metadata. "" when it
 *  worked, else why not. */
std::string open(const char* lib, const char* tmp);

struct Engine;

/** A model [path] of [family] ready for [task] ("asr", "diar", "cls") in [mode] ("offline", "streaming"), with
 *  session options [opts] (key, value). Null on failure: see last_error(). */
Engine* load(const char* path, const char* family, const char* task, const char* mode, int threads,
             const std::vector<std::pair<std::string, std::string>>& opts);
void free(Engine* e);
std::string last_error();

/** Called with 0..1 as work goes on; returning false stops it. */
using Progress = bool (*)(float done, void* user);

/** 16 kHz mono [pcm] to {"text":"...","words":[["word",start,end],...]} (samples), or "" on failure. */
std::string asr(Engine* e, const float* pcm, size_t n, const char* language);

/** Who speaks when in 16 kHz mono [pcm] (streaming): {"turns":[[start,end,speaker],...]} (samples), or "" on failure
 *  or when [progress] stopped it. */
std::string diar(Engine* e, const float* pcm, size_t n, Progress progress, void* user);

/** Sound classes of 16 kHz mono [pcm]: the family's JSON ({"schema":..,"data":{"scores":[{index,label,score}..]}}),
 *  or "" on failure. */
std::string cls(Engine* e, const float* pcm, size_t n, int top_k);

}  // namespace graden_audio
