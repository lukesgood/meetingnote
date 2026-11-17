// JNI bridge for llama.cpp (on-device LLM summarization)
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-cpu.h"
#include "../../../../native-common/cpu_affinity.h"

#define TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model *       model = nullptr;
    llama_context *     ctx   = nullptr;
    const llama_vocab * vocab = nullptr;
    ggml_threadpool *   pool  = nullptr;
    std::atomic<bool>   cancel{false};
    // KV 캐시에 현재 들어 있는 토큰 열 (다음 프롬프트와 겹치는 앞부분은 다시 계산하지 않음)
    std::vector<llama_token> cached;
    bool                reuse = true;
};

void log_cb(ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_WARN || level == GGML_LOG_LEVEL_ERROR) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    // GetStringUTFChars는 Modified UTF-8이므로 이모지 등에서 깨질 수 있어 바이트로 변환
    jclass strCls = env->GetObjectClass(s);
    jmethodID getBytes = env->GetMethodID(strCls, "getBytes", "(Ljava/lang/String;)[B");
    jstring charset = env->NewStringUTF("UTF-8");
    auto arr = (jbyteArray) env->CallObjectMethod(s, getBytes, charset);
    jsize len = env->GetArrayLength(arr);
    std::string out(len, '\0');
    env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte *>(&out[0]));
    env->DeleteLocalRef(arr);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(strCls);
    return out;
}

jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(arr, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

// 버퍼 앞부분 중 완전한 UTF-8 시퀀스로 끝나는 길이
size_t utf8_complete_prefix(const std::string & s) {
    size_t i = s.size();
    // 끝에서 최대 3바이트까지 역추적하여 미완성 시퀀스 확인
    for (size_t back = 1; back <= 4 && back <= s.size(); ++back) {
        unsigned char c = (unsigned char) s[s.size() - back];
        if ((c & 0xC0) == 0x80) continue;          // continuation byte
        size_t need = 1;
        if      ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        if (need > back) i = s.size() - back;      // incomplete
        break;
    }
    return i;
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool add_special) {
    int n = -llama_tokenize(vocab, text.data(), (int) text.size(), nullptr, 0, add_special, true);
    std::vector<llama_token> toks(n);
    llama_tokenize(vocab, text.data(), (int) text.size(), toks.data(), n, add_special, true);
    return toks;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_meetingnote_llama_LlamaLib_nativeLoad(JNIEnv * env, jobject, jstring modelPath, jint nCtx, jint threads) {
    llama_log_set(log_cb, nullptr);
    llama_backend_init();

    auto path = jstr(env, modelPath);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) { LOGE("failed to load %s", path.c_str()); return 0; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = (uint32_t) nCtx;
    cp.n_batch         = 512;
    cp.n_ubatch        = 512;
    cp.n_threads       = threads;
    cp.n_threads_batch = threads;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    cp.no_perf         = false;
    cp.swa_full        = true;   // 슬라이딩 윈도 모델도 앞부분 재사용이 가능하도록
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) { LOGE("failed to create context"); llama_model_free(model); return 0; }

    // 빅코어에 고정된 전용 스레드풀 (스케줄러가 리틀코어로 옮기는 것을 방지)
    ggml_threadpool_params tpp = ggml_threadpool_params_default(threads);
    for (int c : mn::fastest_cores(threads)) tpp.cpumask[c] = true;
    tpp.strict_cpu = true;
    ggml_threadpool * pool = ggml_threadpool_new(&tpp);
    if (pool) llama_attach_threadpool(ctx, pool, pool);

    auto * s = new Session();
    s->model = model;
    s->ctx   = ctx;
    s->pool  = pool;
    s->vocab = llama_model_get_vocab(model);
    LOGI("n_swa=%d", llama_model_n_swa(model));
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_meetingnote_llama_LlamaLib_nativeFree(JNIEnv *, jobject, jlong h) {
    auto * s = reinterpret_cast<Session *>(h);
    if (!s) return;
    llama_free(s->ctx);
    if (s->pool) ggml_threadpool_free(s->pool);
    llama_model_free(s->model);
    delete s;
}

JNIEXPORT jint JNICALL
Java_com_meetingnote_llama_LlamaLib_nativeCountTokens(JNIEnv * env, jobject, jlong h, jstring text) {
    auto * s = reinterpret_cast<Session *>(h);
    return (jint) tokenize(s->vocab, jstr(env, text), false).size();
}

// 모델 내장 채팅 템플릿으로 (system, user) 프롬프트를 구성. assistantPrefix는 응답 앞에 강제로 붙일 텍스트.
JNIEXPORT jbyteArray JNICALL
Java_com_meetingnote_llama_LlamaLib_nativeFormatChat(JNIEnv * env, jobject, jlong h,
        jstring system, jstring user, jstring assistantPrefix) {
    auto * s = reinterpret_cast<Session *>(h);
    std::string sys = jstr(env, system), usr = jstr(env, user), pre = jstr(env, assistantPrefix);

    std::vector<llama_chat_message> msgs;
    if (!sys.empty()) msgs.push_back({"system", sys.c_str()});
    msgs.push_back({"user", usr.c_str()});

    const char * tmpl = llama_model_chat_template(s->model, nullptr);
    std::vector<char> buf(usr.size() * 2 + sys.size() * 2 + 1024);
    int n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int) buf.size());
    if (n > (int) buf.size()) {
        buf.resize(n + 1);
        n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int) buf.size());
    }
    std::string out;
    if (n < 0) {
        LOGE("chat template not supported, falling back to chatml");
        if (!sys.empty()) out += "<|im_start|>system\n" + sys + "<|im_end|>\n";
        out += "<|im_start|>user\n" + usr + "<|im_end|>\n<|im_start|>assistant\n";
    } else {
        out.assign(buf.data(), n);
    }
    out += pre;
    return to_bytes(env, out);
}

