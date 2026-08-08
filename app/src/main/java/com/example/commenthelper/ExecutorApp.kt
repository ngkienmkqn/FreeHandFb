package com.example.commenthelper

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExecutorApp(
    prefs: SharedPreferences,
    authToken: String,
    username: String,
    userGroup: String,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val activeMode by ExecutorForegroundService.activeMode.collectAsState()
    val connected by ExecutorForegroundService.isConnected.collectAsState()
    val queueCounts by ExecutorForegroundService.queueCounts.collectAsState()
    val sessionProgress by ExecutorForegroundService.sessionProgress.collectAsState()
    val currentJobId by ExecutorForegroundService.currentJobId.collectAsState()
    val executorStatus by ExecutorForegroundService.executorStatus.collectAsState()
    val lastError by ExecutorForegroundService.lastError.collectAsState()
    val accessibilityStatus by FbAutoService.currentStatusText.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var accessibilityEnabled by remember { mutableStateOf(FbAutoService.isServiceEnabled(context)) }
    var showUpdate by remember { mutableStateOf<JSONObject?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) accessibilityEnabled = FbAutoService.isServiceEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(authToken) {
        withContext(Dispatchers.IO) {
            val version = executorRequest("/api/app-version", authToken)
            if (version.first == 200 && !version.second.isNullOrBlank()) {
                val json = JSONObject(version.second!!)
                if (json.optString("appVersion").isNotBlank() && json.optString("appVersion") != "1.0.0" && json.optString("apkUrl").isNotBlank()) {
                    showUpdate = json
                }
            }
            val ver = prefs.getString("ota_version", "latest") ?: "latest"
            val engine = executorRequest("/api/engine/script?version=$ver", authToken)
            if (engine.first == 200 && !engine.second.isNullOrBlank()) {
                prefs.edit().putString("engine_script", engine.second).apply()
                FbAutoService.Engine.load(context)
            }
            // Pull syncable settings from server into prefs
            val me = executorRequest("/api/me", authToken)
            if (me.first == 200 && !me.second.isNullOrBlank()) {
                try {
                    val j = JSONObject(me.second!!)
                    val e = prefs.edit()
                    if (j.has("facebookName")) e.putString("facebookName", j.optString("facebookName", ""))
                    val settings = j.optJSONObject("settings")
                    if (settings != null && settings.has("block_timeout_hours")) {
                        e.putInt("block_timeout_hours", settings.getInt("block_timeout_hours"))
                    }
                    e.apply()
                } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(authToken) {
        while (true) {
            val response = executorRequest("/api/executor/queues", authToken)
            ExecutorForegroundService.isConnected.value = response.first in 200..299
            if (response.first == 200 && !response.second.isNullOrBlank()) {
                val json = JSONObject(response.second!!)
                ExecutorForegroundService.queueCounts.value =
                    json.getJSONObject("interaction").getJSONObject("counts").optInt("QUEUED", 0) to
                    json.getJSONObject("publishing").getJSONObject("counts").optInt("QUEUED", 0)
            }
            delay(10_000)
        }
    }

    fun start(mode: String) {
        if (!accessibilityEnabled) {
            openAccessibilitySettings(context)
            return
        }
        val intent = Intent(context, ExecutorForegroundService::class.java)
            .setAction(ExecutorForegroundService.ACTION_START)
            .putExtra(ExecutorForegroundService.EXTRA_MODE, mode)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(mode: String) {
        context.startService(Intent(context, ExecutorForegroundService::class.java)
            .setAction(ExecutorForegroundService.ACTION_STOP)
            .putExtra(ExecutorForegroundService.EXTRA_MODE, mode))
    }

    Scaffold(
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = { Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("FreeHand Executor", fontWeight = FontWeight.Bold)
                        Text("$username · $userGroup", style = MaterialTheme.typography.labelSmall)
                    } },
                    actions = {
                        TextButton(onClick = {
                            activeMode?.let { stop(it) }
                            onLogout()
                        }) { Text("Thoát", color = MaterialTheme.colorScheme.error) }
                    }
                )
                Surface(color = if (connected) Color(0xFFE8F5E9) else Color(0xFFFFEBEE), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (connected) "● Server đã kết nối" else "● Mất kết nối server",
                        color = if (connected) Color(0xFF1B5E20) else Color(0xFFB71C1C),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selectedTab == 0, { selectedTab = 0 }, text = { Text("TƯƠNG TÁC") })
                    Tab(selectedTab == 1, { selectedTab = 1 }, text = { Text("ĐĂNG BÀI") })
                    Tab(selectedTab == 2, { selectedTab = 2 }, text = { Text("CÀI ĐẶT") })
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (selectedTab == 2) {
                ExecutorSettingsTab(prefs = prefs, authToken = authToken)
            } else {
                val mode = if (selectedTab == 0) ExecutorForegroundService.MODE_INTERACTION else ExecutorForegroundService.MODE_PUBLISHING
                ExecutorPanel(
                    title = if (selectedTab == 0) "LUỒNG TƯƠNG TÁC" else "LUỒNG ĐĂNG BÀI",
                    mode = mode,
                    activeMode = activeMode,
                    queueCount = if (selectedTab == 0) queueCounts.first else queueCounts.second,
                    sessionProgress = sessionProgress,
                    currentJobId = currentJobId,
                    executorStatus = executorStatus,
                    accessibilityStatus = accessibilityStatus,
                    accessibilityEnabled = accessibilityEnabled,
                    lastError = lastError,
                    onStart = { start(mode) },
                    onStop = { stop(mode) },
                    onEnableAccessibility = { openAccessibilitySettings(context) }
                )
            }
        }
    }

    showUpdate?.let { update ->
        AlertDialog(
            onDismissRequest = { showUpdate = null },
            title = { Text("Có phiên bản mới") },
            text = { Text(update.optString("changelog", "Phiên bản ${update.optString("appVersion")}")) },
            confirmButton = { FilledTonalButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.optString("apkUrl"))))
                showUpdate = null
            }) { Text("Tải APK") } },
            dismissButton = { TextButton(onClick = { showUpdate = null }) { Text("Để sau") } }
        )
    }
}

