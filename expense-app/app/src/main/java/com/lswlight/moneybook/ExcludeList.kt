package com.lswlight.moneybook

import android.content.Context

/**
 * 자동 기록에서 뺄 상대 이름·단어 목록 (예: 내 이름, 가족 이름, 내 다른 계좌).
 * 은행 출금 알림은 체크카드 결제와 계좌 이체가 같은 모양이라, 이체 상대를 여기 넣어 걸러낸다.
 */
object ExcludeList {
    private const val PREFS = "exclude_list"
    private const val KEY = "terms"

    fun terms(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
            .lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun save(context: Context, terms: List<String>) {
        val clean = terms.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, clean.joinToString("\n")).apply()
    }

    fun add(context: Context, term: String) = save(context, terms(context) + term)

    /** 사용처(상대)에 목록의 단어가 들어 있으면 그 단어를, 없으면 null. */
    fun match(context: Context, merchant: String): String? =
        terms(context).firstOrNull { merchant.contains(it, ignoreCase = true) }
}
