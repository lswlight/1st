package com.lswlight.photolocation

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
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
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )
    else -> arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )
}

private fun Context.granted(permission: String) =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

/** 사진 읽기 권한(전체 또는 Android 14 의 '선택한 사진만')이 있는지 */
private fun Context.canReadPhotos(): Boolean = when {
    Build.VERSION.SDK_INT >= 34 -> granted(Manifest.permission.READ_MEDIA_IMAGES) ||
        granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES)
    else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
}

@Composable
private fun App(vm: GalleryViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    var hasPermission by remember { mutableStateOf(context.canReadPhotos()) }
    var openGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var openPhotoId by rememberSaveable { mutableStateOf<Long?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermission = context.canReadPhotos()
        if (hasPermission) vm.scan()
    }

    // 설정에서 권한을 바꾸고 돌아오거나, 카메라로 찍고 돌아오면 자동으로 새 사진을 확인한다.
    LifecycleResumeEffect(Unit) {
        hasPermission = context.canReadPhotos()
        if (hasPermission) vm.onResume()
        onPauseOrDispose { vm.onPause() }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission && !state.loaded) vm.scan()
    }

    if (!hasPermission) {
        PermissionScreen { launcher.launch(mediaPermissions()) }
        return
    }

    val group = state.groups.firstOrNull { it.name == openGroup }
    val photo = group?.photos?.firstOrNull { it.id == openPhotoId }

    when {
        photo != null -> {
            BackHandler { openPhotoId = null }
            PhotoScreen(
                photo,
                onBack = { openPhotoId = null },
                onSetHome = {
                    vm.setHome(photo)
                    Toast.makeText(context, "이 위치를 집으로 설정했습니다.", Toast.LENGTH_SHORT).show()
                },
                onSetWork = {
                    vm.setWork(photo)
                    Toast.makeText(context, "이 위치를 회사로 설정했습니다.", Toast.LENGTH_SHORT).show()
                },
            )
        }
        group != null -> {
            BackHandler { openGroup = null }
            GroupScreen(group, onBack = { openGroup = null }, onPhoto = { openPhotoId = it.id })
        }
        else -> GroupListScreen(
            state = state,
            locationPermission = context.granted(Manifest.permission.ACCESS_MEDIA_LOCATION),
            onRefresh = vm::scan,
            onLevel = vm::setLevel,
            onResetAnchors = vm::resetAnchors,
            onGroup = { openGroup = it.name },
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
        Text("사진 위치 분류", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "갤러리 사진에 기록된 촬영 위치(GPS)를 읽어 지역별로 묶어 보여줍니다.\n" +
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
    locationPermission: Boolean,
    onRefresh: () -> Unit,
    onLevel: (GroupLevel) -> Unit,
    onResetAnchors: () -> Unit,
    onGroup: (PhotoGroup) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("사진 위치 분류") },
                actions = {
                    IconButton(onClick = onRefresh, enabled = state.progress == null) {
                        Icon(Icons.Default.Refresh, contentDescription = "새로고침")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GroupLevel.entries.forEach { level ->
                    FilterChip(
                        selected = state.level == level,
                        onClick = { onLevel(level) },
                        label = { Text(level.label) },
                    )
                }
            }

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
                    "사진 ${state.totalPhotos}장 · 위치 있음 ${state.locatedPhotos}장 · ${state.groups.size}개 그룹",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (!locationPermission) {
                Notice("사진 위치 접근 권한이 없어 GPS 좌표가 가려집니다. 설정 › 앱 › 권한에서 허용해 주세요.")
            }
            if (state.loaded && state.level == GroupLevel.LIFE) {
                AnchorCard(state, onResetAnchors)
            }
            if (state.geocoderMissing) {
                Notice("이 기기에서 주소 변환(Geocoder)을 지원하지 않아 지역 이름을 표시할 수 없습니다.")
            }

            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.groups, key = { it.name }) { group ->
                    GroupRow(group, onClick = { onGroup(group) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun AnchorCard(state: UiState, onReset: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("집: ${state.homeText ?: "찾지 못함"}", style = MaterialTheme.typography.bodySmall)
            Text("회사: ${state.workText ?: "찾지 못함"}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "틀렸다면 그 장소에서 찍은 사진을 열고 '집으로 설정' / '회사로 설정'을 누르세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.anchorsManual) {
                TextButton(onClick = onReset) { Text("자동 감지로 되돌리기") }
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun GroupRow(group: PhotoGroup, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(group.photos.first(), Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                group.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${group.photos.size}장 · ${dateRange(group.photos)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
    }
}

@Composable
private fun Thumbnail(photo: Photo, modifier: Modifier) {
    AsyncImage(
        model = photo.uri,
        contentDescription = photo.name,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupScreen(group: PhotoGroup, onBack: () -> Unit, onPhoto: (Photo) -> Unit) {
    val context = LocalContext.current
    // 사진은 최신순으로 정렬돼 있으므로 groupBy 결과도 최신 날짜부터 나온다.
    val days = remember(group) { group.photos.groupBy { DAY_FORMAT.format(Date(it.dateTaken)) } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${group.photos.size}장", style = MaterialTheme.typography.bodySmall)
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
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
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
                    Thumbnail(photo, Modifier.aspectRatio(1f).clickable { onPhoto(photo) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoScreen(
    photo: Photo,
    onBack: () -> Unit,
    onSetHome: () -> Unit,
    onSetWork: () -> Unit,
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
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AsyncImage(
                    model = photo.uri,
                    contentDescription = photo.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
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
                    OutlinedButton(onClick = { openInGallery(context, photo.uri) }) {
                        Text("갤러리 앱에서 열기")
                    }
                }
                if (photo.hasLocation) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onSetHome) { Text("🏠 집으로 설정") }
                        TextButton(onClick = onSetWork) { Text("🏢 회사로 설정") }
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

private fun openInGallery(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/*")
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
