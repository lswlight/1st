package com.lswlight.photolocation

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore

/** 내보내기 폴더. PC 에서는 '내장 메모리/Pictures/사진위치분류' 로 보인다. */
const val EXPORT_ROOT = "Pictures/사진위치분류/"

/** 그룹 이름을 윈도우에서도 쓸 수 있는 폴더 이름으로 바꾼다. */
fun exportFolder(groupName: String): String {
    val safe = groupName.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().trimEnd('.')
    return "$EXPORT_ROOT$safe/"
}

/**
 * 분류된 사진을 그룹별 폴더로 복사하거나 옮긴다.
 * 이동과 휴지통 보내기는 다른 앱(카메라)이 만든 파일을 건드리므로
 * 호출 전에 MediaStore.createWriteRequest / createTrashRequest 로 사용자 허락을 받아야 한다.
 */
class Exporter(private val context: Context) {

    private val resolver = context.contentResolver
    private val target = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** 폴더에 이미 있는 파일 이름들. 다시 내보낼 때 중복 복사를 건너뛰는 데 쓴다. */
    fun existingNames(relativePath: String): Set<String> {
        val names = HashSet<String>()
        resolver.query(
            target,
            arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
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
            put(MediaStore.Images.Media.DISPLAY_NAME, photo.name)
            put(MediaStore.Images.Media.MIME_TYPE, resolver.getType(photo.uri) ?: "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.DATE_TAKEN, photo.dateTaken)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val dest = resolver.insert(target, values) ?: return false
        return try {
            resolver.openInputStream(source)!!.use { input ->
                resolver.openOutputStream(dest)!!.use { output -> input.copyTo(output) }
            }
            resolver.update(dest, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(dest, null, null)
            false
        }
    }

    /** 원본 파일을 폴더로 옮긴다. (createWriteRequest 로 허락받은 뒤 호출) */
    fun move(photo: Photo, relativePath: String): Boolean = try {
        val values = ContentValues().apply { put(MediaStore.Images.Media.RELATIVE_PATH, relativePath) }
        resolver.update(photo.uri, values, null, null) > 0
    } catch (e: Exception) {
        false
    }

    /** 내보내기 폴더 안의 모든 사진 */
    fun exportedUris(): List<Uri> {
        val uris = mutableListOf<Uri>()
        for (volume in MediaStore.getExternalVolumeNames(context)) {
            val collection = MediaStore.Images.Media.getContentUri(volume)
            resolver.query(
                collection,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("$EXPORT_ROOT%"),
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
