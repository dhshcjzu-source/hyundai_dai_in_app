package com.example.myapplication.data

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.myapplication.map.MapScene
import com.example.myapplication.model.GeoPoint
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 지도 건물 위에 붙일 이름을 SK open API(TMAP) 역지오코딩으로 받는다.
 * 건물 바닥 중심점의 주소를 물어 `buildingName`을 쓴다. 차 주변 건물부터 조금씩 묻고,
 * 받은 결과는 기기에 저장해 같은 건물을 다시 묻지 않는다(API 호출량 절약).
 * [lookupNear]는 메인 스레드에서 부른다.
 */
class BuildingNameClient(private val appKey: String, cacheDir: File) {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainThread = Handler(Looper.getMainLooper())
    private val cacheFile = File(cacheDir, CACHE_FILE)

    /** 좌표 키 → 건물 이름. 빈 문자열은 "이름 없음"으로 확인된 곳. 백그라운드 스레드에서만 만진다. */
    private var cache: HashMap<String, String>? = null

    private var scene: MapScene? = null
    private val requested = HashSet<Int>()
    private var busy = false
    /** 더 물을 건물이 없던 위치. 여기서 [RELOOKUP_METERS] 이상 움직여야 다시 찾는다. */
    private var exhaustedAt: GeoPoint? = null

    val isConfigured get() = appKey.isNotBlank()

    /** [position] 주변에서 아직 이름을 모르는 건물을 가까운 순으로 묻는다. 이름이 붙으면 [onNames]를 부른다. */
    fun lookupNear(next: MapScene, position: GeoPoint, onNames: () -> Unit) {
        if (!isConfigured || busy) return
        if (next !== scene) {
            scene = next
            requested.clear()
            exhaustedAt = null
        }
        exhaustedAt?.let { if (it.distanceTo(position) < RELOOKUP_METERS) return }

        val (carEast, carNorth) = position.metersFrom(next.origin)
        val batch = next.buildings.indices
            .filter { it !in requested }
            .map { index ->
                val building = next.buildings[index]
                index to Math.hypot(building.centerEast - carEast, building.centerNorth - carNorth)
            }
            .filter { it.second < LOOKUP_RADIUS_METERS }
            .sortedBy { it.second }
            .take(BATCH_SIZE)
            .map { it.first }
        if (batch.isEmpty()) {
            exhaustedAt = position
            return
        }
        requested += batch
        busy = true
        val targets = batch.map { index ->
            val building = next.buildings[index]
            index to next.origin.offset(building.centerNorth.toDouble(), building.centerEast.toDouble())
        }
        executor.execute {
            val names = targets.mapNotNull { (index, point) -> resolve(point)?.let { index to it } }
            saveCache()
            mainThread.post {
                busy = false
                if (scene !== next) return@post
                names.forEach { (index, name) -> next.buildings[index].tmapName = name }
                if (names.isNotEmpty()) onNames()
            }
        }
    }

    fun close() {
        executor.shutdownNow()
    }

    /** 캐시에 있으면 그 값을, 없으면 TMAP에 묻는다. 이름이 없거나 실패하면 null. */
    private fun resolve(point: GeoPoint): String? {
        val names = cache ?: loadCache().also { cache = it }
        val key = String.format(Locale.US, "%.5f,%.5f", point.latitude, point.longitude)
        names[key]?.let { return it.ifEmpty { null } }
        val name = try {
            Thread.sleep(REQUEST_GAP_MS)
            fetch(point)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (e: Exception) {
            // 실패는 저장하지 않는다. 다음 장면에서 다시 묻는다.
            Log.w(TAG, "건물 이름 조회 실패", e)
            return null
        }
        names[key] = name.orEmpty()
        return name
    }

    private fun fetch(point: GeoPoint): String? {
        val url = URL(
            String.format(
                Locale.US,
                "https://apis.openapi.sk.com/tmap/geo/reversegeocoding?version=1&lat=%.6f&lon=%.6f" +
                    "&coordType=WGS84GEO&addressType=A04",
                point.latitude,
                point.longitude,
            ),
        )
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("appKey", appKey)
            connection.setRequestProperty("Accept", "application/json")
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val info = JSONObject(body).getJSONObject("addressInfo")
            val name = info.optString("buildingName").trim()
            // 주소가 멀리 떨어진 건물에 붙은 것이면 다른 건물 이름일 수 있어 버린다.
            val distance = info.optString("mappingDistance").toDoubleOrNull() ?: Double.MAX_VALUE
            return name.takeIf { it.isNotEmpty() && distance <= MAX_MAPPING_METERS }
        } finally {
            connection.disconnect()
        }
    }

    private fun loadCache(): HashMap<String, String> {
        val names = HashMap<String, String>()
        try {
            if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < CACHE_MS) {
                val json = JSONObject(cacheFile.readText())
                json.keys().forEach { key -> names[key] = json.getString(key) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "건물 이름 캐시 읽기 실패", e)
        }
        return names
    }

    private fun saveCache() {
        val names = cache ?: return
        try {
            cacheFile.writeText(JSONObject(names as Map<*, *>).toString())
        } catch (e: Exception) {
            Log.w(TAG, "건물 이름 캐시 저장 실패", e)
        }
    }

    companion object {
        private const val TAG = "BuildingNameClient"
        private const val CACHE_FILE = "tmap_building_names.json"
        private const val CACHE_MS = 30L * 24 * 60 * 60 * 1000
        private const val TIMEOUT_MS = 10_000
        /** 한 번에 묻는 건물 수. 다 받으면 다음 묶음을 묻는다. */
        private const val BATCH_SIZE = 20
        /** 차에서 이 거리 안의 건물만 이름을 묻는다. 지도에 이름이 보이는 범위와 맞춘다. */
        private const val LOOKUP_RADIUS_METERS = 450.0
        private const val RELOOKUP_METERS = 60.0
        /** 호출 사이 간격. 짧은 시간에 몰아서 부르면 TMAP이 막는다. */
        private const val REQUEST_GAP_MS = 120L
        private const val MAX_MAPPING_METERS = 40.0
    }
}
