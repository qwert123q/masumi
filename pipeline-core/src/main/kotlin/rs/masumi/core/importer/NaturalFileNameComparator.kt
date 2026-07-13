package rs.masumi.core.importer

import java.util.Locale

object NaturalFileNameComparator : Comparator<String> {
    private val tokenPattern = Regex("[0-9]+|[^0-9]+")

    override fun compare(left: String, right: String): Int {
        if (left === right) return 0

        val leftTokens = tokenPattern.findAll(left).map { it.value }.toList()
        val rightTokens = tokenPattern.findAll(right).map { it.value }.toList()
        val commonSize = minOf(leftTokens.size, rightTokens.size)

        for (index in 0 until commonSize) {
            val leftToken = leftTokens[index]
            val rightToken = rightTokens[index]
            val tokenComparison = compareToken(leftToken, rightToken)
            if (tokenComparison != 0) return tokenComparison
        }

        val tokenCountComparison = leftTokens.size.compareTo(rightTokens.size)
        if (tokenCountComparison != 0) return tokenCountComparison

        return left.compareTo(right)
    }

    private fun compareToken(left: String, right: String): Int {
        val leftIsNumber = left.firstOrNull()?.isDigit() == true
        val rightIsNumber = right.firstOrNull()?.isDigit() == true
        if (leftIsNumber && rightIsNumber) {
            val leftSignificant = left.trimStart('0').ifEmpty { "0" }
            val rightSignificant = right.trimStart('0').ifEmpty { "0" }
            val lengthComparison = leftSignificant.length.compareTo(rightSignificant.length)
            if (lengthComparison != 0) return lengthComparison

            val valueComparison = leftSignificant.compareTo(rightSignificant)
            if (valueComparison != 0) return valueComparison

            return left.length.compareTo(right.length)
        }

        val foldedComparison = left.lowercase(Locale.ROOT).compareTo(right.lowercase(Locale.ROOT))
        if (foldedComparison != 0) return foldedComparison

        return 0
    }
}
