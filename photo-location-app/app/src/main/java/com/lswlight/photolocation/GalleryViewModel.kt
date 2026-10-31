package com.lswlight.photolocation

import android.app.Application
import android.content.IntentSender
import android.os.Build
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
    /** 하위 지역 (경상북도 아래 포항시, 김천시 …). 비어 있으면 바로 분기 폴더로 들어간다. */
    val subgroups: List<PhotoGroup> = emptyList(),
    /** 사용자가 만든 '내 폴더'이면 그 폴더 이름(⭐ 없이), 아니면 null */
    val customFolder: String? = null,
) {
    /** 분기별 하위 폴더: (2026년 4분기, 사진들) 최신 분기부터 */
    val quarters: List<Pair<String, List<Photo>>> by lazy {
        photos.groupBy { quarterLabel(it.dateTaken) }.toList().sortedByDescending { it.first }
    }
}

data class Progress(val message: String, val done: Int, val total: Int)

data class UiState(
    val progress: Progress? = null,
    val groups: List<PhotoGroup> = emptyList(),
    val totalPhotos: Int = 0,
    val locatedPhotos: Int = 0,
    val totalVideos: Int = 0,
    /** 내 폴더 이름 목록 (폴더에 넣기 창에서 쓴다) */
    val customFolders: List<String> = emptyList(),
    val geocoderMissing: Boolean = false,
    val loaded: Boolean = false,
    /** 집/회사 위치 설명. null 이면 찾지 못함 */
    val homeText: String? = null,
    val workText: String? = null,
    val anchorsManual: Boolean = false,
    /** 사진을 못 찾았을 때 보여줄 진단 정보 */
    val diagnostics: String = "",
    /** 내보내기 진행 상황과 결과 안내 */
    val exportProgress: Progress? = null,
    val exportMessage: String? = null,
    /** 사용자 허락이 필요한 시스템 요청 (사진 이동 / 휴지통). 화면에서 띄운다. */
    val pendingRequest: PendingRequest? = null,
    /** 화면에 잠깐 띄울 안내 (토스트) */
    val userMessage: String? = null,
)

enum class RequestKind { MOVE, TRASH, DELETE }

data class PendingRequest(val sender: IntentSender, val kind: RequestKind)

/** 한 번에 허락을 구하는 사진 수. 너무 많으면 시스템 요청이 실패할 수 있어 나눠서 묻는다. */
private const val REQUEST_CHUNK = 1000

