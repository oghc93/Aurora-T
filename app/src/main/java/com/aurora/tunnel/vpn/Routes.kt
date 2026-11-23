package com.aurora.tunnel.vpn

/** Menghasilkan daftar CIDR IPv4 yang menutupi seluruh ruang alamat KECUALI rentang yang dikecualikan. */
object Routes {
    private fun ip(a: Int, b: Int, c: Int, d: Int): Long =
        (a.toLong() shl 24) or (b.toLong() shl 16) or (c.toLong() shl 8) or d.toLong()

    /** (alamat dasar, panjang prefix) jaringan lokal yang tidak ikut VPN. */
    val LAN: List<Pair<Long, Int>> = listOf(
        ip(10, 0, 0, 0) to 8,
        ip(172, 16, 0, 0) to 12,
        ip(192, 168, 0, 0) to 16,
        ip(169, 254, 0, 0) to 16,
        ip(127, 0, 0, 0) to 8
    )

    fun excluding(excl: List<Pair<Long, Int>>): List<Pair<String, Int>> {
        val ranges = excl.map { (base, pfx) -> base to (base + (1L shl (32 - pfx)) - 1) }.sortedBy { it.first }
        val free = mutableListOf<Pair<Long, Long>>()
        var cur = 0L
        for ((s, e) in ranges) {
            if (s > cur) free.add(cur to (s - 1))
            cur = maxOf(cur, e + 1)
        }
        if (cur <= 0xFFFFFFFFL) free.add(cur to 0xFFFFFFFFL)

        val out = mutableListOf<Pair<String, Int>>()
        for ((s0, e) in free) {
            var s = s0
            while (s <= e) {
                var size = if (s == 0L) 1L shl 32 else (s and -s)
                while (size > e - s + 1) size = size shr 1
                val pfx = 32 - java.lang.Long.numberOfTrailingZeros(size)
                out.add(str(s) to pfx)
                s += size
            }
        }
        return out
    }

    private fun str(v: Long) = "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"
}
