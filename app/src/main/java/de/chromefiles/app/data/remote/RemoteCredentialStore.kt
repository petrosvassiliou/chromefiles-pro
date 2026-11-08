package de.chromefiles.app.data.remote

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Speicher für bekannte SSH-Host-Key-Fingerabdrücke („Trust on first use“). */
interface HostKeyStore {
    fun fingerprintFor(hostId: String): String?
    fun storeFingerprint(hostId: String, fingerprint: String)
}

/** Zusätzliche Verbindungsparameter, die nicht in der Room-Tabelle liegen. */
data class RemoteConnectionExtras(
    val port: Int,
    val domain: String,
    val useHttps: Boolean
)

/**
 * Speichert Passwörter verschlüsselt (AES-256-GCM, Schlüssel im Android Keystore, nicht exportierbar)
 * sowie Verbindungs-Extras und SSH-Host-Keys in privaten SharedPreferences.
 */
@Singleton
class RemoteCredentialStore @Inject constructor(
    @ApplicationContext context: Context
) : HostKeyStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun savePassword(volumeId: String, password: String) {
        saveEncrypted(KEY_PASSWORD + volumeId, password)
    }

    /** Liefert null, wenn kein Passwort gespeichert ist oder es nicht mehr entschlüsselt werden kann. */
    @Synchronized
    fun loadPassword(volumeId: String): String? = loadEncrypted(KEY_PASSWORD + volumeId)

    @Synchronized
    fun savePrivateKey(volumeId: String, privateKeyPem: String, passphrase: String) {
        if (privateKeyPem.isNotBlank()) {
            saveEncrypted(KEY_PRIVKEY + volumeId, privateKeyPem)
            saveEncrypted(KEY_PASSPHRASE + volumeId, passphrase)
        } else {
            prefs.edit()
                .remove(KEY_PRIVKEY + volumeId)
                .remove(KEY_PASSPHRASE + volumeId)
                .apply()
        }
    }

    @Synchronized
    fun loadPrivateKey(volumeId: String): String = loadEncrypted(KEY_PRIVKEY + volumeId).orEmpty()

    @Synchronized
    fun loadPassphrase(volumeId: String): String = loadEncrypted(KEY_PASSPHRASE + volumeId).orEmpty()

    private fun saveEncrypted(prefKey: String, secret: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(prefKey, payload).apply()
    }

    private fun loadEncrypted(prefKey: String): String? {
        val payload = prefs.getString(prefKey, null) ?: return null
        val parts = payload.split(':')
        if (parts.size != 2) return null
        return runCatching {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val data = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.getOrNull()
    }

    fun saveExtras(volumeId: String, extras: RemoteConnectionExtras) {
        prefs.edit()
            .putInt(KEY_PORT + volumeId, extras.port)
            .putString(KEY_DOMAIN + volumeId, extras.domain)
            .putBoolean(KEY_HTTPS + volumeId, extras.useHttps)
            .apply()
    }

    fun loadExtras(volumeId: String): RemoteConnectionExtras? {
        if (!prefs.contains(KEY_PORT + volumeId)) return null
        return RemoteConnectionExtras(
            port = prefs.getInt(KEY_PORT + volumeId, 0),
            domain = prefs.getString(KEY_DOMAIN + volumeId, "").orEmpty(),
            useHttps = prefs.getBoolean(KEY_HTTPS + volumeId, true)
        )
    }

    fun remove(volumeId: String) {
        prefs.edit()
            .remove(KEY_PASSWORD + volumeId)
            .remove(KEY_PRIVKEY + volumeId)
            .remove(KEY_PASSPHRASE + volumeId)
            .remove(KEY_PORT + volumeId)
            .remove(KEY_DOMAIN + volumeId)
            .remove(KEY_HTTPS + volumeId)
            .apply()
    }

    override fun fingerprintFor(hostId: String): String? = prefs.getString(KEY_HOSTKEY + hostId, null)

    override fun storeFingerprint(hostId: String, fingerprint: String) {
        prefs.edit().putString(KEY_HOSTKEY + hostId, fingerprint).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "remote_credentials"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "chromefiles_remote_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val KEY_PASSWORD = "pw_"
        const val KEY_PRIVKEY = "pk_"
        const val KEY_PASSPHRASE = "pp_"
        const val KEY_PORT = "port_"
        const val KEY_DOMAIN = "domain_"
        const val KEY_HTTPS = "https_"
        const val KEY_HOSTKEY = "hostkey_"
    }
}
