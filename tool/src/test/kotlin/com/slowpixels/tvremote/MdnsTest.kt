package com.slowpixels.tvremote

import com.slowpixels.tvremote.net.CompanionServices
import com.slowpixels.tvremote.net.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress

/** The little DNS the finder speaks: the question it sends, and the answers an Apple TV gives. */
class MdnsTest {
    @Test fun theQuestionIsALegacyUnicastQueryForTheCompanionService() {
        val q = Dns.query("_companion-link._tcp.local.", Dns.TYPE_PTR)
        // Header: ID 0, flags 0, one question, no answers.
        assertEquals(listOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0), q.take(12).map { it.toInt() and 0xFF })
        val name = byteArrayOf(15) + "_companion-link".toByteArray() + byteArrayOf(4) + "_tcp".toByteArray() + byteArrayOf(5) + "local".toByteArray() + byteArrayOf(0)
        assertTrue(q.copyOfRange(12, 12 + name.size).contentEquals(name))
        // Type PTR, class IN with the unicast-response bit, so the answer comes straight back to our port.
        assertEquals(listOf(0, 12, 0x80, 1), q.takeLast(4).map { it.toInt() and 0xFF })
    }

    /** A response like the Apple TV's: a PTR answer, with SRV, TXT and A as additional records, names compressed. */
    private fun appleTvResponse(instance: String = "Living Room", port: Int = 49152, ip: IntArray = intArrayOf(192, 168, 1, 77),
                                txt: List<String> = listOf("rpMd=AppleTV11,1", "rpMRtID=ABCDEF", "rpVr=410.0"), includeA: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(n: Int) { out.write(n shr 8); out.write(n) }
        fun u32(n: Long) { u16((n shr 16).toInt()); u16((n and 0xFFFF).toInt()) }
        fun label(s: String) { val b = s.toByteArray(); out.write(b.size); out.write(b) }
        out.write(byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, if (includeA) 3 else 2)) // a response, one answer, additionals
        // Answer: _companion-link._tcp.local PTR <instance>._companion-link._tcp.local
        val typeAt = out.size()
        label("_companion-link"); label("_tcp"); label("local"); out.write(0)
        u16(Dns.TYPE_PTR); u16(1); u32(4500)
        val instanceBytes = instance.toByteArray()
        u16(1 + instanceBytes.size + 2)
        val instanceAt = out.size()
        label(instance); u16(0xC000 or typeAt) // pointer back to the service type
        // SRV for the instance (name as a pointer): priority, weight, port, target host.
        u16(0xC000 or instanceAt); u16(Dns.TYPE_SRV); u16(0x8001); u32(120)
        val host = "Living-Room"
        u16(6 + 1 + host.length + 1 + 5 + 1)
        u16(0); u16(0); u16(port)
        val hostAt = out.size()
        label(host); label("local"); out.write(0)
        // TXT for the instance.
        u16(0xC000 or instanceAt); u16(Dns.TYPE_TXT); u16(0x8001); u32(4500)
        u16(txt.sumOf { it.toByteArray().size + 1 })
        txt.forEach { label(it) }
        if (includeA) {
            u16(0xC000 or hostAt); u16(Dns.TYPE_A); u16(0x8001); u32(120)
            u16(4); ip.forEach { out.write(it) }
        }
        return out.toByteArray()
    }

    @Test fun readsTheAppleTvOutOfAResponse() {
        val records = Dns.records(appleTvResponse())
        assertEquals(4, records.size)
        assertEquals("Living Room._companion-link._tcp.local.", records[0].target)
        assertEquals(49152, records[1].port)
        assertEquals("Living-Room.local.", records[1].host)
        assertEquals(mapOf("rpMd" to "AppleTV11,1", "rpMRtID" to "ABCDEF", "rpVr" to "410.0"), records[2].txt)
        assertEquals("192.168.1.77", records[3].address)
        val tv = CompanionServices.from(records, InetAddress.getByName("10.0.0.5")).single()
        assertEquals("Living Room", tv.name)
        assertEquals("192.168.1.77", tv.host)
        assertEquals(49152, tv.port)
        assertEquals("AppleTV11,1", tv.model)
        assertEquals("ABCDEF", tv.networkId)
    }

    @Test fun theSendersAddressStandsInWhenNoARecordCame() {
        val tv = CompanionServices.from(Dns.records(appleTvResponse(includeA = false)), InetAddress.getByName("192.168.1.9")).single()
        assertEquals("192.168.1.9", tv.host)
        assertTrue(CompanionServices.from(Dns.records(appleTvResponse(includeA = false)), null).isEmpty())
    }

    @Test fun aLoopbackAddressInTheAnswerGivesWayToTheSender() {
        val tv = CompanionServices.from(Dns.records(appleTvResponse(ip = intArrayOf(127, 0, 0, 1))), InetAddress.getByName("10.0.2.2")).single()
        assertEquals("10.0.2.2", tv.host)
    }

    @Test fun aMacWithoutAModelIsStillAServiceButNotAnAppleTv() {
        val s = CompanionServices.from(Dns.records(appleTvResponse(instance = "David’s MacBook", txt = listOf("rpBA=11:22", "rpVr=410.0"))), null).single()
        assertEquals("David’s MacBook", s.name)
        assertEquals("", s.model)
        assertNull(s.networkId)
        assertTrue(!com.slowpixels.tvremote.net.isAppleTv(s.model))
    }

    @Test fun questionsAndRubbishGiveNothing() {
        assertTrue(Dns.records(Dns.query("_companion-link._tcp.local.", Dns.TYPE_PTR)).isEmpty())
        assertTrue(Dns.records(ByteArray(3)).isEmpty())
        assertTrue(Dns.records(ByteArray(0)).isEmpty())
        val good = appleTvResponse()
        // Cut anywhere: whatever is whole is read, nothing throws.
        for (n in 0 until good.size) Dns.records(good.copyOf(n))
        // A pointer loop ends.
        val loop = byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0, 0xC0.toByte(), 12, 0, 12, 0, 1, 0, 0, 0, 0, 0, 0)
        assertTrue(Dns.records(loop).isEmpty())
    }

    @Test fun namesAreReadWhereverTheyPoint() {
        val d = byteArrayOf(3, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 0, 0xC0.toByte(), 0)
        assertEquals("abc." to 5, Dns.readName(d, 0))
        assertEquals("abc." to 7, Dns.readName(d, 5))
    }
}
