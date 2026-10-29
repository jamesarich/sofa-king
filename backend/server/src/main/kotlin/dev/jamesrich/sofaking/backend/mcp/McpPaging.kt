package dev.jamesrich.sofaking.backend.mcp

// How the list tools bound their output.

internal const val QUERY = "query"
internal const val LIMIT = "limit"
internal const val OFFSET = "offset"
internal const val DEFAULT_PAGE = 50
internal const val MAX_PAGE = 200
internal const val MAX_OFFSET = 100_000

/** The `offset` and `limit` arguments of a paged list. */
internal fun Arguments.page(): Pair<Int, Int> = int(OFFSET, 0, 0..MAX_OFFSET) to int(LIMIT, DEFAULT_PAGE, 1..MAX_PAGE)

/** Where the page after one at [offset] of [limit] starts, or null when [total] items end with it. */
internal fun nextOffset(offset: Int, limit: Int, total: Int): Int? = (offset + limit).takeIf { it < total }

/** The first [limit] of this list, with the full count and whether any are left out. */
internal fun <T> List<T>.capped(limit: Int) = Capped(size, take(limit), size > limit)
