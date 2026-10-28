package com.lswlight.moneybook

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 월간 결제 내역을 달력과 그래프로 보는 화면. 그래프는 외부 라이브러리 없이 Canvas 로 그린다. */
class StatsActivity : Activity() {

    private lateinit var db: LedgerDb
    private var month: YearMonth = YearMonth.now()
    private var showGraph = false

    private lateinit var monthLabel: TextView
    private lateinit var tabCalendar: Button
    private lateinit var tabGraph: Button
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = LedgerDb.get(this)
        intent.getStringExtra(EXTRA_MONTH)?.let { month = YearMonth.parse(it) }
        showGraph = intent.getBooleanExtra(EXTRA_GRAPH, false)
        title = "달력 · 그래프"
        setContentView(buildLayout())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildLayout(): View {
        val root = vertical().apply { setPadding(dp(16), dp(12), dp(16), dp(24)) }
        root.addView(horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(button("◀") { month = month.minusMonths(1); refresh() })
            monthLabel = text("", 20f, bold = true).apply { gravity = Gravity.CENTER }
            addView(monthLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(button("▶") { month = month.plusMonths(1); refresh() })
        })
        root.addView(horizontal().apply {
            tabCalendar = button("달력") { showGraph = false; refresh() }
            tabGraph = button("그래프") { showGraph = true; refresh() }
            addView(tabCalendar, weight())
            addView(tabGraph, weight())
        }, margins(top = 4))
        content = vertical()
        root.addView(content, margins(top = 8))
        return ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(root)
        }
    }

    private fun refresh() {
        monthLabel.text = "${month.year}년 ${month.monthValue}월"
        tabCalendar.alpha = if (showGraph) 0.5f else 1f
        tabGraph.alpha = if (showGraph) 1f else 0.5f

        val entries = db.month(month)
        val expense = entries.filter { it.kind == Kind.EXPENSE }
        val income = entries.filter { it.kind == Kind.INCOME }

        content.removeAllViews()
        content.addView(summary(income.sumOf { it.amount }, expense.sumOf { it.amount }, expense))
        if (showGraph) showGraphs(expense) else showCalendar(entries)
    }

    // ---------------------------------------------------------------- 달력

    private fun showCalendar(entries: List<Entry>) {
        val cal = MonthCalendarView(this).apply {
            month = this@StatsActivity.month
            expense = entries.filter { it.kind == Kind.EXPENSE }
                .groupBy { it.time.dayOfMonth }.mapValues { (_, v) -> v.sumOf { it.amount } }
            income = entries.filter { it.kind == Kind.INCOME }
                .groupBy { it.time.dayOfMonth }.mapValues { (_, v) -> v.sumOf { it.amount } }
            onDayClick = { day -> showDay(day, entries.filter { it.time.toLocalDate() == day }) }
        }
        content.addView(cal, margins(top = 12))
        content.addView(text("날짜를 누르면 그날 내역을 볼 수 있어요. 색이 진할수록 많이 쓴 날이에요.", 12f, Color.GRAY).apply {
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun showDay(day: LocalDate, list: List<Entry>) {
        val body = vertical().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        if (list.isEmpty()) body.addView(text("내역이 없어요.", 14f, Color.GRAY))
        for (e in list) {
            body.addView(horizontal().apply {
                setPadding(0, dp(6), 0, dp(6))
                addView(vertical().apply {
                    addView(text(e.memo, 15f))
                    addView(text(listOf(e.time.format(TIME), e.card).filter { it.isNotEmpty() }.joinToString(" · "), 12f, Color.GRAY))
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                val income = e.kind == Kind.INCOME
                addView(text((if (income) "+" else "-") + MainActivity.won(Math.abs(e.amount)), 15f,
                    if (income) INCOME_COLOR else EXPENSE_COLOR, bold = true))
            })
        }
        val spent = list.filter { it.kind == Kind.EXPENSE }.sumOf { it.amount }
        AlertDialog.Builder(this)
            .setTitle("${day.format(DAY_TITLE)} · 지출 ${MainActivity.won(spent)}")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("닫기", null)
            .show()
    }

    // ---------------------------------------------------------------- 그래프

    private fun showGraphs(spent: List<Entry>) {
        // 1) 일별 지출
        section("일별 지출")
        val daily = LongArray(month.lengthOfMonth())
        spent.forEach { daily[it.time.dayOfMonth - 1] += it.amount }
        content.addView(DailyBarChart(this).apply {
            values = daily
            today = if (YearMonth.now() == month) LocalDate.now().dayOfMonth else 0
        }, margins(top = 4))

        // 2) 최근 6개월 수입·지출
        section("최근 6개월 수입 · 지출")
        val months = (5 downTo 0).map { month.minusMonths(it.toLong()) }
        val sums = months.map { ym ->
            val list = db.month(ym)
            list.filter { it.kind == Kind.INCOME }.sumOf { it.amount } to list.filter { it.kind == Kind.EXPENSE }.sumOf { it.amount }
        }
        content.addView(MonthlyBarChart(this).apply {
            labels = months.map { "${it.monthValue}월" }
            income = sums.map { it.first }
            expense = sums.map { it.second }
        }, margins(top = 4))
        content.addView(horizontal().apply {
            gravity = Gravity.CENTER
            addView(text("■ 수입   ", 12f, INCOME_COLOR))
            addView(text("■ 지출", 12f, EXPENSE_COLOR))
        })

        // 3) 카드별 / 사용처별
        section("카드별 지출")
        ranking(spent.groupBy { it.card.ifEmpty { "직접 입력" } }.mapValues { (_, v) -> v.sumOf { it.amount } })
        section("많이 쓴 곳 TOP 5")
        ranking(spent.groupBy { it.memo.substringBefore(" (") }.mapValues { (_, v) -> v.sumOf { it.amount } }, limit = 5)
    }

    /** 가로 막대 순위표. */
    private fun ranking(totals: Map<String, Long>, limit: Int = 8) {
        val rows = totals.filter { it.value > 0 }.entries.sortedByDescending { it.value }.take(limit)
        if (rows.isEmpty()) {
            content.addView(text("이번 달 지출이 없어요.", 13f, Color.GRAY))
            return
        }
        val max = rows.first().value.toFloat()
        for ((name, v) in rows) {
            content.addView(vertical().apply {
                setPadding(0, dp(5), 0, dp(5))
                addView(horizontal().apply {
                    addView(text(name, 14f).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(text(MainActivity.won(v), 14f, bold = true))
                })
                addView(horizontal().apply {
                    addView(View(this@StatsActivity).apply { background = rounded(EXPENSE_COLOR, 4) },
                        LinearLayout.LayoutParams(0, dp(8), v / max))
                    addView(View(this@StatsActivity), LinearLayout.LayoutParams(0, dp(8), 1f - v / max + 0.0001f))
                }, margins(top = 3))
            })
        }
    }

    private fun section(title: String) {
        content.addView(text(title, 16f, bold = true).apply { setPadding(0, dp(18), 0, dp(4)) })
    }

    private fun summary(income: Long, expense: Long, expenseList: List<Entry>): View {
        val days = if (YearMonth.now() == month) LocalDate.now().dayOfMonth else month.lengthOfMonth()
        val perDay = if (days > 0) expense / days else 0
        return vertical().apply {
            background = rounded(Color.parseColor("#F2F4F7"), 12)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            fun line(label: String, value: String, color: Int) = addView(horizontal().apply {
                setPadding(0, dp(2), 0, dp(2))
                addView(text(label, 14f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(text(value, 14f, color, bold = true))
            })
            line("수입", MainActivity.won(income), INCOME_COLOR)
            line("지출 (${expenseList.size}건)", MainActivity.won(expense), EXPENSE_COLOR)
            line("하루 평균 지출", MainActivity.won(perDay), Color.DKGRAY)
        }
    }

    // ---------------------------------------------------------------- 도우미

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun horizontal() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun text(s: String, size: Float, color: Int = Color.BLACK, bold: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun weight() = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
    private fun margins(top: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_MONTH = "month"
        const val EXTRA_GRAPH = "graph"
        val INCOME_COLOR = Color.parseColor("#1E6FD9")
        val EXPENSE_COLOR = Color.parseColor("#D93025")
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")
        private val DAY_TITLE = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)

        /** 달력 칸처럼 좁은 곳용 금액 표기: 12,500 → 1.3만, 1,234,000 → 123만 */
        fun compact(v: Long): String = when {
            v >= 1_000_000 -> "${v / 10_000}만"
            v >= 10_000 -> "%.1f만".format(v / 10_000.0).replace(".0만", "만")
            else -> "%,d".format(v)
        }
    }
}

/** 한 달 달력. 칸마다 그날 지출(빨강)과 수입 표시(파랑 점), 지출이 많을수록 칸 색이 진하다. */
class MonthCalendarView(context: Context) : View(context) {
    var month: YearMonth = YearMonth.now()
    var expense: Map<Int, Long> = emptyMap()
    var income: Map<Int, Long> = emptyMap()
    var onDayClick: ((LocalDate) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val headerH = 28 * density
    private val cellH = 62 * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    // 일요일 시작: 1일이 들어갈 칸 번호(0~6)
    private val offset get() = month.atDay(1).dayOfWeek.value % 7
    private val rows get() = (offset + month.lengthOfMonth() + 6) / 7

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (headerH + rows * cellH).toInt() + 2)
    }

    override fun onDraw(canvas: Canvas) {
        val cellW = width / 7f
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 12 * density
        paint.typeface = Typeface.DEFAULT_BOLD
        listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, d ->
            paint.color = when (i) { 0 -> Color.parseColor("#D93025"); 6 -> Color.parseColor("#1E6FD9"); else -> Color.GRAY }
            canvas.drawText(d, cellW * i + cellW / 2, headerH * 0.7f, paint)
        }

        val max = (expense.values.maxOrNull() ?: 0L).coerceAtLeast(1L)
        val today = LocalDate.now()
        for (day in 1..month.lengthOfMonth()) {
            val idx = offset + day - 1
            val col = idx % 7
            val row = idx / 7
            rect.set(cellW * col + 2, headerH + cellH * row + 2, cellW * (col + 1) - 2, headerH + cellH * (row + 1) - 2)

            // 칸 배경: 지출이 클수록 진한 빨강
            val spent = expense[day] ?: 0L
            paint.style = Paint.Style.FILL
            paint.color = if (spent > 0) {
                val a = (40 + 170 * (spent.toFloat() / max)).toInt().coerceIn(40, 210)
                Color.argb(a, 0xF2, 0x6B, 0x5B)
            } else Color.parseColor("#F5F6F8")
            canvas.drawRoundRect(rect, 8 * density, 8 * density, paint)

            // 오늘 테두리
            if (month.atDay(day) == today) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2 * density
                paint.color = Color.parseColor("#1E6FD9")
                canvas.drawRoundRect(rect, 8 * density, 8 * density, paint)
                paint.style = Paint.Style.FILL
            }

            // 날짜
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = 12 * density
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.color = when (month.atDay(day).dayOfWeek) {
                DayOfWeek.SUNDAY -> Color.parseColor("#D93025")
                DayOfWeek.SATURDAY -> Color.parseColor("#1E6FD9")
                else -> Color.DKGRAY
            }
            canvas.drawText("$day", rect.left + 5 * density, rect.top + 15 * density, paint)

            // 수입 있는 날: 파란 점
            if ((income[day] ?: 0L) > 0) {
                paint.color = Color.parseColor("#1E6FD9")
                canvas.drawCircle(rect.right - 7 * density, rect.top + 10 * density, 3.5f * density, paint)
            }

            // 지출 금액
            if (spent != 0L) {
                paint.textAlign = Paint.Align.RIGHT
                paint.textSize = 11 * density
                paint.color = Color.parseColor("#7A1A12")
                canvas.drawText(StatsActivity.compact(spent), rect.right - 4 * density, rect.bottom - 7 * density, paint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return true
        if (event.action != MotionEvent.ACTION_UP) return super.onTouchEvent(event)
        val col = (event.x / (width / 7f)).toInt()
        val row = ((event.y - headerH) / cellH).toInt()
        val day = row * 7 + col - offset + 1
        if (event.y > headerH && day in 1..month.lengthOfMonth()) {
            performClick()
            onDayClick?.invoke(month.atDay(day))
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

/** 일별 지출 막대 그래프 + 하루 평균 점선. */
class DailyBarChart(context: Context) : View(context) {
    var values: LongArray = LongArray(0)
    var today: Int = 0

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (190 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val top = 22 * density
        val bottom = height - 20 * density
        val left = 4 * density
        val slot = (width - left * 2) / values.size
        val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()

        // 바닥선
        paint.color = Color.parseColor("#E3E7EE")
        paint.strokeWidth = 1 * density
        canvas.drawLine(left, bottom, width - left, bottom, paint)

        // 막대
        values.forEachIndexed { i, v ->
            if (v <= 0) return@forEachIndexed
            val h = (bottom - top) * (v / max)
            paint.color = if (i + 1 == today) Color.parseColor("#1E6FD9") else Color.parseColor("#F26B5B")
            canvas.drawRoundRect(RectF(left + slot * i + slot * 0.15f, bottom - h, left + slot * (i + 1) - slot * 0.15f, bottom),
                2 * density, 2 * density, paint)
        }

        // 날짜 눈금 (1, 5, 10, 15, 20, 25, 말일)
        paint.textSize = 10 * density
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.GRAY
        (listOf(1, 5, 10, 15, 20, 25, values.size)).distinct().forEach { d ->
            canvas.drawText("$d", left + slot * (d - 0.5f), height - 5 * density, paint)
        }

        // 하루 평균 (지출 있는 날 기준이 아니라 달력 일수 기준)
        val days = if (today > 0) today else values.size
        val avg = values.take(days).sum().toFloat() / days
        if (avg > 0) {
            val y = bottom - (bottom - top) * (avg / max)
            paint.color = Color.DKGRAY
            paint.strokeWidth = 1.2f * density
            paint.pathEffect = DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
            canvas.drawLine(left, y, width - left, y, paint)
            paint.pathEffect = null
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText("평균 ${MainActivity.won(avg.toLong())}", width - left, y - 4 * density, paint)
        }

        // 최대값
        paint.textAlign = Paint.Align.LEFT
        paint.color = Color.GRAY
        canvas.drawText("최대 ${MainActivity.won(max.toLong())}", left, 12 * density, paint)
    }
}

/** 월별 수입(파랑)·지출(빨강) 묶음 막대 그래프. */
class MonthlyBarChart(context: Context) : View(context) {
    var labels: List<String> = emptyList()
    var income: List<Long> = emptyList()
    var expense: List<Long> = emptyList()

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (200 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (labels.isEmpty()) return
        val top = 18 * density
        val bottom = height - 22 * density
        val slot = width.toFloat() / labels.size
        val max = ((income + expense).maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
        val barW = slot * 0.28f

        paint.color = Color.parseColor("#E3E7EE")
        paint.strokeWidth = 1 * density
        canvas.drawLine(0f, bottom, width.toFloat(), bottom, paint)

        paint.textAlign = Paint.Align.CENTER
        labels.forEachIndexed { i, label ->
            val cx = slot * i + slot / 2
            fun bar(v: Long, x: Float, color: Int) {
                if (v <= 0) return
                val h = (bottom - top) * (v / max)
                paint.color = color
                canvas.drawRoundRect(RectF(x, bottom - h, x + barW, bottom), 3 * density, 3 * density, paint)
                paint.color = Color.DKGRAY
                paint.textSize = 9 * density
                canvas.drawText(StatsActivity.compact(v), x + barW / 2, bottom - h - 3 * density, paint)
            }
            bar(income.getOrElse(i) { 0 }, cx - barW - 1 * density, Color.parseColor("#1E6FD9"))
            bar(expense.getOrElse(i) { 0 }, cx + 1 * density, Color.parseColor("#F26B5B"))
            paint.color = Color.GRAY
            paint.textSize = 11 * density
            canvas.drawText(label, cx, height - 6 * density, paint)
        }
    }
}
