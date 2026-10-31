package com.lswlight.photolocation

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toIntRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { App() } }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )
    else -> arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )
}

private fun Context.granted(permission: String) =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private enum class PhotoAccess { NONE, PARTIAL, FULL }

/** 사진 읽기 권한 상태. PARTIAL 은 Android 14+ 의 '선택한 사진만 허용' */
private fun Context.photoAccess(): PhotoAccess {
    val full = when {
        Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES)
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return when {
        full -> PhotoAccess.FULL
        Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ->
            PhotoAccess.PARTIAL
        else -> PhotoAccess.NONE
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    startSafely(context, intent)
}

@Composable
private fun App(vm: GalleryViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    var access by remember { mutableStateOf(context.photoAccess()) }
    var openGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var openSub by rememberSaveable { mutableStateOf<String?>(null) }
    var openMonth by rememberSaveable { mutableStateOf<String?>(null) }
    var openPhotoId by rememberSaveable { mutableStateOf<Long?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val before = access
        access = context.photoAccess()
        // '사진 더 선택'처럼 권한 상태는 그대로인데 볼 수 있는 사진이 바뀐 경우도 다시 스캔한다.
        if (access != PhotoAccess.NONE && access == before) vm.rescan()
    }

    // 설정에서 권한을 바꾸고 돌아오거나, 카메라로 찍고 돌아오면 자동으로 새 사진을 확인한다.
    LifecycleResumeEffect(Unit) {
        access = context.photoAccess()
        if (access != PhotoAccess.NONE) vm.onResume()
        onPauseOrDispose { vm.onPause() }
    }

    // 처음 허용했을 때, 그리고 '선택한 사진만' ↔ '모두 허용'이 바뀌었을 때 다시 스캔한다.
    LaunchedEffect(access) {
        if (access != PhotoAccess.NONE) vm.scan()
    }

    // 동영상 권한은 사진 권한과 따로라서, 사진만 허용된 상태로 업데이트하면 동영상이 안 보인다.
    // 이때 한 번 더 요청한다. (같은 '사진 및 동영상' 그룹이라 보통 창 없이 바로 허용된다)
    var askedVideo by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(access) {
        if (access == PhotoAccess.FULL && Build.VERSION.SDK_INT >= 33 && !askedVideo &&
            !context.granted(Manifest.permission.READ_MEDIA_VIDEO)
        ) {
            askedVideo = true
            launcher.launch(mediaPermissions())
        }
    }

    if (access == PhotoAccess.NONE) {
        PermissionScreen { launcher.launch(mediaPermissions()) }
        return
    }

    var exporting by rememberSaveable { mutableStateOf(false) }

    // 사진 이동 / 휴지통 보내기 허락 창
    val requestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> vm.onRequestResult(result.resultCode == Activity.RESULT_OK) }
    LaunchedEffect(state.userMessage) {
        state.userMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearUserMessage()
        }
    }
    LaunchedEffect(state.pendingRequest) {
        state.pendingRequest?.let { requestLauncher.launch(IntentSenderRequest.Builder(it.sender).build()) }
    }

    if (exporting) {
        BackHandler { exporting = false }
        ExportScreen(
            state = state,
            onBack = { exporting = false },
            onCopy = vm::exportCopy,
            onMove = vm::exportMove,
            onClear = vm::clearExported,
            onDismissMessage = vm::clearExportMessage,
        )
        return
    }

    // 그룹 → (하위 지역) → 분기 폴더 → 사진 순으로 들어간다.
    val group = state.groups.firstOrNull { it.name == openGroup }
    val sub = group?.subgroups?.firstOrNull { it.name == openSub }
    // 월 목록을 보여줄 대상: 하위 지역이 있는 그룹이면 고른 하위 지역, 아니면 그룹 자체
    val monthOwner = when {
        group == null -> null
        group.subgroups.isEmpty() -> group
        else -> sub?.let { it.copy(name = "${group.name} › ${it.name}") }
    }
    val month = monthOwner?.quarters?.firstOrNull { it.first == openMonth }
    val photo = month?.second?.firstOrNull { it.id == openPhotoId }
    val closeMonthList = {
        if (sub != null) {
            openSub = null
        } else {
            openGroup = null
        }
    }

    when {
        photo != null -> {
            BackHandler { openPhotoId = null }
            PhotoScreen(
                photo,
                // 같은 분기 폴더의 사진들 사이를 좌우로 넘긴다.
                photos = month.second,
                onPageChanged = { openPhotoId = it.id },
                onBack = { openPhotoId = null },
                onDelete = { vm.deleteItems(listOf(photo)) },
                onSetHome = {
                    vm.setHome(photo)
                    Toast.makeText(context, "이 위치를 집으로 설정했습니다.", Toast.LENGTH_SHORT).show()
                },
                onSetWork = {
                    vm.setWork(photo)
                    Toast.makeText(context, "이 위치를 회사로 설정했습니다.", Toast.LENGTH_SHORT).show()
                },
                areaName = vm.areaOf(photo),
                onSetWorkArea = {
                    vm.setWorkArea(photo)
                    Toast.makeText(context, "${vm.areaOf(photo)} 전체를 회사로 설정했습니다.", Toast.LENGTH_SHORT).show()
                },
            )
        }
        month != null -> {
            BackHandler { openMonth = null }
            GroupScreen(
                monthOwner!!.copy(name = "${monthOwner.name} · ${month.first}", photos = month.second),
                onBack = { openMonth = null },
                onPhoto = { openPhotoId = it.id },
                folders = state.customFolders,
                onAddToFolder = { name, ids ->
                    vm.addToFolder(name, ids)
                    Toast.makeText(context, "${ids.size}개를 '$name' 폴더에 넣었습니다.", Toast.LENGTH_SHORT).show()
                },
                onRemoveFromFolder = { ids ->
                    vm.removeFromFolder(ids)
                    Toast.makeText(context, "${ids.size}개를 폴더에서 뺐습니다. 자동 분류로 돌아갑니다.", Toast.LENGTH_SHORT).show()
                },
                onDelete = vm::deleteItems,
            )
        }
        monthOwner != null -> {
            BackHandler { closeMonthList() }
            MonthListScreen(monthOwner, onBack = { closeMonthList() }, onMonth = { openMonth = it })
        }
        group != null -> {
            BackHandler { openGroup = null }
            SubgroupListScreen(group, onBack = { openGroup = null }, onSub = { openSub = it.name })
        }
        else -> GroupListScreen(
            state = state,
            partialAccess = access == PhotoAccess.PARTIAL,
            onPickMore = { launcher.launch(mediaPermissions()) },
            locationPermission = context.granted(Manifest.permission.ACCESS_MEDIA_LOCATION),
            onRefresh = vm::scan,
            onExport = { exporting = true },
            onResetAnchors = vm::resetAnchors,
            onSetByAddress = { name, query ->
                vm.setAnchorByAddress(name, query) { found ->
                    val label = if (name == "home") "집" else "회사"
                    val msg = if (found != null) "$label 위치: $found" else "주소를 찾지 못했습니다. 시·구 이름까지 넣어 다시 입력해 주세요."
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            },
            onGroup = { openGroup = it.name },
            onCreateFolder = { name ->
                val ok = vm.createFolder(name)
                if (!ok) Toast.makeText(context, "이미 있는 이름입니다.", Toast.LENGTH_SHORT).show()
            },
            onDeleteFolder = vm::deleteFolder,
        )
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text("앨범 박스", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "갤러리 사진·동영상의 촬영 위치(GPS)와 출처로 집, 회사, 지역, 앱별로 묶어 보여줍니다.\n" +
                "사진 파일은 옮기거나 수정하지 않습니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("사진 접근 허용") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupListScreen(
    state: UiState,
    partialAccess: Boolean,
    onPickMore: () -> Unit,
    locationPermission: Boolean,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onResetAnchors: () -> Unit,
    onSetByAddress: (name: String, query: String) -> Unit,
    onGroup: (PhotoGroup) -> Unit,
    onCreateFolder: (String) -> Unit,
    onDeleteFolder: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    if (creating) {
        TextInputDialog(
            title = "새 폴더",
            placeholder = "예: 아기 성장, 가족여행",
            onConfirm = { onCreateFolder(it); creating = false },
            onDismiss = { creating = false },
        )
    }
    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("'$name' 폴더를 지울까요?") },
            text = { Text("폴더만 지워지고 사진·동영상 원본은 그대로 남습니다. 안에 있던 항목은 자동 분류로 돌아갑니다.") },
            confirmButton = { TextButton(onClick = { onDeleteFolder(name); deleting = null }) { Text("지우기") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("앨범 박스") },
                actions = {
                    IconButton(onClick = onExport, enabled = state.loaded && state.progress == null) {
                        Icon(Icons.Default.Share, contentDescription = "PC로 내보내기")
                    }
                    IconButton(onClick = onRefresh, enabled = state.progress == null) {
                        Icon(Icons.Default.Refresh, contentDescription = "새로고침")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.progress?.let { p ->
                Column(Modifier.padding(16.dp)) {
                    Text("${p.message}… ${p.done} / ${p.total}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (state.loaded) {
                Text(
                    "사진 ${state.totalPhotos - state.totalVideos}장 · 동영상 ${state.totalVideos}개 · " +
                        "위치 있음 ${state.locatedPhotos}개 · ${state.groups.size}개 그룹",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (!locationPermission) {
                Notice("사진 위치 접근 권한이 없어 GPS 좌표가 가려집니다. 설정 › 앱 › 권한에서 허용해 주세요.")
            }
            if (partialAccess) {
                AccessCard(
                    "지금은 '선택한 사진만' 볼 수 있게 허용되어 있어 일부 사진만 분류됩니다. " +
                        "갤러리 전체를 분류하려면 설정에서 사진 권한을 '모두 허용'으로 바꿔 주세요.",
                    onPickMore = onPickMore,
                )
            } else if (state.loaded && state.totalPhotos == 0) {
                AccessCard(
                    "사진을 찾지 못했습니다. 설정에서 사진 권한이 '모두 허용'인지 확인해 주세요.\n\n" +
                        "[진단 정보] 이 화면을 캡처해서 보내주시면 원인을 찾는 데 도움이 됩니다.\n${state.diagnostics}",
                    onPickMore = null,
                )
            }
            if (state.loaded && state.totalPhotos > 0) {
                AnchorCard(state, onResetAnchors, onSetByAddress)
            }
            if (state.geocoderMissing) {
                Notice("이 기기에서 주소 변환(Geocoder)을 지원하지 않아 지역 이름을 표시할 수 없습니다.")
            }

            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                if (state.loaded) {
                    item(key = "new-folder") {
                        TextButton(onClick = { creating = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text("＋ 새 폴더")
                        }
                    }
                }
                items(state.groups, key = { it.name }) { group ->
                    GroupRow(
                        group,
                        onClick = { onGroup(group) },
                        // 내 폴더는 길게 눌러 지운다.
                        onLongClick = group.customFolder?.let { name -> { deleting = name } },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun AnchorCard(
    state: UiState,
    onReset: () -> Unit,
    onSetByAddress: (name: String, query: String) -> Unit,
) {
    // 주소 입력 창을 띄운 대상: "home" / "work" / null(닫힘)
    var addressFor by remember { mutableStateOf<String?>(null) }
    addressFor?.let { name ->
        TextInputDialog(
            title = if (name == "home") "집 주소 입력" else "회사 주소 입력",
            placeholder = "예: 서울 중구 세종대로 110",
            onConfirm = { query ->
                onSetByAddress(name, query)
                addressFor = null
            },
            onDismiss = { addressFor = null },
        )
    }
    // 평소에는 한 줄로 접어 두고, 눌렀을 때만 설정을 펼친다.
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "📍 집·회사 설정",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "접기" else "펼치기",
            )
        }
        if (!expanded) return@Card
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            Text("집: ${state.homeText ?: "찾지 못함"}", style = MaterialTheme.typography.bodySmall)
            Text("회사: ${state.workText ?: "찾지 못함"}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "틀렸다면 그 장소에서 찍은 사진을 열고 '집으로 설정' / '회사로 설정'을 누르세요. " +
                    "공장·산업단지처럼 넓은 곳은 '○○시 전체를 회사로 설정'이 편합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { addressFor = "home" }) { Text("주소로 집 지정") }
                TextButton(onClick = { addressFor = "work" }) { Text("주소로 회사 지정") }
            }
            if (state.anchorsManual) {
                TextButton(onClick = onReset) { Text("자동 감지로 되돌리기") }
            }
        }
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    placeholder: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(placeholder) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text("확인") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}

@Composable
private fun AccessCard(text: String, onPickMore: (() -> Unit)?) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { openAppSettings(context) }) { Text("설정에서 모두 허용") }
                if (onPickMore != null) {
                    TextButton(onClick = onPickMore) { Text("사진 더 선택") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportScreen(
    state: UiState,
    onBack: () -> Unit,
    onCopy: (List<PhotoGroup>) -> Unit,
    onMove: (List<PhotoGroup>) -> Unit,
    onClear: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    var move by rememberSaveable { mutableStateOf(false) }
    // 위치 없는 사진은 기본으로 빼 둔다.
    var selected by remember {
        mutableStateOf(state.groups.map { it.name }.filter { it != NO_LOCATION && it != UNRESOLVED }.toSet())
    }
    var confirm by remember { mutableStateOf<String?>(null) }
    val chosen = state.groups.filter { it.name in selected }
    val count = chosen.sumOf { it.photos.size }
    val running = state.exportProgress != null || state.pendingRequest != null

    when (confirm) {
        "move" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("원본 사진을 옮길까요?") },
            text = {
                Text(
                    "${count}장의 원본이 'Pictures/앨범박스' 아래 그룹 폴더로 옮겨집니다. " +
                        "갤러리 앱에서는 계속 보이지만 원래 폴더(카메라 등)에서는 빠집니다.\n\n" +
                        "다음 화면에서 시스템이 수정 허락을 묻습니다."
                )
            },
            confirmButton = { TextButton(onClick = { confirm = null; onMove(chosen) }) { Text("옮기기") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("취소") } },
        )
        "clear" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("분류 폴더를 비울까요?") },
            text = {
                Text(
                    "'Pictures/앨범박스' 안의 사진을 모두 휴지통으로 보냅니다.\n\n" +
                        "• 복사로 내보냈다면: 복사본만 지워지고 원본은 남습니다.\n" +
                        "• 이동으로 내보냈다면: 폰에서 사진이 사라집니다. PC 로 옮긴 것을 꼭 확인하세요.\n\n" +
                        "휴지통에서 30일 안에는 되살릴 수 있습니다."
                )
            },
            confirmButton = { TextButton(onClick = { confirm = null; onClear() }) { Text("휴지통으로") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("취소") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PC로 내보내기") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "선택한 그룹을 폰의 'Pictures/앨범박스/그룹이름' 폴더로 정리합니다. " +
                            "정리된 폴더를 USB 로 PC 에 복사한 뒤, 아래 '분류 폴더 비우기'로 폰에서 지우면 됩니다.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("폴더 구조: 앨범박스 › 그룹 › 2026년 4분기 › 사진", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    ModeRow(!move, "복사", "원본은 그대로 두고 복사본을 만듭니다. 그만큼 저장 공간을 더 씁니다.") { move = false }
                    ModeRow(move, "이동", "원본 파일을 그룹 폴더로 옮깁니다. 저장 공간을 더 쓰지 않습니다.") { move = true }
                    Spacer(Modifier.height(12.dp))
                    state.exportProgress?.let { p ->
                        Text("${p.message}… ${p.done} / ${p.total}", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    state.exportMessage?.let { msg ->
                        Card(Modifier.fillMaxWidth().clickable(onClick = onDismissMessage)) {
                            Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Button(
                        onClick = { if (move) confirm = "move" else onCopy(chosen) },
                        enabled = count > 0 && !running,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (move) "${count}장 옮기기" else "${count}장 복사하기") }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("그룹 선택", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { selected = state.groups.map { it.name }.toSet() }) { Text("전체") }
                    TextButton(onClick = { selected = emptySet() }) { Text("해제") }
                }
            }
            items(state.groups, key = { it.name }) { group ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { selected = if (group.name in selected) selected - group.name else selected + group.name }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = group.name in selected, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                    Text(group.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${group.photos.size}장", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
                }
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text("PC 로 옮긴 다음", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "PC 에 'Pictures/앨범박스' 폴더를 복사했다면 폰의 분류 폴더를 비워 공간을 확보하세요.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { confirm = "clear" }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                        Text("분류 폴더 비우기 (휴지통으로)")
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeRow(selected: Boolean, title: String, desc: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 8.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(desc, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Notice(text: String) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupRow(group: PhotoGroup, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val cover = group.photos.firstOrNull()
        if (cover != null) {
            Thumbnail(cover, Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)))
        } else {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Text("📁") }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                group.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (group.photos.isEmpty()) {
                    "비어 있음 · 길게 눌러 삭제"
                } else {
                    "${group.photos.size}장" +
                        (if (group.subgroups.isNotEmpty()) " · ${group.subgroups.size}곳" else "") +
                        " · ${dateRange(group.photos)}"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
    }
}

@Composable
private fun Thumbnail(photo: Photo, modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        AsyncImage(
            model = photo.uri,
            contentDescription = photo.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (photo.isVideo) PlayBadge(Modifier.align(Alignment.Center).size(28.dp))
    }
}

/** 동영상 표시: 반투명 원 위의 재생 아이콘 */
@Composable
private fun PlayBadge(modifier: Modifier) {
    Icon(
        Icons.Default.PlayArrow,
        contentDescription = "동영상",
        tint = Color.White,
        modifier = modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(4.dp),
    )
}

/** 도 아래 시·군 목록 (경상북도 › 포항시, 김천시 …) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubgroupListScreen(group: PhotoGroup, onBack: () -> Unit, onSub: (PhotoGroup) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${group.photos.size}장 · ${group.subgroups.size}곳",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(group.subgroups, key = { it.name }) { sub ->
                GroupRow(sub, onClick = { onSub(sub) })
                HorizontalDivider()
            }
        }
    }
}

/** 그룹 안의 분기별 폴더 목록 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonthListScreen(group: PhotoGroup, onBack: () -> Unit, onMonth: (String) -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${group.photos.size}장 · ${group.quarters.size}개 분기",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    if (group.centerLat != null && group.centerLng != null) {
                        IconButton(onClick = { openMap(context, group.centerLat, group.centerLng, group.name) }) {
                            Icon(Icons.Default.Place, contentDescription = "지도에서 보기")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (group.photos.isEmpty()) {
                item {
                    Text(
                        "아직 비어 있습니다. 다른 그룹의 사진 목록에서 사진을 길게 눌러 선택한 뒤 '폴더에 넣기'를 누르세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(group.quarters, key = { it.first }) { (label, photos) ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onMonth(label) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Thumbnail(photos.first(), Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("📁 $label", style = MaterialTheme.typography.titleMedium)
                        Text("${photos.size}장", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
                }
                HorizontalDivider()
            }
        }
    }
}

/**
 * 사진 목록. 사진을 길게 누르면 선택 모드가 되어 여러 장을 골라
 * 내 폴더에 넣거나(내 폴더 안에서는) 폴더에서 뺄 수 있다.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun GroupScreen(
    group: PhotoGroup,
    onBack: () -> Unit,
    onPhoto: (Photo) -> Unit,
    folders: List<String>,
    onAddToFolder: (name: String, ids: Set<Long>) -> Unit,
    onRemoveFromFolder: (ids: Set<Long>) -> Unit,
    onDelete: (List<Photo>) -> Unit,
) {
    val context = LocalContext.current
    // 사진은 최신순으로 정렬돼 있으므로 groupBy 결과도 최신 날짜부터 나온다.
    val days = remember(group) { group.photos.groupBy { DAY_FORMAT.format(Date(it.dateTaken)) } }
    var selected by remember(group.name) { mutableStateOf(emptySet<Long>()) }
    var picking by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()

    BackHandler(enabled = selecting) { selected = emptySet() }

    // 꾹 눌러 끌기 선택: 화면에 보이는 순서(최신순)대로 사진 id 와 위치
    val gridState = rememberLazyGridState()
    val haptics = LocalHapticFeedback.current
    val orderedIds = remember(group) { group.photos.map { it.id } }
    val indexOf = remember(orderedIds) { orderedIds.withIndex().associate { it.value to it.index } }
    val photoById = remember(group) { group.photos.associateBy { it.id } }
    // 끌다가 손가락이 화면 위·아래 끝에 닿으면 그쪽으로 자동 스크롤
    var autoScroll by remember { mutableFloatStateOf(0f) }
    val edge = with(LocalDensity.current) { 64.dp.toPx() }
    LaunchedEffect(autoScroll) {
        if (autoScroll != 0f) {
            while (isActive) {
                gridState.scrollBy(autoScroll)
                delay(10)
            }
        }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("어느 폴더에 넣을까요?") },
            text = {
                Column {
                    folders.filter { it != group.customFolder }.forEach { name ->
                        TextButton(onClick = {
                            onAddToFolder(name, selected)
                            selected = emptySet()
                            picking = false
                        }) { Text(CUSTOM_PREFIX + name) }
                    }
                    TextButton(onClick = { picking = false; naming = true }) { Text("＋ 새 폴더 만들어 넣기") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picking = false }) { Text("취소") } },
        )
    }
    if (naming) {
        TextInputDialog(
            title = "새 폴더",
            placeholder = "예: 아기 성장, 가족여행",
            onConfirm = { name ->
                onAddToFolder(name, selected)
                selected = emptySet()
                naming = false
            },
            onDismiss = { naming = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (selecting) {
                        Text("${selected.size}개 선택")
                    } else {
                        Column {
                            Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${group.photos.size}장", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (selecting) selected = emptySet() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (selecting) "선택 취소" else "뒤로")
                    }
                },
                actions = {
                    if (selecting) {
                        TextButton(onClick = { selected = group.photos.map { it.id }.toSet() }) { Text("전체 선택") }
                    } else if (group.centerLat != null && group.centerLng != null) {
                        IconButton(onClick = { openMap(context, group.centerLat, group.centerLng, group.name) }) {
                            Icon(Icons.Default.Place, contentDescription = "지도에서 보기")
                        }
                    }
                },
            )
        },
        // 선택 중일 때 아래에 할 일 버튼
        bottomBar = {
            if (selecting) {
                val chosen = group.photos.filter { it.id in selected }
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                        // 휴대폰 하단 버튼(◁ ○ |||)에 가리지 않도록 그 높이만큼 위로 올린다.
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    TextButton(onClick = { shareItems(context, chosen) }) { Text("공유") }
                    TextButton(onClick = { picking = true }) { Text("폴더에 넣기") }
                    if (group.customFolder != null) {
                        TextButton(onClick = {
                            onRemoveFromFolder(selected)
                            selected = emptySet()
                        }) { Text("빼기") }
                    }
                    TextButton(onClick = {
                        onDelete(chosen)
                        selected = emptySet()
                    }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            state = gridState,
            modifier = Modifier.padding(padding).fillMaxSize()
                // 짧게 누르기: 선택 중이면 선택/해제, 아니면 크게 보기
                .pointerInput(orderedIds) {
                    detectTapGestures(
                        onTap = { pos ->
                            gridState.photoIdAt(pos)?.let { id ->
                                // pointerInput 블록은 다시 만들어지지 않으므로 선택 상태를 매번 새로 읽는다.
                                if (selected.isNotEmpty()) {
                                    selected = if (id in selected) selected - id else selected + id
                                } else {
                                    photoById[id]?.let(onPhoto)
                                }
                            }
                        },
                        // 길게 누르기는 아래 끌기 선택에서 처리한다.
                        onLongPress = {},
                    )
                }
                // 꾹 누른 채로 끌면 처음 사진부터 지금 손가락 아래 사진까지 한꺼번에 선택
                .pointerInput(orderedIds) {
                    var start: Int? = null
                    var before = emptySet<Long>()
                    detectDragGesturesAfterLongPress(
                        onDragStart = { pos ->
                            gridState.photoIdAt(pos)?.let { id ->
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                start = indexOf[id]
                                before = selected - id
                                selected = selected + id
                            }
                        },
                        onDragEnd = { start = null; autoScroll = 0f },
                        onDragCancel = { start = null; autoScroll = 0f },
                        onDrag = { change, _ ->
                            val from = start
                            if (from != null) {
                                val y = change.position.y
                                val height = gridState.layoutInfo.viewportSize.height
                                autoScroll = when {
                                    y > height - edge -> (y - (height - edge)) / 4
                                    y < edge -> -(edge - y) / 4
                                    else -> 0f
                                }
                                gridState.photoIdAt(change.position)?.let { id ->
                                    val to = indexOf[id] ?: return@let
                                    val range = if (from <= to) from..to else to..from
                                    selected = before + orderedIds.subList(range.first, range.last + 1)
                                }
                            }
                        },
                    )
                },
            contentPadding = PaddingValues(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            days.forEach { (day, photos) ->
                item(key = "day:$day", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "$day · ${photos.size}장",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 6.dp),
                    )
                }
                items(photos, key = { it.id }) { photo ->
                    val isSelected = photo.id in selected
                    // 누르기·끌기는 목록 전체에서 처리한다 (위 pointerInput)
                    Box(Modifier.aspectRatio(1f)) {
                        Thumbnail(photo, Modifier.fillMaxSize())
                        if (isSelected) {
                            Box(
                                Modifier.fillMaxSize()
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                                    .border(3.dp, MaterialTheme.colorScheme.primary)
                            )
                            Text(
                                "✓",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                                    .padding(horizontal = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoScreen(
    photo: Photo,
    photos: List<Photo>,
    onPageChanged: (Photo) -> Unit,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onSetHome: () -> Unit,
    onSetWork: () -> Unit,
    areaName: String?,
    onSetWorkArea: () -> Unit,
) {
    val context = LocalContext.current
    val dateText = remember(photo.dateTaken) {
        SimpleDateFormat("yyyy년 M월 d일 HH:mm", Locale.KOREAN).format(Date(photo.dateTaken))
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(photo.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    IconButton(onClick = { shareItems(context, listOf(photo)) }) {
                        Icon(Icons.Default.Share, contentDescription = "공유")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "삭제")
                    }
                },
            )
        },
    ) { padding ->
        // 목록이 바뀌면(삭제 등) 지금 사진 위치에서 다시 시작한다.
        val pagerState = rememberPagerState(initialPage = photos.indexOf(photo).coerceAtLeast(0)) { photos.size }
        LaunchedEffect(pagerState, photos) {
            snapshotFlow { pagerState.currentPage }.collect { page ->
                photos.getOrNull(page)?.let(onPageChanged)
            }
        }
        Column(Modifier.padding(padding).fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                key = { photos[it].id },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { page ->
                val item = photos[page]
                Box(Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = item.uri,
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (item.isVideo) {
                        PlayBadge(
                            Modifier.align(Alignment.Center).size(72.dp)
                                .clickable { openInGallery(context, item.uri, video = true) }
                        )
                    }
                }
            }
            Column(Modifier.padding(16.dp)) {
                Text(dateText, fontWeight = FontWeight.SemiBold)
                if (photo.lat != null && photo.lng != null) {
                    Text(
                        "위도 %.5f, 경도 %.5f".format(photo.lat, photo.lng),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (photo.lat != null && photo.lng != null) {
                        OutlinedButton(onClick = { openMap(context, photo.lat, photo.lng, photo.name) }) {
                            Text("지도에서 보기")
                        }
                    }
                    OutlinedButton(onClick = { openInGallery(context, photo.uri, photo.isVideo) }) {
                        Text(if (photo.isVideo) "동영상 재생" else "갤러리 앱에서 열기")
                    }
                }
                if (photo.hasLocation) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onSetHome) { Text("🏠 집으로 설정") }
                        TextButton(onClick = onSetWork) { Text("🏢 회사로 설정") }
                    }
                    if (areaName != null) {
                        TextButton(onClick = onSetWorkArea) {
                            Text("🏢 ${areaName.substringAfterLast(' ')} 전체를 회사로 설정")
                        }
                    }
                }
            }
        }
    }
}

private val DAY_FORMAT = SimpleDateFormat("yyyy년 M월 d일 (E)", Locale.KOREAN)
private val SHORT_FORMAT = SimpleDateFormat("yyyy.M.d", Locale.KOREAN)

/** 그룹 사진들의 촬영 기간 (예: 2024.3.1 ~ 2026.10.30) */
private fun dateRange(photos: List<Photo>): String {
    val first = SHORT_FORMAT.format(Date(photos.minOf { it.dateTaken }))
    val last = SHORT_FORMAT.format(Date(photos.maxOf { it.dateTaken }))
    return if (first == last) first else "$first ~ $last"
}

private fun openMap(context: Context, lat: Double, lng: Double, label: String) {
    val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})")
    startSafely(context, Intent(Intent.ACTION_VIEW, uri))
}

/** 목록에서 손가락 위치에 있는 사진의 id (날짜 제목 줄이면 null) */
private fun LazyGridState.photoIdAt(pos: Offset): Long? =
    layoutInfo.visibleItemsInfo.firstOrNull { item ->
        item.size.toIntRect().contains(pos.round() - item.offset)
    }?.key as? Long

/** 카카오톡 등 다른 앱으로 보내기 (여러 개 가능) */
private fun shareItems(context: Context, items: List<Photo>) {
    if (items.isEmpty()) return
    val uris = ArrayList(items.map { it.uri })
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    intent.type = when {
        items.all { it.isVideo } -> "video/*"
        items.none { it.isVideo } -> "image/*"
        else -> "*/*"
    }
    // 받는 앱이 파일을 읽을 수 있도록 읽기 권한을 함께 넘긴다.
    intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startSafely(context, Intent.createChooser(intent, "공유"))
}

private fun openInGallery(context: Context, uri: Uri, video: Boolean) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, if (video) "video/*" else "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startSafely(context, intent)
}

private fun startSafely(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "열 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show()
    }
}
