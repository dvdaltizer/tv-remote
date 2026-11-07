package com.slowpixels.tvremote.net

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import org.bouncycastle.crypto.agreement.srp.SRP6Util
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.util.BigIntegers
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/** The cryptography Apple's pairing uses: HKDF-SHA512, ChaCha20-Poly1305, X25519 and Ed25519. */
object Crypto {
    val random = SecureRandom()

    fun hkdf(ikm: ByteArray, salt: String, info: String, length: Int = 32): ByteArray {
        val g = HKDFBytesGenerator(SHA512Digest())
        g.init(HKDFParameters(ikm, salt.toByteArray(), info.toByteArray()))
        return ByteArray(length).also { g.generateBytes(it, 0, length) }
    }

    /** A pairing message's nonce: four zero bytes and its 8-character name ("PS-Msg05"). */
    fun nonce(label: String) = ByteArray(4) + label.toByteArray()

    fun seal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray = chacha(true, key, nonce, plain, aad)
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray = chacha(false, key, nonce, sealed, aad)

    private fun chacha(encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray, aad: ByteArray?): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(c.getOutputSize(input.size))
        val n = c.processBytes(input, 0, input.size, out, 0)
        c.doFinal(out, n)
        return out
    }

    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        val s = Ed25519Signer()
        s.init(true, Ed25519PrivateKeyParameters(seed, 0))
        s.update(message, 0, message.size)
        return s.generateSignature()
    }

    /** Checks an Ed25519 signature; a malformed key or signature is simply not valid. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = runCatching {
        if (publicKey.size != 32 || signature.size != 64) return false
        val v = Ed25519Signer()
        v.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        v.update(message, 0, message.size)
        v.verifySignature(signature)
    }.getOrDefault(false)

    fun publicKey(seed: ByteArray): ByteArray = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    class Ephemeral {
        private val key = X25519PrivateKeyParameters(random)
        val public: ByteArray = key.generatePublicKey().encoded
        fun shared(other: ByteArray): ByteArray {
            val a = X25519Agreement()
            a.init(key)
            return ByteArray(a.agreementSize).also { a.calculateAgreement(X25519PublicKeyParameters(other, 0), it, 0) }
        }
    }
}

/**
 * The client side of HomeKit's SRP-6a (3072-bit group, SHA-512, user
 * "Pair-Setup", the PIN as password), as the Apple TV expects it.
 */
class SrpClient(private val pin: String) {
    private val group = SRP6StandardGroups.rfc5054_3072
    private val n = group.n
    private val g = group.g
    private val a = BigInteger(256, Crypto.random)
    val publicA: BigInteger = g.modPow(a, n)
    lateinit var key: ByteArray
        private set

    private fun h(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    private fun bytes(x: BigInteger) = BigIntegers.asUnsignedByteArray(x)

    /** Works out the shared key from the Apple TV's salt and public key, and returns the proof (M1). */
    fun proof(salt: ByteArray, publicB: ByteArray): ByteArray {
        val digest = SHA512Digest()
        val b = BigInteger(1, publicB)
        val identity = "Pair-Setup".toByteArray()
        val k = SRP6Util.calculateK(digest, n, g)
        val u = SRP6Util.calculateU(digest, n, publicA, b)
        val x = SRP6Util.calculateX(digest, n, salt, identity, pin.toByteArray())
        val base = b.subtract(k.multiply(g.modPow(x, n))).mod(n)
        val s = base.modPow(a.add(u.multiply(x)), n)
        key = h(bytes(s))
        val hn = h(bytes(n))
        val hg = h(bytes(g))
        val xor = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        return h(xor, h(identity), salt, bytes(publicA), publicB, key)
    }
}
