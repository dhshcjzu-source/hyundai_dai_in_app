package com.example.myapplication.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 은색 전기 크로스오버 형태의 3D 모델. 단위는 미터이고 x는 오른쪽, y는 차 앞쪽, z는 위쪽이다.
 *
 * 차체는 앞뒤 곡선과 단면 곡선으로 정의한 촘촘한 곡면이다. 면이 많으므로 비트맵에 한 번 그려 두고,
 * 방향이 바뀔 때만 다시 그린다. 차체가 거의 볼록해서 카메라를 등진 면만 빼면 정렬 없이 그릴 수 있다.
 */
class CarModel {

    private class Face(
        val points: FloatArray,
        val nx: Float,
        val ny: Float,
        val nz: Float,
        val color: Int,
        val gloss: Float,
        /** +1이면 오른쪽, -1이면 왼쪽이 보일 때만 그린다. 0이면 항상 판단 없이 그린다. */
        val side: Int,
    )

    private val faces = ArrayList<Face>()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val destination = RectF()

    private var sprite: Bitmap? = null
    private var spriteCanvas: Canvas? = null
    private var spriteScale = 0f
    private var spriteHeading = Int.MIN_VALUE

    init {
        buildTires()
        buildBody()
        buildEndCap(FRONT_Y)
        buildEndCap(REAR_Y)
        buildWheels()
        buildSideDetails()
        buildMirrors()
    }

    /** 주변 밝기에 맞춰 차 색을 조절한다. 흰색이면 원래 색 그대로다. */
    fun setTint(color: Int) {
        spritePaint.colorFilter = LightingColorFilter(color and 0x00FFFFFF, 0)
    }

    /**
     * ([centerX], [centerY])를 차 바닥 중심으로, 미터당 [pixelsPerMeter] 픽셀 크기로 그린다.
     * 그림은 [renderPixelsPerMeter] 크기로 만들어 두고 줄여 쓰므로 확대, 축소 중에는 다시 만들지 않는다.
     */
    fun draw(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        renderPixelsPerMeter: Float,
        pixelsPerMeter: Float,
        headingDegrees: Float,
    ) {
        val heading = (headingDegrees / HEADING_STEP).roundToInt() * HEADING_STEP
        if (sprite == null || spriteScale != renderPixelsPerMeter || spriteHeading != heading) {
            renderSprite(renderPixelsPerMeter, heading)
        }
        val half = SPRITE_METERS / 2 * pixelsPerMeter
        destination.set(centerX - half, centerY - half, centerX + half, centerY + half)
        canvas.drawBitmap(sprite!!, null, destination, spritePaint)
    }

    private fun renderSprite(pixelsPerMeter: Float, heading: Int) {
        val k = pixelsPerMeter * SUPERSAMPLE
        val size = (SPRITE_METERS * k).toInt()
        if (sprite?.width != size) {
            sprite?.recycle()
            sprite = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { spriteCanvas = Canvas(it) }
        }
        spriteScale = pixelsPerMeter
        spriteHeading = heading
        sprite!!.eraseColor(Color.TRANSPARENT)
        val target = spriteCanvas!!
        val radians = Math.toRadians(heading.toDouble())
        val sinH = sin(radians).toFloat()
        val cosH = cos(radians).toFloat()
        val center = size / 2f

        drawShadow(target, center, k, sinH, cosH)
        fill.strokeWidth = k * 0.012f
        for (face in faces) {
            val nx = face.nx * cosH + face.ny * sinH
            val ny = -face.nx * sinH + face.ny * cosH
            val nz = face.nz
            if (ny * VIEW_Y + nz * VIEW_Z <= 0f) continue
            if (face.side != 0 && -face.side * sinH * VIEW_Y <= 0f) continue

            path.rewind()
            val p = face.points
            var i = 0
            while (i < p.size) {
                val x = p[i] * cosH + p[i + 1] * sinH
                val y = -p[i] * sinH + p[i + 1] * cosH
                val sx = center + x * k
                val sy = center - (y * SCREEN_Y_PER_Y + p[i + 2] * SCREEN_Y_PER_Z) * k
                if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
                i += 3
            }
            path.close()
            fill.color = shade(face, nx, ny, nz)
            target.drawPath(path, fill)
        }
    }

