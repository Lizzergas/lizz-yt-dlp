#include <jni.h>
#include <stdexcept>
#include <vector>

#include "config.h"
#include "lame.h"

namespace {

jlong throwIllegalState(JNIEnv* env, const char* message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
    return 0;
}

lame_t asLame(jlong handle) {
    return reinterpret_cast<lame_t>(handle);
}

}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_lizz_ytdl_androidmedia_LameEncoderBridge_nativeInit(
    JNIEnv* env,
    jobject /* thiz */,
    jint sampleRate,
    jint channelCount,
    jint bitrateKbps
) {
    lame_t lame = lame_init();
    if (lame == nullptr) {
        return throwIllegalState(env, "Failed to initialize LAME encoder");
    }

    lame_set_in_samplerate(lame, sampleRate);
    lame_set_num_channels(lame, channelCount);
    lame_set_VBR(lame, vbr_default);
    lame_set_brate(lame, bitrateKbps);
    lame_set_quality(lame, 2);

    if (lame_init_params(lame) < 0) {
        lame_close(lame);
        return throwIllegalState(env, "Failed to configure LAME encoder");
    }

    return reinterpret_cast<jlong>(lame);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_lizz_ytdl_androidmedia_LameEncoderBridge_nativeEncodeInterleaved(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jshortArray samples,
    jint samplesPerChannel,
    jbyteArray outputBuffer
) {
    lame_t lame = asLame(handle);
    if (lame == nullptr) {
        throwIllegalState(env, "LAME encoder handle is null");
        return -1;
    }

    const jsize sampleCount = env->GetArrayLength(samples);
    const jsize outputSize = env->GetArrayLength(outputBuffer);

    jshort* samplePtr = env->GetShortArrayElements(samples, nullptr);
    jbyte* outputPtr = env->GetByteArrayElements(outputBuffer, nullptr);

    int encoded = lame_encode_buffer_interleaved(
        lame,
        reinterpret_cast<short int*>(samplePtr),
        samplesPerChannel,
        reinterpret_cast<unsigned char*>(outputPtr),
        outputSize
    );

    env->ReleaseShortArrayElements(samples, samplePtr, JNI_ABORT);
    env->ReleaseByteArrayElements(outputBuffer, outputPtr, 0);

    return encoded;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_lizz_ytdl_androidmedia_LameEncoderBridge_nativeFlush(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jbyteArray outputBuffer
) {
    lame_t lame = asLame(handle);
    if (lame == nullptr) {
        throwIllegalState(env, "LAME encoder handle is null");
        return -1;
    }

    const jsize outputSize = env->GetArrayLength(outputBuffer);
    jbyte* outputPtr = env->GetByteArrayElements(outputBuffer, nullptr);
    int flushed = lame_encode_flush(
        lame,
        reinterpret_cast<unsigned char*>(outputPtr),
        outputSize
    );
    env->ReleaseByteArrayElements(outputBuffer, outputPtr, 0);
    return flushed;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_lizz_ytdl_androidmedia_LameEncoderBridge_nativeClose(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle
) {
    lame_t lame = asLame(handle);
    if (lame != nullptr) {
        lame_close(lame);
    }
}
