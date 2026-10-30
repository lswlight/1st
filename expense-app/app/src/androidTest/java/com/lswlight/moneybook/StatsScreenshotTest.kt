package com.lswlight.moneybook

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
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
        val samples = listOf(
            Triple(1, "월급", 4_886_697L), Triple(2, "스타벅스", 4_500L), Triple(3, "양천돈까스", 18_500L),
            Triple(5, "이마트", 87_300L), Triple(8, "APT-관리비", 269_140L), Triple(9, "GS25", 3_200L),
            Triple(12, "배달의민족", 27_000L), Triple(15, "쿠팡", 45_000L), Triple(18, "김밥천국", 7_500L),
            Triple(21, "NETFLIX.COM", 14_571L), Triple(23, "투썸플레이스", 6_800L), Triple(26, "주유소", 70_000L),
        )
        for ((day, memo, amount) in samples) {
            if (day > ym.lengthOfMonth()) continue
            db.save(Entry(
                kind = if (memo == "월급") Kind.INCOME else Kind.EXPENSE,
                amount = amount, memo = memo, card = if (memo == "월급") "" else listOf("하나카드", "MG새마을금고", "KB국민카드")[day % 3],
                time = ym.atDay(day).atTime(12, 0),
            ))
        }
        db.save(Entry(kind = Kind.EXPENSE, amount = 120_000, memo = "지난달 쇼핑", card = "하나카드", time = ym.minusMonths(1).atDay(10).atTime(12, 0)))
        db.save(Entry(kind = Kind.INCOME, amount = 4_800_000, memo = "월급", time = ym.minusMonths(1).atDay(25).atTime(9, 0)))

        for (name in listOf("main", "calendar", "graph", "cards")) {
            val tab = when (name) { "graph" -> StatsActivity.TAB_GRAPH; "cards" -> StatsActivity.TAB_CARDS; else -> StatsActivity.TAB_CALENDAR }
            val intent = if (name == "main") Intent(ctx, MainActivity::class.java)
            else Intent(ctx, StatsActivity::class.java).putExtra(StatsActivity.EXTRA_TAB, tab)
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
