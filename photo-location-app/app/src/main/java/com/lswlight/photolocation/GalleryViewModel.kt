package com.lswlight.photolocation

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

data class PhotoGroup(
    val name: String,
    val photos: List<Photo>,
    /** 지도 열기용 대표 좌표 (위치 없는 그룹은 null) */
    val centerLat: Double?,
    val centerLng: Double?,
)

data class Progress(val message: String, val done: Int, val total: Int)

data class UiState(
    val progress: Progress? = null,
    val level: GroupLevel = GroupLevel.LIFE,
    val groups: List<PhotoGroup> = emptyList(),
    val totalPhotos: Int = 0,
    val locatedPhotos: Int = 0,
    val geocoderMissing: Boolean = false,
    val loaded: Boolean = false,
    /** 생활권 분류에 쓰는 집/회사 위치 설명. null 이면 찾지 못함 */
    val homeText: String? = null,
    val workText: String? = null,
    val anchorsManual: Boolean = false,
    /** 사진을 못 찾았을 때 보여줄 진단 정보 */
    val diagnostics: String = "",
)

const val NO_LOCATION = "위치 정보 없음"
const val UNRESOLVED = "주소 확인 실패 (인터넷 연결 후 새로고침)"
const val HOME = "🏠 집"
const val WORK = "🏢 회사"
const val NEIGHBORHOOD = "🏘️ 동네"
const val OUTING = "🚶 기타 외출"

