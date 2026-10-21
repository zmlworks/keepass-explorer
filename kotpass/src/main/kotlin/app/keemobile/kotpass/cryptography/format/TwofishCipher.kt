package app.keemobile.kotpass.cryptography.format

import app.keemobile.kotpass.cryptography.block.BlockCipherMode
import app.keemobile.kotpass.cryptography.block.PaddedBufferedBlockCipher
import app.keemobile.kotpass.cryptography.engines.TwofishEngine
import app.keemobile.kotpass.cryptography.padding.PKCS7Padding
import java.util.UUID

/**
 * Twofish (KeePass plugin cipher).
 *
 * Keepass2Android Offline writes Twofish ciphertext whose final PKCS#7 block is
 * invalid. On pad failure we raw-CBC-decrypt and drop the last 16-byte block,
 * which yields a clean gzip member for those files (verified against pykeepass).
 */
object TwofishCipher : CipherProvider {
    override val uuid: UUID = UUID.fromString("ad68f29f-576f-4bb9-a36a-d47af965346c")
    override val ivLength = 16U

    override fun encrypt(
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray
    ): ByteArray = createPaddedCipher(BlockCipherMode.CBC(iv))
        .apply { init(true, key) }
        .processBytes(data)

    override fun decrypt(
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray
    ): ByteArray {
        try {
            return createPaddedCipher(BlockCipherMode.CBC(iv))
                .apply { init(false, key) }
                .processBytes(data)
        } catch (_: Exception) {
            val raw = rawCbcDecrypt(key, iv, data)
            return if (raw.size > 16 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                raw.copyOf(raw.size - 16)
            } else {
                raw
            }
        }
    }

    private fun createPaddedCipher(mode: BlockCipherMode) =
        PaddedBufferedBlockCipher(TwofishEngine(), mode, PKCS7Padding)

    private fun rawCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        require(data.size % 16 == 0) { "Twofish ciphertext length must be a multiple of 16" }
        val engine = TwofishEngine()
        engine.init(false, key)
        val out = ByteArray(data.size)
        val prev = iv.copyOf()
        val block = ByteArray(16)
        var off = 0
        while (off < data.size) {
            engine.processBlock(data, off, block, 0)
            for (i in 0 until 16) {
                out[off + i] = (block[i].toInt() xor prev[i].toInt()).toByte()
            }
            System.arraycopy(data, off, prev, 0, 16)
            off += 16
        }
        return out
    }
}
