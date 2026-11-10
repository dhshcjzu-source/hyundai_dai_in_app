package com.example.myapplication

import com.example.myapplication.data.TmapRouteClient
import com.example.myapplication.model.GeoPoint
import com.example.myapplication.nav.NavRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavRouteTest {
    private val start = GeoPoint(35.9, 128.85)

    // 북쪽으로 200m 간 뒤 동쪽(오른쪽)으로 100m 가는 길
    private val corner = start.offset(200.0, 0.0)
    private val end = corner.offset(0.0, 100.0)
    private val route = NavRoute.of(
        listOf(start, corner, end),
        listOf(
            NavRoute.RawGuidance(1, "교차로에서 우회전", "대구대로", false),
            NavRoute.RawGuidance(2, "목적지 도착", "", true),
        ),
        totalSeconds = 60,
    )

    @Test
    fun `measures length and moves along the road`() {
        assertEquals(300.0, route.totalMeters, 1.0)
        assertEquals(100.0, start.distanceTo(route.positionAt(100.0)), 1.0)
        assertEquals(50.0, corner.distanceTo(route.positionAt(250.0)), 1.0)
        assertEquals(0.0, end.distanceTo(route.positionAt(999.0)), 0.5)
    }

    @Test
    fun `heading follows each segment`() {
        assertEquals(0f, route.headingAt(10.0), 1f)
        assertEquals(90f, route.headingAt(260.0), 1f)
    }

    @Test
    fun `next guidance and turn direction`() {
        val first = route.nextGuidance(0.0)!!
        assertEquals("교차로에서 우회전", first.instruction)
        assertEquals(200.0, first.distance, 1.0)
        assertEquals(90f, first.turnDegrees, 2f)
        assertTrue(route.nextGuidance(250.0)!!.arrival)
        assertNull(route.nextGuidance(300.0))
    }

    @Test
    fun `parses tmap route features`() {
        val json = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[128.85,35.9]},
               "properties":{"totalDistance":300,"totalTime":75,"description":"출발","pointType":"S","turnType":200}},
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[[128.85,35.9],[128.85,35.9018]]},
               "properties":{"distance":200}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[128.85,35.9018]},
               "properties":{"description":"교차로에서 좌회전 후 대구대로를 따라 100m 이동","nextRoadName":"대구대로","pointType":"GP","turnType":12}},
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[[128.85,35.9018],[128.8489,35.9018]]},
               "properties":{"distance":100}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[128.8489,35.9018]},
               "properties":{"description":"도착","pointType":"E","turnType":201}}
            ]}
        """.trimIndent()
        val parsed = TmapRouteClient.parseRoute(json)
        assertEquals(3, parsed.points.size)
        assertEquals(75, parsed.totalSeconds)
        assertEquals(2, parsed.guidance.size)
        val turn = parsed.guidance[0]
        assertEquals("교차로에서 좌회전", turn.instruction)
        assertEquals("대구대로", turn.road)
        assertEquals(-90f, turn.turnDegrees, 3f)
        assertTrue(parsed.guidance[1].arrival)
    }

    @Test
    fun `parses live traffic congestion per segment`() {
        // 첫 선: 원활(0→1), 정체(1→2). 둘째 선: 서행. 셋째 선: 교통 정보 없음.
        val json = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[128.85,35.9]},
               "properties":{"totalTime":300,"pointType":"S"}},
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[[128.85,35.9],[128.85,35.901],[128.85,35.902]]},
               "properties":{"traffic":[[0,1,1,50],[1,2,4,5]]}},
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[[128.85,35.902],[128.851,35.902]]},
               "properties":{"traffic":[[0,1,2,20]]}},
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[[128.851,35.902],[128.852,35.902]]},
               "properties":{}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[128.852,35.902]},
               "properties":{"pointType":"E"}}
            ]}
        """.trimIndent()
        val parsed = TmapRouteClient.parseRoute(json)
        assertEquals(300, parsed.totalSeconds)
        assertEquals(
            listOf(NavRoute.CONGESTION_SMOOTH, NavRoute.CONGESTION_JAM, NavRoute.CONGESTION_SLOW, NavRoute.CONGESTION_UNKNOWN),
            parsed.congestion.toList(),
        )
    }

    @Test
    fun `derives congestion from live segment speed when traffic is missing`() {
        fun level(roadType: Int, meters: Int, seconds: Int) = TmapRouteClient.congestionFromSpeed(
            org.json.JSONObject().put("roadType", roadType).put("distance", meters).put("time", seconds),
        )
        assertEquals(NavRoute.CONGESTION_SMOOTH, level(5, 1000, 100)) // 36km/h
        assertEquals(NavRoute.CONGESTION_SLOW, level(5, 1000, 180)) // 20km/h
        assertEquals(NavRoute.CONGESTION_JAM, level(5, 1000, 300)) // 12km/h
        assertEquals(NavRoute.CONGESTION_SLOW, level(0, 1000, 72)) // 고속도로 50km/h
        assertEquals(NavRoute.CONGESTION_UNKNOWN, level(5, 50, 30)) // 짧은 구간은 판정 안 함
    }

    @Test
    fun `falls back to a straight line`() {
        val straight = NavRoute.straight(start, corner)
        assertEquals(200.0, straight.totalMeters, 1.0)
        assertTrue(straight.nextGuidance(0.0)!!.arrival)
    }
}
