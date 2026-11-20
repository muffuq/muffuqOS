#include <jni.h>
#include <vector>
#include "ALACDecoder.h"
#include "ALACBitUtilities.h"

/* One decoder per AirPlay audio stream; the handle is a pointer kept on the Kotlin side. */

extern "C" JNIEXPORT jlong JNICALL
Java_com_muffuq_os_airplay_AlacDecoder_nativeCreate(JNIEnv *env, jclass, jbyteArray cookie) {
    jsize size = env->GetArrayLength(cookie);
    std::vector<uint8_t> config(size);
    env->GetByteArrayRegion(cookie, 0, size, reinterpret_cast<jbyte *>(config.data()));
    auto *decoder = new ALACDecoder();
    if (decoder->Init(config.data(), static_cast<uint32_t>(size)) != 0) {
        delete decoder;
        return 0;
    }
    return reinterpret_cast<jlong>(decoder);
}

/* Decodes one ALAC frame into interleaved native-endian 16-bit PCM. Returns the byte count, or -1. */
extern "C" JNIEXPORT jint JNICALL
Java_com_muffuq_os_airplay_AlacDecoder_nativeDecode(JNIEnv *env, jclass, jlong handle, jbyteArray frame,
                                                    jint length, jbyteArray pcm) {
    auto *decoder = reinterpret_cast<ALACDecoder *>(handle);
    if (!decoder) return -1;
    const uint32_t channels = decoder->mConfig.numChannels;
    const uint32_t samples = decoder->mConfig.frameLength;
    if (env->GetArrayLength(pcm) < static_cast<jsize>(samples * channels * 2)) return -1;

    // The bit reader may look a few bytes past the end of the frame.
    std::vector<uint8_t> input(static_cast<size_t>(length) + 16, 0);
    env->GetByteArrayRegion(frame, 0, length, reinterpret_cast<jbyte *>(input.data()));
    BitBuffer bits;
    BitBufferInit(&bits, input.data(), static_cast<uint32_t>(length));

    std::vector<uint8_t> output(samples * channels * 2);
    uint32_t decoded = 0;
    if (decoder->Decode(&bits, output.data(), samples, channels, &decoded) != 0) return -1;
    const jint bytes = static_cast<jint>(decoded * channels * 2);
    env->SetByteArrayRegion(pcm, 0, bytes, reinterpret_cast<const jbyte *>(output.data()));
    return bytes;
}

extern "C" JNIEXPORT void JNICALL
Java_com_muffuq_os_airplay_AlacDecoder_nativeDestroy(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<ALACDecoder *>(handle);
}
