package dev.sillport.matepad

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import javax.security.auth.x500.X500Principal

/**
 * This tablet's identity: a P-256 key in the Android Keystore (never leaves the secure hardware)
 * with the self-signed certificate the Keystore makes for it. Sill pins keys, not certificates,
 * so names and dates don't matter (RemoteTLS.swift).
 */
object DeviceIdentity {
    private const val ALIAS = "sill-device"

    private val keyStore: KeyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    @Synchronized
    fun ensure() {
        if (keyStore.containsAlias(ALIAS)) return
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        gen.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
                .setCertificateSubject(X500Principal("CN=sill-matepad"))
                .build()
        )
        gen.generateKeyPair()
    }

    val certificate: X509Certificate get() { ensure(); return keyStore.getCertificate(ALIAS) as X509Certificate }
    val privateKey: PrivateKey get() { ensure(); return keyStore.getKey(ALIAS, null) as PrivateKey }

    /** SHA-256 of this key's SubjectPublicKeyInfo: what the Mac pins. */
    val fingerprint: ByteArray get() = Pairing.sha256(certificate.publicKey.encoded)

    val keyManager = object : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getCertificateChain(alias: String?) = arrayOf(certificate)
        override fun getPrivateKey(alias: String?) = privateKey
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
    }
}

/** The DER prefix of a P-256 SubjectPublicKeyInfo; anything else is a key no pin can match. */
private val P256_SPKI_PREFIX = byteArrayOf(
    0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01,
    0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
)

fun spkiFingerprint(cert: X509Certificate): ByteArray? {
    val spki = cert.publicKey.encoded
    if (spki.size != 91 || !spki.copyOfRange(0, P256_SPKI_PREFIX.size).contentEquals(P256_SPKI_PREFIX)) return null
    return Pairing.sha256(spki)
}

class PinMismatch : CertificateException("The Mac presented a different key than the one this tablet paired with")

/**
 * Sill's TLS (RemoteTLS.swift): TLS 1.3 only, both sides present a P-256 certificate, trust is the
 * pin alone, one ALPN, SNI "sill", no resumption (a fresh SSLContext per connection).
 */
object SillTls {
    const val SESSION = "sill/1"
    const val PAIRING = "sill-pair/1"

    class Connected(val socket: SSLSocket, val macFingerprint: ByteArray)

    fun connect(host: String, port: Int, alpn: String, pin: ByteArray?): Connected {
        var seen: ByteArray? = null
        val trust = object : X509ExtendedTrustManager() {
            private fun check(chain: Array<out X509Certificate>?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
                val fp = spkiFingerprint(leaf) ?: throw CertificateException("not a P-256 key")
                if (pin != null && !fp.contentEquals(pin)) throw PinMismatch()
                seen = fp
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = throw CertificateException()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = throw CertificateException()
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLSv1.3")
        ctx.init(arrayOf(DeviceIdentity.keyManager), arrayOf(trust), null)

        val raw = Socket()
        raw.tcpNoDelay = true
        raw.keepAlive = true
        raw.trafficClass = 0x88   // DSCP AF41, interactive video, as Sill's .interactiveVideo service class
        raw.connect(InetSocketAddress(host, port), 10_000)
        val s = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        s.enabledProtocols = arrayOf("TLSv1.3")
        val params = s.sslParameters
        params.applicationProtocols = arrayOf(alpn)
        params.serverNames = listOf(SNIHostName("sill"))
        s.sslParameters = params
        s.soTimeout = 15_000
        s.startHandshake()
        if (s.applicationProtocol != alpn) { s.close(); throw java.io.IOException("The Mac didn't accept $alpn") }
        val fp = seen ?: run { s.close(); throw CertificateException("no key seen") }
        return Connected(s, fp)
    }
}
