package app.lightai.data

import android.content.Context
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Per-device Ed25519 identity used for OpenClaw gateway connect signatures.
 * Ported from OpenClaw's `apps/android/.../DeviceIdentityStore.kt` (MIT).
 *
 * The deviceId is the SHA-256 hex of the raw 32-byte public key, so it's
 * derivable and stable across reinstalls if we keep the private key.
 */
@Serializable
data class DeviceIdentity(
    val deviceId: String,
    val publicKeyRawBase64: String,
    val privateKeyPkcs8Base64: String,
    val createdAtMs: Long,
)

class DeviceIdentityStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val identityFile = File(context.filesDir, "lightai/identity/device.json")

    @Volatile
    private var cached: DeviceIdentity? = null

    @Synchronized
    fun loadOrCreate(): DeviceIdentity {
        cached?.let { return it }
        load()?.let {
            cached = it
            return it
        }
        val fresh = generate()
        save(fresh)
        cached = fresh
        return fresh
    }

    /**
     * Sign a UTF-8 payload with the identity's Ed25519 private key.
     * Returns the base64-url encoded 64-byte signature (no padding).
     */
    fun signPayload(
        payload: String,
        identity: DeviceIdentity,
    ): String? =
        try {
            // BC lightweight API — JCA provider registration is fragile under R8.
            val privateKeyBytes = Base64.decode(identity.privateKeyPkcs8Base64, Base64.DEFAULT)
            val pkInfo = org.bouncycastle.asn1.pkcs.PrivateKeyInfo.getInstance(privateKeyBytes)
            val parsed = pkInfo.parsePrivateKey()
            val rawPrivate = org.bouncycastle.asn1.DEROctetString.getInstance(parsed).octets
            val privateKey =
                org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(rawPrivate, 0)
            val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
            signer.init(true, privateKey)
            val bytes = payload.toByteArray(Charsets.UTF_8)
            signer.update(bytes, 0, bytes.size)
            base64UrlEncode(signer.generateSignature())
        } catch (e: Throwable) {
            android.util.Log.e("LightAI-DeviceAuth", "signPayload failed: ${e.message}", e)
            null
        }

    /** Re-encode the stored raw public key as base64-url (no padding) for the wire. */
    fun publicKeyBase64Url(identity: DeviceIdentity): String? =
        try {
            val raw = Base64.decode(identity.publicKeyRawBase64, Base64.DEFAULT)
            base64UrlEncode(raw)
        } catch (_: Throwable) {
            null
        }

    fun clear() {
        cached = null
        try {
            identityFile.delete()
        } catch (_: Throwable) {
        }
    }

    private fun load(): DeviceIdentity? =
        try {
            if (!identityFile.exists()) {
                null
            } else {
                val raw = identityFile.readText(Charsets.UTF_8)
                val decoded = json.decodeFromString(DeviceIdentity.serializer(), raw)
                if (decoded.deviceId.isBlank() ||
                    decoded.publicKeyRawBase64.isBlank() ||
                    decoded.privateKeyPkcs8Base64.isBlank()
                ) {
                    null
                } else {
                    decoded
                }
            }
        } catch (_: Throwable) {
            null
        }

    private fun save(identity: DeviceIdentity) {
        try {
            identityFile.parentFile?.mkdirs()
            val encoded = json.encodeToString(DeviceIdentity.serializer(), identity)
            identityFile.writeText(encoded, Charsets.UTF_8)
        } catch (e: Throwable) {
            android.util.Log.w("LightAI-DeviceAuth", "save failed: ${e.message}")
        }
    }

    private fun generate(): DeviceIdentity {
        val kpGen = org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator()
        kpGen.init(
            org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters(java.security.SecureRandom()),
        )
        val kp = kpGen.generateKeyPair()
        val pubKey = kp.public as org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
        val privKey = kp.private as org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
        val rawPublic = pubKey.encoded // 32 bytes
        val deviceId = sha256Hex(rawPublic)
        val privKeyInfo =
            org.bouncycastle.crypto.util.PrivateKeyInfoFactory.createPrivateKeyInfo(privKey)
        val pkcs8 = privKeyInfo.encoded
        return DeviceIdentity(
            deviceId = deviceId,
            publicKeyRawBase64 = Base64.encodeToString(rawPublic, Base64.NO_WRAP),
            privateKeyPkcs8Base64 = Base64.encodeToString(pkcs8, Base64.NO_WRAP),
            createdAtMs = System.currentTimeMillis(),
        )
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private fun base64UrlEncode(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()

        @Volatile
        private var INSTANCE: DeviceIdentityStore? = null

        fun getInstance(context: Context): DeviceIdentityStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: DeviceIdentityStore(context.applicationContext).also { INSTANCE = it }
            }
    }
}
