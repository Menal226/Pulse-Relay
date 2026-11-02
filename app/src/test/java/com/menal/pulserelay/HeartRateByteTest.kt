package com.menal.pulserelay

import com.menal.pulserelay.net.HeartRateByte
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateByteTest {
    @Test
    fun `encodes normal bpm as one unsigned byte`() {
        assertEquals(72, HeartRateByte.encode(72)!!.toInt() and 0xFF)
    }

    @Test
    fun `clamps values above 255`() {
        assertEquals(255, HeartRateByte.encode(300)!!.toInt() and 0xFF)
    }

    @Test
    fun `does not send absent or non-positive readings`() {
        assertNull(HeartRateByte.encode(null))
        assertNull(HeartRateByte.encode(0))
        assertNull(HeartRateByte.encode(-4))
    }
}
