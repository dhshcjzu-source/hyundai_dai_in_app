package com.example.myapplication.model

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val latitude: Double, val longitude: Double) {

    fun distanceTo(other: GeoPoint): Double {
        val dLat = Math.toRadians(other.latitude - latitude)
        val dLng = Math.toRadians(other.longitude - longitude)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(latitude)) * cos(Math.toRadians(other.latitude)) *
            sin(dLng / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /** 이 지점에서 북쪽/동쪽으로 이동한 지점. 수 km 이내에서 쓰는 근사식이다. */
    fun offset(northMeters: Double, eastMeters: Double): GeoPoint = GeoPoint(
        latitude + Math.toDegrees(northMeters / EARTH_RADIUS_M),
        longitude + Math.toDegrees(eastMeters / (EARTH_RADIUS_M * cos(Math.toRadians(latitude)))),
    )

    /** [origin] 기준 (동쪽, 북쪽) 미터 좌표. */
    fun metersFrom(origin: GeoPoint): Pair<Double, Double> {
        val north = Math.toRadians(latitude - origin.latitude) * EARTH_RADIUS_M
        val east = Math.toRadians(longitude - origin.longitude) * EARTH_RADIUS_M *
            cos(Math.toRadians(origin.latitude))
        return east to north
    }

    /** [target] 방향으로 최대 [meters]만큼 이동한 지점. 넘어가지 않고 target에서 멈춘다. */
    fun moveToward(target: GeoPoint, meters: Double): GeoPoint {
        val distance = distanceTo(target)
        if (distance <= meters || distance == 0.0) return target
        val ratio = meters / distance
        return GeoPoint(
            latitude + (target.latitude - latitude) * ratio,
            longitude + (target.longitude - longitude) * ratio,
        )
    }

    /** [target]을 향하는 방위각 (북=0, 동=90). */
    fun bearingTo(target: GeoPoint): Float {
        val (east, north) = target.metersFrom(this)
        return ((atan2(east, north) * 180 / PI + 360) % 360).toFloat()
    }

    private companion object {
        const val EARTH_RADIUS_M = 6_371_000.0
    }
}

data class Charger(
    val number: Int,
    /** 현재 차량의 남은 충전 시간(초). 비어 있으면 null. */
    val remainingSeconds: Int?,
    val reservedByMe: Boolean,
) {
    val isCharging: Boolean get() = remainingSeconds != null
    val remainingMinutes: Int get() = secondsToMinutes(remainingSeconds ?: 0)
}

data class Station(
    val id: String,
    val name: String,
    val position: GeoPoint,
    val chargers: List<Charger>,
) {
    val total: Int get() = chargers.size
    val chargingCount: Int get() = chargers.count { it.isCharging }

    /** 지금 바로 쓸 수 있는 자리 수. 내가 예약한 자리는 제외한다. */
    val freeCount: Int get() = chargers.count { !it.isCharging && !it.reservedByMe }

    /** 가장 먼저 끝나는 충전기까지 남은 분. 빈 자리가 있으면 0. */
    val nextFreeMinutes: Int
        get() = if (freeCount > 0) 0 else chargers.mapNotNull { it.remainingSeconds }.minOrNull()
            ?.let(::secondsToMinutes) ?: 0

    /** 예약 시 배정될 충전기: 빈 자리 우선, 없으면 가장 빨리 끝나는 자리. */
    val nextReservableCharger: Charger?
        get() = chargers.filter { !it.reservedByMe }
            .minByOrNull { it.remainingSeconds ?: 0 }
}

data class Reservation(
    val stationId: String,
    val stationName: String,
    val chargerNumber: Int,
    /** 앞 차량 충전이 끝나기까지 남은 분. 자리가 이미 비었으면 0. */
    val waitMinutes: Int,
    /** 자리가 빈 뒤 내 예약이 유지되는 분. */
    val holdMinutes: Int,
)

sealed interface ReserveResult {
    data class Success(val reservation: Reservation) : ReserveResult
    data object TooFar : ReserveResult
    data object AlreadyReserved : ReserveResult
    data object UnknownStation : ReserveResult
}

sealed interface StartChargingResult {
    data object Started : StartChargingResult
    data object NoReservation : StartChargingResult
    data object NotArrived : StartChargingResult
    data object ChargerBusy : StartChargingResult
}

private fun secondsToMinutes(seconds: Int): Int = ceil(seconds / 60.0).toInt()
