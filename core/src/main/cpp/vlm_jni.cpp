#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <cstring>
#include <atomic>
#include <chrono>
#include "ggml-backend.h"
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define LOG_TAG "VlmJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model   * model = nullptr;
    llama_context * lctx  = nullptr;
    mtmd_context  * mctx  = nullptr;
    llama_sampler * smpl  = nullptr;
};

Engine g;
std::atomic<bool> cancelled {false};
std::atomic<int64_t> deadline_ms {0};
int64_t monotonic_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}
bool should_abort(void *) {
    const int64_t deadline = deadline_ms.load();
    return cancelled.load() || (deadline > 0 && monotonic_ms() >= deadline);
}

void release_engine() {
    if (g.smpl)  { llama_sampler_free(g.smpl);   g.smpl  = nullptr; }
    if (g.mctx)  { mtmd_free(g.mctx);            g.mctx  = nullptr; }
    if (g.lctx)  { llama_free(g.lctx);           g.lctx  = nullptr; }
    if (g.model) { llama_model_free(g.model);    g.model = nullptr; }
    llama_backend_free();
}

// Apply the model's built-in chat template; fall back to ChatML.
std::string apply_chat_template(const std::string & user_text) {
    const char * tmpl = g.model ? llama_model_chat_template(g.model, nullptr) : nullptr;
    if (tmpl == nullptr) {
        return "<|im_start|>user\n" + user_text + "<|im_end|>\n<|im_start|>assistant\n";
    }
    llama_chat_message msg { "user", user_text.c_str() };
    int32_t n = llama_chat_apply_template(tmpl, &msg, 1, true, nullptr, 0);
    if (n <= 0) {
        return "<|im_start|>user\n" + user_text + "<|im_end|>\n<|im_start|>assistant\n";
    }
    std::string buf(n, '\0');
    if (llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), n) <= 0) {
        return "<|im_start|>user\n" + user_text + "<|im_end|>\n<|im_start|>assistant\n";
    }
    return buf;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_adskipper_core_vlm_VlmEngine_nativeLoadModel(
        JNIEnv * env, jobject /* thiz */,
        jstring jmodel_path, jstring jmmproj_path, jint threads, jboolean use_gpu) {
    release_engine();

    const char * model_path  = env->GetStringUTFChars(jmodel_path, nullptr);
    const char * mmproj_path = env->GetStringUTFChars(jmmproj_path, nullptr);
    LOGI("loadModel: %s (mmproj %s), threads=%d", model_path, mmproj_path, (int) threads);

    llama_backend_init();
    ggml_backend_load_all();
    cancelled.store(false);
    deadline_ms.store(0);

    // Forward llama/ggml logs to logcat (backend selection, timings, ...).
    llama_log_set([](ggml_log_level level, const char * text, void *) {
        __android_log_print(
            level >= GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR :
            level >= GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN  : ANDROID_LOG_INFO,
            "llama", "%s", text);
    }, nullptr);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = use_gpu ? 99 : 0; // offload to Vulkan GPU when available, CPU otherwise
    g.model = llama_model_load_from_file(model_path, mparams);
    if (g.model == nullptr) {
        LOGE("failed to load model");
        env->ReleaseStringUTFChars(jmodel_path, model_path);
        env->ReleaseStringUTFChars(jmmproj_path, mmproj_path);
        release_engine();
        return JNI_FALSE;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = 4096;
    // GPU dispatches may finish before cancellation is observed; the service also rejects stale frames.
    cparams.abort_callback = should_abort;
    cparams.n_threads       = threads;
    cparams.n_threads_batch = threads;
    g.lctx = llama_init_from_model(g.model, cparams);
    if (g.lctx == nullptr) {
        LOGE("failed to create llama context");
        env->ReleaseStringUTFChars(jmodel_path, model_path);
        env->ReleaseStringUTFChars(jmmproj_path, mmproj_path);
        release_engine();
        return JNI_FALSE;
    }

    mtmd_context_params mp = mtmd_context_params_default();
    mp.use_gpu       = use_gpu; // vision encoder on Vulkan GPU when available
    mp.print_timings = false;
    mp.n_threads     = threads;
    mp.warmup        = false; // skip the load-time warmup encode pass
    g.mctx = mtmd_init_from_file(mmproj_path, g.model, mp);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    env->ReleaseStringUTFChars(jmmproj_path, mmproj_path);
    if (g.mctx == nullptr) {
        LOGE("failed to init mtmd (mmproj)");
        release_engine();
        return JNI_FALSE;
    }
    if (!mtmd_support_vision(g.mctx)) {
        LOGE("model does not support vision input");
        release_engine();
        return JNI_FALSE;
    }

    g.smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g.smpl, llama_sampler_init_greedy());

    LOGI("model loaded OK");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_adskipper_core_vlm_VlmEngine_nativeInfer(
        JNIEnv * env, jobject /* thiz */,
        jobject bitmap, jstring jprompt, jint max_tokens, jlong timeout_ms) {
    if (g.mctx == nullptr || g.lctx == nullptr || g.smpl == nullptr) {
        return env->NewStringUTF("");
    }

    if (timeout_ms <= 0) return env->NewStringUTF("");
    cancelled.store(false);
    deadline_ms.store(monotonic_ms() + timeout_ms);
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("bitmap must be RGBA_8888");
        return env->NewStringUTF("");
    }
    void * pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("lockPixels failed");
        return env->NewStringUTF("");
    }
    // RGBA -> RGB for mtmd_bitmap_init
    const uint32_t w = info.width, h = info.height;
    std::vector<unsigned char> rgb((size_t) w * h * 3);
    const auto * src = static_cast<const uint8_t *>(pixels);
    const size_t stride = info.stride;
    for (uint32_t y = 0; y < h; y++) {
        const uint8_t * row = src + (size_t) y * stride;
        for (uint32_t x = 0; x < w; x++) {
            rgb[((size_t) y * w + x) * 3 + 0] = row[x * 4 + 0];
            rgb[((size_t) y * w + x) * 3 + 1] = row[x * 4 + 1];
            rgb[((size_t) y * w + x) * 3 + 2] = row[x * 4 + 2];
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);

    const char * prompt_c = env->GetStringUTFChars(jprompt, nullptr);
    std::string user_text = std::string(mtmd_default_marker()) + "\n" + prompt_c;
    env->ReleaseStringUTFChars(jprompt, prompt_c);
    std::string text = apply_chat_template(user_text);

    mtmd_bitmap * bmp = mtmd_bitmap_init(w, h, rgb.data());
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    mtmd_input_text input { text.c_str(), /* add_special = */ true, /* parse_special = */ true };
    const mtmd_bitmap * bitmaps[1] = { bmp };

    int32_t rc = mtmd_tokenize(g.mctx, chunks, &input, bitmaps, 1);
    if (rc != 0 || should_abort(nullptr)) {
        LOGE("mtmd_tokenize failed or cancelled: %d", rc);
        mtmd_bitmap_free(bmp);
        mtmd_input_chunks_free(chunks);
        return env->NewStringUTF("");
    }

    // fresh KV per request �� screenshots are independent
    llama_memory_clear(llama_get_memory(g.lctx), true);
    llama_sampler_reset(g.smpl);

    llama_pos n_past = 0;
    rc = mtmd_helper_eval_chunks(g.mctx, g.lctx, chunks, /* n_past = */ 0, /* seq_id = */ 0,
                                 llama_n_batch(g.lctx), /* logits_last = */ true, &n_past);
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bmp);
    if (rc != 0 || should_abort(nullptr)) {
        LOGE("mtmd_helper_eval_chunks failed or cancelled: %d", rc);
        return env->NewStringUTF("");
    }

    const llama_vocab * vocab = llama_model_get_vocab(g.model);
    std::string out;
    out.reserve(256);
    int n_gen = 0;
    int32_t last_drc = 0;
    bool hit_eog = false;
    for (int i = 0; i < max_tokens && !should_abort(nullptr); i++) {
        llama_token t = llama_sampler_sample(g.smpl, g.lctx, -1);
        if (llama_vocab_is_eog(vocab, t)) { hit_eog = true; break; }
        char piece[256];
        int n = llama_token_to_piece(vocab, t, piece, sizeof(piece), 0, true);
        if (n > 0) out.append(piece, n);
        llama_sampler_accept(g.smpl, t);

        // llama_batch_get_one sets n_tokens/pos/seq for a single-token decode
        // (a hand-rolled llama_batch_init batch without n_tokens=1 is
        // rejected by llama_decode with -1).
        llama_token tok = t;
        llama_batch batch = llama_batch_get_one(&tok, 1);
        int32_t drc = llama_decode(g.lctx, batch);
        if (drc != 0) { last_drc = drc; break; }
        n_past++;
        n_gen++;
    }

    if (should_abort(nullptr) || last_drc != 0 || !hit_eog) out.clear();
    LOGI("infer done: %d tokens, cancelled=%d", n_gen, (int) should_abort(nullptr));
    // NewStringUTF aborts on invalid Modified UTF-8, and token pieces are raw
    // bytes: generation can stop mid multi-byte character (and emoji use
    // 4-byte sequences NewStringUTF never accepts). Build the string through
    // String(byte[], "UTF-8"), which substitutes U+FFFD instead of crashing.
    jbyteArray bytes = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) out.size(), (const jbyte *) out.data());
    jclass string_cls = env->FindClass("java/lang/String");
    jmethodID string_ctor = env->GetMethodID(string_cls, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    jstring result = (jstring) env->NewObject(string_cls, string_ctor, bytes, charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(string_cls);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_adskipper_core_vlm_VlmEngine_nativeRelease(JNIEnv * /* env */, jobject /* thiz */) {
    release_engine();
}


extern "C" JNIEXPORT void JNICALL
Java_com_adskipper_core_vlm_VlmEngine_nativeCancel(JNIEnv *, jobject) {
    cancelled.store(true);
}
