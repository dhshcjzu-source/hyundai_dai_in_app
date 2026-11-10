package com.example.myapplication.data

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.myapplication.model.GeoPoint
import com.example.myapplication.nav.NavRoute
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** TMAP 자동차 경로 안내. 실제 도로를 따라가는 경로와 회전 안내를 받는다. */
class TmapRouteClient(private val appKey: String) {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainThread = Handler(Looper.getMainLooper())

    /** [onResult]는 메인 스레드에서 호출된다. 키가 없거나 실패하면 null을 넘긴다. */
    fun fetch(start: GeoPoint, end: GeoPoint, onResult: (NavRoute?) -> Unit) {
        if (appKey.isBlank()) {
            mainThread.post { onResult(null) }
            return
        }
        executor.execute {
            val route = try {
                parseRoute(download(start, end))
            } catch (e: Exception) {
                Log.w(TAG, "경로 조회 실패", e)
                null
            }
            mainThread.post { onResult(route) }
        }
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun download(start: GeoPoint, end: GeoPoint): String {
        val body = JSONObject()
            .put("startX", start.longitude.toString())
            .put("startY", start.latitude.toString())
            .put("endX", end.longitude.toString())
            .put("endY", end.latitude.toString())
            .put("reqCoordType", "WGS84GEO")
            .put("resCoordType", "WGS84GEO")
            // 0: 교통최적+추천. trafficInfo=Y면 구간별 실시간 혼잡도가 함께 온다.
            .put("searchOption", "0")
            .put("trafficInfo", "Y")
            .toString()
        val connection = URL("https://apis.openapi.sk.com/tmap/routes?version=1").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("appKey", appKey)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "TmapRouteClient"
        private const val TIMEOUT_MS = 10_000
        private const val START_POINT = "S"
        private const val END_POINT = "E"
        private const val MIN_SPEED_SEGMENT_METERS = 150.0
        /** TMAP roadType. 0 고속국도, 1 도시고속화도로, 2 국도 … 7 주요도로3, 8 이상은 골목길 등 기타도로. */
        private const val ROAD_HIGHWAY = 0
        private const val ROAD_URBAN_HIGHWAY = 1
        private const val ROAD_NATIONAL = 2
        private const val ROAD_MAJOR_3 = 7

        /** GeoJSON 형태의 응답에서 점은 안내로, 선은 경로 모양으로 쓴다. */
        fun parseRoute(json: String): NavRoute {
            val features = JSONObject(json).getJSONArray("features")
            val points = ArrayList<GeoPoint>()
            val raw = ArrayList<NavRoute.RawGuidance>()
            val congestion = ArrayList<Int>()
            var totalSeconds = 0

            /** 점을 붙인다. 새 점이 생기면 그 앞 구간의 혼잡도로 [level]을 기록한다. */
            fun add(coordinate: JSONArray, level: Int) {
                val point = GeoPoint(coordinate.getDouble(1), coordinate.getDouble(0))
                if (points.lastOrNull() == point) return
                points += point
                if (points.size >= 2) congestion += level
            }

            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val geometry = feature.getJSONObject("geometry")
                val properties = feature.getJSONObject("properties")
                if (properties.has("totalTime")) totalSeconds = properties.getInt("totalTime")
                val coordinates = geometry.getJSONArray("coordinates")
                when (geometry.getString("type")) {
                    "Point" -> {
                        add(coordinates, NavRoute.CONGESTION_UNKNOWN)
                        val pointType = properties.optString("pointType")
                        if (pointType == START_POINT) continue
                        val arrival = pointType == END_POINT || properties.optInt("turnType") == 201
                        raw += NavRoute.RawGuidance(
                            vertex = points.size - 1,
                            instruction = if (arrival) "목적지 도착" else shortInstruction(properties.optString("description")),
                            road = properties.optString("nextRoadName").takeUnless { it == "일반도로" }.orEmpty(),
                            arrival = arrival,
                        )
                    }

                    "LineString" -> {
                        val levels = lineCongestion(properties, coordinates.length())
                        for (j in 0 until coordinates.length()) {
                            add(coordinates.getJSONArray(j), if (j == 0) NavRoute.CONGESTION_UNKNOWN else levels[j - 1])
                        }
                    }
                }
            }
            return NavRoute.of(points, raw, totalSeconds, congestion.toIntArray())
        }

        /**
         * 선 하나의 구간별 혼잡도.
         * TMAP이 "traffic"([시작 좌표 번호, 끝 좌표 번호, 혼잡도, 속도] 목록)을 주면 그대로 쓴다.
         * 주지 않으면(앱 키 요금제에 따라 빠진다) 실시간 교통이 반영된 "time"과 "distance"로 속도를 구해 판정한다.
         */
        private fun lineCongestion(properties: JSONObject, coordinateCount: Int): IntArray {
            val levels = IntArray(maxOf(0, coordinateCount - 1))
            val traffic = properties.optJSONArray("traffic")
            if (traffic == null) {
                levels.fill(congestionFromSpeed(properties))
                return levels
            }
            for (k in 0 until traffic.length()) {
                val item = traffic.optJSONArray(k) ?: continue
                if (item.length() < 3) continue
                val level = item.optInt(2).coerceIn(NavRoute.CONGESTION_UNKNOWN, NavRoute.CONGESTION_JAM)
                for (s in item.optInt(0).coerceAtLeast(0) until item.optInt(1).coerceAtMost(levels.size)) levels[s] = level
            }
            return levels
        }

        /**
         * 서울시 교통정보(TOPIS) 소통 기준으로 구간 속도를 혼잡도로 바꾼다.
         * 도시고속도로·고속도로는 30/70km/h, 일반도로는 15/25km/h, 골목길은 8/15km/h를 경계로 쓴다.
         * 짧은 구간은 신호·회전 대기 때문에 속도가 들쭉날쭉하므로 판정하지 않는다.
         */
        fun congestionFromSpeed(properties: JSONObject): Int {
            val meters = properties.optDouble("distance", 0.0)
            val seconds = properties.optDouble("time", 0.0)
            if (meters < MIN_SPEED_SEGMENT_METERS || seconds <= 0.0) return NavRoute.CONGESTION_UNKNOWN
            val kmh = meters / seconds * 3.6
            val (jam, slow) = when (properties.optInt("roadType", -1)) {
                ROAD_HIGHWAY, ROAD_URBAN_HIGHWAY -> 30.0 to 70.0
                in ROAD_NATIONAL..ROAD_MAJOR_3 -> 15.0 to 25.0
                else -> 8.0 to 15.0
            }
            return when {
                kmh < jam -> NavRoute.CONGESTION_JAM
                kmh < slow -> NavRoute.CONGESTION_SLOW
                else -> NavRoute.CONGESTION_SMOOTH
            }
        }

        /** "교차로에서 좌회전 후 대구대로를 따라 320m 이동"에서 앞부분만 남긴다. */
        fun shortInstruction(description: String): String =
            description.substringBefore(" 후 ").trim().ifEmpty { "직진" }
    }
}
