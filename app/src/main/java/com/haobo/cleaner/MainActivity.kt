package com.haobo.cleaner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val allFilesGranted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        allFilesGranted.value = Environment.isExternalStorageManager()
        setContent {
            val granted by allFilesGranted
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    HaoboApp(
                        allFilesGranted = granted,
                        onRequestAllFiles = { openAllFilesSettings() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        allFilesGranted.value = Environment.isExternalStorageManager()
    }

    private fun openAllFilesSettings() {
        val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(appIntent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }
}

private sealed interface Screen {
    data object Home : Screen
    data class Result(val group: CleanGroup) : Screen
}

@Composable
private fun HaoboApp(
    allFilesGranted: Boolean,
    onRequestAllFiles: () -> Unit,
) {
    val context = LocalContext.current
    val client = remember { ShellClient(context.applicationContext) }
    val direct = remember { DirectBackend(context.applicationContext) }
    val saf = remember { SafBackend(context.applicationContext) }
    val stats = remember { StatsScanner(context.applicationContext) }
    val exclusions = remember { Exclusions(context.applicationContext) }
    val analyzers = remember { Analyzers(context.applicationContext, exclusions) }
    val engine = remember {
        ScanEngine(context.applicationContext, direct, client, saf, stats, analyzers, exclusions)
    }
    val state by client.state.collectAsState()
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var safGranted by remember { mutableStateOf(saf.granted) }

    val safLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                safGranted = saf.granted
            }
        }
    }

    DisposableEffect(Unit) {
        client.attach()
        onDispose { client.detach() }
    }

    val shizukuReady = state is ShellState.Ready

    when (val current = screen) {
        is Screen.Home -> HomeScreen(
            client = client,
            state = state,
            allFilesGranted = allFilesGranted,
            onRequestAllFiles = onRequestAllFiles,
            safGranted = safGranted,
            onRequestSaf = { safLauncher.launch(saf.requestIntent()) },
            onOpen = { screen = Screen.Result(it) },
        )
        is Screen.Result -> ResultScreen(
            group = current.group,
            engine = engine,
            stats = stats,
            enabled = shizukuReady || allFilesGranted || safGranted,
            onBack = { screen = Screen.Home },
        )
    }
}

@Composable
private fun HomeScreen(
    client: ShellClient,
    state: ShellState,
    allFilesGranted: Boolean,
    onRequestAllFiles: () -> Unit,
    safGranted: Boolean,
    onRequestSaf: () -> Unit,
    onOpen: (CleanGroup) -> Unit,
) {
    val storage = remember { readStorage() }
    val ratio = if (storage.total > 0) {
        (storage.used.toFloat() / storage.total.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shizukuReady = state is ShellState.Ready

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "颢博清理",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "存储 ${formatBytes(storage.used)} / ${formatBytes(storage.total)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(3.dp),
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(ratio)
                    .fillMaxHeight()
                    .background(
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(3.dp),
                    ),
            )
        }

        Spacer(Modifier.height(14.dp))

        PermissionCard(
            title = if (allFilesGranted) "文件访问：已授权" else "文件访问：未授权",
            detail = if (allFilesGranted) {
                "常规清理、大文件、安装包、空文件夹都可用"
            } else {
                "授予「所有文件访问」后可用，不需要 Shizuku"
            },
            actionText = if (allFilesGranted) null else "去授权",
            onAction = onRequestAllFiles,
        )

        Spacer(Modifier.height(8.dp))

        PermissionCard(
            title = if (safGranted) "Android 目录：已授权" else "Android 目录：未授权",
            detail = if (safGranted) {
                "应用清理、残留清理可用，不走 Shizuku"
            } else {
                "在系统选择器里选 Android 文件夹，一次授权即可"
            },
            actionText = if (safGranted) null else "去授权",
            onAction = onRequestSaf,
        )

        Spacer(Modifier.height(8.dp))

        PermissionCard(
            title = stateTitle(state),
            detail = stateHint(state),
            actionText = when (state) {
                is ShellState.NoPermission -> "授权"
                is ShellState.NotInstalled, is ShellState.NotRunning -> "重试"
                else -> null
            },
            onAction = {
                if (state is ShellState.NoPermission) client.requestPermission() else client.refresh()
            },
        )

        Spacer(Modifier.height(14.dp))

        CleanGroup.entries.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { group ->
                    val needsAppData = group == CleanGroup.APP || group == CleanGroup.RESIDUAL
                    val enabled = when {
                        needsAppData -> safGranted || shizukuReady
                        group == CleanGroup.APPS -> true
                        else -> allFilesGranted || shizukuReady
                    }
                    ModuleCard(
                        group = group,
                        modifier = Modifier.weight(1f),
                        enabled = enabled,
                        needsAppData = needsAppData,
                        onClick = { onOpen(group) },
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    detail: String,
    actionText: String?,
    onAction: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (actionText != null) {
                Spacer(Modifier.width(8.dp))
                Button(onClick = onAction) { Text(actionText) }
            }
        }
    }
}