    private fun drawShadow(canvas: Canvas, center: Float, k: Float, sinH: Float, cosH: Float) {
        shadow.color = Color.argb(135, 0, 0, 0)
        shadow.maskFilter = BlurMaskFilter(k * 0.16f, BlurMaskFilter.Blur.NORMAL)
        path.rewind()
        var i = 0
        while (i < SHADOW_OUTLINE.size) {
            val mx = SHADOW_OUTLINE[i]
            val my = SHADOW_OUTLINE[i + 1]
            val x = mx * cosH + my * sinH
            val y = -mx * sinH + my * cosH
            val sx = center + (x + 0.1f) * k
            val sy = center - (y - 0.1f) * SCREEN_Y_PER_Y * k
            if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
            i += 2
        }
        path.close()
        canvas.drawPath(path, shadow)
    }

    /** 위를 향할수록 밝게 하늘을 비추고, 옆면은 아래로 갈수록 어두워지는 스튜디오 조명. */
    private fun shade(face: Face, nx: Float, ny: Float, nz: Float): Int {
        val diffuse = max(0f, nx * LIGHT_X + ny * LIGHT_Y + nz * LIGHT_Z)
        val sky = max(0f, nz)
        val light = 0.42f + 0.42f * diffuse + 0.26f * sky
        val highlight = max(0f, nx * HALF_X + ny * HALF_Y + nz * HALF_Z).pow(26) * face.gloss * 255f
        return Color.rgb(
            channel(Color.red(face.color) * light + highlight),
            channel(Color.green(face.color) * light + highlight),
            channel(Color.blue(face.color) * light + highlight),
        )
    }

    private fun channel(value: Float): Int = min(255f, max(0f, value)).toInt()

    private fun buildBody() {
        for (row in 0 until BODY_ROWS) {
            val y0 = REAR_Y + (FRONT_Y - REAR_Y) * row / BODY_ROWS
            val y1 = REAR_Y + (FRONT_Y - REAR_Y) * (row + 1) / BODY_ROWS
            for (column in 0 until BODY_COLUMNS) {
                val s0 = column / BODY_COLUMNS.toFloat()
                val s1 = (column + 1) / BODY_COLUMNS.toFloat()
                val midY = (y0 + y1) / 2
                val midS = (s0 + s1) / 2
                val midZ = surfaceZ(midY, midS)
                val midX = surfaceX(midY, midS)
                val color = material(midX, midY, midZ, midS)
                val gloss = glossOf(color)
                for (side in intArrayOf(-1, 1)) {
                    addPolygon(
                        floatArrayOf(
                            side * surfaceX(y0, s0), y0, surfaceZ(y0, s0),
                            side * surfaceX(y1, s0), y1, surfaceZ(y1, s0),
                            side * surfaceX(y1, s1), y1, surfaceZ(y1, s1),
                            side * surfaceX(y0, s1), y0, surfaceZ(y0, s1),
                        ),
                        color, gloss,
                    )
                }
            }
        }
    }

    /** 앞뒤 끝 단면을 막는다. 전조등과 후미등은 여기에 재질로 나타난다. */
    private fun buildEndCap(y: Float) {
        for (column in 0 until BODY_COLUMNS) {
            val s0 = column / BODY_COLUMNS.toFloat()
            val s1 = (column + 1) / BODY_COLUMNS.toFloat()
            for (slice in 0 until CAP_SLICES) {
                val a0 = -1f + 2f * slice / CAP_SLICES
                val a1 = -1f + 2f * (slice + 1) / CAP_SLICES
                val z0 = surfaceZ(y, s0)
                val z1 = surfaceZ(y, s1)
                val x00 = surfaceX(y, s0) * a0
                val x01 = surfaceX(y, s0) * a1
                val x10 = surfaceX(y, s1) * a0
                val x11 = surfaceX(y, s1) * a1
                val color = material((x00 + x11) / 2, y, (z0 + z1) / 2, (s0 + s1) / 2)
                addPolygon(floatArrayOf(x00, y, z0, x01, y, z0, x11, y, z1, x10, y, z1), color, glossOf(color))
            }
        }
    }

