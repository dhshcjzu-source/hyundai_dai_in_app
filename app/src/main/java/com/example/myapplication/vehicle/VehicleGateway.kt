package com.example.myapplication.vehicle

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.vehicle.Vehicle
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.myapplication.model.GeoPoint
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Pleos Connect SDK, 안드로이드 시스템 GPS 및 네트워크 현재 위치 연결부.
 * 위치는 0.1초마다 갱신하여 앱에 전달하며, 콜백은 항상 메인 스레드에서 호출된다.
 */
class VehicleGateway(
    private val context: Context,
    private val onLocation: (GeoPoint) -> Unit,
    private val onBattery: (Int?) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val vehicle = Vehicle(context)
    private val fusedLocation = FusedLocation(context)
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val networkExecutor = Executors.newSingleThreadExecutor()

    private var fusedConnected = false
    private var systemRegistered = false
    private var stopped = false

    /** 에뮬레이터 초기 기본값(서울시청 고정값)이 아닌 실제/이동 GPS 신호를 받았는지 여부. */
    private var hasActiveGpsSignal = false
    private var initialEmulatorDefaultFix: GeoPoint? = null
    private var latestGpsPoint: GeoPoint? = null
    private var latestNetworkPoint: GeoPoint? = null
    private var lastNetworkFetchAt = 0L
    private var networkFetching = false
    private var lastSystemGpsAt = 0L

    private val locationListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            if (location == null || (location.latitude == 0.0 && location.longitude == 0.0)) return
            onGpsCandidate(GeoPoint(location.latitude, location.longitude), "PleosFused")
        }

        override fun onError(exception: Exception) {
            Log.w(TAG, "위치 수신 실패", exception)
        }
    }

    private val systemLocationListener = LocationListener { location ->
        if (location.latitude == 0.0 && location.longitude == 0.0) return@LocationListener
        onGpsCandidate(GeoPoint(location.latitude, location.longitude), "System:${location.provider}")
    }

    private val locationPoll = object : Runnable {
        override fun run() {
            if (stopped) return
            pollLocation()
            handler.postDelayed(this, GPS_UPDATE_INTERVAL_MS)
        }
    }

    private val batteryPoll = object : Runnable {
        override fun run() {
            if (stopped) return
            readBattery()
            handler.postDelayed(this, BATTERY_POLL_MS)
        }
    }

    private val subscribeLocation = Runnable {
        if (stopped) return@Runnable
        fusedConnected = true
        guarded("위치 구독") {
            fusedLocation.getLastKnownLocation(locationListener)
            fusedLocation.registerFusedLocationCallback(locationListener)
        }
    }

    fun start() {
        stopped = false
        guarded("Vehicle 초기화") { vehicle.initialize() }
        guarded("FusedLocation 초기화") { fusedLocation.initialize() }
        registerSystemLocation()
        fetchNetworkLocationIfNeeded(force = true)
        pollLocation()
        handler.postDelayed(locationPoll, GPS_UPDATE_INTERVAL_MS)
        // initialize()는 서비스 바인딩을 시작만 하므로 연결될 시간을 둔다.
        handler.postDelayed(subscribeLocation, SERVICE_CONNECT_DELAY_MS)
        handler.postDelayed(batteryPoll, SERVICE_CONNECT_DELAY_MS)
    }

    fun onPermissionsUpdated() {
        registerSystemLocation()
        pollLocation()
    }

    fun stop() {
        stopped = true
        handler.removeCallbacks(locationPoll)
        handler.removeCallbacks(subscribeLocation)
        handler.removeCallbacks(batteryPoll)
        fusedConnected = false
        if (systemRegistered) {
            guarded("시스템 GPS 구독 해제") { locationManager?.removeUpdates(systemLocationListener) }
            systemRegistered = false
        }
        guarded("위치 구독 해제") { fusedLocation.unregisterFusedLocationCallback(locationListener) }
        guarded("FusedLocation 해제") { fusedLocation.release() }
        guarded("Vehicle 해제") { vehicle.release() }
        networkExecutor.shutdownNow()
    }

    @SuppressLint("MissingPermission")
    private fun registerSystemLocation() {
        if (systemRegistered || !hasLocationPermission()) return
        val manager = locationManager ?: return
        val providers = runCatching { manager.allProviders }.getOrNull().orEmpty()
        for (provider in providers) {
            guarded("시스템 GPS 등록($provider)") {
                if (manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(
                        provider,
                        GPS_UPDATE_INTERVAL_MS,
                        0f,
                        systemLocationListener,
                        Looper.getMainLooper(),
                    )
                    systemRegistered = true
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun pollLocation() {
        if (hasLocationPermission()) {
            registerSystemLocation()
            val manager = locationManager
            if (manager != null) {
                var bestNonDefault: Location? = null
                var bestAny: Location? = null
                val providers = runCatching { manager.allProviders }.getOrNull().orEmpty()
                for (provider in providers) {
                    val candidate = runCatching { manager.getLastKnownLocation(provider) }.getOrNull() ?: continue
                    if (candidate.latitude == 0.0 && candidate.longitude == 0.0) continue
                    val point = GeoPoint(candidate.latitude, candidate.longitude)
                    if (!isStaticEmulatorDefault(point)) {
                        if (bestNonDefault == null || candidate.elapsedRealtimeNanos > bestNonDefault.elapsedRealtimeNanos) {
                            bestNonDefault = candidate
                        }
                    }
                    if (bestAny == null || candidate.elapsedRealtimeNanos > bestAny.elapsedRealtimeNanos) {
                        bestAny = candidate
                    }
                }
                val chosen = bestNonDefault ?: bestAny
                chosen?.let {
                    onGpsCandidate(GeoPoint(it.latitude, it.longitude), "Poll:${it.provider}")
                }
            }
        }
        if (fusedConnected) {
            guarded("위치 주기 조회") {
                fusedLocation.getLastKnownLocation(locationListener)
            }
        }
        fetchNetworkLocationIfNeeded(force = false)
        // 0.1초마다 최신 유효 위치를 전달한다.
        val current = if (hasActiveGpsSignal) latestGpsPoint else (latestNetworkPoint ?: latestGpsPoint)
        current?.let(::reportLocation)
    }

    private fun onGpsCandidate(point: GeoPoint, sourceName: String) {
        if (!isStaticEmulatorDefault(point)) {
            val now = SystemClock.elapsedRealtime()
            val fromFused = sourceName == "PleosFused"
            // 시스템 GPS가 살아 있으면 Fused 값과 번갈아 쓰지 않는다. 번갈아 쓰면 이동 중 차가 앞뒤로 튄다.
            if (fromFused && now - lastSystemGpsAt < SYSTEM_GPS_PRIORITY_MS) return
            if (!fromFused) lastSystemGpsAt = now
            hasActiveGpsSignal = true
            if (point == latestGpsPoint) return
            latestGpsPoint = point
            Log.d(TAG, "활성 GPS 수신($sourceName): ${point.latitude}, ${point.longitude}")
            reportLocation(point)
        } else if (latestNetworkPoint == null && SystemClock.elapsedRealtime() - lastNetworkFetchAt > NETWORK_FALLBACK_TIMEOUT_MS) {
            // 네트워크 위치 조회도 실패한 경우에만 에뮬레이터 기본 좌표를 사용한다.
            latestGpsPoint = point
            reportLocation(point)
        }
    }

    /**
     * 에뮬레이터의 초기 고정 좌표(서울시청 부근)인지 판별한다.
     * 단, 초기 좌표에서 1m 이상 실제로 움직이기 시작하면 실제 주행 GPS 신호로 인정한다.
     */
    private fun isStaticEmulatorDefault(point: GeoPoint): Boolean {
        if (point.distanceTo(EMULATOR_DEFAULT_SEOUL) > EMULATOR_DEFAULT_RADIUS_METERS) {
            return false
        }
        val first = initialEmulatorDefaultFix
        if (first == null) {
            initialEmulatorDefaultFix = point
            return true
        }
        return point.distanceTo(first) < MIN_GPS_MOVEMENT_METERS
    }

    private fun fetchNetworkLocationIfNeeded(force: Boolean) {
        if (hasActiveGpsSignal || networkFetching) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNetworkFetchAt < NETWORK_REFRESH_INTERVAL_MS) return
        networkFetching = true
        lastNetworkFetchAt = now
        networkExecutor.execute {
            val resolved = fetchLocationFromIpInfo() ?: fetchLocationFromIpApi()
            handler.post {
                networkFetching = false
                if (stopped || resolved == null) return@post
                latestNetworkPoint = resolved
                if (!hasActiveGpsSignal) {
                    Log.i(TAG, "현재 네트워크 기반 위치 갱신: ${resolved.latitude}, ${resolved.longitude}")
                    reportLocation(resolved)
                }
            }
        }
    }

    private fun fetchLocationFromIpInfo(): GeoPoint? = runCatching {
        val conn = (URL("https://ipinfo.io/json").openConnection() as HttpURLConnection).apply {
            connectTimeout = 2_500
            readTimeout = 2_500
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val loc = JSONObject(body).optString("loc", "")
            val parts = loc.split(",")
            if (parts.size != 2) return null
            val lat = parts[0].trim().toDoubleOrNull() ?: return null
            val lon = parts[1].trim().toDoubleOrNull() ?: return null
            if (lat == 0.0 && lon == 0.0) null else GeoPoint(lat, lon)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun fetchLocationFromIpApi(): GeoPoint? = runCatching {
        val conn = (URL("https://freeipapi.com/api/json").openConnection() as HttpURLConnection).apply {
            connectTimeout = 2_500
            readTimeout = 2_500
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val lat = json.optDouble("latitude", 0.0)
            val lon = json.optDouble("longitude", 0.0)
            if (lat == 0.0 && lon == 0.0) null else GeoPoint(lat, lon)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun reportLocation(point: GeoPoint) {
        if (stopped) return
        handler.post {
            if (!stopped) onLocation(point)
        }
    }

    private fun readBattery() {
        guarded("배터리 조회") {
            vehicle.getEvBattery().getEvBatteryStateOfCharge(
                { percent -> handler.post { onBattery(percent) } },
                { error ->
                    Log.w(TAG, "배터리 조회 실패", error)
                    handler.post { onBattery(null) }
                },
            )
        }
    }

    private inline fun guarded(action: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$action 실패", e)
        }
    }

    companion object {
        private const val TAG = "VehicleGateway"
        private const val SERVICE_CONNECT_DELAY_MS = 1_000L
        private const val BATTERY_POLL_MS = 10_000L
        /** GPS 위치 갱신 주기 (0.1초). */
        const val GPS_UPDATE_INTERVAL_MS = 100L
        /** 시스템 GPS가 이 시간 안에 들어왔으면 Pleos Fused 값보다 우선한다(소스 간 위치 튐 방지). */
        private const val SYSTEM_GPS_PRIORITY_MS = 3_000L
        private const val NETWORK_REFRESH_INTERVAL_MS = 3_000L
        private const val NETWORK_FALLBACK_TIMEOUT_MS = 4_000L
        private const val EMULATOR_DEFAULT_RADIUS_METERS = 300.0
        private const val MIN_GPS_MOVEMENT_METERS = 1.0
        /** 에뮬레이터 초기 고정 위치(서울시청). */
        private val EMULATOR_DEFAULT_SEOUL = GeoPoint(37.5665, 126.9780)
    }
}
