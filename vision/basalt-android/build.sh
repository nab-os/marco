#!/usr/bin/env bash
# Cross-compiles Basalt (vision/third_party/basalt, Monado's fork) and what it
# needs for arm64 Android phones, then the desktop runner for the phone too.
#
#   vision/basalt-android/build.sh [WORK_DIR]
#
# WORK_DIR (default vision/basalt-android/build) gets the downloaded sources,
# the build trees and prefix/: libbasalt.so, libtbb.so, libc++_shared.so and
# bin/basalt_runner. Needs the Android NDK (ANDROID_NDK_HOME, or the newest
# under ~/Android/Sdk/ndk), cmake and ninja.
#
# Licences: Basalt BSD-3, oneTBB Apache-2.0, OpenCV Apache-2.0, fmt MIT.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BASALT="$HERE/../third_party/basalt"
WORK="$(mkdir -p "${1:-$HERE/build}" && cd "${1:-$HERE/build}" && pwd)"
PREFIX="$WORK/prefix"
NDK="${ANDROID_NDK_HOME:-$(ls -d "$HOME"/Android/Sdk/ndk/* | sort -V | tail -1)}"
ABI=arm64-v8a
API=31
JOBS="${JOBS:-12}"

TBB_VERSION=2022.3.0
FMT_VERSION=11.2.0
OPENCV_VERSION=4.10.0

ANDROID_ARGS=(
  -G Ninja
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
  -DANDROID_ABI=$ABI
  -DANDROID_PLATFORM=android-$API
  -DANDROID_STL=c++_shared
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_INSTALL_PREFIX="$PREFIX"
  -DCMAKE_PREFIX_PATH="$PREFIX"
  -DCMAKE_FIND_ROOT_PATH="$PREFIX"
  # NDK 30's libc++ no longer includes <cstdlib> transitively, and fmt's format.h
  # (also seen by Basalt) calls malloc/free without including it.
  "-DCMAKE_CXX_FLAGS=-include cstdlib"
)

fetch() { # url dir
  [ -d "$WORK/$2" ] && return
  echo "== fetching $2"
  curl -sSL "$1" | tar xz -C "$WORK"
}

fetch "https://github.com/uxlfoundation/oneTBB/archive/refs/tags/v$TBB_VERSION.tar.gz" "oneTBB-$TBB_VERSION"
fetch "https://github.com/fmtlib/fmt/archive/refs/tags/$FMT_VERSION.tar.gz" "fmt-$FMT_VERSION"
fetch "https://github.com/opencv/opencv/archive/refs/tags/$OPENCV_VERSION.tar.gz" "opencv-$OPENCV_VERSION"

build() { # name source-dir cmake-args...
  local name=$1 src=$2
  shift 2
  if [ -f "$WORK/$name.done" ]; then return; fi
  echo "== building $name"
  cmake -S "$src" -B "$WORK/build-$name" "${ANDROID_ARGS[@]}" "$@" > "$WORK/$name.log"
  cmake --build "$WORK/build-$name" -j "$JOBS" --target install >> "$WORK/$name.log"
  touch "$WORK/$name.done"
}

build tbb "$WORK/oneTBB-$TBB_VERSION" -DTBB_TEST=OFF -DTBB_EXAMPLES=OFF -DTBBMALLOC_BUILD=OFF -DTBB_STRICT=OFF
build fmt "$WORK/fmt-$FMT_VERSION" -DFMT_DOC=OFF -DFMT_TEST=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON
# Only what Basalt's library uses (FAST corners, a few matrix types), statically; highgui
# without a backend, because Basalt's CMake asks for it.
build opencv "$WORK/opencv-$OPENCV_VERSION" \
  -DBUILD_LIST=core,imgproc,features2d,calib3d,flann,imgcodecs,highgui \
  -DBUILD_SHARED_LIBS=OFF -DBUILD_ANDROID_PROJECTS=OFF -DBUILD_ANDROID_EXAMPLES=OFF -DBUILD_JAVA=OFF \
  -DBUILD_TESTS=OFF -DBUILD_PERF_TESTS=OFF -DBUILD_DOCS=OFF -DBUILD_opencv_apps=OFF \
  -DWITH_IPP=OFF -DWITH_ITT=OFF -DWITH_OPENCL=OFF -DWITH_TBB=OFF -DWITH_EIGEN=OFF -DWITH_ADE=OFF \
  -DWITH_JPEG=OFF -DWITH_TIFF=OFF -DWITH_WEBP=OFF -DWITH_OPENJPEG=OFF -DWITH_JASPER=OFF -DWITH_OPENEXR=OFF \
  -DWITH_PNG=ON -DBUILD_PNG=ON -DBUILD_ZLIB=ON -DOPENCV_GENERATE_PKGCONFIG=OFF \
  -DWITH_PROTOBUF=OFF -DBUILD_PROTOBUF=OFF \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON

# OpenCV's Android install puts its config under sdk/native/jni.
OPENCV_DIR="$(dirname "$(find "$PREFIX" -name OpenCVConfig.cmake | head -1)")"

# Basalt links with mold when it finds it, which lacks the NDK's linker options.
build basalt "$BASALT" \
  -DBASALT_BUILD_SHARED_LIBRARY_ONLY=ON -DBASALT_ENABLE_ROSBAG2=OFF -DBASALT_BUILD_TESTS=OFF \
  -DSKIP_PERFORMANCE_COMPARISON=ON -DMOLD_PROGRAM=MOLD_PROGRAM-NOTFOUND \
  -DCXX_MARCH=armv8.2-a -DOpenCV_DIR="$OPENCV_DIR" -DTBB_DIR="$PREFIX/lib/cmake/TBB" -Dfmt_DIR="$PREFIX/lib/cmake/fmt"

build runner "$HERE/../tools/basalt_runner" \
  -DBASALT_LIB="$PREFIX/lib/libbasalt.so" -DOpenCV_DIR="$OPENCV_DIR"

# The C++ runtime the libraries share.
cp "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$PREFIX/lib/"
echo "== done: $PREFIX/lib"
ls -la "$PREFIX"/lib/*.so* "$PREFIX/bin/basalt_runner"