    private fun glossOf(color: Int): Float = when (color) {
        SILVER -> 0.62f
        GLASS, ROOF -> 0.16f
        else -> 0.08f
    }

    private fun material(x: Float, y: Float, z: Float, s: Float): Int {
        val absX = abs(x)
        // 앞 범퍼의 얇은 주간주행등 띠
        if (y > 1.98f && z in 0.60f..0.71f) {
            return if (absX > 0.26f && z in 0.635f..0.68f) HEADLIGHT else CLADDING
        }
        // 뒤쪽 후미등 띠
        if (y < -2.10f && z in 0.74f..0.92f) {
            return if (z in 0.78f..0.88f && (absX * 12f).toInt() % 2 == 0) TAILLIGHT else CLADDING
        }
        if (z < 0.43f) return CLADDING
        // 앞유리: 위로 갈수록 뒤로 눕는다
        if (y in 0.27f..1.12f && s > 0.585f) return GLASS
        // 파노라마 지붕, 양옆은 은색 레일
        if (y in -1.52f..0.27f && s > 0.80f) return ROOF
        // 뒷유리
        if (y in -2.16f..-1.68f && s > 0.70f) return GLASS
        // 옆유리: 앞쪽 A필러와 뒤쪽 C필러가 비스듬하다
        if (s in 0.53f..0.785f) {
            val height = (s - 0.53f) / 0.255f
            val frontEdge = 1.03f - 0.72f * height
            val rearEdge = -1.86f + 0.42f * height
            if (y in rearEdge..frontEdge) return if (abs(y + 0.36f) < 0.055f) PILLAR else GLASS
        }
        return SILVER
    }

    private fun buildTires() {
        for (side in intArrayOf(-1, 1)) for (y in AXLES) {
            for (i in 0 until WHEEL_SEGMENTS) {
                val a0 = i * 2 * PI.toFloat() / WHEEL_SEGMENTS
                val a1 = (i + 1) * 2 * PI.toFloat() / WHEEL_SEGMENTS
                val inner = side * 0.68f
                val outer = side * 0.965f
                addOriented(
                    floatArrayOf(
                        inner, y + TIRE_RADIUS * cos(a0), WHEEL_Z + TIRE_RADIUS * sin(a0),
                        outer, y + TIRE_RADIUS * cos(a0), WHEEL_Z + TIRE_RADIUS * sin(a0),
                        outer, y + TIRE_RADIUS * cos(a1), WHEEL_Z + TIRE_RADIUS * sin(a1),
                        inner, y + TIRE_RADIUS * cos(a1), WHEEL_Z + TIRE_RADIUS * sin(a1),
                    ),
                    TIRE, 0.05f, floatArrayOf(side * 0.82f, y, WHEEL_Z), 0,
                )
            }
        }
    }

