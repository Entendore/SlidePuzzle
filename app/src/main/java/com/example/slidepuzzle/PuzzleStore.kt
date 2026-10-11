package com.example.slidepuzzle

import android.content.Context

/** Records, per-size stats, settings, and daily-challenge/streak state. */
class PuzzleStore(context: Context) {

    data class Record(val moves: Int, val seconds: Int)
    data class Stats(val size: Int, val plays: Int, val wins: Int, val best: Record?)

    private val prefs = context.applicationContext
        .getSharedPreferences("slide_puzzle_store", Context.MODE_PRIVATE)

    // ---------- Records ----------

    fun recordFor(size: Int): Record? {
        val moves = prefs.getInt(key(size, "moves"), -1)
        if (moves < 0) return null
        return Record(moves, prefs.getInt(key(size, "seconds"), 0))
    }

    fun submit(size: Int, moves: Int, seconds: Int): Boolean {
        val current = recordFor(size)
        val better = current == null ||
                moves < current.moves ||
                (moves == current.moves && seconds < current.seconds)
        if (better) {
            prefs.edit()
                .putInt(key(size, "moves"), moves)
                .putInt(key(size, "seconds"), seconds)
                .apply()
        }
        return better
    }

    // ---------- Aggregate stats ----------

    fun addPlay(size: Int) = bump("size_${size}_plays")
    fun addWin(size: Int) = bump("size_${size}_wins")

    fun statsFor(size: Int): Stats = Stats(
        size,
        prefs.getInt("size_${size}_plays", 0),
        prefs.getInt("size_${size}_wins", 0),
        recordFor(size)
    )

    fun allStats(): List<Stats> =
        (PuzzleEngine.MIN_SIZE..PuzzleEngine.MAX_SIZE).map(::statsFor).filter { it.plays > 0 }

    // ---------- Daily challenge & streak ----------

    fun isDailyDone(epochDay: Long): Boolean = prefs.getBoolean("daily_$epochDay", false)

    /**
     * Marks [epochDay]'s challenge complete and updates the streak
     * (consecutive days count; a gap resets it to 1). Returns the new streak.
     */
    fun completeDaily(epochDay: Long): Int {
        if (isDailyDone(epochDay)) return currentStreak()
        val last = prefs.getLong("daily_last_day", Long.MIN_VALUE)
        val streak = if (last == epochDay - 1) currentStreak() + 1 else 1
        prefs.edit()
            .putBoolean("daily_$epochDay", true)
            .putLong("daily_last_day", epochDay)
            .putInt("daily_streak", streak)
            .apply()
        return streak
    }

    fun currentStreak(): Int = prefs.getInt("daily_streak", 0)

    // ---------- Settings ----------

    var lastBoardSize: Int
        get() = prefs.getInt("last_board_size", 5)
        set(value) = prefs.edit().putInt("last_board_size", value).apply()

    var soundOn: Boolean
        get() = prefs.getBoolean("sound_on", true)
        set(value) = prefs.edit().putBoolean("sound_on", value).apply()

    var numbersOn: Boolean
        get() = prefs.getBoolean("numbers_on", true)
        set(value) = prefs.edit().putBoolean("numbers_on", value).apply()

    var tipsShown: Boolean
        get() = prefs.getBoolean("tips_shown", false)
        set(value) = prefs.edit().putBoolean("tips_shown", value).apply()

    // ---------- Internals ----------

    private fun bump(key: String) =
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()

    private fun key(size: Int, field: String) = "size_${size}_$field"
}
