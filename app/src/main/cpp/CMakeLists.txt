cmake_minimum_required(VERSION 3.22)
project(graden_native CXX)

# Source paths compiled into the libraries (by assert messages) are written relative to this folder and the NDK, so
# a build never carries the builder's own folders.
get_filename_component(GRADEN_NDK "${CMAKE_ANDROID_NDK}" ABSOLUTE)
add_compile_options("-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=." "-ffile-prefix-map=${GRADEN_NDK}=ndk" "-ffile-prefix-map=${CMAKE_BINARY_DIR}=build")

# MotionCam RAW decoding: the MotionCam decoder (motioncam/, Apache-2.0) with nlohmann/json and SIMDe (both MIT),
# built for every ABI.
add_library(mcraw SHARED
    mcraw_jni.cpp
    motioncam/lib/Decoder.cpp
    motioncam/lib/RawData.cpp
    motioncam/lib/RawData_Legacy.cpp)
target_include_directories(mcraw PRIVATE motioncam/lib/include thirdparty)
target_compile_features(mcraw PRIVATE cxx_std_17)
target_compile_options(mcraw PRIVATE -O3 -fexceptions -frtti -D_FILE_OFFSET_BITS=64)
target_link_libraries(mcraw log)
target_link_options(mcraw PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384 -s)

