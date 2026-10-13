package de.rochefort.childmonitor

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class AnimatedGardenBackgroundView : View {
    private data class FlowerSprite(
        var x: Float,
        var y: Float,
        val size: Float,
        val variant: Int,
        var color: Int,
        var rotation: Float,
        val rotationSpeed: Float,
        val driftSpeed: Float,
        val swayAmplitude: Float,
        val swaySpeed: Float,
        val phase: Float,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private var flowerColors = intArrayOf(
        Color.argb(36, 205, 231, 214),
        Color.argb(32, 233, 200, 183),
        Color.argb(30, 196, 219, 236),
        Color.argb(32, 215, 232, 195),
        Color.argb(28, 241, 216, 192),
    )

    private val sprites = mutableListOf<FlowerSprite>()
    private val random = Random(1337)
    private val oval = RectF()
    private val path = Path()

    private var lastTimeMs = 0L
    private var started = false
    private var motionEnabled = true
    private var nextMotionCheckMs = 0L
    private val frameTick = Runnable {
        if (started && isShown && windowVisibility == VISIBLE) invalidate()
    }

    // Keep earlier floral families + add many Australian-inspired leaves/plant motifs.
    private val variantFamilies = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, // previous floral families
        12, 13, 14, 15, 16, 17, 18, 19, 20, 21, // leaves/sprigs
        22, 23, 24, 25, 26, 27, 28, 29, 30, 31, // eucalyptus/gumnuts/native motifs
        32, 33, 34, 35, 36, 37, 38, 39, // more leaves + cones + pods
        40, 41, 42, 43, 44, 45, 46, 47, 48, 49, // extra leaf families (10)
        50, 51, 52, 53, 54, 55, 56, 57, 58, 59, // extra plant motif families (10)
    )
    private val totalVariants = 200 // >100 total, includes 50+ leaf/plant families through modulation

    constructor(context: Context?) : super(context)
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    init {
        // #304: this is a purely decorative animated backdrop that sits behind 7 screens. Keep it
        // out of the accessibility tree so TalkBack doesn't focus/announce it as content. Runs for
        // every secondary constructor (Kotlin executes init blocks before each constructor body).
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setFlowerPalette(colors: IntArray) {
        if (colors.isNotEmpty()) {
            flowerColors = colors.copyOf()
            for (i in sprites.indices) {
                sprites[i].color = flowerColors[random.nextInt(flowerColors.size)]
            }
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        started = true
        lastTimeMs = SystemClock.uptimeMillis()
        nextMotionCheckMs = 0L
        invalidate()
    }

    override fun onDetachedFromWindow() {
        started = false
        removeCallbacks(frameTick)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        removeCallbacks(frameTick)
        if (started && visibility == VISIBLE) {
            lastTimeMs = SystemClock.uptimeMillis()
            nextMotionCheckMs = 0L
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        generateSprites(w, h)
    }

    private fun generateSprites(width: Int, height: Int) {
        sprites.clear()
        if (width <= 0 || height <= 0) return

        val density = resources.displayMetrics.density
        val count = (36 + (width * height / 150000)).coerceIn(36, 72)
        repeat(count) {
            val size = random.nextFloat() * 22f * density + 16f * density
            val pos = findFreePosition(width.toFloat(), height.toFloat(), size)
            sprites += FlowerSprite(
                x = pos.first,
                y = pos.second,
                size = size,
                variant = random.nextInt(0, totalVariants),
                color = flowerColors[random.nextInt(flowerColors.size)],
                rotation = random.nextFloat() * 360f,
                rotationSpeed = random.nextFloat() * 8f - 4f,
                driftSpeed = random.nextFloat() * 8f + 5f,
                swayAmplitude = random.nextFloat() * 11f + 4f,
                swaySpeed = random.nextFloat() * 1.0f + 0.4f,
                phase = random.nextFloat() * (2f * PI).toFloat(),
            )
        }
    }

    private fun findFreePosition(width: Float, height: Float, size: Float): Pair<Float, Float> {
        var attempts = 0
        while (attempts < 70) {
            val x = random.nextFloat() * width
            val y = random.nextFloat() * height
            if (!overlapsExisting(x, y, size, minGapFactor = 0.92f)) {
                return x to y
            }
            attempts++
        }
        return random.nextFloat() * width to random.nextFloat() * height
    }

    private fun overlapsExisting(x: Float, y: Float, size: Float, minGapFactor: Float): Boolean {
        for (s in sprites) {
            val minGap = (size + s.size) * minGapFactor
            if (hypot((x - s.x).toDouble(), (y - s.y).toDouble()) < minGap) {
                return true
            }
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!started || sprites.isEmpty()) return

        val now = SystemClock.uptimeMillis()
        if (now >= nextMotionCheckMs) {
            // Decorative motion must respect the system's animation/accessibility setting.
            motionEnabled = runCatching {
                Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            }.getOrDefault(true)
            nextMotionCheckMs = now + 1_000L
        }
        val dt = if (motionEnabled) ((now - lastTimeMs).coerceIn(0L, 100L)) / 1000f else 0f
        lastTimeMs = now

        val w = width.toFloat()
        val h = height.toFloat()
        val t = now / 1000f

        for (sprite in sprites) {
            sprite.y -= sprite.driftSpeed * dt
            if (sprite.y < -sprite.size * 1.8f) {
                val respawnSize = sprite.size
                val respawnY = h + respawnSize * (0.4f + random.nextFloat())
                var x = random.nextFloat() * w
                var tries = 0
                while (tries < 40 && overlapsExisting(x, respawnY, respawnSize, minGapFactor = 0.9f)) {
                    x = random.nextFloat() * w
                    tries++
                }
                sprite.x = x
                sprite.y = respawnY
            }
            sprite.rotation = (sprite.rotation + sprite.rotationSpeed * dt) % 360f
        }

        resolveOverlaps(w, h)

        for (sprite in sprites) {
            val sway = sin(t * sprite.swaySpeed + sprite.phase) * sprite.swayAmplitude
            val drawX = sprite.x + sway
            drawPlantIcon(canvas, drawX, sprite.y, sprite.size, sprite.rotation, sprite.variant, sprite.color)
        }

        removeCallbacks(frameTick)
        // A gentle 15fps backdrop leaves idle time for monitoring work and avoids keeping
        // Android's launch/idle observers busy on slower phones and software-rendered emulators.
        if (motionEnabled && started && isShown && windowVisibility == VISIBLE) {
            postDelayed(frameTick, 66L)
        }
    }

    private fun resolveOverlaps(width: Float, height: Float) {
        // Soft repulsion step keeps icons from overlapping while preserving natural motion.
        for (i in 0 until sprites.size) {
            for (j in i + 1 until sprites.size) {
                val a = sprites[i]
                val b = sprites[j]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(0.001f)
                val minDist = (a.size + b.size) * 0.86f
                if (dist < minDist) {
                    val push = (minDist - dist) * 0.5f
                    val nx = dx / dist
                    val ny = dy / dist
                    a.x -= nx * push
                    a.y -= ny * push
                    b.x += nx * push
                    b.y += ny * push
                }
            }
        }

        for (s in sprites) {
            s.x = s.x.coerceIn(-s.size, width + s.size)
            s.y = s.y.coerceIn(-s.size * 2f, height + s.size * 2f)
        }
    }

    private fun drawPlantIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        rotation: Float,
        variant: Int,
        color: Int,
    ) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(rotation)
        paint.color = color

        val family = variantFamilies[variant % variantFamilies.size]
        val shapeMod = variant / variantFamilies.size

        when (family) {
            0 -> drawPetalFlower(canvas, petals = 6 + (shapeMod % 3), radius = size, petalRatio = 0.3f)
            1 -> drawPetalFlower(canvas, petals = 8 + (shapeMod % 4), radius = size, petalRatio = 0.24f)
            2 -> drawStarFlower(canvas, petals = 9 + (shapeMod % 5), radius = size)
            3 -> drawBrushFlower(canvas, spikes = 14 + (shapeMod % 8), radius = size)
            4 -> drawPetalFlower(canvas, petals = 6, radius = size, petalRatio = 0.22f)
            5 -> drawSplitPetalFlower(canvas, petals = 6 + (shapeMod % 3), radius = size)
            6 -> drawStarFlower(canvas, petals = 7 + (shapeMod % 4), radius = size * 0.9f)
            7 -> drawPetalFlower(canvas, petals = 5 + (shapeMod % 2), radius = size, petalRatio = 0.4f)
            8 -> drawRoundFlower(canvas, petals = 10 + (shapeMod % 5), radius = size * 0.85f)
            9 -> drawPetalFlower(canvas, petals = 5 + (shapeMod % 4), radius = size, petalRatio = 0.27f)
            10 -> drawRoundFlower(canvas, petals = 8 + (shapeMod % 6), radius = size * 0.9f)
            11 -> drawPetalFlower(canvas, petals = 4 + (shapeMod % 3), radius = size, petalRatio = 0.43f)

            12 -> drawLeafSprig(canvas, leaves = 4 + (shapeMod % 4), size = size, narrow = true)
            13 -> drawLeafSprig(canvas, leaves = 5 + (shapeMod % 4), size = size, narrow = false)
            14 -> drawRoundLeafRosette(canvas, leaves = 7 + (shapeMod % 5), size = size)
            15 -> drawLongLeafBurst(canvas, leaves = 8 + (shapeMod % 4), size = size)
            16 -> drawPairLeafCross(canvas, size = size)
            17 -> drawFernLike(canvas, fronds = 5 + (shapeMod % 4), size = size)
            18 -> drawLeafCluster(canvas, leaves = 6 + (shapeMod % 5), size = size)
            19 -> drawLeafSpoke(canvas, leaves = 9 + (shapeMod % 6), size = size)
            20 -> drawLeafSprig(canvas, leaves = 3 + (shapeMod % 5), size = size, narrow = true)
            21 -> drawRoundLeafRosette(canvas, leaves = 10 + (shapeMod % 5), size = size)

            22 -> drawEucalyptusBlossom(canvas, filaments = 14 + (shapeMod % 10), size = size)
            23 -> drawGumnutPair(canvas, size = size)
            24 -> drawBanksiaCone(canvas, spikes = 12 + (shapeMod % 10), size = size)
            25 -> drawWaratahBurst(canvas, petals = 10 + (shapeMod % 5), size = size)
            26 -> drawBottlebrushStem(canvas, bristles = 16 + (shapeMod % 8), size = size)
            27 -> drawWattlePomPom(canvas, dots = 16 + (shapeMod % 12), size = size)
            28 -> drawKangarooPawCluster(canvas, stems = 5 + (shapeMod % 4), size = size)
            29 -> drawGrevilleaSpider(canvas, arms = 7 + (shapeMod % 4), size = size)
            30 -> drawHonkyNutCluster(canvas, nuts = 3 + (shapeMod % 4), size = size)
            31 -> drawTeaTreeBloom(canvas, petals = 12 + (shapeMod % 8), size = size)

            32 -> drawPodCluster(canvas, pods = 4 + (shapeMod % 4), size = size)
            33 -> drawAcaciaLeaflets(canvas, sets = 4 + (shapeMod % 4), size = size)
            34 -> drawSpinifexTuft(canvas, spikes = 12 + (shapeMod % 8), size = size)
            35 -> drawFernLike(canvas, fronds = 8 + (shapeMod % 4), size = size * 0.95f)
            36 -> drawLeafSpoke(canvas, leaves = 5 + (shapeMod % 3), size = size * 1.05f)
            37 -> drawRoundFlower(canvas, petals = 6 + (shapeMod % 4), radius = size)
            38 -> drawBanksiaCone(canvas, spikes = 16 + (shapeMod % 8), size = size * 0.9f)
            39 -> drawGumnutPair(canvas, size = size * 0.85f)

            40, 41, 42, 43, 44, 45, 46, 47, 48, 49 -> {
                drawLeafSprig(canvas, leaves = 4 + ((family + shapeMod) % 5), size = size, narrow = (family % 2 == 0))
            }

            else -> {
                drawAustralianMixed(canvas, family = family, mod = shapeMod, size = size)
            }
        }

        paint.color = Color.argb(min(72, Color.alpha(color) + 24), 246, 248, 236)
        canvas.drawCircle(0f, 0f, size * (0.08f + (shapeMod % 4) * 0.02f), paint)
        canvas.restore()
    }

    private fun drawAustralianMixed(canvas: Canvas, family: Int, mod: Int, size: Float) {
        when (family % 10) {
            0 -> drawEucalyptusBlossom(canvas, filaments = 16 + (mod % 9), size = size)
            1 -> drawHonkyNutCluster(canvas, nuts = 3 + (mod % 3), size = size)
            2 -> drawKangarooPawCluster(canvas, stems = 5 + (mod % 4), size = size)
            3 -> drawWattlePomPom(canvas, dots = 18 + (mod % 10), size = size)
            4 -> drawBottlebrushStem(canvas, bristles = 15 + (mod % 8), size = size)
            5 -> drawLeafCluster(canvas, leaves = 7 + (mod % 5), size = size)
            6 -> drawBanksiaCone(canvas, spikes = 14 + (mod % 8), size = size)
            7 -> drawGrevilleaSpider(canvas, arms = 6 + (mod % 4), size = size)
            8 -> drawTeaTreeBloom(canvas, petals = 12 + (mod % 8), size = size)
            else -> drawAcaciaLeaflets(canvas, sets = 5 + (mod % 3), size = size)
        }
    }

    private fun drawPetalFlower(canvas: Canvas, petals: Int, radius: Float, petalRatio: Float) {
        val petalLength = radius * 0.54f
        val petalWidth = radius * petalRatio.coerceIn(0.16f, 0.46f)
        val distance = radius * 0.34f
        for (i in 0 until petals) {
            canvas.save()
            canvas.rotate(360f / petals * i)
            oval.set(-petalWidth, -petalLength - distance, petalWidth, -distance)
            canvas.drawOval(oval, paint)
            canvas.restore()
        }
    }

    private fun drawSplitPetalFlower(canvas: Canvas, petals: Int, radius: Float) {
        val petalLength = radius * 0.48f
        val petalWidth = radius * 0.16f
        val distance = radius * 0.26f
        for (i in 0 until petals) {
            canvas.save()
            canvas.rotate(360f / petals * i)
            oval.set(-petalWidth * 1.25f, -petalLength - distance, -petalWidth * 0.2f, -distance)
            canvas.drawOval(oval, paint)
            oval.set(petalWidth * 0.2f, -petalLength - distance, petalWidth * 1.25f, -distance)
            canvas.drawOval(oval, paint)
            canvas.restore()
        }
    }

    private fun drawStarFlower(canvas: Canvas, petals: Int, radius: Float) {
        val petalLength = radius * 0.64f
        val petalWidth = radius * 0.2f
        val distance = radius * 0.2f
        for (i in 0 until petals) {
            canvas.save()
            canvas.rotate(360f / petals * i)
            oval.set(-petalWidth, -petalLength - distance, petalWidth, -distance)
            canvas.drawOval(oval, paint)
            canvas.restore()
        }
    }

    private fun drawBrushFlower(canvas: Canvas, spikes: Int, radius: Float) {
        val spikeLength = radius * 0.65f
        val spikeWidth = radius * 0.1f
        val distance = radius * 0.1f
        for (i in 0 until spikes) {
            canvas.save()
            canvas.rotate(360f / spikes * i)
            oval.set(-spikeWidth, -spikeLength - distance, spikeWidth, -distance)
            canvas.drawOval(oval, paint)
            canvas.restore()
        }
    }

    private fun drawRoundFlower(canvas: Canvas, petals: Int, radius: Float) {
        val petalRadius = radius * 0.17f
        val distance = radius * 0.4f
        for (i in 0 until petals) {
            val angle = Math.toRadians((360f / petals * i).toDouble())
            val px = cos(angle).toFloat() * distance
            val py = sin(angle).toFloat() * distance
            canvas.drawCircle(px, py, petalRadius, paint)
        }
    }

    private fun drawLeafSprig(canvas: Canvas, leaves: Int, size: Float, narrow: Boolean) {
        val len = if (narrow) size * 0.62f else size * 0.5f
        val wid = if (narrow) size * 0.12f else size * 0.2f
        for (i in 0 until leaves) {
            canvas.save()
            val angle = -35f + (70f / max(1, leaves - 1)) * i
            canvas.rotate(angle)
            oval.set(-wid, -len, wid, 0f)
            canvas.drawOval(oval, paint)
            canvas.restore()
        }
    }

    private fun drawRoundLeafRosette(canvas: Canvas, leaves: Int, size: Float) {
        val leafLen = size * 0.42f
        val leafW = size * 0.22f
        val d = size * 0.32f
        for (i in 0 until leaves) {
            canvas.save()
            canvas.rotate(360f / leaves * i)
            oval.set(-leafW, -leafLen - d, leafW, -d)
            canvas.drawOval(oval, paint)
            canvas.restore()
        }
    }

    private fun drawLongLeafBurst(canvas: Canvas, leaves: Int, size: Float) {
        val leafLen = size * 0.7f
        val leafW = size * 0.11f
        val d = size * 0.16f
        for (i in 0 until leaves) {
            canvas.save()
            canvas.rotate(360f / leaves * i)
            oval.set(-leafW, -leafLen - d, leafW, -d)
            canvas.drawOval(oval, paint)
            canvas.restore()
        }
    }

    private fun drawPairLeafCross(canvas: Canvas, size: Float) {
        val leafLen = size * 0.58f
        val leafW = size * 0.18f
        val d = size * 0.2f
        repeat(4) { i ->
            canvas.save()
            canvas.rotate(i * 90f)
            oval.set(-leafW, -leafLen - d, leafW, -d)
            canvas.drawOval(oval, paint)
            canvas.restore()
        }
    }

    private fun drawFernLike(canvas: Canvas, fronds: Int, size: Float) {
        paint.alpha = min(255, Color.alpha(paint.color))
        val stemLen = size * 0.65f
        strokePaint.color = paint.color
        canvas.drawLine(0f, -stemLen * 0.5f, 0f, stemLen * 0.55f, strokePaint)
        for (i in 0 until fronds) {
            val y = -stemLen * 0.35f + (stemLen * 0.7f / max(1, fronds - 1)) * i
            val x = size * (0.2f + 0.08f * (i % 2))
            oval.set(-x - size * 0.15f, y - size * 0.08f, -x + size * 0.15f, y + size * 0.08f)
            canvas.drawOval(oval, paint)
            oval.set(x - size * 0.15f, y - size * 0.08f, x + size * 0.15f, y + size * 0.08f)
            canvas.drawOval(oval, paint)
        }
    }

    private fun drawLeafCluster(canvas: Canvas, leaves: Int, size: Float) {
        val d = size * 0.38f
        val lw = size * 0.15f
        val ll = size * 0.45f
        for (i in 0 until leaves) {
            canvas.save()
            canvas.rotate(360f / leaves * i)
            oval.set(-lw, -ll - d, lw, -d)
            canvas.drawOval(oval, paint)
            canvas.restore()
        }
    }

    private fun drawLeafSpoke(canvas: Canvas, leaves: Int, size: Float) {
        val d = size * 0.12f
        val lw = size * 0.1f
        val ll = size * 0.62f
        for (i in 0 until leaves) {
            canvas.save()
            canvas.rotate(360f / leaves * i)
            oval.set(-lw, -ll - d, lw, -d)
            canvas.drawOval(oval, paint)
            canvas.restore()
        }
    }

    private fun drawEucalyptusBlossom(canvas: Canvas, filaments: Int, size: Float) {
        val len = size * 0.58f
        val w = size * 0.07f
        for (i in 0 until filaments) {
            canvas.save()
            canvas.rotate(360f / filaments * i)
            oval.set(-w, -len, w, -size * 0.06f)
            canvas.drawOval(oval, paint)
            canvas.restore()
        }
    }

    private fun drawGumnutPair(canvas: Canvas, size: Float) {
        val r = size * 0.18f
        canvas.drawCircle(-size * 0.17f, size * 0.06f, r, paint)
        canvas.drawCircle(size * 0.2f, -size * 0.03f, r * 1.08f, paint)
        strokePaint.color = paint.color
        canvas.drawLine(-size * 0.17f, size * 0.06f - r, -size * 0.17f, -size * 0.28f, strokePaint)
        canvas.drawLine(size * 0.2f, -size * 0.03f - r, size * 0.2f, -size * 0.33f, strokePaint)
    }

    private fun drawBanksiaCone(canvas: Canvas, spikes: Int, size: Float) {
        val bodyW = size * 0.24f
        val bodyH = size * 0.6f
        oval.set(-bodyW, -bodyH * 0.55f, bodyW, bodyH * 0.55f)
        canvas.drawOval(oval, paint)
        strokePaint.color = adjustAlpha(paint.color, 0.8f)
        for (i in 0 until spikes) {
            val y = -bodyH * 0.5f + (bodyH / max(1, spikes - 1)) * i
            val w = bodyW * (0.65f + 0.35f * sin(i.toDouble()).toFloat().coerceAtLeast(0f))
            canvas.drawLine(-w, y, w, y, strokePaint)
        }
    }

    private fun drawWaratahBurst(canvas: Canvas, petals: Int, size: Float) {
        val len = size * 0.52f
        val w = size * 0.16f
        val d = size * 0.2f
        for (i in 0 until petals) {
            canvas.save()
            canvas.rotate(360f / petals * i)
            oval.set(-w, -len - d, w, -d)
            canvas.drawOval(oval, paint)
            canvas.restore()
        }
    }

    private fun drawBottlebrushStem(canvas: Canvas, bristles: Int, size: Float) {
        val len = size * 0.68f
        strokePaint.color = paint.color
        canvas.drawLine(0f, -len * 0.5f, 0f, len * 0.5f, strokePaint)
        for (i in 0 until bristles) {
            val y = -len * 0.46f + (len / max(1, bristles - 1)) * i
            val half = size * (0.16f + 0.06f * (i % 3))
            canvas.drawLine(-half, y, half, y, strokePaint)
        }
    }

    private fun drawWattlePomPom(canvas: Canvas, dots: Int, size: Float) {
        val r = size * 0.07f
        val d = size * 0.34f
        for (i in 0 until dots) {
            val angle = 2 * PI * i / dots
            val x = cos(angle).toFloat() * d
            val y = sin(angle).toFloat() * d
            canvas.drawCircle(x, y, r, paint)
        }
        canvas.drawCircle(0f, 0f, r * 1.3f, paint)
    }

    private fun drawKangarooPawCluster(canvas: Canvas, stems: Int, size: Float) {
        strokePaint.color = paint.color
        val baseY = size * 0.28f
        for (i in 0 until stems) {
            val x = (i - (stems - 1) / 2f) * size * 0.15f
            val topY = -size * (0.4f + (i % 3) * 0.08f)
            canvas.drawLine(x, baseY, x, topY, strokePaint)
            canvas.drawCircle(x, topY, size * 0.1f, paint)
        }
    }

    private fun drawGrevilleaSpider(canvas: Canvas, arms: Int, size: Float) {
        val len = size * 0.62f
        strokePaint.color = paint.color
        for (i in 0 until arms) {
            val angle = 2 * PI * i / arms
            val x = cos(angle).toFloat() * len
            val y = sin(angle).toFloat() * len
            canvas.drawLine(0f, 0f, x, y, strokePaint)
            canvas.drawCircle(x, y, size * 0.07f, paint)
        }
    }

    private fun drawHonkyNutCluster(canvas: Canvas, nuts: Int, size: Float) {
        val r = size * 0.15f
        for (i in 0 until nuts) {
            val angle = 2 * PI * i / max(1, nuts)
            val x = cos(angle).toFloat() * size * 0.22f
            val y = sin(angle).toFloat() * size * 0.22f
            canvas.drawCircle(x, y, r, paint)
            strokePaint.color = paint.color
            canvas.drawLine(x, y - r, x, y - r - size * 0.2f, strokePaint)
        }
    }

    private fun drawTeaTreeBloom(canvas: Canvas, petals: Int, size: Float) {
        val len = size * 0.52f
        val w = size * 0.075f
        for (i in 0 until petals) {
            canvas.save()
            canvas.rotate(360f / petals * i)
            oval.set(-w, -len, w, -size * 0.08f)
            canvas.drawOval(oval, paint)
            canvas.restore()
        }
    }

    private fun drawPodCluster(canvas: Canvas, pods: Int, size: Float) {
        for (i in 0 until pods) {
            val angle = 2 * PI * i / max(1, pods)
            val x = cos(angle).toFloat() * size * 0.26f
            val y = sin(angle).toFloat() * size * 0.26f
            oval.set(x - size * 0.1f, y - size * 0.14f, x + size * 0.1f, y + size * 0.14f)
            canvas.drawOval(oval, paint)
        }
    }

    private fun drawAcaciaLeaflets(canvas: Canvas, sets: Int, size: Float) {
        strokePaint.color = paint.color
        canvas.drawLine(0f, -size * 0.52f, 0f, size * 0.52f, strokePaint)
        for (i in 0 until sets) {
            val y = -size * 0.42f + (size * 0.84f / max(1, sets - 1)) * i
            oval.set(-size * 0.34f, y - size * 0.08f, -size * 0.05f, y + size * 0.08f)
            canvas.drawOval(oval, paint)
            oval.set(size * 0.05f, y - size * 0.08f, size * 0.34f, y + size * 0.08f)
            canvas.drawOval(oval, paint)
        }
    }

    private fun drawSpinifexTuft(canvas: Canvas, spikes: Int, size: Float) {
        strokePaint.color = paint.color
        val len = size * 0.6f
        for (i in 0 until spikes) {
            val angle = 2 * PI * i / spikes
            val x = cos(angle).toFloat() * len
            val y = sin(angle).toFloat() * len
            canvas.drawLine(0f, 0f, x, y, strokePaint)
        }
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val a = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }
}
