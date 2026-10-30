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
    val level: GroupLevel = GroupLevel.DISTRICT,
    val groups: List<PhotoGroup> = emptyList(),
    val totalPhotos: Int = 0,
    val locatedPhotos: Int = 0,
    val geocoderMissing: Boolean = false,
    val loaded: Boolean = false,
)

const val NO_LOCATION = "위치 정보 없음"
const val UNRESOLVED = "주소 확인 실패 (인터넷 연결 후 새로고침)"

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = PhotoRepository(app)
    private val namer = LocationNamer(app)

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
                    groups = buildGroups(it.level),
                )
            }
            if (dirty && resumed) scheduleScan()
        }
    }

    fun setLevel(level: GroupLevel) {
        _state.update { it.copy(level = level, groups = buildGroups(level)) }
    }

    private fun buildGroups(level: GroupLevel): List<PhotoGroup> {
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
