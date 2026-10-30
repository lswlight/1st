package com.lswlight.photolocation

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore

/** 내보내기 폴더. PC 에서는 '내장 메모리/Pictures/앨범박스' 로 보인다. */
const val EXPORT_ROOT = "Pictures/앨범박스/"

/** 앱 이름을 바꾸기 전에 쓰던 내보내기 폴더. 비우기와 출처 판별에서 계속 인식한다. */
const val LEGACY_EXPORT_ROOT = "Pictures/사진위치분류/"

private val MONTH_FORMAT = java.text.SimpleDateFormat("yyyy년 MM월", java.util.Locale.KOREAN)

/** 월 폴더 이름. 두 자리 월이라 PC 에서도 순서대로 정렬된다. 예: 2026년 03월 */
fun monthLabel(time: Long): String = MONTH_FORMAT.format(java.util.Date(time))

/** 윈도우에서도 쓸 수 있는 폴더 이름으로 바꾼다. */
private fun safeName(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().trimEnd('.')

/**
 * 그룹의 사진마다 넣을 폴더.
 * 하위 지역이 있으면 '경상북도/포항시/2026년 10월/', 없으면 '대구/2026년 10월/'
 */
fun exportTargets(group: PhotoGroup): List<Pair<Photo, String>> {
    val base = EXPORT_ROOT + safeName(group.name) + "/"
    if (group.subgroups.isEmpty()) {
        return group.photos.map { it to base + monthLabel(it.dateTaken) + "/" }
    }
    return group.subgroups.flatMap { sub ->
        sub.photos.map { it to base + safeName(sub.name) + "/" + monthLabel(it.dateTaken) + "/" }
    }
}

/**
 * 분류된 사진·동영상을 그룹별 폴더로 복사하거나 옮긴다.
 * 이동과 휴지통 보내기는 다른 앱(카메라)이 만든 파일을 건드리므로
 * 호출 전에 MediaStore.createWriteRequest / createTrashRequest 로 사용자 허락을 받아야 한다.
 */
class Exporter(private val context: Context) {

    private val resolver = context.contentResolver
    private fun target(isVideo: Boolean) =
        if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** 폴더에 이미 있는 파일 이름들. 다시 내보낼 때 중복 복사를 건너뛰는 데 쓴다. */
    fun existingNames(relativePath: String): Set<String> {
        val names = HashSet<String>()
        // 사진과 동영상이 같은 폴더에 들어가므로 둘을 함께 보는 Files 컬렉션으로 조회한다.
        resolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(relativePath),
            null,
        )?.use { c ->
            while (c.moveToNext()) c.getString(0)?.let { names += it }
        }
        return names
    }

    /** 원본은 그대로 두고 복사본을 만든다. GPS 정보가 지워지지 않도록 원본 그대로 읽는다. */
    fun copy(photo: Photo, relativePath: String): Boolean {
        val canReadLocation = context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val source = if (canReadLocation) MediaStore.setRequireOriginal(photo.uri) else photo.uri
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, photo.name)
            put(
                MediaStore.MediaColumns.MIME_TYPE,
                resolver.getType(photo.uri) ?: if (photo.isVideo) "video/mp4" else "image/jpeg",
            )
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.DATE_TAKEN, photo.dateTaken)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val dest = resolver.insert(target(photo.isVideo), values) ?: return false
        return try {
            resolver.openInputStream(source)!!.use { input ->
                resolver.openOutputStream(dest)!!.use { output -> input.copyTo(output) }
            }
            resolver.update(dest, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(dest, null, null)
            false
        }
    }

    /** 원본 파일을 폴더로 옮긴다. (createWriteRequest 로 허락받은 뒤 호출) */
    fun move(photo: Photo, relativePath: String): Boolean = try {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath) }
        resolver.update(photo.uri, values, null, null) > 0
    } catch (e: Exception) {
        false
    }

    /** 내보내기 폴더(예전 이름 폴더 포함) 안의 모든 사진·동영상 */
    fun exportedUris(): List<Uri> {
        val uris = mutableListOf<Uri>()
        val collections = MediaStore.getExternalVolumeNames(context).flatMap { volume ->
            listOf(MediaStore.Images.Media.getContentUri(volume), MediaStore.Video.Media.getContentUri(volume))
        }
        for (collection in collections) {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$EXPORT_ROOT%", "$LEGACY_EXPORT_ROOT%"),
                null,
            )?.use { c ->
                while (c.moveToNext()) uris += android.content.ContentUris.withAppendedId(collection, c.getLong(0))
            }
        }
        return uris
    }

    /** 이 앱이 만든 파일만 바로 지울 수 있다 (Android 10 용) */
    fun delete(uri: Uri): Boolean = try {
        resolver.delete(uri, null, null) > 0
    } catch (e: Exception) {
        false
    }
}
