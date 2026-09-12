package ink.underflo.wristbrief.mobile.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ink.underflo.wristbrief.mobile.AccountSessionStorage
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Migration of the account session from the legacy plain SharedPreferences
 * ("account_session") to the Keystore-backed secure store. The legacy token is
 * never deleted before the secure copy is written and verified; a verification
 * failure keeps the legacy value intact.
 */
object SessionStoreMigrations {
    sealed interface Outcome {
        data object Moved : Outcome
        data object NoLegacySession : Outcome
        data object SecureAlreadyPopulated : Outcome
        data object VerificationFailed : Outcome
    }

    fun migrate(legacy: AccountSessionStorage, secure: AccountSessionStorage): Outcome {
        if (secure.getString("token") != null) return Outcome.SecureAlreadyPopulated
        val token = legacy.getString("token") ?: return Outcome.NoLegacySession
        val expiresAt = legacy.getString("expires_at") ?: return Outcome.NoLegacySession
        val userId = legacy.getString("user_id") ?: return Outcome.NoLegacySession

        secure.put(token, expiresAt, userId)
        val verified = secure.getString("token") == token &&
            secure.getString("expires_at") == expiresAt &&
            secure.getString("user_id") == userId
        if (!verified) return Outcome.VerificationFailed
        legacy.clear()
        return Outcome.Moved
    }
}

/** The pre-secure storage: plain SharedPreferences under "account_session". */
class LegacySharedPreferencesAccountSessionStorage(context: Context) : AccountSessionStorage {
    private val preferences = context.getSharedPreferences("account_session", Context.MODE_PRIVATE)
    override fun getString(key: String): String? = preferences.getString(key, null)
    override fun put(token: String, expiresAt: String, userId: String) {
        preferences.edit()
            .putString("token", token)
            .putString("expires_at", expiresAt)
            .putString("user_id", userId)
            .apply()
    }
    override fun clear() {
        preferences.edit().clear().apply()
    }
}

/**
 * Android Keystore-backed session storage. Each field is AES-256-GCM encrypted
 * with a non-exportable hardware key; only ciphertext blobs touch the filesystem.
 * Undecryptable blobs (e.g. after a key invalidation) are dropped rather than
 * crashing, which simply signs the user out.
 */
class KeystoreAccountSessionStorage(
    context: Context,
    private val keyAlias: String = "wristbrief_account_session",
) : AccountSessionStorage {
    private val preferences = context.getSharedPreferences("account_session_secure", Context.MODE_PRIVATE)

    override fun getString(key: String): String? {
        val blob = preferences.getString(key, null) ?: return null
        val plain = runCatching { decrypt(blob) }.getOrNull()
        if (plain == null) {
            preferences.edit().remove(key).apply()
            return null
        }
        return plain
    }

    override fun put(token: String, expiresAt: String, userId: String) {
        val editor = preferences.edit()
        for ((key, value) in listOf("token" to token, "expires_at" to expiresAt, "user_id" to userId)) {
            editor.putString(key, encrypt(value))
        }
        editor.apply()
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val separator = blob.indexOf(':')
        if (separator <= 0) throw IllegalArgumentException("malformed blob")
        val iv = Base64.decode(blob.substring(0, separator), Base64.NO_WRAP)
        val data = Base64.decode(blob.substring(separator + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}

/**
 * Storage facade that reads/writes the secure store and transparently migrates
 * a legacy plain-text session on first access. All call sites keep using
 * [ink.underflo.wristbrief.mobile.AccountSessionPreferences] unchanged.
 */
class MigratingAccountSessionStorage(
    private val legacy: AccountSessionStorage,
    private val secure: AccountSessionStorage,
) : AccountSessionStorage {
    constructor(context: Context) : this(
        LegacySharedPreferencesAccountSessionStorage(context),
        KeystoreAccountSessionStorage(context)
    )

    private var migrationChecked = false

    override fun getString(key: String): String? {
        if (!migrationChecked) {
            migrationChecked = true
            // Validation of token format/expiry happens in AccountSessionPreferences.read();
            // the migration only moves values, and only after verifying the copy.
            runCatching { SessionStoreMigrations.migrate(legacy, secure) }
        }
        return secure.getString(key) ?: legacy.getString(key)
    }

    override fun put(token: String, expiresAt: String, userId: String) {
        secure.put(token, expiresAt, userId)
        if (secure.getString("token") == token) legacy.clear()
    }

    override fun clear() {
        secure.clear()
        legacy.clear()
    }
}
