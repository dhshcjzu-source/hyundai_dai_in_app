package com.example.myapplication

import com.example.myapplication.data.BuildingNameClient
import com.example.myapplication.data.BuildingNameClient.Dong
import com.example.myapplication.map.MapScene
import com.example.myapplication.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildingDongTest {
    private val origin = GeoPoint(35.8388, 128.7670)

    /** (동쪽, 북쪽) 중심에 가로세로 20m 사각형 건물. */
    private fun square(east: Float, north: Float, name: String?) = MapScene.Building(
        floatArrayOf(east - 10, east + 10, east + 10, east - 10, east - 10),
        floatArrayOf(north - 10, north - 10, north + 10, north + 10, north - 10),
        30f,
        null,
    ).apply { tmapName = name }

    @Test
    fun `point in polygon`() {
        val building = square(0f, 0f, null)
        assertTrue(building.contains(3f, -4f))
        assertFalse(building.contains(15f, 0f))
    }

    @Test
    fun `parses only dong pois near the complex`() {
        val far = origin.offset(2_000.0, 0.0)
        val json = """
            {"searchPoiInfo":{"pois":{"poi":[
              {"name":"경산푸르지오아파트 101동","noorLat":"${origin.latitude}","noorLon":"${origin.longitude}"},
              {"name":"경산푸르지오아파트 101동","noorLat":"${origin.latitude}","noorLon":"${origin.longitude}"},
              {"name":"경산푸르지오아파트 관리사무소","noorLat":"${origin.latitude}","noorLon":"${origin.longitude}"},
              {"name":"다른푸르지오 102동","noorLat":"${far.latitude}","noorLon":"${far.longitude}"}
            ]}}}
        """.trimIndent()
        val dongs = BuildingNameClient.parseDongPois(json, origin)
        assertEquals(listOf("101동"), dongs.map { it.dong })
    }

    @Test
    fun `assigns dong to the building that contains it and skips other complexes`() {
        val a = square(0f, 0f, "경산푸르지오")
        val b = square(50f, 0f, "경산푸르지오")
        val other = square(100f, 0f, "옆단지")
        val scene = MapScene(origin, emptyList(), listOf(a, b, other), emptyList())
        val dongs = listOf(
            Dong(origin.offset(2.0, 48.0), "102동"), // b 안
            Dong(origin.offset(0.0, 1.0), "101동"), // a 안
            Dong(origin.offset(0.0, 100.0), "103동"), // 옆 단지 건물 안 → 붙이지 않음
        )
        assertEquals(2, BuildingNameClient.assignDongs(scene, "경산푸르지오", dongs))
        assertEquals("경산푸르지오 101동", a.label)
        assertEquals("경산푸르지오 102동", b.label)
        assertNull(other.dongName)
        assertEquals("옆단지", other.label)
    }
}
