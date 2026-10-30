package com.lswlight.photolocation

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File

data class Photo(
    val id: Long,
    val uri: Uri,
    val name: String,
    val dateTaken: Long,
    val lat: Double?,
    val lng: Double?,
) {
    val hasLocation: Boolean get() = lat != null && lng != null
}

/**
 * MediaStore 에서 사진 목록을 가져오고 EXIF 의 GPS 좌표를 읽는다.
 * EXIF 읽기는 느리므로 (사진 id, 수정 시각) 기준으로 파일에 캐시해 두고
 * 새로 추가되거나 바뀐 사진만 다시 읽는다.
 */
class PhotoRepository(private val context: Context) {

    private val cacheFile = File(context.filesDir, "exif_cache.tsv")

    private data class CachedExif(val dateModified: Long, val lat: Double?, val lng: Double?)

    fun loadPhotos(onProgress: (done: Int, total: Int) -> Unit): List<Photo> {
        val cache = readCache()
        val canReadLocation = context.checkSelfPermission(
            android.Manifest.permission.ACCESS_MEDIA_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        data class Row(val id: Long, val name: String, val dateTaken: Long, val dateModified: Long)

        val rows = mutableListOf<Row>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
        )
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val modCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                val dateModified = c.getLong(modCol)
                rows += Row(
                    id = c.getLong(idCol),
                    name = c.getString(nameCol) ?: "",
                    // DATE_TAKEN 이 없는 사진은 수정 시각(초 단위)으로 대신한다.
                    dateTaken = c.getLong(takenCol).takeIf { it > 0 } ?: (dateModified * 1000),
                    dateModified = dateModified,
                )
            }
        }

        val newCache = HashMap<Long, CachedExif>(rows.size)
        val photos = rows.mapIndexed { index, row ->
            if (index % 50 == 0) onProgress(index, rows.size)
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, row.id)
            val cached = cache[row.id]?.takeIf { it.dateModified == row.dateModified }
            val exif = cached ?: readExif(uri, canReadLocation).let { (lat, lng) ->
                CachedExif(row.dateModified, lat, lng)
            }
            newCache[row.id] = exif
            Photo(row.id, uri, row.name, row.dateTaken, exif.lat, exif.lng)
        }
        onProgress(rows.size, rows.size)
        // 위치 권한이 없을 때 읽은 값은 좌표가 가려져 있으므로 캐시에 남기지 않는다.
        if (canReadLocation) writeCache(newCache)
        return photos
    }

    private fun readExif(uri: Uri, canReadLocation: Boolean): Pair<Double?, Double?> {
        val source = if (canReadLocation) MediaStore.setRequireOriginal(uri) else uri
        return try {
            context.contentResolver.openInputStream(source)?.use { stream ->
                val latLong = ExifInterface(stream).latLong
                if (latLong == null || (latLong[0] == 0.0 && latLong[1] == 0.0)) {
                    null to null
                } else {
                    latLong[0] to latLong[1]
                }
            } ?: (null to null)
        } catch (e: Exception) {
            null to null
        }
    }

    private fun readCache(): Map<Long, CachedExif> {
        if (!cacheFile.exists()) return emptyMap()
        val map = HashMap<Long, CachedExif>()
        cacheFile.forEachLine { line ->
            val p = line.split('\t')
            if (p.size == 4) {
                val id = p[0].toLongOrNull() ?: return@forEachLine
                val mod = p[1].toLongOrNull() ?: return@forEachLine
                map[id] = CachedExif(mod, p[2].toDoubleOrNull(), p[3].toDoubleOrNull())
            }
        }
        return map
    }

    private fun writeCache(cache: Map<Long, CachedExif>) {
        cacheFile.bufferedWriter().use { w ->
            for ((id, e) in cache) {
                w.write("$id\t${e.dateModified}\t${e.lat ?: ""}\t${e.lng ?: ""}\n")
            }
        }
    }
}
