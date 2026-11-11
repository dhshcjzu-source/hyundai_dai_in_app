package com.example.myapplication.model

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * 남은 배터리로 갈 수 있는 거리 계산.
 * 차량이 알려주는 주행 가능 거리를 우선 쓰고, 없으면 배터리 잔량(%)으로 추정한다.
 */
object DrivingRange {

    /** 차량 주행 가능 거리를 못 받았을 때 쓰는 완충 시 주행 거리(미터). */
    const val DEFAULT_FULL_RANGE_METERS = 400_000.0

    /** 경로를 못 받았을 때 직선거리에 곱하는 도로 우회 배율. [com.example.myapplication.nav.StationEtas]와 같은 값이다. */
    const val ROAD_FACTOR = 1.3

    /** 도착 후에도 남겨 둘 여유분: 주행 가능 거리의 10%와 5km 중 큰 쪽. */
    const val RESERVE_RATIO = 0.1
    const val MIN_RESERVE_METERS = 5_000.0

    /** 배터리 잔량(%)으로 추정한 주행 가능 거리(미터). */
    fun estimateFromSoc(percent: Int): Double =
        percent.coerceIn(0, 100) / 100.0 * DEFAULT_FULL_RANGE_METERS

    /**
     * SDK가 준 주행 가능 거리를 미터로 바꾼다. 단위(km/m)가 문서로 확정되지 않아,
     * 배터리 잔량 추정치와 더 가까운 해석을 고르고, 잔량을 모르면 1,500 이하를 km로 본다.
     */
    fun fromSdkValue(value: Float?, socPercent: Int?): Double? {
        if (value == null || value.isNaN() || value <= 0f) return null
        val asKm = value * 1000.0
        val asMeters = value.toDouble()
        val estimate = socPercent?.let(::estimateFromSoc)?.takeIf { it > 0 }
            ?: return if (value <= 1_500f) asKm else asMeters
        return if (abs(ln(asKm / estimate)) <= abs(ln(asMeters / estimate))) asKm else asMeters
    }

    /** 여유분을 뺀, 충전소까지 실제로 쓸 수 있는 거리(미터). */
    fun usableMeters(rangeMeters: Double): Double =
        (rangeMeters - max(MIN_RESERVE_METERS, rangeMeters * RESERVE_RATIO)).coerceAtLeast(0.0)

    /** 충전소까지 도로 거리. 실제 경로가 있으면 그 거리, 없으면 직선거리로 추정한다. */
    fun roadMeters(straightMeters: Double, routeMeters: Double?): Double =
        routeMeters ?: (straightMeters * ROAD_FACTOR)

    fun isReachable(straightMeters: Double, routeMeters: Double?, rangeMeters: Double): Boolean =
        roadMeters(straightMeters, routeMeters) <= usableMeters(rangeMeters)
}
