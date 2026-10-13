package de.rochefort.childmonitor.storage

import android.content.Context
import de.rochefort.childmonitor.reliability.MonotonicClock
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted rolling local media buffer for event rewind.
 * Implements CM-STORE-001 and CM-STORE-005 (integrity checksums).
 *
 * Writes short audio/video clips as AES-GCM encrypted files into a rotating
 * ring of N most recent chunks. The file name encodes the monotonic timestamp
 * and a SHA-256 checksum is appended for tamper/corruption detection.
 */
class RollingMediaBuffer(
    private val context: Context,
    private val maxChunks: Int = 256,
    private val subdir: String = "media_ring"
) {

    private val directory = File(context.filesDir, subdir).also { it.mkdirs() }
    private val key: SecretKey = loadOrGenerateKey(context)

    data class ChunkRef(val file: File, val tsMs: Long)

    fun append(payload: ByteArray, ext: String = "bin"): ChunkRef {
        val ts = MonotonicClock.nowMs()
        val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        val cipherText = cipher.doFinal(payload)
        val checksum = MessageDigest.getInstance("SHA-256").digest(payload)
        val file = File(directory, "$ts.$ext.enc")
        file.outputStream().use { out ->
            out.write(nonce)
            out.write(checksum)
            out.write(cipherText)
        }
        trim()
        return ChunkRef(file, ts)
    }

    fun readDecrypted(file: File): ByteArray? {
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 12 + 32) return null
            val nonce = bytes.copyOfRange(0, 12)
            val checksum = bytes.copyOfRange(12, 44)
            val cipherText = bytes.copyOfRange(44, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            val plain = cipher.doFinal(cipherText)
            val actual = MessageDigest.getInstance("SHA-256").digest(plain)
            if (!actual.contentEquals(checksum)) return null
            plain
        } catch (_: Exception) {
            null
        }
    }

    fun list(): List<ChunkRef> {
        return directory.listFiles()
            ?.mapNotNull { f ->
                val name = f.nameWithoutExtension
                val numeric = name.substringBefore('.')
                val ts = numeric.toLongOrNull() ?: return@mapNotNull null
                ChunkRef(f, ts)
            }
            ?.sortedByDescending { it.tsMs }
            ?: emptyList()
    }

    private fun trim() {
        val files = directory.listFiles()?.sortedBy { it.lastModified() } ?: return
        val excess = files.size - maxChunks
        if (excess > 0) {
            for (i in 0 until excess) {
                try { files[i].delete() } catch (_: Exception) { }
            }
        }
    }

    companion object {
        private const val KEY_FILE = "media_ring.key"

        private fun loadOrGenerateKey(context: Context): SecretKey {
            val file = File(context.filesDir, KEY_FILE)
            return if (file.exists()) {
                SecretKeySpec(file.readBytes(), "AES")
            } else {
                val kg = KeyGenerator.getInstance("AES")
                kg.init(256)
                val k = kg.generateKey()
                try { file.writeBytes(k.encoded) } catch (_: IOException) { }
                k
            }
        }
    }
}
