package com.lswlight.photolocation

import android.content.Context
import android.location.Location
import java.util.Calendar
import kotlin.math.floor

/** 집 또는 회사 위치. manual 은 사용자가 직접 지정했는지 여부. */
data class Anchor(val lat: Double, val lng: Double, val manual: Boolean)

enum class LifeKind { HOME, WORK, OTHER }

/** 사용자가 직접 지정한 집/회사 위치를 저장한다. */
class AnchorStore(context: Context) {
    private val prefs = context.getSharedPreferences("anchors", Context.MODE_PRIVATE)

    fun get(name: String): Anchor? {
        val lat = prefs.getString("${name}_lat", null)?.toDoubleOrNull() ?: return null
        val lng = prefs.getString("${name}_lng", null)?.toDoubleOrNull() ?: return null
        return Anchor(lat, lng, manual = true)
    }

    fun set(name: String, lat: Double, lng: Double) {
        prefs.edit().putString("${name}_lat", lat.toString()).putString("${name}_lng", lng.toString()).apply()
    }

    /** 회사로 볼 시·군·구 이름 (예: 경상북도 구미시). 공장·산업단지처럼 넓은 사업장용 */
    var workArea: String?
        get() = prefs.getString("work_area", null)
        set(value) = prefs.edit().putString("work_area", value).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }
}

/**
 * 카메라로 찍은 사진 중 집과 회사에서 찍은 사진을 가려낸다. 나머지는 지역 이름으로 묶는다.
 *
 * - 집: 밤(21시~7시)에 찍은 날이 가장 많은 장소에서 반경 300m (또는 직접 지정)
 * - 회사: 평일 낮(9시~18시)에 찍은 날이 가장 많은 장소에서 반경 1km (또는 주소·시 전체로 직접 지정)
 */
object LifeClassifier {
    private const val HOME_RADIUS_M = 300f
    // 공장·연구소처럼 부지가 넓은 사업장을 고려해 집보다 넓게 잡는다.
    private const val WORK_RADIUS_M = 1_000f
    private const val WORK_MIN_DISTANCE_FROM_HOME_M = 500f
    /** 자동 감지에 필요한 최소 일수. 이보다 적으면 우연히 들른 곳일 수 있다. */
    private const val MIN_DAYS = 3

    fun detectHome(photos: List<Photo>): Anchor? = mostVisitedCell(photos) { cal ->
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        hour >= 21 || hour < 7
    }

    fun detectWork(photos: List<Photo>, home: Anchor?): Anchor? {
        val candidates = photos.filter {
            it.hasLocation && (home == null || distance(it, home) > WORK_MIN_DISTANCE_FROM_HOME_M)
        }
        return mostVisitedCell(candidates) { cal ->
            val day = cal.get(Calendar.DAY_OF_WEEK)
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            day != Calendar.SATURDAY && day != Calendar.SUNDAY && hour in 9..17
        }
    }

    /** workArea 가 있으면 그 시·군·구(areaOf 결과)에서 찍은 사진은 모두 회사로 본다. */
    fun kindOf(
        photo: Photo,
        home: Anchor?,
        work: Anchor?,
        workArea: String?,
        areaOf: (Photo) -> String?,
    ): LifeKind {
        val toHome = home?.let { distance(photo, it) }
        val toWork = work?.let { distance(photo, it) }
        return when {
            toHome != null && toHome <= HOME_RADIUS_M -> LifeKind.HOME
            workArea != null && areaOf(photo) == workArea -> LifeKind.WORK
            toWork != null && toWork <= WORK_RADIUS_M -> LifeKind.WORK
            else -> LifeKind.OTHER
        }
    }

    private fun mostVisitedCell(photos: List<Photo>, match: (Calendar) -> Boolean): Anchor? {
        val cal = Calendar.getInstance()
        val days = HashMap<Pair<Long, Long>, MutableSet<Int>>()
        val members = HashMap<Pair<Long, Long>, MutableList<Photo>>()
        for (photo in photos) {
            if (!photo.hasLocation) continue
            cal.timeInMillis = photo.dateTaken
            if (!match(cal)) continue
            // 0.002도 ≈ 200m 구역. 사진 수가 아니라 '찍은 날 수'로 세어 여행지에서 몰아 찍은 사진에 휘둘리지 않게 한다.
            val key = floor(photo.lat!! * 500).toLong() to floor(photo.lng!! * 500).toLong()
            days.getOrPut(key) { mutableSetOf() } += cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
            members.getOrPut(key) { mutableListOf() } += photo
        }
        val best = days.maxByOrNull { it.value.size } ?: return null
        if (best.value.size < MIN_DAYS) return null
        val list = members.getValue(best.key)
        return Anchor(list.map { it.lat!! }.average(), list.map { it.lng!! }.average(), manual = false)
    }

    private fun distance(photo: Photo, anchor: Anchor): Float {
        val result = FloatArray(1)
        Location.distanceBetween(photo.lat!!, photo.lng!!, anchor.lat, anchor.lng, result)
        return result[0]
    }
}
