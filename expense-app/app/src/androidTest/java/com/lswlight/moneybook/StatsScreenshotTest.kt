package com.lswlight.moneybook

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth

/**
 * 예시 내역을 넣고 달력·그래프 화면을 띄워 화면을 캡처한다 (CI 에서 레이아웃 확인용).
 * 캡처는 /data/local/tmp/stats-*.png 에 남고, CI 가 가져가 로그로 출력한다.
 */
@RunWith(AndroidJUnit4::class)
class StatsScreenshotTest {

    @Test
    fun captureCalendarAndGraph() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val ctx = instr.targetContext
        val db = LedgerDb.get(ctx)
        val ym = YearMonth.now()
        val today = LocalDate.now()
        // 이번 달 1일부터 오늘까지 고르게 퍼지도록 날짜를 정한다 (미래 날짜가 맨 위에 보이지 않게)
        val samples = listOf(
            Triple(0.00, "월급", 4_886_697L), Triple(0.05, "스타벅스", 4_500L), Triple(0.12, "양천돈까스", 18_500L),
            Triple(0.20, "이마트", 87_300L), Triple(0.28, "APT-관리비", 269_140L), Triple(0.36, "GS25", 3_200L),
            Triple(0.45, "배달의민족", 27_000L), Triple(0.55, "쿠팡", 45_000L), Triple(0.65, "김밥천국", 7_500L),
            Triple(0.75, "NETFLIX.COM", 14_571L), Triple(0.88, "투썸플레이스", 6_800L), Triple(1.00, "미소가득주유소", 70_000L),
        )
        val lastDay = maxOf(today.dayOfMonth, 2)
        for ((i, sample) in samples.withIndex()) {
            val (pos, memo, amount) = sample
            val day = 1 + Math.round(pos * (lastDay - 1)).toInt()
            db.save(Entry(
                kind = if (memo == "월급") Kind.INCOME else Kind.EXPENSE,
                amount = amount, memo = memo, card = if (memo == "월급") "" else listOf("하나카드", "MG새마을금고", "KB국민카드")[i % 3],
                time = ym.atDay(day).atTime(9 + i % 10, 10 + i * 3), auto = memo != "월급",
            ))
        }
        // 카드 혜택(수입)과 취소 내역도 하나씩
        db.save(Entry(kind = Kind.INCOME, amount = 2_000, memo = "카드 혜택: 스타벅스 할인", card = "하나카드", time = ym.atDay(1 + Math.round(0.05 * (lastDay - 1)).toInt()).atTime(13, 0), auto = true))

        // 알림 접근이 허용돼 있으면 감지 서비스가 다시 연결될 때까지 잠깐 기다린다 (스토어 캡처에 '연결 안 됨'이 보이지 않게)
        android.service.notification.NotificationListenerService.requestRebind(
            android.content.ComponentName(ctx, CardNotificationListener::class.java)
        )
        repeat(20) { if (!CardNotificationListener.connected) Thread.sleep(500) }
        db.save(Entry(kind = Kind.EXPENSE, amount = 120_000, memo = "지난달 쇼핑", card = "하나카드", time = ym.minusMonths(1).atDay(10).atTime(12, 0)))
        db.save(Entry(kind = Kind.INCOME, amount = 4_800_000, memo = "월급", time = ym.minusMonths(1).atDay(25).atTime(9, 0)))

        for (name in listOf("main", "calendar", "graph", "cards", "family")) {
            val tab = when (name) { "graph" -> StatsActivity.TAB_GRAPH; "cards" -> StatsActivity.TAB_CARDS; else -> StatsActivity.TAB_CALENDAR }
            val intent = when (name) {
                "main" -> Intent(ctx, MainActivity::class.java)
                "family" -> Intent(ctx, FamilyActivity::class.java)
                else -> Intent(ctx, StatsActivity::class.java).putExtra(StatsActivity.EXTRA_TAB, tab)
            }
            val act = instr.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instr.waitForIdleSync()
            Thread.sleep(1500)
            instr.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/stats-$name.png").use { pfd ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes()
            }
            act.finish()
            instr.waitForIdleSync()
        }
    }
}
