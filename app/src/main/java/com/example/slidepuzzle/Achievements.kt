package com.example.slidepuzzle

/**
 * Achievement rules as a pure function — no Android dependencies beyond
 * string-resource ids (plain ints on the JVM).
 */
object Achievements {

    data class Def(val id: String, val labelRes: Int)

    data class GameResult(
        val size: Int,
        val won: Boolean,
        val moves: Int,
        val seconds: Int,
        val usedUndo: Boolean,
        /** Streak after the completed daily; 0 for non-daily games. */
        val dailyStreak: Int
    )

    val ALL = listOf(
        Def("first_win", R.string.ach_first_win),
        Def("win_3", R.string.ach_win_3),
        Def("win_4", R.string.ach_win_4),
        Def("win_5", R.string.ach_win_5),
        Def("speed_4", R.string.ach_speed_4),
        Def("purist", R.string.ach_purist),
        Def("big_10", R.string.ach_big_10),
        Def("giant_20", R.string.ach_giant_20),
        Def("streak_3", R.string.ach_streak_3),
        Def("streak_7", R.string.ach_streak_7)
    )

    private val byId = ALL.associateBy { it.id }

    /**
     * Returns the subset of achievements unlocked by [result] that aren't
     * already in [alreadyUnlocked], in ALL's order.
     */
    fun evaluate(result: GameResult, alreadyUnlocked: Set<String>): List<Def> {
        val earned = LinkedHashSet<String>()
        if (result.won) {
            earned += "first_win"
            if (result.size >= 3) earned += "win_3"
            if (result.size >= 4) earned += "win_4"
            if (result.size >= 5) earned += "win_5"
            if (result.size == 4 && result.seconds in 1..59) earned += "speed_4"
            if (!result.usedUndo) earned += "purist"
            if (result.size == 20) earned += "giant_20"
        }
        // Unlocked by starting a huge board, winning not required
        if (result.size >= 10) earned += "big_10"
        if (result.dailyStreak >= 3) earned += "streak_3"
        if (result.dailyStreak >= 7) earned += "streak_7"

        return earned.filter { it !in alreadyUnlocked }.mapNotNull(byId::get)
    }
}
