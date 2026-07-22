package rs.masumi.app

internal object PageNavigation {
    const val WORKSPACE = 0
    const val DETAILS = 1

    fun normalize(page: Int?): Int = if (page == DETAILS) DETAILS else WORKSPACE

    fun backDestination(currentPage: Int): Int? {
        return if (normalize(currentPage) == DETAILS) WORKSPACE else null
    }
}
