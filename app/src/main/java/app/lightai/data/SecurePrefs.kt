package app.lightai.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted prefs for OpenClaw gateway setup code + auth tokens.
 * Backed by AndroidX Security + Android Keystore.
 */
class SecurePrefs private constructor(context: Context) {
    private val prefs: SharedPreferences = run {
        val masterKey =
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            FILENAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Raw OpenClaw setup code (URL-safe Base64 JSON). */
    var gatewaySetupCode: String?
        get() = prefs.getString(KEY_SETUP_CODE, null)
        set(value) = prefs.edit().putString(KEY_SETUP_CODE, value).apply()

    val gatewayConnectConfig: GatewayConnectConfig?
        get() = gatewaySetupCode?.let { GatewaySetupCodeDecoder.resolveConnectConfig(it) }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val FILENAME = "lightai_secure"
        private const val KEY_SETUP_CODE = "gateway_setup_code"

        @Volatile private var instance: SecurePrefs? = null

        fun getInstance(context: Context): SecurePrefs =
            instance ?: synchronized(this) {
                instance ?: SecurePrefs(context.applicationContext).also { instance = it }
            }
    }
}
