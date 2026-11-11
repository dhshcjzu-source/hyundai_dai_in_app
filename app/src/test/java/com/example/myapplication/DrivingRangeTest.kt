package com.example.myapplication

import com.example.myapplication.model.DrivingRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingRangeTest {

    @Test
    fun socEstimateScalesWithPercent() {
        assertEquals(200_000.0, DrivingRange.estimateFromSoc(50), 0.1)
        assertEquals(0.0, DrivingRange.estimateFromSoc(-3), 0.1)
    }

    @Test
    fun sdkValuePicksUnitCloserToSocEstimate() {
        // 50% → 약 200km. 180은 km, 180000은 m로 읽어야 맞다.
        assertEquals(180_000.0, DrivingRange.fromSdkValue(180f, 50)!!, 0.1)
        assertEquals(180_000.0, DrivingRange.fromSdkValue(180_000f, 50)!!, 0.1)
    }

    @Test
    fun sdkValueWithoutSocUsesMagnitude() {
        assertEquals(250_000.0, DrivingRange.fromSdkValue(250f, null)!!, 0.1)
        assertEquals(250_000.0, DrivingRange.fromSdkValue(250_000f, null)!!, 0.1)
        assertNull(DrivingRange.fromSdkValue(0f, 40))
        assertNull(DrivingRange.fromSdkValue(null, 40))
    }

    @Test
    fun usableKeepsReserve() {
        assertEquals(180_000.0, DrivingRange.usableMeters(200_000.0), 0.1)
        assertEquals(15_000.0, DrivingRange.usableMeters(20_000.0), 0.1)
        assertEquals(0.0, DrivingRange.usableMeters(3_000.0), 0.1)
    }

    @Test
    fun reachabilityUsesRouteOrRoadFactor() {
        // 남은 20km → 15km까지. 직선 12km는 도로 15.6km로 추정되어 못 간다.
        assertFalse(DrivingRange.isReachable(12_000.0, null, 20_000.0))
        assertTrue(DrivingRange.isReachable(11_000.0, null, 20_000.0))
        // 실제 경로가 14km면 갈 수 있다.
        assertTrue(DrivingRange.isReachable(12_000.0, 14_000.0, 20_000.0))
    }
}
