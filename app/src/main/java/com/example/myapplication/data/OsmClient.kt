package com.example.myapplication.data

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.myapplication.map.MapScene
import com.example.myapplication.model.GeoPoint
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

/**
 * OpenStreetMap(Overpass API)에서 주변 도로, 건물, 녹지를 받는다.
 * 공개 서버가 느려서(20~60초) 받은 결과를 기기에 저장해 두고 다시 쓴다.
 */
class OsmClient(private val cacheDir: File) {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainThread = Handler(Looper.getMainLooper())

    /** [onResult]는 메인 스레드에서 호출된다. 실패하면 null을 넘긴다. */
    fun fetch(center: GeoPoint, onResult: (MapScene?) -> Unit) {
        executor.execute {
            val scene = try {
                // 캐시를 같은 칸끼리 나눠 쓰도록 중심을 약 500m 단위로 맞춘다.
                val origin = GeoPoint(snap(center.latitude), snap(center.longitude))
                MapScene.parseOverpass(load(origin), origin).takeUnless { it.isEmpty }
            } catch (e: Exception) {
                Log.w(TAG, "지도 조회 실패", e)
                null
            }
            mainThread.post { onResult(scene) }
        }
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun load(origin: GeoPoint): String {
        val file = File(cacheDir, String.format(Locale.US, "osm_%.3f_%.3f.json", origin.latitude, origin.longitude))
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < CACHE_MS) return file.readText()
        val body = download(query(origin))
        file.writeText(body)
        return body
    }

    private fun download(query: String): String {
        var last: Exception? = null
        for (server in SERVERS) {
            try {
                val connection = URL(server).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.setRequestProperty("User-Agent", USER_AGENT)
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    connection.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
                    check(connection.responseCode == 200) { "HTTP ${connection.responseCode} $server" }
                    return connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "$server 실패, 다음 서버로", e)
                last = e
            }
        }
        throw last ?: IllegalStateException("서버 없음")
    }

    private fun snap(value: Double) = Math.round(value / SNAP_DEGREES) * SNAP_DEGREES

    companion object {
        private const val TAG = "OsmClient"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 90_000
        private const val CACHE_MS = 7L * 24 * 60 * 60 * 1000
        private const val SNAP_DEGREES = 0.005
        private const val ROAD_RADIUS_METERS = 2_200
        private const val BUILDING_RADIUS_METERS = 1_000
        private const val USER_AGENT = "ChargeGO/1.0 (Android in-car demo)"

        /** 응답이 빠른 순서. 앞 서버가 실패하면 다음으로 넘어간다. */
        private val SERVERS = listOf(
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
        )

        fun query(center: GeoPoint): String {
            val around = { radius: Int -> String.format(Locale.US, "(around:%d,%.6f,%.6f)", radius, center.latitude, center.longitude) }
            val roads = around(ROAD_RADIUS_METERS)
            return "[out:json][timeout:60];(" +
                "way[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|service|" +
                "living_street|pedestrian|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$\"]$roads;" +
                "way[\"building\"]${around(BUILDING_RADIUS_METERS)};" +
                "way[\"landuse\"~\"^(grass|forest|meadow|recreation_ground|village_green|reservoir)$\"]$roads;" +
                "way[\"leisure\"~\"^(park|garden|pitch|track)$\"]$roads;" +
                "way[\"natural\"~\"^(water|wood|scrub)$\"]$roads;" +
                ");out geom qt;"
        }
    }
}