// 여러 턴 대화(roles[i], contents[i])를 채팅 템플릿으로 구성
JNIEXPORT jbyteArray JNICALL
Java_com_meetingnote_llama_LlamaLib_nativeFormatMessages(JNIEnv * env, jobject, jlong h,
        jobjectArray roles, jobjectArray contents, jstring assistantPrefix) {
    auto * s = reinterpret_cast<Session *>(h);
    const jsize n = env->GetArrayLength(roles);
    std::vector<std::string> r(n), c(n);
    size_t total = 0;
    for (jsize i = 0; i < n; ++i) {
        auto jr = (jstring) env->GetObjectArrayElement(roles, i);
        auto jc = (jstring) env->GetObjectArrayElement(contents, i);
        r[i] = jstr(env, jr); c[i] = jstr(env, jc);
        env->DeleteLocalRef(jr); env->DeleteLocalRef(jc);
        total += c[i].size() + 64;
    }
    std::vector<llama_chat_message> msgs;
    for (jsize i = 0; i < n; ++i) msgs.push_back({r[i].c_str(), c[i].c_str()});

    const char * tmpl = llama_model_chat_template(s->model, nullptr);
    std::vector<char> buf(total * 2 + 1024);
    int len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int) buf.size());
    if (len > (int) buf.size()) {
        buf.resize(len + 1);
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int) buf.size());
    }
    std::string out;
    if (len < 0) {
        for (jsize i = 0; i < n; ++i) out += "<|im_start|>" + r[i] + "\n" + c[i] + "<|im_end|>\n";
        out += "<|im_start|>assistant\n";
    } else {
        out.assign(buf.data(), len);
    }
    out += jstr(env, assistantPrefix);
    return to_bytes(env, out);
}

JNIEXPORT void JNICALL
Java_com_meetingnote_llama_LlamaLib_nativeCancel(JNIEnv *, jobject, jlong h) {
    auto * s = reinterpret_cast<Session *>(h);
    if (s) s->cancel = true;
}

