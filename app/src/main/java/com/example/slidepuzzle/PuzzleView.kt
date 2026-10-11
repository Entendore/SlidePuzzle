package com.example.slidepuzzle

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

class PuzzleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onMove(moves: Int)
        /** [assisted] is true when the solve came from the auto-solver. */
        fun onSolved(moves: Int, assisted: Boolean)
    }

    var listener: Listener? = null

    var showNumbers = true
        set(value) {
            field = value
            invalidate()
        }

    var engine: PuzzleEngine? = null
        private set

    /** True once the auto-solver has been used in this game. */
    var isAssisted = false
        private set

    val isAutoSolving: Boolean get() = autoPath != null

    // ---------------- Bitmap ----------------
    private var sourceBitmap: Bitmap? = null
    private var croppedFor: Bitmap? = null

    // ---------------- Geometry (world = unzoomed coordinates) ----------------
    private var boardLeft = 0f
    private var boardTop = 0f
    private var boardSide = 0f
    private var tileSize = 0f

    // ---------------- Zoom & pan ----------------
    var scale = 1f
        private set
    private var transX = 0f
    private var transY = 0f
    private var isPinching = false
    private var pinchLastSpan = 0f
    private var pinchLastX = 0f
    private var pinchLastY = 0f
    private var ignoreUntilUp = false

    // ---------------- Drag-to-slide ----------------
    private var dragActive = false
    private var dragCell = -1
    private var dragAxis = 0
    private var dragDir = 0
    private var dragOffset = 0f
    private var dragSegment: List<Int> = emptyList()
    private var snapBackAnimator: ValueAnimator? = null

    // ---------------- Slide animation ----------------
    private var animSlides: List<Pair<Int, Int>> = emptyList()
    private var animFraction = 0f
    private var animator: ValueAnimator? = null
    private var pendingSolve = false
    private var isSolved = false

    // ---------------- Auto-solve ----------------
    private var autoPath: List<Int>? = null
    private var autoIndex = 0
    private val autoStepRunnable = Runnable { stepAutoSolve() }

    // ---------------- Ghost peek ----------------
    private var ghostAlpha = 0f
    private var ghostAnimator: ValueAnimator? = null

    // ---------------- Confetti ----------------
    private class Confetti(
        val x0: Float, val y0: Float, val vx: Float, val vy: Float,
        val size: Float, val color: Int, val spin: Float, val delay: Float
    )

    private var confetti: Array<Confetti> = emptyArray()
    private var confettiTime = 0f
    private var confettiAnimator: ValueAnimator? = null

    // ---------------- Paints ----------------
    private val density = resources.displayMetrics.density
    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B1B26.toInt() }
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val confettiPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val correctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x9900E676.toInt()
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 2f, 0xAA000000.toInt())
    }
    private val solvedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        color = 0xFF4CAF50.toInt()
    }
    private val srcRect = Rect()
    private val destRect = RectF()

    // ---------------- Gestures ----------------
    private val gestureDetector = GestureDetector(context, GestureListener())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downCell = -1
    private var longPressed = false
    private var suppressTap = false

    // ---------------- Accessibility ----------------
    private val a11yHelper = PuzzleA11yHelper()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        ViewCompat.setAccessibilityDelegate(this, a11yHelper)
    }

    // ---------------- Game control ----------------

    /** Starts a new game. [seed] makes the scramble deterministic (daily challenge). */
    fun newGame(bitmap: Bitmap?, size: Int, seed: Long? = null) {
        cancelAllAnimators()
        engine = PuzzleEngine(size.coerceIn(PuzzleEngine.MIN_SIZE, PuzzleEngine.MAX_SIZE)).apply {
            if (seed != null) shuffle(Random(seed)) else shuffle()
        }
        setBitmap(bitmap)
        isSolved = false
        isAssisted = false
        pendingSolve = false
        animSlides = emptyList()
        resetCamera()
        invalidate()
    }

    fun restoreGame(size: Int, positions: IntArray, moves: Int) {
        cancelAllAnimators()
        engine = PuzzleEngine(size).also { it.restore(positions, moves) }
        isSolved = engine?.isSolved == true
        isAssisted = false
        pendingSolve = false
        animSlides = emptyList()
        resetCamera()
        invalidate()
    }

    fun setBitmap(bitmap: Bitmap?) {
        if (bitmap == null) {
            sourceBitmap = null
            croppedFor = null
        } else if (bitmap !== croppedFor) {
            sourceBitmap = centerCropSquare(bitmap)
            croppedFor = bitmap
        }
        invalidate()
    }

    /** Undo the last move. Blocked while the auto-solver is running. */
    fun undo(): Boolean {
        val e = engine ?: return false
        if (isSolved || isAutoSolving) return false
        animator?.cancel(); animator = null
        animSlides = emptyList()
        pendingSolve = false
        snapBackAnimator?.cancel(); snapBackAnimator = null
        clearDragState()
        val undone = e.undo()
        if (undone) invalidate()
        return undone
    }

    /** Re-flags the game as auto-solved after a process-death restore. */
    fun markAssisted(assisted: Boolean) {
        isAssisted = assisted
    }

    /** Public entry point for hint moves. */
    fun applyMove(cell: Int): Boolean = performMove(cell)

    /** Animates the whole optimal plan, one move at a time. */
    fun playAutoSolution(path: List<Int>) {
        val e = engine ?: return
        if (isSolved || isAutoSolving || path.isEmpty()) return
        cancelAllAnimators()
        isAssisted = true
        autoPath = path
        autoIndex = 0
        postDelayed(autoStepRunnable, AUTO_STEP_DELAY_MS)
    }

    private fun stepAutoSolve() {
        val path = autoPath ?: return
        if (autoIndex >= path.size) {
            autoPath = null
            return
        }
        val cell = path[autoIndex++]
        if (!performMove(cell, fromAuto = true)) autoPath = null
    }

    private fun resetCamera() {
        scale = 1f
        transX = 0f
        transY = 0f
        isPinching = false
        ignoreUntilUp = false
    }

    private fun cancelAllAnimators() {
        animator?.cancel(); animator = null
        snapBackAnimator?.cancel(); snapBackAnimator = null
        ghostAnimator?.cancel(); ghostAnimator = null
        confettiAnimator?.cancel(); confettiAnimator = null
        confetti = emptyArray()
        removeCallbacks(autoStepRunnable)
        autoPath = null
    }

    private fun centerCropSquare(source: Bitmap): Bitmap {
        val side = min(source.width, source.height)
        return Bitmap.createBitmap(
            source,
            (source.width - side) / 2,
            (source.height - side) / 2,
            side, side
        )
    }

    // ---------------- Moving tiles ----------------

    private fun performMove(cell: Int, animate: Boolean = true, fromAuto: Boolean = false): Boolean {
        val e = engine ?: return false
        if (isSolved || animator != null) return false
        if (isAutoSolving && !fromAuto) return false
        val slides = e.moveTilesAt(cell) ?: return false

        pendingSolve = e.isSolved
        listener?.onMove(e.moves)
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

        if (animate) {
            animSlides = slides
            startSlideAnimation(slides.size)
        } else if (pendingSolve) {
            pendingSolve = false
            finishSolve(e)
        }
        invalidate()
        return true
    }

    private fun finishSolve(e: PuzzleEngine) {
        isSolved = true
        autoPath = null
        spawnConfetti()
        listener?.onSolved(e.moves, isAssisted)
    }

    private fun startSlideAnimation(tileCount: Int) {
        animator?.cancel()
        animFraction = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (tileCount > 1) 170L else 130L
            addUpdateListener { animation ->
                animFraction = animation.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    animSlides = emptyList()
                    if (pendingSolve) {
                        pendingSolve = false
                        engine?.let { finishSolve(it) }
                    } else if (isAutoSolving) {
                        postDelayed(autoStepRunnable, AUTO_STEP_DELAY_MS)
                    }
                    invalidate()
                }
            })
            start()
        }
    }

    // ---------------- Input: touch ----------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isAutoSolving) return true   // input locked while the demo plays
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snapBackAnimator?.cancel(); snapBackAnimator = null
                clearDragState()
                downX = event.x
                downY = event.y
                downCell = gridPositionAt(worldX(downX), worldY(downY))
                longPressed = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2 && !isPinching) {
                    finishDrag(commit = false)
                    startPinch(event)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPinching) {
                    updatePinch(event)
                } else if (!ignoreUntilUp && !longPressed) {
                    updateDrag(event)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount == 2) {
                    isPinching = false
                    ignoreUntilUp = true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isPinching) {
                    isPinching = false
                } else if (dragActive) {
                    finishDrag(commit = dragOffset > tileSize * 0.5f)
                } else if (!longPressed && !ignoreUntilUp && !suppressTap && downCell != -1) {
                    if (performMove(downCell)) performClick()
                }
                ignoreUntilUp = false
                longPressed = false
                suppressTap = false
                downCell = -1
                animateGhostTo(0f)
            }
            MotionEvent.ACTION_CANCEL -> {
                isPinching = false
                finishDrag(commit = false)
                ignoreUntilUp = false
                longPressed = false
                suppressTap = false
                downCell = -1
                animateGhostTo(0f)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ---------------- Input: keyboard / gamepad ----------------

    /** Arrow keys/WASD slide the tile on that side of the blank into the blank. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val e = engine
        if (e != null && !isSolved && !isAutoSolving) {
            val n = e.size
            val blank = e.blankPosition()
            val cell = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W ->
                    if (blank / n < n - 1) blank + n else -1
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S ->
                    if (blank / n > 0) blank - n else -1
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A ->
                    if (blank % n < n - 1) blank + 1 else -1
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D ->
                    if (blank % n > 0) blank - 1 else -1
                else -> -1
            }
            if (cell != -1 && performMove(cell)) {
                performClick()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun worldX(x: Float) = (x - transX) / scale
    private fun worldY(y: Float) = (y - transY) / scale

    // -------- drag-to-slide --------

    private fun updateDrag(event: MotionEvent) {
        val e = engine ?: return
        if (isSolved || animator != null) return
        val dx = event.x - downX
        val dy = event.y - downY

        if (!dragActive) {
            if (dx * dx + dy * dy < touchSlop * touchSlop) return
            if (downCell == -1 || downCell == e.blankPosition()) return
            val blank = e.blankPosition()
            val sameRow = downCell / e.size == blank / e.size
            val sameCol = downCell % e.size == blank % e.size
            when {
                abs(dx) >= abs(dy) && sameRow -> {
                    val dir = if (blank > downCell) 1 else -1
                    if (dx * dir > 0) lockDrag(downCell, AXIS_H, dir, e)
                }
                abs(dy) > abs(dx) && sameCol -> {
                    val dir = if (blank > downCell) 1 else -1
                    if (dy * dir > 0) lockDrag(downCell, AXIS_V, dir, e)
                }
            }
        } else {
            val projection = if (dragAxis == AXIS_H) dx else dy
            dragOffset = ((projection * dragDir) / scale).coerceIn(0f, tileSize)
            invalidate()
        }
    }

    private fun lockDrag(cell: Int, axis: Int, dir: Int, e: PuzzleEngine) {
        dragActive = true
        dragCell = cell
        dragAxis = axis
        dragDir = dir
        dragOffset = 0f
        val blank = e.blankPosition()
        val segment = ArrayList<Int>()
        var c = cell
        while (c != blank) {
            segment.add(c)
            c += if (axis == AXIS_H) dir else dir * e.size
        }
        dragSegment = segment
        invalidate()
    }

    private fun finishDrag(commit: Boolean) {
        if (!dragActive) return
        if (commit && dragCell != -1) {
            val cell = dragCell
            clearDragState()
            performMove(cell, animate = false)
        } else {
            val from = dragOffset
            snapBackAnimator = ValueAnimator.ofFloat(from, 0f).apply {
                duration = 90L
                addUpdateListener { animation ->
                    dragOffset = animation.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        snapBackAnimator = null
                        clearDragState()
                        invalidate()
                    }
                })
                start()
            }
        }
    }

    private fun clearDragState() {
        dragActive = false
        dragCell = -1
        dragAxis = 0
        dragDir = 0
        dragOffset = 0f
        dragSegment = emptyList()
    }

    // -------- pinch zoom & pan --------

    private fun startPinch(event: MotionEvent) {
        isPinching = true
        pinchLastSpan = spanOf(event)
        pinchLastX = (event.getX(0) + event.getX(1)) / 2f
        pinchLastY = (event.getY(0) + event.getY(1)) / 2f
    }

    private fun spanOf(event: MotionEvent): Float =
        hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

    private fun updatePinch(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val span = spanOf(event)
        val midX = (event.getX(0) + event.getX(1)) / 2f
        val midY = (event.getY(0) + event.getY(1)) / 2f
        if (pinchLastSpan > 0f && span > 0f) {
            val newScale = (scale * span / pinchLastSpan).coerceIn(1f, MAX_SCALE)
            val wx = (midX - transX) / scale
            val wy = (midY - transY) / scale
            scale = newScale
            transX = midX - wx * scale
            transY = midY - wy * scale
            transX += midX - pinchLastX
            transY += midY - pinchLastY
            clampPan()
            invalidate()
        }
        pinchLastSpan = span
        pinchLastX = midX
        pinchLastY = midY
    }

    private fun clampPan() {
        if (engine == null) return
        computeGeometry()
        val scaledSide = boardSide * scale
        transX = if (scaledSide <= width) {
            (width - scaledSide) / 2f - boardLeft * scale
        } else {
            transX.coerceIn(width - (boardLeft + boardSide) * scale, -boardLeft * scale)
        }
        transY = if (scaledSide <= height) {
            (height - scaledSide) / 2f - boardTop * scale
        } else {
            transY.coerceIn(height - (boardTop + boardSide) * scale, -boardTop * scale)
        }
    }

    // -------- misc gestures --------

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            suppressTap = true
            resetCamera()
            invalidate()
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (dragActive) finishDrag(commit = false)
            longPressed = true
            animateGhostTo(1f)
        }
    }

    private fun animateGhostTo(target: Float) {
        if (ghostAlpha == target) return
        ghostAnimator?.cancel()
        ghostAnimator = ValueAnimator.ofFloat(ghostAlpha, target).apply {
            duration = 180
            addUpdateListener { animation ->
                ghostAlpha = animation.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun gridPositionAt(worldX: Float, worldY: Float): Int {
        val e = engine ?: return -1
        if (tileSize <= 0f) return -1
        val col = ((worldX - boardLeft) / tileSize).toInt()
        val row = ((worldY - boardTop) / tileSize).toInt()
        return if (col in 0 until e.size && row in 0 until e.size) row * e.size + col else -1
    }

    // ---------------- Accessibility ----------------

    /**
     * Exposes every cell as a focusable node: "Tile 7 — in place" or
     * "Empty cell". Activating a node attempts the move.
     */
    private inner class PuzzleA11yHelper : ExploreByTouchHelper(this@PuzzleView) {

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val cell = gridPositionAt(worldX(x), worldY(y))
            return if (cell == -1) INVALID_ID else cell
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            val e = engine ?: return
            for (i in 0 until e.tileCount) virtualViewIds.add(i)
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat
        ) {
            val e = engine
            if (e == null) {
                node.contentDescription = context.getString(R.string.app_name)
                node.setBoundsInScreen(Rect())
                return
            }
            val tile = e.positions.getOrNull(virtualViewId)
            node.className = Button::class.java.name
            node.contentDescription = when {
                tile == null || tile == e.blankTileId ->
                    context.getString(R.string.a11y_empty)
                else -> context.getString(R.string.a11y_tile_format, tile + 1) +
                        if (tile == virtualViewId) {
                            context.getString(R.string.a11y_correct_suffix)
                        } else ""
            }
            node.isClickable = true

            // Bounds in screen coordinates (view offset + camera transform)
            computeGeometry()
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val left = loc[0] + transX + (boardLeft + (virtualViewId % e.size) * tileSize) * scale
            val top = loc[1] + transY + (boardTop + (virtualViewId / e.size) * tileSize) * scale
            val side = tileSize * scale
            node.setBoundsInScreen(
                Rect(left.toInt(), top.toInt(), (left + side).toInt(), (top + side).toInt())
            )
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?
        ): Boolean {
            if (action == AccessibilityNodeInfoCompat.ACTION_CLICK && performMove(virtualViewId)) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                return true
            }
            this@PuzzleView.announceForAccessibility(context.getString(R.string.a11y_cant_move))
            return false
        }
    }

    // ---------------- Confetti ----------------

    private fun spawnConfetti() {
        if (boardSide <= 0f) return
        val rnd = Random(System.nanoTime())
        val colors = intArrayOf(
            0xFFEF5350.toInt(), 0xFFFFCA28.toInt(), 0xFF66BB6A.toInt(),
            0xFF42A5F5.toInt(), 0xFFAB47BC.toInt(), 0xFFFF7043.toInt(),
            0xFF26C6DA.toInt()
        )
        confetti = Array(150) {
            Confetti(
                x0 = boardLeft + rnd.nextFloat() * boardSide,
                y0 = boardTop - 20f * density - rnd.nextFloat() * 120f * density,
                vx = (rnd.nextFloat() - 0.5f) * 220f * density,
                vy = (80f + rnd.nextFloat() * 160f) * density,
                size = (4f + rnd.nextFloat() * 6f) * density,
                color = colors[rnd.nextInt(colors.size)],
                spin = (rnd.nextFloat() - 0.5f) * 900f,
                delay = rnd.nextFloat() * 0.6f
            )
        }
        confettiTime = 0f
        confettiAnimator?.cancel()
        confettiAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CONFETTI_DURATION
            addUpdateListener { animation ->
                confettiTime = (animation.animatedValue as Float) * (CONFETTI_DURATION / 1000f)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    confetti = emptyArray()
                    invalidate()
                }
            })
            start()
        }
    }

    private fun drawConfetti(canvas: Canvas) {
        if (confetti.isEmpty()) return
        val gravity = 1400f * density
        val total = CONFETTI_DURATION / 1000f
        for (p in confetti) {
            val t = confettiTime - p.delay
            if (t <= 0f) continue
            val x = p.x0 + p.vx * t
            val y = p.y0 + p.vy * t + 0.5f * gravity * t * t
            if (y > boardTop + boardSide + 60f * density) continue
            confettiPaint.color = p.color
            confettiPaint.alpha = (((total - confettiTime) / 0.6f).coerceIn(0f, 1f) * 255).toInt()
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(p.spin * t)
            canvas.drawRect(-p.size / 2f, -p.size / 2f, p.size / 2f, p.size / 2f, confettiPaint)
            canvas.restore()
        }
    }

    // ---------------- Geometry & drawing ----------------

    private fun computeGeometry() {
        val e = engine ?: return
        val viewSide = min(width, height).toFloat()
        if (viewSide <= 0f) return
        val outerPadding = viewSide * 0.02f
        boardSide = viewSide - outerPadding * 2
        boardLeft = (width - boardSide) / 2f
        boardTop = (height - boardSide) / 2f
        tileSize = boardSide / e.size
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeGeometry()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val e = engine ?: return
        computeGeometry()
        val n = e.size
        if (boardSide <= 0f) return

        canvas.save()
        canvas.translate(transX, transY)
        canvas.scale(scale, scale)

        canvas.drawRoundRect(
            boardLeft, boardTop, boardLeft + boardSide, boardTop + boardSide,
            18f, 18f, boardPaint
        )

        val bitmap = sourceBitmap

        if (isSolved) {
            if (bitmap != null) {
                destRect.set(boardLeft, boardTop, boardLeft + boardSide, boardTop + boardSide)
                canvas.drawBitmap(bitmap, null, destRect, tilePaint)
            } else {
                for (position in 0 until n * n) drawFallbackTile(canvas, e, position)
            }
            canvas.drawRoundRect(
                boardLeft, boardTop, boardLeft + boardSide, boardTop + boardSide,
                18f, 18f, solvedPaint
            )
            drawConfetti(canvas)
            canvas.restore()
            return
        }

        val moving = IntArray(n * n) { -1 }
        for ((from, to) in animSlides) moving[to] = from

        val numberFraction = when {
            n <= 5 -> 0.22f
            n <= 10 -> 0.36f
            else -> 0.46f
        }

        for (position in 0 until n * n) {
            val tileId = e.positions[position]
            if (tileId == e.blankTileId) continue

            val inset = tileInset()
            val size = tileSize - inset * 2
            var left = boardLeft + (position % n) * tileSize + inset
            var top = boardTop + (position / n) * tileSize + inset

            val from = moving[position]
            if (from != -1 && animator != null) {
                val fromLeft = boardLeft + (from % n) * tileSize + inset
                val fromTop = boardTop + (from / n) * tileSize + inset
                left = fromLeft + (left - fromLeft) * animFraction
                top = fromTop + (top - fromTop) * animFraction
            } else if (dragActive && position in dragSegment) {
                if (dragAxis == AXIS_H) left += dragDir * dragOffset
                else top += dragDir * dragOffset
            }

            destRect.set(left, top, left + size, top + size)

            if (bitmap != null) {
                val col = tileId % n
                val row = tileId / n
                srcRect.set(
                    col * bitmap.width / n,
                    row * bitmap.height / n,
                    (col + 1) * bitmap.width / n,
                    (row + 1) * bitmap.height / n
                )
                canvas.drawBitmap(bitmap, srcRect, destRect, tilePaint)
            } else {
                fallbackPaint.color = fallbackColor(tileId)
                canvas.drawRoundRect(destRect, 10f, 10f, fallbackPaint)
            }

            if (tileId == position) {
                correctPaint.strokeWidth = maxOf(2f, tileSize * 0.035f)
                canvas.drawRoundRect(destRect, 10f, 10f, correctPaint)
            }

            if (showNumbers) {
                numberPaint.textSize = size * numberFraction
                val centerX = destRect.centerX()
                val centerY = destRect.centerY() -
                        (numberPaint.descent() + numberPaint.ascent()) / 2f
                canvas.drawText((tileId + 1).toString(), centerX, centerY, numberPaint)
            }
        }

        if (ghostAlpha > 0f && bitmap != null) {
            destRect.set(boardLeft, boardTop, boardLeft + boardSide, boardTop + boardSide)
            ghostPaint.alpha = (ghostAlpha * 165).toInt()
            canvas.drawBitmap(bitmap, null, destRect, ghostPaint)
        }

        drawConfetti(canvas)
        canvas.restore()
    }

    private fun drawFallbackTile(canvas: Canvas, engine: PuzzleEngine, position: Int) {
        val n = engine.size
        val inset = tileInset()
        val size = tileSize - inset * 2
        val left = boardLeft + (position % n) * tileSize + inset
        val top = boardTop + (position / n) * tileSize + inset
        destRect.set(left, top, left + size, top + size)
        fallbackPaint.color = fallbackColor(engine.positions[position])
        canvas.drawRoundRect(destRect, 10f, 10f, fallbackPaint)
    }

    private fun tileInset(): Float = maxOf(1f, tileSize * 0.02f)

    private fun fallbackColor(tileId: Int): Int =
        Color.HSVToColor(floatArrayOf((tileId * 47f) % 360f, 0.55f, 0.85f))

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAllAnimators()
    }

    companion object {
        private const val AXIS_H = 1
        private const val AXIS_V = 2
        private const val MAX_SCALE = 4f
        private const val CONFETTI_DURATION = 3200L
        private const val AUTO_STEP_DELAY_MS = 220L
    }
}