    private fun buildWheels() {
        for (side in intArrayOf(-1, 1)) for (y in AXLES) {
            val x = side * WHEEL_X
            archAbove(x, y, side)
            disc(x + side * DECAL, y, TIRE_RADIUS, TIRE, 0.05f)
            disc(x + side * DECAL * 2, y, 0.295f, RIM_DARK, 0.15f)
            disc(x + side * DECAL * 3, y, 0.28f, RIM_INNER, 0.05f)
            // 두 갈래 스포크
            for (spoke in 0 until 5) {
                val angle = spoke * 2 * PI.toFloat() / 5 + 0.3f
                for (offset in floatArrayOf(-0.16f, 0.16f)) {
                    addPolygon(
                        floatArrayOf(
                            x + side * DECAL * 4, y + 0.06f * cos(angle), WHEEL_Z + 0.06f * sin(angle),
                            x + side * DECAL * 4, y + 0.28f * cos(angle + offset - 0.05f), WHEEL_Z + 0.28f * sin(angle + offset - 0.05f),
                            x + side * DECAL * 4, y + 0.28f * cos(angle + offset + 0.05f), WHEEL_Z + 0.28f * sin(angle + offset + 0.05f),
                        ),
                        RIM_LIGHT, 0.4f,
                    )
                }
            }
            disc(x + side * DECAL * 5, y, 0.07f, RIM_DARK, 0.3f)
        }
    }

    /** 휠 위쪽의 검은 펜더 몰딩. 차체 아래로는 내려가지 않게 위쪽 호만 만든다. */
    private fun archAbove(x: Float, y: Float, side: Int) {
        val count = 20
        val points = FloatArray((count + 1) * 3)
        for (i in 0..count) {
            val angle = -0.12f + (PI.toFloat() + 0.24f) * i / count
            points[i * 3] = x
            points[i * 3 + 1] = y + ARCH_RADIUS * cos(angle)
            points[i * 3 + 2] = WHEEL_Z + ARCH_RADIUS * sin(angle)
        }
        addPolygon(points, CLADDING, 0.05f)
        check(side != 0)
    }

    private fun disc(x: Float, y: Float, radius: Float, color: Int, gloss: Float) {
        val points = FloatArray(WHEEL_SEGMENTS * 3)
        for (i in 0 until WHEEL_SEGMENTS) {
            val angle = i * 2 * PI.toFloat() / WHEEL_SEGMENTS
            points[i * 3] = x
            points[i * 3 + 1] = y + radius * cos(angle)
            points[i * 3 + 2] = WHEEL_Z + radius * sin(angle)
        }
        addPolygon(points, color, gloss)
    }

    private fun buildSideDetails() {
        for (side in intArrayOf(-1, 1)) {
            val x = side * 0.975f
            // 도어 틈
            for (y in floatArrayOf(-1.02f, -0.36f, 0.62f)) {
                addPolygon(floatArrayOf(x, y - 0.01f, 0.46f, x, y + 0.01f, 0.46f, x, y + 0.01f, 0.95f, x, y - 0.01f, 0.95f), SEAM, 0f)
            }
            // 문 아래쪽의 비스듬한 검은 몰딩
            addPolygon(
                floatArrayOf(x, -1.02f, 0.43f, x, 0.98f, 0.43f, x, 0.98f, 0.52f, x, -1.02f, 0.62f),
                CLADDING, 0.05f,
            )
            // 도어 손잡이
            addPolygon(floatArrayOf(x, -0.78f, 0.84f, x, -0.5f, 0.84f, x, -0.5f, 0.87f, x, -0.78f, 0.87f), SEAM, 0f)
            addPolygon(floatArrayOf(x, 0.18f, 0.84f, x, 0.46f, 0.84f, x, 0.46f, 0.87f, x, 0.18f, 0.87f), SEAM, 0f)
        }
    }

    private fun buildMirrors() {
        for (side in intArrayOf(-1, 1)) {
            val inner = side * 0.86f
            val outer = side * 1.14f
            val y0 = 0.90f
            val y1 = 1.03f
            val z0 = 0.99f
            val z1 = 1.13f
            val own = floatArrayOf(side * 1.0f, 0.965f, 1.06f)
            fun quad(vararg p: Float) = addOriented(p, SILVER, 0.5f, own, side)
            quad(inner, y0, z0, outer, y0, z0, outer, y0, z1, inner, y0, z1)
            quad(inner, y1, z0, outer, y1, z0, outer, y1, z1, inner, y1, z1)
            quad(outer, y0, z0, outer, y1, z0, outer, y1, z1, outer, y0, z1)
            quad(inner, y0, z1, outer, y0, z1, outer, y1, z1, inner, y1, z1)
        }
    }

