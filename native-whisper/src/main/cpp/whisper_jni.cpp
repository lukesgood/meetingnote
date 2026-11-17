// JNI bridge for whisper.cpp (speech-to-text)
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#include "whisper.h"
#include "../../../../native-common/cpu_affinity.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static void log_cb(ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_WARN || level == GGML_LOG_LEVEL_ERROR) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

static jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(arr, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

static std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_meetingnote_whisper_WhisperLib_nativeInit(JNIEnv * env, jobject, jstring modelPath) {
    whisper_log_set(log_cb, nullptr);
    auto path = jstr(env, modelPath);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu    = false;   // Adreno 650: CPU(NEON+dotprod)가 더 빠르고 안정적
    cparams.flash_attn = true;
    whisper_context * ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (!ctx) LOGE("failed to load model %s", path.c_str());
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_meetingnote_whisper_WhisperLib_nativeFree(JNIEnv *, jobject, jlong handle) {
    if (handle) whisper_free(reinterpret_cast<whisper_context *>(handle));
}

// 반환: UTF-8 바이트, 각 줄 "t0_ms\tt1_ms\ttext\n"
JNIEXPORT jbyteArray JNICALL
Java_com_meetingnote_whisper_WhisperLib_nativeTranscribe(
        JNIEnv * env, jobject, jlong handle, jfloatArray pcm, jint threads,
        jstring lang, jint beamSize, jstring initialPrompt, jstring vadModelPath, jint audioCtx,
        jboolean koEnOnly, jboolean noContext, jobject progress) {
    auto * ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx) return nullptr;

    // ggml 작업 스레드는 호출 스레드의 affinity를 상속 → 빅코어에 고정
    mn::pin_current_thread(threads);

    const jsize n = env->GetArrayLength(pcm);
    std::vector<float> samples(n);
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());

    std::string language = jstr(env, lang);
    std::string prompt   = jstr(env, initialPrompt);
    std::string vadPath  = jstr(env, vadModelPath);

    whisper_full_params p = whisper_full_default_params(
            beamSize > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    p.n_threads        = threads;
    p.language         = language.empty() ? "auto" : language.c_str();
    p.translate        = false;
    p.print_progress   = false;
    p.print_realtime   = false;
    p.print_special    = false;
    p.print_timestamps = false;
    p.no_timestamps    = false;
    p.suppress_nst     = true;
    p.beam_search.beam_size = beamSize > 1 ? beamSize : 5;
    p.greedy.best_of        = 5;
    if (!prompt.empty()) p.initial_prompt = prompt.c_str();
    if (!vadPath.empty()) {
        p.vad = true;
        p.vad_model_path = vadPath.c_str();
    }
    // 짧은 조각은 인코더 창을 실제 길이에 맞게 줄여 연산 절감 (0 = 기본 30초 창)
    if (audioCtx > 0) p.audio_ctx = audioCtx;
    // 앞 구간 인식 결과를 다음 30초 창의 문맥으로 넘기지 않음 (잘못 인식한 문장이 이어지며 번지는 것 방지)
    if (noContext) { p.no_context = true; p.n_max_text_ctx = 0; }
    // 진행률(0~100) 콜백: 긴 오디오 정밀 인식의 진행 표시용
    struct Prog { JNIEnv * env; jobject obj; jmethodID mid; } prog { env, progress, nullptr };
    if (progress) {
        prog.mid = env->GetMethodID(env->GetObjectClass(progress), "onProgress", "(I)V");
        p.progress_callback = [](whisper_context *, whisper_state *, int pct, void * ud) {
            auto * pr = static_cast<Prog *>(ud);
            pr->env->CallVoidMethod(pr->obj, pr->mid, (jint) pct);
        };
        p.progress_callback_user_data = &prog;
    }

    if (whisper_full(ctx, p, samples.data(), (int) samples.size()) != 0) {
        LOGE("whisper_full failed");
        return nullptr;
    }
    // 언어 자동 감지 시 한국어/영어 외 언어로 잘못 감지되면 한국어로 다시 인식 (한↔영 통역 모드)
    if (language.empty() && koEnOnly) {
        const char * detected = whisper_lang_str(whisper_full_lang_id(ctx));
        if (detected && std::string(detected) != "ko" && std::string(detected) != "en") {
            p.language = "ko";
            p.detect_language = false;
            if (whisper_full(ctx, p, samples.data(), (int) samples.size()) != 0) return nullptr;
        }
    }

    std::string out = "#lang\t";
    {
        const char * l = whisper_lang_str(whisper_full_lang_id(ctx));
        out += l ? l : "";
        out += '\n';
    }
    const int ns = whisper_full_n_segments(ctx);
    for (int i = 0; i < ns; ++i) {
        // t0/t1 단위: 10ms
        out += std::to_string(whisper_full_get_segment_t0(ctx, i) * 10);
        out += '\t';
        out += std::to_string(whisper_full_get_segment_t1(ctx, i) * 10);
        out += '\t';
        out += whisper_full_get_segment_text(ctx, i);
        out += '\n';
    }
    return to_bytes(env, out);
}

JNIEXPORT jstring JNICALL
Java_com_meetingnote_whisper_WhisperLib_nativeSystemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

} // extern "C"
