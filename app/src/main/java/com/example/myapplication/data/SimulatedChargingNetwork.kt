package com.example.myapplication.data

import com.example.myapplication.model.Charger
import com.example.myapplication.model.ChargerSpeed
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
    /**
     * 충전 중인 충전기에 다른 사람 예약이 걸려 있을 확률. 0이면 다른 사람 예약을 만들지 않는다.
     * 실제 예약 목록을 주는 공개 API가 없어 가상으로 만든다. 사업자 예약 API가 생기면 그 값으로 바꾼다.
     */
    private val othersReservationChance: Double = 0.0,
    private val reservationRandom: Random = Random(11),
) {
    data class StationPlan(
        val id: String,
        val name: String,
        val northMeters: Double,
        val eastMeters: Double,
        val total: Int,
        val occupied: Int,
        /** 실시간 상태를 받을 때 맞춰 볼 충전기 ID. */
        val chargerIds: List<String> = emptyList(),
        /** 충전기별 남은 시간(초), null은 비어 있음. 목록이 없으면 앞쪽 [occupied]대에 임의 시간을 준다. */
        val remainingSeconds: List<Int?>? = null,
        /** 충전기별 급속/완속. 목록이 없거나 모르는 자리는 null. */
        val speeds: List<ChargerSpeed?>? = null,
    )

    /** 실제 충전기 상태 한 건. [remainingSeconds]가 null이면 비어 있다. */
    data class LiveUpdate(val stationId: String, val chargerId: String, val remainingSeconds: Int?)

    private class SimCharger(var remainingSeconds: Int?, val speed: ChargerSpeed?) {
        /** 내가 시작한 충전. 실시간 상태로 덮어쓰지 않는다. */
        var mine = false
        /** 다른 사람 예약으로 시작한 충전. 가상이므로 실시간 상태로 덮어쓰지 않는다. */
        var fromReservation = false
        /** 지금 충전이 끝나면 차례로 쓸 다른 사람 예약의 충전 시간(초). */
        val othersQueue = ArrayList<Int>()
    }

    private class SimStation(
        val id: String,
        val name: String,
        val position: GeoPoint,
        val chargers: List<SimCharger>,
        val chargerIds: List<String>,
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
                val given = plan.remainingSeconds
                SimCharger(
                    when {
                        given != null -> given.getOrNull(index)
                        index < plan.occupied -> randomSession()
                        else -> null
                    },
                    plan.speeds?.getOrNull(index),
                )
            },
            chargerIds = plan.chargerIds,
        ).also { station ->
            for (charger in station.chargers) {
                if (charger.remainingSeconds == null) continue
                if (reservationRandom.nextDouble() < othersReservationChance) charger.othersQueue += randomSession()
                if (reservationRandom.nextDouble() < othersReservationChance / 3) charger.othersQueue += randomSession()
            }
        }
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
                    othersQueueSeconds = charger.othersQueue.toList(),
                    speed = charger.speed,
                )
            },
        )
    }

    fun reservation(): Reservation? = reservation?.let { active ->
        val charger = active.station.chargers[active.chargerIndex]
        // 앞선 다른 사람 예약까지 끝나야 내 차례다.
        val waitSeconds = (charger.remainingSeconds ?: 0) + charger.othersQueue.sum()
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
            .minBy { (station.chargers[it].remainingSeconds ?: 0) + station.chargers[it].othersQueue.sum() }
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
        charger.mine = true
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
                    remaining != null -> {
                        charger.remainingSeconds = (remaining - seconds).takeIf { it > 0 }
                        if (charger.remainingSeconds == null) {
                            charger.mine = false
                            charger.fromReservation = false
                            startNextReservation(charger)
                        }
                    }

                    // 비었는데 앞선 다른 사람 예약이 있으면 그 사람이 먼저 쓴다.
                    startNextReservation(charger) -> Unit

                    !isReserved(station, index) &&
                        random.nextDouble() < arrivalChancePerMinute * seconds / 60.0 ->
                        charger.remainingSeconds = randomSession()
                }
                // 충전 중인 자리에 새 다른 사람 예약이 들어온다. 내가 예약한 자리는 내 뒤라서 세지 않는다.
                if (charger.remainingSeconds != null && !isReserved(station, index) &&
                    charger.othersQueue.size < MAX_OTHERS_QUEUE &&
                    reservationRandom.nextDouble() < othersReservationChance * NEW_RESERVATION_RATE * seconds / 60.0
                ) {
                    charger.othersQueue += randomSession()
                }
            }
        }
        return advanceHold(seconds)
    }

    /** 대기 중인 다른 사람 예약이 있으면 충전을 시작시키고 true를 돌려준다. */
    private fun startNextReservation(charger: SimCharger): Boolean {
        if (charger.remainingSeconds != null || charger.othersQueue.isEmpty()) return false
        charger.remainingSeconds = charger.othersQueue.removeAt(0)
        charger.fromReservation = true
        return true
    }

    /**
     * 실제 충전기 상태를 반영한다. 이미 충전 중으로 세고 있는 충전기는 남은 시간을 그대로 둔다.
     */
    fun applyLive(updates: List<LiveUpdate>) {
        for (update in updates) {
            val station = stations.firstOrNull { it.id == update.stationId } ?: continue
            val index = station.chargerIds.indexOf(update.chargerId)
            if (index < 0) continue
            val charger = station.chargers[index]
            if (charger.mine || charger.fromReservation) continue
            charger.remainingSeconds = when {
                update.remainingSeconds == null -> null
                charger.remainingSeconds != null -> charger.remainingSeconds
                else -> update.remainingSeconds
            }
        }
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
        /** 앱에서 쓰는 다른 사람 예약 확률(가상). */
        const val DEMO_OTHERS_RESERVATION_CHANCE = 0.35
        /** 충전기 한 대에 쌓일 수 있는 다른 사람 예약 수. */
        private const val MAX_OTHERS_QUEUE = 2
        /** 분당 새 예약이 들어올 확률 = [othersReservationChance] x 이 값. */
        private const val NEW_RESERVATION_RATE = 0.05
        private val FAST = ChargerSpeed.FAST
        private val SLOW = ChargerSpeed.SLOW

        val DEFAULT_PLANS = listOf(
            StationPlan("hanbit", "한빛공원 충전소", 900.0, 500.0, total = 2, occupied = 1, speeds = listOf(FAST, SLOW)),
            StationPlan("saesol", "새솔마을 충전소", -650.0, -1_100.0, total = 2, occupied = 2, speeds = listOf(SLOW, SLOW)),
            StationPlan("market", "중앙시장 공영주차장", 2_200.0, -900.0, total = 4, occupied = 4, speeds = listOf(FAST, FAST, SLOW, SLOW)),
            StationPlan("river", "강변로 급속충전소", 2_800.0, 1_500.0, total = 3, occupied = 2, speeds = listOf(FAST, FAST, FAST)),
            StationPlan("annex", "시청 별관 충전소", 4_200.0, -300.0, total = 3, occupied = 1, speeds = listOf(FAST, SLOW, SLOW)),
        )
    }
}
