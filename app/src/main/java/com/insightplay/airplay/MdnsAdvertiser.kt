package com.insightplay.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Handles mDNS/Bonjour advertisement for AirPlay and RAOP services.
 * Uses Android NsdManager as primary, falls back to jmDNS if needed.
 */
class MdnsAdvertiser(private val context: Context) {

    companion object {
        private const val TAG = "MdnsAdvertiser"

        // AirPlay service types
        const val AIRPLAY_SERVICE_TYPE = "_airplay._tcp."
        const val RAOP_SERVICE_TYPE = "_raop._tcp."

        // Default ports
        const val AIRPLAY_PORT = 7000
        const val RAOP_PORT = 5000

        // AirPlay feature flags (basic mirroring + video + audio)
        private const val FEATURES = "0x5A7FFFF7,0x1E"
        private const val MODEL = "AppleTV3,2"
        private const val PROTOCOL_VERSION = "1.1"
        private const val SOURCE_VERSION = "220.68"
    }

    private var nsdManager: NsdManager? = null
    private var wifiManager: WifiManager? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // jmDNS fallback
    private var jmDns: JmDNS? = null
    private var jmDnsAirPlay: ServiceInfo? = null
    private var jmDnsRaop: ServiceInfo? = null

    // NSD registration listeners
    private var airplayRegistrationListener: NsdManager.RegistrationListener? = null
    private var raopRegistrationListener: NsdManager.RegistrationListener? = null

    private var isRegistered = false
    private var deviceName = "InsightPlay"
    private var deviceMac = "AA:BB:CC:DD:EE:FF"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun setDeviceName(name: String) {
        deviceName = name
    }

    fun setDeviceMac(mac: String) {
        deviceMac = mac.replace(":", "")
    }

    /**
     * Start advertising AirPlay services via mDNS.
     */
    fun startAdvertising() {
        if (isRegistered) {
            Log.w(TAG, "Already advertising")
            return
        }

        acquireMulticastLock()

        scope.launch {
            try {
                registerWithNsd()
            } catch (e: Exception) {
                Log.w(TAG, "NSD registration failed, falling back to jmDNS", e)
                try {
                    registerWithJmDns()
                } catch (e2: Exception) {
                    Log.e(TAG, "jmDNS fallback also failed", e2)
                }
            }
        }
    }

    /**
     * Stop advertising all services.
     */
    fun stopAdvertising() {
        scope.launch {
            unregisterNsd()
            unregisterJmDns()
            releaseMulticastLock()
            isRegistered = false
            Log.i(TAG, "All mDNS advertisements stopped")
        }
    }

    // ─── NSD Manager (Primary) ────────────────────────────────────────

    private fun registerWithNsd() {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        // Register _airplay._tcp
        val airplayInfo = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = AIRPLAY_SERVICE_TYPE
            port = AIRPLAY_PORT
            setAttribute("deviceid", formatMacAddress(deviceMac))
            setAttribute("features", FEATURES)
            setAttribute("model", MODEL)
            setAttribute("protovers", PROTOCOL_VERSION)
            setAttribute("srcvers", SOURCE_VERSION)
            setAttribute("flags", "0x4")
            setAttribute("vv", "2")
            setAttribute("pk", generatePublicKey())
            setAttribute("pi", generatePairingId())
        }

        airplayRegistrationListener = createRegistrationListener("AirPlay")
        nsdManager?.registerService(airplayInfo, NsdManager.PROTOCOL_DNS_SD, airplayRegistrationListener)

        // Register _raop._tcp
        val raopServiceName = "${deviceMac}@${deviceName}"
        val raopInfo = NsdServiceInfo().apply {
            serviceName = raopServiceName
            serviceType = RAOP_SERVICE_TYPE
            port = RAOP_PORT
            setAttribute("ch", "2")
            setAttribute("cn", "0,1,2,3")   // PCM, ALAC, AAC, AAC-ELD
            setAttribute("da", "true")
            setAttribute("et", "0,3,5")      // None, Fairplay, Fairplay SAPv2.5
            setAttribute("ft", FEATURES)
            setAttribute("md", "0,1,2")       // Text, Artwork, Progress
            setAttribute("am", MODEL)
            setAttribute("pk", generatePublicKey())
            setAttribute("sf", "0x4")
            setAttribute("tp", "UDP")
            setAttribute("vn", "65537")
            setAttribute("vs", SOURCE_VERSION)
            setAttribute("vv", "2")
            setAttribute("ss", "16")          // Sample size
            setAttribute("sr", "44100")       // Sample rate
        }

