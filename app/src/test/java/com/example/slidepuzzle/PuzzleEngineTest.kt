package com.example.slidepuzzle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PuzzleEngineTest {

    @Test
    fun `new engine starts solved with zero moves`() {
        val engine = PuzzleEngine(4)
        assertTrue(engine.isSolved)
        assertEquals(0, engine.moves)
    }

    @Test
    fun `adjacent move swaps tile with blank and counts one move`() {
        val engine = PuzzleEngine(3)
        val slides = engine.moveTilesAt(5)!!
        assertEquals(listOf(5 to 8), slides)
        assertEquals(4, engine.positions[8])
        assertEquals(8, engine.positions[5])
        assertEquals(1, engine.moves)
        assertFalse(engine.isSolved)
    }

    @Test
    fun `line move slides every tile between tile and blank`() {
        val engine = PuzzleEngine(3)
        val slides = engine.moveTilesAt(6)!!
        assertEquals(listOf(7 to 8, 6 to 7), slides)
        assertEquals(8, engine.positions[6])
        assertEquals(6, engine.positions[7])
        assertEquals(7, engine.positions[8])
        assertEquals(2, engine.moves)
    }

    @Test
    fun `diagonal and blank moves are rejected`() {
        val engine = PuzzleEngine(3)
        assertNull(engine.moveTilesAt(0))
        assertNull(engine.moveTilesAt(8))
    }

    @Test
    fun `neighbors respect board edges`() {
        val engine = PuzzleEngine(4)
        assertEquals(listOf(1, 4), engine.neighborsOf(0).sorted())
        assertEquals(4, engine.neighborsOf(5).size)
        assertEquals(2, engine.neighborsOf(15).size)
    }

    @Test
    fun `shuffle always produces a solvable scrambled board`() {
        for (n in 3..20) {
            repeat(5) {
                val engine = PuzzleEngine(n)
                engine.shuffle(Random(42))
                assertFalse("size $n stayed solved", engine.isSolved)
                assertTrue("size $n produced an unsolvable board", isSolvable(engine.positions, n))
                assertEquals("shuffle must not count as moves", 0, engine.moves)
            }
        }
    }

    @Test
    fun `same seed produces identical scrambles - daily challenges are reproducible`() {
        val a = PuzzleEngine(6).apply { shuffle(Random(20240101L)) }
        val b = PuzzleEngine(6).apply { shuffle(Random(20240101L)) }
        assertTrue(a.positions.contentEquals(b.positions))

        val c = PuzzleEngine(6).apply { shuffle(Random(20240102L)) }
        assertFalse(a.positions.contentEquals(c.positions))
    }

    @Test
    fun `undo reverses a single move`() {
        val engine = PuzzleEngine(3)
        engine.moveTilesAt(5)
        assertTrue(engine.canUndo)
        assertTrue(engine.undo())
        assertTrue(engine.isSolved)
        assertEquals(0, engine.moves)
        assertFalse(engine.canUndo)
    }

    @Test
    fun `undo reverts a multi-tile line move in one step`() {
        val engine = PuzzleEngine(3)
        engine.moveTilesAt(6)
        assertEquals(2, engine.moves)
        assertTrue(engine.undo())
        assertTrue(engine.isSolved)
        assertEquals(0, engine.moves)
    }

    @Test
    fun `undo is unavailable on a fresh board`() {
        assertFalse(PuzzleEngine(4).undo())
    }

    @Test
    fun `restore accepts a valid board and rejects invalid ones`() {
        val engine = PuzzleEngine(3)
        engine.shuffle()
        val saved = engine.positions.copyOf()
        engine.restore(saved, 7)
        assertTrue(saved.contentEquals(engine.positions))
        assertEquals(7, engine.moves)

        assertThrows(IllegalArgumentException::class.java) {
            engine.restore(intArrayOf(1, 1, 2, 3, 4, 5, 6, 7, 8), 0)
        }
    }

    private fun isSolvable(board: IntArray, n: Int): Boolean {
        val blankId = n * n - 1
        val tiles = board.filter { it != blankId }
        val inversions = tiles.indices
            .sumOf { i -> tiles.drop(i + 1).count { it < tiles[i] } }
        return if (n % 2 == 1) {
            inversions % 2 == 0
        } else {
            val blankRowFromBottom = n - board.indexOf(blankId) / n
            (inversions + blankRowFromBottom) % 2 == 1
        }
    }
}
