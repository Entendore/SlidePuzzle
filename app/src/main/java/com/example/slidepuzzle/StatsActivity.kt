package com.example.slidepuzzle

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.example.slidepuzzle.databinding.ActivityStatsBinding
import org.json.JSONObject
import java.util.Locale

class StatsActivity : AppCompatActivity() {

    private val density by lazy { resources.displayMetrics.density }
    private val backup = DataBackup(this)

    private val exportDoc =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) exportTo(uri)
        }

    private val importDoc =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importFrom(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val store = PuzzleStore(this)
        val stats = store.allStats()
        if (stats.isEmpty()) {
            binding.statsEmpty.isVisible = true
        } else {
            addHeaderRow(binding.statsContainer)
            stats.forEach { addDataRow(binding.statsContainer, it) }
            addTotalRow(binding.statsContainer, stats)
        }
        addAchievementsSection(binding.statsContainer, store)

        binding.btnExport.setOnClickListener {
            exportDoc.launch(getString(R.string.backup_default_name))
        }
        binding.btnImport.setOnClickListener {
            importDoc.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
        }
    }

    // ---------- Backup ----------

    private fun exportTo(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(backup.exportJson().toString(2).toByteArray())
            }
            Toast.makeText(this, R.string.backup_exported, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun importFrom(uri: Uri) {
        try {
            val json = contentResolver.openInputStream(uri)?.use {
                it.readBytes().decodeToString()
            }
            if (json != null) {
                backup.importJson(JSONObject(json))
                Toast.makeText(this, R.string.backup_imported, Toast.LENGTH_SHORT).show()
                recreate()
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Achievements ----------

    private fun addAchievementsSection(container: LinearLayout, store: PuzzleStore) {
        container.addView(sectionTitle(getString(R.string.achievements_title)))

        val streak = store.currentStreak()
        if (streak > 0) {
            container.addView(newRow().apply {
                addView(newCell(getString(R.string.streak_format, streak), 1f, emphasized = true))
            })
        }

        val unlocked = AchievementStore(this).unlocked()
        Achievements.ALL.forEach { def ->
            val got = def.id in unlocked
            container.addView(newRow().apply {
                addView(newCell(if (got) "🏆" else "🔒", 0.35f))
                addView(newCell(getString(def.labelRes), 2.65f, emphasized = got))
            })
        }
    }

    // ---------- Row builders ----------

    private fun sectionTitle(text: String) = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(24), 0, dp(4)) }
        this.text = text
        textSize = 20f
        setTextColor(0xFFFFFFFF.toInt())
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun addHeaderRow(container: LinearLayout) {
        container.addView(newRow().apply {
            addView(newCell(getString(R.string.stats_header_size), 1.2f, emphasized = true))
            addView(newCell(getString(R.string.stats_header_best), 2f, emphasized = true))
            addView(newCell(getString(R.string.stats_header_record), 1f, emphasized = true))
        })
    }

    private fun addDataRow(container: LinearLayout, stats: PuzzleStore.Stats) {
        val bestText = stats.best
            ?.let { getString(R.string.stats_best_format, it.moves, formatSeconds(it.seconds)) }
            ?: "—"
        container.addView(newRow().apply {
            addView(newCell("${stats.size} × ${stats.size}", 1.2f))
            addView(newCell(bestText, 2f))
            addView(newCell("${stats.wins} / ${stats.plays}", 1f))
        })
    }

    private fun addTotalRow(container: LinearLayout, stats: List<PuzzleStore.Stats>) {
        val plays = stats.sumOf { it.plays }
        val wins = stats.sumOf { it.wins }
        container.addView(newRow().apply {
            addView(newCell(getString(R.string.stats_total), 1.2f, emphasized = true))
            addView(newCell("—", 2f))
            addView(newCell("$wins / $plays", 1f, emphasized = true))
        })
    }

    private fun newRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun newCell(text: String, weight: Float, emphasized: Boolean = false) =
        TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
            this.text = text
            textSize = 15f
            setTextColor(if (emphasized) 0xFFFFFFFF.toInt() else 0xFFB0B0C0.toInt())
            if (emphasized) typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun dp(value: Int) = (value * density).toInt()

    private fun formatSeconds(seconds: Int): String =
        String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
}
