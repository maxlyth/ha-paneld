package io.github.maxlyth.hapaneld.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelBackupTest {
    private val plain = """{"kind":"ha-paneld-backup","config":{"mqtt_password":"s3cret"}}""".toByteArray()

    @Test fun sealThenOpenRoundTrips() {
        val bundle = PanelBackup.seal(plain, "correct horse")
        assertArrayEquals(plain, PanelBackup.open(bundle, "correct horse"))
    }

    @Test fun wrongPassphraseFailsToOpen() {
        val bundle = PanelBackup.seal(plain, "correct horse")
        assertNull(PanelBackup.open(bundle, "battery staple"))
    }

    @Test fun tamperedCiphertextIsRejected() {
        val bundle = PanelBackup.seal(plain, "pw")
        bundle[bundle.size - 1] = (bundle[bundle.size - 1].toInt() xor 0x01).toByte()
        assertNull(PanelBackup.open(bundle, "pw"))
    }

    @Test fun ciphertextIsNotPlaintext() {
        val bundle = PanelBackup.seal(plain, "pw")
        // the secret must not appear anywhere in the sealed bytes
        val hay = String(bundle, Charsets.ISO_8859_1)
        assertFalse(hay.contains("s3cret"))
        assertTrue(hay.startsWith("HPB1"))
    }

    @Test fun garbageBundleReturnsNullNotThrow() {
        assertNull(PanelBackup.open("not a bundle".toByteArray(), "pw"))
        assertNull(PanelBackup.open(ByteArray(3), "pw"))
    }

    @Test fun isSealedDistinguishesEncryptedFromPlain() {
        assertTrue(PanelBackup.isSealed(PanelBackup.seal(plain, "pw")))
        assertFalse(PanelBackup.isSealed("""{"kind":"ha-paneld-backup"}""".toByteArray()))
        assertFalse(PanelBackup.isSealed(ByteArray(2)))
    }

    @Test fun eachSealUsesFreshSaltAndIv() {
        val a = PanelBackup.seal(plain, "pw")
        val b = PanelBackup.seal(plain, "pw")
        // same plaintext + passphrase must not produce identical bundles (random salt/iv)
        assertFalse(a.contentEquals(b))
    }
}
