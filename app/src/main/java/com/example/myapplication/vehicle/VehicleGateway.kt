package com.example.myapplication.vehicle

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.myapplication.model.GeoPoint

/**
 * Pleos Connect SDK 연결부. 위치와 배터리 값만 앱에 전달한다.
 * 콜백은 항상 메인 스레드에서 호출되며, 값을 못 받아도 앱은 계속 동작한다.
 */
class VehicleGateway(
    context: Context,
    private val onLocation: (GeoPoint) -> Unit,
    private val onBattery: (Int?) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val vehicle = Vehicle(context)
    private val fusedLocation = FusedLocation(context)

    private val locationListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            // 수신 전에는 null이나 초기값 (0, 0)이 올 수 있어 걸러낸다.
            if (location == null || location.latitude == 0.0 && location.longitude == 0.0) return
            handler.post { onLocation(GeoPoint(location.latitude, location.longitude)) }
        }

        override fun onError(exception: Exception) {
            Log.w(TAG, "위치 수신 실패", exception)
        }
    }

    private val batteryPoll = object : Runnable {
        override fun run() {
            readBattery()
            handler.postDelayed(this, BATTERY_POLL_MS)
        }
    }

    private val subscribeLocation = Runnable {
        guarded("위치 구독") {
            fusedLocation.getLastKnownLocation(locationListener)
            fusedLocation.registerFusedLocationCallback(locationListener)
        }
    }

    fun start() {
        guarded("Vehicle 초기화") { vehicle.initialize() }
        guarded("FusedLocation 초기화") { fusedLocation.initialize() }
        // initialize()는 서비스 바인딩을 시작만 하므로 연결될 시간을 둔다.
        handler.postDelayed(subscribeLocation, SERVICE_CONNECT_DELAY_MS)
        handler.postDelayed(batteryPoll, SERVICE_CONNECT_DELAY_MS)
    }

    fun stop() {
        handler.removeCallbacks(subscribeLocation)
        handler.removeCallbacks(batteryPoll)
        guarded("위치 구독 해제") { fusedLocation.unregisterFusedLocationCallback(locationListener) }
        guarded("FusedLocation 해제") { fusedLocation.release() }
        guarded("Vehicle 해제") { vehicle.release() }
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

    private companion object {
        const val TAG = "VehicleGateway"
        const val SERVICE_CONNECT_DELAY_MS = 1_000L
        const val BATTERY_POLL_MS = 10_000L
    }
}
