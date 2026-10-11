package com.example.slidepuzzle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PuzzleSolverTest {

    @Test
    fun `solved board yields empty plan`() {
        val solved = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8)
        assertEquals(emptyList<Int>(), PuzzleSolver.solve(solved, 3))
    }

    @Test
    fun `two move scramble is solved optimally`() {
        val start = intArrayOf(0, 1, 2, 3, 4, 5, 8, 6, 7)
        val path = PuzzleSolver.solve(start, 3)!!
        assertEquals(2, path.size)
        verify(start, 3, path)
    }

    @Test
    fun `random walks are solved`() {
        verify(randomWalk(3, 200, seed = 1L), 3, null)
        verify(randomWalk(4, 40, seed = 2L), 4, null)
    }

    @Test
    fun `exhausted budget returns null instead of hanging`() {
        val board = randomWalk(3, 50, seed = 7L)
        assertNull(PuzzleSolver.solve(board, 3, maxNodes = 1))
    }

    @Test
    fun `weighted search solves a 5x5 within budget`() {
        val board = randomWalk(5, 80, seed = 11L)
        val path = PuzzleSolver.solve(
            board, 5, maxNodes = 4_000_000L, timeBudgetMs = 10_000L, weight = 2.0
        )
        assertNotNull("5x5 weighted search exceeded budget", path)
        verify(board, 5, path)
    }

    // -------- helpers --------

    /** Plays the plan through a real PuzzleEngine; it must end solved. */
    private fun verify(start: IntArray, size: Int, path: List<Int>?) {
        val engine = PuzzleEngine(size)
        engine.restore(start.copyOf(), 0)
        val plan = path ?: PuzzleSolver.solve(start, size)!!
        for (cell in plan) {
            assertNotNull("plan step $cell was illegal", engine.moveTilesAt(cell))
        }
        assertTrue(engine.isSolved)
    }

    /** Deterministic scramble: N legal moves from the solved state. */
    private fun randomWalk(size: Int, steps: Int, seed: Long): IntArray {
        val engine = PuzzleEngine(size)
        engine.restore(IntArray(size * size) { it }, 0)
        val rnd = Random(seed)
        var blank = engine.blankPosition()
        var previous = -1
        repeat(steps) {
            val options = engine.neighborsOf(blank).filter { it != previous }
            val pick = options[rnd.nextInt(options.size)]
            assertTrue(engine.moveTilesAt(pick) != null)
            previous = blank
            blank = engine.blankPosition()
        }
        return engine.positions.copyOf()
    }
}
