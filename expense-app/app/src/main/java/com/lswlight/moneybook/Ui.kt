package com.lswlight.moneybook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

/**
 * '파스텔 라이프' 디자인의 색과 공통 부품 (둥근 버튼, 카드 배경, 아래쪽 탭, 사용처 아이콘).
 * 화면마다 같은 모양을 쓰도록 여기 모아 둔다.
 */
object Ui {
    val BG = Color.parseColor("#FBF7F0")       // 크림색 바탕
    val CARD = Color.WHITE
    val INK = Color.parseColor("#2D2A26")      // 본문 글자
    val SUB = Color.parseColor("#9C9283")      // 보조 글자
    val GREEN = Color.parseColor("#2F9E6E")    // 포인트 (수입·남은 돈)
    val CORAL = Color.parseColor("#E0705C")    // 지출
    val LINE = Color.parseColor("#F0E8DB")
    val PILL = Color.parseColor("#EFE7DA")
    val MINT = Color.parseColor("#DFF3E8")
    val PEACH = Color.parseColor("#FFE5DF")
    val LAVENDER = Color.parseColor("#E6ECFF")
    val WARN = Color.parseColor("#FFF1DC")

    const val TAB_HOME = 0
    const val TAB_CALENDAR = 1
    const val TAB_CARDS = 2
    const val TAB_FAMILY = 3
    const val TAB_SETTINGS = 4
    const val EXTRA_OPEN_SETTINGS = "open_settings"

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun round(c: Context, color: Int, radiusDp: Int = 22) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(c, radiusDp).toFloat()
    }

    /** 둥근 알약 모양 버튼. 눌렀을 때 물결 효과가 난다. */
    fun pill(c: Context, label: String, bg: Int = PILL, fg: Int = INK, size: Float = 14f, onClick: () -> Unit) =
        TextView(c).apply {
            text = label
            textSize = size
            setTextColor(fg)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10))
            background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#22000000")), round(c, bg, 99), null)
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** 화면 위쪽 제목 줄 (← 돌아가기 + 제목). */
    fun header(a: Activity, title: String): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(a, 8), dp(a, 8), dp(a, 16), dp(a, 4))
        addView(TextView(a).apply {
            text = "←"
            textSize = 22f
            setTextColor(INK)
            setPadding(dp(a, 10), dp(a, 4), dp(a, 14), dp(a, 4))
            setOnClickListener { a.finish() }
        })
        addView(TextView(a).apply {
            text = title
            textSize = 20f
            setTextColor(INK)
            typeface = Typeface.DEFAULT_BOLD
        })
    }

    /**
     * 아래쪽 탭 (홈 · 달력 · 카드 · 가족 · 설정).
     * [local] 이 true 를 돌려주면 지금 화면 안에서 처리한 것이고, 아니면 해당 화면을 연다.
     */
    fun tabBar(a: Activity, selected: Int, month: String, local: (Int) -> Boolean = { false }): View =
        LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(CARD)
            setPadding(0, dp(a, 6), 0, dp(a, 8))
            val items = listOf("⌂" to "홈", "▦" to "달력", "▭" to "카드", "♡" to "가족", "⚙" to "설정")
            items.forEachIndexed { i, (icon, label) ->
                val color = if (i == selected) GREEN else Color.parseColor("#B3A897")
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    isClickable = true
                    background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#14000000")), null, null)
                    addView(TextView(a).apply { text = icon; textSize = 19f; setTextColor(color); gravity = Gravity.CENTER })
                    addView(TextView(a).apply {
                        text = label; textSize = 11f; setTextColor(color); gravity = Gravity.CENTER
                        if (i == selected) typeface = Typeface.DEFAULT_BOLD
                    })
                    setOnClickListener { if (i != selected && !local(i)) open(a, i, month) }
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
        }.let { bar ->
            // 위쪽에 얇은 구분선
            LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(View(a).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(a, 1)))
                addView(bar)
            }
        }

    private fun open(a: Activity, tab: Int, month: String) {
        val intent = when (tab) {
            TAB_HOME -> Intent(a, MainActivity::class.java)
            TAB_SETTINGS -> Intent(a, MainActivity::class.java).putExtra(EXTRA_OPEN_SETTINGS, true)
            TAB_FAMILY -> Intent(a, FamilyActivity::class.java)
            TAB_CARDS -> Intent(a, StatsActivity::class.java).putExtra(StatsActivity.EXTRA_TAB, StatsActivity.TAB_CARDS)
            else -> Intent(a, StatsActivity::class.java).putExtra(StatsActivity.EXTRA_TAB, StatsActivity.TAB_CALENDAR)
        }.putExtra(StatsActivity.EXTRA_MONTH, month)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        a.startActivity(intent)
        if (a !is MainActivity) a.finish()
        @Suppress("DEPRECATION")
        a.overridePendingTransition(0, 0)
    }

    /**
     * Android 15+ (대상 API 35 이상) 는 화면이 상태 표시줄·내비게이션 바 아래까지 그려지므로(edge-to-edge),
     * 내용이 가려지지 않게 시스템 바·키보드 크기만큼 여백을 준다. setContentView 다음에 부른다.
     */
    fun fitSystemBars(a: Activity) {
        if (Build.VERSION.SDK_INT < 35) return
        val root: View = a.findViewById(android.R.id.content) ?: return
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        root.requestApplyInsets()
    }

    /** 탭 화면의 바탕: 스크롤되는 내용 + 아래쪽 탭. */
    fun screen(a: Activity, content: View, tabs: View): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BG)
        addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        addView(tabs, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    /** 사용처 이름으로 고른 아이콘과 바탕색. */
    fun category(memo: String, kind: Kind, amount: Long): Pair<String, Int> {
        val m = memo.lowercase()
        fun has(vararg k: String) = k.any { m.contains(it.lowercase()) }
        return when {
            amount < 0 -> "↩️" to Color.parseColor("#EFEFEF")
            memo.startsWith("카드 혜택") -> "🎁" to LAVENDER
            kind == Kind.INCOME -> "💰" to MINT
            memo.contains("추정)") -> "🌐" to Color.parseColor("#F1E8FF")
            has("카페", "커피", "스타벅스", "투썸", "이디야", "메가커피", "빽다방", "컴포즈", "폴바셋", "할리스", "coffee") -> "☕" to Color.parseColor("#FFF1E6")
            has("주유", "오일", "칼텍스", "에너지", "s-oil", "에쓰오일") -> "⛽" to MINT
            has("관리비", "apt", "아파트", "월세") -> "🏠" to Color.parseColor("#FFF6D9")
            has("통신", "skt", "kt ", "유플러스", "lg u") -> "📱" to LAVENDER
            has("의원", "병원", "약국", "치과", "한의원") -> "🏥" to Color.parseColor("#FFE9EE")
            has("택시", "카카오t", "버스", "지하철", "코레일", "srt", "주차", "고속") -> "🚌" to Color.parseColor("#E6F4FF")
            has("넷플릭스", "netflix", "youtube", "유튜브", "멜론", "디즈니", "티빙", "웨이브", "spotify") -> "🎬" to Color.parseColor("#F1E8FF")
            has("마트", "홈플러스", "코스트코", "gs25", "cu ", "세븐일레븐", "편의점", "쿠팡", "plaza", "다이소") -> "🛒" to Color.parseColor("#FFF6D9")
            has("식당", "김밥", "돈까스", "낙지", "치킨", "피자", "버거", "맥도날드", "롯데리아", "배달", "요기요", "국밥", "분식", "고기", "횟집") -> "🍽" to PEACH
            else -> "💳" to Color.parseColor("#F3EEE6")
        }
    }
}
