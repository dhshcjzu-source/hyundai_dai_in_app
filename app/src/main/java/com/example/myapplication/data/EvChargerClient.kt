package com.example.myapplication.data

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.myapplication.data.SimulatedChargingNetwork.LiveUpdate
import com.example.myapplication.data.SimulatedChargingNetwork.StationPlan
import com.example.myapplication.data.TmapChargerClient.Candidate
import com.example.myapplication.model.GeoPoint
import org.w3c.dom.Element
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 공공데이터포털 한국환경공단 전기자동차 충전소 정보(B552584/EvCharger).
 * 위치 반경 검색이 없어 시군구 코드로 받은 뒤 가까운 충전소를 고른다.
 */
class EvChargerClient(private val serviceKey: String) {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainThread = Handler(Looper.getMainLooper())

    val isConfigured get() = serviceKey.isNotBlank()

    /** 조회에 쓴 법정동 코드와 고른 충전소. */
    data class StationResult(val regionCode: String, val plans: List<StationPlan>)

    /**
     * [regionCode]는 백그라운드에서 불리며 10자리 법정동 코드를 돌려준다.
     * [onResult]는 메인 스레드에서 호출되고 실패하면 null을 받는다.
     */
    fun fetchStations(center: GeoPoint, regionCode: () -> String?, onResult: (StationResult?) -> Unit) {
        executor.execute {
            val result = try {
                val code = checkNotNull(regionCode()) { "지역 코드 없음" }
                val rows = parseChargers(download("getChargerInfo", code, ""))
                val plans = TmapChargerClient.pickSpread(toCandidates(rows, center, System.currentTimeMillis()), center)
                if (plans.isEmpty()) null else StationResult(code, plans)
            } catch (e: Exception) {
                Log.w(TAG, "충전소 조회 실패", e)
                null
            }
            mainThread.post { onResult(result) }
        }
    }

