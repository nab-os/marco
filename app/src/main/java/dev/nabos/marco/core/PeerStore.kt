package dev.nabos.marco.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

/** A phone we're paired with. Both phones of a pairing hold the same [key]. */
data class Peer(val name: String, val key: ByteArray) {
    /** Stable, non-secret identifier for this pairing (same on both phones). */
    val id: String
        get() = MessageDigest.getInstance("SHA-256").digest("marco/id/v1".toByteArray() + key)
            .copyOf(8).joinToString("") { "%02x".format(it) }

    /** 6-digit code that is identical on both phones if pairing worked. */
    val safetyCode: String
        get() {
            val h = MessageDigest.getInstance("SHA-256").digest(key)
            val n = ((h[0].toInt() and 0xff) shl 16) or ((h[1].toInt() and 0xff) shl 8) or (h[2].toInt() and 0xff)
            return "%06d".format(n % 1_000_000).let { it.substring(0, 3) + " " + it.substring(3) }
        }

    override fun equals(other: Any?) = other is Peer && other.name == name && other.key.contentEquals(key)
    override fun hashCode() = 31 * name.hashCode() + key.contentHashCode()
}

data class PairingInvite(val key: ByteArray, val inviterName: String)

/**
 * App-private storage for the pairing keys and settings. The keys live in the
 * app's sandboxed SharedPreferences; anyone with root on the phone could read them,
 * which only lets them ring/find your other phones.
 */
class PeerStore private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("marco", Context.MODE_PRIVATE)

    private val _peers = MutableStateFlow(loadPeers())
    /** Paired phones, in pairing order. */
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _findable = MutableStateFlow(prefs.getBoolean(K_FINDABLE, true))
    /** User's wish for this phone to be findable (the service follows it). */
    val findable: StateFlow<Boolean> = _findable.asStateFlow()

    var proximitySound: Boolean
        get() = prefs.getBoolean(K_SOUND, true)
        set(v) = prefs.edit().putBoolean(K_SOUND, v).apply()

    var autoRing: Boolean
        get() = prefs.getBoolean(K_AUTORING, false)
        set(v) = prefs.edit().putBoolean(K_AUTORING, v).apply()

    var deviceName: String
        get() = prefs.getString(K_DEVICE_NAME, null) ?: android.os.Build.MODEL
        set(v) = prefs.edit().putString(K_DEVICE_NAME, v).apply()

    /** Adds [peer], or renames it if this pairing already exists. */
    fun savePeer(peer: Peer) = editPeers { list ->
        val i = list.indexOfFirst { it.id == peer.id }
        if (i < 0) list + peer else list.toMutableList().apply { this[i] = peer }
    }

    fun removePeer(id: String) = editPeers { list -> list.filterNot { it.id == id } }

    private fun editPeers(change: (List<Peer>) -> List<Peer>) {
        _peers.update { old ->
            change(old).also { prefs.edit().putString(K_PEERS, encodePeers(it)).apply() }
        }
    }

    fun setFindable(v: Boolean) {
        prefs.edit().putBoolean(K_FINDABLE, v).apply()
        _findable.value = v
    }

    private fun loadPeers(): List<Peer> {
        prefs.getString(K_PEERS, null)?.let { return decodePeers(it) }
        // Migrate the single pairing stored by v0.1.
        val legacyKey = prefs.getString(K_LEGACY_PEER_KEY, null)
            ?.let { runCatching { unb64(it) }.getOrNull() }
            ?.takeIf { it.size == Protocol.KEY_LEN }
        val peers = listOfNotNull(legacyKey?.let { Peer(prefs.getString(K_LEGACY_PEER_NAME, null) ?: "Other phone", it) })
        prefs.edit()
            .putString(K_PEERS, encodePeers(peers))
            .remove(K_LEGACY_PEER_KEY)
            .remove(K_LEGACY_PEER_NAME)
            .apply()
        return peers
    }

    private fun encodePeers(peers: List<Peer>): String = JSONArray().apply {
        peers.forEach { put(JSONObject().put("name", it.name).put("key", b64(it.key))) }
    }.toString()

    private fun decodePeers(json: String): List<Peer> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val key = runCatching { unb64(o.getString("key")) }.getOrNull()
                ?.takeIf { it.size == Protocol.KEY_LEN } ?: return@mapNotNull null
            Peer(o.optString("name").ifBlank { "Other phone" }, key)
        }.distinctBy { it.id }
    }

    companion object {
        private const val K_PEERS = "peers"
        private const val K_LEGACY_PEER_NAME = "peer_name"
        private const val K_LEGACY_PEER_KEY = "peer_key"
        private const val K_FINDABLE = "findable"
        private const val K_SOUND = "proximity_sound"
        private const val K_AUTORING = "auto_ring"
        private const val K_DEVICE_NAME = "device_name"

        private const val QR_PREFIX = "MARCO1:"

        @Volatile private var instance: PeerStore? = null

        fun get(context: Context): PeerStore =
            instance ?: synchronized(this) {
                instance ?: PeerStore(context.applicationContext).also { instance = it }
            }

        fun encodeInvite(invite: PairingInvite): String =
            QR_PREFIX + b64(invite.key) + ":" + URLEncoder.encode(invite.inviterName, "UTF-8")

        fun decodeInvite(text: String): PairingInvite? {
            if (!text.startsWith(QR_PREFIX)) return null
            val parts = text.removePrefix(QR_PREFIX).split(":", limit = 2)
            if (parts.size != 2) return null
            val key = runCatching { unb64(parts[0]) }.getOrNull() ?: return null
            if (key.size != Protocol.KEY_LEN) return null
            val name = runCatching { URLDecoder.decode(parts[1], "UTF-8") }.getOrDefault("Other phone")
            return PairingInvite(key, name.ifBlank { "Other phone" })
        }

        private const val B64 = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        private fun b64(b: ByteArray) = Base64.encodeToString(b, B64)
        private fun unb64(s: String): ByteArray = Base64.decode(s, B64)
    }
}
