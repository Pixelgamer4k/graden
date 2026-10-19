#!/bin/sh
# Builds the audio.cpp library Graden's captions load (speakers and sound classes), for 64-bit ARM phones:
# one build with the 8-bit matrix-multiply instructions and one without, into app/src/main/jniLibs.
# Usage: tools/audiocpp/build.sh [ndk dir]   (needs git, cmake, ninja)
set -e
COMMIT=ccdbe76929e8a77428db6ec3bbbaadeb35acae38
HERE=$(cd "$(dirname "$0")" && pwd)
APP=$(cd "$HERE/../.." && pwd)/app/src/main
NDK=${1:-$(ls -d "$HOME"/Library/Android/sdk/ndk/27.* "$HOME"/Android/Sdk/ndk/27.* 2>/dev/null | head -1)}
WORK=${WORK:-$HERE/.work}
mkdir -p "$WORK"
if [ ! -d "$WORK/audio.cpp" ]; then
  git clone --filter=blob:none https://github.com/0xShug0/audio.cpp "$WORK/audio.cpp"
fi
cd "$WORK/audio.cpp"
git checkout -q -f "$COMMIT"
git apply "$HERE/graden.patch"
# Source paths compiled into the library (by assert messages) are made relative, so it never carries this machine's folders.
SRC=$(pwd -P)
MAP="-ffile-prefix-map=$SRC=audio.cpp -ffile-prefix-map=$(pwd)=audio.cpp -ffile-prefix-map=$WORK=work -ffile-prefix-map=$NDK=ndk"
build() {  # name, -march
  rm -f "build-$1/CMakeCache.txt"
  cmake -S . -B "build-$1" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release \
    -DAUDIOCPP_MODEL_SET=custom -DAUDIOCPP_MODELS="nemotron_3_diar;ced" -DAUDIOCPP_BUILD_C_API=ON \
    -DENGINE_ENABLE_NEON=ON -DENGINE_ENABLE_NATIVE_CPU=OFF -DENGINE_ENABLE_OPENMP=OFF -DGGML_OPENMP=OFF \
    -DENGINE_ENABLE_METAL=OFF -DENGINE_ENABLE_VULKAN=OFF -DAUDIOCPP_BUILD_NATIVE_MODEL_MANAGER=OFF \
    -DGGML_CPU_ARM_ARCH="$2" -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384 -Wl,-soname,lib$1.so" \
    -DCMAKE_C_FLAGS="$MAP" -DCMAKE_CXX_FLAGS="$MAP"
  cmake --build "build-$1" --target audiocpp
  mkdir -p "$APP/jniLibs/arm64-v8a"
  "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip -o "$APP/jniLibs/arm64-v8a/lib$1.so" "build-$1/bin/libaudiocpp.so"
}
build audiocpp_i8mm armv8.2-a+fp16+dotprod+i8mm
build audiocpp_v82 armv8.2-a+fp16+dotprod
git checkout -q -f "$COMMIT"
ls -l "$APP/jniLibs/arm64-v8a"
