// JNI bridge for llama.cpp (on-device LLM summarization)
#include <cstring>
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-cpu.h"
#include "ggml-backend.h"
#include "common.h"
#include "sampling.h"
#include "speculative.h"
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
    // 추측 생성 (Gemma 4 MTP 보조 모델): 보조 모델이 다음 토큰 몇 개를 미리 짐작하고 본 모델이 한 번에 확인.
    // 폰(SD 7s Gen 2)에서 번역 생성 약 2배, 회의록 구간 메모 약 1.4배 (2026-11-20)
    common_params                      spec_params;
    common_speculative_init_result_ptr spec_init;
    common_speculative *               spec = nullptr;
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


// 추측 생성 (llama.cpp examples/speculative-simple와 같은 흐름). KV 앞부분 재사용·진행률·중단은 일반 생성과 동일.
// 마지막 프롬프트 토큰은 디코드하지 않고 남겨 두었다가 [마지막 토큰, 추측 토큰들]을 한 번에 확인한다.
jstring generate_spec(JNIEnv * env, Session * s, const std::vector<llama_token> & toks, size_t common,
        int maxTokens, float temperature, float topP, float repeatPenalty,
        jobject callback, jmethodID onToken, jobject progress, jmethodID onPrompt) {
    llama_context * ctx = s->ctx;
    llama_context * ctx_dft = s->spec_params.speculative.draft.ctx_dft;
    llama_memory_t mem = llama_get_memory(ctx);
    const llama_seq_id seq = 0;
    // 보조 모델이 직전 위치의 상태를 받을 수 있도록 마지막 토큰 앞의 한 토큰 이상은 이번에 다시 계산
    if (common + 2 > toks.size()) common = toks.size() >= 2 ? toks.size() - 2 : 0;
    llama_memory_seq_rm(mem, seq, (llama_pos) common, -1);
    if (ctx_dft) llama_memory_seq_rm(llama_get_memory(ctx_dft), seq, (llama_pos) common, -1);
    s->cached.assign(toks.begin(), toks.begin() + common);
    auto fail = [&](const char * msg) {
        llama_memory_clear(mem, true);
        if (ctx_dft) llama_memory_clear(llama_get_memory(ctx_dft), true);
        s->cached.clear();
        return env->NewStringUTF(msg);
    };

    const int64_t t0 = ggml_time_us();
    const int n_batch = onPrompt ? std::min<int>(256, (int) llama_n_batch(ctx)) : (int) llama_n_batch(ctx);
    const size_t n_prompt = toks.size() - 1;              // 마지막 토큰 제외
    const int to_eval = (int) (toks.size() - common);
    for (size_t i = common; i < n_prompt; i += n_batch) {
        const size_t n = std::min<size_t>(n_batch, n_prompt - i);
        common_batch b(ctx);
        for (size_t k = 0; k < n; ++k) b.add(toks[i + k], (llama_pos) (i + k), seq, false);
        if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, b.get()) != 0) return fail("error:decode_failed");
        if (!common_speculative_process(s->spec, b)) return fail("error:decode_failed");
        s->cached.insert(s->cached.end(), toks.begin() + i, toks.begin() + i + n);
        if (onPrompt) env->CallVoidMethod(progress, onPrompt, (jint) (i + n - common), (jint) to_eval);
        if (s->cancel) return fail("error:cancelled");
    }
    const int64_t t1 = ggml_time_us();

    common_params_sampling sp;
    sp.temp = temperature; sp.top_p = topP; sp.top_k = 0; sp.min_p = 0.0f;
    sp.penalty_repeat = repeatPenalty; sp.penalty_last_n = 256;
    common_sampler_ptr smpl(common_sampler_init(s->model, sp));

    llama_tokens prompt_tgt(toks.begin(), toks.end() - 1);
    llama_token id_last = toks.back();
    int n_past = (int) prompt_tgt.size();
    common_speculative_begin(s->spec, seq, prompt_tgt);

    std::string pending;
    int n_gen = 0;
    bool stop = false;
    llama_tokens draft;
    common_batch bt(ctx);
    while (!stop && !s->cancel && n_gen < maxTokens) {
        int n_draft_max = std::min((int) llama_n_ctx(ctx) - n_past - 2, maxTokens - n_gen - 1);
        n_draft_max = std::max(n_draft_max, 0);
        draft.clear();
        common_speculative_get_draft_params(s->spec, seq) = { true, n_draft_max, n_past, id_last, &prompt_tgt, &draft };
        common_speculative_draft(s->spec);
        if (ctx_dft) llama_memory_seq_rm(llama_get_memory(ctx_dft), seq, n_past, -1);

        bt.clear();
        bt.add(id_last, n_past, seq, true);
        for (size_t i = 0; i < draft.size(); ++i) bt.add(draft[i], n_past + 1 + (llama_pos) i, seq, true);
        if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, bt.get()) != 0) return fail("error:decode_failed");
        if (!common_speculative_process(s->spec, bt)) return fail("error:decode_failed");

        auto ids = common_sampler_sample_and_accept_n(smpl.get(), ctx, draft);
        common_speculative_accept(s->spec, seq, ids.size() - 1);
        n_past += 1;                                       // id_last
        for (size_t i = 0; i < ids.size(); ++i) {
            prompt_tgt.push_back(id_last);
            id_last = ids[i];
            if (i > 0) n_past += 1;                        // 받아들인 추측 토큰
            if (llama_vocab_is_eog(s->vocab, id_last)) { stop = true; break; }
            char piece[256];
            int len = llama_token_to_piece(s->vocab, id_last, piece, sizeof(piece), 0, false);
            if (len > 0) pending.append(piece, len);
            ++n_gen;
            size_t ok = utf8_complete_prefix(pending);
            if (ok > 0) {
                jbyteArray arr = to_bytes(env, pending.substr(0, ok));
                jboolean cont = env->CallBooleanMethod(callback, onToken, arr);
                env->DeleteLocalRef(arr);
                pending.erase(0, ok);
                if (!cont) { stop = true; break; }
            }
            if (n_gen >= maxTokens) { stop = true; break; }
        }
        // KV에는 prompt_tgt까지만 남김 (확인에 쓰고 버린 추측 토큰 제거). id_last는 다음 반복에서 계산
        n_past = (int) prompt_tgt.size();
        llama_memory_seq_rm(mem, seq, n_past, -1);
        if (ctx_dft) llama_memory_seq_rm(llama_get_memory(ctx_dft), seq, n_past, -1);
    }
    const int64_t t2 = ggml_time_us();
    s->cached = prompt_tgt;

    std::string perf = std::to_string(toks.size() - common) + "," + std::to_string((t1 - t0) / 1000) + "," +
                       std::to_string(n_gen) + "," + std::to_string((t2 - t1) / 1000) + "," + std::to_string(common);
    LOGI("perf(spec) %s", perf.c_str());
    return env->NewStringUTF(perf.c_str());
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_meetingnote_llama_LlamaLib_nativeLoad(JNIEnv * env, jobject, jstring modelPath, jint nCtx, jint threads, jstring draftPath, jint accel) {
    llama_log_set(log_cb, nullptr);
    llama_backend_init();

    auto path = jstr(env, modelPath);
    llama_model_params mp = llama_model_default_params();
    // 가속 판(그래픽 칩·AI 칩): 기기 점검에서 고른 장치에 모든 레이어를 올림. accel 0=CPU, 1=그래픽 칩, 2=AI 칩
    int gpu_layers = 0;
    ggml_backend_dev_t accel_devs[2] = { nullptr, nullptr };
#ifdef MN_GPU
    llama_backend_init();
    if (accel > 0) {
        for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
            ggml_backend_dev_t d = ggml_backend_dev_get(i);
            const std::string name = ggml_backend_dev_name(d);
            const bool is_npu = name.rfind("HTP", 0) == 0;
            const bool is_gpu = ggml_backend_dev_type(d) == GGML_BACKEND_DEVICE_TYPE_GPU && !is_npu;
            if ((accel == 1 && is_gpu) || (accel == 2 && is_npu)) { accel_devs[0] = d; break; }
        }
        if (accel_devs[0]) {
            mp.devices = accel_devs;
            gpu_layers = 999;
            LOGI("accelerator: %s (%s)", ggml_backend_dev_name(accel_devs[0]), ggml_backend_dev_description(accel_devs[0]));
        } else {
            LOGI("accelerator %d not available, using CPU", (int) accel);
        }
    }
