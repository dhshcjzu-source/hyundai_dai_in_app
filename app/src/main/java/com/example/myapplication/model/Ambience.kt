package com.example.myapplication.model

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

enum class WeatherKind { CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, RAIN, SNOW, THUNDER }

data class Weather(
    val kind: WeatherKind,
    val temperatureC: Int?,
    /** 구름양 0~1 */
    val cloudCover: Float,
    /** 비, 눈의 세기 0~1 */
    val intensity: Float,
) {
    companion object {
        /** 세계기상기구(WMO) 날씨 코드를 앱의 날씨 종류로 바꾼다. */
        fun kindOf(code: Int): WeatherKind = when (code) {
            0, 1 -> WeatherKind.CLEAR
            2 -> WeatherKind.PARTLY_CLOUDY
            3 -> WeatherKind.CLOUDY
            45, 48 -> WeatherKind.FOG
            in 51..67, in 80..82 -> WeatherKind.RAIN
            in 71..77, 85, 86 -> WeatherKind.SNOW
            in 95..99 -> WeatherKind.THUNDER
            else -> WeatherKind.PARTLY_CLOUDY
        }

        fun intensityOf(code: Int): Float = when (code) {
            51, 56, 61, 66, 71, 77, 80, 85 -> 0.4f
            53, 57, 63, 67, 73, 81, 86 -> 0.7f
            55, 65, 75, 82, 95, 96, 99 -> 1f
            else -> 0f
        }

        /** 시연과 테스트에 쓰는 날씨별 기본값. */
        fun preset(kind: WeatherKind): Weather = when (kind) {
            WeatherKind.CLEAR -> Weather(kind, null, 0.05f, 0f)
            WeatherKind.PARTLY_CLOUDY -> Weather(kind, null, 0.5f, 0f)
            WeatherKind.CLOUDY -> Weather(kind, null, 1f, 0f)
            WeatherKind.FOG -> Weather(kind, null, 1f, 0f)
            WeatherKind.RAIN -> Weather(kind, null, 1f, 0.7f)
            WeatherKind.SNOW -> Weather(kind, null, 1f, 0.7f)
            WeatherKind.THUNDER -> Weather(kind, null, 1f, 1f)
        }
    }
}

/** 태양의 위치. 네트워크 없이 차량 위치와 UTC 시각만으로 계산한다. */
data class SunPosition(
    /** 지평선 기준 높이(도). 음수면 해가 진 상태다. */
    val elevation: Double,
    /** 남중 기준 시간각(도). 오전은 음수, 오후는 양수다. */
    val hourAngle: Double,
) {
    companion object {
        fun at(position: GeoPoint, utcMillis: Long): SunPosition {
            val days = utcMillis / 86_400_000.0 - 10_957.5 // 2000-01-01 12:00 UTC 기준
            val meanLongitude = normalize(280.46 + 0.9856474 * days)
            val meanAnomaly = Math.toRadians(normalize(357.528 + 0.9856003 * days))
            val eclipticLongitude = Math.toRadians(
                meanLongitude + 1.915 * sin(meanAnomaly) + 0.02 * sin(2 * meanAnomaly),
            )
            val obliquity = Math.toRadians(23.439 - 0.0000004 * days)
            val declination = asin(sin(obliquity) * sin(eclipticLongitude))
            val rightAscension = Math.toDegrees(
                kotlin.math.atan2(cos(obliquity) * sin(eclipticLongitude), cos(eclipticLongitude)),
            )
            val siderealTime = normalize(280.46061837 + 360.98564736629 * days + position.longitude)
            var hourAngle = normalize(siderealTime - rightAscension)
            if (hourAngle > 180) hourAngle -= 360

            val latitude = Math.toRadians(position.latitude)
            val sinElevation = sin(latitude) * sin(declination) +
                cos(latitude) * cos(declination) * cos(Math.toRadians(hourAngle))
            return SunPosition(Math.toDegrees(asin(sinElevation.coerceIn(-1.0, 1.0))), hourAngle)
        }

        private fun normalize(degrees: Double): Double = degrees - 360 * floor(degrees / 360)
    }
}

