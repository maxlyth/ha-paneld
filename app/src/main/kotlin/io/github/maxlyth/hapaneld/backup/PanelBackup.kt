package io.github.maxlyth.hapaneld.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted panel-backup envelope. A backup bundle carries a panel's whole recoverable state — the
 * ha-paneld config (incl. MQTT/HA secrets) and, optionally, the HA Companion's login database (its HA
 * access/refresh tokens). Those are full-HA-access secrets, so a bundle is ALWAYS encrypted at rest with
 * a user passphrase; ha-paneld never writes or logs it in the clear.
 *
 * Envelope (binary): magic "HPB1" | salt(16) | iv(12) | AES-256-GCM ciphertext(+tag). Key =
 * PBKDF2WithHmacSHA256(passphrase, salt, 210k iters, 256-bit). The crypto is pure JVM (javax.crypto) —
 * round-tripped in PanelBackupTest.
 */
object PanelBackup {
    private val MAGIC = "HPB1".toByteArray(Charsets.US_ASCII)
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256

    /** True if [bytes] is a sealed envelope (starts with the magic) vs a plain, unencrypted bundle. */
    fun isSealed(bytes: ByteArray): Boolean =
        bytes.size > MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    /** Encrypt [plaintext] under [passphrase] into a self-describing envelope (magic|salt|iv|ciphertext). */
    fun seal(plaintext: ByteArray, passphrase: String): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rnd.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { rnd.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        }
        val ct = cipher.doFinal(plaintext)
        return MAGIC + salt + iv + ct
    }

    /** Decrypt an envelope produced by [seal]. Returns null on a bad magic, a truncated bundle, or a wrong
     *  passphrase / tampered ciphertext (GCM tag mismatch) — the caller shows "wrong passphrase or corrupt". */
    fun open(bundle: ByteArray, passphrase: String): ByteArray? {
        val header = MAGIC.size + SALT_LEN + IV_LEN
        if (bundle.size <= header) return null
        if (!bundle.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        val salt = bundle.copyOfRange(MAGIC.size, MAGIC.size + SALT_LEN)
        val iv = bundle.copyOfRange(MAGIC.size + SALT_LEN, header)
        val ct = bundle.copyOfRange(header, bundle.size)
        return runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
                doFinal(ct)
            }
        }.getOrNull()
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(key, "AES")
    }
}
