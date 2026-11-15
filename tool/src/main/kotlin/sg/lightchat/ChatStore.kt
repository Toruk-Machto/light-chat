package sg.lightchat

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences as AndroidPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SavedChats(val chats: List<Chat> = emptyList(), val preferences: Preferences = Preferences())

class ChatStore(dir: File) {
    private val file = AtomicFile(File(dir, "chats.json"))
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): SavedChats = try {
        json.decodeFromString<SavedChats>(file.readFully().decodeToString())
    } catch (_: java.io.FileNotFoundException) {
        SavedChats()
    }

    fun save(data: SavedChats) {
        val bytes = json.encodeToString(data).encodeToByteArray()
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}

interface Keys {
    suspend fun get(provider: Provider): String?
    suspend fun put(provider: Provider, key: String?)
}

class KeyVault(private val preferences: DataStore<AndroidPreferences>) : Keys {
    private val store get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override suspend fun get(provider: Provider): String? {
        val ciphertext = preferences.data.first()[slot(provider)] ?: return null
        val key = store.getKey(alias(provider), null) as? SecretKey ?: return null
        val bytes = Base64.decode(ciphertext, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString()
    }

    override suspend fun put(provider: Provider, key: String?) {
        if (key == null) {
            preferences.edit { it.remove(slot(provider)) }
            store.deleteEntry(alias(provider))
            return
        }
        val alias = alias(provider)
        val secret = store.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret)
        val ciphertext = cipher.iv + cipher.doFinal(key.encodeToByteArray())
        preferences.edit { it[slot(provider)] = Base64.encodeToString(ciphertext, Base64.NO_WRAP) }
    }

    private fun alias(provider: Provider) = "sg.lightchat.key.${provider.name}"
    private fun slot(provider: Provider) = stringPreferencesKey("encrypted_${provider.name}_key")
}
