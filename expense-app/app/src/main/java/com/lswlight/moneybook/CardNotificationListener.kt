package com.lswlight.moneybook

import android.app.Notification
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.Executors

/**
 * 폰에 뜨는 알림 중 카드 승인 문자 / 카카오톡 알림톡 / 카드 앱 푸시를 골라 지출로 기록한다.
 * 사용자가 설정에서 "알림 접근"을 허용해야 시스템이 이 서비스를 실행한다.
 */
class CardNotificationListener : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())

    // 저장과 환율 받기는 화면 스레드 밖에서 한 건씩 차례로 처리한다 (중복 확인이 꼬이지 않게)
    private val worker = Executors.newSingleThreadExecutor()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName) return
        // 앱 이름에 "카드" 가 들어가면 (예: 하나Pay 의 "하나카드") 카드사 앱,
        // "은행·뱅크·금고" 등이 들어가면 (예: MG새마을금고) 은행 앱으로 보고 출금 알림을 기록한다
        val label = appLabel(pkg)
        val trusted = isCardApp(pkg) || label.contains("카드") || BANK_LABEL.containsMatchIn(label)
        if (!trusted && pkg !in MESSAGE_APPS) return

        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        // 카드사 앱 알림은 제목이 "결제" 뿐이라, 앱 이름을 첫 줄로 붙여 카드사를 알 수 있게 한다
        val text = notificationText(n).let { if (label.contains("카드")) "$label\n$it" else it }
        val posted = sbn.postTime.toLocalDateTime()
        worker.execute {
            try {
                record(pkg, text, posted, trusted, label)
            } catch (e: Throwable) {
                // 처리 중 오류가 나도 조용히 사라지거나 앱이 죽지 않게 감지 기록에 남긴다
                Log.e(TAG, "알림 처리 실패", e)
                DetectLog.add(this, pkg, "처리 오류: ${e.javaClass.simpleName} ${e.message.orEmpty()}", text)
            }
        }
    }

    private fun record(pkg: String, text: String, posted: LocalDateTime, trusted: Boolean, label: String) {
        val fee = ExchangeRates.feePercent(this)
        var parsed = CardMessageParser.parse(text, posted, trusted, ExchangeRates.cached(this), fee)
        // 해외 결제인데 오늘 환율을 아직 안 받았으면 받아서 다시 계산한다 (하루 한 번, 실패하면 저장된 값 사용)
        if (parsed != null && parsed.foreign.isNotEmpty() && ExchangeRates.isStale(this) && ExchangeRates.refresh(this)) {
            parsed = CardMessageParser.parse(text, posted, trusted, ExchangeRates.cached(this), fee)
        }
        if (parsed == null) {
            // 금액처럼 보이는 게 있는 알림만 남긴다 (일반 카톡 대화로 기록이 금방 밀려나지 않게)
            if (MONEY_LIKE.containsMatchIn(text)) DetectLog.add(this, pkg, "결제 문구 아님", text)
            return
        }

        // 기록 제외 목록에 있는 상대 (내 이름·가족·내 다른 계좌 등으로의 이체)
        ExcludeList.match(this, parsed.merchant)?.let { term ->
            DetectLog.add(this, pkg, "제외 목록($term)이라 건너뜀", text)
            return
        }

        // 문자 속 시각이 알림 시각과 크게 다르면 (오래된 문자 재표시 등) 알림 시각을 쓴다
        val time = parsed.time?.takeIf { Duration.between(it, posted).abs().toDays() < 7 } ?: posted
        // 혜택(할인·캐시백)은 수입, 취소는 음수 지출, 나머지는 지출
        val kind = if (parsed.isBenefit) Kind.INCOME else Kind.EXPENSE
        val amount = if (parsed.isCancel) -parsed.amount else parsed.amount

        val db = LedgerDb.get(this)
        if (db.hasRecentAuto(kind, amount, time)) {
            DetectLog.add(this, pkg, "중복이라 건너뜀 (3분 안에 같은 금액이 이미 기록됨)", text)
            return
        }
        db.save(
            Entry(
                kind = kind,
                amount = amount,
                memo = parsed.memo(),
                // 문구에 카드사가 없으면 알림 보낸 앱 이름 (예: MG새마을금고)
                card = parsed.card.ifEmpty { if (trusted) label else "" },
                time = time,
                auto = true,
                raw = text,
            )
        )
        Log.i(TAG, "자동 기록: ${parsed.merchant} $amount ($pkg)")
        DetectLog.add(this, pkg, "기록됨: ${parsed.memo()} ${MainActivity.won(amount)}", text)
        main.post { LedgerDb.onChanged?.invoke() }
    }

    override fun onListenerConnected() {
        connected = true
    }

    // 앱 업데이트 등으로 시스템과 연결이 끊기면 다시 붙여 달라고 요청한다
    override fun onListenerDisconnected() {
        connected = false
        requestRebind(ComponentName(this, CardNotificationListener::class.java))
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        ""
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

        private val BANK_LABEL = Regex("""은행|뱅크|뱅킹|금고|신협|우체국|원큐""")

        private val MONEY_LIKE = Regex("""\d\s*원|${CardMessageParser.CURRENCIES.joinToString("|")}""")

        /** 시스템이 이 서비스에 알림을 보내 주는 중인지 (화면의 상태 표시용). */
        @Volatile var connected = false

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
