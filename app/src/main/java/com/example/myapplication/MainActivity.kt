package com.example.myapplication

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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.myapplication.data.SimulatedChargingNetwork
import com.example.myapplication.data.TmapChargerClient
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
    private var network = SimulatedChargingNetwork(DEFAULT_POSITION)
    private var selectedStationId: String? = null
    private var listedStationIds: List<String> = emptyList()
    private var demoDriving = false
    private var lastTickAt = 0L
    private var simSecondCarry = 0L
    private var lastPanelAt = 0L

    private val weatherClient = WeatherClient()
    private val chargerClient = TmapChargerClient(BuildConfig.TMAP_APP_KEY)
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
        bindViews()
        readDebugOverrides()
        requestPermissions(PERMISSIONS, REQUEST_PERMISSIONS)
        render()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && gateway == null) startVehicleGateway()
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
        map.onZoomLimitsChanged = {
            zoomInButton.isEnabled = map.canZoomIn
            zoomOutButton.isEnabled = map.canZoomOut
        }
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
        if (source == PositionSource.DEMO) return
        // 첫 GPS 수신 시 가상 충전소를 실제 차량 주변으로 옮긴다.
        if (source == PositionSource.DEFAULT && network.reservation() == null) {
            network = SimulatedChargingNetwork(position)
        }
        // 기본 위치에서 첫 GPS로 건너뛴 것은 주행이 아니므로 방향에 반영하지 않는다.
        if (source == PositionSource.VEHICLE && position.distanceTo(car) > MIN_HEADING_MOVE_METERS) {
            heading = car.bearingTo(position)
        }
        car = position
        source = PositionSource.VEHICLE
    }

    private fun toggleDemo() {
        demoDriving = !demoDriving
        if (demoDriving) {
            source = PositionSource.DEMO
            // 움직이는 차를 볼 수 있도록 카메라를 차로 되돌린다.
            map.recenter()
        }
        render()
    }

    /** 선택한 충전소로, 선택이 없으면 가장 가까운 충전소로 가상 주행한다. */
    private fun driveDemo(elapsedMs: Long) {
        val stations = network.stations()
        val target = stations.firstOrNull { it.id == selectedStationId }
            ?: stations.minByOrNull { car.distanceTo(it.position) }
            ?: return
        val distance = car.distanceTo(target.position)
        if (distance <= DEMO_STOP_METERS) {
            demoDriving = false
            render()
            return
        }
        heading = car.bearingTo(target.position)
        val step = DEMO_SPEED_METERS_PER_SECOND * elapsedMs / 1000.0
        car = car.moveToward(target.position, minOf(step, distance - DEMO_STOP_METERS / 2))
        map.setCar(car, heading)
    }

    private fun advanceSimulation(elapsedMs: Long) {
        simSecondCarry += elapsedMs * SIM_SPEED
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
        chargerClient.fetch(center) { plans ->
            stationsLoading = false
            if (plans == null || network.reservation() != null) return@fetch
            network = SimulatedChargingNetwork(center, plans)
            simChip.text = getString(R.string.sim_badge_tmap, SIM_SPEED)
            render()
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
        demoButton.setText(if (demoDriving) R.string.demo_stop else R.string.demo_start)

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
            "pleos.car.permission.CAR_ENERGY",
            "pleos.car.permission.FUSED_LOCATION",
        )
        const val REQUEST_PERMISSIONS = 1

        /** GPS 수신 전 사용할 기본 위치 (서울시청). */
        val DEFAULT_POSITION = GeoPoint(37.5663, 126.9779)

        /** 시뮬레이션 1초당 실제 경과 배율. 시연에서 변화가 보이도록 빠르게 돌린다. */
        const val SIM_SPEED = 6
        const val TICK_MS = 100L
        const val PANEL_INTERVAL_MS = 1_000L
        const val DEMO_SPEED_METERS_PER_SECOND = 120.0
        const val DEMO_STOP_METERS = 40.0
        const val MIN_HEADING_MOVE_METERS = 3.0

        /** 이만큼 움직이면 주변 충전소를 다시 받는다. 검색 반경(5km)보다 작게 잡는다. */
        const val STATIONS_REFRESH_METERS = 3_000.0
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
