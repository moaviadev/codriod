package dev.moavia.codriod.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.moavia.codriod.git.CodriodHostKeys
import dev.moavia.codriod.git.GitRemoteService
import dev.moavia.codriod.git.KeyVault
import dev.moavia.codriod.git.PlainVault
import dev.moavia.codriod.git.SshIdentity
import dev.moavia.codriod.git.SshIdentityStore
import dev.moavia.codriod.git.UnknownHostKey
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals SSH private keys with a non-exportable AES key held by Android Keystore for this app and Android user. */
class AndroidKeystoreVault : KeyVault {
    private companion object {
        const val ALIAS = "codriod-git-ssh-keys"
        const val STORE = "AndroidKeyStore"
        const val VERSION: Byte = 1
    }

    override val description = "Encrypted with Android Keystore, on this device only"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return generator.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return byteArrayOf(VERSION, iv.size.toByte()) + iv + body
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > 2 && sealed[0] == VERSION) { "Unsupported key format" }
        val ivSize = sealed[1].toInt()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 2, ivSize))
        return cipher.doFinal(sealed, 2 + ivSize, sealed.size - 2 - ivSize)
    }
}

/**
 * The person's own Git sign-in on this phone. Codriod is only the environment: it creates an SSH key
 * locally, the person adds the public key to their own GitHub account in the browser, and GitHub
 * decides what that account may access. There is no Codriod account, OAuth app, token or server.
 */
class GitAccountController(context: Context) {
    companion object {
        const val GITHUB_ADD_KEY_URL = "https://github.com/settings/ssh/new"
        const val GITHUB_KEYS_URL = "https://github.com/settings/keys"
    }

    private val appContext = context.applicationContext
    val identities = SshIdentityStore(File(appContext.filesDir, "git-identities"), chooseVault())

    /** Android Keystore when it works on this device; app-private storage otherwise (Android 5-5.1, broken Keystores). */
    private fun chooseVault(): KeyVault {
        if (Build.VERSION.SDK_INT < 23) return PlainVault
        val keystore = AndroidKeystoreVault()
        val probe = byteArrayOf(1, 2, 3)
        return if (runCatching { keystore.open(keystore.seal(probe)).contentEquals(probe) }.getOrDefault(false)) keystore
            else PlainVault
    }
    val hostKeys = CodriodHostKeys(File(appContext.filesDir, "git-known-hosts"))
    val remote = GitRemoteService(identities, hostKeys)

    var keys by mutableStateOf<List<SshIdentity>>(emptyList())
        private set
    var defaultKeyId by mutableStateOf<String?>(null)
        private set
    /** A server whose fingerprint the person must confirm before Codriod connects to it. */
    var pendingHost by mutableStateOf<UnknownHostKey?>(null)

    val keyStorage get() = identities.vaultDescription

    fun reload() {
        keys = identities.list()
        defaultKeyId = identities.defaultIdentity()?.id
    }

    fun defaultKey(): SshIdentity? = keys.firstOrNull { it.id == defaultKeyId }

    /** Blocking; call off the main thread. */
    fun createKey(label: String): SshIdentity = identities.create(label).also { reload() }

    fun deleteKey(id: String) { identities.delete(id); reload() }

    fun setDefault(id: String) { identities.setDefault(id); reload() }

    /** Blocking network call. */
    fun verifyGitHub(id: String): String = remote.verifyGitHub(id).also { reload() }

    fun trustPendingHost() {
        pendingHost?.let(hostKeys::trust)
        pendingHost = null
    }

    /** Copies the PUBLIC key only. */
    fun copyPublicKey(identity: SshIdentity) {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SSH public key", identity.publicKey))
    }

    /** GitHub's official page; the person signs in and approves the key there. */
    fun openGitHubAddKey() = openUrl(GITHUB_ADD_KEY_URL)

    fun openUrl(url: String) {
        appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    init { reload() }
}
