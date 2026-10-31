package com.lswlight.photolocation

/*
 * 사진 출처 구분. 저장 폴더(RELATIVE_PATH)를 보고
 * 내가 카메라로 찍은 사진인지, 카카오톡 등에서 받은 사진인지 나눈다.
 * 받은 사진은 위치가 있더라도 내 생활권(집/회사/여행)에 섞지 않는다.
 */

const val KAKAO = "💬 카카오톡"
const val SCREENSHOT = "📱 스크린샷"
const val DOWNLOAD = "⬇️ 다운로드"
private const val OTHER_APP_PREFIX = "🗂️ "

/** 받은 사진 그룹이 목록에 나오는 순서 */
val RECEIVED_ORDER = listOf(KAKAO, SCREENSHOT, DOWNLOAD)

/**
 * 받은 사진이면 그룹 이름을, 카메라로 찍은 사진이면 null 을 돌려준다.
 * 예: "Pictures/KakaoTalk/" → 💬 카카오톡, "DCIM/Camera/" → null, "Pictures/Band/" → 🗂️ Band
 * 갤러리 앱에서 만든 앨범(예: DCIM/보안카드, Pictures/보안카드)도 앨범 이름으로 따로 묶는다.
 */
fun receivedGroupName(relativePath: String): String? {
    val path = relativePath.trim('/')
    val lower = path.lowercase()

    // 내보내기 기능으로 옮긴 사진은 첫 하위 폴더 이름으로 원래 분류를 되살린다.
    val exportRoot = listOf(EXPORT_ROOT, LEGACY_EXPORT_ROOT).map { it.trim('/') }.firstOrNull { path.startsWith(it) }
    if (exportRoot != null) {
        val sub = path.removePrefix(exportRoot).trim('/').substringBefore('/')
        return sub.takeIf { it in RECEIVED_ORDER || it.startsWith(OTHER_APP_PREFIX) }
    }

    return when {
        "kakaotalk" in lower -> KAKAO
        "screenshot" in lower || "screen record" in lower || "screenrecord" in lower -> SCREENSHOT
        lower.startsWith("download") -> DOWNLOAD
        // 카메라가 찍어 저장하는 폴더만 위치로 분류한다: DCIM, DCIM/Camera, DCIM/100ANDRO 같은 번호 폴더
        lower == "dcim" || lower.startsWith("dcim/camera") || Regex("^dcim/\\d{3}").containsMatchIn(lower) -> null
        path.isEmpty() -> "${OTHER_APP_PREFIX}기타"
        else -> OTHER_APP_PREFIX + path.substringAfterLast('/')
    }
}
