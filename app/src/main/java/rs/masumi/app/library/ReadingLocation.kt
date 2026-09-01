package rs.masumi.app.library

/** A stable reader anchor: page plus its normalized vertical offset. */
data class ReadingLocation(
    val pageIndex: Int,
    val intraPageFraction: Float,
) {
    fun clamped(pageCount: Int): ReadingLocation {
        if (pageCount <= 0) return START
        val finiteFraction = intraPageFraction.takeIf(Float::isFinite) ?: 0f
        return ReadingLocation(
            pageIndex = pageIndex.coerceIn(0, pageCount - 1),
            intraPageFraction = finiteFraction.coerceIn(0f, MAXIMUM_FRACTION),
        )
    }

    fun reset(): ReadingLocation = START

    fun toStored(): String = "$pageIndex|$intraPageFraction"

    companion object {
        val START = ReadingLocation(pageIndex = 0, intraPageFraction = 0f)

        fun fromStored(value: String?): ReadingLocation? = runCatching {
            val parts = requireNotNull(value).split('|')
            require(parts.size == 2)
            val fraction = parts[1].toFloat()
            require(fraction.isFinite())
            ReadingLocation(parts[0].toInt(), fraction)
        }.getOrNull()
        private const val MAXIMUM_FRACTION = 0.9999f
    }
}