@Composable
private fun ModuleCard(
    group: CleanGroup,
    modifier: Modifier,
    enabled: Boolean,
    needsAppData: Boolean,
    onClick: () -> Unit,
) {
    val clickModifier = if (enabled) Modifier.clickable { onClick() } else Modifier
    Card(modifier = modifier.then(clickModifier)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = group.title,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = group.desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (needsAppData) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "需授权 Android 目录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun ResultScreen(
    group: CleanGroup,
    engine: ScanEngine,
    stats: StatsScanner,
    enabled: Boolean,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var items by remember { mutableStateOf(emptyList<JunkItem>()) }
    var scanning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(group, enabled) {
        if (!enabled || loaded) return@LaunchedEffect
        loaded = true
        scanning = true
        val result = engine.scan(group) { stage = it }
        items = result.items
        message = result.error ?: if (result.items.isEmpty()) "没有找到可清理的内容" else null
        scanning = false
    }

    val selected = items.filter { it.checked }
    val selectedBytes = selected.sumOf { it.size }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        BackHandler { onBack() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.height(48.dp).width(80.dp),
            ) { Text("返回") }
            Spacer(Modifier.width(4.dp))
            Column {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (scanning) "扫描中 · $stage" else "共 ${items.size} 项",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (group == CleanGroup.APP) {
            TextButton(onClick = { stats.openUsageAccessSettings(context) }) {
                Text("授予「使用情况访问」权限")
            }
            Spacer(Modifier.height(4.dp))
        }

        if (scanning) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(2.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.4f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(items, key = { "${it.path}#${it.ruleName}" }) { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = item.checked,
                        onCheckedChange = {
                            items = items.map {
                                if (it.path == item.path) it.copy(checked = !it.checked) else it
                            }
                        },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = item.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (item.size > 0) formatBytes(item.size) else "—",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        if (group.isAnalysis) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "合计 ${formatBytes(items.sumOf { it.size })}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                TextButton(onClick = onBack) { Text("完成") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "已选 ${selected.size} 项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatBytes(selectedBytes),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Button(
                    enabled = !busy && !scanning && selected.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            busy = true
                            val report = engine.delete(selected)
                            message = buildString {
                                append("释放 ${formatBytes(report.freedBytes)}")
                                if (report.failed.isNotEmpty()) {
                                    append("，${report.failed.size} 项失败")
                                }
                            }
                            items = items.filterNot { it.checked }
                            busy = false
                        }
                    },
                ) { Text(if (busy) "清理中…" else "清理选中") }
            }
        }
    }
}

private data class StorageInfo(val total: Long, val used: Long)

private fun readStorage(): StorageInfo = runCatching {
    val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
    val total = stat.totalBytes
    val free = stat.availableBytes
    StorageInfo(total = total, used = total - free)
}.getOrDefault(StorageInfo(0L, 0L))

private fun stateTitle(state: ShellState): String = when (state) {
    is ShellState.Ready -> "Shizuku：已就绪"
    is ShellState.NoPermission -> "Shizuku：等待授权"
    is ShellState.NotRunning -> "Shizuku：未运行"
    is ShellState.NotInstalled -> "Shizuku：未安装"
}

private fun stateHint(state: ShellState): String = when (state) {
    is ShellState.Ready -> "可以读取 Android/data，应用清理与残留清理可用"
    is ShellState.NoPermission -> "点右侧按钮，在弹窗里允许"
    is ShellState.NotRunning -> "可选。装上并启动后，可清理其他应用的外部缓存"
    is ShellState.NotInstalled -> "可选。不装也能用其余五个模块"
}
