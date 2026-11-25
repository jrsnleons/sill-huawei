package dev.sillport.matepad

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vectors in Sill's docs/remote-access-plan.md §3.6, computed in Python and matched by Swift. */
class PairingTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val fpMac = Pairing.sha256("mac".toByteArray())
    private val fpDev = Pairing.sha256("device".toByteArray())

    @Test fun standInFingerprints() {
        assertArrayEquals(hex("348a629f5ceed032c3e8706ec47d9bfafb00fb4250b018dd965435ca50cb836e"), fpMac)
        assertArrayEquals(hex("263a4dbe41488fb87214b0032339dbb9f0c8da14c16dfcf13084bf3c2552eca5"), fpDev)
    }

    @Test fun codeKeyAndProofs() {
        val k = Pairing.codeKey("482913557208", fpMac)
        assertArrayEquals(hex("e9baf879a1cc4ac8b21554df480b6c1621671319107c91e7cb05d154f649d31d"), k)
        assertEquals("dk7PZj4vHsAxj--1u9Vg7ehSn8a1tWc0RCG46YpFVO8", Pairing.b64u(Pairing.deviceProof(k, fpDev, fpMac)))
        assertEquals("6BmGWC9XT8J8OY8phhq9bASgoSnoWpRxBeqZtvRrWmk", Pairing.b64u(Pairing.macProof(k, fpMac, fpDev)))
        assertTrue(Pairing.isValidMacProof(Pairing.b64uDecode("6BmGWC9XT8J8OY8phhq9bASgoSnoWpRxBeqZtvRrWmk")!!, k, fpMac, fpDev))
    }

    @Test fun qrProofs() {
        val secret = ByteArray(16) { 0x22 }
        assertEquals("AyZAJqNTU5nSKEaHx6RZOf0K5dOeygR_8JSrCV_gNOo", Pairing.b64u(Pairing.deviceProof(secret, fpDev, fpMac)))
        assertEquals("XAX-uZPsq_Ax8fvI_sV7Ex0a-ROi19rI-FW-ykPh9TE", Pairing.b64u(Pairing.macProof(secret, fpMac, fpDev)))
    }

    @Test fun macID() {
        assertEquals("A3C5HR4RBV67YR21", Pairing.macID(Pairing.sha256("example".toByteArray())))
    }

    @Test fun codeCheck() {
        assertEquals(Pairing.CodeCheck.Ok("482913557208"), Pairing.checkCode("4829 1355 7208"))
        assertEquals(Pairing.CodeCheck.Typo, Pairing.checkCode("482913557209"))
        assertEquals(Pairing.CodeCheck.Typo, Pairing.checkCode("428913557208"))   // adjacent transposition
        assertEquals(Pairing.CodeCheck.Length, Pairing.checkCode("4829"))
    }
}

class WireTest {
    @Test fun headerRoundTrip() {
        val bytes = Wire.encode(Kind.PING, byteArrayOf(1, 2, 3))
        assertEquals(17, bytes.size)
        val m = Wire.read(java.io.DataInputStream(bytes.inputStream()))
        assertEquals(Kind.PING, m.kind)
        assertArrayEquals(byteArrayOf(1, 2, 3), m.payload)
    }

    @Test fun parameterSetsAndAnnexB() {
        val ps = ParameterSets.parse(byteArrayOf(4, 2, 0, 2, 0x40, 0x01, 0, 1, 0x42))!!
        assertEquals(4, ps.nalLength)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x40, 0x01, 0, 0, 0, 1, 0x42), ps.annexB())
        val au = byteArrayOf(0, 0, 0, 2, 0x26, 0x01, 0, 0, 0, 1, 0x02)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x26, 0x01, 0, 0, 0, 1, 0x02), lengthPrefixedToAnnexB(au, 4))
        assertNull(lengthPrefixedToAnnexB(byteArrayOf(0, 0, 0, 9, 1), 4))
    }
}
