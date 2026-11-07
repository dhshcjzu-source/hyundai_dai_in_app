package com.example.myapplication.data

import com.example.myapplication.model.Charger
import com.example.myapplication.model.GeoPoint
import com.example.myapplication.model.ReserveResult
import com.example.myapplication.model.Reservation
import com.example.myapplication.model.StartChargingResult
import com.example.myapplication.model.Station
import kotlin.math.ceil
import kotlin.random.Random

/**
 * 충전소 예약 서버를 대신하는 시뮬레이터.
 *
 * Pleos Connect SDK는 차량 정보만 제공하고, 충전소 현황과 예약은 다루지 않는다.
 * 실서비스에서는 이 클래스를 충전 사업자 서버/API 호출로 교체한다.
 * 충전소 이름과 현황은 모두 가상의 데이터다.
 */
class SimulatedChargingNetwork(
    origin: GeoPoint,
    seedPlans: List<StationPlan> = DEFAULT_PLANS,
    private val random: Random = Random(7),
    private val arrivalChancePerMinute: Double = 0.15,
) {
    data class StationPlan(
        val id: String,
        val name: String,
        val northMeters: Double,
        val eastMeters: Double,
        val total: Int,
        val occupied: Int,
    )

    private class SimCharger(var remainingSeconds: Int?)

    private class SimStation(
        val id: String,
        val name: String,
        val position: GeoPoint,
        val chargers: List<SimCharger>,
    )

    private class SimReservation(
        val station: SimStation,
        val chargerIndex: Int,
        var holdSeconds: Int,
    )

    private val stations: List<SimStation> = seedPlans.map { plan ->
        SimStation(
            id = plan.id,
            name = plan.name,
            position = origin.offset(plan.northMeters, plan.eastMeters),
            chargers = List(plan.total) { index ->
                SimCharger(if (index < plan.occupied) randomSession() else null)
            },
        )
    }

    private var reservation: SimReservation? = null

    fun stations(): List<Station> = stations.map { station ->
        Station(
            id = station.id,
            name = station.name,
            position = station.position,
            chargers = station.chargers.mapIndexed { index, charger ->
                Charger(
                    number = index + 1,
                    remainingSeconds = charger.remainingSeconds,
                    reservedByMe = isReserved(station, index),
                )
            },
        )
    }

    fun reservation(): Reservation? = reservation?.let { active ->
        val waitSeconds = active.station.chargers[active.chargerIndex].remainingSeconds ?: 0
        Reservation(
            stationId = active.station.id,
            stationName = active.station.name,
            chargerNumber = active.chargerIndex + 1,
            waitMinutes = ceil(waitSeconds / 60.0).toInt(),
            holdMinutes = ceil(active.holdSeconds / 60.0).toInt(),
        )
    }

    /** 위치 조건은 서버 쪽 규칙이므로 화면이 아니라 여기서 검증한다. */
    fun reserve(stationId: String, car: GeoPoint): ReserveResult {
        if (reservation != null) return ReserveResult.AlreadyReserved
        val station = stations.firstOrNull { it.id == stationId } ?: return ReserveResult.UnknownStation
        if (car.distanceTo(station.position) > RESERVE_RADIUS_METERS) return ReserveResult.TooFar

        val chargerIndex = station.chargers.indices
            .minBy { station.chargers[it].remainingSeconds ?: 0 }
        reservation = SimReservation(station, chargerIndex, HOLD_SECONDS)
        return ReserveResult.Success(reservation()!!)
    }

    fun cancelReservation() {
        reservation = null
    }

    fun startCharging(car: GeoPoint): StartChargingResult {
        val active = reservation ?: return StartChargingResult.NoReservation
        if (car.distanceTo(active.station.position) > ARRIVAL_RADIUS_METERS) {
            return StartChargingResult.NotArrived
        }
        val charger = active.station.chargers[active.chargerIndex]
        if (charger.remainingSeconds != null) return StartChargingResult.ChargerBusy
        charger.remainingSeconds = MY_SESSION_SECONDS
        reservation = null
        return StartChargingResult.Started
    }

    /**
     * 시뮬레이션 시간을 [seconds]초 진행한다.
     * @return 이번 진행으로 내 예약이 만료되었으면 true
     */
    fun advance(seconds: Int): Boolean {
        stations.forEach { station ->
            station.chargers.forEachIndexed { index, charger ->
                val remaining = charger.remainingSeconds
                when {
                    remaining != null ->
                        charger.remainingSeconds = (remaining - seconds).takeIf { it > 0 }

                    !isReserved(station, index) &&
                        random.nextDouble() < arrivalChancePerMinute * seconds / 60.0 ->
                        charger.remainingSeconds = randomSession()
                }
            }
        }
        return advanceHold(seconds)
    }

    /** 앞 차량이 충전 중일 때는 유지 시간이 줄지 않는다. */
    private fun advanceHold(seconds: Int): Boolean {
        val active = reservation ?: return false
        if (active.station.chargers[active.chargerIndex].remainingSeconds != null) return false
        active.holdSeconds -= seconds
        if (active.holdSeconds > 0) return false
        reservation = null
        return true
    }

    private fun isReserved(station: SimStation, chargerIndex: Int): Boolean =
        reservation?.let { it.station === station && it.chargerIndex == chargerIndex } == true

    private fun randomSession(): Int = random.nextInt(8 * 60, 45 * 60)

    companion object {
        const val RESERVE_RADIUS_METERS = 1_500.0
        const val ARRIVAL_RADIUS_METERS = 100.0
        const val HOLD_SECONDS = 15 * 60
        const val MY_SESSION_SECONDS = 40 * 60

        val DEFAULT_PLANS = listOf(
            StationPlan("hanbit", "한빛공원 충전소", 900.0, 500.0, total = 2, occupied = 1),
            StationPlan("saesol", "새솔마을 충전소", -650.0, -1_100.0, total = 2, occupied = 2),
            StationPlan("market", "중앙시장 공영주차장", 2_200.0, -900.0, total = 4, occupied = 4),
            StationPlan("river", "강변로 급속충전소", 2_800.0, 1_500.0, total = 3, occupied = 2),
            StationPlan("annex", "시청 별관 충전소", 4_200.0, -300.0, total = 3, occupied = 1),
        )
    }
}
