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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ExecutorForegroundService : Service() {
    companion object {
        const val ACTION_START = "com.example.commenthelper.executor.START"
        const val ACTION_STOP = "com.example.commenthelper.executor.STOP"
        const val EXTRA_MODE = "mode"
        const val EXTRA_TYPES = "types"
        const val MODE_INTERACTION = "interaction"
        const val MODE_PUBLISHING = "publishing"
        const val TYPE_INTERACTION = "interaction"
        const val TYPE_PUBLISHING = "publishing"
        const val TYPE_JOIN = "join"
        const val SESSION_MODE = "session"
        val CLAIM_PRIORITY = listOf(TYPE_JOIN, TYPE_INTERACTION, TYPE_PUBLISHING)
        private const val OUTBOX_PREFS_KEY = "executor_lifecycle_outbox"
        private const val OUTBOX_MAX_ATTEMPTS = 8
        private val ALL_TYPES = setOf(TYPE_JOIN, TYPE_INTERACTION, TYPE_PUBLISHING)

        val activeTypes = MutableStateFlow<Set<String>>(emptySet())
        /** Non-null while a multi-type session is running (value = [SESSION_MODE]). */
        val activeMode = MutableStateFlow<String?>(null)
        val isConnected = MutableStateFlow(false)
        /** join, interaction, publishing queued counts */
        val queueCounts = MutableStateFlow(Triple(0, 0, 0))
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

    private data class JobResultExtras(
        val groupUrl: String? = null,
        val groupName: String? = null,
        val alreadyJoined: Boolean? = null,
        val pendingRequest: Boolean? = null,
        val membershipStatus: String? = null
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
            val extras = JobResultExtras(
                groupUrl = intent.getStringExtra("groupUrl")?.takeIf { it.isNotBlank() },
                groupName = intent.getStringExtra("groupName")?.takeIf { it.isNotBlank() },
                alreadyJoined = if (intent.hasExtra("alreadyJoined")) intent.getBooleanExtra("alreadyJoined", false) else null,
                pendingRequest = if (intent.hasExtra("pendingRequest")) intent.getBooleanExtra("pendingRequest", false) else null,
                membershipStatus = intent.getStringExtra("membershipStatus")?.takeIf { it.isNotBlank() }
            )
            scope.launch { finishClaimedJob(success, reasonCode, error, step, retryable, extras) }
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
            ACTION_STOP -> stopExecutor()
            ACTION_START -> {
                val types = intent.getStringArrayListExtra(EXTRA_TYPES)?.toSet()
                    ?: intent.getStringExtra(EXTRA_MODE)?.let { setOf(it) }
                    ?: emptySet()
                startExecutor(types)
            }
            else -> {
                val saved = prefs.getStringSet("executor_running_types", null)?.toSet()
                val types = if (!saved.isNullOrEmpty()) {
                    saved
                } else {
                    prefs.getString("executor_running_mode", null)?.let { legacy ->
                        when (legacy) {
                            MODE_INTERACTION, MODE_PUBLISHING, TYPE_JOIN -> setOf(legacy)
                            SESSION_MODE -> emptySet()
                            else -> emptySet()
                        }
                    } ?: emptySet()
                }
                if (types.isNotEmpty()) startExecutor(types)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startExecutor(types: Set<String>) {
        val cleaned = types.filter { it in ALL_TYPES }.toSet()
        if (cleaned.isEmpty()) return
        if (workerJob?.isActive == true) {
            activeTypes.value = cleaned
            prefs.edit().putStringSet("executor_running_types", cleaned).apply()
            updateNotification(sessionLabel())
            return
        }
        activeTypes.value = cleaned
        activeMode.value = SESSION_MODE
        executorStatus.value = "Đang kết nối server..."
        lastError.value = ""
        prefs.edit()
            .putStringSet("executor_running_types", cleaned)
            .putString("executor_running_mode", SESSION_MODE)
            .apply()
        startForeground(2206, notification("Đang khởi động ${sessionLabel()}"))
        workerJob = scope.launch { workerLoop() }
    }

    private fun stopExecutor() {
        if (activeMode.value == null && workerJob == null) return
        prefs.edit()
            .remove("executor_running_mode")
            .remove("executor_running_types")
            .apply()
        activeMode.value = null
        activeTypes.value = emptySet()
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
                if (!deliverLifecycleWithRetry(active, "interrupted", body, maxAttempts = 3)) {
                    enqueueLifecycleOutbox(active.id, "interrupted", body, active.leaseToken)
                }
            }
            clearActiveJob()
            executorStatus.value = "Đang dừng"
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun workerLoop() {
        var lastHeartbeat = 0L
        var lastSummary = 0L
        try {
            flushLifecycleOutbox()
            while (activeMode.value == SESSION_MODE) {
                val now = System.currentTimeMillis()
                if (now - lastSummary > 10_000) {
                    refreshQueueSummary()
                    flushLifecycleOutbox()
                    lastSummary = now
                }

                val active = claimedJob
                if (active == null) {
                    val cool = cooldownRemainingMs()
                    if (cool > 0L) {
                        val until = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(System.currentTimeMillis() + cool))
                        executorStatus.value = "Tạm nghỉ chống chặn đến $until"
                        updateNotification("${sessionLabel()} · Tạm nghỉ đến $until")
                        delay(minOf(cool, 60_000L))
                    } else {
                        executorStatus.value = "Đang chờ yêu cầu"
                        updateNotification("${sessionLabel()} · Đang chờ yêu cầu")
                        claimNext()?.let { claimed ->
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
            if (activeMode.value == SESSION_MODE) {
                delay(5_000)
                workerJob = scope.launch { workerLoop() }
            }
        }
    }

    private suspend fun claimNext(): ClaimedJob? {
        val types = activeTypes.value
        for (type in CLAIM_PRIORITY) {
            if (type !in types) continue
            val job = claimType(type) ?: continue
            return job
        }
        return null
    }

    private suspend fun claimType(type: String): ClaimedJob? {
        val token = prefs.getString("auth_token", "") ?: ""
        if (token.isBlank()) {
            lastError.value = "Phiên đăng nhập không hợp lệ."
            return null
        }
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val response = request("/api/executor/$type/claim", "POST", JSONObject().put("deviceId", deviceId), token)
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

        executorStatus.value = when (job.type) {
            TYPE_JOIN -> "Đang tham gia nhóm"
            TYPE_INTERACTION -> "Đang tương tác"
            TYPE_PUBLISHING -> "Đang chuẩn bị đăng bài"
            else -> "Đang xử lý"
        }
        updateNotification("${typeLabel(job.type)} · ${job.id}")
        withContext(Dispatchers.IO) {
            when (job.type) {
                TYPE_PUBLISHING -> {
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
                }
                TYPE_JOIN -> {
                    val kind = job.payload.optString("kind")
                    val url = if (kind == "link") job.payload.getString("groupUrl")
                    else "fb_join_keyword:${job.payload.getString("query")}"
                    withContext(Dispatchers.Main) {
                        accessibility.startProcessing(listOf(
                            FbAutoService.TaskItem(
                                postId = job.id,
                                url = url,
                                comment = "",
                                isJoinGroup = true,
                                executorJobId = job.id,
                                reportLegacyCompletion = false
                            )
                        ), appendNotificationScan = false)
                    }
                }
                else -> {
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
    }

    private suspend fun finishClaimedJob(
        success: Boolean,
        reasonCode: String,
        error: String,
        step: String,
        retryable: Boolean,
        extras: JobResultExtras = JobResultExtras()
    ) {
        val active = claimedJob ?: return
        val endpoint = if (success) "complete" else "fail"
        val body = if (success) {
            val result = JSONObject().put("completedAt", System.currentTimeMillis())
            extras.groupUrl?.let { result.put("groupUrl", it) }
            extras.groupName?.let { result.put("groupName", it) }
            extras.alreadyJoined?.let { result.put("alreadyJoined", it) }
            extras.pendingRequest?.let { result.put("pendingRequest", it) }
            extras.membershipStatus?.let { result.put("membershipStatus", it) }
            JSONObject()
                .put("result", result)
                .put("deviceId", Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown")
        } else JSONObject()
            .put("reasonCode", reasonCode)
            .put("error", error)
            .put("step", step)
            .put("retryable", retryable && !irreversibleReached)
        val delivered = deliverLifecycleWithRetry(active, endpoint, body)
        if (!delivered) {
            enqueueLifecycleOutbox(active.id, endpoint, body, active.leaseToken)
            lastError.value = "Không báo được kết quả job ${active.id}; đã xếp hàng gửi lại."
        } else if (!success) {
            lastError.value = "Job ${active.id} thất bại."
        }
        sessionProgress.value = (sessionProgress.value.first + 1) to sessionProgress.value.second
        clearActiveJob()
    }

    private suspend fun deliverLifecycleWithRetry(job: ClaimedJob, action: String, body: JSONObject, maxAttempts: Int = 4): Boolean {
        var delayMs = 2_000L
        repeat(maxAttempts) { attempt ->
            val code = postLifecycle(job, action, JSONObject(body.toString()))
            if (code in 200..299) return true
            // 409 on complete may still mean already succeeded (idempotent path) — handled server-side when lease matches.
            if (code == 409 && action == "complete") {
                // Lease lost; keep outbox only if not already acknowledged as succeeded via idempotent token path later.
            }
            if (attempt < maxAttempts - 1) delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(15_000L)
        }
        return false
    }

    private fun enqueueLifecycleOutbox(jobId: String, action: String, body: JSONObject, leaseToken: String) {
        val arr = loadOutbox()
        // Replace existing entry for same job+action
        val next = JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optString("jobId") == jobId && item.optString("action") == action) continue
            next.put(item)
        }
        next.put(JSONObject()
            .put("jobId", jobId)
            .put("action", action)
            .put("body", body)
            .put("leaseToken", leaseToken)
            .put("attempts", 0)
            .put("nextAt", System.currentTimeMillis()))
        saveOutbox(next)
    }

    private fun loadOutbox(): JSONArray {
        val raw = prefs.getString(OUTBOX_PREFS_KEY, "[]") ?: "[]"
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    private fun saveOutbox(arr: JSONArray) {
        prefs.edit().putString(OUTBOX_PREFS_KEY, arr.toString()).apply()
    }

    private suspend fun flushLifecycleOutbox() {
        val arr = loadOutbox()
        if (arr.length() == 0) return
        val token = prefs.getString("auth_token", "") ?: ""
        if (token.isBlank()) return
        val kept = JSONArray()
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optLong("nextAt", 0L) > now) {
                kept.put(item)
                continue
            }
            val jobId = item.optString("jobId")
            val action = item.optString("action")
            val leaseToken = item.optString("leaseToken")
            val body = item.optJSONObject("body") ?: JSONObject()
            body.put("leaseToken", leaseToken)
            val response = request("/api/executor/jobs/$jobId/$action", "POST", body, token)
            val code = response.first
            if (code in 200..299) continue
            val attempts = item.optInt("attempts", 0) + 1
            if (attempts >= OUTBOX_MAX_ATTEMPTS) {
                lastError.value = "Outbox bỏ job $jobId/$action sau $attempts lần (HTTP $code)."
                continue
            }
            val backoff = (2_000L * (1L shl (attempts - 1).coerceAtMost(4))).coerceAtMost(60_000L)
            kept.put(item
                .put("attempts", attempts)
                .put("nextAt", now + backoff))
        }
        saveOutbox(kept)
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
            val join = json.optJSONObject("join")?.getJSONObject("counts")?.optInt("QUEUED", 0) ?: 0
            queueCounts.value = Triple(join, interaction, publishing)
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

    private fun cooldownRemainingMs(): Long {
        val unlockAt = prefs.getLong("block_timeout_epoch", 0L)
        val now = System.currentTimeMillis()
        return if (unlockAt > now) unlockAt - now else 0L
    }

    private fun typeLabel(type: String) = when (type) {
        TYPE_JOIN -> "Join nhóm"
        TYPE_INTERACTION -> "Tương tác"
        TYPE_PUBLISHING -> "Đăng bài"
        else -> type
    }

    private fun sessionLabel(): String {
        val types = activeTypes.value
        if (types.isEmpty()) return "Executor"
        return types.map { typeLabel(it) }.joinToString(" · ")
    }

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
