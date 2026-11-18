#include <jni.h>
#include <algorithm>
#include <string>
#include <thread>
#include <vector>
#include "whisper.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_andmx_ui2_chat_voice_WhisperNative_init(JNIEnv *env, jobject, jstring modelPath) {
    if (modelPath == nullptr) return 0;
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) return 0;
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_andmx_ui2_chat_voice_WhisperNative_transcribe(
        JNIEnv *env, jobject, jlong handle, jfloatArray pcm, jstring language) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(handle);
    if (ctx == nullptr || pcm == nullptr) return nullptr;

    const jsize n = env->GetArrayLength(pcm);
    if (n <= 0) return nullptr;
    std::vector<float> samples(static_cast<size_t>(n));
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());
    if (env->ExceptionCheck()) return nullptr;

    std::string lang = "auto";
    if (language != nullptr) {
        const char *l = env->GetStringUTFChars(language, nullptr);
        if (l != nullptr) {
            lang = l;
            env->ReleaseStringUTFChars(language, l);
        }
    }
    if (lang.empty()) lang = "auto";

    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    unsigned int cores = std::thread::hardware_concurrency();
    wparams.n_threads = static_cast<int>(std::max(1u, std::min(4u, cores)));
    wparams.language = lang.c_str();
    wparams.translate = false;
    wparams.no_timestamps = true;
    wparams.print_special = false;
    wparams.print_progress = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;

    if (whisper_full(ctx, wparams, samples.data(), static_cast<int>(samples.size())) != 0) {
        return nullptr;
    }

    std::string text;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; i++) {
        const char *segment = whisper_full_get_segment_text(ctx, i);
        if (segment != nullptr) text += segment;
    }
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT void JNICALL
Java_com_andmx_ui2_chat_voice_WhisperNative_release(JNIEnv *, jobject, jlong handle) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(handle);
    if (ctx != nullptr) whisper_free(ctx);
}

}