    private fun addPolygon(points: FloatArray, color: Int, gloss: Float) {
        addOriented(points, color, gloss, BODY_CENTER, 0)
    }

    /** 면의 법선을 [center]에서 바깥쪽으로 향하게 맞춰 등록한다. */
    private fun addOriented(points: FloatArray, color: Int, gloss: Float, center: FloatArray, side: Int) {
        var nx = 0f
        var ny = 0f
        var nz = 0f
        var mx = 0f
        var my = 0f
        var mz = 0f
        val count = points.size / 3
        for (i in 0 until count) {
            val j = (i + 1) % count
            val ax = points[i * 3]
            val ay = points[i * 3 + 1]
            val az = points[i * 3 + 2]
            val bx = points[j * 3]
            val by = points[j * 3 + 1]
            val bz = points[j * 3 + 2]
            nx += (ay - by) * (az + bz)
            ny += (az - bz) * (ax + bx)
            nz += (ax - bx) * (ay + by)
            mx += ax
            my += ay
            mz += az
        }
        val length = sqrt(nx * nx + ny * ny + nz * nz)
        if (length == 0f) return
        nx /= length
        ny /= length
        nz /= length
        val outward = nx * (mx / count - center[0]) + ny * (my / count - center[1]) + nz * (mz / count - center[2])
        if (outward < 0) {
            nx = -nx
            ny = -ny
            nz = -nz
        }
        faces += Face(points, nx, ny, nz, color, gloss, side)
    }

    private fun surfaceX(y: Float, s: Float): Float {
        val width = spline(WIDTH, y)
        val roofHalf = spline(ROOF_HALF, y)
        return when {
            s <= LOWER_END -> width * (0.955f + 0.045f * sin(PI.toFloat() * s / LOWER_END))
            s <= SHOULDER_END -> lerp(width * 0.955f, width * 0.90f, (s - LOWER_END) / (SHOULDER_END - LOWER_END))
            s <= CABIN_END -> lerp(width * 0.90f, min(roofHalf, width * 0.90f), (s - SHOULDER_END) / (CABIN_END - SHOULDER_END))
            else -> min(roofHalf, width * 0.90f) * (1f - (s - CABIN_END) / (1f - CABIN_END))
        }
    }

