/*
 * Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gimirick.circletosearch.ui.components

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.*
import android.net.Uri
import android.view.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
/** Simple holder for a floating-toolbar button's label and screen hit-rect. */
private class ToolbarButton(
    val label: String,
    val baseRect: Rect,
    val hitRect: Rect = Rect(),
)

private data class ToolbarTouchTarget(
    val button: ToolbarButton? = null,
    val isDragHandle: Boolean = false,
)

/**
 * Manages the dim+punch-out Copy Text overlay with OCR capabilities.
 */
class CopyTextOverlayManager(
    private val context: Context,
    private val screenshotBitmap: android.graphics.Bitmap?,
) {
    private var dimView: DimPunchOutView? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val disposed = AtomicBoolean(false)
    private var onDismissCallback: (() -> Unit)? = null
    private var onBackgroundTouchCallback: ((Int, Float, Float) -> Unit)? = null
    private var onAnalysisCompleteCallback: ((Int) -> Unit)? = null

    private val statusMessage = mutableStateOf<String?>(null)
    private val textNodes = mutableListOf<TextNode>()
    private var visualLines: List<List<Word>> = emptyList()
    private var allWords: List<Word> = emptyList()

    /**
     * Sets a callback that is invoked after the analysis is complete.
     * @param callback receives the number of text nodes found.
     */
    fun setOnAnalysisComplete(callback: (Int) -> Unit) {
        onAnalysisCompleteCallback = callback
    }

    /**
     * Starts text analysis. Called automatically on startup.
     */
    fun startAnalysis() {
        // The Compose screen owns the single OCR pass and publishes nodes via updateNodes().
    }

    /**
     * Returns the number of found text nodes.
     */
    fun getNodeCount(): Int = textNodes.size

    /**
     * Checks if scanning is currently in progress.
     */
    fun isScanning(): Boolean = false

    private fun updateAllWords() {
        val layout = visualTextLayout(textNodes)
        visualLines = layout.lines
        allWords = layout.words
    }

    /** Publishes the screen-owned OCR result without starting a duplicate native scan. */
    fun updateNodes(nodes: List<TextNode>) {
        if (disposed.get()) return
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            scope.launch { applyNodes(nodes) }
        } else {
            applyNodes(nodes)
        }
    }

    private fun applyNodes(nodes: List<TextNode>) {
        if (disposed.get()) return
        val hadActiveSelection = hasActiveSelection()
        val oldSelectionStart = globalSelectionStart
        val oldSelectionEnd = globalSelectionEnd
        val startAnchor = allWords.getOrNull(oldSelectionStart)?.toSelectionAnchor()
        val endAnchor = allWords.getOrNull(oldSelectionEnd)?.toSelectionAnchor()

        val sortedNodes = nodes.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        textNodes.clear()
        textNodes.addAll(sortedNodes)
        updateAllWords()

        if (hadActiveSelection && startAnchor != null && endAnchor != null) {
            val remappedStart = findSelectionAnchorMatch(startAnchor, allWords)
            val remappedEnd =
                if (oldSelectionStart == oldSelectionEnd) remappedStart
                else findSelectionAnchorMatch(endAnchor, allWords)

            if (remappedStart in allWords.indices && remappedEnd in allWords.indices) {
                globalSelectionStart = remappedStart
                globalSelectionEnd = remappedEnd
                hasActiveTextSelection.value = true
            } else {
                clearSelection()
            }
        } else if (globalSelectionStart != -1 || globalSelectionEnd != -1) {
            clearSelection()
        }
        onAnalysisCompleteCallback?.invoke(textNodes.size)
        dimView?.refreshWordsChanged()
    }
    
    // Selection state
    val hasActiveTextSelection = mutableStateOf(false)
    private var globalSelectionStart: Int = -1
    private var globalSelectionEnd: Int = -1

    fun hasActiveSelection(): Boolean =
        globalSelectionStart in allWords.indices &&
            globalSelectionEnd in allWords.indices

    fun clearSelection() {
        val hadSelectionState =
            globalSelectionStart != -1 || globalSelectionEnd != -1
        globalSelectionStart = -1
        globalSelectionEnd = -1
        hasActiveTextSelection.value = false
        if (hadSelectionState) {
            dimView?.resetSelectionUi()
        }
    }

    fun getOverlayView(
        onDismiss: () -> Unit,
        onBackgroundTouch: (action: Int, x: Float, y: Float) -> Unit = { _, _, _ -> },
    ): View {
        onDismissCallback = onDismiss
        onBackgroundTouchCallback = onBackgroundTouch
        
        // Reset interactive state
        clearSelection()
        
        val container = FrameLayout(context)
        
        val view = DimPunchOutView(context)
        dimView = view
        container.addView(view)
        
        val topBar = ComposeView(context).apply {
            setContent {
                MaterialTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        TopBarUI(onClose = ::dismiss)

                        statusMessage.value?.let { msg ->
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 100.dp)
                                    .background(ComposeColor.Black.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                                    .padding(horizontal = 20.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    msg,
                                    color = ComposeColor.White,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
        container.addView(topBar)

        return container
    }

    @Composable
    private fun TopBarUI(onClose: () -> Unit) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .background(ComposeColor.Black.copy(alpha = 0.35f), CircleShape)
                    .size(40.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Exit Copy Mode", tint = ComposeColor.White)
            }
        }
    }

    /**
     * Handles an explicit user close action. The owner callback is invoked at most once.
     * Lifecycle teardown and manager replacement must call [disposeSilently] instead.
     */
    fun dismiss() {
        dispose(notifyOwner = true)
    }

    /**
     * Releases this manager without treating lifecycle teardown as a user close request.
     * This method is idempotent and never invokes the callback passed to [getOverlayView].
     */
    fun disposeSilently() {
        dispose(notifyOwner = false)
    }

    private fun dispose(notifyOwner: Boolean) {
        if (!disposed.compareAndSet(false, true)) return
        scope.cancel()
        dimView = null
        val callback = onDismissCallback.takeIf { notifyOwner }
        onDismissCallback = null
        onBackgroundTouchCallback = null
        onAnalysisCompleteCallback = null
        callback?.invoke()
    }

    fun rescanNodes() {
        // The captured bitmap is immutable; accessibility scroll events do not change it.
        dimView?.invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class DimPunchOutView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

        private val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        // Material 3 theme palette matching CircleToSearchTheme (Purple/Lavender)
        private val colorSurface = if (isNight) Color.parseColor("#2B2930") else Color.parseColor("#F3EDF7")
        private val colorSurfaceBorder = if (isNight) Color.parseColor("#49454F") else Color.parseColor("#E6E0E9")
        private val colorPrimary = if (isNight) Color.parseColor("#D0BCFF") else Color.parseColor("#6750A4")
        private val colorOnPrimary = if (isNight) Color.parseColor("#381E72") else Color.WHITE
        private val colorSecondaryContainer = if (isNight) Color.parseColor("#4A4458") else Color.parseColor("#E8DEF8")
        private val colorOnSecondaryContainer = if (isNight) Color.parseColor("#E8DEF8") else Color.parseColor("#1D192B")
        private val colorNeutralContainer = if (isNight) Color.parseColor("#36343B") else Color.parseColor("#E6E0E9")
        private val colorOnNeutralContainer = if (isNight) Color.parseColor("#CAC4D0") else Color.parseColor("#49454F")
        private val colorDragHandle = if (isNight) Color.parseColor("#80CAC4D0") else Color.parseColor("#8079747E")
        private val colorSelectionHighlight = if (isNight) Color.parseColor("#4F378B") else Color.parseColor("#D0BCFF")
        private val colorSelectionHandle = colorPrimary

        private val dimPaint = Paint().apply { color = Color.BLACK; alpha = 38; isAntiAlias = false }
        private val selectedWordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorSelectionHighlight
            alpha = if (isNight) 100 else 115
            style = Paint.Style.FILL
        }
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorSelectionHandle
            style = Paint.Style.FILL
        }
        private val toolbarBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorSurface
            style = Paint.Style.FILL
        }
        private val toolbarBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorSurfaceBorder
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
        }
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorSurface
            setShadowLayer(
                8f * density,
                0f,
                3f * density,
                if (isNight) Color.argb(120, 0, 0, 0) else Color.argb(45, 0, 0, 0)
            )
        }
        private val hPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorDragHandle
            style = Paint.Style.FILL
        }
        private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val primaryBtnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorOnPrimary
            style = Paint.Style.FILL
            textSize = 13f * density
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val secondaryBtnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorOnSecondaryContainer
            style = Paint.Style.FILL
            textSize = 13f * density
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        private val neutralBtnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorOnNeutralContainer
            style = Paint.Style.FILL
            textSize = 13f * density
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }

        private fun getButtonColors(label: String): Pair<Int, Paint> {
            return when (label) {
                "Copy" -> Pair(colorPrimary, primaryBtnTextPaint)
                "Cancel" -> Pair(colorNeutralContainer, neutralBtnTextPaint)
                else -> Pair(colorSecondaryContainer, secondaryBtnTextPaint)
            }
        }

        private fun getTextOffset(paint: Paint): Float {
            val metrics = paint.fontMetrics
            return ((metrics.descent - metrics.ascent) / 2f) - metrics.descent
        }

        private var dragHandleType = 0
        private val toolbarButtons = listOf(
            ToolbarButton("Copy", Rect()),
            ToolbarButton("Share", Rect()),
            ToolbarButton("Translate", Rect()),
            ToolbarButton("All", Rect()),
            ToolbarButton("Cancel", Rect()),
        )
        private val toolbarButtonWidths = FloatArray(toolbarButtons.size)
        private val toolbarRect = RectF()
        private val dragHandleRect = RectF()
        private var toolbarOffsetX = 0f
        private var toolbarOffsetY = 0f
        private var toolbarBaseLeft = 0f
        private var toolbarBaseWidth = 0f
        private var toolbarLayoutWidth = 0
        private var isDraggingToolbar = false
        private var toolbarInitialized = false
        private var toolbarDragArmed = false
        private var pendingToolbarAction: String? = null
        private var pendingWordHit = false
        private var toolbarGestureConsumed = false
        private var backgroundGestureActive = false
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        
        // Paths and scratch geometry are rebuilt only when words, selection, or size changes.
        private val tempRect = RectF()
        private val tempLineRect = RectF()
        private val tempBtnRect = RectF()
        private val touchStartRect = RectF()
        private val touchEndRect = RectF()
        private val textCutoutPath = Path()
        private val highlightPath = Path()
        private val encompassingRect = RectF()
        private var currentScaleX = 0f
        private var currentScaleY = 0f
        
        private val toolbarButtonPadding = 14f * density
        private val toolbarButtonHeight = 36f * density
        private val toolbarButtonSpacing = 6f * density
        private val toolbarMargin = 8f * density
        private val toolbarDragHandleWidth = 16f * density

        private fun updateSelection(start: Int, end: Int) {
            if (start !in allWords.indices || end !in allWords.indices) {
                clearSelection()
                return
            }
            globalSelectionStart = start
            globalSelectionEnd = end
            hasActiveTextSelection.value = true
            recalculateHighlightPath()
            invalidate()
        }

        private fun resetTouchGestureState() {
            dragHandleType = 0
            isDraggingToolbar = false
            toolbarDragArmed = false
            pendingToolbarAction = null
            pendingWordHit = false
            toolbarGestureConsumed = false
            backgroundGestureActive = false
        }

        fun resetSelectionUi() {
            resetTouchGestureState()
            toolbarButtons.forEach { it.hitRect.setEmpty() }
            toolbarRect.setEmpty()
            dragHandleRect.setEmpty()
            toolbarOffsetX = 0f
            toolbarOffsetY = 0f
            toolbarBaseLeft = 0f
            toolbarBaseWidth = 0f
            toolbarLayoutWidth = 0
            toolbarInitialized = false
            highlightPath.reset()
            encompassingRect.setEmpty()
            invalidate()
        }

        fun refreshWordsChanged() {
            recalculateTextCutoutPath()
            recalculateHighlightPath()
            toolbarInitialized = false
            invalidate()
        }

        private fun recalculateHighlightPath() {
            highlightPath.reset()
            encompassingRect.setEmpty()

            if (!hasActiveSelection() || !updateScale()) return

            val start = globalSelectionStart.coerceAtMost(globalSelectionEnd)
            val end = globalSelectionStart.coerceAtLeast(globalSelectionEnd)

            var globalIndex = 0
            visualLines.forEach { line ->
                tempLineRect.setEmpty()
                line.forEach { word ->
                    if (globalIndex >= start && globalIndex <= end) {
                        setScaledRect(tempRect, word.bounds)
                        if (tempLineRect.isEmpty) {
                            tempLineRect.set(tempRect)
                        } else {
                            tempLineRect.union(tempRect)
                        }
                        if (encompassingRect.isEmpty) {
                            encompassingRect.set(tempRect)
                        } else {
                            encompassingRect.union(tempRect)
                        }
                    }
                    globalIndex++
                }
                if (!tempLineRect.isEmpty) {
                    tempLineRect.inset(-8f, -4f)
                    highlightPath.addRoundRect(tempLineRect, 8f, 8f, Path.Direction.CW)
                }
            }
        }

        private fun recalculateTextCutoutPath() {
            textCutoutPath.reset()
            if (!updateScale()) return
            textNodes.forEach { node ->
                setScaledRect(tempRect, node.bounds)
                tempRect.inset(-8f, -4f)
                textCutoutPath.addRoundRect(tempRect, 8f, 8f, Path.Direction.CW)
            }
        }

        private fun updateScale(): Boolean {
            if (width <= 0 || height <= 0) return false
            val bitmapWidth = screenshotBitmap?.width?.takeIf { it > 0 } ?: 1
            val bitmapHeight = screenshotBitmap?.height?.takeIf { it > 0 } ?: 1
            currentScaleX = width.toFloat() / bitmapWidth
            currentScaleY = height.toFloat() / bitmapHeight
            return true
        }

        private fun setScaledRect(target: RectF, source: RectF) {
            target.set(
                source.left * currentScaleX,
                source.top * currentScaleY,
                source.right * currentScaleX,
                source.bottom * currentScaleY,
            )
        }

        private fun setScaledRect(target: RectF, source: Rect) {
            target.set(
                source.left * currentScaleX,
                source.top * currentScaleY,
                source.right * currentScaleX,
                source.bottom * currentScaleY,
            )
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            recalculateTextCutoutPath()
            recalculateHighlightPath()
            toolbarInitialized = false
        }

        override fun onDraw(canvas: Canvas) {
            if (!hasActiveSelection()) {
                return // Do not dim or show text blocks if nothing is selected
            }

            val saveCount = canvas.save()
            canvas.clipOutPath(textCutoutPath)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            canvas.restoreToCount(saveCount)

            val start = globalSelectionStart.coerceAtMost(globalSelectionEnd)
            val end = globalSelectionStart.coerceAtLeast(globalSelectionEnd)
            canvas.drawPath(highlightPath, selectedWordPaint)

            val startBounds = allWords[start].bounds
            val endBounds = allWords[end].bounds
            drawHandle(
                canvas,
                startBounds.left * currentScaleX,
                startBounds.top * currentScaleY,
                isStart = true,
            )
            drawHandle(
                canvas,
                endBounds.right * currentScaleX,
                endBounds.bottom * currentScaleY,
                isStart = false,
            )
            drawFloatingToolbar(canvas, encompassingRect)
        }

        private fun drawHandle(canvas: Canvas, x: Float, y: Float, isStart: Boolean) {
            val radius = 8f * density
            val stemWidth = 2f * density
            val stemHeight = 20f * density
            canvas.drawCircle(x, y, radius, handlePaint)
            if (isStart) {
                canvas.drawRoundRect(
                    x - stemWidth / 2f,
                    y,
                    x + stemWidth / 2f,
                    y + stemHeight,
                    stemWidth / 2f,
                    stemWidth / 2f,
                    handlePaint
                )
            } else {
                canvas.drawRoundRect(
                    x - stemWidth / 2f,
                    y - stemHeight,
                    x + stemWidth / 2f,
                    y,
                    stemWidth / 2f,
                    stemWidth / 2f,
                    handlePaint
                )
            }
        }

        private fun drawFloatingToolbar(canvas: Canvas, anchor: RectF) {
            if (!toolbarInitialized || toolbarLayoutWidth != width) {
                var totalWidth =
                    toolbarMargin * 2f +
                        toolbarDragHandleWidth +
                        toolbarButtonSpacing * toolbarButtons.size
                toolbarButtons.forEachIndexed { index, button ->
                    val (_, textPaint) = getButtonColors(button.label)
                    val buttonWidth = textPaint.measureText(button.label) + toolbarButtonPadding * 2f
                    toolbarButtonWidths[index] = buttonWidth
                    totalWidth += buttonWidth
                }
                val tx = (width - totalWidth) / 2f
                toolbarOffsetX = 0f
                toolbarOffsetY = anchor.top - (toolbarButtonHeight + toolbarMargin * 2f) - 32f
                if (toolbarOffsetY < 150f) toolbarOffsetY = anchor.bottom + 32f
                
                var currentX = tx + toolbarMargin + toolbarDragHandleWidth + toolbarButtonSpacing
                toolbarButtons.forEachIndexed { index, button ->
                    val buttonWidth = toolbarButtonWidths[index]
                    button.baseRect.set(currentX.toInt(), 0, (currentX + buttonWidth).toInt(), 0)
                    currentX += buttonWidth + toolbarButtonSpacing
                }
                toolbarBaseLeft = tx
                toolbarBaseWidth = totalWidth
                toolbarLayoutWidth = width
                toolbarInitialized = true
            }
            
            val ty = toolbarOffsetY
            val tx = toolbarBaseLeft + toolbarOffsetX
            val totalWidth = toolbarBaseWidth
            
            toolbarRect.set(tx, ty, tx + totalWidth, ty + toolbarButtonHeight + toolbarMargin * 2f)
            
            // Elevation shadow
            canvas.drawRoundRect(toolbarRect, 22f * density, 22f * density, shadowPaint)
            // Background surface
            canvas.drawRoundRect(toolbarRect, 22f * density, 22f * density, toolbarBgPaint)
            // Outline border
            canvas.drawRoundRect(toolbarRect, 22f * density, 22f * density, toolbarBorderPaint)

            // Drag handle indicator
            val dx = tx + toolbarMargin
            dragHandleRect.set(
                dx,
                ty + toolbarMargin,
                dx + toolbarDragHandleWidth,
                ty + toolbarMargin + toolbarButtonHeight,
            )
            val handleWidth = 3.5f * density
            val handleHeight = 16f * density
            val hx = dragHandleRect.centerX() - handleWidth / 2f
            val hy = dragHandleRect.centerY() - handleHeight / 2f
            canvas.drawRoundRect(
                hx,
                hy,
                hx + handleWidth,
                hy + handleHeight,
                handleWidth / 2f,
                handleWidth / 2f,
                hPaint,
            )

            // Buttons
            toolbarButtons.forEach { btn ->
                val btnW = btn.baseRect.width().toFloat()
                val startX = btn.baseRect.left.toFloat() + toolbarOffsetX
                tempBtnRect.set(
                    startX,
                    ty + toolbarMargin,
                    startX + btnW,
                    ty + toolbarMargin + toolbarButtonHeight,
                )
                
                val (bgColor, textPaint) = getButtonColors(btn.label)
                btnPaint.color = bgColor
                canvas.drawRoundRect(
                    tempBtnRect,
                    toolbarButtonHeight / 2f,
                    toolbarButtonHeight / 2f,
                    btnPaint,
                )
                canvas.drawText(
                    btn.label,
                    tempBtnRect.centerX(),
                    tempBtnRect.centerY() + getTextOffset(textPaint),
                    textPaint,
                )
                
                // Update rect for touch events
                btn.hitRect.set(
                    tempBtnRect.left.toInt(),
                    tempBtnRect.top.toInt(),
                    tempBtnRect.right.toInt(),
                    tempBtnRect.bottom.toInt()
                )
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val lx = event.x; val ly = event.y
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    resetTouchGestureState()
                    initialTouchX = lx
                    initialTouchY = ly
                    lastTouchX = lx
                    lastTouchY = ly

                    if (hasActiveSelection()) {
                        val toolbarTarget = findToolbarTouchTarget(lx, ly)
                        toolbarTarget?.button?.let { button ->
                            pendingToolbarAction = button.label
                            toolbarGestureConsumed = true
                            return true
                        }
                        if (toolbarTarget?.isDragHandle == true) {
                            toolbarDragArmed = true
                            toolbarGestureConsumed = true
                            return true
                        }
                        if (toolbarRect.contains(lx, ly)) {
                            toolbarGestureConsumed = true
                            return true
                        }

                        val start = globalSelectionStart.coerceAtMost(globalSelectionEnd)
                        val end = globalSelectionStart.coerceAtLeast(globalSelectionEnd)
                        updateScale()
                        setScaledRect(touchStartRect, allWords[start].bounds)
                        setScaledRect(touchEndRect, allWords[end].bounds)
                        val visualStartIsGlobalStart = globalSelectionStart <= globalSelectionEnd
                        if (isPointNear(lx, ly, touchStartRect.left, touchStartRect.top)) {
                            dragHandleType = if (visualStartIsGlobalStart) 1 else 2
                            return true
                        }
                        if (isPointNear(lx, ly, touchEndRect.right, touchEndRect.bottom)) {
                            dragHandleType = if (visualStartIsGlobalStart) 2 else 1
                            return true
                        }
                    }
                    val nearest = findNearestWordGlobal(lx, ly)
                    if (nearest != -1) {
                        // A drag that begins on recognized text may be a circle
                        // gesture. Commit the word only on UP; crossing touch
                        // slop transfers the complete gesture to Compose.
                        pendingWordHit = true
                        return true
                    }

                    // Keep ownership inside the full-screen AndroidView and forward
                    // the complete gesture explicitly. Returning false from a
                    // translucent NOT_TOUCH_MODAL window can leak the gesture to the
                    // captured app instead of re-routing it to a Compose sibling.
                    clearSelection()
                    backgroundGestureActive = true
                    onBackgroundTouchCallback?.invoke(MotionEvent.ACTION_DOWN, lx, ly)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = lx - lastTouchX
                    val dy = ly - lastTouchY
                    val totalDx = lx - initialTouchX
                    val totalDy = ly - initialTouchY
                    val movedBeyondTouchSlop =
                        totalDx * totalDx + totalDy * totalDy >= touchSlop * touchSlop
                    lastTouchX = lx
                    lastTouchY = ly

                    if (backgroundGestureActive) {
                        onBackgroundTouchCallback?.invoke(MotionEvent.ACTION_MOVE, lx, ly)
                        return true
                    }

                    if (pendingWordHit) {
                        if (movedBeyondTouchSlop) {
                            clearSelection()
                            backgroundGestureActive = true
                            onBackgroundTouchCallback?.invoke(
                                MotionEvent.ACTION_DOWN,
                                initialTouchX,
                                initialTouchY,
                            )
                            onBackgroundTouchCallback?.invoke(MotionEvent.ACTION_MOVE, lx, ly)
                        }
                        return true
                    }

                    if (pendingToolbarAction != null) {
                        if (movedBeyondTouchSlop) pendingToolbarAction = null
                        return true
                    }

                    if (toolbarDragArmed || isDraggingToolbar) {
                        if (toolbarDragArmed) {
                            if (!movedBeyondTouchSlop) return true
                            toolbarDragArmed = false
                            isDraggingToolbar = true
                            toolbarOffsetX += totalDx
                            toolbarOffsetY += totalDy
                        } else {
                            toolbarOffsetX += dx
                            toolbarOffsetY += dy
                        }
                        invalidate()
                        return true
                    }
                    if (toolbarGestureConsumed) return true

                    if (dragHandleType != 0) {
                        val nearest = findNearestWordGlobal(lx, ly)
                        if (nearest != -1) {
                            if (dragHandleType == 1) updateSelection(nearest, globalSelectionEnd) else updateSelection(globalSelectionStart, nearest)
                        }
                        return true
                    }

                    if (hasActiveSelection()) {
                        val nearest = findNearestWordGlobal(lx, ly)
                        if (nearest != -1) { updateSelection(globalSelectionStart, nearest); return true }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (backgroundGestureActive) {
                        onBackgroundTouchCallback?.invoke(MotionEvent.ACTION_UP, lx, ly)
                        resetTouchGestureState()
                        return true
                    }
                    if (pendingWordHit) {
                        // OCR can publish/reorder allWords between DOWN and UP.
                        // Re-hit-test the original point instead of retaining an
                        // index into a list whose identity is not stable.
                        val wordIndex = findNearestWordGlobal(initialTouchX, initialTouchY)
                        resetTouchGestureState()
                        if (wordIndex != -1) {
                            updateSelection(wordIndex, wordIndex)
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        } else {
                            clearSelection()
                        }
                        return true
                    }
                    val action = pendingToolbarAction
                    val releasedAction =
                        findToolbarTouchTarget(lx, ly)?.button?.label
                    resetTouchGestureState()
                    if (action != null && action == releasedAction) {
                        handleToolbarAction(action)
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (backgroundGestureActive) {
                        onBackgroundTouchCallback?.invoke(MotionEvent.ACTION_CANCEL, lx, ly)
                    }
                    resetTouchGestureState()
                    return true
                }
            }
            return true
        }

        private fun findToolbarTouchTarget(x: Float, y: Float): ToolbarTouchTarget? {
            toolbarButtons.firstOrNull { it.hitRect.containsPoint(x, y) }?.let { button ->
                return ToolbarTouchTarget(button = button)
            }
            if (dragHandleRect.contains(x, y)) {
                return ToolbarTouchTarget(isDragHandle = true)
            }

            val hitPadding = 12f * density
            var nearestButton: ToolbarButton? = null
            var nearestDistance = Float.MAX_VALUE

            toolbarButtons.forEach { button ->
                if (button.hitRect.containsWithPadding(x, y, hitPadding)) {
                    val distance = distanceSquaredToBounds(
                        x,
                        y,
                        button.hitRect.left.toFloat(),
                        button.hitRect.top.toFloat(),
                        button.hitRect.right.toFloat(),
                        button.hitRect.bottom.toFloat(),
                    )
                    if (distance < nearestDistance) {
                        nearestDistance = distance
                        nearestButton = button
                    }
                }
            }

            if (dragHandleRect.containsWithPadding(x, y, hitPadding)) {
                val dragDistance = distanceSquaredToBounds(
                    x,
                    y,
                    dragHandleRect.left,
                    dragHandleRect.top,
                    dragHandleRect.right,
                    dragHandleRect.bottom,
                )
                if (dragDistance < nearestDistance) {
                    return ToolbarTouchTarget(isDragHandle = true)
                }
            }

            return nearestButton?.let { ToolbarTouchTarget(button = it) }
        }

        private fun distanceSquaredToBounds(
            x: Float,
            y: Float,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
        ): Float {
            val dx = when {
                x < left -> left - x
                x > right -> x - right
                else -> 0f
            }
            val dy = when {
                y < top -> top - y
                y > bottom -> y - bottom
                else -> 0f
            }
            return dx * dx + dy * dy
        }

        private fun Rect.containsPoint(x: Float, y: Float): Boolean =
            !isEmpty && x >= left && x <= right && y >= top && y <= bottom

        private fun Rect.containsWithPadding(x: Float, y: Float, padding: Float): Boolean =
            !isEmpty &&
                x >= left - padding &&
                x <= right + padding &&
                y >= top - padding &&
                y <= bottom + padding

        private fun RectF.containsWithPadding(x: Float, y: Float, padding: Float): Boolean =
            !isEmpty &&
                x >= left - padding &&
                x <= right + padding &&
                y >= top - padding &&
                y <= bottom + padding

        private fun findNearestWordGlobal(sx: Float, sy: Float): Int {
            val bitmapWidth = screenshotBitmap?.width?.takeIf { it > 0 } ?: 1
            val bitmapHeight = screenshotBitmap?.height?.takeIf { it > 0 } ?: 1
            val scaleX = width.toFloat() / bitmapWidth
            val scaleY = height.toFloat() / bitmapHeight
            return findBestWordHitIndex(
                words = allWords,
                x = sx,
                y = sy,
                scaleX = scaleX,
                scaleY = scaleY,
                proximityPx = 30f,
            )
        }

        private fun isPointNear(px: Float, py: Float, x: Float, y: Float): Boolean {
            val dx = px - x; val dy = py - y
            return dx * dx + dy * dy < 80 * 80
        }

        private fun handleToolbarAction(label: String) {
            if (!hasActiveSelection()) return
            val start = globalSelectionStart.coerceAtMost(globalSelectionEnd)
            val end = globalSelectionStart.coerceAtLeast(globalSelectionEnd)
            val selectedText = (start..end).mapNotNull { allWords.getOrNull(it) }.joinToString(" ") { it.text }

            when (label) {
                "Copy" -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Copied Text", selectedText))
                    Toast.makeText(context, "Text copied ✓", Toast.LENGTH_SHORT).show()
                    clearSelection()
                }
                "Share" -> {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, selectedText); addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, "Share text via").apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
                    clearSelection()
                }
                "All" -> updateSelection(0, allWords.lastIndex)
                "Cancel" -> clearSelection()
                "Translate" -> { openUrl(context, "https://translate.google.com/?text=${Uri.encode(selectedText)}") }
            }
        }
    }

    private fun openUrl(context: Context, url: String) {
        var finalUrl = url
        if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
            finalUrl = "https://" + finalUrl
        }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot open link", Toast.LENGTH_SHORT).show()
        }
    }
}