// prompt(이미 템플릿 적용됨)로부터 생성. callback.onToken(byte[]) 이 false를 반환하면 중단.
// 반환: 성능 정보 문자열 "prompt_tokens,prompt_ms,gen_tokens,gen_ms"
JNIEXPORT jstring JNICALL
Java_com_meetingnote_llama_LlamaLib_nativeGenerate(JNIEnv * env, jobject, jlong h, jstring prompt,
        jint maxTokens, jfloat temperature, jfloat topP, jfloat repeatPenalty, jobject callback, jobject progress) {
    auto * s = reinterpret_cast<Session *>(h);
    s->cancel = false;

    jclass cbCls = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbCls, "onToken", "([B)Z");
    jmethodID onPrompt = nullptr;
    if (progress) onPrompt = env->GetMethodID(env->GetObjectClass(progress), "onPrompt", "(II)V");

    mn::pin_current_thread((int) llama_n_threads(s->ctx));
    llama_memory_t mem = llama_get_memory(s->ctx);

    auto toks = tokenize(s->vocab, jstr(env, prompt), true);
    const int n_ctx = (int) llama_n_ctx(s->ctx);
    if ((int) toks.size() + maxTokens > n_ctx) {
        LOGE("prompt too long: %zu tokens (ctx %d)", toks.size(), n_ctx);
        return env->NewStringUTF("error:prompt_too_long");
    }

    // 직전 호출과 같은 앞부분(시스템 프롬프트·이전 대화)은 KV 캐시를 그대로 쓰고 나머지만 계산
    size_t common = 0;
    if (s->reuse) {
        while (common < s->cached.size() && common < toks.size() && s->cached[common] == toks[common]) ++common;
        if (common == toks.size() && common > 0) --common;   // 마지막 토큰은 로짓을 얻기 위해 다시 계산
    }
    if (common > 0 && !llama_memory_seq_rm(mem, 0, (llama_pos) common, -1)) {
        LOGI("partial KV removal unsupported; disabling prefix reuse");
        s->reuse = false;
        common = 0;
    }
    if (common == 0) llama_memory_clear(mem, true);
    s->cached.assign(toks.begin(), toks.begin() + common);
    auto fail = [&](const char * msg) {
        llama_memory_clear(mem, true);
        s->cached.clear();
        return env->NewStringUTF(msg);
    };

    const int64_t t0 = ggml_time_us();
    // 진행률을 받는 경우 더 잘게 나눠 처리 (긴 프롬프트도 화면 진척이 자주 갱신되도록)
    const int n_batch = onPrompt ? std::min<int>(256, (int) llama_n_batch(s->ctx)) : (int) llama_n_batch(s->ctx);
    const int to_eval = (int) (toks.size() - common);
    for (size_t i = common; i < toks.size(); i += n_batch) {
        int n = std::min<int>(n_batch, (int) (toks.size() - i));
        if (llama_decode(s->ctx, llama_batch_get_one(toks.data() + i, n)) != 0) return fail("error:decode_failed");
        s->cached.insert(s->cached.end(), toks.begin() + i, toks.begin() + i + n);
        if (onPrompt) env->CallVoidMethod(progress, onPrompt, (jint) (i + n - common), (jint) to_eval);
        if (s->cancel) return fail("error:cancelled");
    }
    const int64_t t1 = ggml_time_us();

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
            llama_vocab_n_tokens(s->vocab), 256, repeatPenalty, 0.0f, 0.0f));
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    std::string pending;
    int n_gen = 0;
    char piece[256];
    for (; n_gen < maxTokens && !s->cancel; ++n_gen) {
        llama_token tok = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, tok)) break;
        int len = llama_token_to_piece(s->vocab, tok, piece, sizeof(piece), 0, false);
        if (len > 0) pending.append(piece, len);

        size_t ok = utf8_complete_prefix(pending);
        if (ok > 0) {
            jbyteArray arr = to_bytes(env, pending.substr(0, ok));
            jboolean cont = env->CallBooleanMethod(callback, onToken, arr);
            env->DeleteLocalRef(arr);
            pending.erase(0, ok);
            if (!cont) break;
        }
        if (llama_decode(s->ctx, llama_batch_get_one(&tok, 1)) != 0) {
            llama_memory_clear(mem, true);
            s->cached.clear();
            break;
        }
        s->cached.push_back(tok);
    }
    const int64_t t2 = ggml_time_us();
    llama_sampler_free(smpl);

    std::string perf = std::to_string(toks.size() - common) + "," + std::to_string((t1 - t0) / 1000) + "," +
                       std::to_string(n_gen) + "," + std::to_string((t2 - t1) / 1000) + "," + std::to_string(common);
    LOGI("perf %s", perf.c_str());
    return env->NewStringUTF(perf.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_meetingnote_llama_LlamaLib_nativeSystemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

} // extern "C"
