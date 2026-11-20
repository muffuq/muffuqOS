#include <jni.h>
#include <pthread.h>
#include "playfair.h"

static pthread_mutex_t playfair_lock = PTHREAD_MUTEX_INITIALIZER;

/*
 * keyMessage: the 164-byte message from the second /fp-setup request
 * encryptedKey: the 72-byte "ekey" from the first SETUP request
 * returns the 16-byte AES key, or null when the inputs have the wrong size
 */
JNIEXPORT jbyteArray JNICALL
Java_com_muffuq_os_airplay_FairPlay_nativeDecryptKey(JNIEnv *env, jclass clazz,
                                                     jbyteArray keyMessage, jbyteArray encryptedKey) {
    if ((*env)->GetArrayLength(env, keyMessage) != 164 || (*env)->GetArrayLength(env, encryptedKey) != 72) {
        return NULL;
    }
    unsigned char message[164];
    unsigned char cipher[72];
    unsigned char key[16];
    (*env)->GetByteArrayRegion(env, keyMessage, 0, 164, (jbyte *) message);
    (*env)->GetByteArrayRegion(env, encryptedKey, 0, 72, (jbyte *) cipher);

    pthread_mutex_lock(&playfair_lock);
    playfair_decrypt(message, cipher, key);
    pthread_mutex_unlock(&playfair_lock);

    jbyteArray result = (*env)->NewByteArray(env, 16);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, 16, (const jbyte *) key);
    return result;
}
