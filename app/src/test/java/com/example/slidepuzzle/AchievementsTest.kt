package com.example.slidepuzzle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementsTest {

    private val none = emptySet<String>()
    private fun result(
        size: Int, won: Boolean, moves: Int = 50, seconds: Int = 90,
        usedUndo: Boolean = false, streak: Int = 0
    ) = Achievements.GameResult(size, won, moves, seconds, usedUndo, streak)

    private fun ids(r: Achievements.GameResult, unlocked: Set<String> = none) =
        Achievements.evaluate(r, unlocked).map { it.id }.toSet()

    @Test
    fun `first win unlocks the basics`() {
        val got = ids(result(3, won = true))
        assertTrue("first_win" in got && "win_3" in got && "purist" in got)
        assertFalse("speed_4" in got)
    }

    @Test
    fun `speed requires a 4x4 under a minute`() {
        assertTrue("speed_4" in ids(result(4, won = true, seconds = 59)))
        assertFalse("speed_4" in ids(result(4, won = true, seconds = 61)))
        assertFalse("speed_4" in ids(result(5, won = true, seconds = 30)))
    }

    @Test
    fun `purist refuses undo`() {
        assertFalse("purist" in ids(result(4, won = true, usedUndo = true)))
    }

    @Test
    fun `big league unlocks on play, not win`() {
        assertTrue("big_10" in ids(result(12, won = false)))
        assertFalse("big_10" in ids(result(9, won = false)))
    }

    @Test
    fun `giant twenty needs a real win`() {
        assertTrue("giant_20" in ids(result(20, won = true)))
        assertFalse("giant_20" in ids(result(20, won = false)))
    }

    @Test
    fun `streak badges`() {
        val got = ids(result(3, won = true, streak = 7))
        assertTrue("streak_3" in got && "streak_7" in got)
        assertFalse("streak_3" in ids(result(3, won = true, streak = 2)))
    }

    @Test
    fun `already unlocked are not returned again`() {
        val got = ids(result(3, won = true), unlocked = setOf("first_win", "win_3", "purist"))
        assertTrue(got.isEmpty())
    }

    @Test
    fun `losses only unlock play-based badges`() {
        val got = ids(result(15, won = false))
        assertTrue("big_10" in got)
        assertFalse("first_win" in got)
    }
}
