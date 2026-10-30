package com.lswlight.photolocation

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
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
    /** 저장 폴더 (예: DCIM/Camera/) */
    val folder: String,
    val isVideo: Boolean = false,
) {
    val hasLocation: Boolean get() = lat != null && lng != null

    /** 받은 사진이면 출처 그룹 이름(💬 카카오톡 등), 카메라로 찍은 사진이면 null */
    val source: String? = receivedGroupName(folder)
}

/**
 * MediaStore 에서 사진·동영상 목록을 가져오고 GPS 좌표를 읽는다 (사진은 EXIF, 동영상은 메타데이터).
 * EXIF 읽기는 느리므로 (사진 id, 수정 시각) 기준으로 파일에 캐시해 두고
 * 새로 추가되거나 바뀐 사진만 다시 읽는다.
 */
class PhotoRepository(private val context: Context) {

    private val cacheFile = File(context.filesDir, "exif_cache.tsv")

    private data class CachedExif(val dateModified: Long, val lat: Double?, val lng: Double?)

    /** 사진을 못 찾았을 때 원인 파악용으로 화면에 보여주는 조회 기록 */
    var diagnostics: String = ""
        private set

    fun loadPhotos(onProgress: (done: Int, total: Int) -> Unit): List<Photo> {
        val cache = readCache()
        val canReadLocation = context.checkSelfPermission(
            android.Manifest.permission.ACCESS_MEDIA_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        data class Row(
            val id: Long,
            val volume: String,
            val name: String,
            val folder: String,
            val dateTaken: Long,
            val dateModified: Long,
            val isVideo: Boolean,
        )

        val rows = mutableListOf<Row>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        // 내장 메모리와 SD 카드를 각각 조회한다.
        val log = mutableListOf<String>()
        val volumes = try {
            MediaStore.getExternalVolumeNames(context).toList()
        } catch (e: Exception) {
            log += "볼륨 목록 오류: ${e.javaClass.simpleName} ${e.message}"
            emptyList()
        }.ifEmpty { listOf(MediaStore.VOLUME_EXTERNAL) }

        // 볼륨마다 사진과 동영상을 각각 조회한다.
        val queries = volumes.flatMap { listOf(it to false, it to true) }
        for ((volume, isVideo) in queries) {
            val kind = if (isVideo) "동영상" else "사진"
            try {
                val cursor = context.contentResolver.query(
                    collection(volume, isVideo),
                    projection,
                    null,
                    null,
                    "${MediaStore.MediaColumns.DATE_TAKEN} DESC",
                )
                if (cursor == null) {
                    log += "$volume $kind: 조회 결과 없음(null)"
                    continue
                }
                cursor.use { c ->
                    log += "$volume $kind: ${c.count}개"
                    val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val takenCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                    val modCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val folderCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                    while (c.moveToNext()) {
                        val dateModified = c.getLong(modCol)
                        rows += Row(
                            id = c.getLong(idCol),
                            volume = volume,
                            name = c.getString(nameCol) ?: "",
                            folder = c.getString(folderCol) ?: "",
                            // DATE_TAKEN 이 없는 사진은 수정 시각(초 단위)으로 대신한다.
                            dateTaken = c.getLong(takenCol).takeIf { it > 0 } ?: (dateModified * 1000),
                            dateModified = dateModified,
                            isVideo = isVideo,
                        )
                    }
                }
            } catch (e: Exception) {
                log += "$volume $kind 오류: ${e.javaClass.simpleName} ${e.message}"
            }
        }
        // 여러 볼륨을 합쳤으므로 다시 최신순 정렬
        rows.sortByDescending { it.dateTaken }
        diagnostics = log.joinToString(" · ")

        val newCache = HashMap<Long, CachedExif>(rows.size)
        val photos = rows.mapIndexed { index, row ->
            if (index % 50 == 0) onProgress(index, rows.size)
            val uri = ContentUris.withAppendedId(collection(row.volume, row.isVideo), row.id)
            val cached = cache[row.id]?.takeIf { it.dateModified == row.dateModified }
            val exif = cached ?: run {
                val (lat, lng) = if (row.isVideo) readVideoLocation(uri, canReadLocation) else readExif(uri, canReadLocation)
                CachedExif(row.dateModified, lat, lng)
            }
            newCache[row.id] = exif
            Photo(row.id, uri, row.name, row.dateTaken, exif.lat, exif.lng, row.folder, row.isVideo)
        }
        onProgress(rows.size, rows.size)
        // 위치 권한이 없을 때 읽은 값은 좌표가 가려져 있으므로 캐시에 남기지 않는다.
        if (canReadLocation) writeCache(newCache)
        return photos
    }

    private fun collection(volume: String, isVideo: Boolean): Uri =
        if (isVideo) MediaStore.Video.Media.getContentUri(volume) else MediaStore.Images.Media.getContentUri(volume)

    /** 동영상의 촬영 위치. 형식(ISO 6709): +37.5665+126.9780/ 또는 +37.5665+126.9780+012.300/ */
    private fun readVideoLocation(uri: Uri, canReadLocation: Boolean): Pair<Double?, Double?> {
        val source = if (canReadLocation) MediaStore.setRequireOriginal(uri) else uri
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, source)
            val location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            val match = location?.let { Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(it) }
            val lat = match?.groupValues?.get(1)?.toDoubleOrNull()
            val lng = match?.groupValues?.get(2)?.toDoubleOrNull()
            if (lat == null || lng == null || (lat == 0.0 && lng == 0.0)) null to null else lat to lng
        } catch (e: Exception) {
            null to null
        } finally {
            retriever.release()
        }
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