        raopRegistrationListener = createRegistrationListener("RAOP")
        nsdManager?.registerService(raopInfo, NsdManager.PROTOCOL_DNS_SD, raopRegistrationListener)

        isRegistered = true
        Log.i(TAG, "NSD registration started for $deviceName")
    }

    private fun unregisterNsd() {
        try {
            airplayRegistrationListener?.let { nsdManager?.unregisterService(it) }
            raopRegistrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering NSD services", e)
        }
        airplayRegistrationListener = null
        raopRegistrationListener = null
    }

    private fun createRegistrationListener(serviceLabel: String): NsdManager.RegistrationListener {
        return object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "$serviceLabel service registered: ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "$serviceLabel registration failed: error $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "$serviceLabel service unregistered")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "$serviceLabel unregistration failed: error $errorCode")
            }
        }
    }

    // ─── jmDNS Fallback ───────────────────────────────────────────────

    private fun registerWithJmDns() {
        val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiMgr.connectionInfo
        val ipInt = wifiInfo.ipAddress
        val ipBytes = byteArrayOf(
            (ipInt and 0xFF).toByte(),
            (ipInt shr 8 and 0xFF).toByte(),
            (ipInt shr 16 and 0xFF).toByte(),
            (ipInt shr 24 and 0xFF).toByte()
        )
        val address = InetAddress.getByAddress(ipBytes)

        jmDns = JmDNS.create(address, deviceName)

        // AirPlay service
        val airplayProps = mapOf(
            "deviceid" to formatMacAddress(deviceMac),
            "features" to FEATURES,
            "model" to MODEL,
            "protovers" to PROTOCOL_VERSION,
            "srcvers" to SOURCE_VERSION,
            "flags" to "0x4",
            "vv" to "2",
            "pk" to generatePublicKey(),
            "pi" to generatePairingId()
        )

        jmDnsAirPlay = ServiceInfo.create(
            AIRPLAY_SERVICE_TYPE,
            deviceName,
            AIRPLAY_PORT,
            0, 0,
            airplayProps
        )
        jmDns?.registerService(jmDnsAirPlay)

        // RAOP service
        val raopProps = mapOf(
            "ch" to "2",
            "cn" to "0,1,2,3",
            "da" to "true",
            "et" to "0,3,5",
            "ft" to FEATURES,
            "md" to "0,1,2",
            "am" to MODEL,
            "pk" to generatePublicKey(),
            "sf" to "0x4",
            "tp" to "UDP",
            "vn" to "65537",
            "vs" to SOURCE_VERSION,
            "vv" to "2",
            "ss" to "16",
            "sr" to "44100"
        )

        val raopName = "${deviceMac}@${deviceName}"
        jmDnsRaop = ServiceInfo.create(
            RAOP_SERVICE_TYPE,
            raopName,
            RAOP_PORT,
            0, 0,
            raopProps
        )
        jmDns?.registerService(jmDnsRaop)

        isRegistered = true
        Log.i(TAG, "jmDNS registration completed for $deviceName")
    }

    private fun unregisterJmDns() {
        try {
            jmDns?.unregisterAllServices()
            jmDns?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing jmDNS", e)
        }
        jmDns = null
    }

    // ─── Utilities ────────────────────────────────────────────────────

    private fun acquireMulticastLock() {
        wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager?.createMulticastLock("insightplay_mdns")?.apply {
            setReferenceCounted(true)
            acquire()
        }
        Log.d(TAG, "Multicast lock acquired")
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.d(TAG, "Multicast lock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing multicast lock", e)
        }
    }

    private fun formatMacAddress(mac: String): String {
        val clean = mac.replace(":", "").replace("-", "").uppercase()
        return clean.chunked(2).joinToString(":")
    }

    private fun generatePublicKey(): String {
        // Generate a random 32-byte public key (hex encoded)
        // In production, this would be a proper Ed25519 public key
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun generatePairingId(): String {
        // Generate a UUID-like pairing identifier
        return java.util.UUID.randomUUID().toString()
    }

    fun isAdvertising(): Boolean = isRegistered

    fun destroy() {
        stopAdvertising()
        scope.cancel()
    }
}
