cmake_minimum_required(VERSION 3.22)
project(graden_native CXX)

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
