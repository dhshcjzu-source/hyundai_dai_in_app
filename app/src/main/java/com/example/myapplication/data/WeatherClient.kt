package com.example.myapplication.data

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.myapplication.model.GeoPoint
import com.example.myapplication.model.Weather
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Open-Meteo에서 현재 날씨를 받는다. 위치는 약 1km 단위로 반올림해 보낸다. */
class WeatherClient {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainThread = Handler(Looper.getMainLooper())

    /** [onResult]는 메인 스레드에서 호출된다. 실패하면 null을 넘긴다. */
    fun fetch(position: GeoPoint, onResult: (Weather?) -> Unit) {
        executor.execute {
            val weather = try {
                parse(download(position))
            } catch (e: Exception) {
                Log.w(TAG, "날씨 조회 실패", e)
                null
            }
            mainThread.post { onResult(weather) }
        }
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun download(position: GeoPoint): String {
        val url = URL(
            String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
                    "&current=temperature_2m,weather_code,cloud_cover",
                position.latitude,
                position.longitude,
            ),
        )
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(json: String): Weather {
        val current = JSONObject(json).getJSONObject("current")
        val code = current.getInt("weather_code")
        return Weather(
            kind = Weather.kindOf(code),
            temperatureC = current.getDouble("temperature_2m").roundToInt(),
            cloudCover = (current.getDouble("cloud_cover") / 100).toFloat().coerceIn(0f, 1f),
            intensity = Weather.intensityOf(code),
        )
    }

    private companion object {
        const val TAG = "WeatherClient"
        const val TIMEOUT_MS = 10_000
    }
}
