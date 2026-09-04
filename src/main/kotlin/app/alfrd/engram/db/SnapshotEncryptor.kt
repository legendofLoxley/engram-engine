package app.alfrd.engram.db

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption for snapshot files before they leave the process, per the custody
 * design's requirement that "snapshots and Memory Capsules are private and encrypted before
 * upload."
 *
 * The GCM nonce is generated fresh per encryption and stored as a prefix on the ciphertext file
 * (nonce || ciphertext-with-tag), so a single key can safely encrypt many snapshots.
 *
 * @param keyBase64 A base64-encoded 256-bit key, e.g. generated via `openssl rand -base64 32`.
 */
class SnapshotEncryptor(keyBase64: String) {

    private val keySpec = SecretKeySpec(Base64.getDecoder().decode(keyBase64), "AES")
    private val secureRandom = SecureRandom()

    /** Encrypts [plaintextFile] into [outputFile], returning the SHA-256 checksum of the resulting ciphertext. */
    fun encrypt(plaintextFile: File, outputFile: File): String {
        val nonce = ByteArray(NONCE_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))

        outputFile.outputStream().use { out ->
            out.write(nonce)
            plaintextFile.inputStream().use { input ->
                javax.crypto.CipherOutputStream(out, cipher).use { cipherOut ->
                    input.copyTo(cipherOut)
                }
            }
        }
        return sha256(outputFile)
    }

    /** Decrypts [ciphertextFile] (as produced by [encrypt]) into [outputFile]. */
    fun decrypt(ciphertextFile: File, outputFile: File) {
        ciphertextFile.inputStream().use { input ->
            val nonce = ByteArray(NONCE_LENGTH_BYTES)
            val read = input.read(nonce)
            require(read == NONCE_LENGTH_BYTES) { "Ciphertext file too short to contain a nonce" }

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))

            outputFile.outputStream().use { out ->
                javax.crypto.CipherInputStream(input, cipher).use { cipherIn ->
                    cipherIn.copyTo(out)
                }
            }
        }
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val NONCE_LENGTH_BYTES = 12

        /** SHA-256 checksum of [file]'s bytes, as stored on disk (i.e. of ciphertext, for encrypted snapshots). */
        fun sha256(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
