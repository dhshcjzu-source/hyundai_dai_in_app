package com.example.myapplication.data

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.myapplication.map.MapScene
import com.example.myapplication.model.GeoPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 지도 건물 위에 붙일 이름을 SK open API(TMAP) 역지오코딩으로 받는다.
 * 건물 바닥 중심점의 주소를 물어 `buildingName`을 쓴다. 차 주변 건물부터 조금씩 묻고,
 * 받은 결과는 기기에 저장해 같은 건물을 다시 묻지 않는다(API 호출량 절약).
 *
 * 아파트 단지는 역지오코딩이 모든 동에 단지 이름만 준다. 같은 이름이 여러 건물에 붙으면 단지로 보고,
 * 단지 이름으로 TMAP POI를 검색해 "○○아파트 101동" 좌표를 받은 뒤 그 좌표가 들어 있는 건물에 동 이름을 붙인다.
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
    /** 이 장면에서 이미 동을 찾아본 단지 이름. */
    private val dongRequested = HashSet<String>()
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
            dongRequested.clear()
            exhaustedAt = null
        }
        if (lookupDongs(next, onNames)) return
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

    /**
     * 같은 이름이 [MIN_COMPLEX_BUILDINGS]채 이상인 단지 하나의 동 위치를 받아 건물에 붙인다.
     * 물어볼 단지가 있어 일을 시작했으면 true.
     */
    private fun lookupDongs(next: MapScene, onNames: () -> Unit): Boolean {
        val complex = next.buildings.mapNotNull { it.tmapName }
            .groupingBy { it }.eachCount()
            .filter { (name, count) -> count >= MIN_COMPLEX_BUILDINGS && name !in dongRequested }
            .keys.firstOrNull() ?: return false
        dongRequested += complex
        val members = next.buildings.filter { it.tmapName == complex }
        val center = next.origin.offset(
            members.map { it.centerNorth.toDouble() }.average(),
            members.map { it.centerEast.toDouble() }.average(),
        )
        busy = true
        executor.execute {
            val dongs = resolveDongs(complex, center)
            saveCache()
            mainThread.post {
                busy = false
                if (scene !== next) return@post
                if (assignDongs(next, complex, dongs) > 0) onNames()
            }
        }
        return true
    }

    /** 캐시에 있으면 그 값을, 없으면 TMAP POI 검색으로 단지의 동 위치를 받는다. */
    private fun resolveDongs(complex: String, center: GeoPoint): List<Dong> {
        val names = cache ?: loadCache().also { cache = it }
        val key = "dong|$complex"
        names[key]?.let { return parseDongCache(it) }
        val dongs = try {
            Thread.sleep(REQUEST_GAP_MS)
            fetchDongs(complex, center)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "아파트 동 조회 실패: $complex", e)
            return emptyList()
        }
        names[key] = JSONArray().apply {
            dongs.forEach { put(JSONArray().put(it.position.latitude).put(it.position.longitude).put(it.dong)) }
        }.toString()
        return dongs
    }

    private fun fetchDongs(complex: String, center: GeoPoint): List<Dong> {
        val url = URL(
            String.format(
                Locale.US,
                "https://apis.openapi.sk.com/tmap/pois?version=1&searchKeyword=%s&count=%d" +
                    "&centerLat=%.6f&centerLon=%.6f&radius=1&searchtypCd=R&reqCoordType=WGS84GEO&resCoordType=WGS84GEO",
                URLEncoder.encode(complex, "UTF-8"),
                POI_COUNT,
                center.latitude,
                center.longitude,
            ),
        )
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("appKey", appKey)
            connection.setRequestProperty("Accept", "application/json")
            // 결과가 없으면 204로 빈 응답이 온다.
            if (connection.responseCode == 204) return emptyList()
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return parseDongPois(body, center)
        } finally {
            connection.disconnect()
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

    /** 아파트 동 하나의 위치. [dong]은 "101동"처럼 동 번호만 담는다. */
    data class Dong(val position: GeoPoint, val dong: String)

    companion object {
        private const val TAG = "BuildingNameClient"
        /** 같은 이름이 이만큼 이상 붙은 건물 무리를 아파트 단지로 본다. */
        private const val MIN_COMPLEX_BUILDINGS = 2
        private const val POI_COUNT = 50
        /** 단지 중심에서 이보다 먼 동 POI는 이름이 비슷한 다른 단지일 수 있어 버린다. */
        private const val MAX_DONG_FROM_CENTER_METERS = 500.0
        /** 동 POI가 어느 건물 바닥에도 들어가지 않을 때 가장 가까운 건물 중심까지 허용하는 거리. */
        private const val DONG_SNAP_METERS = 25.0
        private val DONG_SUFFIX = Regex("""(\d{1,4}동)$""")

        /** TMAP POI 검색 응답에서 "…101동"으로 끝나는 POI만 골라 동 위치로 만든다. 건물 중심 좌표(noor)를 쓴다. */
        fun parseDongPois(json: String, center: GeoPoint): List<Dong> {
            val pois = JSONObject(json).optJSONObject("searchPoiInfo")
                ?.optJSONObject("pois")?.optJSONArray("poi") ?: return emptyList()
            val dongs = LinkedHashMap<String, Dong>()
            for (i in 0 until pois.length()) {
                val poi = pois.getJSONObject(i)
                val dong = DONG_SUFFIX.find(poi.optString("name").trim())?.groupValues?.get(1) ?: continue
                val lat = poi.optString("noorLat").toDoubleOrNull() ?: continue
                val lon = poi.optString("noorLon").toDoubleOrNull() ?: continue
                val position = GeoPoint(lat, lon)
                if (position.distanceTo(center) > MAX_DONG_FROM_CENTER_METERS) continue
                dongs.putIfAbsent(dong, Dong(position, dong))
            }
            return dongs.values.toList()
        }

        private fun parseDongCache(value: String): List<Dong> = try {
            val array = JSONArray(value)
            (0 until array.length()).map { i ->
                val item = array.getJSONArray(i)
                Dong(GeoPoint(item.getDouble(0), item.getDouble(1)), item.getString(2))
            }
        } catch (e: Exception) {
            emptyList()
        }

        /**
         * 동 위치가 들어 있는 건물에 "단지 이름 + 동"을 붙인다. 다른 이름이 붙은 건물(옆 단지, 상가)에는 붙이지 않는다.
         * 어느 바닥에도 안 들어가면 [DONG_SNAP_METERS] 안의 가장 가까운 건물에 붙인다. 붙인 건물 수를 돌려준다.
         */
        fun assignDongs(scene: MapScene, complex: String, dongs: List<Dong>): Int {
            val candidates = scene.buildings.filter { it.dongName == null && (it.tmapName == null || it.tmapName == complex) }
            var assigned = 0
            for (dong in dongs) {
                val (east, north) = dong.position.metersFrom(scene.origin)
                val e = east.toFloat()
                val n = north.toFloat()
                val building = candidates.firstOrNull { it.dongName == null && it.contains(e, n) }
                    ?: candidates.filter { it.dongName == null }
                        .minByOrNull { Math.hypot((it.centerEast - e).toDouble(), (it.centerNorth - n).toDouble()) }
                        ?.takeIf { Math.hypot((it.centerEast - e).toDouble(), (it.centerNorth - n).toDouble()) <= DONG_SNAP_METERS }
                    ?: continue
                building.dongName = "$complex ${dong.dong}"
                assigned++
            }
            return assigned
        }
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
