package com.example.slidepuzzle

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.example.slidepuzzle.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: PuzzleStore
    private lateinit var sounds: PuzzleSounds
    private lateinit var persistence: GamePersistence
    private lateinit var achievements: AchievementStore
    private val vm: GameViewModel by viewModels()

    private var boardSize = 5
    private var elapsedSeconds = 0
    private var isGameInProgress = false
    private var soundOn = true
    private var cameraUri: Uri? = null

    // Session flags (persisted through process death)
    private var dailyActive = false
    private var dailyDay = -1L          // captured when the daily starts
    private var usedUndoThisGame = false
    private var lastSolveSummary: String? = null

    private var pendingDaily = false    // launcher-shortcut routing

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            elapsedSeconds++
            binding.timerText.text = formatTime(elapsedSeconds)
            handler.postDelayed(this, 1_000)
        }
    }

    // ---------- Activity result launchers ----------

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) cameraUri?.let(::onImagePicked)
        }

    private val pickImage =
        registerForActivityResult(LocalImageContract()) { uri ->
            uri?.let(::onImagePicked)
        }

    // ---------- Lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = PuzzleStore(this)
        sounds = PuzzleSounds(this)
        persistence = GamePersistence(this)
        achievements = AchievementStore(this)
        soundOn = store.soundOn
        boardSize = store.lastBoardSize
        binding.puzzleView.showNumbers = store.numbersOn

        binding.puzzleView.listener = object : PuzzleView.Listener {
            override fun onMove(moves: Int) {
                binding.movesText.text = getString(R.string.moves_format, moves)
                if (!isGameInProgress) {
                    isGameInProgress = true
                    startTimer()
                }
                if (soundOn) sounds.playSlide()
            }

            override fun onSolved(moves: Int, assisted: Boolean) = handleSolved(moves, assisted)
        }

        binding.btnCamera.setOnClickListener {
            val uri = createCameraUri()
            cameraUri = uri
            takePicture.launch(uri)
        }
        binding.btnGallery.setOnClickListener { pickImage.launch("image/*") }
        binding.btnUndo.setOnClickListener { onUndoClicked() }
        binding.btnHint.setOnClickListener { onHintClicked() }
        binding.btnShuffle.setOnClickListener { startNewGame() }
        binding.btnSize.setOnClickListener { showSizeDialog() }
        binding.btnDaily.setOnClickListener { onDailyClicked() }
        binding.btnSolve.setOnClickListener { onAutoSolveClicked() }
        binding.btnShare.setOnClickListener { onShareClicked() }
        binding.btnStats.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }
        binding.btnNumbers.setOnClickListener {
            binding.puzzleView.showNumbers = !binding.puzzleView.showNumbers
            store.numbersOn = binding.puzzleView.showNumbers
        }
        binding.btnSound.setOnClickListener {
            soundOn = !soundOn
            store.soundOn = soundOn
            updateSoundButton()
        }

        updateSizeButton()
        updateSoundButton()
        updateDailyButton()
        binding.puzzleView.requestFocus()

        // Launcher shortcuts (cold start only)
        val action = intent?.action
        if (savedInstanceState == null && action == ACTION_QUICK3) {
            boardSize = 3
            store.lastBoardSize = 3
            updateSizeButton()
        }
        if (savedInstanceState == null && action == ACTION_DAILY) pendingDaily = true

        if (savedInstanceState != null) {
            restoreFromBundle(savedInstanceState)
        } else {
            restoreOrNewGame()
        }

        if (!store.tipsShown) {
            store.tipsShown = true
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.tips_title)
                .setMessage(R.string.tips_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        if (pendingDaily) {
            pendingDaily = false
            onDailyClicked()
        }
    }

    override fun onStart() {
        super.onStart()
        if (isGameInProgress) startTimer()
    }

    override fun onStop() {
        super.onStop()
        stopTimer()
        saveGameToDisk()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        sounds.release()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val engine = binding.puzzleView.engine
        outState.putInt(KEY_SIZE, engine?.size ?: boardSize)
        outState.putInt(KEY_ELAPSED, elapsedSeconds)
        outState.putBoolean(KEY_IN_PROGRESS, isGameInProgress)
        outState.putBoolean(KEY_ASSISTED, binding.puzzleView.isAssisted)
        outState.putBoolean(KEY_DAILY_ACTIVE, dailyActive)
        outState.putLong(KEY_DAILY_DAY, dailyDay)
        outState.putBoolean(KEY_USED_UNDO, usedUndoThisGame)
        engine?.let {
            outState.putIntArray(KEY_POSITIONS, it.positions)
            outState.putInt(KEY_MOVES, it.moves)
        }
    }

    // ---------- Startup paths ----------

    private fun restoreOrNewGame() {
        val saved = persistence.load()
        if (saved != null && saved.inProgress) {
            boardSize = saved.size
            store.lastBoardSize = boardSize
            updateSizeButton()
            elapsedSeconds = saved.elapsed
            isGameInProgress = true
            dailyActive = saved.dailyActive
            dailyDay = saved.dailyDay
            usedUndoThisGame = saved.usedUndo
            binding.puzzleView.markAssisted(saved.assisted)
            binding.timerText.text = formatTime(elapsedSeconds)
            binding.movesText.text = getString(R.string.moves_format, saved.moves)
            binding.puzzleView.restoreGame(saved.size, saved.positions, saved.moves)
            showStatusForNewGame()
        } else {
            elapsedSeconds = 0
        }

        val master = vm.masterBitmap
        if (master != null) {
            binding.puzzleView.setBitmap(master)
            if (saved == null || !saved.inProgress) startNewGame()
        } else {
            binding.progressBar.isVisible = true
            loadMasterAsync { bmp ->
                if (saved == null || !saved.inProgress) startNewGame()
                else binding.puzzleView.setBitmap(bmp)
            }
        }
    }

    private fun restoreFromBundle(saved: Bundle) {
        boardSize = saved.getInt(KEY_SIZE, boardSize)
        elapsedSeconds = saved.getInt(KEY_ELAPSED, 0)
        isGameInProgress = saved.getBoolean(KEY_IN_PROGRESS, false)
        dailyActive = saved.getBoolean(KEY_DAILY_ACTIVE, false)
        dailyDay = saved.getLong(KEY_DAILY_DAY, -1L)
        usedUndoThisGame = saved.getBoolean(KEY_USED_UNDO, false)
        val moves = saved.getInt(KEY_MOVES, 0)

        updateSizeButton()
        binding.timerText.text = formatTime(elapsedSeconds)
        binding.movesText.text = getString(R.string.moves_format, moves)

        val positions = saved.getIntArray(KEY_POSITIONS)
        if (positions != null) {
            binding.puzzleView.restoreGame(boardSize, positions, moves)
            binding.puzzleView.markAssisted(saved.getBoolean(KEY_ASSISTED, false))
        }

        val engine = binding.puzzleView.engine
        if (engine != null && engine.isSolved) {
            binding.statusText.text =
                getString(R.string.solved_format, moves, formatTime(elapsedSeconds))
        } else {
            showStatusForNewGame()
        }

        val master = vm.masterBitmap
        if (master != null) {
            binding.puzzleView.setBitmap(master)
        } else {
            binding.progressBar.isVisible = true
            loadMasterAsync { bmp -> binding.puzzleView.setBitmap(bmp) }
        }
    }

    // ---------- Game / UI helpers ----------

    private fun startNewGame() {
        dailyActive = false
        dailyDay = -1L
        isGameInProgress = false
        stopTimer()
        elapsedSeconds = 0
        usedUndoThisGame = false
        store.addPlay(boardSize)
        registerPlayAchievements(boardSize)
        binding.timerText.text = formatTime(0)
        binding.movesText.text = getString(R.string.moves_format, 0)
        binding.puzzleView.newGame(vm.masterBitmap, boardSize)
        showStatusForNewGame()
    }

    private fun showStatusForNewGame() {
        val best = store.recordFor(boardSize)
        binding.statusText.text = best
            ?.let { getString(R.string.best_format, it.moves, formatTime(it.seconds)) }
            ?: getString(R.string.tap_to_start)
    }

    private fun handleSolved(moves: Int, assisted: Boolean) {
        isGameInProgress = false
        stopTimer()
        val size = binding.puzzleView.engine?.size ?: boardSize
        val time = formatTime(elapsedSeconds)

        if (assisted) {
            binding.statusText.text = getString(R.string.solved_assisted_format, moves, time)
            updateDailyButton()
            saveGameToDisk()
            return
        }

        store.addWin(size)
        var streak = 0
        val prefix: String
        if (dailyActive) {
            // Marks the day the challenge STARTED, not "today" (midnight fix)
            streak = store.completeDaily(dailyDay)
            dailyActive = false
            updateDailyButton()
            prefix = getString(R.string.daily_complete_prefix)
        } else {
            prefix = ""
        }

        val isRecord = store.submit(size, moves, elapsedSeconds)
        lastSolveSummary = getString(R.string.share_solve_format, size, moves, time)
        val solvedLine = getString(R.string.solved_format, moves, time)

        binding.statusText.text = prefix + if (isRecord) {
            getString(R.string.new_record) + "\n" + solvedLine
        } else {
            store.recordFor(size)
                ?.let { solvedLine + "\n" + getString(R.string.best_format, it.moves, formatTime(it.seconds)) }
                ?: solvedLine
        }
        if (soundOn) sounds.playWin()
        binding.puzzleView.announceForAccessibility(getString(R.string.a11y_solved))

        // Achievements
        val newly = achievements.recordGame(
            Achievements.GameResult(size, won = true, moves, elapsedSeconds, usedUndoThisGame, streak)
        )
        if (newly.isNotEmpty()) {
            val names = newly.joinToString("\n") { "🏆 ${getString(it.labelRes)}" }
            Toast.makeText(this, names, Toast.LENGTH_LONG).show()
            if (soundOn) sounds.playWin()
        }
        saveGameToDisk()
    }

    private fun registerPlayAchievements(size: Int) {
        achievements.recordGame(
            Achievements.GameResult(size, won = false, 0, 0, usedUndo = false, dailyStreak = 0)
        )
    }

    private fun saveGameToDisk() {
        val view = binding.puzzleView
        val engine = view.engine ?: return
        persistence.save(
            engine.size, engine.positions, engine.moves, elapsedSeconds,
            isGameInProgress, view.isAssisted, dailyActive, dailyDay, usedUndoThisGame
        )
    }

    private fun updateSizeButton() {
        binding.btnSize.text = getString(R.string.size_button, boardSize, boardSize)
    }

    private fun updateSoundButton() {
        binding.btnSound.text =
            if (soundOn) getString(R.string.sound_on) else getString(R.string.sound_off)
    }

    private fun updateDailyButton() {
        binding.btnDaily.text =
            if (store.isDailyDone(todayEpochDay())) {
                getString(R.string.daily_button_done)
            } else getString(R.string.daily_button)
    }

    // ---------- Daily challenge ----------

    private fun todayEpochDay(): Long = System.currentTimeMillis() / DAY_MS

    private fun dailySizeFor(day: Long): Int =
        (PuzzleEngine.MIN_SIZE + (day % 6)).toInt()

    private fun onDailyClicked() {
        val today = todayEpochDay()
        if (store.isDailyDone(today)) {
            Toast.makeText(this, R.string.daily_done_toast, Toast.LENGTH_SHORT).show()
            return
        }
        val size = dailySizeFor(today)
        val streak = store.currentStreak()
        val message = getString(R.string.daily_available_format, size, size) +
                if (streak > 0) getString(R.string.daily_streak_format, streak) else ""
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.daily_challenge)
            .setMessage(message)
            .setPositiveButton(R.string.solve_play) { _, _ -> startDailyGame(today, size) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startDailyGame(day: Long, size: Int) {
        dailyActive = true
        dailyDay = day
        isGameInProgress = false
        stopTimer()
        elapsedSeconds = 0
        usedUndoThisGame = false
        store.addPlay(size)
        registerPlayAchievements(size)
        binding.timerText.text = formatTime(0)
        binding.movesText.text = getString(R.string.moves_format, 0)
        binding.puzzleView.newGame(vm.masterBitmap, size, seed = day * 31 + size)
        binding.statusText.text = getString(R.string.daily_started_format, size, size)
    }

    // ---------- Auto-solve & hint ----------

    private fun onAutoSolveClicked() {
        val engine = binding.puzzleView.engine ?: return
        if (engine.isSolved || binding.puzzleView.isAutoSolving) return
        if (engine.size > PuzzleSolver.MAX_AUTOSOLVE_SIZE) {
            Toast.makeText(this, R.string.hint_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.solve_confirm_title)
            .setMessage(getString(R.string.solve_confirm_msg, engine.size))
            .setPositiveButton(R.string.solve_play) { _, _ -> computeAndPlaySolution() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun computeAndPlaySolution() {
        val engine = binding.puzzleView.engine ?: return
        binding.btnSolve.isEnabled = false
        lifecycleScope.launch {
            val path = withContext(Dispatchers.Default) {
                PuzzleSolver.solve(engine.positions.copyOf(), engine.size)   // optimal
            }
            binding.btnSolve.isEnabled = true
            if (path.isNullOrEmpty()) {
                Toast.makeText(this@MainActivity, R.string.hint_failed, Toast.LENGTH_SHORT).show()
            } else {
                binding.puzzleView.playAutoSolution(path)
            }
        }
    }

    private fun onHintClicked() {
        val engine = binding.puzzleView.engine ?: return
        if (engine.isSolved || binding.puzzleView.isAutoSolving) return
        if (engine.size > PuzzleSolver.MAX_HINT_SIZE) {
            Toast.makeText(this, R.string.hint_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnHint.isEnabled = false
        lifecycleScope.launch {
            val path = withContext(Dispatchers.Default) {
                PuzzleSolver.solve(
                    engine.positions.copyOf(),
                    engine.size,
                    maxNodes = 4_000_000L,
                    timeBudgetMs = 2_500L,
                    weight = if (engine.size >= 5) 2.0 else 1.0
                )
            }
            binding.btnHint.isEnabled = true
            val cell = path?.firstOrNull()
            if (cell == null) {
                Toast.makeText(this@MainActivity, R.string.hint_failed, Toast.LENGTH_SHORT).show()
            } else {
                binding.puzzleView.applyMove(cell)
            }
        }
    }

    private fun onUndoClicked() {
        if (binding.puzzleView.undo()) {
            usedUndoThisGame = true
            val moves = binding.puzzleView.engine?.moves ?: 0
            binding.movesText.text = getString(R.string.moves_format, moves)
            if (soundOn) sounds.playSlide()
        }
    }

    // ---------- Share ----------

    private fun onShareClicked() {
        val text = lastSolveSummary ?: run {
            val size = binding.puzzleView.engine?.size ?: boardSize
            val best = store.recordFor(size)
            if (best != null) {
                getString(R.string.share_best_format, size, best.moves, formatTime(best.seconds))
            } else getString(R.string.share_invite)
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
    }

    // ---------- Size dialog ----------

    private fun showSizeDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_board_size, null)
        val slider = view.findViewById<Slider>(R.id.sizeSlider)
        val label = view.findViewById<TextView>(R.id.sizeLabel)

        slider.value = boardSize.toFloat()
        label.text = getString(R.string.size_button, boardSize, boardSize)
        slider.addOnChangeListener { _, value, _ ->
            label.text = getString(R.string.size_button, value.toInt(), value.toInt())
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.size_dialog_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newSize = slider.value.toInt()
                if (newSize != boardSize) {
                    boardSize = newSize
                    store.lastBoardSize = newSize
                    updateSizeButton()
                    startNewGame()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- Image loading ----------

    private fun onImagePicked(uri: Uri) {
        binding.progressBar.isVisible = true
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                decodeMaster(uri)?.also { persistence.savePhoto(it) }
            }
            binding.progressBar.isVisible = false
            if (bitmap == null) {
                Toast.makeText(this@MainActivity, R.string.load_failed, Toast.LENGTH_SHORT).show()
            } else {
                vm.masterBitmap = bitmap
                binding.puzzleView.setBitmap(bitmap)
                startNewGame()
            }
        }
    }

    private fun loadMasterAsync(onReady: (Bitmap) -> Unit = {}) {
        vm.masterBitmap?.let {
            binding.progressBar.isVisible = false
            onReady(it)
            return
        }
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                persistence.loadPhoto()
                    ?: createDefaultBitmap(MASTER_SIZE).also { persistence.savePhoto(it) }
            }
            vm.masterBitmap = bitmap
            binding.progressBar.isVisible = false
            binding.puzzleView.setBitmap(bitmap)
            onReady(bitmap)
        }
    }

    private fun decodeMaster(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        var inSampleSize = 1
        while (bounds.outWidth / (inSampleSize * 2) >= MASTER_SIZE &&
            bounds.outHeight / (inSampleSize * 2) >= MASTER_SIZE
        ) inSampleSize *= 2

        val options = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        val decoded = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val upright = rotateForExif(decoded, uri)
        val side = min(upright.width, upright.height)
        val square = Bitmap.createBitmap(
            upright,
            (upright.width - side) / 2,
            (upright.height - side) / 2,
            side, side
        )
        Bitmap.createScaledBitmap(square, MASTER_SIZE, MASTER_SIZE, true)
    } catch (e: Exception) {
        null
    }

    private fun rotateForExif(bitmap: Bitmap, uri: Uri): Bitmap {
        val orientation = contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun createCameraUri(): Uri {
        val dir = File(cacheDir, "images").apply { mkdirs() }
        val file = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }

    private fun createDefaultBitmap(target: Int): Bitmap {
        val side = target
        val s = side / 1024f
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, side.toFloat(), side.toFloat(),
                Color.parseColor("#6A11CB"), Color.parseColor("#2575FC"),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, side.toFloat(), side.toFloat(), paint)

        paint.shader = null
        paint.color = Color.WHITE
        paint.alpha = 45
        val random = java.util.Random(7)
        repeat(14) {
            canvas.drawCircle(
                random.nextInt(side).toFloat(),
                random.nextInt(side).toFloat(),
                (70 + random.nextInt(170)) * s,
                paint
            )
        }
        paint.alpha = 230
        paint.textSize = 130f * s
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("SLIDE IT!", side / 2f, side / 2f, paint)
        return bitmap
    }

    // ---------- Timer ----------

    private fun startTimer() {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, 1_000)
    }

    private fun stopTimer() {
        handler.removeCallbacks(tickRunnable)
    }

    private fun formatTime(seconds: Int): String =
        String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)

    companion object {
        private const val MASTER_SIZE = 2048
        private const val DAY_MS = 86_400_000L
        private const val KEY_SIZE = "size"
        private const val KEY_POSITIONS = "positions"
        private const val KEY_MOVES = "moves"
        private const val KEY_ELAPSED = "elapsed"
        private const val KEY_IN_PROGRESS = "in_progress"
        private const val KEY_ASSISTED = "assisted"
        private const val KEY_DAILY_ACTIVE = "daily_active"
        private const val KEY_DAILY_DAY = "daily_day"
        private const val KEY_USED_UNDO = "used_undo"
        const val ACTION_DAILY = "com.example.slidepuzzle.action.DAILY"
        const val ACTION_QUICK3 = "com.example.slidepuzzle.action.QUICK3"
    }
}
