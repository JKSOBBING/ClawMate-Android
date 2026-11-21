#include "llama.h"

#include <jni.h>
#include <algorithm>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
constexpr int CONTEXT_LIMIT = 4096;
constexpr int PREFILL_BATCH = 128;

struct Session {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    llama_sampler * sampler = nullptr;
    std::vector<llama_token> tokens;
    size_t cursor = 0;

    ~Session() {
        if (sampler) llama_sampler_free(sampler);
        if (context) llama_free(context);
        if (model) llama_model_free(model);
    }
};

// Keep standard UTF-8 across JNI, including supplementary Unicode characters.
std::string bytes(JNIEnv * env, jbyteArray array) {
    std::string result(env->GetArrayLength(array), '\0');
    env->GetByteArrayRegion(array, 0, static_cast<jsize>(result.size()),
                            reinterpret_cast<jbyte *>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Could not read input");
    return result;
}

std::vector<std::string> strings(JNIEnv * env, jobjectArray array) {
    std::vector<std::string> result;
    const jsize count = env->GetArrayLength(array);
    result.reserve(count);
    for (jsize i = 0; i < count; ++i) {
        auto value = static_cast<jbyteArray>(env->GetObjectArrayElement(array, i));
        if (!value) throw std::runtime_error("Missing chat message");
        result.push_back(bytes(env, value));
        env->DeleteLocalRef(value);
    }
    return result;
}

// libllama and standard-library errors must never escape the JNI entry point.
template<typename T, typename Work>
T checked(JNIEnv * env, T fallback, Work work) {
    try {
        return work();
    } catch (const std::exception & error) {
        if (!env->ExceptionCheck()) {
            env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        }
        return fallback;
    }
}

std::string format_prompt(llama_model * model, const std::vector<std::string> & roles,
                          const std::vector<std::string> & texts) {
    if (roles.size() != texts.size()) throw std::runtime_error("Invalid GGUF chat messages");
    std::vector<llama_chat_message> chat;
    for (size_t i = 0; i < roles.size(); ++i) chat.push_back({roles[i].c_str(), texts[i].c_str()});
    const auto * tmpl = llama_model_chat_template(model, nullptr);
    auto format = [&](char * output, int capacity) {
        return llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, output, capacity);
    };
    const int size = format(nullptr, 0);
    if (size <= 0) throw std::runtime_error("Unsupported GGUF chat template");
    std::string prompt(size, '\0');
    if (format(prompt.data(), size) != size) throw std::runtime_error("Could not format GGUF chat");
    return prompt;
}

void decode(Session & session, llama_token * tokens, int count) {
    if (llama_decode(session.context, llama_batch_get_one(tokens, count)) != 0) {
        throw std::runtime_error("GGUF evaluation failed. Check the model and available memory.");
    }
}

// v0.4.5 calls progress synchronously on the loading thread, between tensor
// reads. open() owns this JNI frame until llama_model_load_from_file returns.
struct LoadProgress {
    JNIEnv * env;
    jobject check_active;
    jmethodID run;

    static bool report(float, void * user_data) {
        auto & self = *static_cast<LoadProgress *>(user_data);
        self.env->CallVoidMethod(self.check_active, self.run);
        // The Kotlin check throws CancellationException. Returning false tells
        // llama to release a partial model; JNI then propagates that exception.
        return !self.env->ExceptionCheck();
    }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_clawmate_inference_NativeGguf_open(JNIEnv * env, jobject, jbyteArray path, jint threads,
                                            jobject check_active) {
    return checked(env, jlong(0), [&] {
        static std::once_flag initialized;
        std::call_once(initialized, llama_backend_init);
        auto session = std::make_unique<Session>();
        auto params = llama_model_default_params();
        params.n_gpu_layers = 0;
        jclass runnable = env->FindClass("java/lang/Runnable");
        if (!runnable) return jlong(0);
        LoadProgress progress{env, check_active, env->GetMethodID(runnable, "run", "()V")};
        if (!progress.run) return jlong(0);
        params.progress_callback = LoadProgress::report;
        params.progress_callback_user_data = &progress;
        session->model = llama_model_load_from_file(bytes(env, path).c_str(), params);
        if (env->ExceptionCheck()) return jlong(0);
        if (!session->model) throw std::runtime_error("Cannot load GGUF. Check model compatibility and available memory.");
        if (!llama_model_has_decoder(session->model) || llama_model_has_encoder(session->model)) {
            throw std::runtime_error("This engine requires a decoder-only GGUF text model.");
        }
        auto ctx = llama_context_default_params();
        ctx.n_ctx = std::min(CONTEXT_LIMIT, llama_model_n_ctx_train(session->model));
        ctx.n_batch = PREFILL_BATCH;
        ctx.n_ubatch = PREFILL_BATCH;
        ctx.n_threads = threads;
        ctx.n_threads_batch = threads;
        session->context = llama_init_from_model(session->model, ctx);
        if (!session->context) throw std::runtime_error("Could not allocate the GGUF inference context.");
        session->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(session->sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(session->sampler, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(session->sampler, llama_sampler_init_temp(0.7f));
        llama_sampler_chain_add(session->sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        return reinterpret_cast<jlong>(session.release());
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_clawmate_inference_NativeGguf_prompt(JNIEnv * env, jobject, jlong handle,
                                               jobjectArray roles, jobjectArray texts) {
    return checked(env, jint(0), [&] {
        auto & session = *reinterpret_cast<Session *>(handle);
        auto prompt = format_prompt(session.model, strings(env, roles), strings(env, texts));
        const auto * vocab = llama_model_get_vocab(session.model);
        const int context_size = static_cast<int>(llama_n_ctx(session.context));
        session.tokens.resize(context_size);
        int count = llama_tokenize(vocab, prompt.data(), static_cast<int>(prompt.size()),
                                   session.tokens.data(), context_size, true, true);
        if (count == std::numeric_limits<int>::min() || count == 0) {
            throw std::runtime_error("GGUF tokenization failed");
        }
        if (count < 0) return 0; // More tokens than the full context window.
        session.tokens.resize(count);
        session.cursor = 0;
        return context_size - count;
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_clawmate_inference_NativeGguf_prefillBatch(JNIEnv * env, jobject, jlong handle) {
    return checked(env, jboolean(false), [&] {
        auto & session = *reinterpret_cast<Session *>(handle);
        auto count = std::min<size_t>(PREFILL_BATCH, session.tokens.size() - session.cursor);
        decode(session, session.tokens.data() + session.cursor, static_cast<int>(count));
        session.cursor += count;
        return static_cast<jboolean>(session.cursor < session.tokens.size());
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_clawmate_inference_NativeGguf_next(JNIEnv * env, jobject, jlong handle) {
    return checked(env, static_cast<jbyteArray>(nullptr), [&]() -> jbyteArray {
        auto & session = *reinterpret_cast<Session *>(handle);
        const auto * vocab = llama_model_get_vocab(session.model);
        auto token = llama_sampler_sample(session.sampler, session.context, -1);
        if (llama_vocab_is_eog(vocab, token)) return nullptr;
        std::string piece(64, '\0');
        int size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, true);
        if (size < 0) {
            piece.resize(-size);
            size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, true);
        }
        if (size < 0) throw std::runtime_error("Could not decode GGUF token");
        decode(session, &token, 1);
        auto result = env->NewByteArray(size);
        if (result) env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte *>(piece.data()));
        return result;
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_clawmate_inference_NativeGguf_close(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Session *>(handle);
}