# The built-in software encoders are optional (-Psoftenc=true after scripts/build-codecs.sh). x264 and x265 are built
# for arm64 from source (see third_party/*-COPYING.txt: both are GPL).
if(GRADEN_SOFTENC AND ANDROID_ABI STREQUAL "arm64-v8a")
    set(TP ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
    add_library(x264 STATIC IMPORTED)
    set_target_properties(x264 PROPERTIES IMPORTED_LOCATION ${TP}/lib/${ANDROID_ABI}/libx264.a)
    add_library(x265 STATIC IMPORTED)
    set_target_properties(x265 PROPERTIES IMPORTED_LOCATION ${TP}/lib/${ANDROID_ABI}/libx265.a)

    add_library(softenc SHARED softenc.cpp)
    target_include_directories(softenc PRIVATE ${TP}/include)
    target_compile_options(softenc PRIVATE -O3 -ffast-math)
    target_link_libraries(softenc x265 x264 log -static-libstdc++)
    target_link_options(softenc PRIVATE -Wl,-Bsymbolic -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,-z,max-page-size=16384 -s)
endif()

# The visual gyro's point tracker (vgyro_jni.cpp) on OpenCV 4.10.0 (Apache-2.0, see third_party/opencv/LICENSE): only
# core, imgproc and video, linked statically. scripts/fetch-opencv.sh puts them in third_party/opencv.
# ARM only: OpenCV's x86 builds need Intel's IPP, which is not under a GPL-friendly licence (x86 is emulators only;
# there Graden falls back to its own tracker).
if(ANDROID_ABI MATCHES "arm")
set(CV ${CMAKE_CURRENT_SOURCE_DIR}/third_party/opencv)
foreach(m opencv_video opencv_imgproc opencv_core)
    add_library(${m} STATIC IMPORTED)
    set_target_properties(${m} PROPERTIES IMPORTED_LOCATION ${CV}/lib/${ANDROID_ABI}/lib${m}.a)
endforeach()
set(CV_EXTRA)
foreach(m tegra_hal tbb cpufeatures ittnotify)
    if(EXISTS ${CV}/lib/${ANDROID_ABI}/lib${m}.a)
        add_library(cv_${m} STATIC IMPORTED)
        set_target_properties(cv_${m} PROPERTIES IMPORTED_LOCATION ${CV}/lib/${ANDROID_ABI}/lib${m}.a)
        list(APPEND CV_EXTRA cv_${m})
    endif()
endforeach()
add_library(vgyro SHARED vgyro_jni.cpp)
target_include_directories(vgyro PRIVATE ${CV}/include)
target_compile_features(vgyro PRIVATE cxx_std_17)
target_compile_options(vgyro PRIVATE -O3 -fexceptions -frtti)
target_link_libraries(vgyro opencv_video opencv_imgproc opencv_core ${CV_EXTRA} ${CV_EXTRA} z log dl m)
target_link_options(vgyro PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 -s)
endif()

# Captions: whisper.cpp v1.9.5 (whisper/, MIT) on its own build of ggml's CPU backend, with its repacked-weight matrix
# kernels. On 64-bit ARM three copies: one adding the 8-bit matrix-multiply instructions, one for ARMv8.2 with
# half-float and dot-product instructions (phones from about 2019 on), and one plain ARMv8 for older
# cores (Cortex-A53/A73); Whisper.kt loads the one the phone's CPU can run.
enable_language(C)
set(WH ${CMAKE_CURRENT_SOURCE_DIR}/whisper)
set(WHISPER_SOURCES
    whisper_jni.cpp
    ${WH}/src/whisper.cpp ${WH}/src/aneforge/whisper-aneforge.cpp
    ${WH}/ggml/src/ggml.c ${WH}/ggml/src/ggml.cpp ${WH}/ggml/src/ggml-alloc.c ${WH}/ggml/src/ggml-backend.cpp
    ${WH}/ggml/src/ggml-backend-meta.cpp ${WH}/ggml/src/ggml-backend-reg.cpp ${WH}/ggml/src/ggml-opt.cpp
    ${WH}/ggml/src/ggml-threading.cpp ${WH}/ggml/src/ggml-quants.c ${WH}/ggml/src/gguf.cpp
    ${WH}/ggml/src/ggml-cpu/ggml-cpu.c ${WH}/ggml/src/ggml-cpu/ggml-cpu.cpp ${WH}/ggml/src/ggml-cpu/binary-ops.cpp
    ${WH}/ggml/src/ggml-cpu/unary-ops.cpp ${WH}/ggml/src/ggml-cpu/ops.cpp ${WH}/ggml/src/ggml-cpu/quants.c
    ${WH}/ggml/src/ggml-cpu/repack.cpp ${WH}/ggml/src/ggml-cpu/traits.cpp ${WH}/ggml/src/ggml-cpu/vec.cpp
    ${WH}/ggml/src/ggml-cpu/hbm.cpp ${WH}/ggml/src/ggml-cpu/amx/amx.cpp ${WH}/ggml/src/ggml-cpu/amx/mmq.cpp
    ${WH}/ggml/src/ggml-cpu/tiled/tiled.cpp ${WH}/ggml/src/ggml-cpu/tiled/tiled-kernel.cpp)
if(ANDROID_ABI MATCHES "arm")
    list(APPEND WHISPER_SOURCES ${WH}/ggml/src/ggml-cpu/arch/arm/quants.c ${WH}/ggml/src/ggml-cpu/arch/arm/repack.cpp
        ${WH}/ggml/src/ggml-cpu/arch/arm/cpu-feats.cpp)
endif()
function(graden_whisper name)
    add_library(${name} SHARED ${WHISPER_SOURCES})
    target_include_directories(${name} PRIVATE ${WH}/include ${WH}/src ${WH}/ggml/include ${WH}/ggml/src ${WH}/ggml/src/ggml-cpu)
    target_compile_definitions(${name} PRIVATE GGML_USE_CPU GGML_USE_CPU_REPACK GGML_SCHED_MAX_COPIES=4 _GNU_SOURCE _XOPEN_SOURCE=600 WHISPER_VERSION="1.9.5" NDEBUG)
    target_compile_features(${name} PRIVATE cxx_std_17)
    set(flags -O3 -ffunction-sections -fdata-sections -fvisibility=hidden -fexceptions -frtti -Wno-everything ${ARGN})
    target_compile_options(${name} PRIVATE ${flags})
    target_link_libraries(${name} log android m)
    target_link_options(${name} PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 -s)
endfunction()
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # 8-bit matrix-multiply instructions (Cortex-X2/A710 on: phones from about 2022), which the repacked Q8_0
    # weights use for the encoder: the biggest single speed-up.
    graden_whisper(whisper_i8mm -march=armv8.2-a+fp16+dotprod+i8mm)
    graden_whisper(whisper_v82 -march=armv8.2-a+fp16+dotprod)
    graden_whisper(whisper -march=armv8-a)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    graden_whisper(whisper -mfpu=neon-vfpv4)
endif()
# The newer caption models (Nemotron 3.5 ASR, Nemotron 3 Diarization, CED) run in audio.cpp (Apache-2.0), built by
# tools/audiocpp/build.sh into jniLibs (two builds, chosen at run time); this is the small bridge that loads it.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_library(graden_audio SHARED audio/audio_jni.cpp audio/graden_audio.cpp)
    target_compile_features(graden_audio PRIVATE cxx_std_17)
    target_compile_options(graden_audio PRIVATE -O2 -fvisibility=hidden -Wno-comment)
    target_link_libraries(graden_audio dl log)
    target_link_options(graden_audio PRIVATE -Wl,-z,max-page-size=16384)
endif()
# x86_64 is emulators only: no captions there.