#else
    (void) accel;
#endif
    mp.n_gpu_layers = gpu_layers;
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

    // 보조 모델(MTP)이 있으면 추측 생성 준비. 실패하면 일반 생성으로 동작
    const std::string dpath = jstr(env, draftPath);
    if (!dpath.empty() && common_context_can_seq_rm(ctx) == COMMON_CONTEXT_SEQ_RM_TYPE_PART) {   // 추측 토큰을 지울 수 있어야 함
        try {
            common_params & p = s->spec_params;
            p.model.path = path;
            p.n_ctx = nCtx; p.n_batch = 512; p.n_ubatch = 512;
            p.cpuparams.n_threads = threads; p.cpuparams_batch.n_threads = threads;
            p.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
            p.speculative.types = { COMMON_SPECULATIVE_TYPE_DRAFT_MTP };
            p.speculative.draft.mparams.path = dpath;
            p.n_gpu_layers = gpu_layers;
            p.speculative.draft.n_gpu_layers = gpu_layers;
            p.speculative.draft.cpuparams.n_threads = threads;
            p.speculative.draft.cpuparams_batch.n_threads = threads;
            common_params pd = common_base_params_to_speculative(p);
            s->spec_init = common_speculative_init_from_params(pd, model, ctx);
            llama_context * ctx_dft = s->spec_init ? s->spec_init->context() : nullptr;
            if (ctx_dft) {
                if (pool) llama_attach_threadpool(ctx_dft, pool, pool);
                p.speculative.draft.ctx_tgt = ctx;
                p.speculative.draft.ctx_dft = ctx_dft;
                s->spec = common_speculative_init(p.speculative, 1);
            }
        } catch (const std::exception & e) {
            LOGE("speculative init failed: %s", e.what());
            s->spec = nullptr;
        }
        LOGI("speculative (MTP): %s", s->spec ? "on" : "off");
    }
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_meetingnote_llama_LlamaLib_nativeFree(JNIEnv *, jobject, jlong h) {
    auto * s = reinterpret_cast<Session *>(h);
    if (!s) return;
    if (s->spec) common_speculative_free(s->spec);
    s->spec_init.reset();          // 보조 컨텍스트는 본 컨텍스트보다 먼저 해제 (KV를 공유)
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
// Gemma 4: llama_chat_apply_template의 내장 목록에 없음(<|turn> 형식) → 직접 구성.
// GGUF의 jinja 템플릿과 같은 형식이며, 사고 모드는 켜지 않는다(시스템 턴에 <|think|>를 넣지 않음).
static bool is_gemma4(const char * tmpl) { return tmpl && strstr(tmpl, "<|turn>") != nullptr; }
static std::string gemma4_format(const std::vector<std::pair<std::string, std::string>> & msgs) {
    std::string out;
    for (const auto & m : msgs) {
        const std::string role = m.first == "assistant" ? "model" : m.first;
        out += "<|turn>" + role + "\n" + m.second + "<turn|>\n";
    }
    out += "<|turn>model\n";
    return out;
}

JNIEXPORT jbyteArray JNICALL
Java_com_meetingnote_llama_LlamaLib_nativeFormatChat(JNIEnv * env, jobject, jlong h,
        jstring system, jstring user, jstring assistantPrefix) {
    auto * s = reinterpret_cast<Session *>(h);
    std::string sys = jstr(env, system), usr = jstr(env, user), pre = jstr(env, assistantPrefix);

    std::vector<llama_chat_message> msgs;
    if (!sys.empty()) msgs.push_back({"system", sys.c_str()});
    msgs.push_back({"user", usr.c_str()});

    const char * tmpl = llama_model_chat_template(s->model, nullptr);
    if (is_gemma4(tmpl)) {
        std::vector<std::pair<std::string, std::string>> m;
        if (!sys.empty()) m.emplace_back("system", sys);
        m.emplace_back("user", usr);
        return to_bytes(env, gemma4_format(m) + pre);
    }
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
    if (is_gemma4(tmpl)) {
        std::vector<std::pair<std::string, std::string>> m;
        for (jsize i = 0; i < n; ++i) m.emplace_back(r[i], c[i]);
        return to_bytes(env, gemma4_format(m) + jstr(env, assistantPrefix));
    }
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

    if (s->spec) {
        return generate_spec(env, s, toks, common, maxTokens, temperature, topP, repeatPenalty, callback, onToken, progress, onPrompt);
    }

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

/** 이 판에서 쓸 수 있는 그래픽 칩 이름 (그래픽 칩 판이 아니거나 없으면 빈 문자열) */
JNIEXPORT jstring JNICALL
Java_com_meetingnote_llama_LlamaLib_nativeGpuName(JNIEnv * env, jobject) {
    std::string name;
#ifdef MN_GPU
    llama_backend_init();
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        if (ggml_backend_dev_type(d) == GGML_BACKEND_DEVICE_TYPE_CPU) continue;
        if (!name.empty()) name += ", ";
        name += std::string(ggml_backend_dev_name(d)) + "=" + ggml_backend_dev_description(d);
    }
#endif
    return env->NewStringUTF(name.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_meetingnote_llama_LlamaLib_nativeSystemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

} // extern "C"
