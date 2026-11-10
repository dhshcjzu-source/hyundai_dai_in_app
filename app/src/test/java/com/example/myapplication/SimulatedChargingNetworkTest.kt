package com.example.myapplication

import com.example.myapplication.data.SimulatedChargingNetwork
import com.example.myapplication.data.SimulatedChargingNetwork.StationPlan
import com.example.myapplication.model.GeoPoint
import com.example.myapplication.model.ReserveResult
import com.example.myapplication.model.StartChargingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SimulatedChargingNetworkTest {
    private val origin = GeoPoint(37.5663, 126.9779)
    private val nearFree = StationPlan("free", "빈자리", 0.0, 500.0, total = 2, occupied = 1)
    private val nearFull = StationPlan("full", "만차", 0.0, -500.0, total = 2, occupied = 2)
    private val far = StationPlan("far", "먼곳", 5_000.0, 0.0, total = 2, occupied = 0)

    private fun network() = SimulatedChargingNetwork(
        origin, listOf(nearFree, nearFull, far), Random(1), arrivalChancePerMinute = 0.0,
    )

    private fun SimulatedChargingNetwork.station(id: String) = stations().first { it.id == id }

    @Test
    fun `distance is measured correctly`() {
        assertEquals(1_000.0, origin.distanceTo(origin.offset(1_000.0, 0.0)), 1.0)
        assertEquals(1_000.0, origin.distanceTo(origin.offset(0.0, 1_000.0)), 1.0)
    }

    @Test
    fun `station reports charging count and total`() {
        val station = network().station("free")
        assertEquals(1, station.chargingCount)
        assertEquals(2, station.total)
        assertEquals(1, station.freeCount)
        assertEquals(0, station.nextFreeMinutes)
    }

    @Test
    fun `full station reports minutes until the first charger finishes`() {
        val station = network().station("full")
        val shortest = station.chargers.minOf { it.remainingSeconds!! }
        assertEquals(0, station.freeCount)
        assertEquals((shortest + 59) / 60, station.nextFreeMinutes)
    }

    @Test
    fun `cannot reserve outside the radius`() {
        assertEquals(ReserveResult.TooFar, network().reserve("far", origin))
    }

    @Test
    fun `can reserve once the car moves into the radius`() {
        val network = network()
        val close = origin.offset(4_000.0, 0.0)
        assertTrue(network.reserve("far", close) is ReserveResult.Success)
    }

    @Test
    fun `reservation prefers a free charger and takes it out of the free count`() {
        val network = network()
        val result = network.reserve("free", origin) as ReserveResult.Success
        assertEquals(2, result.reservation.chargerNumber)
        assertEquals(0, result.reservation.waitMinutes)
        assertEquals(0, network.station("free").freeCount)
    }

    @Test
    fun `full station reservation takes the charger that finishes first`() {
        val network = network()
        val station = network.station("full")
        val first = station.chargers.minBy { it.remainingSeconds!! }
        val result = network.reserve("full", origin) as ReserveResult.Success
        assertEquals(first.number, result.reservation.chargerNumber)
        assertEquals(first.remainingMinutes, result.reservation.waitMinutes)
    }

    @Test
    fun `only one reservation at a time`() {
        val network = network()
        network.reserve("free", origin)
        assertEquals(ReserveResult.AlreadyReserved, network.reserve("full", origin))
    }

    @Test
    fun `hold does not run down while the previous car is charging`() {
        val network = network()
        val wait = network.station("full").chargers.minOf { it.remainingSeconds!! }
        network.reserve("full", origin)

        assertFalse(network.advance(wait - 1))
        assertEquals(15, network.reservation()!!.holdMinutes)

        assertFalse(network.advance(1))
        assertEquals(0, network.reservation()!!.waitMinutes)
    }

    @Test
    fun `reservation expires when the hold runs out`() {
        val network = network()
        network.reserve("free", origin)
        assertFalse(network.advance(SimulatedChargingNetwork.HOLD_SECONDS - 1))
        assertNotNull(network.reservation())
        assertTrue(network.advance(1))
        assertNull(network.reservation())
    }

    @Test
    fun `reserved charger is not taken by arriving cars`() {
        val network = SimulatedChargingNetwork(
            origin, listOf(StationPlan("s", "s", 0.0, 0.0, 1, 0)), Random(1), arrivalChancePerMinute = 60.0,
        )
        network.reserve("s", origin)
        network.advance(60)
        assertFalse(network.station("s").chargers[0].isCharging)
    }

    @Test
    fun `cars arrive at unreserved chargers`() {
        val network = SimulatedChargingNetwork(
            origin, listOf(StationPlan("s", "s", 0.0, 0.0, 1, 0)), Random(1), arrivalChancePerMinute = 60.0,
        )
        network.advance(60)
        assertTrue(network.station("s").chargers[0].isCharging)
    }

    @Test
    fun `charging requires arrival and a free charger`() {
        val network = network()
        assertEquals(StartChargingResult.NoReservation, network.startCharging(origin))

        network.reserve("free", origin)
        assertEquals(StartChargingResult.NotArrived, network.startCharging(origin))

        val station = network.station("free")
        assertEquals(StartChargingResult.Started, network.startCharging(station.position))
        assertNull(network.reservation())
        assertEquals(2, network.station("free").chargingCount)
    }

    @Test
    fun `charging waits for the previous car to finish`() {
        val network = network()
        network.reserve("full", origin)
        val station = network.station("full")
        assertEquals(StartChargingResult.ChargerBusy, network.startCharging(station.position))
    }

    @Test
    fun `start time is the later of arrival and the charger becoming free`() {
        val station = com.example.myapplication.model.Station(
            "s", "s", origin,
            listOf(
                // 10분 뒤 끝나지만 다른 사람 예약(30분)이 기다리고 있다 → 40분 뒤 사용 가능
                com.example.myapplication.model.Charger(1, 600, false, othersQueueSeconds = listOf(1_800)),
                // 20분 뒤 끝나고 예약 없음
                com.example.myapplication.model.Charger(2, 1_200, false),
            ),
        )
        assertEquals(1, station.othersReservedCount)
        assertEquals(2, station.nextReservableCharger!!.number)
        assertEquals(1_200, station.startInSeconds(300)) // 5분 만에 도착해도 20분 기다린다
        assertEquals(1_800, station.startInSeconds(1_800)) // 30분 걸리면 도착 즉시
    }

    @Test
    fun `queued reservations charge before me and delay my turn`() {
        val busy = StationPlan("q", "q", 0.0, 0.0, total = 1, occupied = 1, remainingSeconds = listOf(600))
        val network = SimulatedChargingNetwork(
            origin, listOf(busy), Random(1), arrivalChancePerMinute = 0.0, othersReservationChance = 1.0,
        )
        val charger = network.station("q").chargers[0]
        assertTrue(charger.othersReserved >= 1)
        val result = network.reserve("q", origin) as ReserveResult.Success
        assertEquals(charger.availableInMinutes, result.reservation.waitMinutes)

        // 앞 차가 끝나면 다른 사람 예약이 이어서 충전한다
        network.advance(600)
        val after = network.station("q").chargers[0]
        assertTrue(after.isCharging)
        assertEquals(charger.othersQueueSeconds.first(), after.remainingSeconds)
    }

    @Test
    fun `no other reservations unless enabled`() {
        assertEquals(0, network().stations().sumOf { it.othersReservedCount })
    }

    @Test
    fun `live plans use given remaining times and apply status updates`() {
        val live = StationPlan(
            "ev-1", "실시간", 0.0, 300.0, total = 2, occupied = 1,
            chargerIds = listOf("01", "02"), remainingSeconds = listOf(600, null),
        )
        val network = SimulatedChargingNetwork(origin, listOf(live), Random(1), arrivalChancePerMinute = 0.0)
        assertEquals(10, network.station("ev-1").chargers[0].remainingMinutes)
        assertFalse(network.station("ev-1").chargers[1].isCharging)

        network.applyLive(
            listOf(
                SimulatedChargingNetwork.LiveUpdate("ev-1", "01", null),
                SimulatedChargingNetwork.LiveUpdate("ev-1", "02", 1_200),
                SimulatedChargingNetwork.LiveUpdate("ev-1", "99", 60),
                SimulatedChargingNetwork.LiveUpdate("other", "01", 60),
            ),
        )
        assertFalse(network.station("ev-1").chargers[0].isCharging)
        assertEquals(20, network.station("ev-1").chargers[1].remainingMinutes)
    }
}
