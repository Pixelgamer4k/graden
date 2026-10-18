#!/bin/sh
# Fetches the OpenCV 4.10.0 Android SDK (Apache-2.0) and keeps what the visual gyro links: headers and the static
# core, imgproc and video libraries (plus their 3rdparty helpers) for every ABI, in app/src/main/cpp/third_party/opencv.
set -e
cd "$(dirname "$0")/.."
O=app/src/main/cpp/third_party/opencv
T=$(mktemp -d)
curl -sSL -o "$T/cv.zip" https://github.com/opencv/opencv/releases/download/4.10.0/opencv-4.10.0-android-sdk.zip
echo "464d55abe64b53af3aeb73197cf4620535abd909916d06e6a8b7c32712013966  $T/cv.zip" | shasum -a 256 -c -
unzip -q "$T/cv.zip" 'OpenCV-android-sdk/sdk/native/jni/include/*' 'OpenCV-android-sdk/sdk/native/staticlibs/*' 'OpenCV-android-sdk/sdk/native/3rdparty/*' 'OpenCV-android-sdk/LICENSE' -d "$T"
X="$T/OpenCV-android-sdk"
rm -rf "$O"; mkdir -p "$O/lib"
cp -R "$X/sdk/native/jni/include" "$O/"; cp "$X/LICENSE" "$O/"
for a in arm64-v8a armeabi-v7a; do
  mkdir -p "$O/lib/$a"
  cp "$X/sdk/native/staticlibs/$a/"libopencv_core.a "$X/sdk/native/staticlibs/$a/"libopencv_imgproc.a "$X/sdk/native/staticlibs/$a/"libopencv_video.a "$O/lib/$a/"
  for l in libtegra_hal libtbb libcpufeatures libittnotify; do [ -f "$X/sdk/native/3rdparty/libs/$a/$l.a" ] && cp "$X/sdk/native/3rdparty/libs/$a/$l.a" "$O/lib/$a/"; done
done
rm -rf "$T"
echo "OpenCV in $O"
