package com.example.slidepuzzle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persists the in-progress game (with session flags) and photo to storage. */
class GamePersistence(context: Context) {

    data class SavedState(
        val size: Int,
        val positions: IntArray,
        val moves: Int,
        val elapsed: Int,
        val inProgress: Boolean,
        val assisted: Boolean,
        val dailyActive: Boolean,
        val dailyDay: Long,
        val usedUndo: Boolean
    )

    private val stateFile = File(context.filesDir, "game_state.json")
    private val photoFile = File(context.filesDir, "current_photo.jpg")

    fun save(
        size: Int,
        positions: IntArray,
        moves: Int,
        elapsed: Int,
        inProgress: Boolean,
        assisted: Boolean = false,
        dailyActive: Boolean = false,
        dailyDay: Long = -1L,
        usedUndo: Boolean = false
    ) {
        try {
            val array = JSONArray()
            positions.forEach { array.put(it) }
            val json = JSONObject()
                .put("size", size)
                .put("positions", array)
                .put("moves", moves)
                .put("elapsed", elapsed)
                .put("inProgress", inProgress)
                .put("assisted", assisted)
                .put("dailyActive", dailyActive)
                .put("dailyDay", dailyDay)
                .put("usedUndo", usedUndo)
            val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(stateFile)) {
                stateFile.writeText(json.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            // Best-effort persistence; never crash over it
        }
    }

    fun load(): SavedState? = try {
        if (!stateFile.exists()) null
        else {
            val json = JSONObject(stateFile.readText())
            val size = json.getInt("size")
            val array = json.getJSONArray("positions")
            val positions = IntArray(array.length()) { array.getInt(it) }
            require(size in PuzzleEngine.MIN_SIZE..PuzzleEngine.MAX_SIZE)
            require(positions.size == size * size)
            val sorted = positions.copyOf(); sorted.sort()
            require(sorted.indices.all { sorted[it] == it })
            SavedState(
                size, positions,
                json.getInt("moves"),
                json.getInt("elapsed"),
                json.getBoolean("inProgress"),
                json.optBoolean("assisted", false),
                json.optBoolean("dailyActive", false),
                json.optLong("dailyDay", -1L),
                json.optBoolean("usedUndo", false)
            )
        }
    } catch (e: Exception) {
        null
    }

    fun savePhoto(bitmap: Bitmap) {
        try {
            photoFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
        } catch (e: Exception) {
        }
    }

    fun loadPhoto(): Bitmap? = try {
        if (photoFile.exists()) BitmapFactory.decodeFile(photoFile.absolutePath) else null
    } catch (e: Exception) {
        null
    }
}
