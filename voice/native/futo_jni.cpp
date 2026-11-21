#include <jni.h>
#include "whisper.h"
#include <algorithm>
#include <cstring>
#include <exception>
#include <string>
#include <vector>

namespace {

void report_error(JNIEnv * env, const char * message) {
    if (!env->ExceptionCheck()) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
    }
}

void quiet_log(ggml_log_level, const char *, void *) {}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_ru_local_voyah_musicsearch_FutoSpeech_load(JNIEnv * env, jclass, jstring path) {
    const char * filename = env->GetStringUTFChars(path, nullptr);
    if (!filename) return 0;
    whisper_context * context = nullptr;
    try {
        whisper_log_set(quiet_log, nullptr);
        auto config = whisper_context_default_params();
        config.use_gpu = false;
        context = whisper_init_from_file_with_params(filename, config);
    } catch (const std::exception &) {
        report_error(env, "FUTO model could not be loaded");
    }
    env->ReleaseStringUTFChars(path, filename);
    if (!context) report_error(env, "FUTO model could not be loaded");
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ru_local_voyah_musicsearch_FutoSpeech_decode(JNIEnv * env, jclass, jlong handle,
                                                jfloatArray audio, jint language) {
    auto * context = reinterpret_cast<whisper_context *>(handle);
    const int count = env->GetArrayLength(audio);
    if (!context || count < 1600 || count > 16000 * 15) {
        report_error(env, "Invalid FUTO audio buffer");
        return nullptr;
    }
    try {
        std::vector<float> samples(count);
        env->GetFloatArrayRegion(audio, 0, count, samples.data());
        if (env->ExceptionCheck()) return nullptr;
        auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = 3;
        params.language = language == 1 ? "ru" : language == 2 ? "en" : "ru,en";
        params.audio_ctx = std::min(whisper_n_audio_ctx(context), (count + 319) / 320 + 50);
        params.greedy.best_of = 1;
        params.no_context = true;
        params.no_timestamps = true;
        params.temperature_inc = 0.0f;
        params.print_progress = false;
        params.print_realtime = false;
        params.print_timestamps = false;
        if (whisper_full(context, params, samples.data(), count) != 0) {
            report_error(env, "FUTO could not decode the recording");
            return nullptr;
        }
        std::string text;
        for (int i = 0; i < whisper_full_n_segments(context); ++i) {
            text += whisper_full_get_segment_text(context, i);
        }
        jbyteArray bytes = env->NewByteArray(text.size());
        if (bytes) env->SetByteArrayRegion(bytes, 0, text.size(), reinterpret_cast<const jbyte *>(text.data()));
        return bytes;
    } catch (const std::exception &) {
        report_error(env, "FUTO could not decode the recording");
        return nullptr;
    }
}