/** 이보다 사진이 적은 외출은 여행으로 따로 만들지 않고 '기타 외출'에 모은다. */
private const val MIN_TRIP_PHOTOS = 3

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = PhotoRepository(app)
    private val namer = LocationNamer(app)
    private val anchorStore = AnchorStore(app)
    private var home: Anchor? = null
    private var work: Anchor? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var photos: List<Photo> = emptyList()
    private var scanJob: Job? = null
    private var pendingJob: Job? = null

    /** 마지막 스캔 이후 사진이 추가·삭제됐는지 */
    private var dirty = false
    /** 앱 화면이 보이고 있는지 */
    private var resumed = false

    // 카메라로 새로 찍거나 지운 사진을 감지한다.
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            dirty = true
            if (resumed) scheduleScan()
        }
    }

    init {
        app.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
        )
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
    }

    /** 화면으로 돌아왔을 때: 그사이 사진이 바뀌었으면 다시 스캔한다. */
    fun onResume() {
        resumed = true
        if (dirty) scheduleScan()
    }

    fun onPause() {
        resumed = false
    }

    // 사진 여러 장을 연속으로 찍을 때 매번 스캔하지 않도록 잠시 모아서 한 번만 실행한다.
    private fun scheduleScan() {
        pendingJob?.cancel()
        pendingJob = viewModelScope.launch {
            delay(1500)
            scan()
        }
    }

    fun scan() {
        // 스캔 중에 들어온 변경은 dirty 로 남겨 두었다가 끝난 뒤 다시 처리한다.
        if (scanJob?.isActive == true) return
        dirty = false
        scanJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                photos = repository.loadPhotos { done, total ->
                    _state.update { it.copy(progress = Progress("사진 위치 읽는 중", done, total)) }
                }
                if (namer.isAvailable) {
                    val keys = photos.filter { it.hasLocation }
                        .map { namer.cellKey(it.lat!!, it.lng!!) }
                        .toSet()
                    namer.resolve(keys) { done, total ->
                        _state.update { it.copy(progress = Progress("주소 찾는 중", done, total)) }
                    }
                }
            }
            _state.update {
                it.copy(
                    progress = null,
                    loaded = true,
                    totalPhotos = photos.size,
                    locatedPhotos = photos.count { p -> p.hasLocation },
                    geocoderMissing = !namer.isAvailable,
                    diagnostics = diagnostics(),
                )
            }
            refreshGroups()
            if (dirty && resumed) scheduleScan()
        }
    }

    private fun diagnostics(): String {
        val app = getApplication<Application>()
        fun has(p: String) = if (app.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED) "O" else "X"
        return buildString {
            append("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · ")
            append("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
            append("권한: 사진전체 ${has("android.permission.READ_MEDIA_IMAGES")}")
            append(" / 선택사진 ${has("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")}")
            append(" / 저장소 ${has("android.permission.READ_EXTERNAL_STORAGE")}")
            append(" / 사진위치 ${has("android.permission.ACCESS_MEDIA_LOCATION")}\n")
            append("조회: ${repository.diagnostics}")
        }
    }

    fun setLevel(level: GroupLevel) {
        _state.update { it.copy(level = level, groups = buildGroups(level)) }
    }

    /** 사진 위치를 집 또는 회사로 직접 지정한다. */
    fun setHome(photo: Photo) = setAnchor("home", photo)
    fun setWork(photo: Photo) = setAnchor("work", photo)

    private fun setAnchor(name: String, photo: Photo) {
        if (!photo.hasLocation) return
        anchorStore.set(name, photo.lat!!, photo.lng!!)
        refreshGroups()
    }

    /** 직접 지정한 집/회사를 지우고 자동 감지로 되돌린다. */
    fun resetAnchors() {
        anchorStore.clear()
        refreshGroups()
    }

    private fun refreshGroups() {
        home = anchorStore.get("home") ?: LifeClassifier.detectHome(photos)
        work = anchorStore.get("work") ?: LifeClassifier.detectWork(photos, home)
        _state.update {
            it.copy(
                groups = buildGroups(it.level),
                homeText = home?.let { a -> describe(a) },
                workText = work?.let { a -> describe(a) },
                anchorsManual = home?.manual == true || work?.manual == true,
            )
        }
    }

    private fun describe(anchor: Anchor): String {
        val area = namer.cached(namer.cellKey(anchor.lat, anchor.lng))?.district ?: "주소 미확인"
        return "$area (${if (anchor.manual) "직접 지정" else "자동 감지"})"
    }

    private fun buildGroups(level: GroupLevel): List<PhotoGroup> {
        if (level == GroupLevel.LIFE) return buildLifeGroups()
        val byName = LinkedHashMap<String, MutableList<Photo>>()
        for (photo in photos) {
            val name = if (!photo.hasLocation) {
                NO_LOCATION
            } else {
                namer.cached(namer.cellKey(photo.lat!!, photo.lng!!))?.nameAt(level) ?: UNRESOLVED
            }
            byName.getOrPut(name) { mutableListOf() } += photo
        }
        val special = setOf(NO_LOCATION, UNRESOLVED)
        val placed = byName.filterKeys { it !in special }.map { (name, list) -> toGroup(name, list) }
            .sortedByDescending { it.photos.size }
        val rest = listOf(UNRESOLVED, NO_LOCATION).mapNotNull { name ->
            byName[name]?.let { toGroup(name, it) }
        }
        return placed + rest
    }

    private fun buildLifeGroups(): List<PhotoGroup> {
        val byKind = photos.filter { it.hasLocation }
            .groupBy { LifeClassifier.kindOf(it, home, work) }

        val trips = mutableListOf<PhotoGroup>()
        val outings = mutableListOf<Photo>()
        val usedNames = HashSet<String>()
        // 최신 여행이 위로 오도록 역순
        for (trip in LifeClassifier.splitTrips(byKind[LifeKind.AWAY].orEmpty()).asReversed()) {
            if (trip.size < MIN_TRIP_PHOTOS) {
                outings += trip
                continue
            }
            var name = tripName(trip)
            var n = 2
            while (!usedNames.add(name)) name = "${tripName(trip)} ($n)".also { n++ }
            trips += toGroup(name, trip)
        }

        val fixed = listOf(
            HOME to byKind[LifeKind.HOME],
            WORK to byKind[LifeKind.WORK],
            NEIGHBORHOOD to byKind[LifeKind.NEIGHBORHOOD],
        ).mapNotNull { (name, list) -> list?.let { toGroup(name, it) } }

        val rest = listOfNotNull(
            outings.takeIf { it.isNotEmpty() }?.let { list ->
                toGroup(OUTING, list.sortedByDescending { it.dateTaken })
            },
            photos.filter { !it.hasLocation }.takeIf { it.isNotEmpty() }?.let { toGroup(NO_LOCATION, it) },
        )
        return fixed + trips + rest
    }

    /** 예: ✈️ 2025년 8월 강릉시 외 2곳 (3일) */
    private fun tripName(trip: List<Photo>): String {
        val areas = trip.mapNotNull { namer.cached(namer.cellKey(it.lat!!, it.lng!!))?.district }
            .groupingBy { it.substringAfterLast(' ') }
            .eachCount()
        val main = areas.maxByOrNull { it.value }?.key ?: "알 수 없는 지역"
        val others = if (areas.size > 1) " 외 ${areas.size - 1}곳" else ""
        val cal = Calendar.getInstance().apply { timeInMillis = trip.last().dateTaken }
        val month = "${cal.get(Calendar.YEAR)}년 ${cal.get(Calendar.MONTH) + 1}월"
        return "✈️ $month $main$others (${LifeClassifier.dayCount(trip)}일)"
    }

    private fun toGroup(name: String, list: List<Photo>): PhotoGroup {
        val located = list.filter { it.hasLocation }
        return PhotoGroup(
            name = name,
            photos = list,
            centerLat = located.takeIf { it.isNotEmpty() }?.map { it.lat!! }?.average(),
            centerLng = located.takeIf { it.isNotEmpty() }?.map { it.lng!! }?.average(),
        )
    }
}
