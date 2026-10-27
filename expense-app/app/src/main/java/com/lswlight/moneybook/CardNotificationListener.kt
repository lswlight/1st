package com.lswlight.moneybook

import android.app.Notification
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.time.Duration

/**
 * 폰에 뜨는 알림 중 카드 승인 문자 / 카카오톡 알림톡 / 카드 앱 푸시를 골라 지출로 기록한다.
 * 사용자가 설정에서 "알림 접근"을 허용해야 시스템이 이 서비스를 실행한다.
 */
class CardNotificationListener : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName) return
        val trusted = isCardApp(pkg)
        if (!trusted && pkg !in MESSAGE_APPS) return

        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val text = notificationText(n)
        val posted = sbn.postTime.toLocalDateTime()
        val parsed = CardMessageParser.parse(text, posted, trusted)
        if (parsed == null) {
            DetectLog.add(this, pkg, "결제 문구 아님", text)
            return
        }

        // 문자 속 시각이 알림 시각과 크게 다르면 (오래된 문자 재표시 등) 알림 시각을 쓴다
        val time = parsed.time?.takeIf { Duration.between(it, posted).abs().toDays() < 7 } ?: posted
        val amount = if (parsed.isCancel) -parsed.amount else parsed.amount

        val db = LedgerDb.get(this)
        if (db.hasRecentAuto(amount, time)) {
            DetectLog.add(this, pkg, "중복이라 건너뜀 (3분 안에 같은 금액이 이미 기록됨)", text)
            return
        }
        db.save(
            Entry(
                kind = Kind.EXPENSE,
                amount = amount,
                memo = parsed.merchant,
                card = parsed.card,
                time = time,
                auto = true,
                raw = text,
            )
        )
        Log.i(TAG, "자동 기록: ${parsed.merchant} $amount ($pkg)")
        DetectLog.add(this, pkg, "기록됨: ${parsed.merchant} ${MainActivity.won(amount)}", text)
        main.post { LedgerDb.onChanged?.invoke() }
    }

    private fun notificationText(n: Notification): String {
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val conversation = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
        // 카카오톡 등 대화형 알림은 본문이 EXTRA_MESSAGES 에 들어 있고 EXTRA_TEXT 는 비거나 잘려 있을 수 있다
        @Suppress("DEPRECATION") // 새 API 는 Android 13 부터라 minSdk 26 에서는 이 함수를 쓴다
        val lastMessage = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
            ?.mapNotNull { (it as? Bundle)?.getCharSequence("text")?.toString() }
            ?.lastOrNull()
        val body = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: lastMessage
            ?: ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
            ?: ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        return listOf(title, conversation, body).filter { it.isNotBlank() }.distinct().joinToString("\n")
    }

    companion object {
        private const val TAG = "MoneyBook"

        /** 문자 앱과 카카오톡: 카드사 문구일 때만 기록한다. */
        val MESSAGE_APPS = setOf(
            "com.samsung.android.messaging",       // 삼성 메시지
            "com.google.android.apps.messaging",   // Google 메시지
            "com.android.mms",
            "com.android.messaging",
            "com.kakao.talk",                      // 카카오톡 (카드사 알림톡)
        )

        /** 결제 알림만 보내는 앱: 카드 단어가 없어도 결제 문구면 기록한다. */
        private val PAY_APPS = setOf(
            "viva.republica.toss",                 // 토스
            "com.kakaopay.app",                    // 카카오페이
            "com.kakaobank.channel",               // 카카오뱅크
            "nh.smart.nhallonepay",                // NH pay
            "kvp.jjy.MispAndroid320",              // BC 페이북
        )

        // 신한·KB·현대·삼성·롯데·우리 등 카드사 앱은 패키지 이름에 card 가 들어간다
        fun isCardApp(pkg: String) = pkg in PAY_APPS || pkg.contains("card", ignoreCase = true)
    }
}
