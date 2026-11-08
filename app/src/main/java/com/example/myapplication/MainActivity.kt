package com.example.myapplication

import android.Manifest
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.res.ColorStateList
import android.widget.Button
import android.widget.ImageView
import java.text.SimpleDateFormat
import java.util.Locale
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.myapplication.data.SimulatedChargingNetwork
import com.example.myapplication.data.EvChargerClient
import com.example.myapplication.data.OsmClient
import com.example.myapplication.data.TmapChargerClient
import com.example.myapplication.data.TmapRouteClient
import com.example.myapplication.nav.NavRoute
import com.example.myapplication.data.WeatherClient
import com.example.myapplication.data.SimulatedChargingNetwork.Companion.ARRIVAL_RADIUS_METERS
import com.example.myapplication.data.SimulatedChargingNetwork.Companion.RESERVE_RADIUS_METERS
import com.example.myapplication.model.Ambience
import com.example.myapplication.model.Charger
import com.example.myapplication.model.GeoPoint
import com.example.myapplication.model.ReserveResult
import com.example.myapplication.model.Reservation
import com.example.myapplication.model.StartChargingResult
import com.example.myapplication.model.Station
import com.example.myapplication.model.SunPosition
import com.example.myapplication.model.Weather
import com.example.myapplication.model.WeatherKind
import com.example.myapplication.ui.ChargerBar
import com.example.myapplication.ui.GoMapView
import com.example.myapplication.vehicle.VehicleGateway

class MainActivity : AppCompatActivity() {

    private enum class PositionSource { DEFAULT, VEHICLE, DEMO }

    /** 충전소 데이터를 어디서 받았는지. 실시간이면 현황을 실제 속도로 돌린다. */
    private enum class StationSource { SIMULATED, TMAP, LIVE }

    private lateinit var map: GoMapView
    private lateinit var batteryChip: TextView
    private lateinit var sourceChip: TextView
    private lateinit var weatherChip: TextView
    private lateinit var demoButton: Button
    private lateinit var reservationCard: View
    private lateinit var reservationTitle: TextView
    private lateinit var reservationDetail: TextView
    private lateinit var startChargingButton: Button
    private lateinit var listPane: View
    private lateinit var stationRows: LinearLayout
    private lateinit var detailPane: View
    private lateinit var detailName: TextView
    private lateinit var detailDistance: TextView
    private lateinit var detailCharging: TextView
    private lateinit var detailBar: ChargerBar
    private lateinit var listSummary: TextView
    private lateinit var detailNextFree: TextView
    private lateinit var chargerRows: LinearLayout
    private lateinit var reserveButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private var gateway: VehicleGateway? = null

    private var car = DEFAULT_POSITION
    private var heading = PARKED_HEADING
    private var source = PositionSource.DEFAULT
    private var headingAnchor: GeoPoint? = null
    private var network = SimulatedChargingNetwork(DEFAULT_POSITION)
    private var selectedStationId: String? = null
    private var listedStationIds: List<String> = emptyList()
    private var demoDriving = false
    private var lastTickAt = 0L
    private var simSecondCarry = 0L
    private var lastPanelAt = 0L

    private val weatherClient = WeatherClient()
    private val chargerClient = TmapChargerClient(BuildConfig.TMAP_APP_KEY)
    private val evClient = EvChargerClient(BuildConfig.DATA_GO_KR_KEY)
    private val routeClient = TmapRouteClient(BuildConfig.TMAP_APP_KEY)
    private var stationSource = StationSource.SIMULATED
    private var liveRegionCode: String? = null
    private var liveStatusAt = 0L
    private var liveStatusLoading = false

    private lateinit var osmClient: OsmClient
    private var sceneRequestedFor: GeoPoint? = null
    private var sceneLoading = false
    private var sceneRetryAt = 0L

