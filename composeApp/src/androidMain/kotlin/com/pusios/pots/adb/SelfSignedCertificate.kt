package com.pusios.pots.adb

import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import java.math.BigInteger
import java.security.KeyPair
import java.security.SecureRandom
import java.security.Signature
import java.util.Date

/**
 * Builds the self-signed certificate that carries the host key's public half.
 *
 * ADB identifies a client by the certificate it presents, not by a bare public key, so a host key
 * without a certificate is useless. The README of libadb-android uses `sun.security.x509`, which
 * does not exist on Android; the usual alternative is BouncyCastle's `bcpkix` certificate builders,
 * but the app only ships `bcprov`, and a certificate is a small enough DER structure to assemble
 * from `bcprov`'s ASN.1 classes without adding a ~1 MB dependency:
 *
 * ```
 * Certificate ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signature BIT STRING }
 * ```
 *
 * Nothing validates this certificate — adbd only extracts the public key from it — but it is a real
 * X.509 v3 document, so `CertificateFactory` parses it and a desktop `adb` would accept it too.
 */
internal object SelfSignedCertificate {
    private const val SIGNATURE_ALGORITHM = "SHA256withRSA"
    private const val SUBJECT = "CN=pots"
    private const val CLOCK_SKEW_MILLIS = 60_000L
    private const val VALIDITY_MILLIS = 10L * 365 * 24 * 60 * 60 * 1000

    /** The DER encoding of a self-signed certificate for [keyPair]. */
    fun create(keyPair: KeyPair): ByteArray {
        val signatureAlgorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
        val subject = X500Name(SUBJECT)
        val now = System.currentTimeMillis()

        val generator = V3TBSCertificateGenerator()
        generator.setSerialNumber(ASN1Integer(BigInteger(64, SecureRandom())))
        generator.setIssuer(subject)
        generator.setSubject(subject)
        generator.setStartDate(Time(Date(now - CLOCK_SKEW_MILLIS)))
        generator.setEndDate(Time(Date(now + VALIDITY_MILLIS)))
        generator.setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(keyPair.public.encoded))
        generator.setSignature(signatureAlgorithm)

        val tbsCertificate = generator.generateTBSCertificate()
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(keyPair.private)
            update(tbsCertificate.encoded)
            sign()
        }
        val certificate = DERSequence(
            arrayOf<ASN1Encodable>(tbsCertificate, signatureAlgorithm, DERBitString(signature)),
        )
        return certificate.encoded
    }
}
