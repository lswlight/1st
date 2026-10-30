package com.lswlight.photolocation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
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

    fun scan() {
        if (_state.value.progress != null) return
        viewModelScope.launch {
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