const val NO_LOCATION = "위치 정보 없음"
const val UNRESOLVED = "주소 확인 실패 (인터넷 연결 후 새로고침)"
const val HOME = "🏠 집"
const val WORK = "🏢 회사"

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = PhotoRepository(app)
    private val exporter = Exporter(app)
    private val moveQueue = ArrayDeque<List<Pair<Photo, String>>>()
    private val trashQueue = ArrayDeque<List<android.net.Uri>>()
    private val deleteQueue = ArrayDeque<List<android.net.Uri>>()
    private var deleteTotal = 0
    private var exportDone = 0
    private var exportTotal = 0
    private val namer = LocationNamer(app)
    private val anchorStore = AnchorStore(app)
    private val customStore = CustomFolderStore(app)
    private var home: Anchor? = null
    private var work: Anchor? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var photos: List<Photo> = emptyList()
    /** 내가 카메라로 찍은 사진. 위치 분류(집/회사/지역)는 이것만 쓴다. */
    private var cameraPhotos: List<Photo> = emptyList()
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
        app.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer
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

    /** 권한이 바뀌어 볼 수 있는 항목이 달라졌을 때. 스캔 중이면 끝난 뒤 한 번 더 스캔한다. */
    fun rescan() {
        dirty = true
        scheduleScan()
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
                cameraPhotos = photos.filter { it.source == null }
                if (namer.isAvailable) {
                    val keys = cameraPhotos.filter { it.hasLocation }
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
                    totalVideos = photos.count { p -> p.isVideo },
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
            append(" / 동영상 ${has("android.permission.READ_MEDIA_VIDEO")}")
            append(" / 선택사진 ${has("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")}")
            append(" / 저장소 ${has("android.permission.READ_EXTERNAL_STORAGE")}")
            append(" / 사진위치 ${has("android.permission.ACCESS_MEDIA_LOCATION")}\n")
            append("조회: ${repository.diagnostics}")
        }
    }

    /** 사진 위치를 집 또는 회사로 직접 지정한다. */
    fun setHome(photo: Photo) = setAnchor("home", photo)
    fun setWork(photo: Photo) = setAnchor("work", photo)

    /** 사진이 찍힌 시·군·구 이름 (주소를 아직 못 찾았으면 null) */
    fun areaOf(photo: Photo): String? =
        if (photo.hasLocation) namer.cached(namer.cellKey(photo.lat!!, photo.lng!!))?.district else null

    private fun setAnchor(name: String, photo: Photo) {
        if (!photo.hasLocation) return
        anchorStore.set(name, photo.lat!!, photo.lng!!)
        // 회사를 지점으로 지정하면 이전에 지정한 '시 전체' 설정은 지운다.
        if (name == "work") anchorStore.workArea = null
        refreshGroups()
    }

    /**
     * 주소를 입력해 집("home") 또는 회사("work")를 지정한다.
     * onResult 에는 찾은 주소가, 못 찾으면 null 이 전달된다.
     */
    fun setAnchorByAddress(name: String, query: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                namer.geocodeAddress(query)?.also { (lat, lng, _) ->
                    // 카드에 지역 이름을 보여주기 위해 주소 캐시도 채워 둔다.
                    namer.resolve(listOf(namer.cellKey(lat, lng))) { _, _ -> }
                }
            }
            if (found != null) {
                anchorStore.set(name, found.first, found.second)
                if (name == "work") anchorStore.workArea = null
                refreshGroups()
            }
            onResult(found?.third)
        }
    }

    // ---- 내 폴더 ----

    /** 새 폴더를 만든다. 이름이 비었거나 이미 있으면 false */
    fun createFolder(name: String): Boolean = customStore.create(name).also { if (it) refreshGroups() }

    fun deleteFolder(name: String) {
        customStore.delete(name)
        refreshGroups()
    }

    /** 사진을 내 폴더에 넣는다. 폴더가 없으면 새로 만든다. */
    fun addToFolder(name: String, ids: Collection<Long>) {
        if (name.trim() !in customStore.folderNames) customStore.create(name)
        customStore.add(name.trim(), ids)
        refreshGroups()
    }

    /** 내 폴더에서 빼서 다시 자동 분류로 돌려보낸다. */
    fun removeFromFolder(ids: Collection<Long>) {
        customStore.remove(ids)
        refreshGroups()
    }

    /** 직접 지정한 집/회사를 지우고 자동 감지로 되돌린다. */
    fun resetAnchors() {
        anchorStore.clear()
        refreshGroups()
    }

    private fun refreshGroups() {
        home = anchorStore.get("home") ?: LifeClassifier.detectHome(cameraPhotos)
        val workArea = anchorStore.workArea
        // 회사 지역을 직접 지정했으면 자동 감지한 회사 지점은 쓰지 않는다.
        work = anchorStore.get("work") ?: if (workArea == null) LifeClassifier.detectWork(cameraPhotos, home) else null
        _state.update {
            it.copy(
                groups = buildGroups(),
                customFolders = customStore.folderNames,
                homeText = home?.let { a -> describe(a) },
                workText = listOfNotNull(
                    workArea?.let { a -> "$a 전체 (직접 지정)" },
                    work?.let { a -> "${describe(a)} · 반경 1km" },
                ).joinToString(", ").ifEmpty { null },
                anchorsManual = home?.manual == true || work?.manual == true || workArea != null,
            )
        }
    }

    private fun describe(anchor: Anchor): String {
        val area = namer.cached(namer.cellKey(anchor.lat, anchor.lng))?.district ?: "주소 미확인"
        return "$area (${if (anchor.manual) "직접 지정" else "자동 감지"})"
    }

    /**
     * 그룹 순서: 🏠 집 → 🏢 회사 → 지역(대구, 경상북도 포항시 …, 사진 많은 순)
     * → 위치 정보 없음 → 주소 확인 실패 → 받은 사진(카카오톡 등)
     */
    private fun buildGroups(): List<PhotoGroup> {
        val workArea = anchorStore.workArea
        val assigned = customStore.assigned
        val byName = LinkedHashMap<String, MutableList<Photo>>()
        // 지역 그룹별 하위 지역(시·군) 사진. 광역시처럼 하위가 없으면 키가 null
        val subsByRegion = HashMap<String, LinkedHashMap<String?, MutableList<Photo>>>()
        // 내 폴더에 넣은 사진은 자동 분류에서 뺀다.
        for (photo in cameraPhotos) {
            if (photo.id in assigned) continue
            val name = when {
                !photo.hasLocation -> NO_LOCATION
                else -> when (LifeClassifier.kindOf(photo, home, work, workArea, ::areaOf)) {
                    LifeKind.HOME -> HOME
                    LifeKind.WORK -> WORK
                    LifeKind.OTHER -> {
                        val place = namer.cached(namer.cellKey(photo.lat!!, photo.lng!!))
                        if (place == null) {
                            UNRESOLVED
                        } else {
                            val (region, sub) = place.regionPath()
                            subsByRegion.getOrPut(region) { LinkedHashMap() }.getOrPut(sub) { mutableListOf() } += photo
                            region
                        }
                    }
                }
            }
            byName.getOrPut(name) { mutableListOf() } += photo
        }
        val fixedTop = listOf(HOME, WORK)
        val fixedBottom = listOf(NO_LOCATION, UNRESOLVED)
        val regions = byName.filterKeys { it !in fixedTop && it !in fixedBottom }
            .map { (name, list) -> toGroup(name, list, subgroups(subsByRegion[name])) }
            .sortedByDescending { it.photos.size }
        fun fixed(names: List<String>) = names.mapNotNull { n -> byName[n]?.let { toGroup(n, it) } }
        return customGroups() + fixed(fixedTop) + regions + fixed(fixedBottom) + receivedGroups()
    }

    /** 내 폴더. 사진이 없는 폴더도 목록에 보인다. */
    private fun customGroups(): List<PhotoGroup> {
        val byFolder = photos.filter { it.id in customStore.assigned }.groupBy { customStore.assigned.getValue(it.id) }
        return customStore.folderNames.map { name ->
            toGroup(CUSTOM_PREFIX + name, byFolder[name].orEmpty()).copy(customFolder = name)
        }
    }

    /** 시·군 하위 그룹. 하위 이름이 하나도 없으면(광역시) 빈 목록 */
    private fun subgroups(subs: Map<String?, List<Photo>>?): List<PhotoGroup> {
        if (subs == null || subs.keys.all { it == null }) return emptyList()
        return subs.map { (sub, list) -> toGroup(sub ?: "시·군 미확인", list) }
            .sortedByDescending { it.photos.size }
    }

    /** 카카오톡·스크린샷 등 받은 사진은 위치와 상관없이 출처(앱)별로 묶는다. */
    private fun receivedGroups(): List<PhotoGroup> {
        val bySource = photos.filter { it.source != null && it.id !in customStore.assigned }.groupBy { it.source!! }
        val known = RECEIVED_ORDER.mapNotNull { name -> bySource[name]?.let { toGroup(name, it) } }
        val others = bySource.filterKeys { it !in RECEIVED_ORDER }
            .map { (name, list) -> toGroup(name, list) }
            .sortedByDescending { it.photos.size }
        return known + others
    }

    private fun toGroup(name: String, list: List<Photo>, subgroups: List<PhotoGroup> = emptyList()): PhotoGroup {
        val located = list.filter { it.hasLocation }
        return PhotoGroup(
            name = name,
            photos = list,
            subgroups = subgroups,
            centerLat = located.takeIf { it.isNotEmpty() }?.map { it.lat!! }?.average(),
            centerLng = located.takeIf { it.isNotEmpty() }?.map { it.lng!! }?.average(),
        )
    }

    // ---- PC 로 옮기기 위한 내보내기 ----

    /** 원본은 두고 그룹 폴더로 복사한다. 이미 같은 이름이 있으면 건너뛴다. */
    fun exportCopy(groups: List<PhotoGroup>) {
        if (_state.value.exportProgress != null) return
        viewModelScope.launch {
            val total = groups.sumOf { it.photos.size }
            var copied = 0
            var skipped = 0
            var failed = 0
            var done = 0
            withContext(Dispatchers.IO) {
                val existing = HashMap<String, Set<String>>()
                for ((photo, folder) in groups.flatMap { exportTargets(it) }) {
                    val names = existing.getOrPut(folder) { exporter.existingNames(folder) }
                    when {
                        photo.name in names -> skipped++
                        exporter.copy(photo, folder) -> copied++
                        else -> failed++
                    }
                    done++
                    if (done % 10 == 0) setExportProgress("복사 중", done, total)
                }
            }
            finishExport(
                "${copied}장을 복사했습니다." +
                    (if (skipped > 0) " (이미 있는 ${skipped}장은 건너뜀)" else "") +
                    (if (failed > 0) " 실패 ${failed}장." else "") + PC_GUIDE
            )
        }
    }

    /** 원본 파일을 그룹 폴더로 옮긴다. 먼저 사용자에게 수정 허락을 받는다. (Android 11+) */
    fun exportMove(groups: List<PhotoGroup>) {
        if (_state.value.exportProgress != null) return
        if (Build.VERSION.SDK_INT < 30) {
            _state.update { it.copy(exportMessage = "사진 이동은 Android 11 이상에서만 됩니다. '복사'를 이용해 주세요.") }
            return
        }
        val items = groups.flatMap { exportTargets(it) }
        moveQueue.clear()
        moveQueue.addAll(items.chunked(REQUEST_CHUNK))
        exportDone = 0
        exportTotal = items.size
        requestNextMove()
    }

    /** 내보내기 폴더(Pictures/앨범박스)를 통째로 휴지통으로 보낸다. */
    fun clearExported() {
        if (_state.value.exportProgress != null) return
        viewModelScope.launch {
            val uris = withContext(Dispatchers.IO) { exporter.exportedUris() }
            if (uris.isEmpty()) {
                _state.update { it.copy(exportMessage = "분류 폴더가 이미 비어 있습니다.") }
                return@launch
            }
            if (Build.VERSION.SDK_INT >= 30) {
                trashQueue.clear()
                trashQueue.addAll(uris.chunked(REQUEST_CHUNK))
                exportTotal = uris.size
                requestNextTrash()
            } else {
                val deleted = withContext(Dispatchers.IO) { uris.count { exporter.delete(it) } }
                finishExport("${deleted}장을 지웠습니다.")
            }
        }
    }

    /** 시스템 허락 창의 결과 */
    fun onRequestResult(granted: Boolean) {
        val request = _state.value.pendingRequest ?: return
        _state.update { it.copy(pendingRequest = null) }
        if (!granted) {
            if (request.kind == RequestKind.DELETE) {
                deleteQueue.clear()
                return
            }
            moveQueue.clear()
            trashQueue.clear()
            finishExport("취소했습니다.")
            return
        }
        when (request.kind) {
            RequestKind.MOVE -> viewModelScope.launch {
                val chunk = moveQueue.removeFirstOrNull() ?: return@launch
                withContext(Dispatchers.IO) {
                    for ((photo, folder) in chunk) {
                        exporter.move(photo, folder)
                        exportDone++
                        if (exportDone % 10 == 0) setExportProgress("이동 중", exportDone, exportTotal)
                    }
                }
                requestNextMove()
            }
            RequestKind.TRASH -> {
                trashQueue.removeFirstOrNull()
                requestNextTrash()
            }
            RequestKind.DELETE -> {
                deleteQueue.removeFirstOrNull()
                requestNextDelete()
            }
        }
    }

    // ---- 삭제 ----

    /**
     * 사진·동영상을 갤러리에서도 지운다. 바로 지우지 않고 시스템 휴지통으로 보내며,
     * 시스템이 삭제 확인 창을 띄운다. (Android 11+)
     */
    fun deleteItems(items: Collection<Photo>) {
        if (items.isEmpty()) return
        if (Build.VERSION.SDK_INT < 30) {
            _state.update { it.copy(userMessage = "삭제는 Android 11 이상에서만 됩니다.") }
            return
        }
        deleteQueue.clear()
        deleteQueue.addAll(items.map { it.uri }.chunked(REQUEST_CHUNK))
        deleteTotal = items.size
        requestNextDelete()
    }

    private fun requestNextDelete() {
        val chunk = deleteQueue.firstOrNull()
        if (chunk == null) {
            _state.update { it.copy(userMessage = "${deleteTotal}개를 휴지통으로 옮겼습니다.") }
            scheduleScan()
            return
        }
        val intent = MediaStore.createTrashRequest(getApplication<Application>().contentResolver, chunk, true)
        _state.update { it.copy(pendingRequest = PendingRequest(intent.intentSender, RequestKind.DELETE)) }
    }

    fun clearUserMessage() {
        _state.update { it.copy(userMessage = null) }
    }

    private fun requestNextMove() {
        val chunk = moveQueue.firstOrNull()
        if (chunk == null) {
            finishExport("${exportTotal}장을 분류 폴더로 옮겼습니다." + PC_GUIDE)
            return
        }
        setExportProgress("이동 허락 기다리는 중", exportDone, exportTotal)
        val intent = MediaStore.createWriteRequest(
            getApplication<Application>().contentResolver, chunk.map { it.first.uri }
        )
        _state.update { it.copy(pendingRequest = PendingRequest(intent.intentSender, RequestKind.MOVE)) }
    }

    private fun requestNextTrash() {
        val chunk = trashQueue.firstOrNull()
        if (chunk == null) {
            finishExport("분류 폴더의 ${exportTotal}장을 휴지통으로 보냈습니다. 30일 안에는 갤러리 휴지통에서 되살릴 수 있습니다.")
            return
        }
        setExportProgress("휴지통 허락 기다리는 중", 0, exportTotal)
        val intent = MediaStore.createTrashRequest(
            getApplication<Application>().contentResolver, chunk, true
        )
        _state.update { it.copy(pendingRequest = PendingRequest(intent.intentSender, RequestKind.TRASH)) }
    }

    private fun setExportProgress(message: String, done: Int, total: Int) {
        _state.update { it.copy(exportProgress = Progress(message, done, total), exportMessage = null) }
    }

    private fun finishExport(message: String) {
        _state.update { it.copy(exportProgress = null, exportMessage = message) }
        // 파일 위치가 바뀌었으므로 다시 스캔
        scheduleScan()
    }

    fun clearExportMessage() {
        _state.update { it.copy(exportMessage = null) }
    }
}

private const val PC_GUIDE =
    "\n\nPC 에 USB 로 연결한 뒤 '내 PC › 휴대폰 › 내장 메모리 › Pictures › 앨범박스' 폴더를 복사하세요."
