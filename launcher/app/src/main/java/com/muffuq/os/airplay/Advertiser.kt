package com.muffuq.os.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Bonjour advertisement that makes the TV show up in the iPhone's Screen Mirroring list: an _airplay._tcp service
 * plus the _raop._tcp (audio) service named "<device id>@<name>". Values follow UxPlay's defaults.
 */
class Advertiser(context: Context, private val identity: DeviceIdentity, private val name: String, private val port: Int) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val listeners = mutableListOf<NsdManager.RegistrationListener>()

    fun register() {
        register("_airplay._tcp", name, airPlayTxt(identity))
        register("_raop._tcp", "${identity.deviceIdPlain}@$name", raopTxt(identity))
    }

    fun unregister() {
        listeners.forEach { runCatching { nsd.unregisterService(it) } }
        listeners.clear()
    }

    private fun register(type: String, serviceName: String, txt: List<Pair<String, String>>) {
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = type
            port = this@Advertiser.port
            txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "advertising ${info.serviceName} ($type)")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                Log.e(TAG, "could not advertise $type: $error")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        listeners += listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    companion object {
        private const val TAG = "MuffuqAirPlay"
        const val MODEL = "AppleTV3,2"
        const val SOURCE_VERSION = "220.68"

        /**
         * UxPlay's default feature set: mirroring, audio, FairPlay; bit 27 ("supports legacy pairing") off,
         * which lets iOS skip pairing entirely. No HLS (bits 0/4) and no H.265 (bit 42).
         */
        const val FEATURES = 0x527FFEE6L
        private const val FEATURES_TXT = "0x527FFEE6,0x0"

        fun airPlayTxt(identity: DeviceIdentity) = listOf(
            "deviceid" to identity.deviceIdColons,
            "features" to FEATURES_TXT,
            "pw" to "false",
            "flags" to "0x4",
            "model" to MODEL,
            "pk" to identity.publicKey.toHex(),
            "pi" to identity.pairingId,
            "srcvers" to SOURCE_VERSION,
            "vv" to "2",
        )

        fun raopTxt(identity: DeviceIdentity) = listOf(
            "ch" to "2",
            "cn" to "0,1,2,3",
            "da" to "true",
            "et" to "0,3,5",
            "vv" to "2",
            "ft" to FEATURES_TXT,
            "am" to MODEL,
            "md" to "0,1,2",
            "rhd" to "5.6.0.0",
            "pw" to "false",
            "sf" to "0x4",
            "sr" to "44100",
            "ss" to "16",
            "sv" to "false",
            "tp" to "UDP",
            "txtvers" to "1",
            "vs" to SOURCE_VERSION,
            "vn" to "65537",
            "pk" to identity.publicKey.toHex(),
        )

        /** DNS TXT wire format (length-prefixed "key=value" strings), as returned by /info qualifiers. */
        fun txtRecord(entries: List<Pair<String, String>>): ByteArray {
            val out = ByteArrayOutputStream()
            for ((k, v) in entries) {
                val bytes = "$k=$v".toByteArray()
                out.write(bytes.size)
                out.write(bytes)
            }
            return out.toByteArray()
        }
    }
}
