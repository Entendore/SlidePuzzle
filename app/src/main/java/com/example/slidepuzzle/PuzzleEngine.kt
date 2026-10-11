package com.example.slidepuzzle

import kotlin.random.Random

/**
 * Pure game logic for an N×N sliding puzzle — no Android dependencies.
 * Board model: [positions] maps a grid position (row-major) to the tile id
 * there; the board is solved when positions[i] == i. Supports multi-tile
 * line moves and full undo history.
 */
class PuzzleEngine(val size: Int) {

    init { require(size in 2..64) { "size must be between 2 and 64" } }

    val blankTileId = size * size - 1
    val tileCount: Int get() = size * size

    var positions = IntArray(tileCount) { it }
        private set

    var moves = 0
        private set

    val isSolved: Boolean get() = positions.indices.all { positions[it] == it }
    val canUndo: Boolean get() = history.isNotEmpty()

    private class Snapshot(val positions: IntArray, val moves: Int)
    private val history = ArrayList<Snapshot>()

    fun blankPosition(): Int = positions.indexOf(blankTileId)

    fun reset() {
        for (i in positions.indices) positions[i] = i
        moves = 0
        history.clear()
    }

    fun neighborsOf(position: Int): List<Int> {
        val row = position / size
        val col = position % size
        val result = ArrayList<Int>(4)
        if (row > 0) result += position - size
        if (row < size - 1) result += position + size
        if (col > 0) result += position - 1
        if (col < size - 1) result += position + 1
        return result
    }

    /**
     * Slide the tile at [position] toward the blank. If it shares a row or
     * column with the blank, every tile between them slides one cell.
     * Returns the slides as (from, to) pairs, or null if illegal.
     */
    fun moveTilesAt(position: Int): List<Pair<Int, Int>>? {
        val blank = blankPosition()
        if (position == blank || !inLineWith(position, blank)) return null

        pushSnapshot()
        val slides = ArrayList<Pair<Int, Int>>()
        var cursor = blank
        while (cursor != position) {
            val next = stepToward(cursor, position)
            positions[cursor] = positions[next]
            positions[next] = blankTileId
            slides += next to cursor
            cursor = next
        }
        moves += slides.size
        return slides
    }

    /** Revert the last [moveTilesAt] (a whole line move counts as one step). */
    fun undo(): Boolean {
        if (history.isEmpty()) return false
        val snapshot = history.removeAt(history.size - 1)
        positions = snapshot.positions.copyOf()
        moves = snapshot.moves
        return true
    }

    fun shuffle(random: Random = Random.Default) {
        do {
            reset()
            var blank = blankPosition()
            var previous = -1
            repeat(tileCount * SCRAMBLE_MOVES_PER_TILE) {
                val options = neighborsOf(blank).filter { it != previous }
                val pick = options[random.nextInt(options.size)]
                positions[blank] = positions[pick]
                positions[pick] = blankTileId
                previous = blank
                blank = pick
            }
        } while (misplacedCount() < tileCount / 2)
        moves = 0
    }

    fun restore(savedPositions: IntArray, savedMoves: Int) {
        require(savedPositions.size == tileCount) {
            "board has ${savedPositions.size} cells, expected $tileCount"
        }
        val sorted = savedPositions.sortedArray()
        for (i in sorted.indices) {
            require(sorted[i] == i) { "board is not a permutation of 0..${tileCount - 1}" }
        }
        require(savedMoves >= 0) { "moves must be >= 0" }
        positions = savedPositions.copyOf()
        moves = savedMoves
        history.clear()
    }

    private fun pushSnapshot() {
        history.add(Snapshot(positions.copyOf(), moves))
        if (history.size > MAX_HISTORY) history.removeAt(0)
    }

    private fun misplacedCount(): Int = positions.indices.count { positions[it] != it }

    private fun stepToward(from: Int, to: Int): Int {
        val rowFrom = from / size
        val rowTo = to / size
        return when {
            rowFrom > rowTo -> from - size
            rowFrom < rowTo -> from + size
            from > to -> from - 1
            else -> from + 1
        }
    }

    private fun inLineWith(a: Int, b: Int): Boolean =
        a / size == b / size || a % size == b % size

    companion object {
        const val MIN_SIZE = 3
        const val MAX_SIZE = 20
        private const val SCRAMBLE_MOVES_PER_TILE = 40
        private const val MAX_HISTORY = 2000
    }
}
