package ru.vlad.triliumnotes.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionStorageException(cause: Throwable) : IOException("Unable to access encrypted session", cause)

internal interface SessionCipher {
    fun encrypt(clear: ByteArray): ByteArray
    fun decrypt(encrypted: ByteArray): ByteArray
}

/** Android's non-exportable per-installation key protects the entire persisted session. */
internal class KeystoreSessionCipher : SessionCipher {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    override fun encrypt(clear: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return byteArrayOf(1, cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(clear)
    }

    override fun decrypt(encrypted: ByteArray): ByteArray {
        require(encrypted.size > 14 && encrypted[0] == 1.toByte())
        val ivSize = encrypted[1].toInt() and 255
        require(ivSize == 12 && encrypted.size > ivSize + 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(2, ivSize + 2)))
        return cipher.doFinal(encrypted.copyOfRange(ivSize + 2, encrypted.size))
    }

    private companion object { const val ALIAS = "trilium.session.v1" }
}

class SessionStore internal constructor(context: Context, private val cipher: SessionCipher) {
    constructor(context: Context) : this(context, KeystoreSessionCipher())
    private val file = AtomicFile(File(context.filesDir, "session.encrypted"))

    fun load(): TriliumSession? = synchronized(LOCK) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized null
        try {
            val data = JSONObject(cipher.decrypt(file.readFully()).toString(Charsets.UTF_8))
            val server = normalizeServerUrl(data.getString("serverUrl"), allowInsecureHttp = true)
            val token = data.getString("token")
            require(token.isNotBlank())
            TriliumSession(data.getString("id"), accountIdFor(server), server, token, data.getBoolean("passwordLogin"))
        } catch (error: Exception) { throw SessionStorageException(error) }
    }

    fun save(session: TriliumSession) = synchronized(LOCK) {
        try {
            require(session.serverUrl == normalizeServerUrl(session.serverUrl, allowInsecureHttp = true))
            require(session.accountId == accountIdFor(session.serverUrl) && session.token.isNotBlank())
            val data = JSONObject().put("id", session.id).put("serverUrl", session.serverUrl)
                .put("token", session.token).put("passwordLogin", session.passwordLogin)
            val encrypted = cipher.encrypt(data.toString().toByteArray(Charsets.UTF_8))
            val output = file.startWrite()
            try { output.write(encrypted); file.finishWrite(output) }
            catch (error: Exception) { file.failWrite(output); throw error }
        } catch (error: Exception) { throw SessionStorageException(error) }
    }

    fun clear() = synchronized(LOCK) {
        file.delete()
        if (listOf(file.baseFile, File(file.baseFile.path + ".bak"), File(file.baseFile.path + ".new")).any { it.exists() })
            throw SessionStorageException(IOException("Unable to remove session"))
    }

    private companion object { val LOCK = Any() }
}
