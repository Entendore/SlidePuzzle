package com.example.slidepuzzle

import android.content.Context

/** Persists unlocked achievement ids. */
class AchievementStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("slide_puzzle_achievements", Context.MODE_PRIVATE)

    /** Evaluates [result] against the unlocked set and stores any new unlocks. */
    fun recordGame(result: Achievements.GameResult): List<Achievements.Def> {
        val unlocked = prefs.getStringSet(KEY, emptySet())!!.toMutableSet()
        val newly = Achievements.evaluate(result, unlocked)
        if (newly.isNotEmpty()) {
            unlocked.addAll(newly.map { it.id })
            prefs.edit().putStringSet(KEY, unlocked).apply()
        }
        return newly
    }

    fun unlocked(): Set<String> = prefs.getStringSet(KEY, emptySet())!!

    companion object {
        private const val KEY = "unlocked"
    }
}
