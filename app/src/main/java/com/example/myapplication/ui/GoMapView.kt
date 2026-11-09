package com.example.myapplication.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import com.example.myapplication.map.Facility
import com.example.myapplication.map.MapScene
import com.example.myapplication.model.Ambience
import com.example.myapplication.model.GeoPoint
import com.example.myapplication.model.Station
import com.example.myapplication.nav.NavRoute
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 포켓몬 GO처럼 내 차를 중심에 두고 비스듬히 내려다보는 지도.
 * 도로와 공원은 장식용으로 생성한 배경이며 실제 지도가 아니다.
 * 충전소의 상대 위치와 예약 가능 반경만 실제 거리 기준으로 그린다.
 */
class GoMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onStationTapped: ((String) -> Unit)? = null

    private var origin: GeoPoint? = null
    private var car: GeoPoint? = null
    private var heading = 0f
    private var stations: List<Station> = emptyList()
    private var selectedId: String? = null
    private var reservedStationId: String? = null
    private var reserveRadius = 0.0

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    /** 건물 이름 글자 테두리. 티맵 내비처럼 상자 없이 글자만 보이도록 흰 외곽선을 두른다. */
    private val labelHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val path = Path()
    private val carModel = CarModel()
    private val oval = RectF()
    private val quadRight = FloatArray(4)
    private val quadForward = FloatArray(4)
    private var pathStarted = false
    private val markerHits = HashMap<String, Triple<Float, Float, Float>>()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    /** 기본 상태(0단계) 기준 확대/축소 단계 (-3..+5). 줌인은 최대 5번, 줌아웃은 최대 3번 가능하다. */
    var zoomStep = 0
        private set

    /** 지도 확대 배율. [DEFAULT_ZOOM]이 기본이고 두 손가락으로 조절한다. */
    var zoom = DEFAULT_ZOOM
        private set

    /** 애니메이션이 끝났을 때의 배율. 연속으로 눌러도 단계가 누적된다. */
    private var zoomTarget = DEFAULT_ZOOM
    private var zoomAnimator: ValueAnimator? = null

    /** 확대, 축소 가능 여부가 바뀔 수 있을 때 호출된다. */
    var onZoomLimitsChanged: (() -> Unit)? = null
    /** 애니메이션이 끝났을 때의 배율. */
    val zoomGoal get() = zoomTarget
    val canZoomIn get() = zoomStep < MAX_ZOOM_STEPS
    val canZoomOut get() = zoomStep > -MAX_ZOOM_OUT_STEPS

    /** 카메라가 바라보는 방위각(북=0, 동=90). 항상 내 차를 축으로 돈다. */
    var bearing = 0f
        private set
    private var bearingSin = 0f
    private var bearingCos = 1f
    private var bearingAnimator: ValueAnimator? = null

    /** 켜면 한 손가락으로 끌 때 지도를 옮기는 대신 내 차를 중심으로 돌린다. */
    var rotateMode = false

    /** 켜면 카메라가 차의 진행 방향을 따라 돈다. 손으로 돌리면 꺼진다. */
    var followHeading = false
    private var lastFrameAt = 0L

    private var route: NavRoute? = null
    private var scene: MapScene? = null
    private var facilities: List<Facility> = emptyList()

    // 다각형을 그릴 때 다시 쓰는 좌표 배열. 매 프레임 새로 만들지 않는다.
    private var viewXs = FloatArray(64)
    private var viewYs = FloatArray(64)
    private var clipXs = FloatArray(128)
    private var clipYs = FloatArray(128)
    private var buildingOrder = IntArray(0)
    private val buildingLabels = ArrayList<BuildingLabel>()
    /** 차가 달리고 있는 도로의 실제 폭(미터). 차 크기의 기준이다. */
    private var carRoadMeters = DEFAULT_CAR_ROAD_METERS
    private var carRoadCheckedAt: GeoPoint? = null
    private var carRoadScene: MapScene? = null
    private val placedLabels = ArrayList<RectF>()
    private var buildingDepth = FloatArray(0)
    private var routeProgress = 0.0

    /** [bearing]이 바뀔 때 호출된다. */
    var onBearingChanged: (() -> Unit)? = null

    private var lastTwistAngle = 0f
    private var twistTotal = 0f
    private var twisting = false

    // 내 차를 기준으로 카메라를 옮긴 거리(미터). 화면 오른쪽, 화면 안쪽 방향이며 지도를 끌면 바뀐다.
    private var panRight = 0f
    private var panForward = 0f
    private var panAnimator: ValueAnimator? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragging = false

    /** 지도를 옮겨 카메라가 내 차에서 벗어나 있는지 여부. */
    val isPanned get() = panRight != 0f || panForward != 0f

    /** 내 차의 화면 좌표. 테스트에서 기준점이 유지되는지 확인할 때 쓴다. */
    val carScreenX get() = screenX(-panRight, -panForward)
    val carScreenY get() = screenY(-panForward)

    /** [isPanned]가 바뀔 수 있을 때 호출된다. */
    var onPanChanged: (() -> Unit)? = null

    /** 이번 터치에 두 손가락이 닿았으면 손을 뗄 때 충전소 선택으로 처리하지 않는다. */
    private var gestureHadMultipleFingers = false
    private var pinchStartStep = 0
    private var pinchScale = 1f
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinchStartStep = zoomStep
                pinchScale = 1f
                zoomAnimator?.cancel()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pinchScale *= detector.scaleFactor
                val minStepZoom = zoomForStep(pinchStartStep - 1)
                val maxStepZoom = zoomForStep(pinchStartStep + 1)
                val baseZoom = zoomForStep(pinchStartStep)
                applyZoom((baseZoom * pinchScale).coerceIn(minStepZoom, maxStepZoom))
                zoomTarget = zoom
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                val nextStep = when {
                    pinchScale >= PINCH_STEP_THRESHOLD -> (pinchStartStep + 1).coerceAtMost(MAX_ZOOM_STEPS)
                    pinchScale <= 1f / PINCH_STEP_THRESHOLD -> (pinchStartStep - 1).coerceAtLeast(-MAX_ZOOM_OUT_STEPS)
                    else -> pinchStartStep
                }
                setZoomStep(nextStep, animate = false)
            }
        },
    ).apply { isQuickScaleEnabled = false }

    private val nearY get() = NEAR_Y / zoom

    private var fadeShader: Shader? = null
    private var skyShader: Shader? = null
    private var ambience: Ambience? = null

    // 투영 매개변수. onSizeChanged에서 화면 크기에 맞춘다.
    private var centerX = 0f
    private var horizonY = 0f
    private var focal = 0f
    private var unit = 1f

    /** 카메라를 북쪽 방향으로 되돌린다. 더 가까운 쪽으로 돈다. */
    fun resetBearing() {
        followHeading = false
        bearingAnimator?.cancel()
        val start = if (bearing > 180f) bearing - 360f else bearing
        if (start == 0f) return
        bearingAnimator = ValueAnimator.ofFloat(start, 0f).apply {
            duration = BEARING_RESET_MS
            addUpdateListener { applyBearing(it.animatedValue as Float) }
            start()
        }
    }

    private fun rotateBy(degrees: Float) {
        followHeading = false
        bearingAnimator?.cancel()
        applyBearing(bearing + degrees)
    }

    private fun applyBearing(degrees: Float) {
        bearing = ((degrees % 360f) + 360f) % 360f
        val radians = Math.toRadians(bearing.toDouble())
        bearingSin = sin(radians).toFloat()
        bearingCos = cos(radians).toFloat()
        onBearingChanged?.invoke()
    }

    // 동쪽, 북쪽 좌표와 화면 기준(오른쪽, 안쪽) 좌표를 서로 바꾼다.
    private fun toViewX(east: Float, north: Float) = east * bearingCos - north * bearingSin
    private fun toViewY(east: Float, north: Float) = east * bearingSin + north * bearingCos
    private fun toEast(right: Float, forward: Float) = right * bearingCos + forward * bearingSin
    private fun toNorth(right: Float, forward: Float) = -right * bearingSin + forward * bearingCos

    fun zoomTo(value: Float) = setZoomStep(stepForZoom(value), animate = true)

    /** 애니메이션 없이 바로 배율을 바꾼다. */
    fun setZoomNow(value: Float) = setZoomStep(stepForZoom(value), animate = false)

    /**
     * 멀리서 볼 때만 도로 폭과 건물 높이를 키운다. 가까이 볼수록 실제 크기로 돌아간다.
     * 0이면 실제 크기, 1이면 가장 많이 키운 상태다.
     */
    private val exaggeration
        get() = ((ln(TRUE_SCALE_ZOOM) - ln(zoom)) / (ln(TRUE_SCALE_ZOOM) - ln(FULL_EXAGGERATION_ZOOM))).coerceIn(0f, 1f)

    /** 실제 도로와 건물. 있으면 장식용 바둑판 도로 대신 그린다. */
    fun setScene(next: MapScene?) {
        scene = next
        val count = next?.buildings?.size ?: 0
        buildingOrder = IntArray(count) { it }
        buildingDepth = FloatArray(count)
    }

    fun setFacilities(next: List<Facility>) {
        facilities = next
    }

    /** 그릴 주행 경로. null이면 지운다. */
    fun setRoute(next: NavRoute?) {
        route = next
        routeProgress = 0.0
    }

    /** 출발점부터 지금까지 경로를 따라 간 거리(미터). 지난 길은 흐리게 그린다. */
    fun setRouteProgress(meters: Double) {
        routeProgress = meters
    }

    fun zoomIn() {
        if (!canZoomIn) return
        setZoomStep(zoomStep + 1, animate = true)
    }

    fun zoomOut() {
        if (!canZoomOut) return
        setZoomStep(zoomStep - 1, animate = true)
    }

    private fun setZoomStep(step: Int, animate: Boolean) {
        zoomStep = step.coerceIn(-MAX_ZOOM_OUT_STEPS, MAX_ZOOM_STEPS)
        val target = zoomForStep(zoomStep)
        if (animate) {
            animateZoomTo(target)
        } else {
            setZoomDirectly(target)
        }
    }

    private fun setZoomDirectly(value: Float) {
        zoomAnimator?.cancel()
        applyZoom(value.coerceIn(MIN_ZOOM, MAX_ZOOM))
        zoomTarget = zoom
        onZoomLimitsChanged?.invoke()
    }

    /**
     * 항상 내 차를 기준으로 확대, 축소한다. 지도를 옮긴 상태에서도 차가 화면의 같은 자리에 남도록
     * 카메라 거리를 배율에 반비례해 조절한다.
     */
    private fun applyZoom(value: Float) {
        val ratio = zoom / value
        panRight = (panRight * ratio).coerceIn(-MAX_PAN, MAX_PAN)
        panForward = (panForward * ratio).coerceIn(-MAX_PAN, MAX_PAN)
        zoom = value
    }

    private fun animateZoomTo(target: Float) {
        zoomTarget = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        zoomAnimator?.cancel()
        zoomAnimator = ValueAnimator.ofFloat(zoom, zoomTarget).apply {
            duration = ZOOM_ANIMATION_MS
            addUpdateListener { applyZoom(it.animatedValue as Float) }
            start()
        }
        onZoomLimitsChanged?.invoke()
    }

    /** 카메라를 내 차로 되돌린다. */
    fun recenter() {
        panAnimator?.cancel()
        if (!isPanned) return
        val startEast = panRight
        val startNorth = panForward
        panAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = RECENTER_ANIMATION_MS
            addUpdateListener {
                val remaining = it.animatedValue as Float
                panRight = startEast * remaining
                panForward = startNorth * remaining
                onPanChanged?.invoke()
            }
            start()
        }
    }

    /**
     * 끈 만큼 지도를 옮긴다. 어디를 짚어도 같은 속도가 되도록 내 차 위치의 축척을 쓴다.
     * 짚은 지점의 축척을 쓰면 지평선 근처에서 조금만 끌어도 수 km를 건너뛴다.
     */
    private fun panBy(deltaX: Float, deltaY: Float) {
        val metersPerPixelX = CAMERA_BACK / (focal * zoom)
        val metersPerPixelY = CAMERA_BACK * CAMERA_BACK / (focal * CAMERA_HEIGHT * zoom)
        panRight = (panRight - deltaX * metersPerPixelX).coerceIn(-MAX_PAN, MAX_PAN)
        panForward = (panForward + deltaY * metersPerPixelY).coerceIn(-MAX_PAN, MAX_PAN)
        onPanChanged?.invoke()
    }

    fun setCar(position: GeoPoint, headingDegrees: Float) {
        if (origin == null || position.distanceTo(origin!!) > ORIGIN_RESET_METERS) origin = position
        car = position
        heading = headingDegrees
    }

    fun setStations(
        stations: List<Station>,
        selectedId: String?,
        reservedStationId: String?,
        reserveRadiusMeters: Double,
    ) {
        this.stations = stations
        this.selectedId = selectedId
        this.reservedStationId = reservedStationId
        this.reserveRadius = reserveRadiusMeters
    }

    fun setAmbience(next: Ambience) {
        if (next == ambience) return
        val skyChanged = next.skyTop != ambience?.skyTop || next.skyBottom != ambience?.skyBottom
        ambience = next
        carModel.setTint(next.carTint)
        if (skyChanged) rebuildSkyShaders()
    }

    private fun rebuildSkyShaders() {
        val colors = ambience ?: return
        if (height == 0) return
        skyShader = LinearGradient(0f, 0f, 0f, horizonY, colors.skyTop, colors.skyBottom, Shader.TileMode.CLAMP)
        fadeShader = LinearGradient(
            0f, horizonY, 0f, horizonY + height * 0.14f,
            colors.skyBottom, colors.skyBottom and 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        centerX = w / 2f
        horizonY = h * 0.14f
        focal = h * 0.72f
        unit = min(w, h) / 1440f
        rebuildSkyShaders()
    }

    override fun onDraw(canvas: Canvas) {
        val position = car ?: return
        val look = ambience ?: return
        // 부동소수 정밀도가 떨어지지 않도록 하루 단위로 잘라 쓴다.
        val time = (System.currentTimeMillis() % 86_400_000L) / 1000f
        canvas.drawColor(look.ground)
        drawBackground(canvas, position, look, time)
        updateCarRoad(position)
        followCarHeading()
        drawRangeRing(canvas, look, time)
        drawRoute(canvas, position)
        drawBuildings(canvas, position, look)
        drawFacilities(canvas, position)
        drawStations(canvas, position, time)
        drawCar(canvas, look, time)
        drawPrecipitation(canvas, look, time)
        if (scene != null) drawAttribution(canvas)
        postInvalidateOnAnimation()
    }

    private fun drawBackground(canvas: Canvas, position: GeoPoint, look: Ambience, time: Float) {
        val (baseEast, baseNorth) = position.metersFrom(origin ?: position)
        val cameraEast = baseEast + toEast(panRight, panForward)
        val cameraNorth = baseNorth + toNorth(panRight, panForward)
        val viewSide = VIEW_SIDE / zoom
        val viewBack = VIEW_BACK / zoom
        val viewFront = VIEW_FRONT / zoom

        scene?.let { real ->
            drawScene(canvas, real, position, look)
            drawSky(canvas, look, time)
            return
        }

        // 돌아간 시야 사각형을 모두 덮는 동서남북 범위. 회전이 없으면 시야 사각형과 같다.
        val spanEast = abs(viewSide * bearingCos)
        val spanNorth = abs(viewSide * bearingSin)
        val minEast = min(toEast(0f, viewBack), toEast(0f, viewFront)) - spanEast
        val maxEast = max(toEast(0f, viewBack), toEast(0f, viewFront)) + spanEast
        val minNorth = min(toNorth(0f, viewBack), toNorth(0f, viewFront)) - spanNorth
        val maxNorth = max(toNorth(0f, viewBack), toNorth(0f, viewFront)) + spanNorth
        val firstCellX = floor((cameraEast + minEast) / BLOCK).toInt()
        val lastCellX = floor((cameraEast + maxEast) / BLOCK).toInt()
        val firstCellY = floor((cameraNorth + minNorth) / BLOCK).toInt()
        val lastCellY = floor((cameraNorth + maxNorth) / BLOCK).toInt()

        // 공원: 위치에서 결정되는 해시로 고르므로 이동해도 같은 자리에 남는다.
        fill.color = look.park
        for (cellY in firstCellY..lastCellY) for (cellX in firstCellX..lastCellX) {
            if ((cellX * 73856093 xor cellY * 19349663).mod(7) != 0) continue
            val x0 = (cellX * BLOCK - cameraEast).toFloat() + ROAD_MARGIN
            val y0 = (cellY * BLOCK - cameraNorth).toFloat() + ROAD_MARGIN
            val size = BLOCK.toFloat() - ROAD_MARGIN * 2
            val middleRight = toViewX(x0 + size / 2, y0 + size / 2)
            val middleForward = toViewY(x0 + size / 2, y0 + size / 2)
            if (abs(middleRight) > viewSide + BLOCK || middleForward < viewBack - BLOCK || middleForward > viewFront + BLOCK) continue
            drawGroundQuad(canvas, x0, y0, x0 + size, y0 + size)
        }

        // 도로: 남북 방향, 동서 방향 격자
        for (cellX in firstCellX..lastCellX + 1) {
            val major = cellX.mod(4) == 0
            val x = (cellX * BLOCK - cameraEast).toFloat()
            val half = if (major) MAJOR_ROAD else MINOR_ROAD
            fill.color = if (major) look.majorRoad else look.minorRoad
            drawGroundQuad(canvas, x - half, minNorth, x + half, maxNorth)
        }
        for (cellY in firstCellY..lastCellY + 1) {
            val major = cellY.mod(4) == 0
            val y = (cellY * BLOCK - cameraNorth).toFloat()
            val half = if (major) MAJOR_ROAD else MINOR_ROAD
            fill.color = if (major) look.majorRoad else look.minorRoad
            drawGroundQuad(canvas, minEast, y - half, maxEast, y + half)
        }

        drawSky(canvas, look, time)
    }

    /** 녹지와 물을 깔고 그 위에 실제 도로를 그린다. 멀리 있는 도로도 보이도록 폭을 키워 그린다. */
    private fun drawScene(canvas: Canvas, real: MapScene, position: GeoPoint, look: Ambience) {
        val (offsetEast, offsetNorth) = position.metersFrom(real.origin)
        val east = offsetEast.toFloat()
        val north = offsetNorth.toFloat()
        for (area in real.areas) {
            fill.color = when (area.kind) {
                MapScene.AreaKind.GREEN -> look.park
                MapScene.AreaKind.PITCH -> blend(look.park, PITCH_COLOR, 0.35f)
                MapScene.AreaKind.WATER -> blend(look.ground, WATER_COLOR, 0.65f)
            }
            fillGroundPolygon(canvas, toView(area.east, area.north, east, north))
        }
        stroke.strokeCap = Paint.Cap.ROUND
        for (road in real.roads) {
            stroke.color = if (road.major) look.majorRoad else look.minorRoad
            val count = toView(road.east, road.north, east, north)
            for (i in 0 until count - 1) {
                var ax = viewXs[i]
                var ay = viewYs[i]
                var bx = viewXs[i + 1]
                var by = viewYs[i + 1]
                if (ay < nearY && by < nearY) continue
                if (ay < nearY) {
                    ax += (bx - ax) * (nearY - ay) / (by - ay); ay = nearY
                } else if (by < nearY) {
                    bx += (ax - bx) * (nearY - by) / (ay - by); by = nearY
                }
                stroke.strokeWidth = road.widthMeters * roadScale * pixelsPerMeter((ay + by) / 2)
                canvas.drawLine(screenX(ax, ay), screenY(ay), screenX(bx, by), screenY(by), stroke)
            }
        }
        stroke.strokeCap = Paint.Cap.BUTT
    }

    /**
     * 건물을 층수만큼 세운 블록으로 그린다. 먼 건물부터 그리고, 카메라를 등진 벽은 그리지 않는다.
     * 위에서 멀리 내려다보는 시점이라 실제 높이로는 거의 안 보여서 높이를 키운다.
     */
    private fun drawBuildings(canvas: Canvas, position: GeoPoint, look: Ambience) {
        val real = scene ?: return
        val (offsetEast, offsetNorth) = position.metersFrom(real.origin)
        val east = offsetEast.toFloat()
        val north = offsetNorth.toFloat()
        val far = BUILDING_FAR_METERS
        val heightScale = 1f + (BUILDING_HEIGHT_SCALE - 1f) * exaggeration
        real.buildings.forEachIndexed { index, building ->
            val dx = building.east[0] - east
            val dy = building.north[0] - north
            buildingDepth[index] = toViewY(dx, dy) - panForward
        }
        val order = buildingOrder.sortedByDescending { buildingDepth[it] }
        val cameraY = -CAMERA_BACK / zoom
        val wall = tint(WALL_COLOR, look.carTint)
        val roof = tint(ROOF_COLOR, look.carTint)
        buildingLabels.clear()
        for (index in order) {
            if (buildingDepth[index] < nearY + 100f || buildingDepth[index] > far) continue
            val building = real.buildings[index]
            val count = toView(building.east, building.north, east, north)
            if ((0 until count).any { viewYs[it] < nearY + 1f }) continue
            val height = building.heightMeters * heightScale
            // 꼭짓점 순서(시계/반시계)에 따라 바깥쪽 방향이 달라진다.
            var area = 0f
            for (i in 0 until count - 1) area += viewXs[i] * viewYs[i + 1] - viewXs[i + 1] * viewYs[i]
            val outward = if (area > 0) 1f else -1f
            for (i in 0 until count - 1) {
                val ax = viewXs[i]; val ay = viewYs[i]
                val bx = viewXs[i + 1]; val by = viewYs[i + 1]
                val normalX = (by - ay) * outward
                val normalY = -(bx - ax) * outward
                // 카메라 쪽을 보는 벽만 그린다.
                if (normalX * (0f - (ax + bx) / 2) + normalY * (cameraY - (ay + by) / 2) <= 0f) continue
                val length = hypot(normalX, normalY).coerceAtLeast(0.001f)
                val light = 0.72f + 0.28f * ((normalX * LIGHT_X + normalY * LIGHT_Y) / length).coerceIn(-1f, 1f)
                fill.color = shadeColor(wall, light)
                fill.alpha = BUILDING_ALPHA
                path.rewind()
                path.moveTo(screenX(ax, ay), screenY(ay))
                path.lineTo(screenX(bx, by), screenY(by))
                path.lineTo(screenX(bx, by), screenYAt(by, height))
                path.lineTo(screenX(ax, ay), screenYAt(ay, height))
                path.close()
                canvas.drawPath(path, fill)
            }
            path.rewind()
            for (i in 0 until count) {
                val x = screenX(viewXs[i], viewYs[i])
                val y = screenYAt(viewYs[i], height)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            fill.color = roof
            fill.alpha = BUILDING_ALPHA
            canvas.drawPath(path, fill)
            stroke.color = shadeColor(wall, 0.6f)
            stroke.alpha = BUILDING_EDGE_ALPHA
            stroke.strokeWidth = 1.5f * unit
            canvas.drawPath(path, stroke)
            building.tmapName?.let { name -> collectBuildingLabel(building, name, height, east, north) }
        }
        fill.alpha = 255
        stroke.alpha = 255
        drawBuildingLabels(canvas)
    }

    private class BuildingLabel(val name: String, val x: Float, val y: Float, val depth: Float, val scale: Float)

    /** 이름이 있는 건물의 지붕 중심 화면 위치를 모아 둔다. 가까운 건물만 이름을 붙인다. */
    private fun collectBuildingLabel(building: MapScene.Building, name: String, height: Float, east: Float, north: Float) {
        val dx = building.centerEast - east
        val dy = building.centerNorth - north
        if (hypot(dx, dy) > BUILDING_LABEL_METERS) return
        val x = toViewX(dx, dy) - panRight
        val y = toViewY(dx, dy) - panForward
        if (y < nearY + 50f || nearHorizon(y)) return
        buildingLabels += BuildingLabel(name, screenX(x, y), screenYAt(y, height), y, depthScale(y))
    }

    /**
     * 건물 이름을 지붕 위에 그린다. 가까운 건물부터 놓고, 이미 놓은 이름과 겹치거나
     * 같은 이름(캠퍼스처럼 여러 동이 한 이름)이 있으면 건너뛴다.
     */
    private fun drawBuildingLabels(canvas: Canvas) {
        if (buildingLabels.isEmpty()) return
        buildingLabels.sortBy { it.depth }
        placedLabels.clear()
        val drawn = HashSet<String>()
        for (label in buildingLabels) {
            if (placedLabels.size >= MAX_BUILDING_LABELS) break
            if (label.name in drawn) continue
            text.textSize = max(18f * unit, 24f * unit * label.scale)
            val halo = text.textSize * 0.18f
            val half = text.measureText(label.name) / 2 + halo
            val baseline = label.y - 6f * unit
            val rect = RectF(label.x - half, baseline - text.textSize - halo, label.x + half, baseline + text.textSize * 0.25f + halo)
            // 화면 가장자리에 걸려 잘리는 이름은 그리지 않는다.
            if (rect.top < 0f || rect.bottom > height || rect.left < 0f || rect.right > width) continue
            if (placedLabels.any { RectF.intersects(it, rect) }) continue
            placedLabels += rect
            drawn += label.name
            labelHalo.textSize = text.textSize
            labelHalo.strokeWidth = halo * 2
            canvas.drawText(label.name, label.x, baseline, labelHalo)
            text.color = BUILDING_LABEL_COLOR
            canvas.drawText(label.name, label.x, baseline, text)
        }
    }

    /** 편의점, 카페, 음식점을 작은 표식으로 그린다. 가까운 곳만 이름을 붙인다. */
    private fun drawFacilities(canvas: Canvas, position: GeoPoint) {
        for (facility in facilities) {
            val (east, north) = facility.position.metersFrom(position)
            val x = toViewX(east.toFloat(), north.toFloat()) - panRight
            val y = toViewY(east.toFloat(), north.toFloat()) - panForward
            if (y < nearY + 50f || nearHorizon(y)) continue
            val scale = depthScale(y)
            val radius = FACILITY_RADIUS * unit * scale
            val cx = screenX(x, y)
            val cy = screenY(y) - radius * 1.2f
            fill.color = FACILITY_COLORS.getValue(facility.kind)
            canvas.drawCircle(cx, cy, radius, fill)
            stroke.color = Color.WHITE
            stroke.strokeWidth = radius * 0.16f
            canvas.drawCircle(cx, cy, radius, stroke)
            text.color = Color.WHITE
            text.textSize = radius * 1.05f
            canvas.drawText(facility.kind.glyph, cx, cy + radius * 0.38f, text)
            if (hypot(east, north) < FACILITY_LABEL_METERS) {
                text.textSize = max(18f * unit, 22f * unit * scale)
                val half = text.measureText(facility.name) / 2 + text.textSize * 0.4f
                fill.color = LABEL_PLATE_COLOR
                canvas.drawRoundRect(
                    cx - half, cy + radius * 1.2f, cx + half, cy + radius * 1.2f + text.textSize * 1.4f,
                    text.textSize * 0.4f, text.textSize * 0.4f, fill,
                )
                canvas.drawText(facility.name, cx, cy + radius * 1.2f + text.textSize * 1.05f, text)
            }
        }
    }

    private fun drawAttribution(canvas: Canvas) {
        text.textAlign = Paint.Align.RIGHT
        text.textSize = 18f * unit
        text.color = Color.argb(200, 255, 255, 255)
        canvas.drawText("© OpenStreetMap contributors", width - 16f * unit, 30f * unit, text)
        text.textAlign = Paint.Align.CENTER
    }

    /** 장면 좌표를 차 기준 화면 좌표(오른쪽, 안쪽)로 바꿔 [viewXs], [viewYs]에 담는다. */
    private fun toView(east: FloatArray, north: FloatArray, offsetEast: Float, offsetNorth: Float): Int {
        val count = east.size
        if (viewXs.size < count) {
            viewXs = FloatArray(count * 2)
            viewYs = FloatArray(count * 2)
        }
        for (i in 0 until count) {
            val dx = east[i] - offsetEast
            val dy = north[i] - offsetNorth
            viewXs[i] = toViewX(dx, dy) - panRight
            viewYs[i] = toViewY(dx, dy) - panForward
        }
        return count
    }

    /** [toView]로 바꾼 다각형을 카메라 뒤쪽을 잘라내고 바닥에 칠한다. */
    private fun fillGroundPolygon(canvas: Canvas, count: Int) {
        if (count < 3) return
        if ((0 until count).all { viewYs[it] < nearY }) return
        if (clipXs.size < count * 2) {
            clipXs = FloatArray(count * 2)
            clipYs = FloatArray(count * 2)
        }
        var out = 0
        for (i in 0 until count) {
            val next = (i + 1) % count
            val ay = viewYs[i]
            val by = viewYs[next]
            if (ay >= nearY) {
                clipXs[out] = viewXs[i]; clipYs[out] = ay; out++
            }
            if ((ay >= nearY) != (by >= nearY)) {
                clipXs[out] = viewXs[i] + (viewXs[next] - viewXs[i]) * (nearY - ay) / (by - ay)
                clipYs[out] = nearY
                out++
            }
        }
        if (out < 3) return
        path.rewind()
        for (i in 0 until out) {
            val x = screenX(clipXs[i], clipYs[i])
            val y = screenY(clipYs[i])
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, fill)
    }

    private fun nearHorizon(y: Float) = screenY(y) < horizonY + HORIZON_MARGIN * unit

    private fun pixelsPerMeter(y: Float) = focal * zoom / (y * zoom + CAMERA_BACK)

    private fun blend(from: Int, to: Int, amount: Float): Int = Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * amount).toInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * amount).toInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount).toInt(),
    )

    /** 낮밤 밝기([Ambience.carTint])를 건물 색에 곱한다. */
    private fun tint(color: Int, light: Int): Int = Color.rgb(
        Color.red(color) * Color.red(light) / 255,
        Color.green(color) * Color.green(light) / 255,
        Color.blue(color) * Color.blue(light) / 255,
    )

    private fun shadeColor(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255),
    )

    private fun drawSky(canvas: Canvas, look: Ambience, time: Float) {
        // 카메라가 돌면 하늘도 함께 흘러간다. 한 바퀴에 화면 SKY_SCREENS_PER_TURN개만큼 움직인다.
        val skyShift = bearing / 360f * SKY_SCREENS_PER_TURN
        fill.shader = skyShader
        canvas.drawRect(0f, 0f, width.toFloat(), horizonY, fill)
        fill.shader = null

        if (look.stars > 0f) {
            for (i in 0 until STAR_COUNT) {
                val x = fract(i * 0.6180339f - skyShift) * width
                val y = fract(i * 0.3247179f) * horizonY * 0.92f
                val twinkle = 0.6f + 0.4f * sin(time * 1.7f + i)
                fill.color = Color.WHITE
                fill.alpha = (look.stars * twinkle * 230).toInt()
                canvas.drawCircle(x, y, (1.2f + fract(i * 0.77f) * 1.6f) * unit, fill)
            }
        }
        // 해와 달은 하늘의 한 지점에 있으므로 돌리는 동안 화면 밖으로 나갈 수 있다.
        val skySpan = width * SKY_SCREENS_PER_TURN
        val celestialX = fract((look.celestialX - skyShift + 0.5f) / SKY_SCREENS_PER_TURN) * skySpan - width * 0.5f
        val celestialY = horizonY * 0.5f
        if (look.sun > 0f) {
            fill.color = SUN_COLOR
            fill.alpha = (look.sun * 70).toInt()
            canvas.drawCircle(celestialX, celestialY, 78f * unit, fill)
            fill.alpha = (look.sun * 255).toInt()
            canvas.drawCircle(celestialX, celestialY, 46f * unit, fill)
        }
        if (look.moon > 0f) {
            fill.color = MOON_COLOR
            fill.alpha = (look.moon * 60).toInt()
            canvas.drawCircle(celestialX, celestialY, 66f * unit, fill)
            fill.alpha = (look.moon * 255).toInt()
            canvas.drawCircle(celestialX, celestialY, 40f * unit, fill)
            fill.color = look.skyTop
            fill.alpha = (look.moon * 255).toInt()
            canvas.drawCircle(celestialX + 16f * unit, celestialY - 7f * unit, 33f * unit, fill)
        }
        if (look.clouds > 0.08f) {
            // 뭉치가 겹쳐도 진해지지 않도록 불투명하게 그린 뒤 레이어 전체를 반투명하게 합친다.
            val count = (look.clouds * CLOUD_COUNT).toInt().coerceAtLeast(1)
            val layer = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), horizonY + 40f * unit, (140 + look.clouds * 90).toInt())
            fill.color = desaturatedCloud(look)
            for (i in 0 until count) {
                val span = width + 500f * unit
                val x = fract(i * 0.381966f + time * 0.002f * (1f + fract(i * 0.53f)) - skyShift * width / span) * span - 250f * unit
                val y = horizonY * (0.3f + fract(i * 0.7548f) * 0.55f)
                val size = (34f + fract(i * 0.5698f) * 30f) * unit
                canvas.drawRoundRect(x - size * 2.3f, y - size * 0.3f, x + size * 2.3f, y + size * 0.55f, size * 0.42f, size * 0.42f, fill)
                canvas.drawCircle(x - size * 1.15f, y - size * 0.25f, size * 0.62f, fill)
                canvas.drawCircle(x - size * 0.2f, y - size * 0.55f, size * 0.95f, fill)
                canvas.drawCircle(x + size * 0.95f, y - size * 0.3f, size * 0.7f, fill)
            }
            canvas.restoreToCount(layer)
        }

        fill.alpha = 255
        fill.shader = fadeShader
        canvas.drawRect(0f, horizonY, width.toFloat(), horizonY + height * 0.14f, fill)
        fill.shader = null
    }

    private fun desaturatedCloud(look: Ambience): Int {
        val brightness = Ambience.luminance(look.skyBottom) / 255f
        val level = (90 + brightness * 165).toInt().coerceIn(0, 255)
        return Color.rgb(level, level, level)
    }

    private fun drawPrecipitation(canvas: Canvas, look: Ambience, time: Float) {
        if (look.fog > 0f) {
            fill.color = desaturatedCloud(look)
            fill.alpha = (look.fog * 255).toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        }
        if (look.rain > 0f) {
            stroke.color = RAIN_COLOR
            stroke.alpha = 150
            stroke.strokeWidth = 2.5f * unit
            val length = 46f * unit
            for (i in 0 until (look.rain * RAIN_COUNT).toInt()) {
                val speed = 1.4f + fract(i * 0.419f)
                val x = fract(i * 0.6180339f + time * speed * 0.035f) * width
                val y = fract(i * 0.7548776f + time * speed * 0.9f) * height
                canvas.drawLine(x, y, x - length * 0.16f, y + length, stroke)
            }
        }
        if (look.snow > 0f) {
            fill.color = Color.WHITE
            fill.alpha = 225
            for (i in 0 until (look.snow * SNOW_COUNT).toInt()) {
                val speed = 0.5f + fract(i * 0.419f)
                val sway = sin(time * speed + i) * 18f * unit
                val x = fract(i * 0.6180339f) * width + sway
                val y = fract(i * 0.7548776f + time * speed * 0.055f) * height
                canvas.drawCircle(x, y, (2.5f + fract(i * 0.31f) * 3.5f) * unit, fill)
            }
        }
        if (look.lightning) {
            // 약 9초에 한 번, 위치 대신 시간으로 정하는 짧은 번개 섬광
            val phase = time % 9f
            if (phase < 0.09f || phase in 0.18f..0.24f) {
                fill.color = Color.WHITE
                fill.alpha = 120
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
            }
        }
        fill.alpha = 255
    }

    private fun fract(value: Float): Float = value - floor(value)

    /** 동서남북으로 놓인 사각형을 돌려서 그린다. 카메라 뒤로 넘어가는 부분은 잘라낸다. */
    private fun drawGroundQuad(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        quadRight[0] = toViewX(x0, y0); quadForward[0] = toViewY(x0, y0)
        quadRight[1] = toViewX(x1, y0); quadForward[1] = toViewY(x1, y0)
        quadRight[2] = toViewX(x1, y1); quadForward[2] = toViewY(x1, y1)
        quadRight[3] = toViewX(x0, y1); quadForward[3] = toViewY(x0, y1)
        val near = nearY
        path.rewind()
        pathStarted = false
        for (i in 0 until 4) {
            val next = (i + 1) % 4
            val ax = quadRight[i]
            val ay = quadForward[i]
            val by = quadForward[next]
            if (ay >= near) addGroundVertex(ax, ay)
            if ((ay >= near) != (by >= near)) {
                addGroundVertex(ax + (quadRight[next] - ax) * (near - ay) / (by - ay), near)
            }
        }
        if (!pathStarted) return
        path.close()
        canvas.drawPath(path, fill)
    }

    private fun addGroundVertex(x: Float, y: Float) {
        if (pathStarted) path.lineTo(screenX(x, y), screenY(y)) else path.moveTo(screenX(x, y), screenY(y))
        pathStarted = true
    }

    private fun drawRangeRing(canvas: Canvas, look: Ambience, time: Float) {
        if (reserveRadius <= 0) return
        path.rewind()
        for (i in 0..RING_SEGMENTS) {
            val angle = i * 2 * Math.PI / RING_SEGMENTS
            val x = (cos(angle) * reserveRadius).toFloat() - panRight
            val y = max((sin(angle) * reserveRadius).toFloat() - panForward, nearY)
            if (i == 0) path.moveTo(screenX(x, y), screenY(y)) else path.lineTo(screenX(x, y), screenY(y))
        }
        path.close()
        fill.color = (look.range and 0x00FFFFFF) or (RANGE_FILL_ALPHA shl 24)
        canvas.drawPath(path, fill)
        stroke.color = look.range
        stroke.alpha = (205 + 50 * sin(time * 2.5f)).toInt()
        stroke.strokeWidth = 8f * unit
        canvas.drawPath(path, stroke)
    }

    /** 진행 방향이 화면 위쪽이 되도록 카메라를 부드럽게 돌린다. */
    private fun followCarHeading() {
        val now = SystemClock.uptimeMillis()
        val seconds = ((now - lastFrameAt).coerceIn(0L, 100L)) / 1000f
        lastFrameAt = now
        if (!followHeading) return
        var delta = heading - bearing
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        if (abs(delta) < 0.05f) return
        applyBearing(bearing + delta * min(1f, seconds * FOLLOW_RATE))
    }

    private fun drawRoute(canvas: Canvas, position: GeoPoint) {
        val path = route ?: return
        val points = path.points
        val split = path.positionAt(routeProgress)
        for (outline in booleanArrayOf(true, false)) {
            for (i in 0 until points.size - 1) {
                val start = path.cumulative[i]
                val end = path.cumulative[i + 1]
                when {
                    end <= routeProgress -> if (!outline) drawRouteSegment(canvas, position, points[i], points[i + 1], true, false)
                    start >= routeProgress -> drawRouteSegment(canvas, position, points[i], points[i + 1], false, outline)
                    else -> {
                        if (!outline) drawRouteSegment(canvas, position, points[i], split, true, false)
                        drawRouteSegment(canvas, position, split, points[i + 1], false, outline)
                    }
                }
            }
        }
    }

    /** 경로 한 구간을 바닥에 그린다. 멀수록 가늘어지고 카메라 뒤쪽은 잘라낸다. */
    private fun drawRouteSegment(
        canvas: Canvas,
        position: GeoPoint,
        from: GeoPoint,
        to: GeoPoint,
        traveled: Boolean,
        outline: Boolean,
    ) {
        val (fromEast, fromNorth) = from.metersFrom(position)
        val (toEast, toNorth) = to.metersFrom(position)
        var ax = toViewX(fromEast.toFloat(), fromNorth.toFloat()) - panRight
        var ay = toViewY(fromEast.toFloat(), fromNorth.toFloat()) - panForward
        var bx = toViewX(toEast.toFloat(), toNorth.toFloat()) - panRight
        var by = toViewY(toEast.toFloat(), toNorth.toFloat()) - panForward
        val near = nearY
        if (ay < near && by < near) return
        if (ay < near) {
            ax += (bx - ax) * (near - ay) / (by - ay)
            ay = near
        } else if (by < near) {
            bx += (ax - bx) * (near - by) / (ay - by)
            by = near
        }
        // 멀리서는 화면 기준 굵기, 가까이서는 차선 하나 정도의 실제 폭으로 그린다.
        val middle = (ay + by) / 2
        val width = max(ROUTE_WIDTH * unit * depthScale(middle) * sqrt(min(zoom, 3f)), ROUTE_METERS * pixelsPerMeter(middle))
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeWidth = if (outline) width * 1.5f else width
        stroke.color = when {
            outline -> ROUTE_OUTLINE_COLOR
            traveled -> ROUTE_TRAVELED_COLOR
            else -> ROUTE_COLOR
        }
        canvas.drawLine(screenX(ax, ay), screenY(ay), screenX(bx, by), screenY(by), stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun drawStations(canvas: Canvas, position: GeoPoint, time: Float) {
        markerHits.clear()
        val placed = stations
            .map { station ->
                val (east, north) = station.position.metersFrom(position)
                Triple(station, toViewX(east.toFloat(), north.toFloat()), toViewY(east.toFloat(), north.toFloat()))
            }
            .filter { (_, _, forward) -> forward - panForward > nearY }
            .sortedByDescending { (_, _, forward) -> forward }

        for ((station, right, forward) in placed) {
            val x = right - panRight
            val y = forward - panForward
            val inRange = hypot(right, forward) <= reserveRadius
            // 지평선에 붙은 먼 표식은 겹쳐서 읽을 수 없으므로 숨긴다. 선택한 충전소는 항상 보인다.
            if (station.id != selectedId && nearHorizon(y)) continue
            drawStation(canvas, station, screenX(x, y), screenY(y), depthScale(y), inRange, time)
        }
    }

    private fun drawStation(
        canvas: Canvas,
        station: Station,
        groundX: Float,
        groundY: Float,
        scale: Float,
        inRange: Boolean,
        time: Float,
    ) {
        val radius = MARKER_RADIUS * unit * scale
        val hasFree = station.freeCount > 0
        val mine = station.id == reservedStationId
        val base = when {
            mine -> MINE_COLOR
            !inRange -> OUT_OF_RANGE_COLOR
            hasFree -> FREE_COLOR
            else -> BUSY_COLOR
        }
        val bob = if (inRange) sin(time * 3f + station.id.hashCode()) * radius * 0.12f else 0f
        val markerY = groundY - radius * 2.6f + bob

        // 바닥 그림자, 기둥
        fill.color = Color.argb(90, 0, 0, 0)
        oval.set(groundX - radius, groundY - radius * 0.32f, groundX + radius, groundY + radius * 0.32f)
        canvas.drawOval(oval, fill)
        stroke.color = base
        stroke.strokeWidth = radius * 0.2f
        canvas.drawLine(groundX, groundY, groundX, markerY, stroke)

        // 예약 가능 거리 안의 충전소는 포켓스톱처럼 빛나며 회전한다.
        if (inRange) {
            stroke.color = base
            stroke.alpha = 200
            stroke.strokeWidth = radius * 0.1f
            drawHexagon(canvas, groundX, markerY, radius * (1.3f + 0.08f * sin(time * 3f)), time * 0.8f, stroke)
        }
        fill.color = base
        drawHexagon(canvas, groundX, markerY, radius, 0f, fill)
        if (station.id == selectedId) {
            stroke.color = Color.WHITE
            stroke.alpha = 255
            stroke.strokeWidth = radius * 0.13f
            drawHexagon(canvas, groundX, markerY, radius * 1.1f, 0f, stroke)
        }

        // 충전 중 / 전체 대수
        text.color = LABEL_DARK
        text.textSize = radius * 0.72f
        canvas.drawText("${station.chargingCount}/${station.total}", groundX, markerY + radius * 0.26f, text)

        // 이름과 대기 시간
        val labelY = markerY - radius * 1.5f
        text.textSize = max(22f * unit, 30f * unit * scale)
        text.color = Color.WHITE
        // 밝은 낮이나 눈 쌓인 바닥에서도 읽히도록 글자 뒤에 어두운 판을 깐다.
        val status = if (hasFree) "빈자리 ${station.freeCount}" else "${station.nextFreeMinutes}분 대기"
        val labelHalf = max(text.measureText(station.name), text.measureText(status)) / 2 + text.textSize * 0.55f
        fill.color = LABEL_PLATE_COLOR
        canvas.drawRoundRect(
            groundX - labelHalf, labelY - text.textSize * 2.25f,
            groundX + labelHalf, labelY + text.textSize * 0.45f,
            text.textSize * 0.5f, text.textSize * 0.5f, fill,
        )
        canvas.drawText(station.name, groundX, labelY - text.textSize * 1.15f, text)
        text.color = if (hasFree) FREE_COLOR else BUSY_COLOR
        canvas.drawText(status, groundX, labelY, text)

        markerHits[station.id] = Triple(groundX, markerY, max(radius * 1.6f, MIN_HIT_RADIUS * unit))
    }

    private fun drawHexagon(canvas: Canvas, cx: Float, cy: Float, r: Float, rotation: Float, paint: Paint) {
        path.rewind()
        for (i in 0 until 6) {
            val angle = rotation + i * Math.PI.toFloat() / 3f
            val x = cx + r * sin(angle)
            val y = cy - r * cos(angle)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    /** 지금 배율에서 도로 폭에 곱하는 과장 배율. 멀리 볼수록 도로를 넓게 그린다. */
    private val roadScale get() = 1f + (ROAD_WIDTH_SCALE - 1f) * exaggeration

    /**
     * 차 바로 아래 도로를 찾아 그 폭을 [carRoadMeters]에 담는다. 매 프레임 찾으면 무거우므로
     * 차가 [CAR_ROAD_RECHECK_METERS] 이상 움직였거나 지도가 바뀌었을 때만 다시 찾는다.
     */
    private fun updateCarRoad(position: GeoPoint) {
        val real = scene
        if (real == null) {
            carRoadMeters = DEFAULT_CAR_ROAD_METERS
            return
        }
        val last = carRoadCheckedAt
        if (real === carRoadScene && last != null && last.distanceTo(position) < CAR_ROAD_RECHECK_METERS) return
        carRoadScene = real
        carRoadCheckedAt = position
        val (offsetEast, offsetNorth) = position.metersFrom(real.origin)
        val px = offsetEast.toFloat()
        val py = offsetNorth.toFloat()
        var best = CAR_ROAD_SEARCH_METERS
        var width = DEFAULT_CAR_ROAD_METERS
        for (road in real.roads) {
            for (i in 0 until road.east.size - 1) {
                val distance = segmentDistance(px, py, road.east[i], road.north[i], road.east[i + 1], road.north[i + 1])
                // 도로 가장자리까지의 거리로 비교해야 넓은 도로 위에 있을 때 옆 골목을 잡지 않는다.
                val edge = distance - road.widthMeters / 2
                if (edge < best) {
                    best = edge
                    width = road.widthMeters
                }
            }
        }
        // 골목, 주차장 길처럼 좁은 길에서는 차가 주변 도로보다 작아 보이므로 일반 2차로 폭 아래로는 줄이지 않는다.
        carRoadMeters = max(width, DEFAULT_CAR_ROAD_METERS)
    }

    private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /**
     * 내 차량의 화면상 1미터당 픽셀 수.
     * 차가 달리는 도로([carRoadMeters]) 폭보다 [CAR_OVER_ROAD]배 넓게 보이도록 맞춘다.
     * 도로와 같은 방식으로 계산하므로 확대/축소해도 도로 대비 크기가 유지된다.
     */
    private val carPixelsPerMeter: Float
        get() {
            val roadPixels = carRoadMeters * roadScale * pixelsPerMeter(max(-panForward, nearY / 2))
            return roadPixels * CAR_OVER_ROAD / CAR_WIDTH_METERS
        }

    /** 내 차량의 렌더링 배율. 예전 고정 크기(1f) 대비 비율이다. */
    val carRenderScale: Float get() = carPixelsPerMeter / (CAR_PIXELS_PER_METER * unit)

    private fun drawCar(canvas: Canvas, look: Ambience, time: Float) {
        // 지도를 멀리 옮기면 차가 카메라 뒤로 넘어갈 수 있다.
        if (-panForward <= nearY) return
        val cx = screenX(-panRight, -panForward)
        val cy = screenY(-panForward)
        // 차 크기는 주변 도로 폭보다 조금 크게, 확대/축소에 맞춰 함께 바뀐다.
        val scale = carRenderScale
        val pulse = (time * 0.7f) % 1f
        val radius = (120f + pulse * 100f) * unit * scale
        stroke.color = look.range
        stroke.alpha = ((1 - pulse) * 220).toInt()
        stroke.strokeWidth = 5f * unit
        oval.set(cx - radius, cy - radius * 0.55f, cx + radius, cy + radius * 0.55f)
        canvas.drawOval(oval, stroke)

        if (look.headlights > 0.02f) drawHeadlightBeam(canvas, cx, cy, look.headlights)
        val k = carPixelsPerMeter
        carModel.draw(canvas, cx, cy, k, k, screenHeading)
    }

    /** 화면에서 본 차의 방향. 카메라가 돈 만큼 반대로 돌아 보인다. */
    private val screenHeading get() = ((heading - bearing) % 360f + 360f) % 360f

    /** 차 앞쪽 바닥에 비치는 전조등 불빛. 바닥은 눌려 보이므로 세로만 줄인다. */
    private fun drawHeadlightBeam(canvas: Canvas, cx: Float, cy: Float, strength: Float) {
        val k = carPixelsPerMeter
        val radians = Math.toRadians(screenHeading.toDouble())
        val sinH = sin(radians).toFloat()
        val cosH = cos(radians).toFloat()
        fun beamX(side: Float, ahead: Float) = cx + (side * cosH + ahead * sinH) * k
        fun beamY(side: Float, ahead: Float) = cy - (-side * sinH + ahead * cosH) * GROUND_SQUASH * k
        // 겹친 삼각형으로 멀어질수록 빛이 약해지는 모습을 낸다.
        fill.color = HEADLIGHT_BEAM_COLOR
        fill.alpha = (strength * 34).toInt()
        for (reach in BEAM_REACHES) {
            val spread = 0.7f + (reach - 2.2f) * 0.3f
            path.rewind()
            path.moveTo(beamX(-0.7f, 2.2f), beamY(-0.7f, 2.2f))
            path.lineTo(beamX(0.7f, 2.2f), beamY(0.7f, 2.2f))
            path.lineTo(beamX(spread, reach), beamY(spread, reach))
            path.lineTo(beamX(-spread, reach), beamY(-spread, reach))
            path.close()
            canvas.drawPath(path, fill)
        }
        fill.alpha = 255
    }

    private fun screenX(x: Float, y: Float) = centerX + focal * x * zoom / (y * zoom + CAMERA_BACK)
    private fun screenY(y: Float) = horizonY + focal * CAMERA_HEIGHT / (y * zoom + CAMERA_BACK)

    /** 바닥에서 [height]미터 위에 있는 점의 화면 y. */
    private fun screenYAt(y: Float, height: Float) = horizonY + focal * (CAMERA_HEIGHT - height * zoom) / (y * zoom + CAMERA_BACK)
    private fun depthScale(y: Float) = (CAMERA_BACK / (y * zoom + CAMERA_BACK)).coerceIn(0.55f, 1.3f)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                gestureHadMultipleFingers = false
                dragging = false
                panAnimator?.cancel()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                gestureHadMultipleFingers = true
                if (event.pointerCount == 2) {
                    lastTwistAngle = fingerAngle(event)
                    twistTotal = 0f
                    twisting = false
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    twistBy(event)
                } else if (!gestureHadMultipleFingers) {
                    if (abs(event.x - downX) >= touchSlop || abs(event.y - downY) >= touchSlop) dragging = true
                    if (dragging) {
                        if (rotateMode) {
                            // 손가락을 따라 앞쪽 풍경이 움직이도록 반대 방향으로 돈다.
                            rotateBy(-(event.x - lastTouchX) / width * DEGREES_PER_MAP_WIDTH)
                        } else {
                            panBy(event.x - lastTouchX, event.y - lastTouchY)
                        }
                        lastTouchX = event.x
                        lastTouchY = event.y
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                val isTap = !gestureHadMultipleFingers && !dragging
                if (isTap) {
                    performClick()
                    stationAt(event.x, event.y)?.let { onStationTapped?.invoke(it) }
                }
            }
        }
        return true
    }

    private fun fingerAngle(event: MotionEvent): Float = Math.toDegrees(
        atan2((event.getY(1) - event.getY(0)).toDouble(), (event.getX(1) - event.getX(0)).toDouble()),
    ).toFloat()

    /** 두 손가락을 비틀면 돌린다. 핀치 중 생기는 작은 흔들림은 무시한다. */
    private fun twistBy(event: MotionEvent) {
        val angle = fingerAngle(event)
        var delta = angle - lastTwistAngle
        if (delta > 180f) delta -= 360f else if (delta < -180f) delta += 360f
        lastTwistAngle = angle
        if (!twisting) {
            twistTotal += delta
            if (abs(twistTotal) < TWIST_THRESHOLD_DEGREES) return
            twisting = true
        }
        rotateBy(-delta)
    }

    /** 마우스 휠과 트랙패드 스크롤로도 내 차 기준으로 확대, 축소한다. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_SCROLL) return super.onGenericMotionEvent(event)
        val amount = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (amount == 0f) return false
        if (amount > 0f) zoomIn() else zoomOut()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun stationAt(x: Float, y: Float): String? = markerHits.entries
        .filter { (_, hit) -> hypot(x - hit.first, y - hit.second) <= hit.third }
        .minByOrNull { (_, hit) -> hypot(x - hit.first, y - hit.second) }
        ?.key

    companion object {
        private const val CAMERA_BACK = 3_000f
        private const val CAMERA_HEIGHT = 2_000f
        private const val NEAR_Y = -2_600f
        private const val VIEW_BACK = -2_600f
        private const val VIEW_FRONT = 16_000f
        private const val VIEW_SIDE = 12_000f
        private const val BLOCK = 500.0
        private const val ROAD_MARGIN = 26f
        private const val MINOR_ROAD = 13f
        private const val MAJOR_ROAD = 26f
        private const val RING_SEGMENTS = 96
        private const val MARKER_RADIUS = 62f
        private const val MIN_HIT_RADIUS = 76f
        private const val CAR_PIXELS_PER_METER = 56f
        /** 주변 도로를 모를 때(실제 지도 받기 전, 도로 밖) 차 크기 기준 도로 폭(미터). 일반 2차로 폭. */
        private const val DEFAULT_CAR_ROAD_METERS = 8f
        /** 차 폭을 차가 달리는 도로 폭보다 이만큼만 크게 그린다. */
        private const val CAR_OVER_ROAD = 1.15f
        /** 이 거리(도로 가장자리 기준) 안에 도로가 없으면 [DEFAULT_CAR_ROAD_METERS]를 쓴다. */
        private const val CAR_ROAD_SEARCH_METERS = 25f
        private const val CAR_ROAD_RECHECK_METERS = 8.0
        /** 차 모델의 실제 폭(미터). CarModel의 최대 반폭 0.955 × 2. */
        private const val CAR_WIDTH_METERS = 1.91f
        private const val MAX_PAN = 5_000f
        private const val ORIGIN_RESET_METERS = 10_000.0
        private const val DEGREES_PER_MAP_WIDTH = 360f
        private const val TWIST_THRESHOLD_DEGREES = 8f
        private const val BEARING_RESET_MS = 320L
        private const val SKY_SCREENS_PER_TURN = 3f
        private const val FOLLOW_RATE = 4f
        private const val ROAD_WIDTH_SCALE = 3f
        private const val TRUE_SCALE_ZOOM = 40f
        private const val HORIZON_MARGIN = 110f
        private const val FULL_EXAGGERATION_ZOOM = 2f
        private const val ROUTE_METERS = 4f
        private const val BUILDING_FAR_METERS = 2_500f
        /** 건물 벽·지붕 불투명도(0~255). 뒤쪽 도로와 표식이 비쳐 보이도록 반투명하게 그린다. */
        private const val BUILDING_ALPHA = 150
        private const val BUILDING_EDGE_ALPHA = 200
        /** 차에서 이 거리 안의 건물에만 이름을 붙인다. */
        private const val BUILDING_LABEL_METERS = 450f
        private const val MAX_BUILDING_LABELS = 14
        private val BUILDING_LABEL_COLOR = Color.rgb(40, 44, 52)
        private const val BUILDING_HEIGHT_SCALE = 4f
        private const val FACILITY_RADIUS = 20f
        private const val FACILITY_LABEL_METERS = 350.0
        private const val LIGHT_X = -0.6f
        private const val LIGHT_Y = -0.8f
        private val WATER_COLOR = Color.rgb(64, 140, 210)
        private val PITCH_COLOR = Color.rgb(200, 120, 90)
        private val WALL_COLOR = Color.rgb(196, 202, 212)
        private val ROOF_COLOR = Color.rgb(232, 235, 240)
        private val FACILITY_COLORS = mapOf(
            Facility.Kind.CONVENIENCE to Color.rgb(255, 128, 64),
            Facility.Kind.CAFE to Color.rgb(160, 104, 70),
            Facility.Kind.FOOD to Color.rgb(232, 84, 112),
        )
        private const val ROUTE_WIDTH = 22f
        private val ROUTE_COLOR = Color.rgb(66, 153, 255)
        private val ROUTE_OUTLINE_COLOR = Color.rgb(18, 56, 112)
        private val ROUTE_TRAVELED_COLOR = Color.argb(150, 150, 160, 172)
        private const val RECENTER_ANIMATION_MS = 280L
        /** 기본 상태(0단계)에서 줌인은 최대 2번까지만 가능하다. */
        const val MAX_ZOOM_STEPS = 2
        /** 기본 상태(0단계)에서 줌아웃은 최대 2번까지만 가능하다. */
        const val MAX_ZOOM_OUT_STEPS = 2
        const val DEFAULT_ZOOM = 8f
        const val ZOOM_STEP = 1.6f
        private const val PINCH_STEP_THRESHOLD = 1.05f
        private const val ZOOM_ANIMATION_MS = 180L
        val MIN_ZOOM = zoomForStep(-MAX_ZOOM_OUT_STEPS)
        val MAX_ZOOM = zoomForStep(MAX_ZOOM_STEPS)

        fun zoomForStep(step: Int): Float =
            DEFAULT_ZOOM * ZOOM_STEP.pow(step.coerceIn(-MAX_ZOOM_OUT_STEPS, MAX_ZOOM_STEPS))

        private fun stepForZoom(value: Float): Int =
            (ln((value / DEFAULT_ZOOM).coerceAtLeast(0.001f)) / ln(ZOOM_STEP))
                .roundToInt()
                .coerceIn(-MAX_ZOOM_OUT_STEPS, MAX_ZOOM_STEPS)

        const val RANGE_FILL_ALPHA = 44
        const val STAR_COUNT = 70
        const val CLOUD_COUNT = 9
        const val RAIN_COUNT = 240
        const val SNOW_COUNT = 170
        const val GROUND_SQUASH = 0.555f
        val BEAM_REACHES = floatArrayOf(4f, 5.5f, 7f)
        val LABEL_PLATE_COLOR = Color.argb(170, 12, 18, 26)
        val SUN_COLOR = Color.rgb(255, 224, 130)
        val MOON_COLOR = Color.rgb(236, 240, 255)
        val RAIN_COLOR = Color.rgb(205, 225, 245)
        val HEADLIGHT_BEAM_COLOR = Color.rgb(255, 244, 190)
        val FREE_COLOR = Color.rgb(61, 220, 151)
        val BUSY_COLOR = Color.rgb(255, 179, 71)
        val MINE_COLOR = Color.rgb(120, 228, 255)
        val OUT_OF_RANGE_COLOR = Color.rgb(142, 158, 168)
        val LABEL_DARK = Color.rgb(16, 24, 32)
    }
}
