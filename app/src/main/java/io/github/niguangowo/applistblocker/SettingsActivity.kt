package io.github.niguangowo.applistblocker

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class SettingsActivity : ComponentActivity() {

    private var status by mutableStateOf(ModuleStatus.DISCONNECTED)

    private var records by mutableStateOf(RecordSnapshot.UNAVAILABLE)

    private var preferences: SharedPreferences? = null

    /**
     * 记录文件变化时刷新界面；Hook 侧写入后本进程会收到回调。
     * 回调发生在提交 `commit()` 的那个线程（Provider 的 Binder 线程或清空记录的后台线程），
     * 因此必须切回主线程再写 Compose 状态。
     */
    private val recordsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { refreshRecords() } }

    /**
     * 框架连接状态的观察者。保存为字段而不是每次用 `::applyServiceState`，
     * 因为 [ServiceBridge.clearObserver] 按引用比对，方法引用每次求值可能是新对象。
     */
    private val serviceObserver: () -> Unit = { applyServiceState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        preferences = BlockRecordStore.preferences(this)
        refreshRecords()

        setContent {
            MiuixTheme(
                controller = remember {
                    ThemeController(colorSchemeMode = ColorSchemeMode.System)
                }
            ) {
                SettingsScreen(
                    status = status,
                    records = records,
                    onClearRecords = ::clearRecords,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 记录只由本进程写入，注册监听即可拿到实时更新，无需轮询。
        preferences?.registerOnSharedPreferenceChangeListener(recordsListener)
        refreshRecords()

        // 框架 service 由进程级单例统一持有：本类只观察，不直接注册，
        // 避免静态 listener 强引用已销毁的 Activity。
        ServiceBridge.start()
        ServiceBridge.observe(serviceObserver)
    }

    override fun onStop() {
        preferences?.unregisterOnSharedPreferenceChangeListener(recordsListener)
        ServiceBridge.clearObserver(serviceObserver)
        super.onStop()
    }

    /** 用 [ServiceBridge] 的最新状态刷新界面；回调已保证在主线程。 */
    private fun applyServiceState() {
        val service = ServiceBridge.service
        status = if (service == null) {
            ModuleStatus.DISCONNECTED
        } else {
            try {
                ModuleStatus(
                    bound = true,
                    frameworkName = service.frameworkName,
                    frameworkVersion = service.frameworkVersion,
                    frameworkVersionCode = service.frameworkVersionCode,
                    apiVersion = service.apiVersion,
                    scope = runCatching { service.scope.toList() }.getOrDefault(emptyList()),
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to query Xposed service", t)
                ModuleStatus.DISCONNECTED
            }
        }
    }

    /** 从本进程私有的记录文件重新读取快照。 */
    private fun refreshRecords() {
        val store = preferences ?: run {
            records = RecordSnapshot.UNAVAILABLE
            return
        }
        records = runCatching {
            RecordSnapshot(
                available = true,
                total = BlockRecordStore.total(store),
                entries = BlockRecordStore.recent(store),
            )
        }.getOrElse { t ->
            Log.e(TAG, "Failed to read block records", t)
            RecordSnapshot.UNAVAILABLE
        }
    }

    private fun clearRecords() {
        val store = preferences ?: return
        // clear() 内部是同步 commit()，放到后台线程避免在主线程上写盘。
        Thread {
            BlockRecordStore.clear(store)
            runOnUiThread { refreshRecords() }
        }.start()
    }

    private companion object {
        const val TAG = "AppListBlocker"
    }
}

/**
 * 设置界面持有的记录快照。[available] 为 false 表示本进程的本地记录存储读取失败，
 * 此时界面只提示不可用，不展示计数与明细。记录与框架连接无关：它由模块进程自己写入。
 */
private data class RecordSnapshot(
    val available: Boolean,
    val total: Int,
    val entries: List<BlockRecordStore.Entry>,
) {
    companion object {
        val UNAVAILABLE = RecordSnapshot(false, 0, emptyList())
    }
}

private data class ModuleStatus(
    val bound: Boolean,
    val frameworkName: String?,
    val frameworkVersion: String?,
    val frameworkVersionCode: Long,
    val apiVersion: Int,
    val scope: List<String>,
) {
    val enabled: Boolean get() = bound

    val scoped: Boolean get() = scope.contains(SCOPE_PACKAGE)

    val active: Boolean get() = enabled && scoped

    companion object {
        val DISCONNECTED = ModuleStatus(false, null, null, 0L, 0, emptyList())
    }
}

@Composable
private fun SettingsScreen(
    status: ModuleStatus,
    records: RecordSnapshot,
    onClearRecords: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.app_name),
                subtitle = stringResource(R.string.xposed_description),
                scrollBehavior = scrollBehavior,
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
        ) {
            ActivationCard(status = status)

            Spacer(Modifier.height(8.dp))

            SmallTitle(stringResource(R.string.section_status))
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                BasicComponent(
                    title = stringResource(R.string.status_channel),
                    summary = stringResource(
                        if (status.enabled) R.string.status_channel_ok else R.string.status_channel_missing
                    ),
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                BasicComponent(
                    title = stringResource(R.string.status_framework),
                    summary = frameworkSummary(status),
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                BasicComponent(
                    title = stringResource(R.string.status_scope),
                    summary = if (status.scoped) SCOPE_PACKAGE else stringResource(
                        R.string.status_scope_missing, SCOPE_PACKAGE
                    ),
                )
            }

            Spacer(Modifier.height(8.dp))

            SmallTitle(stringResource(R.string.section_records))
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                if (!records.available) {
                    Text(
                        text = stringResource(R.string.records_unavailable),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                } else if (records.total == 0) {
                    Text(
                        text = stringResource(R.string.records_empty),
                        style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp),
                    )
                    Text(
                        text = stringResource(R.string.records_empty_hint),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 16.dp),
                    )
                } else {
                    BasicComponent(
                        title = stringResource(R.string.records_total),
                        summary = stringResource(R.string.records_count, records.total),
                    )

                    if (records.entries.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                        Text(
                            text = stringResource(R.string.records_recent),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                        )

                        val formatter = remember {
                            SimpleDateFormat(RECORDS_TIME_PATTERN, Locale.getDefault())
                        }

                        records.entries.forEach { entry ->
                            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                            BasicComponent(
                                title = recordTypeLabel(entry.type),
                                summary = formatter.format(Date(entry.timestamp)),
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                    TextButton(
                        text = stringResource(R.string.records_clear),
                        onClick = onClearRecords,
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            SmallTitle(stringResource(R.string.section_about))
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                BasicComponent(
                    title = stringResource(R.string.about_version),
                    summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                BasicComponent(
                    title = stringResource(R.string.about_build_time),
                    summary = BuildConfig.BUILD_TIME,
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                BasicComponent(
                    title = stringResource(R.string.about_detail),
                    summary = stringResource(R.string.module_description),
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                Text(
                    text = stringResource(R.string.about_license),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(16.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ActivationCard(status: ModuleStatus) {
    val active = status.active
    val background = if (active) ActivationBackground else MiuixTheme.colorScheme.surfaceContainerHigh
    val accent = if (active) ActivationAccent else MiuixTheme.colorScheme.outline
    val contentColor = if (active) ActivationText else MiuixTheme.colorScheme.onSurfaceContainer

    val title = stringResource(
        if (active) R.string.activation_active else R.string.activation_inactive
    )
    val summary = when {
        active -> stringResource(R.string.activation_version, BuildConfig.VERSION_NAME)
        !status.enabled -> stringResource(R.string.activation_hint_enable)
        else -> stringResource(R.string.activation_hint_scope, SCOPE_PACKAGE)
    }

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .height(132.dp),
        colors = CardDefaults.defaultColors(color = background, contentColor = contentColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            ) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.title3,
                    color = contentColor,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.body2,
                    color = contentColor,
                )
            }

            ActivationBadge(
                color = accent,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 27.dp, y = 31.dp)
                    .size(110.dp),
            )
        }
    }
}

@Composable
private fun ActivationBadge(color: Color, modifier: Modifier = Modifier) {
    val iconPath = remember {
        PathParser().parsePathString(CHECK_CIRCLE_OUTLINE_PATH).toPath()
    }

    Canvas(modifier = modifier) {
        val scale = size.width / 24f
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            drawPath(path = iconPath, color = color)
        }
    }
}

private const val CHECK_CIRCLE_OUTLINE_PATH =
    "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2z" +
        "m0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z" +
        "m3.88-11.71L10 14.17l-1.88-1.88c-.39-.39-1.02-.39-1.41 0-.39.39-.39 1.02 0 1.41" +
        "l2.59 2.59c.39.39 1.02.39 1.41 0L17.3 9.7c.39-.39.39-1.02 0-1.41-.39-.39-1.03-.39-1.42 0z"

@Composable
private fun recordTypeLabel(type: String): String = when (type) {
    BlockRecordStore.TYPE_EGRESS -> stringResource(R.string.records_egress)
    BlockRecordStore.TYPE_COLLECTOR -> stringResource(R.string.records_collector)
    else -> type
}

@Composable
private fun frameworkSummary(status: ModuleStatus): String {
    val name = status.frameworkName
    if (name.isNullOrEmpty()) {
        return stringResource(R.string.status_framework_unknown)
    }
    val version = status.frameworkVersion.orEmpty()
    val builder = StringBuilder(name)
    if (version.isNotEmpty()) {
        builder.append(' ').append(version)
    }
    if (status.frameworkVersionCode > 0L) {
        builder.append(" (").append(status.frameworkVersionCode).append(')')
    }
    if (status.apiVersion > 0) {
        builder.append(" · API ").append(status.apiVersion)
    }
    return builder.toString()
}

private val ActivationBackground = Color(0xFFDFFAE4)
private val ActivationAccent = Color(0xFF36D167)
private val ActivationText = Color(0xFF101010)

private const val SCOPE_PACKAGE = "com.miui.guardprovider"

private const val RECORDS_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"