    /** 최근 [STATUS_PERIOD_MINUTES]분 사이에 바뀐 충전기 상태. */
    fun fetchStatus(regionCode: String, onResult: (List<LiveUpdate>?) -> Unit) {
        executor.execute {
            val updates = try {
                toUpdates(parseChargers(download("getChargerStatus", regionCode, "&period=$STATUS_PERIOD_MINUTES")), System.currentTimeMillis())
            } catch (e: Exception) {
                Log.w(TAG, "상태 조회 실패", e)
                null
            }
            mainThread.post { onResult(updates) }
        }
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun download(operation: String, regionCode: String, extra: String): String {
        // 포털의 인코딩 키(%가 들어 있음)와 디코딩 키를 모두 받는다.
        val key = if (serviceKey.contains('%')) serviceKey else URLEncoder.encode(serviceKey, "UTF-8")
        val url = URL(
            "https://apis.data.go.kr/B552584/EvCharger/$operation?serviceKey=$key" +
                "&pageNo=1&numOfRows=$MAX_ROWS&zcode=${regionCode.take(2)}&zscode=${regionCode.take(5)}$extra",
        )
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            val code = connection.responseCode
            val body = (if (code == 200) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(code == 200) { "HTTP $code ${errorMessage(body)}" }
            return body
        } finally {
            connection.disconnect()
        }
    }

    /** 응답 한 줄. 충전기 하나에 해당한다. */
    data class ChargerRow(
        val stationId: String,
        val stationName: String,
        val chargerId: String,
        val latitude: Double?,
        val longitude: Double?,
        val status: String,
        val chargingSince: String?,
        val outputKw: Int?,
        val chargerType: String,
        val deleted: Boolean,
    )

    companion object {
        private const val TAG = "EvChargerClient"
        private const val TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_ROWS = 9_999
        const val STATUS_PERIOD_MINUTES = 5
        private const val SEARCH_RADIUS_METERS = 5_000.0

        // 충전기 상태 코드
        private const val STATUS_FREE = "2"
        private const val STATUS_CHARGING = "3"

        private const val FAST_SESSION_SECONDS = 40 * 60
        private const val SLOW_SESSION_SECONDS = 4 * 60 * 60
        private const val MIN_REMAINING_SECONDS = 5 * 60
        private val SLOW_TYPES = setOf("02", "08")

        fun parseChargers(xml: String): List<ChargerRow> {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(xml.byteInputStream())
            val resultCode = document.getElementsByTagName("resultCode").item(0)?.textContent?.trim()
            check(resultCode == null || resultCode == "00") { "API 오류 $resultCode ${errorMessage(xml)}" }
            check(document.getElementsByTagName("returnAuthMsg").length == 0) { "API 오류 ${errorMessage(xml)}" }
            val items = document.getElementsByTagName("item")
            return (0 until items.length).map { index ->
                val item = items.item(index) as Element
                fun text(tag: String) = item.getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()
                ChargerRow(
                    stationId = text("statId"),
                    stationName = text("statNm"),
                    chargerId = text("chgerId"),
                    latitude = text("lat").toDoubleOrNull(),
                    longitude = text("lng").toDoubleOrNull(),
                    status = text("stat"),
                    chargingSince = text("nowTsdt").ifEmpty { null },
                    outputKw = text("output").toIntOrNull(),
                    chargerType = text("chgerType"),
                    deleted = text("delYn") == "Y",
                )
            }
        }

        private fun errorMessage(body: String): String =
            Regex("<(?:resultMsg|returnAuthMsg|errMsg)>([^<]*)<").findAll(body).joinToString(" ") { it.groupValues[1] }

        /**
         * 충전소별로 묶고 상태를 아는 충전기(충전대기, 충전중)만 남긴다.
         * 통신이상, 상태미확인 충전기는 비어 있는지 알 수 없으므로 세지 않는다.
         */
        fun toCandidates(rows: List<ChargerRow>, center: GeoPoint, nowMillis: Long): List<Candidate> =
            rows.filter { !it.deleted && it.latitude != null && it.longitude != null }
                .groupBy { it.stationId }
                .mapNotNull { (stationId, chargers) ->
                    val first = chargers.first()
                    val position = GeoPoint(first.latitude!!, first.longitude!!)
                    if (center.distanceTo(position) > SEARCH_RADIUS_METERS) return@mapNotNull null
                    val known = chargers.filter { it.status == STATUS_FREE || it.status == STATUS_CHARGING }
                        .sortedBy { it.chargerId }
                    if (known.isEmpty()) return@mapNotNull null
                    val remaining = known.map { if (it.status == STATUS_CHARGING) estimateRemainingSeconds(it, nowMillis) else null }
                    Candidate(
                        id = "ev-$stationId",
                        name = TmapChargerClient.shortName(first.stationName),
                        position = position,
                        total = known.size,
                        charging = remaining.count { it != null },
                        chargerIds = known.map { it.chargerId },
                        remainingSeconds = remaining,
                    )
                }

        fun toUpdates(rows: List<ChargerRow>, nowMillis: Long): List<LiveUpdate> = rows
            .filter { it.status == STATUS_FREE || it.status == STATUS_CHARGING }
            .map { row ->
                LiveUpdate(
                    stationId = "ev-${row.stationId}",
                    chargerId = row.chargerId,
                    remainingSeconds = if (row.status == STATUS_CHARGING) estimateRemainingSeconds(row, nowMillis) else null,
                )
            }

        /**
         * 남은 충전 시간은 API가 주지 않으므로 충전 시작 시각과 보통 충전 시간으로 어림한다.
         * 급속은 40분, 완속은 4시간으로 본다.
         */
        fun estimateRemainingSeconds(row: ChargerRow, nowMillis: Long): Int {
            val slow = row.chargerType in SLOW_TYPES || (row.outputKw != null && row.outputKw <= 11)
            val session = if (slow) SLOW_SESSION_SECONDS else FAST_SESSION_SECONDS
            val started = row.chargingSince?.let(::parseTime) ?: return session / 2
            val elapsed = ((nowMillis - started) / 1000).toInt()
            return (session - elapsed).coerceAtLeast(MIN_REMAINING_SECONDS)
        }

        private fun parseTime(value: String): Long? = try {
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("Asia/Seoul")
                isLenient = false
            }.parse(value)?.time
        } catch (e: java.text.ParseException) {
            null
        }
    }
}
