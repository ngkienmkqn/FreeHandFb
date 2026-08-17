package com.example.commenthelper

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
    val activeTypes by ExecutorForegroundService.activeTypes.collectAsState()
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
    var selJoin by remember { mutableStateOf(prefs.getBoolean("sel_join", true)) }
    var selInteract by remember { mutableStateOf(prefs.getBoolean("sel_interaction", true)) }
    var selPublish by remember { mutableStateOf(prefs.getBoolean("sel_publishing", false)) }
    var startHint by remember { mutableStateOf("") }

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
                val interaction = json.getJSONObject("interaction").getJSONObject("counts").optInt("QUEUED", 0)
                val publishing = json.getJSONObject("publishing").getJSONObject("counts").optInt("QUEUED", 0)
                val join = json.optJSONObject("join")?.getJSONObject("counts")?.optInt("QUEUED", 0) ?: 0
                ExecutorForegroundService.queueCounts.value = Triple(join, interaction, publishing)
            }
            delay(10_000)
        }
    }

    fun persistSelections() {
        prefs.edit()
            .putBoolean("sel_join", selJoin)
            .putBoolean("sel_interaction", selInteract)
            .putBoolean("sel_publishing", selPublish)
            .apply()
    }

    fun selectedTypes(): List<String> = buildList {
        if (selJoin) add(ExecutorForegroundService.TYPE_JOIN)
        if (selInteract) add(ExecutorForegroundService.TYPE_INTERACTION)
        if (selPublish) add(ExecutorForegroundService.TYPE_PUBLISHING)
    }

    fun startSession() {
        if (!accessibilityEnabled) {
            openAccessibilitySettings(context)
            return
        }
        val types = selectedTypes()
        if (types.isEmpty()) {
            startHint = "Chọn ít nhất một loại job."
            return
        }
        startHint = ""
        persistSelections()
        val intent = Intent(context, ExecutorForegroundService::class.java)
            .setAction(ExecutorForegroundService.ACTION_START)
            .putStringArrayListExtra(ExecutorForegroundService.EXTRA_TYPES, ArrayList(types))
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopSession() {
        context.startService(
            Intent(context, ExecutorForegroundService::class.java)
                .setAction(ExecutorForegroundService.ACTION_STOP)
        )
    }

    val isRunning = activeMode != null
    val (joinQ, interactQ, publishQ) = queueCounts

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
                            if (isRunning) stopSession()
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
                    Tab(selectedTab == 0, { selectedTab = 0 }, text = { Text("CHẠY JOB") })
                    Tab(selectedTab == 1, { selectedTab = 1 }, text = { Text("CÀI ĐẶT") })
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (selectedTab == 1) {
                ExecutorSettingsTab(prefs = prefs, authToken = authToken)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Spacer(Modifier.height(4.dp))
                    Text("CHẠY JOB FACEBOOK", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Chọn loại job; ưu tiên claim: Join → Tương tác → Đăng bài. Một job Facebook tại một thời điểm.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Loại job", fontWeight = FontWeight.SemiBold)
                            TypeCheckRow(
                                checked = selJoin,
                                label = "Join nhóm",
                                count = joinQ
                            ) {
                                selJoin = it
                                persistSelections()
                                if (isRunning) startSession()
                            }
                            TypeCheckRow(
                                checked = selInteract,
                                label = "Tương tác",
                                count = interactQ
                            ) {
                                selInteract = it
                                persistSelections()
                                if (isRunning) startSession()
                            }
                            TypeCheckRow(
                                checked = selPublish,
                                label = "Đăng bài",
                                count = publishQ
                            ) {
                                selPublish = it
                                persistSelections()
                                if (isRunning) startSession()
                            }
                        }
                    }

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatusRow("Trạng thái", if (isRunning) "● $executorStatus" else "Đang dừng")
                            StatusRow(
                                "Queue chờ",
                                "Join $joinQ · Tương tác $interactQ · Đăng $publishQ"
                            )
                            if (isRunning && activeTypes.isNotEmpty()) {
                                StatusRow(
                                    "Đang nhận",
                                    activeTypes.joinToString(", ") {
                                        when (it) {
                                            ExecutorForegroundService.TYPE_JOIN -> "Join"
                                            ExecutorForegroundService.TYPE_INTERACTION -> "Tương tác"
                                            ExecutorForegroundService.TYPE_PUBLISHING -> "Đăng bài"
                                            else -> it
                                        }
                                    }
                                )
                            }
                            StatusRow("Tiến độ phiên", "${sessionProgress.first} / ${sessionProgress.second}")
                            currentJobId?.takeIf { isRunning }?.let { StatusRow("Đang xử lý", it) }
                            if (isRunning && currentJobId != null) StatusRow("Bước hiện tại", accessibilityStatus)
                            LinearProgressIndicator(
                                progress = {
                                    if (sessionProgress.second == 0) 0f
                                    else sessionProgress.first.toFloat() / sessionProgress.second
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    if (isRunning) {
                        Button(onClick = { stopSession() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Text("■ DỪNG", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = { startSession() },
                            enabled = selectedTypes().isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            Text("▶ BẮT ĐẦU", fontWeight = FontWeight.Bold)
                        }
                    }

                    if (startHint.isNotBlank()) {
                        Text(startHint, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Accessibility: ${if (accessibilityEnabled) "✅ Sẵn sàng" else "❌ Chưa bật"}",
                            modifier = Modifier.weight(1f)
                        )
                        if (!accessibilityEnabled) {
                            OutlinedButton(onClick = { openAccessibilitySettings(context) }) { Text("Bật") }
                        }
                    }
                    Text(
                        "Lỗi gần nhất: ${lastError.ifBlank { "Không có" }}",
                        modifier = Modifier.fillMaxWidth(),
                        color = if (lastError.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
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
private fun TypeCheckRow(
    checked: Boolean,
    label: String,
    count: Int,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, modifier = Modifier.weight(1f))
        Text("$count chờ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
