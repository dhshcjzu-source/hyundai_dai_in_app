package com.example.myapplication.map

import com.example.myapplication.model.GeoPoint
import org.json.JSONObject

/**
 * 실제 지도 모양. 좌표는 [origin] 기준 동쪽, 북쪽 미터로 미리 바꿔 두어 그릴 때 계산을 줄인다.
 * 도로, 건물, 녹지/물 영역은 OpenStreetMap에서 받는다.
 */
class MapScene(
    val origin: GeoPoint,
    val roads: List<Road>,
    val buildings: List<Building>,
    val areas: List<Area>,
) {
    class Road(val east: FloatArray, val north: FloatArray, val widthMeters: Float, val major: Boolean)

    class Building(val east: FloatArray, val north: FloatArray, val heightMeters: Float, val name: String?) {
        /** 바닥 꼭짓점의 평균(닫힌 다각형의 마지막 중복점 제외). 이름표 위치와 TMAP 이름 조회에 쓴다. */
        val centerEast: Float
        val centerNorth: Float

        /** TMAP 역지오코딩으로 받은 건물 이름. 받기 전이거나 이름이 없으면 null. 메인 스레드에서만 바꾼다. */
        var tmapName: String? = null

        init {
            val closed = east.size > 1 && east.first() == east.last() && north.first() == north.last()
            val count = if (closed) east.size - 1 else east.size
            var sumEast = 0f
            var sumNorth = 0f
            for (i in 0 until count) {
                sumEast += east[i]
                sumNorth += north[i]
            }
            centerEast = sumEast / count
            centerNorth = sumNorth / count
        }
    }

    class Area(val east: FloatArray, val north: FloatArray, val kind: AreaKind)

    enum class AreaKind { GREEN, WATER, PITCH }

    val isEmpty get() = roads.isEmpty() && buildings.isEmpty() && areas.isEmpty()

    companion object {
        private const val LEVEL_METERS = 3.2f
        private const val DEFAULT_BUILDING_METERS = 9f
        private const val MIN_BUILDING_METERS = 4f
        private const val MAX_BUILDING_METERS = 90f

        private val ROAD_WIDTHS = mapOf(
            "motorway" to 22f, "trunk" to 20f, "primary" to 16f, "secondary" to 14f, "tertiary" to 12f,
            "motorway_link" to 9f, "trunk_link" to 9f, "primary_link" to 9f, "secondary_link" to 9f,
            "tertiary_link" to 9f, "unclassified" to 8f, "residential" to 8f, "living_street" to 6f,
            "pedestrian" to 6f, "service" to 5f,
        )
        private val MAJOR_ROADS = setOf("motorway", "trunk", "primary", "secondary", "tertiary")

        /** Overpass API의 `out geom` 응답을 읽는다. 모르는 항목은 건너뛴다. */
        fun parseOverpass(json: String, origin: GeoPoint): MapScene {
            val elements = JSONObject(json).getJSONArray("elements")
            val roads = ArrayList<Road>()
            val buildings = ArrayList<Building>()
            val areas = ArrayList<Area>()
            for (i in 0 until elements.length()) {
                val element = elements.getJSONObject(i)
                if (element.optString("type") != "way") continue
                val tags = element.optJSONObject("tags") ?: continue
                val geometry = element.optJSONArray("geometry") ?: continue
                if (geometry.length() < 2) continue
                val east = FloatArray(geometry.length())
                val north = FloatArray(geometry.length())
                for (j in 0 until geometry.length()) {
                    val node = geometry.getJSONObject(j)
                    val (e, n) = GeoPoint(node.getDouble("lat"), node.getDouble("lon")).metersFrom(origin)
                    east[j] = e.toFloat()
                    north[j] = n.toFloat()
                }
                val highway = tags.optString("highway")
                when {
                    tags.has("building") -> if (geometry.length() >= 3) {
                        buildings += Building(east, north, buildingHeight(tags), tags.optString("name").ifEmpty { null })
                    }

                    highway.isNotEmpty() -> ROAD_WIDTHS[highway]?.let { width ->
                        roads += Road(east, north, width, highway in MAJOR_ROADS)
                    }

                    geometry.length() >= 3 -> areaKind(tags)?.let { areas += Area(east, north, it) }
                }
            }
            // 큰 도로를 작은 도로 위에 그리도록 뒤로 보낸다.
            roads.sortBy { it.widthMeters }
            return MapScene(origin, roads, buildings, areas)
        }

        private fun buildingHeight(tags: JSONObject): Float {
            val height = tags.optString("height").substringBefore(' ').toFloatOrNull()
            val levels = tags.optString("building:levels").toFloatOrNull()
            val meters = height ?: levels?.times(LEVEL_METERS) ?: when (tags.optString("building")) {
                "apartments" -> 45f
                "house", "detached", "residential" -> 7f
                "garage", "shed", "kiosk", "roof" -> 4f
                else -> DEFAULT_BUILDING_METERS
            }
            return meters.coerceIn(MIN_BUILDING_METERS, MAX_BUILDING_METERS)
        }

        private fun areaKind(tags: JSONObject): AreaKind? {
            val natural = tags.optString("natural")
            val leisure = tags.optString("leisure")
            val landuse = tags.optString("landuse")
            return when {
                natural == "water" || landuse == "reservoir" -> AreaKind.WATER
                leisure == "pitch" || leisure == "track" -> AreaKind.PITCH
                natural in setOf("wood", "scrub") || leisure in setOf("park", "garden") ||
                    landuse in setOf("grass", "forest", "meadow", "recreation_ground", "village_green") -> AreaKind.GREEN
                else -> null
            }
        }
    }
}

/** 지도에 표시하는 주변 시설. TMAP 주변 검색에서 받는다. */
data class Facility(val name: String, val kind: Kind, val position: GeoPoint) {
    enum class Kind(val tmapCategory: String, val glyph: String) {
        CONVENIENCE("편의점", "편"),
        CAFE("카페", "카"),
        FOOD("음식점", "식"),
    }
}
