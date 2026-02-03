#include <android/log.h>
#include <jni.h>
#include <atomic>
#include <chrono>
#include <iomanip>
#include <math.h>
#include <string>
#include <unordered_map>
#include <unistd.h>
#include "llama.h"
#include <codecvt>
#include <locale>
#include "common.h"

#define TAG "llama-android.cpp"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

jclass la_int_var;
jmethodID la_int_var_value;
jmethodID la_int_var_inc;

std::string cached_token_chars;
static std::unordered_map<llama_batch *, int> g_batch_n_tokens;
static std::vector<std::string> g_stop_strings;
static std::string g_generated_text;
static std::atomic<bool> g_stop_requested(false);

bool is_valid_utf8(const char *string) {
    if (!string) {
        return true;
    }

    const unsigned char *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            // U+0000 to U+007F
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            // U+0080 to U+07FF
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            // U+0800 to U+FFFF
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            // U+10000 to U+10FFFF
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }

    return true;
}

static JavaVM *g_jvm = nullptr;
static jclass g_llama_android_class = nullptr;
static jmethodID g_log_from_native_method = nullptr;
static std::atomic<int> g_n_threads(-1);
static std::atomic<int> g_n_threads_batch(-1);
static std::atomic<float> g_temperature(0.7f);
static std::atomic<float> g_top_p(0.9f);
static std::atomic<int> g_top_k(40);
static std::atomic<int> g_n_ctx(4096);
static std::atomic<bool> g_kv_cache_reuse(true);
static std::vector<llama_token> g_cached_tokens;

