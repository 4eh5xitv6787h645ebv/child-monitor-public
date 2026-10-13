package de.rochefort.childmonitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.InputDevice
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Full-gesture image view:
 * - single-finger drag pan
 * - two-finger pinch zoom
 * - two-finger rotate
 * - mouse-wheel zoom (for desktop/mouse input)
 * - double-tap callback (used to exit fullscreen)
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val displayMatrix = Matrix()
    private val savedMatrix = Matrix()

    private var mode = NONE
    private val start = PointF()
    private val mid = PointF()
    private var oldDist = 1f
    private var oldRotation = 0f
    private var rotationInitialized = false

    private var minScale = 1f
    private var maxScale = 6f
    private var currentScale = 1f
    private var baseScale = 1f
    private var isInitialized = false
    private var drawableWidth = 0f
    private var drawableHeight = 0f
    private val tempRect = RectF()

    private val gestureDetector: GestureDetector

    var onDoubleTapListener: ((Float, Float) -> Unit)? = null

    init {
        scaleType = ScaleType.MATRIX
        imageMatrix = displayMatrix

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                return performClick()
            }
        })
    }

    /** Exposes the fullscreen double-tap action to TalkBack and keyboard users. */
    override fun performClick(): Boolean {
        super.performClick()
        onDoubleTapListener?.invoke(width / 2f, height / 2f)
        return true
    }

    // GestureDetector calls performClick() for the double-tap action. Calling it again on every
    // ACTION_UP would turn a completed pan gesture into an unintended fullscreen exit.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(displayMatrix)
                start.set(event.x, event.y)
                mode = DRAG
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                oldDist = spacing(event)
                if (oldDist > 10f) {
                    savedMatrix.set(displayMatrix)
                    midPoint(mid, event)
                    if (event.pointerCount >= 3) {
                        oldRotation = rotation(event)
                        rotationInitialized = true
                    } else {
                        rotationInitialized = false
                    }
                    mode = ZOOM
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                mode = NONE
                rotationInitialized = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG) {
                    displayMatrix.set(savedMatrix)
                    val dx = event.x - start.x
                    val dy = event.y - start.y
                    displayMatrix.postTranslate(dx, dy)
                    constrainTranslation()
                    imageMatrix = displayMatrix
                } else if (mode == ZOOM && event.pointerCount >= 2) {
                    val newDist = spacing(event)
                    if (newDist > 10f) {
                        displayMatrix.set(savedMatrix)

                        val scale = newDist / oldDist
                        val desiredScale = currentScale * scale
                        val clampedScale = desiredScale.coerceIn(minScale, maxScale)
                        val realScale = if (currentScale == 0f) 1f else clampedScale / currentScale

                        displayMatrix.postScale(realScale, realScale, mid.x, mid.y)

                        // Rotate only when user uses 3+ fingers.
                        if (event.pointerCount >= 3) {
                            val newRotation = rotation(event)
                            if (!rotationInitialized) {
                                oldRotation = newRotation
                                rotationInitialized = true
                            } else {
                                val deltaRotation = newRotation - oldRotation
                                displayMatrix.postRotate(deltaRotation, mid.x, mid.y)
                            }
                        } else {
                            rotationInitialized = false
                        }
                        constrainTranslation()

                        imageMatrix = displayMatrix
                    }
                }
            }
        }

        currentScale = extractScale(displayMatrix)
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
        ) {
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (vScroll != 0f) {
                val step = 1.12f
                val factor = if (vScroll > 0f) step else (1f / step)
                return applyScaleAt(factor, event.x, event.y)
            }
        }
        return super.onGenericMotionEvent(event)
    }

    fun updateBitmap(bitmap: Bitmap?) {
        if (bitmap == null) return

        val bitmapWidth = bitmap.width.toFloat()
        val bitmapHeight = bitmap.height.toFloat()
        val sizeChanged = drawableWidth != bitmapWidth || drawableHeight != bitmapHeight
        drawableWidth = bitmapWidth
        drawableHeight = bitmapHeight

        super.setImageBitmap(bitmap)

        if (width > 0 && height > 0 && (!isInitialized || sizeChanged)) {
            fitImageToView(bitmapWidth, bitmapHeight)
            isInitialized = true
        } else {
            constrainTranslation()
        }

        imageMatrix = displayMatrix
    }

    fun resetToFit() {
        if (drawableWidth > 0f && drawableHeight > 0f) {
            fitImageToView(drawableWidth, drawableHeight)
            isInitialized = true
        }
    }

    private fun fitImageToView(bitmapWidth: Float, bitmapHeight: Float) {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        if (viewWidth == 0f || viewHeight == 0f || bitmapWidth == 0f || bitmapHeight == 0f) return

        val scaleX = viewWidth / bitmapWidth
        val scaleY = viewHeight / bitmapHeight
        // Use "fit center" behavior so full camera frame is visible by default.
        baseScale = min(scaleX, scaleY)
        currentScale = baseScale
        // Allow significantly more zoom-out (~40% more than previous limit).
        minScale = baseScale * 0.6f
        maxScale = baseScale * 6f

        val scaledWidth = bitmapWidth * baseScale
        val scaledHeight = bitmapHeight * baseScale
        val dx = (viewWidth - scaledWidth) / 2f
        val dy = (viewHeight - scaledHeight) / 2f

        displayMatrix.reset()
        displayMatrix.postScale(baseScale, baseScale)
        displayMatrix.postTranslate(dx, dy)
        constrainTranslation()
        imageMatrix = displayMatrix
    }

    private fun constrainTranslation() {
        if (drawableWidth <= 0f || drawableHeight <= 0f || width == 0 || height == 0) return

        tempRect.set(0f, 0f, drawableWidth, drawableHeight)
        displayMatrix.mapRect(tempRect)

        val viewW = width.toFloat()
        val viewH = height.toFloat()

        val dx = when {
            tempRect.width() <= viewW -> (viewW - tempRect.width()) / 2f - tempRect.left
            tempRect.left > 0f -> -tempRect.left
            tempRect.right < viewW -> viewW - tempRect.right
            else -> 0f
        }

        val dy = when {
            tempRect.height() <= viewH -> (viewH - tempRect.height()) / 2f - tempRect.top
            tempRect.top > 0f -> -tempRect.top
            tempRect.bottom < viewH -> viewH - tempRect.bottom
            else -> 0f
        }

        if (dx != 0f || dy != 0f) {
            displayMatrix.postTranslate(dx, dy)
        }
    }

    private fun applyScaleAt(scaleFactor: Float, focusX: Float, focusY: Float): Boolean {
        if (drawableWidth <= 0f || drawableHeight <= 0f || width == 0 || height == 0) return false
        val desiredScale = currentScale * scaleFactor
        val clampedScale = desiredScale.coerceIn(minScale, maxScale)
        val realScale = if (currentScale == 0f) 1f else clampedScale / currentScale
        if (realScale == 1f) return false

        displayMatrix.postScale(realScale, realScale, focusX, focusY)
        constrainTranslation()
        imageMatrix = displayMatrix
        currentScale = extractScale(displayMatrix)
        return true
    }

    private fun extractScale(matrix: Matrix): Float {
        val values = FloatArray(9)
        matrix.getValues(values)
        val scaleX = values[Matrix.MSCALE_X]
        val skewY = values[Matrix.MSKEW_Y]
        return sqrt(scaleX * scaleX + skewY * skewY)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            if (drawableWidth > 0f && drawableHeight > 0f) {
                fitImageToView(drawableWidth, drawableHeight)
                isInitialized = true
            } else {
                isInitialized = false
            }
        }
    }

    private fun spacing(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return sqrt(x * x + y * y)
    }

    private fun midPoint(point: PointF, event: MotionEvent) {
        if (event.pointerCount < 2) return
        val x = event.getX(0) + event.getX(1)
        val y = event.getY(0) + event.getY(1)
        point.set(x / 2f, y / 2f)
    }

    private fun rotation(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val deltaX = event.getX(0) - event.getX(1)
        val deltaY = event.getY(0) - event.getY(1)
        return Math.toDegrees(atan2(deltaY.toDouble(), deltaX.toDouble())).toFloat()
    }

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }
}
