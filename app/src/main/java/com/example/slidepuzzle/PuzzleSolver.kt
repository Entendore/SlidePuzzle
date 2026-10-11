package com.example.slidepuzzle

import kotlin.math.abs

/**
 * Sliding-puzzle solver using IDA* with an incrementally-updated Manhattan
 * heuristic. [weight] > 1 trades optimality for speed (weighted IDA*),
 * which is what makes 5×5 hints feasible on a phone.
 */
object PuzzleSolver {

    /** Hints (weighted search) work up to this size. */
    const val MAX_HINT_SIZE = 5

    /** Watch-auto-solve stays optimal, so it is capped lower. */
    const val MAX_AUTOSOLVE_SIZE = 4

    fun solve(
        start: IntArray,
        size: Int,
        maxNodes: Long = 2_000_000L,
        timeBudgetMs: Long = 2_500L,
        weight: Double = 1.0
    ): List<Int>? {
        val total = size * size
        if (size !in PuzzleEngine.MIN_SIZE..MAX_HINT_SIZE) return null
        if (start.size != total) return null
        val blankTile = total - 1

        val md = Array(total) { t ->
            IntArray(total) { c ->
                if (t == blankTile) 0
                else abs(t / size - c / size) + abs(t % size - c % size)
            }
        }

        var h = 0
        for (c in 0 until total) {
            val t = start[c]
            if (t != blankTile) h += md[t][c]
        }
        if (h == 0) return emptyList()

        val board = start.copyOf()
        var blank = board.indexOf(blankTile)
        val deadline = System.currentTimeMillis() + timeBudgetMs
        var nodes = 0L
        val path = ArrayList<Int>(64)

        class BudgetExceeded : Exception()

        fun neighborsOf(b: Int): IntArray {
            val row = b / size
            val col = b % size
            var count = 0
            val out = IntArray(4)
            if (row > 0) out[count++] = b - size
            if (row < size - 1) out[count++] = b + size
            if (col > 0) out[count++] = b - 1
            if (col < size - 1) out[count++] = b + 1
            return out.copyOf(count)
        }

        fun dfs(g: Int, bound: Double, prevBlank: Int): Double {
            if (h == 0) return -1.0   // FOUND
            if (++nodes > maxNodes) throw BudgetExceeded()
            if (nodes and 1023L == 0L && System.currentTimeMillis() > deadline) {
                throw BudgetExceeded()
            }
            var minBound = Double.MAX_VALUE
            for (nb in neighborsOf(blank)) {
                if (nb == prevBlank) continue
                val tile = board[nb]
                val dh = md[tile][blank] - md[tile][nb]
                val f = g + 1 + weight * (h + dh)
                if (f > bound) {
                    if (f < minBound) minBound = f
                    continue
                }
                board[blank] = tile
                board[nb] = blankTile
                val oldBlank = blank
                blank = nb
                h += dh
                path.add(nb)
                val result = dfs(g + 1, bound, oldBlank)
                if (result == -1.0) return -1.0
                if (result < minBound) minBound = result
                path.removeAt(path.size - 1)
                h -= dh
                blank = oldBlank
                board[nb] = tile
                board[oldBlank] = blankTile
            }
            return minBound
        }

        return try {
            var bound = weight * h
            while (true) {
                val result = dfs(0, bound, -1)
                if (result == -1.0) return path.toList()
                if (result == Double.MAX_VALUE) return null
                bound = result
            }
        } catch (e: BudgetExceeded) {
            null
        }
    }
}
