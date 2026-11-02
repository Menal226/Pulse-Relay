package com.menal.pulserelay.net

object HeartRateByte {
    /**
     * One unsigned byte on the wire, represented as a Kotlin Byte.
     * Invalid/absent readings are not sent; values above 255 are clamped.
     */
    fun encode(bpm: Int?): Byte? {
        if (bpm == null || bpm < 1) return null
        return bpm.coerceAtMost(255).toByte()
    }
}
