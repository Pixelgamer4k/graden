#!/usr/bin/env bash
# Builds the optional software encoders (x264 and x265, both GPL) for 64-bit Android, into
# app/src/main/cpp/third_party. Then build the app with: ./gradlew assembleRelease -Psoftenc=true
#
# Needs: git, cmake, make, and the Android NDK (set ANDROID_NDK_HOME, or have it under the Android SDK).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/.codec-build"
OUT="$ROOT/app/src/main/cpp/third_party"
API=29

NDK="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK" ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  NDK="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)"
fi
[ -d "$NDK" ] || { echo "Android NDK not found: set ANDROID_NDK_HOME" >&2; exit 1; }
HOST="$(ls "$NDK/toolchains/llvm/prebuilt" | head -1)"
TC="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
JOBS="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"

mkdir -p "$WORK" "$OUT/include" "$OUT/lib/arm64-v8a"
cd "$WORK"
[ -d x264 ] || git clone --depth 1 -b stable https://code.videolan.org/videolan/x264.git x264
[ -d x265 ] || git clone --depth 1 https://bitbucket.org/multicoreware/x265_git.git x265

# x264: 8- and 10-bit in one library, with the ARM assembly.
rm -rf build-x264 && mkdir build-x264 && cd build-x264
CC="$TC/aarch64-linux-android$API-clang" AR="$TC/llvm-ar" RANLIB="$TC/llvm-ranlib" STRIP="$TC/llvm-strip" \
  ../x264/configure --host=aarch64-linux-android --prefix="$WORK/prefix" --enable-static --enable-pic \
  --disable-cli --disable-opencl --bit-depth=all --extra-cflags="-O3"
make -j"$JOBS" && make install
cd "$WORK"

# x265: the assembly is compiled with an explicit Android target.
sed -i.bak 's/^        set(ARM_ARGS -O3)$/        set(ARM_ARGS -O3 --target=aarch64-linux-android'"$API"' -fPIC)/' x265/source/CMakeLists.txt
COMMON=(-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=$API
        -DCMAKE_BUILD_TYPE=Release -DENABLE_SHARED=OFF -DENABLE_CLI=OFF -DENABLE_PIC=ON -DCMAKE_POLICY_VERSION_MINIMUM=3.5)
rm -rf build-x265-10 build-x265-8 && mkdir build-x265-10 build-x265-8
(cd build-x265-10 && cmake ../x265/source "${COMMON[@]}" -DHIGH_BIT_DEPTH=ON -DEXPORT_C_API=OFF -DMAIN12=OFF && make -j"$JOBS")
cp build-x265-10/libx265.a build-x265-8/libx265_main10.a
(cd build-x265-8 && cmake ../x265/source "${COMMON[@]}" -DEXTRA_LIB=x265_main10.a -DEXTRA_LINK_FLAGS=-L. -DLINKED_10BIT=ON && make -j"$JOBS")
(cd build-x265-8 && printf 'CREATE libx265_all.a\nADDLIB libx265.a\nADDLIB libx265_main10.a\nSAVE\nEND\n' | "$TC/llvm-ar" -M)

cp prefix/include/x264.h prefix/include/x264_config.h "$OUT/include/"
cp prefix/lib/libx264.a "$OUT/lib/arm64-v8a/"
cp x265/source/x265.h build-x265-8/x265_config.h "$OUT/include/"
cp build-x265-8/libx265_all.a "$OUT/lib/arm64-v8a/libx265.a"
echo "Done. Build with: ./gradlew assembleRelease -Psoftenc=true"