    private fun surfaceZ(y: Float, s: Float): Float {
        val belt = spline(BELT, y)
        val top = max(spline(TOP, y), belt + 0.05f)
        return when {
            s <= LOWER_END -> lerp(BODY_BOTTOM, belt - 0.03f, s / LOWER_END)
            s <= SHOULDER_END -> lerp(belt - 0.03f, belt + 0.01f, (s - LOWER_END) / (SHOULDER_END - LOWER_END))
            s <= CABIN_END -> lerp(belt + 0.01f, top - 0.04f, (s - SHOULDER_END) / (CABIN_END - SHOULDER_END))
            else -> top - 0.04f + 0.04f * sin((s - CABIN_END) / (1f - CABIN_END) * PI.toFloat() / 2)
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** 값 표를 Catmull-Rom 곡선으로 잇는다. 표는 (y, 값) 쌍이다. */
    private fun spline(keys: FloatArray, y: Float): Float {
        val count = keys.size / 2
        var index = 0
        while (index < count - 2 && y > keys[(index + 1) * 2]) index++
        val y0 = keys[index * 2]
        val y1 = keys[(index + 1) * 2]
        val t = ((y - y0) / (y1 - y0)).coerceIn(0f, 1f)
        val p0 = keys[max(index - 1, 0) * 2 + 1]
        val p1 = keys[index * 2 + 1]
        val p2 = keys[(index + 1) * 2 + 1]
        val p3 = keys[min(index + 2, count - 1) * 2 + 1]
        return 0.5f * (
            2 * p1 + (p2 - p0) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t +
                (3 * p1 - p0 - 3 * p2 + p3) * t * t * t
            )
    }

    private companion object {
        const val REAR_Y = -2.32f
        const val FRONT_Y = 2.32f
        const val BODY_BOTTOM = 0.30f
        const val BODY_ROWS = 72
        const val BODY_COLUMNS = 32
        const val CAP_SLICES = 14

        const val LOWER_END = 0.42f
        const val SHOULDER_END = 0.50f
        const val CABIN_END = 0.80f

        val TOP = floatArrayOf(
            -2.32f, 1.00f, -2.18f, 1.08f, -1.85f, 1.50f, -1.2f, 1.59f, -0.2f, 1.61f, 0.3f, 1.56f,
            0.75f, 1.30f, 1.15f, 1.03f, 1.7f, 0.96f, 2.1f, 0.84f, 2.32f, 0.72f,
        )
        val BELT = floatArrayOf(
            -2.32f, 0.93f, -2.0f, 0.99f, -1f, 1.00f, 0.5f, 0.98f, 1.15f, 0.95f, 1.8f, 0.88f, 2.15f, 0.76f, 2.32f, 0.66f,
        )
        val WIDTH = floatArrayOf(
            -2.32f, 0.74f, -2.24f, 0.86f, -2.0f, 0.93f, -1.5f, 0.95f, 0f, 0.955f, 1.5f, 0.945f, 1.95f, 0.90f, 2.2f, 0.80f, 2.32f, 0.66f,
        )
        val ROOF_HALF = floatArrayOf(
            -2.32f, 0.60f, -1.9f, 0.66f, -0.5f, 0.70f, 0.3f, 0.66f, 0.8f, 0.72f, 1.2f, 0.80f, 2f, 0.70f, 2.32f, 0.50f,
        )

        val BODY_CENTER = floatArrayOf(0f, 0f, 0.75f)
        val AXLES = floatArrayOf(-1.5f, 1.5f)
        const val WHEEL_X = 0.968f
        const val WHEEL_Z = 0.41f
        const val TIRE_RADIUS = 0.41f
        const val ARCH_RADIUS = 0.49f
        const val WHEEL_SEGMENTS = 28
        const val DECAL = 0.003f

        const val HEADING_STEP = 2
        const val SUPERSAMPLE = 2
        const val SPRITE_METERS = 6.6f

        // 지도 카메라와 같은 내려다보는 각도 (약 34도)
        const val SCREEN_Y_PER_Y = 0.555f
        const val SCREEN_Y_PER_Z = 0.832f
        const val VIEW_Y = -0.832f
        const val VIEW_Z = 0.555f

        const val LIGHT_X = -0.47f
        const val LIGHT_Y = -0.36f
        const val LIGHT_Z = 0.81f
        const val HALF_X = -0.26f
        const val HALF_Y = -0.66f
        const val HALF_Z = 0.71f

        val SHADOW_OUTLINE = floatArrayOf(
            -0.7f, 2.45f, 0.7f, 2.45f, 1.08f, 2.0f, 1.08f, -2.0f, 0.75f, -2.4f, -0.75f, -2.4f, -1.08f, -2.0f, -1.08f, 2.0f,
        )

        val SILVER = Color.rgb(226, 229, 232)
        val CLADDING = Color.rgb(34, 36, 40)
        val GLASS = Color.rgb(30, 36, 42)
        val ROOF = Color.rgb(20, 23, 27)
        val PILLAR = Color.rgb(70, 74, 80)
        val TIRE = Color.rgb(14, 15, 17)
        val RIM_DARK = Color.rgb(40, 43, 48)
        val RIM_INNER = Color.rgb(18, 20, 23)
        val RIM_LIGHT = Color.rgb(120, 125, 130)
        val SEAM = Color.rgb(105, 110, 115)
        val HEADLIGHT = Color.rgb(255, 255, 248)
        val TAILLIGHT = Color.rgb(235, 30, 45)
    }
}