@Composable
private fun ExecutorPanel(
    title: String,
    mode: String,
    activeMode: String?,
    queueCount: Int,
    sessionProgress: Pair<Int, Int>,
    currentJobId: String?,
    executorStatus: String,
    accessibilityStatus: String,
    accessibilityEnabled: Boolean,
    lastError: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEnableAccessibility: () -> Unit
) {
    val isThisRunning = activeMode == mode
    val otherModeRunning = activeMode != null && activeMode != mode
    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusRow("Trạng thái", if (isThisRunning) "● $executorStatus" else "Đang dừng")
                StatusRow("Queue chờ", "$queueCount yêu cầu")
                StatusRow("Tiến độ phiên", "${sessionProgress.first} / ${sessionProgress.second}")
                currentJobId?.takeIf { isThisRunning }?.let { StatusRow("Đang xử lý", it) }
                if (isThisRunning && currentJobId != null) StatusRow("Bước hiện tại", accessibilityStatus)
                LinearProgressIndicator(
                    progress = { if (sessionProgress.second == 0) 0f else sessionProgress.first.toFloat() / sessionProgress.second },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (isThisRunning) {
            Button(onClick = onStop, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("■ DỪNG", fontWeight = FontWeight.Bold) }
        } else {
            Button(onClick = onStart, enabled = !otherModeRunning, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("▶ BẮT ĐẦU", fontWeight = FontWeight.Bold)
            }
        }

        if (otherModeRunning) {
            Text(
                "Luồng ${if (activeMode == ExecutorForegroundService.MODE_INTERACTION) "Tương tác" else "Đăng bài"} đang sử dụng Facebook. Hãy dừng luồng đó trước.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Accessibility: ${if (accessibilityEnabled) "✅ Sẵn sàng" else "❌ Chưa bật"}", modifier = Modifier.weight(1f))
            if (!accessibilityEnabled) OutlinedButton(onClick = onEnableAccessibility) { Text("Bật") }
        }
        Text(
            "Lỗi gần nhất: ${lastError.ifBlank { "Không có" }}",
            modifier = Modifier.fillMaxWidth(),
            color = if (lastError.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ExecutorSettingsTab(prefs: SharedPreferences, authToken: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var blockHourTxt by remember { mutableStateOf(prefs.getInt("block_timeout_hours", 24).toString()) }
    var facebookName by remember { mutableStateOf(prefs.getString("facebookName", "") ?: "") }
    var otaVersion by remember { mutableStateOf(prefs.getString("ota_version", "latest") ?: "latest") }
    var saveStatus by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(authToken) {
        val me = executorRequest("/api/me", authToken)
        if (me.first == 200 && !me.second.isNullOrBlank()) {
            try {
                val j = JSONObject(me.second!!)
                val e = prefs.edit()
                if (j.has("facebookName")) {
                    val name = j.optString("facebookName", "")
                    e.putString("facebookName", name)
                    facebookName = name
                }
                val settings = j.optJSONObject("settings")
                if (settings != null && settings.has("block_timeout_hours")) {
                    val hours = settings.getInt("block_timeout_hours")
                    e.putInt("block_timeout_hours", hours)
                    blockHourTxt = hours.toString()
                }
                e.apply()
            } catch (_: Exception) {}
        }
        otaVersion = prefs.getString("ota_version", "latest") ?: "latest"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Cài đặt Executor", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Lưu cục bộ; giờ nghỉ block và tên FB đồng bộ lên server.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = blockHourTxt,
            onValueChange = { blockHourTxt = it.filter { c -> c.isDigit() }.take(3) },
            label = { Text("Nghỉ khi block (giờ)") },
            supportingText = { Text("Nhập 0 để tắt. Dùng khi FbAutoService phát hiện block.") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = facebookName,
            onValueChange = { facebookName = it },
            label = { Text("Tên Facebook") },
            supportingText = { Text("Dùng để bỏ qua bình luận của chính mình.") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = otaVersion,
            onValueChange = { otaVersion = it.trim() },
            label = { Text("Phiên bản OTA (script)") },
            supportingText = { Text("Mặc định: latest. Pin version để tải engine script.") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("latest") }
        )

        Button(
            onClick = {
                saving = true
                saveStatus = ""
                val hours = blockHourTxt.toIntOrNull() ?: 24
                val ver = otaVersion.ifBlank { "latest" }
                otaVersion = ver
                prefs.edit()
                    .putInt("block_timeout_hours", hours)
                    .putString("facebookName", facebookName.trim())
                    .putString("ota_version", ver)
                    .apply()
                scope.launch {
                    try {
                        // Mirror MainActivity: settings.block_timeout_hours + top-level facebookName
                        val settings = JSONObject().put("block_timeout_hours", hours)
                        val body = JSONObject()
                            .put("settings", settings)
                            .put("facebookName", facebookName.trim())
                        val put = executorRequest("/api/me", authToken, method = "PUT", json = body.toString())
                        val engine = executorRequest("/api/engine/script?version=$ver", authToken)
                        if (engine.first == 200 && !engine.second.isNullOrBlank()) {
                            prefs.edit().putString("engine_script", engine.second).apply()
                            FbAutoService.Engine.load(context)
                        }
                        saveStatus = when {
                            put.first in 200..299 -> "Đã lưu và đồng bộ"
                            put.first == -1 -> "Đã lưu cục bộ (lỗi mạng: ${put.second})"
                            else -> "Đã lưu cục bộ (sync HTTP ${put.first})"
                        }
                    } catch (e: Exception) {
                        saveStatus = "Đã lưu cục bộ (${e.message})"
                    } finally {
                        saving = false
                    }
                }
            },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(if (saving) "Đang lưu..." else "LƯU CÀI ĐẶT", fontWeight = FontWeight.Bold)
        }

        if (saveStatus.isNotBlank()) {
            Text(saveStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private suspend fun executorRequest(
    path: String,
    token: String,
    method: String = "GET",
    json: String? = null
): Pair<Int, String?> = withContext(Dispatchers.IO) {
    try {
        val conn = URL("$SERVER_URL$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8_000
        conn.readTimeout = 10_000
        if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
        if (json != null) {
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(json) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        code to try { stream?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null }
    } catch (e: Exception) { -1 to e.message }
}