static jstring new_jstring_utf8(JNIEnv *env, const char *text) {
    if (!text) {
        return env->NewStringUTF("");
    }

    try {
        std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t> converter;
        std::u16string u16 = converter.from_bytes(text);
        return env->NewString(reinterpret_cast<const jchar *>(u16.data()),
                              static_cast<jsize>(u16.size()));
    } catch (const std::range_error &) {
        std::string sanitized;
        sanitized.reserve(strlen(text));
        for (const unsigned char ch: std::string(text)) {
            sanitized.push_back(ch < 0x80 ? static_cast<char>(ch) : '?');
        }
        return env->NewStringUTF(sanitized.c_str());
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_configureThreads(JNIEnv *, jclass, jint n_threads,
                                                     jint n_threads_batch) {
    g_n_threads.store(n_threads);
    g_n_threads_batch.store(n_threads_batch);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_configureSampling(JNIEnv *, jclass, jfloat temperature,
                                                      jfloat top_p, jint top_k) {
    g_temperature.store(temperature);
    g_top_p.store(top_p);
    g_top_k.store(top_k);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_configureContext(JNIEnv *, jclass, jint n_ctx) {
    if (n_ctx <= 0) {
        return;
    }
    g_n_ctx.store(n_ctx);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_configureKvCacheReuse(JNIEnv *, jclass, jboolean enabled) {
    g_kv_cache_reuse.store(enabled == JNI_TRUE);
}

template<typename JVM>
static auto attach_current_thread_impl(JVM *jvm, JNIEnv **env, int)
-> decltype(jvm->AttachCurrentThread(env, nullptr), jint{}) {
    // Header has the JNIEnv** signature
    return jvm->AttachCurrentThread(env, nullptr);
}

template<typename JVM>
static jint attach_current_thread_impl(JVM *jvm, JNIEnv **env, long) {
    // Fallback for headers that want void** (e.g., Flox)
    void *venv = nullptr;
    jint r = jvm->AttachCurrentThread(&venv, nullptr);
    *env = reinterpret_cast<JNIEnv *>(venv);
    return r;
}

static inline jint attach_current_thread(JavaVM *jvm, JNIEnv **env) {
    return attach_current_thread_impl(jvm, env, 0);
}

void log_to_kotlin_bridge(ggml_log_level level, const char *message) {
    if (!g_jvm || !g_llama_android_class || !g_log_from_native_method) {
        __android_log_print(ANDROID_LOG_DEBUG, "llama.cpp", "%s", message);
        return;
    }

    JNIEnv *env = nullptr;
    bool did_attach_thread = false;

    jint get_env_result = g_jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (get_env_result == JNI_EDETACHED) {
        if (attach_current_thread(g_jvm, &env) != JNI_OK || !env) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "AttachCurrentThread failed");
            return;
        }
        did_attach_thread = true;
    } else if (get_env_result != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "GetEnv failed");
        return;
    }

    jstring jni_message = new_jstring_utf8(env, message);
    if (jni_message == nullptr) {
        // Handle potential out-of-memory error
        if (did_attach_thread) {
            g_jvm->DetachCurrentThread();
        }
        return;
    }

    env->CallStaticVoidMethod(g_llama_android_class, g_log_from_native_method, (jint) level,
                              jni_message);
    env->DeleteLocalRef(jni_message);

    // ✨ THE FIX: Only detach the thread if we were the ones who attached it.
    // In the case of the Llm-RunLoop, we will NOT detach it.
    if (did_attach_thread) {
        g_jvm->DetachCurrentThread();
    }
}

void log_info_to_kt(const char *fmt, ...) {
    char buffer[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);

    log_to_kotlin_bridge((ggml_log_level) 4, buffer);
}

static void slf4j_log_callback(ggml_log_level level, const char *fmt, void *data) {
    log_to_kotlin_bridge(level, fmt);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return -1;
    }

    jclass local_class = env->FindClass("android/llama/cpp/LLamaAndroid");
    if (!local_class) return -1;
    g_llama_android_class = (jclass) env->NewGlobalRef(local_class);

    g_log_from_native_method = env->GetStaticMethodID(g_llama_android_class, "logFromNative",
                                                      "(ILjava/lang/String;)V");
    if (!g_log_from_native_method) return -1;

    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_android_llama_cpp_LLamaAndroid_load_1model(JNIEnv *env, jobject, jstring filename) {
    llama_model_params model_params = llama_model_default_params();

    auto path_to_model = env->GetStringUTFChars(filename, 0);
    LOGi("Loading model from %s", path_to_model);

    auto model = llama_model_load_from_file(path_to_model, model_params);
    env->ReleaseStringUTFChars(filename, path_to_model);

    if (!model) {
        LOGe("load_model() failed");
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "load_model() failed");
        return 0;
    }

    return reinterpret_cast<jlong>(model);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_free_1model(JNIEnv *, jobject, jlong model) {
    llama_model_free(reinterpret_cast<llama_model *>(model));
}

extern "C"
JNIEXPORT jlong JNICALL
Java_android_llama_cpp_LLamaAndroid_new_1context(JNIEnv *env, jobject, jlong jmodel) {
    auto model = reinterpret_cast<llama_model *>(jmodel);

    if (!model) {
        LOGe("new_context(): model cannot be null");
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Model cannot be null");
        return 0;
    }

    int default_threads = std::max(1, std::min(8, (int) sysconf(_SC_NPROCESSORS_ONLN) - 2));
    int n_threads = g_n_threads.load();
    if (n_threads <= 0) {
        n_threads = default_threads;
    }
    int n_threads_batch = g_n_threads_batch.load();
    if (n_threads_batch <= 0) {
        n_threads_batch = n_threads;
    }
    LOGi("Using %d threads (batch=%d)", n_threads, n_threads_batch);

    llama_context_params ctx_params = llama_context_default_params();

    const int configured_ctx = g_n_ctx.load();
    ctx_params.n_ctx = configured_ctx > 0 ? configured_ctx : 4096;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads_batch;

    llama_context *context = llama_init_from_model(model, ctx_params);

    if (!context) {
        LOGe("llama_new_context_with_model() returned null)");
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "llama_new_context_with_model() returned null)");
        return 0;
    }

    return reinterpret_cast<jlong>(context);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_free_1context(JNIEnv *, jobject, jlong context) {
    llama_free(reinterpret_cast<llama_context *>(context));
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_backend_1free(JNIEnv *, jobject) {
    llama_backend_free();
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_log_1to_1android(JNIEnv *, jobject) {
    llama_log_set(slf4j_log_callback, NULL);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_android_llama_cpp_LLamaAndroid_bench_1model(
        JNIEnv *env,
        jobject,
        jlong context_pointer,
        jlong model_pointer,
        jlong batch_pointer,
        jint pp,
        jint tg,
        jint pl,
        jint nr
) {
    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    const auto context = reinterpret_cast<llama_context *>(context_pointer);
    const auto model = reinterpret_cast<llama_model *>(model_pointer);
    const auto batch = reinterpret_cast<llama_batch *>(batch_pointer);

    const int n_ctx = llama_n_ctx(context);

    LOGi("n_ctx = %d", n_ctx);

    int i, j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        LOGi("Benchmark prompt processing (pp)");

        common_batch_clear(*batch);

        const int n_tokens = pp;
        for (i = 0; i < n_tokens; i++) {
            common_batch_add(*batch, 0, i, {0}, false);
        }

        batch->logits[batch->n_tokens - 1] = true;
        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp_start = ggml_time_us();
        if (llama_decode(context, *batch) != 0) {
            LOGi("llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        // bench text generation

        LOGi("Benchmark text generation (tg)");

        llama_memory_clear(llama_get_memory(context), false);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {

            common_batch_clear(*batch);
            for (j = 0; j < pl; j++) {
                common_batch_add(*batch, 0, i, {j}, true);
            }

            LOGi("llama_decode() text generation: %d", i);
            if (llama_decode(context, *batch) != 0) {
                LOGi("llama_decode() failed during text generation");
            }
        }

        const auto t_tg_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;

        const auto speed_pp = double(pp) / t_pp;
        const auto speed_tg = double(pl * tg) / t_tg;

        pp_avg += speed_pp;
        tg_avg += speed_tg;

        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;

        LOGi("pp %f t/s, tg %f t/s", speed_pp, speed_tg);
    }

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    if (nr > 1) {
        pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0;
        tg_std = 0;
    }

    char model_desc[128];
    llama_model_desc(model, model_desc, sizeof(model_desc));

    const auto model_size = double(llama_model_size(model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(model)) / 1e9;

    const auto backend = "(Android)"; // TODO: What should this be?

    std::stringstream result;
    result << std::setprecision(2);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |\n";

    return new_jstring_utf8(env, result.str().c_str());
}

extern "C"
JNIEXPORT jlong JNICALL
Java_android_llama_cpp_LLamaAndroid_new_1batch(JNIEnv *, jobject, jint n_tokens, jint embd,
                                               jint n_seq_max) {

    // Source: Copy of llama.cpp:llama_batch_init but heap-allocated.

    llama_batch *batch = new llama_batch{
            0,
            nullptr,
            nullptr,
            nullptr,
            nullptr,
            nullptr,
            nullptr,
    };

    if (embd) {
        batch->embd = (float *) malloc(sizeof(float) * n_tokens * embd);
    } else {
        batch->token = (llama_token *) malloc(sizeof(llama_token) * n_tokens);
    }

    batch->pos = (llama_pos *) malloc(sizeof(llama_pos) * n_tokens);
    batch->n_seq_id = (int32_t *) malloc(sizeof(int32_t) * n_tokens);
    batch->seq_id = (llama_seq_id **) malloc(sizeof(llama_seq_id *) * n_tokens);
    for (int i = 0; i < n_tokens; ++i) {
        batch->seq_id[i] = (llama_seq_id *) malloc(sizeof(llama_seq_id) * n_seq_max);
    }
    batch->logits = (int8_t *) malloc(sizeof(int8_t) * n_tokens);

    g_batch_n_tokens[batch] = n_tokens;
    return reinterpret_cast<jlong>(batch);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_free_1batch(JNIEnv *, jobject, jlong batch_pointer) {
    const auto batch = reinterpret_cast<llama_batch *>(batch_pointer);
    if (!batch) return;

    free(batch->token);
    free(batch->embd);
    free(batch->pos);
    free(batch->n_seq_id);
    if (batch->seq_id) {
        auto it = g_batch_n_tokens.find(batch);
        if (it != g_batch_n_tokens.end()) {
            for (int i = 0; i < it->second; ++i) {
                free(batch->seq_id[i]);
            }
            g_batch_n_tokens.erase(it);
        }
    }
    free(batch->seq_id);
    free(batch->logits);
    delete batch;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_android_llama_cpp_LLamaAndroid_new_1sampler(JNIEnv *, jobject) {
    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler *smpl = llama_sampler_chain_init(sparams);

    // The last n tokens to consider for the repetition penalty.
    // -1 means use the entire context size. 64 is a common default.
    int32_t penalty_last_n = 64;

    // The penalty value. 1.0 means no penalty. 1.1 is a good start.
    float penalty_repeat = 1.1f;

    // The following two penalties are disabled (set to 0.0) but are required
    // by the function signature.
    float penalty_freq = 0.0f;
    float penalty_present = 0.0f;

    // **THE FIX:** Add the penalties sampler to the chain.
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
            penalty_last_n,
            penalty_repeat,
            penalty_freq,
            penalty_present
    ));

    const float temperature = g_temperature.load();
    const float top_p = g_top_p.load();
    const int top_k = g_top_k.load();

    if (temperature > 0.0f) {
        if (top_k > 0) {
            llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
        }
        if (top_p > 0.0f && top_p < 1.0f) {
            llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
        }
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        const auto seed = static_cast<uint32_t>(
                std::chrono::steady_clock::now().time_since_epoch().count()
        );
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed));
    } else {
        // The chain must end with a sampler that actually selects a token.
        // Greedy is the simplest (always picks the most likely token).
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    }

    return reinterpret_cast<jlong>(smpl);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_free_1sampler(JNIEnv *, jobject, jlong sampler_pointer) {
    llama_sampler_free(reinterpret_cast<llama_sampler *>(sampler_pointer));
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_backend_1init(JNIEnv *, jobject, jboolean numa) {
    llama_backend_init();
}

extern "C"
JNIEXPORT jstring JNICALL
Java_android_llama_cpp_LLamaAndroid_system_1info(JNIEnv *env, jobject) {
    return new_jstring_utf8(env, llama_print_system_info());
}

static int g_prompt_tokens = 0;

extern "C"
JNIEXPORT jint JNICALL
Java_android_llama_cpp_LLamaAndroid_completion_1init(
        JNIEnv *env,
        jobject,
        jlong context_pointer,
        jlong batch_pointer,
        jstring jtext,
        jboolean format_chat,
        jint n_len, jobjectArray stop) {

    cached_token_chars.clear();
    g_generated_text.clear();
    g_stop_requested.store(false);

    // Parse stop strings from the Java array
    g_stop_strings.clear();
    if (stop != nullptr) {
        int stop_count = env->GetArrayLength(stop);
        for (int i = 0; i < stop_count; i++) {
            auto jstr = (jstring) env->GetObjectArrayElement(stop, i);
            if (jstr) {
                const char *chars = env->GetStringUTFChars(jstr, nullptr);
                if (chars) {
                    g_stop_strings.emplace_back(chars);
                    env->ReleaseStringUTFChars(jstr, chars);
                }
                env->DeleteLocalRef(jstr);
            }
        }
    }

    const auto text = env->GetStringUTFChars(jtext, 0);
    const auto context = reinterpret_cast<llama_context *>(context_pointer);
    const auto batch = reinterpret_cast<llama_batch *>(batch_pointer);

    bool parse_special = (format_chat == JNI_TRUE);
    const auto tokens_list = common_tokenize(context, text, true, parse_special);

    int n_ctx = llama_n_ctx(context);
    size_t n_kv_req = tokens_list.size() + static_cast<size_t>(n_len);
    LOGi("n_len = %d, n_ctx = %d, n_kv_req = %zu", n_len, n_ctx, n_kv_req);

    if (n_kv_req > n_ctx) {
        LOGe("error: n_kv_req > n_ctx, the required KV cache size is not big enough");
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                      "Prompt is too long for the model's context size.");
        return 0;
    }

    g_prompt_tokens = static_cast<int>(tokens_list.size());

    for (auto id: tokens_list) {
        LOGi("token: `%s`-> %d ", common_token_to_piece(context, id).c_str(), id);
    }

    common_batch_clear(*batch);

    bool reuse = false;
    size_t reuse_prefix = 0;
    if (g_kv_cache_reuse.load() && !g_cached_tokens.empty()) {
        if (g_cached_tokens.size() <= tokens_list.size()) {
            reuse = true;
            for (size_t i = 0; i < g_cached_tokens.size(); i++) {
                if (g_cached_tokens[i] != tokens_list[i]) {
                    reuse = false;
                    break;
                }
            }
            if (reuse) {
                reuse_prefix = g_cached_tokens.size();
            }
        }
    }

    if (!reuse) {
        // Fully reset KV cache to avoid non-consecutive sequence positions.
        llama_memory_clear(llama_get_memory(context), true);
        if (!g_kv_cache_reuse.load()) {
            g_cached_tokens.clear();
        }
        g_cached_tokens.assign(tokens_list.begin(), tokens_list.end());
        // evaluate the initial prompt
        for (auto i = 0; i < tokens_list.size(); i++) {
            common_batch_add(*batch, tokens_list[i], i, {0}, false);
        }
    } else {
        g_cached_tokens.assign(tokens_list.begin(), tokens_list.end());
        if (reuse_prefix < tokens_list.size()) {
            for (auto i = reuse_prefix; i < tokens_list.size(); i++) {
                common_batch_add(*batch, tokens_list[i], i, {0}, false);
            }
        }
    }

    if (batch->n_tokens > 0) {
        // llama_decode will output logits only for the last token of the prompt
        batch->logits[batch->n_tokens - 1] = true;
        if (llama_decode(context, *batch) != 0) {
            LOGe("llama_decode() failed");
        }
    }

    env->ReleaseStringUTFChars(jtext, text);

    return g_prompt_tokens;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_android_llama_cpp_LLamaAndroid_completion_1loop(
        JNIEnv *env,
        jobject,
        jlong context_pointer,
        jlong batch_pointer,
        jlong sampler_pointer,
        jint n_len,
        jobject intvar_ncur
) {
    const auto context = reinterpret_cast<llama_context *>(context_pointer);
    const auto batch = reinterpret_cast<llama_batch *>(batch_pointer);
    const auto sampler = reinterpret_cast<llama_sampler *>(sampler_pointer);
    const auto model = llama_get_model(context);
    const auto vocab = llama_model_get_vocab(model);

    if (!la_int_var) la_int_var = env->GetObjectClass(intvar_ncur);
    if (!la_int_var_value) la_int_var_value = env->GetMethodID(la_int_var, "getValue", "()I");
    if (!la_int_var_inc) la_int_var_inc = env->GetMethodID(la_int_var, "inc", "()V");

    if (g_stop_requested.load()) {
        return nullptr;
    }

    const auto new_token_id = llama_sampler_sample(sampler, context, -1);

    const auto n_cur = env->CallIntMethod(intvar_ncur, la_int_var_value);
    const auto generated = n_cur - g_prompt_tokens;
    if (llama_vocab_is_eog(vocab, new_token_id) || generated >= n_len) {
        return nullptr;
    }

    if (g_kv_cache_reuse.load()) {
        g_cached_tokens.push_back(new_token_id);
    }
    auto new_token_chars = common_token_to_piece(context, new_token_id);
    cached_token_chars += new_token_chars;

    jstring new_token = nullptr;
    if (is_valid_utf8(cached_token_chars.c_str())) {
        const auto prior_len = g_generated_text.size();
        g_generated_text += cached_token_chars;

        // Check if any stop string has been generated
        for (const auto &stop_str : g_stop_strings) {
            if (!stop_str.empty() && g_generated_text.length() >= stop_str.length()) {
                auto pos = g_generated_text.find(stop_str);
                if (pos != std::string::npos) {
                    LOGi("Stop string matched: %s", stop_str.c_str());
                    size_t prefix_len = pos > prior_len ? pos - prior_len : 0;
                    if (prefix_len > 0) {
                        cached_token_chars = cached_token_chars.substr(0, prefix_len);
                        new_token = new_jstring_utf8(env, cached_token_chars.c_str());
                    } else {
                        cached_token_chars.clear();
                        new_token = new_jstring_utf8(env, "");
                    }
                    g_generated_text = g_generated_text.substr(0, pos);
                    cached_token_chars.clear();
                    g_stop_requested.store(true);
                    return new_token;
                }
            }
        }

        new_token = new_jstring_utf8(env, cached_token_chars.c_str());

        log_info_to_kt("cached: %s, new_token_chars: `%s`, id: %d", cached_token_chars.c_str(),
                       new_token_chars.c_str(), new_token_id);

        cached_token_chars.clear();
    } else {
        new_token = new_jstring_utf8(env, "");
    }

    common_batch_clear(*batch);
    common_batch_add(*batch, new_token_id, n_cur, {0}, true);

    env->CallVoidMethod(intvar_ncur, la_int_var_inc);

    if (llama_decode(context, *batch) != 0) {
        LOGe("llama_decode() returned null");
    }

    return new_token;
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_kv_1cache_1clear(JNIEnv *, jobject, jlong context) {
    llama_memory_clear(llama_get_memory(reinterpret_cast<llama_context *>(context)), true);
    g_cached_tokens.clear();
}


extern "C"
JNIEXPORT jint JNICALL
Java_android_llama_cpp_LLamaAndroid_model_1n_1ctx(
        JNIEnv *env,
        jobject /* this */,
        jlong context_ptr) {
    auto *context = reinterpret_cast<llama_context *>(context_ptr);
    if (!context) {
        return 0;
    }
    return llama_n_ctx(context);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_android_llama_cpp_LLamaAndroid_tokenize(
        JNIEnv *env,
        jobject /* this */,
        jlong context_ptr,
        jstring text_to_tokenize,
        jboolean add_bos) {
    auto *context = reinterpret_cast<llama_context *>(context_ptr);
    if (!context) {
        return env->NewIntArray(0); // Return empty array if context is invalid
    }

    const char *text_chars = env->GetStringUTFChars(text_to_tokenize, nullptr);
    std::string text(text_chars);
    env->ReleaseStringUTFChars(text_to_tokenize, text_chars);

    bool parse_special = false;
    const std::vector<llama_token> tokens_list = common_tokenize(context, text, add_bos,
                                                                 parse_special);

    jintArray result = env->NewIntArray(tokens_list.size());

    if (!tokens_list.empty()) {
        env->SetIntArrayRegion(result, 0, tokens_list.size(),
                               reinterpret_cast<const jint *>(tokens_list.data()));
    }

    return result;
}
