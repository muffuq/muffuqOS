package com.muffuq.os.airplay

import android.content.Context
import androidx.core.content.edit
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The receiver's persistent identity: a random locally-administered "MAC" used as AirPlay device id, a long-term
 * Ed25519 key (advertised as "pk"), and a pairing id. Persisting them keeps iPhones from seeing a new TV each boot.
 */
class DeviceIdentity(context: Context) {
    val deviceId: ByteArray
    val pairingId: String
    private val signingKey: Ed25519PrivateKeyParameters

    init {
        val prefs = context.getSharedPreferences("airplay_identity", Context.MODE_PRIVATE)
        val random = SecureRandom()
        val storedId = prefs.getString("device_id", null)?.hexToBytes()
        val storedKey = prefs.getString("ed25519", null)?.hexToBytes()
        deviceId = storedId ?: ByteArray(6).also {
            random.nextBytes(it)
            it[0] = ((it[0].toInt() and 0xFC) or 0x02).toByte() // locally administered, unicast
        }
        signingKey = Ed25519PrivateKeyParameters(storedKey ?: ByteArray(32).also(random::nextBytes), 0)
        pairingId = prefs.getString("pairing_id", null) ?: UUID.randomUUID().toString()
        if (storedId == null || storedKey == null || !prefs.contains("pairing_id")) {
            prefs.edit {
                putString("device_id", deviceId.toHex())
                putString("ed25519", signingKey.encoded.toHex())
                putString("pairing_id", pairingId)
            }
        }
    }

    val publicKey: ByteArray get() = signingKey.generatePublicKey().encoded

    /** "aa:bb:cc:dd:ee:ff" — the _airplay._tcp deviceid / /info deviceID form. */
    val deviceIdColons: String get() = deviceId.joinToString(":") { "%02x".format(it) }

    /** "AABBCCDDEEFF" — the prefix of the _raop._tcp service name. */
    val deviceIdPlain: String get() = deviceId.joinToString("") { "%02X".format(it) }

    fun sign(message: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, signingKey)
        update(message, 0, message.size)
        generateSignature()
    }
}

/**
 * Legacy AirPlay pairing (POST /pair-setup + /pair-verify). Only used when the client asks for it; with features
 * bit 27 off (our default, same as UxPlay) iOS skips it and connects ~5 s faster.
 */
class PairingSession(private val identity: DeviceIdentity) {
    /** X25519 shared secret, available once pair-verify step 1 has run. */
    var sharedSecret: ByteArray? = null
        private set
    private var ourEphemeral: X25519PrivateKeyParameters? = null
    private var theirEphemeral: ByteArray? = null
    private var theirSigningKey: ByteArray? = null

    fun setup(request: ByteArray): ByteArray? = if (request.size == 32) identity.publicKey else null

    /** Step 1: returns our ephemeral X25519 key + our signature, encrypted. Step 2: verifies theirs. */
    fun verify(request: ByteArray): VerifyResult {
        if (request.size < 4) return VerifyResult.Invalid
        return when (request[0].toInt()) {
            1 -> {
                if (request.size != 4 + 32 + 32) return VerifyResult.Invalid
                val theirX = request.copyOfRange(4, 36)
                theirEphemeral = theirX
                theirSigningKey = request.copyOfRange(36, 68)
                val ours = X25519PrivateKeyParameters(SecureRandom())
                ourEphemeral = ours
                val ourX = ours.generatePublicKey().encoded
                val secret = ByteArray(32)
                X25519Agreement().apply { init(ours) }.calculateAgreement(X25519PublicKeyParameters(theirX, 0), secret, 0)
                sharedSecret = secret

                val signature = identity.sign(ourX + theirX)
                VerifyResult.Reply(ourX + verifyCipher(secret).update(signature))
            }
            0 -> {
                val secret = sharedSecret ?: return VerifyResult.Invalid
                if (request.size != 4 + 64) return VerifyResult.Invalid
                // Same keystream as step 1, which used up the first 64 bytes.
                val cipher = verifyCipher(secret)
                cipher.update(ByteArray(64))
                val signature = cipher.update(request.copyOfRange(4, 68))
                val message = theirEphemeral!! + ourEphemeral!!.generatePublicKey().encoded
                val ok = Ed25519Signer().run {
                    init(false, Ed25519PublicKeyParameters(theirSigningKey!!, 0))
                    update(message, 0, message.size)
                    verifySignature(signature)
                }
                if (ok) VerifyResult.Reply(ByteArray(0)) else VerifyResult.Rejected
            }
            else -> VerifyResult.Invalid
        }
    }

    private fun verifyCipher(secret: ByteArray): Cipher {
        val key = sha512("Pair-Verify-AES-Key".toByteArray(), secret).copyOf(16)
        val iv = sha512("Pair-Verify-AES-IV".toByteArray(), secret).copyOf(16)
        return Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
    }

    sealed interface VerifyResult {
        class Reply(val body: ByteArray) : VerifyResult
        data object Rejected : VerifyResult
        data object Invalid : VerifyResult
    }
}

internal fun sha512(vararg parts: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-512").run {
        parts.forEach(::update)
        digest()
    }

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
