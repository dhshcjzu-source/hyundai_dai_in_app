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
            .put("searchOption", "0")
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

        /** GeoJSON 형태의 응답에서 점은 안내로, 선은 경로 모양으로 쓴다. */
        fun parseRoute(json: String): NavRoute {
            val features = JSONObject(json).getJSONArray("features")
            val points = ArrayList<GeoPoint>()
            val raw = ArrayList<NavRoute.RawGuidance>()
            var totalSeconds = 0

            fun add(coordinate: JSONArray) {
                val point = GeoPoint(coordinate.getDouble(1), coordinate.getDouble(0))
                if (points.lastOrNull() != point) points += point
            }

            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val geometry = feature.getJSONObject("geometry")
                val properties = feature.getJSONObject("properties")
                if (properties.has("totalTime")) totalSeconds = properties.getInt("totalTime")
                val coordinates = geometry.getJSONArray("coordinates")
                when (geometry.getString("type")) {
                    "Point" -> {
                        add(coordinates)
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

                    "LineString" -> for (j in 0 until coordinates.length()) add(coordinates.getJSONArray(j))
                }
            }
            return NavRoute.of(points, raw, totalSeconds)
        }

        /** "교차로에서 좌회전 후 대구대로를 따라 320m 이동"에서 앞부분만 남긴다. */
        fun shortInstruction(description: String): String =
            description.substringBefore(" 후 ").trim().ifEmpty { "직진" }
    }
}
