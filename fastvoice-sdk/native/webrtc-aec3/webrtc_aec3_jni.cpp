#include <jni.h>

#include <cstdint>
#include <exception>
#include <stdexcept>
#include <string>
#include <vector>

#include "api/audio/audio_processing.h"
#include "api/scoped_refptr.h"

namespace {

bool IsSupportedRate(int rate_hz) {
  return rate_hz == 8000 || rate_hz == 16000 || rate_hz == 32000 ||
         rate_hz == 48000;
}

struct Processor {
  Processor(int capture_rate_hz, int render_rate_hz)
      : capture_stream(capture_rate_hz, 1),
        render_stream(render_rate_hz, 1),
        render_output(render_stream.num_samples()) {
    if (!IsSupportedRate(capture_rate_hz) || !IsSupportedRate(render_rate_hz)) {
      throw std::invalid_argument("unsupported sample rate");
    }

    webrtc::AudioProcessing::Config config;
    config.echo_canceller.enabled = true;
    config.echo_canceller.mobile_mode = false;
    config.echo_canceller.enforce_high_pass_filtering = true;
    config.high_pass_filter.enabled = true;
    apm = webrtc::AudioProcessingBuilder().SetConfig(config).Create();
    if (!apm) {
      throw std::runtime_error("AudioProcessingBuilder returned null");
    }
    const int result = apm->Initialize();
    if (result != webrtc::AudioProcessing::kNoError) {
      throw std::runtime_error("AudioProcessing Initialize failed: " +
                               std::to_string(result));
    }
  }

  rtc::scoped_refptr<webrtc::AudioProcessing> apm;
  webrtc::StreamConfig capture_stream;
  webrtc::StreamConfig render_stream;
  std::vector<int16_t> render_output;
};

void Throw(JNIEnv* env, const std::string& message) {
  jclass exception_class = env->FindClass("java/lang/IllegalStateException");
  if (exception_class != nullptr) {
    env->ThrowNew(exception_class, message.c_str());
  }
}

Processor* FromHandle(jlong handle) {
  if (handle == 0) {
    throw std::runtime_error("WebRTC AEC3 handle is closed");
  }
  return reinterpret_cast<Processor*>(handle);
}

void RequireFrame(JNIEnv* env,
                  jshortArray frame,
                  size_t expected,
                  const char* label) {
  if (frame == nullptr ||
      env->GetArrayLength(frame) != static_cast<jsize>(expected)) {
    throw std::invalid_argument(std::string(label) + " has wrong length");
  }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_zxkws_fastvoice_internal_WebRtcEchoCanceller_nativeCreate(
    JNIEnv* env, jobject, jint capture_rate_hz, jint render_rate_hz) {
  try {
    return reinterpret_cast<jlong>(
        new Processor(capture_rate_hz, render_rate_hz));
  } catch (const std::exception& error) {
    Throw(env, error.what());
    return 0;
  }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_zxkws_fastvoice_internal_WebRtcEchoCanceller_nativeProcessRender(
    JNIEnv* env, jobject, jlong handle, jshortArray input) {
  try {
    Processor* processor = FromHandle(handle);
    RequireFrame(env, input, processor->render_stream.num_samples(),
                 "render frame");
    jshort* samples = env->GetShortArrayElements(input, nullptr);
    if (samples == nullptr) throw std::runtime_error("cannot read render frame");
    const int result = processor->apm->ProcessReverseStream(
        reinterpret_cast<int16_t*>(samples), processor->render_stream,
        processor->render_stream, processor->render_output.data());
    env->ReleaseShortArrayElements(input, samples, JNI_ABORT);
    if (result != webrtc::AudioProcessing::kNoError) {
      throw std::runtime_error("ProcessReverseStream failed: " +
                               std::to_string(result));
    }
    return result;
  } catch (const std::exception& error) {
    Throw(env, error.what());
    return -1;
  }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_zxkws_fastvoice_internal_WebRtcEchoCanceller_nativeProcessCapture(
    JNIEnv* env,
    jobject,
    jlong handle,
    jshortArray input,
    jshortArray output,
    jint delay_ms) {
  try {
    Processor* processor = FromHandle(handle);
    RequireFrame(env, input, processor->capture_stream.num_samples(),
                 "capture input");
    RequireFrame(env, output, processor->capture_stream.num_samples(),
                 "capture output");
    const int delay_result = processor->apm->set_stream_delay_ms(delay_ms);
    if (delay_result != webrtc::AudioProcessing::kNoError &&
        delay_result != webrtc::AudioProcessing::kBadStreamParameterWarning) {
      throw std::runtime_error("set_stream_delay_ms failed: " +
                               std::to_string(delay_result));
    }

    jshort* input_samples = env->GetShortArrayElements(input, nullptr);
    if (input_samples == nullptr) {
      throw std::runtime_error("cannot read capture input");
    }
    jshort* output_samples = env->GetShortArrayElements(output, nullptr);
    if (output_samples == nullptr) {
      env->ReleaseShortArrayElements(input, input_samples, JNI_ABORT);
      throw std::runtime_error("cannot write capture output");
    }
    const int result = processor->apm->ProcessStream(
        reinterpret_cast<int16_t*>(input_samples), processor->capture_stream,
        processor->capture_stream,
        reinterpret_cast<int16_t*>(output_samples));
    env->ReleaseShortArrayElements(input, input_samples, JNI_ABORT);
    env->ReleaseShortArrayElements(output, output_samples, 0);
    if (result != webrtc::AudioProcessing::kNoError) {
      throw std::runtime_error("ProcessStream failed: " +
                               std::to_string(result));
    }
    return result;
  } catch (const std::exception& error) {
    Throw(env, error.what());
    return -1;
  }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_zxkws_fastvoice_internal_WebRtcEchoCanceller_nativeDelayEstimateMs(
    JNIEnv* env, jobject, jlong handle) {
  try {
    const auto stats = FromHandle(handle)->apm->GetStatistics();
    return stats.delay_ms.value_or(-1);
  } catch (const std::exception& error) {
    Throw(env, error.what());
    return -1;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_zxkws_fastvoice_internal_WebRtcEchoCanceller_nativeClose(
    JNIEnv*, jobject, jlong handle) {
  delete reinterpret_cast<Processor*>(handle);
}
