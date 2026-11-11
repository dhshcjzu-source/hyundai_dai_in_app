package com.example.myapplication.data

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.myapplication.data.SimulatedChargingNetwork.StationPlan
import com.example.myapplication.map.Facility
import com.example.myapplication.model.ChargerSpeed
import com.example.myapplication.model.GeoPoint
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

/**
 * SK open API(TMAP)의 주변 POI 검색으로 실제 전기차 충전소를 받는다.
 * 이름, 위치, 충전기 수, 충전 중인 대수만 쓰고 이후 현황과 예약은 시뮬레이터가 이어서 돌린다.
 */
class TmapChargerClient(private val appKey: String) {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainThread = Handler(Looper.getMainLooper())

    val isConfigured get() = appKey.isNotBlank()

    /** [onResult]는 메인 스레드에서 호출된다. 실패하거나 결과가 없으면 null을 넘긴다. */
    fun fetch(center: GeoPoint, onResult: (List<StationPlan>?) -> Unit) {
        executor.execute {
            val plans = try {
                pickSpread(parse(download(center)), center).takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                Log.w(TAG, "충전소 조회 실패", e)
                null
            }
            mainThread.post { onResult(plans) }
        }
    }

    /** 위치의 10자리 법정동 코드. 백그라운드 스레드에서 부른다. 실패하면 null. */
    fun regionCode(position: GeoPoint): String? = try {
        val url = URL(
            String.format(
                Locale.US,
                "https://apis.openapi.sk.com/tmap/geo/reversegeocoding?version=1&lat=%.6f&lon=%.6f" +
                    "&coordType=WGS84GEO&addressType=A10",
                position.latitude,
                position.longitude,
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
            JSONObject(body).getJSONObject("addressInfo").getString("legalDongCode").takeIf { it.length >= 5 }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "지역 코드 조회 실패", e)
        null
    }

    /** 주변 편의점, 카페, 음식점. 종류마다 가까운 곳 몇 개만 남긴다. [onResult]는 메인 스레드에서 호출된다. */
    fun fetchFacilities(center: GeoPoint, onResult: (List<Facility>?) -> Unit) {
        executor.execute {
            val facilities = try {
                Facility.Kind.entries.flatMap { kind -> searchFacilities(center, kind) }
            } catch (e: Exception) {
                Log.w(TAG, "시설 조회 실패", e)
                null
            }
            mainThread.post { onResult(facilities) }
        }
    }

    private fun searchFacilities(center: GeoPoint, kind: Facility.Kind): List<Facility> {
        val url = URL(
            String.format(
                Locale.US,
                "https://apis.openapi.sk.com/tmap/pois/search/around?version=1&categories=%s" +
                    "&centerLat=%.6f&centerLon=%.6f&radius=1&count=%d&reqCoordType=WGS84GEO&resCoordType=WGS84GEO",
                URLEncoder.encode(kind.tmapCategory, "UTF-8"),
                center.latitude,
                center.longitude,
                FACILITIES_PER_KIND,
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
            if (body.isBlank()) return emptyList()
            val pois = JSONObject(body).getJSONObject("searchPoiInfo").getJSONObject("pois").getJSONArray("poi")
            return (0 until pois.length()).map { index ->
                val poi = pois.getJSONObject(index)
                Facility(
                    name = poi.getString("name"),
                    kind = kind,
                    position = GeoPoint(poi.getString("noorLat").toDouble(), poi.getString("noorLon").toDouble()),
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun download(center: GeoPoint): String {
        val url = URL(
            String.format(
                Locale.US,
                "https://apis.openapi.sk.com/tmap/pois/search/around?version=1&categories=%s" +
                    "&centerLat=%.6f&centerLon=%.6f&radius=%d&count=%d" +
                    "&reqCoordType=WGS84GEO&resCoordType=WGS84GEO",
                URLEncoder.encode("EV충전소", "UTF-8"),
                center.latitude,
                center.longitude,
                SEARCH_RADIUS_KM,
                SEARCH_COUNT,
            ),
        )
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("appKey", appKey)
            connection.setRequestProperty("Accept", "application/json")
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(json: String): List<Candidate> {
        val pois = JSONObject(json).getJSONObject("searchPoiInfo").getJSONObject("pois").getJSONArray("poi")
        return (0 until pois.length()).mapNotNull { index ->
            val poi = pois.getJSONObject(index)
            val chargers = poi.optJSONObject("evChargers")?.optJSONArray("evCharger") ?: return@mapNotNull null
            // 충전대기, 충전중만 센다. 운영중지, 점검중, 통신이상, 상태미확인은 빈자리인지 알 수 없다.
            // 충전 중인 자리를 앞에 둔다. 시뮬레이터가 앞쪽 [Candidate.charging]대를 충전 중으로 채우기 때문이다.
            val usable = (0 until chargers.length()).map { chargers.getJSONObject(it) }
                .filter { it.optString("status") == STATUS_FREE || it.optString("status") == STATUS_CHARGING }
                .sortedBy { if (it.optString("status") == STATUS_CHARGING) 0 else 1 }
            if (usable.isEmpty()) return@mapNotNull null
            Candidate(
                id = "tmap-" + poi.getString("id"),
                name = shortName(poi.getString("name")),
                position = GeoPoint(poi.getString("noorLat").toDouble(), poi.getString("noorLon").toDouble()),
                total = usable.size,
                charging = usable.count { it.optString("status") == STATUS_CHARGING },
                speeds = usable.map(::speedOf),
            )
        }
    }

    data class Candidate(
        val id: String,
        val name: String,
        val position: GeoPoint,
        val total: Int,
        val charging: Int,
        /** 실시간 상태를 이어 붙일 때 쓰는 충전기 ID. 없으면 비워 둔다. */
        val chargerIds: List<String> = emptyList(),
        /** 충전기별 남은 시간(초). null이면 비어 있음. 목록 자체가 없으면 시뮬레이터가 정한다. */
        val remainingSeconds: List<Int?>? = null,
        /** 충전기별 급속/완속. 목록이 없으면 알 수 없음. */
        val speeds: List<ChargerSpeed?>? = null,
    )

    companion object {
        private const val TAG = "TmapChargerClient"
        private const val TIMEOUT_MS = 10_000
        private const val SEARCH_RADIUS_KM = 5
        private const val SEARCH_COUNT = 200
        private const val MAX_STATIONS = 6
        private const val FACILITIES_PER_KIND = 8
        private const val MIN_SPACING_METERS = 800.0

        // 환경부 충전기 상태 코드
        private const val STATUS_FREE = "2"
        private const val STATUS_CHARGING = "3"

        /** "서울신문사급속 전기차충전소"처럼 모두 붙는 꼬리말을 떼어 화면에서 짧게 보이게 한다. */
        /**
         * TMAP evCharger 한 대의 급속/완속. isFast(Y/N)를 먼저 보고,
         * 없으면 powerType 문구, 그다음 환경부 충전기 타입 코드(type)로 판단한다.
         */
        fun speedOf(charger: JSONObject): ChargerSpeed? {
            when (charger.optString("isFast").uppercase(Locale.US)) {
                "Y" -> return ChargerSpeed.FAST
                "N" -> return ChargerSpeed.SLOW
            }
            val power = charger.optString("powerType")
            if ("급속" in power) return ChargerSpeed.FAST
            if ("완속" in power) return ChargerSpeed.SLOW
            return EvChargerClient.speedOf(charger.optString("type"), null)
        }

        fun shortName(name: String): String =
            name.replace(Regex("\\s*전기차\\s*충전소$"), "").trim().ifEmpty { name }

        /**
         * 가까운 순으로 고르되 서로 [MIN_SPACING_METERS] 이상 떨어진 곳만 남긴다.
         * 한 건물에 여러 충전소가 몰려 있으면 지도 표식이 겹치기 때문이다.
         */
        fun pickSpread(candidates: List<Candidate>, center: GeoPoint): List<StationPlan> {
            val picked = ArrayList<Candidate>()
            for (candidate in candidates.sortedBy { center.distanceTo(it.position) }) {
                if (picked.size == MAX_STATIONS) break
                if (picked.any { it.position.distanceTo(candidate.position) < MIN_SPACING_METERS }) continue
                picked += candidate
            }
            return picked.map { candidate ->
                val (east, north) = candidate.position.metersFrom(center)
                StationPlan(
                    candidate.id, candidate.name, north, east, candidate.total, candidate.charging,
                    candidate.chargerIds, candidate.remainingSeconds, candidate.speeds,
                )
            }
        }
    }
}
