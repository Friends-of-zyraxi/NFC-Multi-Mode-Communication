package io.github.zyraxi21.nfc.update

/** 按数字段比较日期式版本号与语义化版本号，避免把 .10 判为比 .9 更旧。 */
internal data class ReleaseVersion(
    private val numbers: List<Long>,
    private val preRelease: List<String>
) : Comparable<ReleaseVersion> {
    val isPrerelease: Boolean get() = preRelease.isNotEmpty()

    override fun compareTo(other: ReleaseVersion): Int {
        repeat(maxOf(numbers.size, other.numbers.size)) { index ->
            val comparison = (numbers.getOrNull(index) ?: 0L)
                .compareTo(other.numbers.getOrNull(index) ?: 0L)
            if (comparison != 0) return comparison
        }
        if (preRelease.isEmpty() && other.preRelease.isNotEmpty()) return 1
        if (preRelease.isNotEmpty() && other.preRelease.isEmpty()) return -1
        repeat(minOf(preRelease.size, other.preRelease.size)) { index ->
            val left = preRelease[index]
            val right = other.preRelease[index]
            val leftNumber = left.toLongOrNull()
            val rightNumber = right.toLongOrNull()
            val comparison = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    companion object {
        private val pattern = Regex(
            "^[vV]?([0-9]+(?:\\.[0-9]+)*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
                "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
        )

        fun parse(value: String): ReleaseVersion? {
            val match = pattern.matchEntire(value.trim()) ?: return null
            val numbers = match.groupValues[1].split('.').map { it.toLongOrNull() ?: return null }
            val preRelease = match.groupValues[2].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            return ReleaseVersion(numbers, preRelease)
        }
    }
}
