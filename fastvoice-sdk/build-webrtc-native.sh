#!/bin/zsh
set -euo pipefail

module_dir=${0:A:h}
native_dir="$module_dir/native/webrtc-aec3"
cache_dir="$native_dir/.cache"
source_dir="$cache_dir/webrtc-audio-processing"
build_dir="$cache_dir/build-arm64"
tools_dir="$cache_dir/tools"
cross_file_template="$native_dir/android-arm64.ini.in"
cross_file="$native_dir/android-arm64.ini"
ndk_version=27.3.13750724
source_url=https://gitlab.freedesktop.org/pulseaudio/webrtc-audio-processing.git
source_commit=846fe90a289f58b7c9303a635142aa2c7caa93e5

android_sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
ndk_dir=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
if [[ -z "$ndk_dir" && -n "$android_sdk_dir" ]]; then
  ndk_dir="$android_sdk_dir/ndk/$ndk_version"
fi
case "$(uname -s)" in
  Darwin) host_tag=darwin-x86_64 ;;
  Linux) host_tag=linux-x86_64 ;;
  *)
    print -u2 "Unsupported build host: $(uname -s)"
    exit 1
    ;;
esac
toolchain_dir="$ndk_dir/toolchains/llvm/prebuilt/$host_tag"
if [[ ! -x "$toolchain_dir/bin/aarch64-linux-android26-clang++" ]]; then
  print -u2 "Android NDK $ndk_version arm64 compiler not found. Set ANDROID_NDK_HOME."
  exit 1
fi

mkdir -p "$cache_dir"
sed "s|@TOOLCHAIN_DIR@|$toolchain_dir|g" "$cross_file_template" > "$cross_file"
if [[ ! -d "$source_dir/.git" ]]; then
  git clone \
    --depth 1 \
    --branch v2.1 \
    --single-branch \
    "$source_url" \
    "$source_dir"
fi
if ! git -C "$source_dir" cat-file -e "$source_commit^{commit}" 2>/dev/null; then
  git -C "$source_dir" fetch --depth 1 origin "$source_commit"
fi
git -C "$source_dir" checkout --detach "$source_commit"
actual_commit=$(git -C "$source_dir" rev-parse HEAD)
if [[ "$actual_commit" != "$source_commit" ]]; then
  print -u2 "Unexpected source commit: $actual_commit"
  exit 1
fi

if [[ ! -x "$tools_dir/bin/meson" ]]; then
  python3 -m venv "$tools_dir"
  "$tools_dir/bin/pip" install meson==1.8.3 ninja==1.13.0
fi
export PATH="$tools_dir/bin:$PATH"

if [[ -f "$build_dir/build.ninja" ]]; then
  "$tools_dir/bin/meson" setup "$build_dir" "$source_dir" \
    --reconfigure \
    --cross-file "$cross_file" \
    --wrap-mode=forcefallback \
    -Dgnustl=disabled \
    -Dneon=enabled
else
  "$tools_dir/bin/meson" setup "$build_dir" "$source_dir" \
    --cross-file "$cross_file" \
    --wrap-mode=forcefallback \
    -Dgnustl=disabled \
    -Dneon=enabled
fi
"$tools_dir/bin/meson" compile -C "$build_dir"

abseil_dir=$(find "$source_dir/subprojects" \
  -mindepth 1 -maxdepth 1 -type d -name 'abseil-cpp-*' | head -n 1)
if [[ -z "$abseil_dir" ]]; then
  print -u2 "Meson did not download the pinned Abseil fallback"
  exit 1
fi

jni_dir="$module_dir/src/main/jniLibs/arm64-v8a"
mkdir -p "$jni_dir"
cp \
  "$build_dir/webrtc/modules/audio_processing/libwebrtc-audio-processing-2.so" \
  "$jni_dir/libwebrtc-audio-processing-2.so"
cp \
  "$toolchain_dir/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" \
  "$jni_dir/libc++_shared.so"

"$toolchain_dir/bin/aarch64-linux-android26-clang++" \
  -shared -fPIC -O3 -std=c++17 -fvisibility=hidden \
  -I"$source_dir/webrtc" \
  -I"$abseil_dir" \
  "$native_dir/webrtc_aec3_jni.cpp" \
  -L"$build_dir/webrtc/modules/audio_processing" \
  -Wl,--no-undefined \
  -Wl,-rpath,'$ORIGIN' \
  -l:libwebrtc-audio-processing-2.so \
  -llog \
  -o "$jni_dir/libfastvoice_webrtc_aec3.so"

"$toolchain_dir/bin/llvm-strip" \
  "$jni_dir/libwebrtc-audio-processing-2.so" \
  "$jni_dir/libfastvoice_webrtc_aec3.so"

print "WebRTC core: $jni_dir/libwebrtc-audio-processing-2.so"
print "JNI adapter: $jni_dir/libfastvoice_webrtc_aec3.so"
