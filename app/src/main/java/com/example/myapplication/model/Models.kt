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

/** 충전 속도 구분. 공공데이터 기준으로 완속은 AC 완속형과 11kW 이하 충전기다. */
enum class ChargerSpeed { FAST, SLOW }

data class Charger(
    val number: Int,
    /** 현재 차량의 남은 충전 시간(초). 비어 있으면 null. */
    val remainingSeconds: Int?,
    val reservedByMe: Boolean,
    /** 지금 충전이 끝난 뒤 차례를 기다리는 다른 사람 예약들의 충전 시간(초), 순서대로. */
    val othersQueueSeconds: List<Int> = emptyList(),
    /** 급속/완속. 데이터에 없으면 null. */
    val speed: ChargerSpeed? = null,
) {
    val isCharging: Boolean get() = remainingSeconds != null
    val remainingMinutes: Int get() = secondsToMinutes(remainingSeconds ?: 0)
    val othersReserved: Int get() = othersQueueSeconds.size

    /** 지금 충전 중인 차와 앞선 다른 사람 예약이 모두 끝나 내가 쓸 수 있을 때까지 남은 초. */
    val availableInSeconds: Int get() = (remainingSeconds ?: 0) + othersQueueSeconds.sum()
    val availableInMinutes: Int get() = secondsToMinutes(availableInSeconds)
}

data class Station(
    val id: String,
    val name: String,
    val position: GeoPoint,
    val chargers: List<Charger>,
) {
    val total: Int get() = chargers.size
    val chargingCount: Int get() = chargers.count { it.isCharging }
    val fastCount: Int get() = chargers.count { it.speed == ChargerSpeed.FAST }
    val slowCount: Int get() = chargers.count { it.speed == ChargerSpeed.SLOW }

    /** 지금 바로 쓸 수 있는 자리 수. 내가 예약한 자리와 다른 사람이 예약한 자리는 제외한다. */
    val freeCount: Int get() = chargers.count { !it.isCharging && !it.reservedByMe && it.othersReserved == 0 }

    /** 다른 사람들이 걸어 둔 예약 수. */
    val othersReservedCount: Int get() = chargers.sumOf { it.othersReserved }

    /** 가장 먼저 쓸 수 있게 되는 충전기까지 남은 분(다른 사람 예약 포함). 빈 자리가 있으면 0. */
    val nextFreeMinutes: Int
        get() = if (freeCount > 0) 0 else chargers.filter { !it.reservedByMe }.minOfOrNull { it.availableInSeconds }
            ?.let(::secondsToMinutes) ?: 0

    /** 예약 시 배정될 충전기: 다른 사람 예약까지 고려해 가장 빨리 쓸 수 있는 자리. */
    val nextReservableCharger: Charger?
        get() = chargers.filter { !it.reservedByMe }
            .minByOrNull { it.availableInSeconds }

    /**
     * 지금 출발해 [travelSeconds] 걸려 도착하면 충전을 시작할 수 있을 때까지 걸리는 초.
     * 도착 시간과, 충전 중인 차·다른 사람 예약이 끝나는 시간 중 늦은 쪽이다. 쓸 수 있는 충전기가 없으면 null.
     */
    fun startInSeconds(travelSeconds: Int): Int? =
        chargers.filter { !it.reservedByMe }.minOfOrNull { maxOf(travelSeconds, it.availableInSeconds) }
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
