#!/usr/bin/env bash
# Cross-compiles WebRTC AEC3 and the FastVoice JNI adapter for the SDK's shipped ABIs.
#
# Usage:
#   ./build-webrtc-native.sh                     # builds every supported ABI
#   ./build-webrtc-native.sh arm64-v8a           # builds a single ABI
#   ./build-webrtc-native.sh armeabi-v7a arm64-v8a
#
# Outputs land directly in src/main/jniLibs/<abi>/ and are committed to the repo,
# so CI consumes the prebuilt libraries instead of running this script.
set -euo pipefail

module_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
native_dir="$module_dir/native/webrtc-aec3"
cache_dir="$native_dir/.cache"
source_dir="$cache_dir/webrtc-audio-processing"
tools_dir="$cache_dir/tools"
ndk_version=27.3.13750724
source_url=https://gitlab.freedesktop.org/pulseaudio/webrtc-audio-processing.git
source_commit=846fe90a289f58b7c9303a635142aa2c7caa93e5

# The native libraries must not require a newer platform than the SDK's minSdk.
android_api=23

supported_abis=(arm64-v8a armeabi-v7a)
if [[ $# -gt 0 ]]; then
  requested_abis=("$@")
else
  requested_abis=("${supported_abis[@]}")
fi
for abi in "${requested_abis[@]}"; do
  case "$abi" in
    arm64-v8a | armeabi-v7a) ;;
    *)
      echo "Unsupported ABI: $abi (expected one of: ${supported_abis[*]})" >&2
      exit 1
      ;;
  esac
done

android_sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
ndk_dir=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
if [[ -z "$ndk_dir" && -n "$android_sdk_dir" ]]; then
  ndk_dir="$android_sdk_dir/ndk/$ndk_version"
fi
case "$(uname -s)" in
  Darwin) host_tag=darwin-x86_64 ;;
  Linux) host_tag=linux-x86_64 ;;
  *)
    echo "Unsupported build host: $(uname -s)" >&2
    exit 1
    ;;
esac
toolchain_dir="$ndk_dir/toolchains/llvm/prebuilt/$host_tag"

# Per-ABI toolchain triples, cross-file templates, and target-specific compiler flags.
abi_cross_template() {
  case "$1" in
    arm64-v8a) echo "$native_dir/android-arm64.ini.in" ;;
    armeabi-v7a) echo "$native_dir/android-armv7a.ini.in" ;;
  esac
}
abi_clang() {
  case "$1" in
    arm64-v8a) echo "$toolchain_dir/bin/aarch64-linux-android$android_api-clang++" ;;
    armeabi-v7a) echo "$toolchain_dir/bin/armv7a-linux-androideabi$android_api-clang++" ;;
  esac
}
abi_sysroot_lib() {
  case "$1" in
    arm64-v8a) echo "$toolchain_dir/sysroot/usr/lib/aarch64-linux-android" ;;
    armeabi-v7a) echo "$toolchain_dir/sysroot/usr/lib/arm-linux-androideabi" ;;
  esac
}
# ARMv7 needs NEON requested explicitly; ARMv8 always has Advanced SIMD.
# _FILE_OFFSET_BITS must match the core library built by the armv7 cross file.
abi_jni_flags() {
  case "$1" in
    arm64-v8a) echo "" ;;
    armeabi-v7a) echo "-mfpu=neon -mfloat-abi=softfp -D_FILE_OFFSET_BITS=32" ;;
  esac
}

for abi in "${requested_abis[@]}"; do
  if [[ ! -x "$(abi_clang "$abi")" ]]; then
    echo "Android NDK $ndk_version compiler for $abi not found. Set ANDROID_NDK_HOME." >&2
    exit 1
  fi
done

# Fetch and pin the upstream source once; every ABI builds from the same tree.
mkdir -p "$cache_dir"
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
  echo "Unexpected source commit: $actual_commit" >&2
  exit 1
fi

if [[ ! -x "$tools_dir/bin/meson" ]]; then
  python3 -m venv "$tools_dir"
  "$tools_dir/bin/pip" install meson==1.8.3 ninja==1.13.0
fi
export PATH="$tools_dir/bin:$PATH"

for abi in "${requested_abis[@]}"; do
  echo "==> Building WebRTC AEC3 for $abi (API $android_api)"

  build_dir="$cache_dir/build-$abi"
  cross_file="$cache_dir/android-$abi.ini"
  clang="$(abi_clang "$abi")"
  sysroot_lib="$(abi_sysroot_lib "$abi")"
  # Word-splitting is intended: these are separate compiler arguments.
  read -r -a jni_flags <<<"$(abi_jni_flags "$abi")"

  sed "s|@TOOLCHAIN_DIR@|$toolchain_dir|g" "$(abi_cross_template "$abi")" >"$cross_file"

  meson_args=(
    --cross-file "$cross_file"
    --wrap-mode=forcefallback
    -Dgnustl=disabled
    -Dneon=enabled
  )
  if [[ -f "$build_dir/build.ninja" ]]; then
    meson_args+=(--reconfigure)
  fi
  "$tools_dir/bin/meson" setup "$build_dir" "$source_dir" "${meson_args[@]}"
  "$tools_dir/bin/meson" compile -C "$build_dir"

  abseil_dir=$(find "$source_dir/subprojects" \
    -mindepth 1 -maxdepth 1 -type d -name 'abseil-cpp-*' | head -n 1)
  if [[ -z "$abseil_dir" ]]; then
    echo "Meson did not download the pinned Abseil fallback" >&2
    exit 1
  fi

  jni_dir="$module_dir/src/main/jniLibs/$abi"
  mkdir -p "$jni_dir"
  cp \
    "$build_dir/webrtc/modules/audio_processing/libwebrtc-audio-processing-2.so" \
    "$jni_dir/libwebrtc-audio-processing-2.so"
  cp "$sysroot_lib/libc++_shared.so" "$jni_dir/libc++_shared.so"

  # --no-undefined makes the link fail rather than deferring a missing symbol to
  # dlopen() on the device.
  "$clang" \
    -shared -fPIC -O3 -std=c++17 -fvisibility=hidden \
    "${jni_flags[@]}" \
    -I"$source_dir/webrtc" \
    -I"$abseil_dir" \
    "$native_dir/webrtc_aec3_jni.cpp" \
    -L"$build_dir/webrtc/modules/audio_processing" \
    -Wl,--no-undefined \
    -Wl,-rpath,'$ORIGIN' \
    -l:libwebrtc-audio-processing-2.so \
    -o "$jni_dir/libfastvoice_webrtc_aec3.so"

  "$toolchain_dir/bin/llvm-strip" \
    "$jni_dir/libwebrtc-audio-processing-2.so" \
    "$jni_dir/libfastvoice_webrtc_aec3.so"

  echo "WebRTC core: $jni_dir/libwebrtc-audio-processing-2.so"
  echo "JNI adapter: $jni_dir/libfastvoice_webrtc_aec3.so"
done
