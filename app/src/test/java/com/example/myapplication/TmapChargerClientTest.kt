package com.example.myapplication

import com.example.myapplication.data.TmapChargerClient
import com.example.myapplication.data.TmapChargerClient.Candidate
import com.example.myapplication.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class TmapChargerClientTest {
    private val center = GeoPoint(37.5663, 126.9779)

    private fun candidate(id: String, north: Double, east: Double, total: Int = 2, charging: Int = 1) =
        Candidate(id, id, center.offset(north, east), total, charging)

    @Test
    fun `picks nearest stations but skips ones crowded together`() {
        val plans = TmapChargerClient.pickSpread(
            listOf(
                candidate("far", 3_000.0, 0.0),
                candidate("near", 100.0, 0.0),
                candidate("sameBuilding", 150.0, 50.0),
                candidate("west", 0.0, -900.0),
            ),
            center,
        )
        assertEquals(listOf("near", "west", "far"), plans.map { it.id })
        assertEquals(100.0, plans[0].northMeters, 1.0)
        assertEquals(-900.0, plans[1].eastMeters, 1.0)
    }

    @Test
    fun `keeps at most six stations`() {
        val many = (1..10).map { candidate("s$it", it * 1_000.0, 0.0) }
        assertEquals(6, TmapChargerClient.pickSpread(many, center).size)
    }

    @Test
    fun `drops the common suffix from station names`() {
        assertEquals("서울신문사급속", TmapChargerClient.shortName("서울신문사급속 전기차충전소"))
        assertEquals("한국프레스센터", TmapChargerClient.shortName("한국프레스센터 전기차충전소"))
        assertEquals("전기차충전소", TmapChargerClient.shortName("전기차충전소"))
    }
}
