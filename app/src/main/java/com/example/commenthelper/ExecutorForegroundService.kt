package com.example.commenthelper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ExecutorForegroundService : Service() {
    companion object {
        const val ACTION_START = "com.example.commenthelper.executor.START"
        const val ACTION_STOP = "com.example.commenthelper.executor.STOP"
        const val EXTRA_MODE = "mode"
        const val MODE_INTERACTION = "interaction"
        const val MODE_PUBLISHING = "publishing"

        val activeMode = MutableStateFlow<String?>(null)
        val isConnected = MutableStateFlow(false)
        val queueCounts = MutableStateFlow(0 to 0)
        val sessionProgress = MutableStateFlow(0 to 0)
        val currentJobId = MutableStateFlow<String?>(null)
        val executorStatus = MutableStateFlow("Đang dừng")
        val lastError = MutableStateFlow("")
    }

    private data class ClaimedJob(
        val id: String,
        val type: String,
        val payload: JSONObject,
        val leaseToken: String,
        val irreversible: Boolean = false
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var workerJob: Job? = null
    @Volatile private var claimedJob: ClaimedJob? = null
    @Volatile private var irreversibleReached = false
    private val prefs by lazy { getSharedPreferences("comment_helper_prefs", Context.MODE_PRIVATE) }

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getStringExtra("postId") ?: return
            val active = claimedJob ?: return
            if (id != active.id) return
            val success = intent.getBooleanExtra("success", false)
            val reasonCode = intent.getStringExtra("reasonCode") ?: "ACCESSIBILITY_FAILED"
            val error = intent.getStringExtra("error") ?: "Accessibility không hoàn thành được thao tác."
            val step = intent.getStringExtra("step") ?: ""
            val retryable = intent.getBooleanExtra("retryable", true)
            scope.launch { finishClaimedJob(success, reasonCode, error, step, retryable) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val filter = IntentFilter("com.example.commenthelper.POST_DONE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(resultReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(resultReceiver, filter)

        FbAutoService.onIrreversibleAction = { jobId ->
            val active = claimedJob
            if (active == null || active.id != jobId) false
            else runBlocking(Dispatchers.IO) {
                val ok = postLifecycle(active, "checkpoint", JSONObject()) in 200..299
                if (ok) irreversibleReached = true
                ok
            }
        }
        FbAutoService.onActionProgress = { jobId, action, status ->
            val active = claimedJob
            if (active == null || active.id != jobId) false
            else runBlocking(Dispatchers.IO) {
                postLifecycle(active, "actions/$action", JSONObject().put("status", status)) in 200..299
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopExecutor(intent.getStringExtra(EXTRA_MODE))
            ACTION_START -> startExecutor(intent.getStringExtra(EXTRA_MODE))
            else -> prefs.getString("executor_running_mode", null)?.let { startExecutor(it) }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startExecutor(mode: String?) {
        if (mode != MODE_INTERACTION && mode != MODE_PUBLISHING) return
        val running = activeMode.value
        if (running != null && running != mode) {
            lastError.value = "Luồng ${modeLabel(running)} đang sử dụng Facebook."
            return
        }
        if (workerJob?.isActive == true) return

        activeMode.value = mode
        executorStatus.value = "Đang kết nối server..."
        lastError.value = ""
        prefs.edit().putString("executor_running_mode", mode).apply()
        startForeground(2206, notification("Đang khởi động ${modeLabel(mode)}"))
        workerJob = scope.launch { workerLoop(mode) }
    }

    private fun stopExecutor(requestedMode: String?) {
        val running = activeMode.value ?: return
        if (requestedMode != null && requestedMode != running) return
        prefs.edit().remove("executor_running_mode").apply()
        activeMode.value = null
        executorStatus.value = "Đang dừng an toàn..."
        workerJob?.cancel()
        workerJob = null

        scope.launch {
            val active = claimedJob
            FbAutoService.instance?.stopProcessing()
            if (active != null) {
                val body = JSONObject()
                    .put("safeToRetry", !irreversibleReached)
                    .put("error", "Người dùng dừng executor trên điện thoại.")
                postLifecycle(active, "interrupted", body)
            }
            clearActiveJob()
            executorStatus.value = "Đang dừng"
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun workerLoop(mode: String) {
        var lastHeartbeat = 0L
        var lastSummary = 0L
        try {
            while (activeMode.value == mode) {
                val now = System.currentTimeMillis()
                if (now - lastSummary > 10_000) {
                    refreshQueueSummary()
                    lastSummary = now
                }

                val active = claimedJob
                if (active == null) {
                    executorStatus.value = "Đang chờ yêu cầu"
                    updateNotification("${modeLabel(mode)} · Đang chờ yêu cầu")
                    claimNext(mode)?.let { claimed ->
                        claimedJob = claimed
                        irreversibleReached = claimed.irreversible
                        currentJobId.value = claimed.id
                        sessionProgress.value = sessionProgress.value.first to (sessionProgress.value.second + 1)
                        if (claimed.irreversible) {
                            postLifecycle(claimed, "interrupted", JSONObject()
                                .put("safeToRetry", false)
                                .put("error", "Khôi phục job sau checkpoint; cần kiểm tra thủ công."))
                            clearActiveJob()
                        } else {
                            dispatchToAccessibility(claimed)
                            lastHeartbeat = now
                        }
                    }
                } else if (now - lastHeartbeat > 15_000) {
                    val code = postLifecycle(active, "heartbeat", JSONObject())
                    if (code == 409) {
                        lastError.value = "Lease job đã hết hạn."
                        FbAutoService.instance?.stopProcessing()
                        clearActiveJob()
                    }
                    lastHeartbeat = now
                }
                delay(2_000)
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
        } catch (e: Exception) {
            isConnected.value = false
            lastError.value = e.message ?: "Executor gặp lỗi không xác định."
            executorStatus.value = "Mất kết nối, đang thử lại..."
            if (activeMode.value == mode) {
                delay(5_000)
                workerJob = scope.launch { workerLoop(mode) }
            }
        }
    }

    private suspend fun claimNext(mode: String): ClaimedJob? {
        val token = prefs.getString("auth_token", "") ?: ""
        if (token.isBlank()) {
            lastError.value = "Phiên đăng nhập không hợp lệ."
            return null
        }
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val response = request("/api/executor/$mode/claim", "POST", JSONObject().put("deviceId", deviceId), token)
        isConnected.value = response.first in 200..299
        if (response.first == 204) return null
        if (response.first == 409) {
            lastError.value = response.second?.let { JSONObject(it).optString("error") } ?: "Luồng khác đang chạy."
            delay(3_000)
            return null
        }
        if (response.first !in 200..299 || response.second.isNullOrBlank()) {
            lastError.value = "Không claim được job (HTTP ${response.first})."
            delay(5_000)
            return null
        }
        val body = JSONObject(response.second!!)
        val job = body.getJSONObject("job")
        return ClaimedJob(
            id = job.getString("id"), type = job.getString("type"),
            payload = job.getJSONObject("payload"), leaseToken = body.getString("leaseToken"),
            irreversible = job.optLong("irreversibleAt", 0L) > 0L
        )
    }

    private suspend fun dispatchToAccessibility(job: ClaimedJob) {
        val accessibility = FbAutoService.instance
        if (accessibility == null || !FbAutoService.isServiceEnabled(this)) {
            lastError.value = "Accessibility chưa sẵn sàng."
            postLifecycle(job, "interrupted", JSONObject().put("safeToRetry", true).put("error", lastError.value))
            clearActiveJob()
            return
        }

        executorStatus.value = if (job.type == MODE_INTERACTION) "Đang tương tác" else "Đang chuẩn bị đăng bài"
        updateNotification("${modeLabel(job.type)} · ${job.id}")
        withContext(Dispatchers.IO) {
            if (job.type == MODE_PUBLISHING) {
                val images = job.payload.optJSONArray("images")
                val imageUrls = if (images == null) emptyList() else (0 until images.length()).map { images.getString(it) }
                if (!downloadImages(imageUrls)) {
                    postLifecycle(job, "fail", JSONObject().put("error", "Không tải đủ ảnh của job."))
                    clearActiveJob()
                    return@withContext
                }
                withContext(Dispatchers.Main) {
                    accessibility.startPublishing(listOf(FbAutoService.TaskItem(
                        postId = job.id,
                        url = job.payload.getString("groupUrl"),
                        comment = job.payload.getString("content"),
                        isPublishingGroup = true,
                        imageCount = imageUrls.size,
                        executorJobId = job.id,
                        reportLegacyCompletion = false
                    )), appendNotificationScan = false)
                }
            } else {
                val targetPost = job.payload.optJSONObject("targetPost")
                val targetAnchorsJson = targetPost?.optJSONArray("anchors")
                val actions = job.payload.optJSONObject("actions")
                val actionLike = actions?.optBoolean("like", true) ?: true
                val actionComment = actions?.optBoolean("comment", true) ?: true
                val targetAnchors = if (targetAnchorsJson == null) emptyList() else
                    (0 until targetAnchorsJson.length()).mapNotNull { index ->
                        targetAnchorsJson.optString(index).trim().takeIf { it.isNotEmpty() }
                    }
                withContext(Dispatchers.Main) {
                    accessibility.startProcessing(listOf(FbAutoService.TaskItem(
                        postId = job.id,
                        url = job.payload.getString("url"),
                        comment = job.payload.getString("comment"),
                        executorJobId = job.id,
                        reportLegacyCompletion = false,
                        targetPostAuthor = targetPost?.optString("author").orEmpty(),
                        targetPostText = targetPost?.optString("text").orEmpty(),
                        targetPostAnchors = targetAnchors,
                        actionLike = actionLike,
                        actionComment = actionComment
                    )), appendNotificationScan = false)
                }
            }
        }
    }

    private suspend fun finishClaimedJob(success: Boolean, reasonCode: String, error: String, step: String, retryable: Boolean) {
        val active = claimedJob ?: return
        val endpoint = if (success) "complete" else "fail"
        val body = if (success) JSONObject().put("result", JSONObject().put("completedAt", System.currentTimeMillis()))
        else JSONObject()
            .put("reasonCode", reasonCode)
            .put("error", error)
            .put("step", step)
            .put("retryable", retryable && !irreversibleReached)
        val code = postLifecycle(active, endpoint, body)
        if (code !in 200..299) lastError.value = "Không báo được kết quả job ${active.id} (HTTP $code)."
        else if (!success) lastError.value = "Job ${active.id} thất bại."
        sessionProgress.value = (sessionProgress.value.first + 1) to sessionProgress.value.second
        clearActiveJob()
    }

    private suspend fun refreshQueueSummary() {
        val token = prefs.getString("auth_token", "") ?: ""
        if (token.isBlank()) return
        val response = request("/api/executor/queues", "GET", null, token)
        isConnected.value = response.first in 200..299
        if (response.first == 401) lastError.value = "Phiên đăng nhập đã hết hạn."
        if (response.first == 200 && !response.second.isNullOrBlank()) {
            val json = JSONObject(response.second!!)
            val interaction = json.getJSONObject("interaction").getJSONObject("counts").optInt("QUEUED", 0)
            val publishing = json.getJSONObject("publishing").getJSONObject("counts").optInt("QUEUED", 0)
            queueCounts.value = interaction to publishing
        }
    }

    private suspend fun postLifecycle(job: ClaimedJob, action: String, body: JSONObject): Int {
        body.put("leaseToken", job.leaseToken)
        val token = prefs.getString("auth_token", "") ?: ""
        val response = request("/api/executor/jobs/${job.id}/$action", "POST", body, token)
        isConnected.value = response.first in 200..299
        return response.first
    }

    private fun clearActiveJob() {
        claimedJob = null
        irreversibleReached = false
        currentJobId.value = null
        if (activeMode.value != null) executorStatus.value = "Đang chờ yêu cầu"
    }

    private suspend fun request(path: String, method: String, body: JSONObject?, token: String): Pair<Int, String?> = withContext(Dispatchers.IO) {
        try {
            val conn = URL(SERVER_URL + path).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to try { stream?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null }
        } catch (e: Exception) {
            -1 to e.message
        }
    }

    private suspend fun downloadImages(urls: List<String>): Boolean = withContext(Dispatchers.IO) {
        urls.forEachIndexed { index, source ->
            try {
                val isPng = source.substringBefore('?').endsWith(".png", true) || source.startsWith("data:image/png")
                val ext = if (isPng) "png" else "jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "FreeHand_${System.currentTimeMillis()}_$index.$ext")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/$ext")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FreeHand")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
                try {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        if (source.startsWith("data:image")) {
                            output.write(android.util.Base64.decode(source.substringAfter(','), android.util.Base64.DEFAULT))
                        } else {
                            val conn = URL(source).openConnection() as HttpURLConnection
                            conn.connectTimeout = 15_000
                            conn.readTimeout = 30_000
                            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                            conn.inputStream.use { it.copyTo(output) }
                        }
                    } ?: error("Không mở được output stream")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                    }
                } catch (e: Exception) {
                    contentResolver.delete(uri, null, null)
                    throw e
                }
            } catch (e: Exception) {
                lastError.value = "Tải ảnh ${index + 1} thất bại: ${e.message}"
                return@withContext false
            }
        }
        true
    }

    private fun modeLabel(mode: String) = if (mode == MODE_INTERACTION) "Tương tác" else "Đăng bài"

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("executor", "FreeHand Executor", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, "executor")
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle("FreeHand Executor")
        .setContentText(text)
        .setOngoing(true)
        .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(2206, notification(text))
    }

    override fun onDestroy() {
        try { unregisterReceiver(resultReceiver) } catch (_: Exception) {}
        FbAutoService.onIrreversibleAction = null
        FbAutoService.onActionProgress = null
        scope.cancel()
        super.onDestroy()
    }
}