    private var navRoute: NavRoute? = null
    private var navProgress = 0.0
    private var routeLoading = false
    private lateinit var chips: View
    private lateinit var navBanner: View
    private lateinit var navArrow: ImageView
    private lateinit var navDistance: TextView
    private lateinit var navInstruction: TextView
    private lateinit var navSummary: TextView
    private var stationsRequestedFor: GeoPoint? = null
    private var stationsLoading = false
    private lateinit var simChip: TextView
    private var weather: Weather? = null
    private var weatherRequestedAt = 0L
    private var weatherRequestedFor: GeoPoint? = null
    private var weatherLoading = false

    // 디버그 빌드에서 시연용으로 시각과 날씨를 고정한다. 값이 없으면 실제 값을 쓴다.
    private var forcedSunElevation: Float? = null
    private var forcedWeather: Weather? = null

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - lastTickAt
            lastTickAt = now
            if (demoDriving) driveDemo(elapsed)
            advanceSimulation(elapsed)
            if (now - lastPanelAt >= PANEL_INTERVAL_MS) render()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        osmClient = OsmClient(cacheDir)
        bindViews()
        readDebugOverrides()
        startVehicleGateway()
        requestPermissions(PERMISSIONS, REQUEST_PERMISSIONS)
        render()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (gateway == null) startVehicleGateway()
            else gateway?.onPermissionsUpdated()
        }
    }

    override fun onStart() {
        super.onStart()
        lastTickAt = SystemClock.elapsedRealtime()
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        super.onStop()
    }

    override fun onDestroy() {
        gateway?.stop()
        gateway = null
        weatherClient.close()
        chargerClient.close()
        evClient.close()
        routeClient.close()
        if (::osmClient.isInitialized) osmClient.close()
        super.onDestroy()
    }

    private fun bindViews() {
        map = findViewById(R.id.map)
        batteryChip = findViewById(R.id.batteryChip)
        sourceChip = findViewById(R.id.sourceChip)
        weatherChip = findViewById(R.id.weatherChip)
        demoButton = findViewById(R.id.demoButton)
        reservationCard = findViewById(R.id.reservationCard)
        reservationTitle = findViewById(R.id.reservationTitle)
        reservationDetail = findViewById(R.id.reservationDetail)
        startChargingButton = findViewById(R.id.startChargingButton)
        listPane = findViewById(R.id.listPane)
        stationRows = findViewById(R.id.stationRows)
        detailPane = findViewById(R.id.detailPane)
        detailName = findViewById(R.id.detailName)
        detailDistance = findViewById(R.id.detailDistance)
        detailCharging = findViewById(R.id.detailCharging)
        detailBar = findViewById(R.id.detailBar)
        listSummary = findViewById(R.id.listSummary)
        detailNextFree = findViewById(R.id.detailNextFree)
        chargerRows = findViewById(R.id.chargerRows)
        reserveButton = findViewById(R.id.reserveButton)

        batteryChip.setText(R.string.battery_unknown)
        simChip = findViewById(R.id.simChip)
        chips = findViewById(R.id.chips)
        navBanner = findViewById(R.id.navBanner)
        navArrow = findViewById(R.id.navArrow)
        navDistance = findViewById(R.id.navDistance)
        navInstruction = findViewById(R.id.navInstruction)
        navSummary = findViewById(R.id.navSummary)
        simChip.text = getString(R.string.sim_badge, SIM_SPEED)

        map.onStationTapped = ::selectStation
        val zoomInButton = findViewById<View>(R.id.zoomInButton)
        val zoomOutButton = findViewById<View>(R.id.zoomOutButton)
        zoomInButton.setOnClickListener { map.zoomIn() }
        zoomOutButton.setOnClickListener { map.zoomOut() }
        val rotateButton = findViewById<View>(R.id.rotateButton)
        rotateButton.setOnClickListener {
            map.rotateMode = !map.rotateMode
            rotateButton.isActivated = map.rotateMode
            if (map.rotateMode) toast(R.string.rotate_on_toast)
        }
        val compassButton = findViewById<View>(R.id.compassButton)
        compassButton.setOnClickListener { map.resetBearing() }
        map.onBearingChanged = {
            compassButton.rotation = -map.bearing
            compassButton.visibility = if (map.bearing == 0f) View.GONE else View.VISIBLE
        }
        val recenterButton = findViewById<View>(R.id.recenterButton)
        recenterButton.setOnClickListener { map.recenter() }
        map.onPanChanged = {
            recenterButton.visibility = if (map.isPanned) View.VISIBLE else View.GONE
        }
        val viewButton = findViewById<Button>(R.id.viewButton)
        viewButton.setOnClickListener {
            map.zoomTo(if (map.zoomGoal >= CLOSE_VIEW_THRESHOLD) OVERVIEW_ZOOM else CLOSE_ZOOM)
        }
        map.onZoomLimitsChanged = {
            zoomInButton.isEnabled = map.canZoomIn
            zoomOutButton.isEnabled = map.canZoomOut
            viewButton.setText(if (map.zoomGoal >= CLOSE_VIEW_THRESHOLD) R.string.view_overview else R.string.view_close)
        }
        // 기본 배율(0단계)에서 시작해 핀치줌/핀치아웃을 각각 최대 5번씩 할 수 있게 한다.
        map.setZoomNow(GoMapView.DEFAULT_ZOOM)
        demoButton.setOnClickListener { toggleDemo() }
        findViewById<View>(R.id.backButton).setOnClickListener { selectStation(null) }
        reserveButton.setOnClickListener { reserveSelected() }
        startChargingButton.setOnClickListener { startCharging() }
        findViewById<View>(R.id.cancelButton).setOnClickListener {
            network.cancelReservation()
            toast(R.string.canceled_toast)
            render()
        }
    }

    private fun startVehicleGateway() {
        gateway = VehicleGateway(
            context = this,
            onLocation = ::onVehicleLocation,
            onBattery = { percent ->
                if (percent == null) batteryChip.setText(R.string.battery_unknown)
                else batteryChip.text = getString(R.string.battery_value, percent)
            },
        ).also { it.start() }
    }

    private fun onVehicleLocation(position: GeoPoint) {
        if (demoDriving || routeLoading) return
        val firstFix = source == PositionSource.DEFAULT
        val movedMeters = position.distanceTo(car)
        // 첫 GPS 수신 시 또는 시뮬레이션 상태에서 위치가 크게 바뀌면 주변 충전소를 현 위치 기준으로 다시 잡는다.
        if ((firstFix || (stationSource == StationSource.SIMULATED && movedMeters > STATIONS_REFRESH_METERS)) &&
            network.reservation() == null
        ) {
            network = SimulatedChargingNetwork(position)
        }
        // 실제 GPS 이동 시 차량 헤딩과 카메라 방향을 이동 방향으로 회전시킨다.
        // 0.1초 간격 갱신은 한 번에 움직이는 거리가 작아 방향이 흔들리므로, 일정 거리 이상 움직인 기준점과 비교한다.
        val anchor = headingAnchor
        if (firstFix || anchor == null) {
            headingAnchor = position
        } else {
            val fromAnchor = anchor.distanceTo(position)
            if (fromAnchor > MAX_DRIVING_STEP_METERS) {
                headingAnchor = position
            } else if (fromAnchor >= MIN_HEADING_MOVE_METERS) {
                heading = anchor.bearingTo(position)
                map.followHeading = true
                headingAnchor = position
            }
        }
        car = position
        source = PositionSource.VEHICLE
        map.setCar(car, heading)
        render()
    }

    /**
     * 선택한 충전소로, 선택이 없으면 주행 거리가 있는 가까운 충전소로 가상 주행한다.
     * TMAP 경로가 있으면 실제 도로를 따라가고, 없으면 직선으로 간다.
     */
    private fun toggleDemo() {
        if (demoDriving || routeLoading) {
            stopDemo()
            return
        }
        val stations = network.stations()
        val target = stations.firstOrNull { it.id == selectedStationId }
            ?: stations.filter { car.distanceTo(it.position) > ARRIVAL_RADIUS_METERS }
                .minByOrNull { car.distanceTo(it.position) }
            ?: stations.minByOrNull { car.distanceTo(it.position) }
            ?: return
        source = PositionSource.DEMO
        // 움직이는 차를 볼 수 있도록 카메라를 차로 되돌린다.
        map.recenter()
        routeLoading = true
        val start = car
        routeClient.fetch(start, target.position) { route ->
            if (!routeLoading) return@fetch
            routeLoading = false
            val next = route ?: NavRoute.straight(start, target.position)
            navRoute = next
            navProgress = 0.0
            demoDriving = true
            map.setRoute(next)
            map.followHeading = true
            map.zoomTo(CLOSE_ZOOM)
            render()
        }
        render()
    }

    private fun stopDemo(arrived: Boolean = false) {
        demoDriving = false
        routeLoading = false
        navRoute = null
        map.setRoute(null)
        map.followHeading = false
        if (source == PositionSource.DEMO) source = PositionSource.VEHICLE
        headingAnchor = null
        if (arrived) toast(R.string.nav_arrived_toast)
        render()
    }

    private fun driveDemo(elapsedMs: Long) {
        val route = navRoute ?: return
        navProgress = minOf(route.totalMeters, navProgress + NAV_SPEED_METERS_PER_SECOND * elapsedMs / 1000.0)
        car = route.positionAt(navProgress)
        heading = turnToward(heading, route.headingAt(navProgress), elapsedMs)
        map.setCar(car, heading)
        map.setRouteProgress(navProgress)
        renderNav(route)
        if (navProgress >= route.totalMeters) stopDemo(arrived = true)
    }

    /** 굽은 길에서 차가 확 돌지 않도록 초당 [NAV_TURN_DEGREES_PER_SECOND]도까지만 돈다. */
    private fun turnToward(current: Float, target: Float, elapsedMs: Long): Float {
        var delta = target - current
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        val limit = NAV_TURN_DEGREES_PER_SECOND * elapsedMs / 1000f
        return ((current + delta.coerceIn(-limit, limit)) % 360f + 360f) % 360f
    }

    private fun renderNav(route: NavRoute) {
        val next = route.nextGuidance(navProgress)
        if (next != null) {
            setIfChanged(navDistance, formatDistance(next.distance - navProgress))
            val instruction = if (next.road.isEmpty()) next.instruction else "${next.instruction} · ${next.road}"
            setIfChanged(navInstruction, instruction)
            navArrow.rotation = next.turnDegrees.coerceIn(-150f, 150f)
            navArrow.setImageResource(if (next.arrival) R.drawable.ic_flag else R.drawable.ic_turn)
        }
        val remainingMeters = route.totalMeters - navProgress
        val remainingSeconds = if (route.totalMeters > 0) route.totalSeconds * remainingMeters / route.totalMeters else 0.0
        val arrival = SimpleDateFormat("HH:mm", Locale.KOREA).format(System.currentTimeMillis() + (remainingSeconds * 1000).toLong())
        setIfChanged(
            navSummary,
            getString(R.string.nav_summary, arrival, formatDistance(remainingMeters), maxOf(1, (remainingSeconds / 60).toInt())),
        )
    }

    private fun setIfChanged(view: TextView, text: String) {
        if (view.text.toString() != text) view.text = text
    }

    private fun advanceSimulation(elapsedMs: Long) {
        // 실시간 현황은 실제 시간대로, 시뮬레이션은 빠르게 돌린다.
        simSecondCarry += elapsedMs * if (stationSource == StationSource.LIVE) 1 else SIM_SPEED
        val seconds = (simSecondCarry / 1000).toInt()
        if (seconds == 0) return
        simSecondCarry -= seconds * 1000L
        if (network.advance(seconds)) {
            toast(R.string.expired_toast)
            render()
        }
    }

    private fun selectStation(id: String?) {
        selectedStationId = id
        render()
    }

    private fun reserveSelected() {
        val id = selectedStationId ?: return
        when (network.reserve(id, car)) {
            is ReserveResult.Success -> toast(R.string.reserved_toast)
            ReserveResult.TooFar -> toast(R.string.too_far_toast)
            ReserveResult.AlreadyReserved -> toast(R.string.reserve_other)
            ReserveResult.UnknownStation -> Unit
        }
        render()
    }

    private fun startCharging() {
        if (network.startCharging(car) == StartChargingResult.Started) {
            toast(R.string.charging_started_toast)
        }
        render()
    }

    private fun readDebugOverrides() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (intent.hasExtra(EXTRA_SUN)) forcedSunElevation = intent.getFloatExtra(EXTRA_SUN, 0f)
        intent.getStringExtra(EXTRA_WEATHER)?.let { name ->
            forcedWeather = Weather.preset(WeatherKind.valueOf(name.uppercase()))
        }
    }

    /** 실제 위치를 받으면 그 주변의 실제 충전소를 TMAP에서 받아 시뮬레이터를 다시 만든다. */
    private fun refreshStations() {
        if (!chargerClient.isConfigured || stationsLoading || source == PositionSource.DEFAULT) return
        if (stationsRequestedFor?.let { it.distanceTo(car) < STATIONS_REFRESH_METERS } == true) return
        // 예약 중에는 충전소 목록을 바꾸지 않는다.
        if (network.reservation() != null) return
        stationsLoading = true
        stationsRequestedFor = car
        val center = car
        if (!evClient.isConfigured) {
            fetchTmapStations(center)
            return
        }
        // 공공데이터(환경부)를 먼저 쓰고, 실패하면 TMAP으로 넘어간다.
        evClient.fetchStations(center, { chargerClient.regionCode(center) }) { result ->
            if (result == null) {
                fetchTmapStations(center)
                return@fetchStations
            }
            stationsLoading = false
            if (network.reservation() != null) return@fetchStations
            network = SimulatedChargingNetwork(center, result.plans, arrivalChancePerMinute = 0.0)
            stationSource = StationSource.LIVE
            liveRegionCode = result.regionCode
            liveStatusAt = SystemClock.elapsedRealtime()
            simChip.setText(R.string.sim_badge_live)
            render()
        }
    }

    private fun fetchTmapStations(center: GeoPoint) {
        chargerClient.fetch(center) { plans ->
            stationsLoading = false
            if (plans == null || network.reservation() != null) return@fetch
            network = SimulatedChargingNetwork(center, plans)
            stationSource = StationSource.TMAP
            simChip.text = getString(R.string.sim_badge_tmap, SIM_SPEED)
            render()
        }
    }

    /** 실제 위치를 받으면 주변 실제 도로, 건물(OpenStreetMap)과 시설(TMAP)을 받는다. */
    private fun refreshScene(now: Long) {
        if (sceneLoading || source == PositionSource.DEFAULT || now < sceneRetryAt) return
        if (sceneRequestedFor?.let { it.distanceTo(car) < SCENE_REFRESH_METERS } == true) return
        sceneLoading = true
        sceneRequestedFor = car
        val center = car
        osmClient.fetch(center) { scene ->
            sceneLoading = false
            if (scene == null) {
                // 실패하면 잠시 뒤 다시 시도한다. 그동안은 장식용 지도를 그린다.
                sceneRequestedFor = null
                sceneRetryAt = SystemClock.elapsedRealtime() + SCENE_RETRY_MS
            } else {
                map.setScene(scene)
                if (center.distanceTo(car) >= SCENE_REFRESH_METERS) {
                    refreshScene(SystemClock.elapsedRealtime())
                }
            }
        }
        // 전기차 충전소만 표시한다. 편의점/카페/음식점 등 주변 시설은 조회하지 않는다.
    }

    /** 실시간 모드에서 1분마다 바뀐 충전기 상태를 받아 반영한다. */
    private fun refreshLiveStatus(now: Long) {
        val region = liveRegionCode ?: return
        if (stationSource != StationSource.LIVE || liveStatusLoading || now - liveStatusAt < LIVE_STATUS_INTERVAL_MS) return
        liveStatusLoading = true
        liveStatusAt = now
        evClient.fetchStatus(region) { updates ->
            liveStatusLoading = false
            if (updates != null && stationSource == StationSource.LIVE) {
                network.applyLive(updates)
                render()
            }
        }
    }

    private fun refreshWeather(now: Long) {
        if (forcedWeather != null || weatherLoading) return
        val movedFar = weatherRequestedFor?.let { it.distanceTo(car) > WEATHER_REFRESH_METERS } ?: true
        val interval = if (weather == null) WEATHER_RETRY_MS else WEATHER_REFRESH_MS
        if (!movedFar && now - weatherRequestedAt < interval) return
        // 기본 위치의 날씨를 잠깐 보여주지 않도록 실제 위치를 받은 뒤에 요청한다.
        if (source == PositionSource.DEFAULT) return
        weatherLoading = true
        weatherRequestedAt = now
        weatherRequestedFor = car
        weatherClient.fetch(car) { result ->
            weatherLoading = false
            // 실패하면 직전 날씨를 유지한다.
            if (result != null) weather = result
        }
    }

    private fun renderAmbience() {
        val computed = SunPosition.at(car, System.currentTimeMillis())
        val sun = forcedSunElevation?.let { computed.copy(elevation = it.toDouble()) } ?: computed
        val current = forcedWeather ?: weather
        map.setAmbience(Ambience.of(sun, current))

        val phase = when {
            sun.elevation > TWILIGHT_END_DEGREES -> getString(R.string.phase_day)
            sun.elevation < -TWILIGHT_END_DEGREES -> getString(R.string.phase_night)
            sun.hourAngle < 0 -> getString(R.string.phase_dawn)
            else -> getString(R.string.phase_dusk)
        }
        weatherChip.text = when {
            current == null -> getString(R.string.weather_unknown, phase)
            current.temperatureC == null -> getString(R.string.weather_forced, weatherName(current.kind), phase)
            else -> getString(R.string.weather_value, weatherName(current.kind), current.temperatureC, phase)
        }
    }

    private fun weatherName(kind: WeatherKind): String = getString(
        when (kind) {
            WeatherKind.CLEAR -> R.string.weather_clear
            WeatherKind.PARTLY_CLOUDY -> R.string.weather_partly
            WeatherKind.CLOUDY -> R.string.weather_cloudy
            WeatherKind.FOG -> R.string.weather_fog
            WeatherKind.RAIN -> R.string.weather_rain
            WeatherKind.SNOW -> R.string.weather_snow
            WeatherKind.THUNDER -> R.string.weather_thunder
        },
    )

    private fun render() {
        lastPanelAt = SystemClock.elapsedRealtime()
        refreshWeather(lastPanelAt)
        refreshStations()
        refreshLiveStatus(lastPanelAt)
        refreshScene(lastPanelAt)
        renderAmbience()
        val stations = network.stations()
        val reservation = network.reservation()

        map.setCar(car, heading)
        map.setStations(stations, selectedStationId, reservation?.stationId, RESERVE_RADIUS_METERS)
        sourceChip.setText(
            when (source) {
                PositionSource.DEFAULT -> R.string.source_default
                PositionSource.VEHICLE -> R.string.source_vehicle
                PositionSource.DEMO -> R.string.source_demo
            },
        )
        demoButton.setText(
            when {
                routeLoading -> R.string.route_searching
                demoDriving -> R.string.demo_stop
                else -> R.string.demo_start
            },
        )
        val navigating = demoDriving && navRoute != null
        navBanner.visibility = if (navigating) View.VISIBLE else View.GONE
        navSummary.visibility = if (navigating) View.VISIBLE else View.GONE
        chips.visibility = if (navigating) View.GONE else View.VISIBLE
        navRoute?.let { if (navigating) renderNav(it) }

        renderReservation(reservation, stations)
        val selected = stations.firstOrNull { it.id == selectedStationId }
        listPane.visibility = if (selected == null) View.VISIBLE else View.GONE
        detailPane.visibility = if (selected == null) View.GONE else View.VISIBLE
        if (selected == null) renderList(stations) else renderDetail(selected, reservation)
    }

    private fun renderReservation(reservation: Reservation?, stations: List<Station>) {
        reservationCard.visibility = if (reservation == null) View.GONE else View.VISIBLE
        if (reservation == null) return
        reservationTitle.text = getString(
            R.string.reservation_summary, reservation.stationName, reservation.chargerNumber,
        )
        reservationDetail.text = if (reservation.waitMinutes > 0) {
            getString(R.string.reservation_wait, reservation.waitMinutes, reservation.holdMinutes)
        } else {
            getString(R.string.reservation_ready, reservation.holdMinutes)
        }
        val station = stations.firstOrNull { it.id == reservation.stationId }
        val arrived = station != null && car.distanceTo(station.position) <= ARRIVAL_RADIUS_METERS
        startChargingButton.visibility = if (arrived) View.VISIBLE else View.GONE
        startChargingButton.isEnabled = reservation.waitMinutes == 0
    }

    private fun renderList(stations: List<Station>) {
        val sorted = stations.sortedBy { car.distanceTo(it.position) }
        listSummary.text = getString(R.string.list_summary, stations.sumOf { it.freeCount })
        // 1초마다 갱신되므로 행을 다시 만들면 누르는 도중 터치가 끊긴다. 있는 행을 재사용한다.
        syncRows(stationRows, sorted.size, R.layout.item_station)
        listedStationIds = sorted.map { it.id }
        sorted.forEachIndexed { index, station ->
            val row = stationRows.getChildAt(index)
            val distance = car.distanceTo(station.position)
            row.findViewById<TextView>(R.id.name).text = station.name
            row.findViewById<TextView>(R.id.distance).text = formatDistance(distance)
            row.findViewById<TextView>(R.id.charging).text =
                getString(R.string.charging_count, station.chargingCount, station.total)
            row.findViewById<View>(R.id.rangeTag).visibility =
                if (distance <= RESERVE_RADIUS_METERS) View.VISIBLE else View.GONE
            bindStatus(row.findViewById(R.id.status), station)
            row.findViewById<ChargerBar>(R.id.bar).setChargers(station.chargers)
            row.setBackgroundResource(
                if (distance <= RESERVE_RADIUS_METERS) R.drawable.bg_card_highlight else R.drawable.bg_card,
            )
            row.setOnClickListener { selectStation(listedStationIds[index]) }
        }
    }

    private fun renderDetail(station: Station, reservation: Reservation?) {
        val distance = car.distanceTo(station.position)
        detailName.text = station.name
        detailDistance.text = formatDistance(distance)
        detailCharging.text = getString(R.string.charging_count, station.chargingCount, station.total)
        bindStatus(detailNextFree, station)
        detailBar.setChargers(station.chargers)

        syncRows(chargerRows, station.chargers.size, R.layout.item_charger)
        station.chargers.forEachIndexed { index, charger ->
            val row = chargerRows.getChildAt(index)
            row.findViewById<TextView>(R.id.number).text = getString(R.string.charger_number, charger.number)
            bindChargerStatus(row.findViewById(R.id.status), charger)
        }
        bindReserveButton(station, reservation, distance)
    }

    /** 빈자리 또는 대기 시간을 색이 있는 배지로 보여준다. */
    private fun bindStatus(view: TextView, station: Station) {
        val hasFree = station.freeCount > 0
        view.text = if (hasFree) {
            getString(R.string.status_free, station.freeCount)
        } else {
            getString(R.string.status_wait, station.nextFreeMinutes)
        }
        view.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (hasFree) R.color.accent_free else R.color.accent_busy),
        )
    }

    private fun bindChargerStatus(view: TextView, charger: Charger) {
        val (text, color) = when {
            charger.reservedByMe && charger.isCharging ->
                getString(R.string.charger_mine_wait, charger.remainingMinutes) to R.color.accent_free
            charger.reservedByMe -> getString(R.string.charger_mine_ready) to R.color.accent_free
            charger.isCharging ->
                getString(R.string.charger_charging, charger.remainingMinutes) to R.color.accent_busy
            else -> getString(R.string.charger_free) to R.color.accent_free
        }
        view.text = text
        view.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun bindReserveButton(station: Station, reservation: Reservation?, distance: Double) {
        reserveButton.visibility = if (reservation?.stationId == station.id) View.GONE else View.VISIBLE
        val next = station.nextReservableCharger
        when {
            reservation != null -> {
                reserveButton.isEnabled = false
                reserveButton.setText(R.string.reserve_other)
            }

            distance > RESERVE_RADIUS_METERS -> {
                reserveButton.isEnabled = false
                reserveButton.text = getString(
                    R.string.reserve_too_far, formatDistance(distance - RESERVE_RADIUS_METERS),
                )
            }

            next == null -> reserveButton.isEnabled = false

            else -> {
                reserveButton.isEnabled = true
                reserveButton.text = if (next.isCharging) {
                    getString(R.string.reserve_wait, next.number, next.remainingMinutes)
                } else {
                    getString(R.string.reserve_now, next.number)
                }
            }
        }
    }

    private fun syncRows(container: ViewGroup, count: Int, layout: Int) {
        while (container.childCount > count) container.removeViewAt(container.childCount - 1)
        while (container.childCount < count) {
            container.addView(LayoutInflater.from(this).inflate(layout, container, false))
        }
    }

    private fun formatDistance(meters: Double): String =
        if (meters < 1000) getString(R.string.distance_m, meters.toInt())
        else getString(R.string.distance_km, meters / 1000)

    private fun toast(@StringRes message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        val PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            "pleos.car.permission.CAR_ENERGY",
            "pleos.car.permission.FUSED_LOCATION",
        )
        const val REQUEST_PERMISSIONS = 1

        /** GPS 수신 전 사용할 기본 위치 (서울시청). */
        val DEFAULT_POSITION = GeoPoint(37.5663, 126.9779)

        /** 시뮬레이션 1초당 실제 경과 배율. 시연에서 변화가 보이도록 빠르게 돌린다. */
        const val SIM_SPEED = 6
        const val TICK_MS = 100L
        /** 0.5초마다 화면 패널을 갱신한다. GPS 위치는 0.1초마다 들어올 때마다 바로 반영한다. */
        const val PANEL_INTERVAL_MS = 500L
        /** 데모 주행 속도. 실제보다 빠르게(시속 약 110km) 움직여 시연 시간을 줄인다. */
        const val NAV_SPEED_METERS_PER_SECOND = 30.0
        const val NAV_TURN_DEGREES_PER_SECOND = 220f
        /** 근접 뷰(최대 줌인 단계)와 전체 뷰(최대 줌아웃 단계). */
        val CLOSE_ZOOM = GoMapView.MAX_ZOOM
        val OVERVIEW_ZOOM = GoMapView.MIN_ZOOM
        const val CLOSE_VIEW_THRESHOLD = GoMapView.DEFAULT_ZOOM
        const val LIVE_STATUS_INTERVAL_MS = 60_000L
        /** 받아 둔 지도(도로 반경 2.2km)를 벗어나기 전에 새로 받는다. */
        const val SCENE_REFRESH_METERS = 500.0
        const val SCENE_RETRY_MS = 60_000L
        const val MIN_HEADING_MOVE_METERS = 3.0
        const val MAX_DRIVING_STEP_METERS = 500.0

        /** 이만큼 움직이면 주변 충전소를 다시 받는다. */
        const val STATIONS_REFRESH_METERS = 500.0
        const val WEATHER_REFRESH_MS = 15 * 60_000L
        const val WEATHER_RETRY_MS = 60_000L
        const val WEATHER_REFRESH_METERS = 20_000.0
        const val TWILIGHT_END_DEGREES = 6.0
        const val EXTRA_SUN = "sun"
        const val EXTRA_WEATHER = "weather"

        /** 움직이기 전까지 쓰는 방향. 차 앞모습이 비스듬히 보이는 각도다. */
        const val PARKED_HEADING = 235f
    }
}
