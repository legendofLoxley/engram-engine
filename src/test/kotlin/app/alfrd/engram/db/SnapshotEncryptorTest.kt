package app.alfrd.engram.db

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.Base64
import javax.crypto.AEADBadTagException

class SnapshotEncryptorTest {

    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    private val encryptor = SnapshotEncryptor(key)

    @Test
    fun `round-trips plaintext through encrypt and decrypt`() {
        val plaintext = Files.createTempFile("plain-", ".txt").toFile()
        val ciphertext = Files.createTempFile("cipher-", ".enc").toFile()
        val decrypted = Files.createTempFile("decrypted-", ".txt").toFile()
        try {
            val original = "the dog's name is Norton".repeat(100).toByteArray()
            plaintext.writeBytes(original)

            encryptor.encrypt(plaintext, ciphertext)
            assertTrue(!ciphertext.readBytes().contentEquals(original), "Ciphertext must not equal plaintext")

            encryptor.decrypt(ciphertext, decrypted)
            assertArrayEquals(original, decrypted.readBytes())
        } finally {
            plaintext.delete(); ciphertext.delete(); decrypted.delete()
        }
    }

    @Test
    fun `checksum returned by encrypt matches the ciphertext file's SHA-256`() {
        val plaintext = Files.createTempFile("plain-", ".txt").toFile()
        val ciphertext = Files.createTempFile("cipher-", ".enc").toFile()
        try {
            plaintext.writeText("checksum me")
            val returnedChecksum = encryptor.encrypt(plaintext, ciphertext)
            assertEquals(SnapshotEncryptor.sha256(ciphertext), returnedChecksum)
        } finally {
            plaintext.delete(); ciphertext.delete()
        }
    }

    @Test
    fun `tampered ciphertext fails to decrypt rather than silently returning corrupt data`() {
        val plaintext = Files.createTempFile("plain-", ".txt").toFile()
        val ciphertext = Files.createTempFile("cipher-", ".enc").toFile()
        val decrypted = Files.createTempFile("decrypted-", ".txt").toFile()
        try {
            plaintext.writeText("do not tamper with me")
            encryptor.encrypt(plaintext, ciphertext)

            val bytes = ciphertext.readBytes()
            bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
            ciphertext.writeBytes(bytes)

            // CipherInputStream wraps AEADBadTagException in an IOException — the important
            // guarantee is that decryption fails loudly rather than returning tampered plaintext.
            val thrown = assertThrows(java.io.IOException::class.java) {
                encryptor.decrypt(ciphertext, decrypted)
            }
            assertTrue(thrown.cause is AEADBadTagException, "Root cause should be a GCM tag failure")
        } finally {
            plaintext.delete(); ciphertext.delete(); decrypted.delete()
        }
    }
}
