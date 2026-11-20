package com.muffuq.os.airplay
/**
 * FairPlay (SAP) handshake for AirPlay screen mirroring. The two /fp-setup replies are fixed messages; the key
 * sent in SETUP ("ekey") is then unwrapped by the native playfair code (src/main/cpp, from UxPlay, GPL-3.0).
 */
class FairPlay {
    private var keyMessage: ByteArray? = null

    /** /fp-setup phase 1: 16-byte request -> 142-byte reply, chosen by the mode byte. Null = unsupported. */
    fun setup(request: ByteArray): ByteArray? {
        if (request.size != 16 || request[4].toInt() != 0x03) return null
        val mode = request[14].toInt()
        if (mode !in REPLIES.indices) return null
        keyMessage = null
        return REPLIES[mode].copyOf()
    }

    /** /fp-setup phase 2: keep the 164-byte key message, reply with a fixed header + its last 20 bytes. */
    fun handshake(request: ByteArray): ByteArray? {
        if (request.size != 164 || request[4].toInt() != 0x03) return null
        keyMessage = request.copyOf()
        return HEADER + request.copyOfRange(144, 164)
    }

    /** Unwraps the 72-byte "ekey" from SETUP into the 16-byte session AES key. */
    fun decryptKey(encryptedKey: ByteArray): ByteArray? {
        val message = keyMessage ?: return null
        return nativeDecryptKey(message, encryptedKey)
    }

    companion object {
        init {
            System.loadLibrary("muffuq_native")
        }

        @JvmStatic
        private external fun nativeDecryptKey(keyMessage: ByteArray, encryptedKey: ByteArray): ByteArray?

        private val HEADER = hex("46504c590301040000000014")

        private val REPLIES = listOf(
            hex(
                "46504c59030102000000008202000f9f3f9e0a2521dbdf312ab2bfb29e8d232b" +
                "6376a8c818701d22ae93d82737feaf9db4fdf41c2dba9d1f49caaabf6591ac1f" +
                "7bc6f7e0663d21afe01565953eab81f418ceed095adb7c3d0e254909a79831d4" +
                "9c3982973434facb42c63a1cd911a6fe941a8a6d4a743b46c3a7649e44c78955" +
                "e49d8155009549c4e2f7a3f6d5ba",
            ),
            hex(
                "46504c5903010200000000820201cf32a25714b2524f8aa0ad7af164e37bcf44" +
                "24e200047efc0ad67afcd95ded1c2730bb591b962ed63a9c4ded88ba8fc78de6" +
                "4d91ccfd5c7b56da88e31f5cceafc7431995a01665a54e1939d25b94db64b9e4" +
                "5d8d063e1e6af07e9656162b0efa404275ea5a44d9591c7256b9fbe6513898b8" +
                "0227721988571650942ad946688a",
            ),
            hex(
                "46504c5903010200000000820202c169a352eeed35b18cdd9c58d64f16c1519a" +
                "89eb5317bd0d4336cd68f638ff9d016a5b52b7fa9216b2b65482c78444118121" +
                "a2c7fed83db7119e9182aad7d18c7063e2a457555910af9e0efc76347d164043" +
                "807f581ee4fbe42ca9dedc1b5eb2a3aa3d2ecd59e7eee70b3629f22afd161d87" +
                "7353ddb99adc8e07006e56f850ce",
            ),
            hex(
                "46504c59030102000000008202039001e1727e0f57f9f5880db104a6257a23f5" +
                "cfff1abbe1e93045251afb97eb9fc0011ebe0f3a81df5b691d76acb2f7a5c708" +
                "e3d328f56bb39dbde5f29c8a17f481487e3ae863c678325422e6f78e166d18aa" +
                "7fd636258bce28726f661f738893ce44311e4be6c0535193e5ef72e868623372" +
                "9c227d820c999445d89246c8c359",
            )
        )

        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