/** 시간대와 날씨를 합쳐 지도를 그릴 색과 효과로 바꾼 결과. */
data class Ambience(
    val skyTop: Int,
    val skyBottom: Int,
    val ground: Int,
    val park: Int,
    val minorRoad: Int,
    val majorRoad: Int,
    val range: Int,
    val carTint: Int,
    /** 다음은 모두 0~1 값이다. */
    val stars: Float,
    val sun: Float,
    val moon: Float,
    val clouds: Float,
    val fog: Float,
    val headlights: Float,
    val rain: Float,
    val snow: Float,
    val lightning: Boolean,
    /** 하늘에서 해/달의 가로 위치 0(왼쪽)~1(오른쪽) */
    val celestialX: Float,
) {
    companion object {
        private class Palette(
            val skyTop: Long, val skyBottom: Long, val ground: Long, val park: Long,
            val minorRoad: Long, val majorRoad: Long, val range: Long, val carTint: Long,
        )

        private val NIGHT = Palette(0xFF050817, 0xFF1A2654, 0xFF121A36, 0xFF16283F, 0xFF2C3B6B, 0xFF5A6DAD, 0xFF7FD4FF, 0xFF8F9AC4)
        private val TWILIGHT = Palette(0xFF343A78, 0xFFF4905A, 0xFF4A4066, 0xFF4F5A70, 0xFF7A6688, 0xFFC49A9C, 0xFFFFD9A8, 0xFFE8C4B0)
        private val DAY = Palette(0xFF2F8FDE, 0xFFBFE4F8, 0xFF7FA694, 0xFF5F9E72, 0xFF9FB8A9, 0xFFC9D8CD, 0xFF0F6FB0, 0xFFFFFFFF)

        fun of(sun: SunPosition, weather: Weather?): Ambience {
            val base = blendPalette(sun.elevation)
            val cover = weather?.cloudCover ?: 0f
            val kind = weather?.kind
            val wet = kind == WeatherKind.RAIN || kind == WeatherKind.THUNDER
            val snowing = kind == WeatherKind.SNOW
            val foggy = kind == WeatherKind.FOG

            // 구름이 많을수록 색이 빠지고 어두워진다. 비가 오면 더 어둡다.
            val grey = (cover * 0.78f).coerceIn(0f, 1f)
            val dim = 1f - cover * 0.2f - (if (wet) 0.22f else 0f)
            fun weathered(color: Int) = multiply(desaturate(color, grey), dim)

            val daylight = ((sun.elevation + 6) / 14).toFloat().coerceIn(0f, 1f)
            var ground = weathered(base[2])
            var park = weathered(base[3])
            var minor = weathered(base[4])
            var major = weathered(base[5])
            if (snowing) {
                // 쌓인 눈은 밤에도 은은하게 밝다.
                val snowColor = multiply(0xFFEAF0F5.toInt(), 0.42f + 0.58f * daylight)
                ground = lerpColor(ground, snowColor, 0.85f)
                park = lerpColor(park, snowColor, 0.7f)
                minor = multiply(snowColor, 0.82f)
                major = multiply(snowColor, 0.68f)
            }
            val night = 1f - daylight
            val clearSky = (1f - cover).coerceIn(0f, 1f)
            return Ambience(
                skyTop = weathered(base[0]),
                skyBottom = weathered(base[1]),
                ground = ground,
                park = park,
                minorRoad = minor,
                majorRoad = major,
                // 바닥이 어두워지면 링이 묻히지 않게 밝은 쪽으로 옮긴다.
                range = lerpColor(desaturate(base[6], grey * 0.5f), 0xFFFFFFFF.toInt(), cover * 0.65f),
                carTint = base[7],
                stars = night * clearSky,
                sun = if (sun.elevation > -2) clearSky.coerceAtLeast(0.15f) else 0f,
                moon = if (sun.elevation < -2) clearSky.coerceAtLeast(0.15f) else 0f,
                clouds = cover,
                fog = if (foggy) 0.55f else 0f,
                headlights = if (wet || foggy) maxOf(night, 0.35f) else night,
                rain = if (wet) weather!!.intensity.coerceAtLeast(0.3f) else 0f,
                snow = if (snowing) weather!!.intensity.coerceAtLeast(0.3f) else 0f,
                lightning = kind == WeatherKind.THUNDER,
                celestialX = celestialX(sun.hourAngle),
            )
        }

        /** 화면은 북쪽을 보므로 오전의 해는 오른쪽(동쪽), 오후의 해는 왼쪽(서쪽)에 있다. */
        private fun celestialX(hourAngle: Double): Float {
            // 밤에는 달을 해의 반대편 움직임으로 놓는다.
            val wrapped = if (hourAngle > 90) hourAngle - 180 else if (hourAngle < -90) hourAngle + 180 else hourAngle
            val t = ((-wrapped + 90) / 180).toFloat().coerceIn(0f, 1f)
            return 0.42f + t * 0.5f
        }

        private fun blendPalette(elevation: Double): IntArray {
            val (from, to, t) = when {
                elevation <= -6 -> Triple(NIGHT, NIGHT, 0f)
                elevation <= 0 -> Triple(NIGHT, TWILIGHT, ((elevation + 6) / 6).toFloat())
                elevation <= 8 -> Triple(TWILIGHT, DAY, (elevation / 8).toFloat())
                else -> Triple(DAY, DAY, 0f)
            }
            fun mix(a: Long, b: Long) = lerpColor(a.toInt(), b.toInt(), t)
            return intArrayOf(
                mix(from.skyTop, to.skyTop), mix(from.skyBottom, to.skyBottom), mix(from.ground, to.ground),
                mix(from.park, to.park), mix(from.minorRoad, to.minorRoad), mix(from.majorRoad, to.majorRoad),
                mix(from.range, to.range), mix(from.carTint, to.carTint),
            )
        }

        fun luminance(color: Int): Float =
            0.299f * red(color) + 0.587f * green(color) + 0.114f * blue(color)

        private fun red(c: Int) = (c shr 16) and 0xFF
        private fun green(c: Int) = (c shr 8) and 0xFF
        private fun blue(c: Int) = c and 0xFF
        private fun rgb(r: Float, g: Float, b: Float): Int =
            (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)

        private fun lerpColor(a: Int, b: Int, t: Float): Int = rgb(
            red(a) + (red(b) - red(a)) * t,
            green(a) + (green(b) - green(a)) * t,
            blue(a) + (blue(b) - blue(a)) * t,
        )

        private fun desaturate(color: Int, amount: Float): Int {
            val l = luminance(color)
            return rgb(
                red(color) + (l - red(color)) * amount,
                green(color) + (l - green(color)) * amount,
                blue(color) + (l - blue(color)) * amount,
            )
        }

        private fun multiply(color: Int, factor: Float): Int =
            rgb(red(color) * factor, green(color) * factor, blue(color) * factor)
    }
}
