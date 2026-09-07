#include <jni.h>
#include <memory>
#include <string>
#include <vector>

#include "llm/llm.hpp"

using MNN::Transformer::Embedding;

namespace {

struct RagEmbeddingHandle {
    std::unique_ptr<Embedding> engine;
};

void throwJava(JNIEnv* env, const char* className, const std::string& message) {
    jclass clazz = env->FindClass(className);
    if (clazz != nullptr) {
        env->ThrowNew(clazz, message.c_str());
        env->DeleteLocalRef(clazz);
    }
}

RagEmbeddingHandle* fromHandle(JNIEnv* env, jlong handle) {
    if (handle == 0) {
        throwJava(env, "java/lang/IllegalStateException", "Embedding engine handle is closed");
        return nullptr;
    }
    return reinterpret_cast<RagEmbeddingHandle*>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_alibaba_mnnllm_android_rag_MnnEmbeddingEngine_nativeCreate(
        JNIEnv* env,
        jobject,
        jstring configPath) {
    if (configPath == nullptr) {
        throwJava(env, "java/lang/IllegalArgumentException", "Embedding config path is null");
        return 0;
    }

    const char* chars = env->GetStringUTFChars(configPath, nullptr);
    if (chars == nullptr) {
        return 0;
    }
    const std::string path(chars);
    env->ReleaseStringUTFChars(configPath, chars);

    try {
        std::unique_ptr<Embedding> engine(Embedding::createEmbedding(path, true));
        if (!engine) {
            throwJava(env, "java/lang/IllegalStateException", "MNN failed to create the embedding engine");
            return 0;
        }
        auto* handle = new RagEmbeddingHandle{std::move(engine)};
        return reinterpret_cast<jlong>(handle);
    } catch (const std::exception& error) {
        throwJava(env, "java/lang/IllegalStateException", error.what());
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown MNN embedding initialization failure");
    }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_alibaba_mnnllm_android_rag_MnnEmbeddingEngine_nativeDimensions(
        JNIEnv* env,
        jobject,
        jlong nativeHandle) {
    auto* handle = fromHandle(env, nativeHandle);
    if (handle == nullptr || !handle->engine) {
        return 0;
    }
    return static_cast<jint>(handle->engine->dim());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_alibaba_mnnllm_android_rag_MnnEmbeddingEngine_nativeEmbed(
        JNIEnv* env,
        jobject,
        jlong nativeHandle,
        jstring text) {
    auto* handle = fromHandle(env, nativeHandle);
    if (handle == nullptr || !handle->engine) {
        return nullptr;
    }
    if (text == nullptr) {
        throwJava(env, "java/lang/IllegalArgumentException", "Embedding text is null");
        return nullptr;
    }

    const char* chars = env->GetStringUTFChars(text, nullptr);
    if (chars == nullptr) {
        return nullptr;
    }
    const std::string input(chars);
    env->ReleaseStringUTFChars(text, chars);

    try {
        auto output = handle->engine->txt_embedding(input);
        if (output == nullptr || output->getInfo() == nullptr) {
            throwJava(env, "java/lang/IllegalStateException", "MNN embedding inference returned no output");
            return nullptr;
        }

        const int size = static_cast<int>(output->getInfo()->size);
        const int dimensions = handle->engine->dim();
        if (size != dimensions || dimensions <= 0) {
            throwJava(env, "java/lang/IllegalStateException", "MNN embedding output dimensions are invalid");
            return nullptr;
        }

        const float* values = output->readMap<float>();
        if (values == nullptr) {
            throwJava(env, "java/lang/IllegalStateException", "MNN embedding output is unreadable");
            return nullptr;
        }

        jfloatArray result = env->NewFloatArray(dimensions);
        if (result == nullptr) {
            return nullptr;
        }
        env->SetFloatArrayRegion(result, 0, dimensions, values);
        return result;
    } catch (const std::exception& error) {
        throwJava(env, "java/lang/IllegalStateException", error.what());
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown MNN embedding inference failure");
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_alibaba_mnnllm_android_rag_MnnEmbeddingEngine_nativeRelease(
        JNIEnv*,
        jobject,
        jlong nativeHandle) {
    delete reinterpret_cast<RagEmbeddingHandle*>(nativeHandle);
}
