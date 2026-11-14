package com.lswlight.moneybook

import android.content.Context

/**
 * 사용자가 '최근 감지 알림'에서 직접 추가한, 결제 알림을 읽을 앱 목록 (패키지 이름).
 * 지역화폐·새 페이 앱처럼 기본 목록에 없는 앱의 결제 알림도 읽게 한다.
 */
object WatchedApps {
    private const val PREFS = "watched_apps"
    private const val KEY = "packages"

    fun all(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty()

    fun contains(context: Context, pkg: String) = pkg in all(context)

    fun add(context: Context, pkg: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, all(context) + pkg).apply()
    }
}
