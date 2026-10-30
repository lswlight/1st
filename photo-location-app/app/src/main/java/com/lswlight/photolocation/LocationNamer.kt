package com.lswlight.photolocation

import android.content.Context
import android.location.Geocoder
import java.io.File
import java.util.Locale
import kotlin.math.floor

/** 한 지점의 주소를 나라 / 시·도 / 시·군·구 세 단계로 나눈 것. */
data class Place(val country: String, val province: String, val district: String)

enum class GroupLevel(val label: String) {
    /** 집 / 회사 / 동네 / 여행 (LifeClassifier) */
    LIFE("생활권"),
    COUNTRY("나라"),
    PROVINCE("시·도"),
    DISTRICT("시·군·구"),
}

fun Place.nameAt(level: GroupLevel): String = when (level) {
    GroupLevel.COUNTRY -> country
    GroupLevel.PROVINCE -> province
    GroupLevel.LIFE, GroupLevel.DISTRICT -> district
}

/**
 * 좌표를 주소로 바꾼다. 같은 동네 사진마다 Geocoder 를 부르지 않도록
 * 좌표를 약 1km 격자(0.01도)로 묶어 격자 하나당 한 번만 조회하고 파일에 캐시한다.
 */
class LocationNamer(context: Context) {

    private val geocoder = Geocoder(context, Locale.KOREAN)
    private val cacheFile = File(context.filesDir, "geo_cache.tsv")
    private val cache: MutableMap<String, Place> = readCache()

    val isAvailable: Boolean get() = Geocoder.isPresent()

    fun cellKey(lat: Double, lng: Double): String =
        "${floor(lat * 100).toLong()},${floor(lng * 100).toLong()}"

    fun cached(key: String): Place? = cache[key]

    /** 캐시에 없는 격자만 조회한다. 실패한 격자는 캐시하지 않아 다음 새로고침 때 다시 시도한다. */
    fun resolve(keys: Collection<String>, onProgress: (done: Int, total: Int) -> Unit) {
        val missing = keys.filter { it !in cache }
        missing.forEachIndexed { index, key ->
            onProgress(index, missing.size)
            lookup(key)?.let { cache[key] = it }
        }
        onProgress(missing.size, missing.size)
        if (missing.isNotEmpty()) writeCache()
    }

    @Suppress("DEPRECATION")
    private fun lookup(key: String): Place? {
        val (latCell, lngCell) = key.split(',').map { it.toLong() }
        // 격자 중앙 좌표로 조회
        val lat = (latCell + 0.5) / 100.0
        val lng = (lngCell + 0.5) / 100.0
        val address = try {
            geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()
        } catch (e: Exception) {
            null
        } ?: return null

        val country = address.countryName ?: return null
        val province = address.adminArea ?: country
        // 한국 주소 기준: 일반 시는 locality(수원시), 서울·광역시의 구는 subLocality(강남구)에 들어온다.
        val sub = address.locality ?: address.subAdminArea ?: address.subLocality
        val district = if (sub != null && sub != province) "$province $sub" else province
        return Place(country, province, district)
    }

    private fun readCache(): MutableMap<String, Place> {
        val map = HashMap<String, Place>()
        if (!cacheFile.exists()) return map
        cacheFile.forEachLine { line ->
            val p = line.split('\t')
            if (p.size == 4) map[p[0]] = Place(p[1], p[2], p[3])
        }
        return map
    }

    private fun writeCache() {
        cacheFile.bufferedWriter().use { w ->
            for ((key, place) in cache) {
                w.write("$key\t${place.country}\t${place.province}\t${place.district}\n")
            }
        }
    }
}
