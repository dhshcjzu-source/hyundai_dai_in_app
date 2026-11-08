package com.example.myapplication

import com.example.myapplication.map.MapScene
import com.example.myapplication.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapSceneTest {
    private val origin = GeoPoint(35.9, 128.85)

    private fun node(north: Double, east: Double): String {
        val p = origin.offset(north, east)
        return """{"lat":${p.latitude},"lon":${p.longitude}}"""
    }

    @Test
    fun `reads roads, buildings and areas`() {
        val json = """
            {"elements":[
              {"type":"way","id":1,"tags":{"highway":"service"},"geometry":[${node(0.0, 0.0)},${node(100.0, 0.0)}]},
              {"type":"way","id":2,"tags":{"highway":"tertiary","name":"대구대로"},"geometry":[${node(0.0, 0.0)},${node(0.0, 200.0)}]},
              {"type":"way","id":3,"tags":{"highway":"footway"},"geometry":[${node(0.0, 0.0)},${node(10.0, 0.0)}]},
              {"type":"way","id":4,"tags":{"building":"yes","building:levels":"17","name":"본관"},
               "geometry":[${node(0.0, 0.0)},${node(0.0, 30.0)},${node(30.0, 30.0)},${node(0.0, 0.0)}]},
              {"type":"way","id":5,"tags":{"building":"house"},
               "geometry":[${node(50.0, 0.0)},${node(50.0, 10.0)},${node(60.0, 10.0)},${node(50.0, 0.0)}]},
              {"type":"way","id":6,"tags":{"natural":"water"},
               "geometry":[${node(0.0, 0.0)},${node(0.0, 50.0)},${node(50.0, 50.0)},${node(0.0, 0.0)}]},
              {"type":"node","id":7,"lat":35.9,"lon":128.85}
            ]}
        """.trimIndent()
        val scene = MapScene.parseOverpass(json, origin)

        // 보행로는 그리지 않고, 좁은 도로부터 넓은 도로 순서로 정렬된다.
        assertEquals(listOf(5f, 12f), scene.roads.map { it.widthMeters })
        assertTrue(scene.roads[1].major)
        assertEquals(200f, scene.roads[1].east[1], 1f)
        assertEquals(100f, scene.roads[0].north[1], 1f)

        assertEquals(2, scene.buildings.size)
        assertEquals(17 * 3.2f, scene.buildings[0].heightMeters, 0.01f)
        assertEquals("본관", scene.buildings[0].name)
        assertEquals(7f, scene.buildings[1].heightMeters, 0.01f)
        assertNull(scene.buildings[1].name)

        assertEquals(MapScene.AreaKind.WATER, scene.areas.single().kind)
    }
}
