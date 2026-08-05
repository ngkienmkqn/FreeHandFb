package com.example.commenthelper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.Function
import java.text.Normalizer
import kotlin.math.abs

/**
 * Accessibility Service that automates Like + Comment on native Facebook app.
 *
 * Flow per post:
 *   1. MainActivity sends task → Service opens FB link
 *   2. Wait for FB to load (detect known UI elements)
 *   3. Auto-Like (if not already liked)
 *   4. Auto-Comment (find input → set text → tap send)
 *   5. Mark done → open next post
 */
class FbAutoService : AccessibilityService() {

    companion object {
        private const val TAG = "FbAutoService"
        private val UI_DIACRITICS = Regex("\\p{Mn}+")
        private val UI_NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")

        /** Task queue sent from MainActivity */
        val taskQueue = MutableStateFlow<List<TaskItem>>(emptyList())

        /** Currently processing post ID */
        val currentPostId = MutableStateFlow<String?>(null)

        /** Progress: (completed, total) */
        val progress = MutableStateFlow(0 to 0)

        /** Whether the service is currently running tasks */
        val isRunning = MutableStateFlow(false)

        /** Signal to stop processing */
        val stopRequested = MutableStateFlow(false)

        /** Detailed status text for UI */
        val currentStatusText = MutableStateFlow("Đang chờ...")

        /** Reference to the running service instance */
        var instance: FbAutoService? = null
            private set
            
        /** Callback invoked when the queue naturally finishes (not manually stopped) */
        var onQueueFinished: (() -> Unit)? = null
        /** Callback invoked when a post is marked as DEAD */
        var onPostDead: ((String) -> Unit)? = null
        /** Executor callback immediately before the irreversible Send/Post click. */
        var onIrreversibleAction: ((String) -> Boolean)? = null
        var onActionProgress: ((String, String, String) -> Boolean)? = null

        fun isServiceEnabled(context: Context): Boolean {
            val enabledServices = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(context.packageName)
        }
    }

    object Engine {
        var wrongScreen = listOf("gửi bằng messenger", "gửi trong messenger", "chia sẻ lên tin", "share to story", "gửi cho", "tìm kiếm người", "search people")
        var blockDialog = listOf("bạn đang tạm thời bị chặn", "tài khoản của bạn bị hạn chế", "you can't post right now", "temporarily blocked", "restricted")
        var groupJoin = listOf("tham gia nhóm", "join group")
        var questionnaireSubmit = listOf("gửi", "đồng ý", "submit", "i agree")
        var deadLink = listOf("không khả dụng", "không tồn tại", "đã bị gỡ", "content isn't available", "content not found")
        var composeButton = listOf("bài viết mới...", "viết gì đó...", "bạn viết gì đi", "bạn đang nghĩ gì", "tạo bài viết", "thảo luận", "write something", "write a public", "what's on your mind", "create post", "share something")
        var postButton = listOf("đăng", "post")
        var commentButton = listOf("bình luận", "comment", "viết bình luận", "write a comment")
        var sendComment = listOf("gửi", "send", "đăng", "post", "tiếp", "next")
        var photoButton = listOf("ảnh/video", "photo/video", "thêm vào bài viết", "add to your post", "ảnh", "photo")
        var galleryExclude = listOf("take", "chụp", "camera", "thu gọn", "chọn nhiều", "thêm vào", "collapse", "select multiple", "thư viện", "library", "pictures", "album", "video", "quay lại", "back", "navigate", "bài viết mới", "new post")
        var multiSelectButton = listOf("chọn nhiều file", "chọn nhiều", "select multiple", "select multiple files")
        var galleryClickDelay = 800L
        var galleryNextButton = listOf("next", "tiếp", "done", "xong", "tiếp tục", "hoàn tất")
        var notificationIgnore = listOf("đăng nhập", "thiết bị", "yêu cầu tham gia", "tham gia nhóm")
        var notificationApprove = listOf("phê duyệt ảnh", "phê duyệt bài", "approved your photo", "approved your post")

        var rhinoScope: Scriptable? = null
        var lastVersion: String = ""

        fun load(context: Context) {
            try {
                val prefs = context.getSharedPreferences("comment_helper_prefs", Context.MODE_PRIVATE)
                val script = prefs.getString("engine_script", "{}") ?: "{}"
                
                val scriptObj = org.json.JSONObject(script)
                val version = scriptObj.optString("version", "?")
                val jsCode = scriptObj.optString("jsCode", "")
                
                // Hot reload check
                if (version == lastVersion && rhinoScope != null) return
                lastVersion = version

                val j = scriptObj.optJSONObject("anchors")
                if (j != null) {
                    fun getList(key: String, default: List<String>): List<String> {
                        val a = j.optJSONArray(key) ?: return default
                        return (0 until a.length()).map { a.getString(it).lowercase() }
                    }

                    wrongScreen = getList("wrong_screen", wrongScreen)
                    blockDialog = getList("block_dialog", blockDialog)
                    groupJoin = getList("group_join", groupJoin)
                    questionnaireSubmit = getList("questionnaire_submit", questionnaireSubmit)
                    deadLink = getList("dead_link", deadLink)
                    composeButton = getList("compose_button", composeButton)
                    postButton = getList("post_button", postButton)
                    commentButton = getList("comment_button", commentButton)
                    sendComment = getList("send_comment", sendComment)
                    photoButton = getList("photo_button", photoButton)
                    galleryExclude = getList("gallery_exclude", galleryExclude)
                    multiSelectButton = getList("multi_select_button", multiSelectButton)
                    galleryNextButton = getList("gallery_next_button", galleryNextButton)
                    notificationIgnore = getList("notification_ignore", notificationIgnore)
                    notificationApprove = getList("notification_approve", notificationApprove)
                    galleryClickDelay = j.optLong("gallery_click_delay", galleryClickDelay)
                    // Allow local override from Settings UI
                    val localDelay = prefs.getLong("local_gallery_delay", 0L)
                    if (localDelay > 0) galleryClickDelay = localDelay
                }
                
                // Init Rhino JS Engine
                if (jsCode.isNotBlank()) {
                    val rhino = RhinoContext.enter()
                    rhino.optimizationLevel = -1 // Required for Android
                    try {
                        val scope = rhino.initSafeStandardObjects()
                        rhinoScope = scope
                        // Pass engine object to JS
                        org.mozilla.javascript.ScriptableObject.putProperty(scope, "engine", RhinoContext.javaToJS(this, scope))
                        rhino.evaluateString(scope, jsCode, "OTAScript", 1, null)
                        Log.d(TAG, "Rhino JS Engine Hot-Reloaded.")
                    } finally {
                        RhinoContext.exit()
                    }
                }
                
                Log.d(TAG, "OTA Engine loaded. Version: $version. GalleryDelay: ${galleryClickDelay}ms")
            } catch(e: Throwable) { Log.e(TAG, "Failed loading OTA script/JS", e) }
        }
        
        fun callJsFunction(funcName: String, vararg args: Any): Any? {
            val scope = rhinoScope ?: return null
            val rhino = RhinoContext.enter()
            rhino.optimizationLevel = -1
            try {
                val obj = scope.get(funcName, scope)
                if (obj is Function) {
                    val jsArgs = args.map { RhinoContext.javaToJS(it, scope) }.toTypedArray()
                    return obj.call(rhino, scope, scope, jsArgs)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing JS function: $funcName", e)
            } finally {
                RhinoContext.exit()
            }
            return null
        }
    }

    data class TaskItem(
        val postId: String,
        val url: String,
        val comment: String,
        val isPublishingGroup: Boolean = false,
        val imageCount: Int = 0,
        val isScrapingGroup: Boolean = false,
        val postIndex: Int = 0,
        val executorJobId: String? = null,
        val reportLegacyCompletion: Boolean = true,
        val targetPostAuthor: String = "",
        val targetPostText: String = "",
        val targetPostAnchors: List<String> = emptyList(),
        val actionLike: Boolean = true,
        val actionComment: Boolean = true
    )

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    
    private val processedNotifications = mutableSetOf<String>()

    private enum class Step {
        IDLE,
        WAITING_FOR_FB_LOAD,
        SEEKING_TARGET_POST,
        LOOKING_FOR_LIKE,
        LOOKING_FOR_COMMENT_FIELD,
        WAITING_FOR_COMMENT_SENT,
        LOOKING_FOR_COMPOSER, 
        WAITING_FOR_COMPOSER_INPUT, 
        LOOKING_FOR_COMPOSER_DONE,
        LOOKING_FOR_PHOTO_BUTTON, 
        SELECTING_PHOTOS, 
        WAITING_FOR_POST_TO_UPLOAD,
        CLICKING_YOU_TAB,
        LOOKING_FOR_MY_POST,
        CLICKING_SHARE_AND_COPY,
        WAITING_FOR_CLIPBOARD,
        SCRAPING_GROUP_INFO,
        WAITING_FOR_OPENED_POST,
        CLICKING_NOTIFICATION_TAB,
        SCANNING_NOTIFICATIONS,
        DONE
    }

    private var currentStep = Step.IDLE
        set(value) {
            field = value
            updateStatusText()
        }
    private var currentTask: TaskItem? = null
    private var currentIndex = 0
    private var retryCount = 0
    private var nextStepTime = 0L
    private var commentEntryOpened = false
    private var targetSearchScrollCount = 0
    private var targetSearchStartedAt = 0L
    private var lastTargetSearchSignature = 0
    private var unchangedTargetSearchCount = 0
    private var postUploadStableChecks = 0
    
    // Auto-learned Facebook display name of the device owner
    private var fbProfileName: String? = null
    
    /**
     * Attempt to learn the FB profile name from the "..." menu button's contentDescription.
     * Facebook uses patterns like "Lựa chọn khác cho bài viết của [NAME]" or "More options for [NAME]'s post".
     */
    private fun learnProfileName(allNodes: List<AccessibilityNodeInfo>) {
        if (fbProfileName != null) return // Already learned
        for (node in allNodes) {
            val desc = node.contentDescription?.toString() ?: ""
            // Vietnamese: "Lựa chọn khác cho bài viết của Nguyễn Văn A"
            val viMatch = Regex("lựa chọn khác cho bài viết của (.+)", RegexOption.IGNORE_CASE).find(desc)
            if (viMatch != null) {
                fbProfileName = viMatch.groupValues[1].trim()
                Log.d(TAG, "✅ Learned FB profile name (VI): '$fbProfileName'")
                return
            }
            // English: "More options for NAME's post"
            val enMatch = Regex("more options for (.+?)['']s post", RegexOption.IGNORE_CASE).find(desc)
            if (enMatch != null) {
                fbProfileName = enMatch.groupValues[1].trim()
                Log.d(TAG, "✅ Learned FB profile name (EN): '$fbProfileName'")
                return
            }
        }
    }
    
    /**
     * Check if a "..." menu node near the given post belongs to the device owner.
     * Returns true if we can't determine ownership (safe fallback) or if the name matches.
     */
    private fun isMyPost(allNodes: List<AccessibilityNodeInfo>, postNodeIndex: Int): Boolean {
        val name = fbProfileName ?: return true // If we don't know our name yet, allow it
        // Search nearby for "..." menu button
        for (i in maxOf(0, postNodeIndex - 30) until minOf(allNodes.size, postNodeIndex + 30)) {
            val desc = allNodes[i].contentDescription?.toString() ?: ""
            if (desc.contains("lựa chọn khác cho bài viết của", ignoreCase = true) || 
                desc.contains("more options for", ignoreCase = true)) {
                val isOwner = desc.contains(name, ignoreCase = true)
                if (!isOwner) {
                    Log.d(TAG, "❌ Post belongs to someone else: '$desc' (expected: '$name')")
                }
                return isOwner
            }
        }
        return true // No "..." found, allow it (safe fallback)
    }
    private fun setNextStepDelay(delay: Long) {
        nextStepTime = System.currentTimeMillis() + delay
    }

    private fun updateStatusText() {
        val baseMsg = when(currentStep) {
            Step.IDLE -> "Đang rảnh"
            Step.WAITING_FOR_FB_LOAD -> "Đang mở bài viết trên ứng dụng FB..."
            Step.SEEKING_TARGET_POST -> "Đang tự cuộn tìm bài viết mục tiêu..."
            Step.LOOKING_FOR_LIKE -> "Đang tìm kiếm nút Thích..."
            Step.LOOKING_FOR_COMMENT_FIELD -> "Đang tìm kiếm ô nhập Bình luận..."
            Step.WAITING_FOR_COMMENT_SENT -> "Đang gửi bình luận..."
            Step.LOOKING_FOR_COMPOSER -> "Đang chuẩn bị viết bài mới..."
            Step.WAITING_FOR_COMPOSER_INPUT -> "Đang nhập nội dung bài viết..."
            Step.LOOKING_FOR_COMPOSER_DONE -> "Đang xác nhận nội dung bài viết..."
            Step.LOOKING_FOR_PHOTO_BUTTON -> "Đang tìm nút tải ảnh lên..."
            Step.SELECTING_PHOTOS -> "Đang chọn ảnh từ thư viện..."
            Step.WAITING_FOR_POST_TO_UPLOAD -> "Đang chờ Facebook tải bài lên..."
            Step.CLICKING_YOU_TAB -> "Đang tìm tab 'Bạn' trong nhóm..."
            Step.LOOKING_FOR_MY_POST -> "Đang tìm bài viết cá nhân..."
            Step.CLICKING_SHARE_AND_COPY -> "Đang lấy link bài viết vừa đăng..."
            Step.WAITING_FOR_CLIPBOARD -> "Đang xử lý link vừa sao chép..."
            Step.SCRAPING_GROUP_INFO -> "Đang quét thông tin thành viên nhóm..."
            Step.WAITING_FOR_OPENED_POST -> "Đang chờ tải nội dung bài viết..."
            Step.CLICKING_NOTIFICATION_TAB -> "Đang chuyển sang tab thông báo..."
            Step.SCANNING_NOTIFICATIONS -> "Đang quét thông báo bài viết được phê duyệt..."
            Step.DONE -> "Hoàn thành nhiệm vụ"
        }
        val q = taskQueue.value
        val nextMsg = if (q.isNotEmpty() && currentIndex + 1 < q.size) {
            val nextTask = q[currentIndex + 1]
            if (nextTask.isPublishingGroup) " (Tiếp theo: Đăng bài nhóm)"
            else if (nextTask.isScrapingGroup) " (Tiếp theo: Quét nhóm)"
            else " (Tiếp theo: Tương tác bài)"
        } else " (Sắp xong chuỗi tác vụ)"
        currentStatusText.value = baseMsg + if (currentStep != Step.IDLE && currentStep != Step.DONE) nextMsg else ""
    }
    private val MAX_RETRIES: Int
        get() = when (currentStep) {
            Step.WAITING_FOR_POST_TO_UPLOAD, Step.WAITING_FOR_COMMENT_SENT -> 80
            Step.SEEKING_TARGET_POST -> 70
            else -> 40
        }
    private val STEP_DELAY: Long
        get() = if (getSharedPreferences("comment_helper_prefs", Context.MODE_PRIVATE).getBoolean("global_debug_mode", false)) 2500L else 800L
    
    private val isDebugMode: Boolean
        get() = getSharedPreferences("comment_helper_prefs", Context.MODE_PRIVATE).getBoolean("global_debug_mode", false)
    enum class ScreenType { UNKNOWN, FEED, COMPOSER, GALLERY, POST_SHEET }

    private fun evaluateCurrentScreen(nodes: List<AccessibilityNodeInfo>): ScreenType {
        var result = ScreenType.UNKNOWN
        
        // 1. Gallery check (Top right "Chọn nhiều file" or "Tiếp")
        val hasGalleryUI = nodes.any { 
            val txt = it.text?.toString()?.lowercase() ?: ""
            val cd = it.contentDescription?.toString()?.lowercase() ?: ""
            (txt == "tiếp" || cd == "tiếp" || txt == "tiếp tục" || cd == "tiếp tục" || txt.contains("chọn nhiều") || cd.contains("chọn nhiều")) &&
            !txt.contains("chỉnh sửa") && !cd.contains("chỉnh sửa")
        }
        if (hasGalleryUI) result = ScreenType.GALLERY

        // 2. Composer check (Tạo bài viết, Bạn đang nghĩ gì, or Thêm ảnh with an active EditText)
        if (result == ScreenType.UNKNOWN) {
            val hasEditText = nodes.any { 
                it.isEditable || 
                it.className?.toString() == "android.widget.EditText" || 
                it.className?.toString() == "android.widget.MultiAutoCompleteTextView"
            }
            val hasComposerUI = nodes.any {
                val txt = it.text?.toString()?.lowercase() ?: ""
                val cd = it.contentDescription?.toString()?.lowercase() ?: ""
                Engine.composeButton.any { cb -> txt.contains(cb) || cd.contains(cb) } ||
                Engine.photoButton.any { pb -> txt.contains(pb) || cd.contains(pb) }
            }
            if (hasComposerUI && hasEditText) result = ScreenType.COMPOSER
        }

        // 3. Post options sheet (Share/Copy link)
        if (result == ScreenType.UNKNOWN) {
            val hasPostSheet = nodes.any {
                val txt = it.text?.toString()?.lowercase() ?: ""
                val cd = it.contentDescription?.toString()?.lowercase() ?: ""
                txt.contains("sao chép liên kết") || cd.contains("sao chép liên kết") || 
                txt.contains("copy link") || cd.contains("copy link")
            }
            if (hasPostSheet) result = ScreenType.POST_SHEET
        }

        // 4. Feed check (Bảng tin, Like, Comment buttons)
        if (result == ScreenType.UNKNOWN) {
            val hasFeedUI = nodes.any {
                val txt = it.text?.toString()?.lowercase() ?: ""
                val cd = it.contentDescription?.toString()?.lowercase() ?: ""
                txt == "thích" || txt == "bình luận" || cd == "like" || cd == "comment" || txt.contains("bảng tin")
            }
            if (hasFeedUI) result = ScreenType.FEED
        }

        return result
    }


    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Engine.load(this)
        Log.d(TAG, "Service connected")
        
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            @Suppress("DEPRECATION")
            wakeLock = powerManager.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "FbAutoService::AutomationWakeLock"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init wakeLock", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        resetState()
        Log.d(TAG, "Service destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isRunning.value) return
        if (currentStep == Step.IDLE || currentStep == Step.DONE) return

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.facebook.katana" && pkg != "com.facebook.lite") return

        // Respect the synchronization delay across all events
        if (System.currentTimeMillis() < nextStepTime) return

        // Process based on current step
        when (currentStep) {
            Step.WAITING_FOR_FB_LOAD -> handleWaitingForLoad()
            Step.SEEKING_TARGET_POST -> handleSeekingTargetPost()
            Step.LOOKING_FOR_LIKE -> handleLookingForLike()
            Step.LOOKING_FOR_COMMENT_FIELD -> handleLookingForCommentField()
            Step.WAITING_FOR_COMMENT_SENT -> findAndClickSend()
            Step.LOOKING_FOR_COMPOSER -> handleLookingForComposer()
            Step.WAITING_FOR_COMPOSER_INPUT -> handleWaitingForComposerInput()
            Step.LOOKING_FOR_COMPOSER_DONE -> handleLookingForComposerDone()
            Step.LOOKING_FOR_PHOTO_BUTTON -> { handleLookingForPhotoButton() }
            Step.SELECTING_PHOTOS -> { handleSelectingPhotos() }
            Step.WAITING_FOR_POST_TO_UPLOAD -> { handleWaitingForPostToUpload() }
            Step.CLICKING_SHARE_AND_COPY -> { handleClickingShareAndCopy() }
            Step.SCRAPING_GROUP_INFO -> { handleScrapingGroupInfo() }
            else -> {}
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
        resetState()
    }

    /* ================== PUBLIC API ================== */

    fun startProcessing(tasks: List<TaskItem>, appendNotificationScan: Boolean = true) {
        if (tasks.isEmpty()) return
        if (isRunning.value) {
            Log.w(TAG, "⚠️ LOCK: Đang chạy task khác, từ chối startProcessing mới. Hãy đợi task cũ hoàn thành.")
            return
        }
        val finalTasks = tasks.toMutableList()
        if (appendNotificationScan) finalTasks.add(TaskItem("NOTIF_SCAN", "ACTION_SCAN_NOTIFICATIONS", ""))
        
        taskQueue.value = finalTasks
        progress.value = 0 to finalTasks.size
        currentIndex = 0
        isMarkingDone = false
        stopRequested.value = false
        isRunning.value = true

        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(2 * 60 * 60 * 1000L) // 2 hours max
                Log.d(TAG, "WakeLock acquired for processing")
            }
        } catch (_: Exception) {}

        processNextPost()
    }

    fun startPublishing(tasks: List<TaskItem>, appendNotificationScan: Boolean = true) {
        if (tasks.isEmpty()) return
        if (isRunning.value) {
            Log.w(TAG, "⚠️ LOCK: Đang chạy task khác, từ chối startPublishing mới. Hãy đợi task cũ hoàn thành.")
            return
        }
        val finalTasks = tasks.toMutableList()
        if (appendNotificationScan) finalTasks.add(TaskItem("NOTIF_SCAN", "ACTION_SCAN_NOTIFICATIONS", ""))

        taskQueue.value = finalTasks
        progress.value = 0 to finalTasks.size
        currentIndex = 0
        stopRequested.value = false
        isRunning.value = true

        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(2 * 60 * 60 * 1000L) // 2 hours max
                Log.d(TAG, "WakeLock acquired for publishing")
            }
        } catch (_: Exception) {}

        processNextPost()
    }

    fun startScrapingGroup(url: String) {
        val task = TaskItem(
            postId = "SCRAPE_" + System.currentTimeMillis(),
            url = url,
            comment = "",
            isScrapingGroup = true
        )
        taskQueue.value = listOf(task)
        progress.value = 0 to 1
        currentIndex = 0
        stopRequested.value = false
        isRunning.value = true

        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(2 * 60 * 60 * 1000L) // 2 hours max
                Log.d(TAG, "WakeLock acquired for scraping")
            }
        } catch (_: Exception) {}

        processNextPost()
    }

    fun stopProcessing() {
        stopRequested.value = true
        isRunning.value = false
        currentStep = Step.IDLE
        currentTask = null
        currentPostId.value = null
    }

    /* ================== PROCESSING LOGIC ================== */

    private fun processNextPost() {
        if (stopRequested.value) {
            resetState()
            return
        }

        val tasks = taskQueue.value
        if (currentIndex >= tasks.size) {
            // All done
            resetState()
            return
        }

        val task = tasks[currentIndex]
        currentTask = task
        currentPostId.value = task.postId
        
        // HOT-RELOAD CHECK (Before starting the task)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val prefs = getSharedPreferences("comment_helper_prefs", Context.MODE_PRIVATE)
                val token = prefs.getString("auth_token", "") ?: ""
                
                val urlScripts = "$SERVER_URL/api/engine/scripts"
                val conn = java.net.URL(urlScripts).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "Bearer $token")
                val rc = conn.responseCode
                if (rc in 200..299) {
                    val resp = conn.inputStream.bufferedReader().use { it.readText() }
                    val latestVer = org.json.JSONObject(resp).optString("latest", "")
                    if (latestVer.isNotBlank() && latestVer != Engine.lastVersion) {
                        Log.d(TAG, "OTA: New version detected ($latestVer). Downloading...")
                        val urlScript = "$SERVER_URL/api/engine/script?version=$latestVer"
                        val conn2 = java.net.URL(urlScript).openConnection() as java.net.HttpURLConnection
                        conn2.requestMethod = "GET"
                        conn2.setRequestProperty("Authorization", "Bearer $token")
                        if (conn2.responseCode in 200..299) {
                            val sb = conn2.inputStream.bufferedReader().use { it.readText() }
                            prefs.edit().putString("engine_script", sb).apply()
                            Engine.load(this@FbAutoService)
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Hot-Reload check failed", e)
            }
            
            // Continue on main thread
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                executePostTask(task)
            }
        }
    }
    
    private fun executePostTask(task: TaskItem) {
        commentEntryOpened = false
        postUploadStableChecks = 0
        if (task.url == "ACTION_SCAN_NOTIFICATIONS") {
            currentStep = Step.CLICKING_NOTIFICATION_TAB
            retryCount = 0
            healingCount = 0
            multiSelectClicked = false
            Log.d(TAG, "Processing NOTIF_SCAN task...")
            
            handler.postDelayed({
                openFacebookLink("fb://notifications")
                startRetryChecker()
            }, 2000)
            return
        }

        if (task.url.startsWith("fb_join_keyword:")) {
            currentStep = Step.WAITING_FOR_FB_LOAD
            retryCount = 0
            healingCount = 0
            multiSelectClicked = false
            Log.d(TAG, "Processing keyword group-joining task: ${task.url}")
            
            forceStopFacebook()
            handler.postDelayed({
                val kw = task.url.substringAfter("fb_join_keyword:")
                openFacebookLink("fb://search/groups?query=$kw")
                startRetryChecker()
            }, 2000)
            return
        }
        
        resetTargetSearch()
        currentStep = Step.WAITING_FOR_FB_LOAD
        retryCount = 0
        healingCount = 0
        multiSelectClicked = false

        Log.d(TAG, "Processing post ${currentIndex + 1}/${taskQueue.value.size}: ${task.url}")
        if (task.postIndex > 0) {
            debugLog("🚀 Bắt đầu bài thứ ${task.postIndex} trong nhóm này ngày hôm nay.")
        }

        // Clear clipboard first to avoid grabbing old links
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("", ""))

        // FORCE KILL Facebook to ensure clean state before each task
        forceStopFacebook()

        // Wait for FB to fully die, then open the new link
        handler.postDelayed({
            openFacebookLink(task.url)
            // Start a timeout checker
            startRetryChecker()
        }, 2000) // 2s delay after killing FB
    }

    /**
     * Force-stop the Facebook app to ensure a completely clean slate.
     * This prevents stale UI state, memory buildup, and wrong-screen bugs.
     */
    private fun forceStopFacebook() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.killBackgroundProcesses("com.facebook.katana")
            am.killBackgroundProcesses("com.facebook.orca")
            Log.d(TAG, "🔪 Force-killed Facebook background processes")
        } catch (e: Exception) {
            Log.e(TAG, "killBackgroundProcesses failed", e)
        }
        // Also try shell command (works on rooted/ADB-enabled devices)
        try {
            Runtime.getRuntime().exec(arrayOf("am", "force-stop", "com.facebook.katana"))
            Log.d(TAG, "🔪 am force-stop com.facebook.katana executed")
        } catch (e: Exception) {
            Log.w(TAG, "am force-stop failed (not rooted?): ${e.message}")
        }
    }

    private fun openFacebookLink(url: String) {
        val targetUrl = FbUrlHelper.buildFbOpenUrl(url)
        val mobileFallback = FbUrlHelper.normalizeFbUrlForNative(url)

        val intentKatana = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            setPackage("com.facebook.katana")
        }
        try {
            startActivity(intentKatana)
        } catch (e: Exception) {
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(mobileFallback)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(fallback)
            } catch (e3: Exception) {
                Log.e(TAG, "Cannot open URL: $url", e3)
                val currentPostId = currentTask?.postId
                if (!currentPostId.isNullOrEmpty()) {
                    onPostDead?.invoke(currentPostId)
                }
                markCurrentDone(false, "OPEN_FACEBOOK_FAILED", "Không thể mở liên kết bằng Facebook Katana/Lite.")
            }
        }
    }

    private var isRetryCheckerRunning = false

    private fun startRetryChecker() {
        if (isRetryCheckerRunning) return
        isRetryCheckerRunning = true
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isRunning.value || stopRequested.value || currentStep == Step.IDLE || currentStep == Step.DONE) {
                    isRetryCheckerRunning = false
                    return
                }
                if (System.currentTimeMillis() < nextStepTime) {
                    handler.postDelayed(this, 500)
                    return
                }

                val root = rootInActiveWindow
                if (root != null) {
                    val allNodes = findAllNodes(root)
                    if (interceptWrongScreen(allNodes)) {
                        retryCount = 0
                        recycleNodes(allNodes)
                        root.recycle()
                        handler.postDelayed(this, 1500)
                        return
                    }
                    if (interceptBlockDialog(allNodes)) {
                        recycleNodes(allNodes)
                        root.recycle()
                        isRetryCheckerRunning = false
                        return
                    }
                    if (interceptGroupJoin(allNodes)) {
                        retryCount = 0
                        recycleNodes(allNodes)
                        root.recycle()
                        handler.postDelayed(this, 1500)
                        return
                    }
                    recycleNodes(allNodes)
                    root.recycle()
                }
                


                retryCount++
                if (retryCount > MAX_RETRIES) {
                    val root2 = rootInActiveWindow
                    if (root2 != null) {
                        val allNodes2 = findAllNodes(root2)
                        val screen = evaluateCurrentScreen(allNodes2)
                        recycleNodes(allNodes2)
                        
                        debugLog("⚠️ Kẹt ở bước $currentStep. Màn hình hiện tại: $screen")
                        val healed = attemptSelfHealing(screen)
                        if (healed) {
                            retryCount = 0
                            root2.recycle()
                            handler.postDelayed(this, 1000)
                            return
                        }
                        root2.recycle()
                    }
                    
                    Log.w(TAG, "Timeout waiting for step: $currentStep")
                    // If we timeout on upload, try grabbing anyway
                    if (currentStep == Step.WAITING_FOR_POST_TO_UPLOAD) {
                        currentStep = Step.CLICKING_SHARE_AND_COPY
                        retryCount = 0
                        handleClickingShareAndCopy()
                        nextStepTime = System.currentTimeMillis() + 500L
                        handler.postDelayed(this, 500)
                        return
                    }
                    isRetryCheckerRunning = false
                    markCurrentDone(false, "STEP_TIMEOUT", "Quá thời gian xử lý ở bước ${currentStep.name}.")
                    return
                }
                // Actively try to find elements
                when (currentStep) {
                    Step.WAITING_FOR_FB_LOAD -> handleWaitingForLoad()
                    Step.SEEKING_TARGET_POST -> handleSeekingTargetPost()
                    Step.LOOKING_FOR_LIKE -> handleLookingForLike()
                    Step.LOOKING_FOR_COMMENT_FIELD -> handleLookingForCommentField()
                    Step.WAITING_FOR_COMMENT_SENT -> handleWaitingForCommentSent()
                    Step.LOOKING_FOR_COMPOSER -> handleLookingForComposer()
                    Step.WAITING_FOR_COMPOSER_INPUT -> handleWaitingForComposerInput()
                    Step.LOOKING_FOR_COMPOSER_DONE -> handleLookingForComposerDone()
                    Step.LOOKING_FOR_PHOTO_BUTTON -> { handleLookingForPhotoButton() }
                    Step.SELECTING_PHOTOS -> { handleSelectingPhotos() }
                    Step.WAITING_FOR_POST_TO_UPLOAD -> { handleWaitingForPostToUpload() }
                    Step.CLICKING_YOU_TAB -> { handleClickingYouTab() }
                    Step.LOOKING_FOR_MY_POST -> { handleLookingForMyPost() }
                    Step.CLICKING_SHARE_AND_COPY -> { handleClickingShareAndCopy() }
                    Step.WAITING_FOR_CLIPBOARD -> {
                        val clipTask = currentTask
                        if (clipTask == null) {
                            isRetryCheckerRunning = false
                            return
                        }
                        submitCopiedLinkToBackend(clipTask)
                    }
                    Step.WAITING_FOR_OPENED_POST -> { handleWaitingForOpenedPost() }
                    Step.CLICKING_NOTIFICATION_TAB -> { handleClickingNotificationTab() }
                    Step.SCANNING_NOTIFICATIONS -> { handleScanningNotifications() }
                    else -> { isRetryCheckerRunning = false; return }
                }
                handler.postDelayed(this, 500)
            }
        }, 1500) // Initial delay to let FB open
    }

    private var healingCount = 0

    private fun attemptSelfHealing(screen: ScreenType): Boolean {
        healingCount++
        if (healingCount > 3) {
            debugLog("❌ Tự chữa lành thất bại sau 3 lần. Hủy bài viết.")
            healingCount = 0
            return false
        }
        
        debugLog("🛠 Kích hoạt Tự chữa lành (Lần $healingCount)...")
        debugLog("--- 🚨 PHÂN TÍCH LỖI MÀN HÌNH $screen 🚨 ---")
        debugLog("Lý do: Kẹt ở bước '$currentStep', không tìm thấy mục tiêu.")
        debugLog("Danh sách đối tượng (X-RAY):")
        val rootXray = rootInActiveWindow
        if (rootXray != null) {
            val nodes = findAllNodes(rootXray)
            var count = 0
            for (n in nodes) {
                val c = n.className?.toString()?.substringAfterLast('.') ?: ""
                val d = n.contentDescription?.toString() ?: ""
                val t = n.text?.toString() ?: ""
                if (d.isNotBlank() || t.isNotBlank() || n.isClickable) {
                    debugLog("  - [$c] Chữ: '$t' | Mô tả: '$d' | Bấm: ${n.isClickable}")
                    count++
                    if (count >= 40) {
                        debugLog("  ... (Ẩn bớt để tránh trôi log)")
                        break
                    }
                }
            }
            recycleNodes(nodes)
            rootXray.recycle()
        }
        debugLog("-------------------------------------------")

        when (screen) {
            ScreenType.FEED -> {
                if (currentStep == Step.CLICKING_SHARE_AND_COPY || currentStep == Step.WAITING_FOR_POST_TO_UPLOAD) {
                    debugLog("⚠️ Lỗi lấy link (không mở được menu bài viết). Bỏ qua lấy link.")
                    markCurrentDone(success = true)
                    return true
                }
                if (currentStep != Step.WAITING_FOR_FB_LOAD && currentStep != Step.LOOKING_FOR_COMPOSER) {
                    debugLog("⚠️ Bị văng ra Bảng tin. Thử tìm lại ô Soạn bài...")
                    currentStep = Step.LOOKING_FOR_COMPOSER
                    return true
                }
            }
            ScreenType.COMPOSER -> {
                if (currentStep == Step.SELECTING_PHOTOS || currentStep == Step.WAITING_FOR_COMMENT_SENT) {
                    debugLog("⚠️ Kẹt ở Soạn bài. Đang thử đóng bàn phím và sửa tiến trình...")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    if (currentStep == Step.SELECTING_PHOTOS) currentStep = Step.LOOKING_FOR_PHOTO_BUTTON
                    if (currentStep == Step.WAITING_FOR_COMMENT_SENT) currentStep = Step.WAITING_FOR_COMPOSER_INPUT
                    return true
                }
            }
            ScreenType.GALLERY -> {
                if (currentStep != Step.SELECTING_PHOTOS) {
                    debugLog("⚠️ Khay ảnh mở sai thời điểm. Đang sửa lại tiến trình...")
                    currentStep = Step.SELECTING_PHOTOS
                    return true
                }
            }
            ScreenType.POST_SHEET -> {
                if (currentStep != Step.CLICKING_SHARE_AND_COPY) {
                    debugLog("⚠️ Kẹt ở Menu Share. Đang thử đóng...")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    return true
                }
            }
            else -> {}
        }
        
        // Fallback
        performGlobalAction(GLOBAL_ACTION_BACK)
        return true
    }

    /* ================== DOM INTERCEPTOR ================== */

    private fun interceptWrongScreen(nodes: List<AccessibilityNodeInfo>): Boolean {
        // JS Override Check
        val jsResult = Engine.callJsFunction("interceptWrongScreen", nodes, this)
        if (jsResult is Boolean) return jsResult

        // 1. Direct Discard Dialog handler - Try to click 'Bỏ bài viết' (Discard) to exit clean
        val discardNode = nodes.firstOrNull { 
            val txt = it.text?.toString()?.lowercase() ?: ""
            txt.contains("bỏ bài viết") || txt.contains("discard post") || txt.contains("discard")
        }
        if (discardNode != null) {
            Log.w(TAG, "Discard dialog detected! Attempting to click 'Bỏ bài viết' (Discard) to return to main feed.")
            var temp: AccessibilityNodeInfo? = discardNode
            while (temp != null) {
                val clicked = temp.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                if (clicked) {
                    Log.d(TAG, "Successfully clicked discard button ancestor.")
                    return true
                }
                temp = temp.parent
            }
        }

        // 2. Secondary fallback - Try to click 'Tiếp tục chỉnh sửa' (Continue Editing)
        val continueEditNode = nodes.firstOrNull { 
            val txt = it.text?.toString()?.lowercase() ?: ""
            txt.contains("tiếp tục chỉnh sửa") || txt.contains("continue editing")
        }
        if (continueEditNode != null) {
            Log.w(TAG, "Discard dialog detected! Attempting to click 'Tiếp tục chỉnh sửa' (Continue editing) to return to composer.")
            var temp: AccessibilityNodeInfo? = continueEditNode
            while (temp != null) {
                val clicked = temp.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                if (clicked) {
                    Log.d(TAG, "Successfully clicked continue editing ancestor.")
                    return true
                }
                temp = temp.parent
            }
        }

        val isWrongScreen = nodes.any { 
            val txt = it.text?.toString()?.lowercase() ?: ""
            Engine.wrongScreen.any { wrongStr -> txt.contains(wrongStr) }
        }
        if (isWrongScreen) {
            Log.w(TAG, "Intercepted Wrong Screen (Share/Messenger Sheet). Pressing BACK.")
            performGlobalAction(GLOBAL_ACTION_BACK)
            return true
        }
        return false
    }

    private fun interceptBlockDialog(nodes: List<AccessibilityNodeInfo>): Boolean {
        val isBlocked = nodes.any { 
            val text = it.text?.toString()?.lowercase() ?: ""
            Engine.blockDialog.any { blockTxt -> text.contains(blockTxt) }
        }
        if (isBlocked) {
            Log.w(TAG, "Facebook Block Detected! Enforcing Cooldown.")
            val prefs = getSharedPreferences("comment_helper_prefs", Context.MODE_PRIVATE)
            val hours = prefs.getInt("block_timeout_hours", 24)
            val unlockEpoch = System.currentTimeMillis() + hours * 3600 * 1000L
            prefs.edit().putLong("block_timeout_epoch", unlockEpoch).apply()
            
            val okBtn = nodes.firstOrNull { 
                val txt = it.text?.toString()?.lowercase() ?: ""
                listOf("ok", "đóng", "close").any { hint -> txt.equals(hint, ignoreCase=true) } && (it.isClickable || it.parent?.isClickable == true)
            }
            if (okBtn != null) {
                okBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: okBtn.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            
            markCurrentDone(false, "FACEBOOK_ACTION_BLOCK", "Facebook chặn thao tác; tài khoản cần cooldown.")
            stopProcessing()
            return true
        }
        return false
    }

    private fun interceptGroupJoin(nodes: List<AccessibilityNodeInfo>): Boolean {
        var altered = false

        // 1. Click "Tham gia nhóm" (Join Group)
        val joinBtn = nodes.firstOrNull { 
            val txt = it.text?.toString()?.lowercase() ?: ""
            (Engine.groupJoin.contains(txt)) && (it.isClickable || it.parent?.isClickable == true)
        }
        if (joinBtn != null) {
            Log.d(TAG, "Intercepted Join Group Request")
            joinBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: joinBtn.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }

        // 2. Look for Questionnaire Items (Only fill if we see Submit buttons or Checkboxes)
        val submitBtn = nodes.firstOrNull { 
            val txt = it.text?.toString()?.lowercase() ?: ""
            val cd = it.contentDescription?.toString()?.lowercase() ?: ""
            ((Engine.questionnaireSubmit.contains(txt)) && (it.isClickable || it.parent?.isClickable == true)) ||
            ((Engine.questionnaireSubmit.contains(cd)) && (it.isClickable || it.parent?.isClickable == true))
        }

        val editTexts = nodes.filter { it.className?.toString() == "android.widget.EditText" }
        val checkBoxes = nodes.filter { it.className?.toString() == "android.widget.CheckBox" || it.className?.toString() == "android.widget.RadioButton" }
        
        val isQuestionnaire = nodes.any { 
            val txt = it.text?.toString()?.lowercase() ?: ""
            txt.contains("tham gia nhóm") || txt.contains("câu hỏi") || txt.contains("quy tắc")
        }

        if (isQuestionnaire && (editTexts.isNotEmpty() || checkBoxes.isNotEmpty() || submitBtn != null)) {
            Log.d(TAG, "Intercepted Group Questionnaire")
            
            for (et in editTexts) {
                val txt = et.text?.toString() ?: ""
                if (txt.isBlank() || txt.contains("câu trả lời", true) || txt.contains("answer", true)) {
                    val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "ok") }
                    et.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    altered = true
                }
            }

            for (cb in checkBoxes) {
                if (!cb.isChecked) {
                    cb.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: cb.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    altered = true
                }
            }

            if (!altered && submitBtn != null) {
                submitBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: submitBtn.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                altered = true
            }
        }
        return altered
    }

    /* ================== DEAD LINK INTERCEPTOR ================== */
    private fun interceptDeadLink(nodes: List<AccessibilityNodeInfo>): Boolean {
        val isDead = nodes.any { 
            val text = it.text?.toString()?.lowercase() ?: ""
            Engine.deadLink.any { deadTxt -> text.contains(deadTxt) }
        }

        if (isDead) {
            // Check if user simply hasn't joined the group (Join Group button visible)
            // IMPORTANT: Exclude "đã tham gia" (already joined) indicators — those are NOT join buttons
            val hasJoinButton = nodes.any {
                val txt = it.text?.toString()?.lowercase() ?: ""
                val desc = it.contentDescription?.toString()?.lowercase() ?: ""
                if (txt.contains("đã tham gia") || desc.contains("đã tham gia") || 
                    txt.contains("đã gia nhập") || desc.contains("đã gia nhập")) return@any false
                Engine.groupJoin.any { anchor -> txt.contains(anchor) || desc.contains(anchor) }
            }

            if (hasJoinButton) {
                debugLog("⚠️ Nội dung không hiển thị vì chưa tham gia nhóm. Bài vẫn sống, bỏ qua (KHÔNG xóa).")
                dumpScreenToLog("DEAD_LINK_BUT_NOT_JOINED")
                markCurrentDone(false, "GROUP_MEMBERSHIP_REQUIRED", "Tài khoản chưa tham gia group nên không xem được bài.")
                return true
            }

            Log.w(TAG, "DEAD LINK detected! Aborting interaction to preserve safety.")
            debugLog("❌ LỖI NGHIÊM TRỌNG: Phát hiện bài viết đã bị xóa hoặc nhóm bị khóa ('Nội dung này hiện không hiển thị'). Đang tiến hành xóa vĩnh viễn bài đăng khỏi hệ thống để bảo vệ các máy khác...")
            dumpScreenToLog("DEAD_LINK_CONFIRMED")
            val currentPostId = currentTask?.postId
            if (!currentPostId.isNullOrEmpty()) {
                onPostDead?.invoke(currentPostId)
            }
            // Mark as done locally to clear from queue
            markCurrentDone(false, "TARGET_POST_UNAVAILABLE", "Bài đã bị xóa, group bị khóa hoặc nội dung không còn khả dụng.", retryable = false)
            return true
        }
        return false
    }

    /* ================== STEP HANDLERS ================== */

    private var lastLoadLogTime = 0L

    private fun taskRequiresTargetPostSearch(task: TaskItem?): Boolean {
        return task != null && !task.isPublishingGroup && !task.isScrapingGroup &&
            (task.targetPostAnchors.isNotEmpty() || task.targetPostText.isNotBlank() || task.targetPostAuthor.isNotBlank())
    }

    private fun firstInteractionStep(task: TaskItem?): Step {
        return if (task?.actionLike == false && task.actionComment) Step.LOOKING_FOR_COMMENT_FIELD else Step.LOOKING_FOR_LIKE
    }

    private fun resetTargetSearch() {
        targetSearchScrollCount = 0
        targetSearchStartedAt = 0L
        lastTargetSearchSignature = 0
        unchangedTargetSearchCount = 0
    }

    private fun handleWaitingForLoad() {
        val now = System.currentTimeMillis()
        if (now - lastLoadLogTime >= 1000) {
            debugLog("Đang chờ Facebook tải xong... (retry=$retryCount)")
            lastLoadLogTime = now
        }
        if (retryCount >= 10 && retryCount % 5 == 0) dumpScreenToLog("WAITING_FOR_FB_LOAD")
        val root = rootInActiveWindow ?: return
        
        val task = currentTask
        if (task != null && task.url.startsWith("fb_join_keyword:")) {
            // For keyword group-joining, wait 15 seconds to let DOM interceptors click join buttons, then succeed
            if (retryCount >= 30) {
                debugLog("✅ Đã hoàn thành tìm kiếm và tham gia nhóm cho từ khóa.")
                markCurrentDone(success = true)
            } else {
                setNextStepDelay(500)
            }
            root.recycle()
            return
        }

        val allNodes = findAllNodes(root)
        
        // Dead link check takes absolute priority
        if (interceptDeadLink(allNodes)) {
            recycleNodes(allNodes)
            root.recycle()
            return
        }

        if (currentTask?.isScrapingGroup == true) {
            val hasGroupInfo = allNodes.any { it.text?.toString()?.contains("thành viên", ignoreCase = true) == true }
            recycleNodes(allNodes)
            if (hasGroupInfo) currentStep = Step.SCRAPING_GROUP_INFO
            root.recycle()
            return
        }

        if (taskRequiresTargetPostSearch(currentTask)) {
            val targetRegion = resolveTargetPostRegion(root, allNodes)
            val pageReady = allNodes.count { it.isVisibleToUser } >= 6
            recycleNodes(allNodes)

            if (targetRegion != null) {
                debugLog("🎯 Đã khóa đúng bài mục tiêu ngay khi mở link (điểm=${targetRegion.confidence}).")
                currentStep = firstInteractionStep(currentTask)
                retryCount = 0
                setNextStepDelay(STEP_DELAY)
            } else if (pageReady) {
                resetTargetSearch()
                targetSearchStartedAt = System.currentTimeMillis()
                currentStep = Step.SEEKING_TARGET_POST
                retryCount = 0
                debugLog("🔎 Chưa thấy bài mục tiêu trong màn hình đầu; bắt đầu tự cuộn ngay.")
                setNextStepDelay(350)
            }
            root.recycle()
            return
        }
        
        recycleNodes(allNodes)

        if (currentTask?.isPublishingGroup == true) {
            // Wait for group to load (composer placeholder visible)
            val composer = findGroupComposerPlaceholder(root)
            if (composer != null) {
                Log.d(TAG, "Group loaded — found composer")
                composer.recycle()
                currentStep = Step.LOOKING_FOR_COMPOSER
                retryCount = 0
                setNextStepDelay(STEP_DELAY)
            }
        } else {
            // Check if we can find any interactable content (Like button area or comment area)
            val likeNode = findLikeButton(root)
            val commentArea = findCommentInput(root)

            if (likeNode != null || commentArea != null) {
                debugLog("✅ FB đã tải xong! Tìm thấy: Like=${likeNode != null}, Comment=${commentArea != null} (sau $retryCount retry)")
                dumpScreenToLog("FB_LOADED_OK")
                likeNode?.recycle()
                commentArea?.recycle()
                currentStep = firstInteractionStep(currentTask)
                retryCount = 0
                setNextStepDelay(STEP_DELAY)
            } else if (retryCount >= 10) {
                // After 5 seconds of waiting, check if we landed on a Group page instead of a post
                val allNodes = findAllNodes(root)
                val hasGroupFeed = allNodes.any {
                    val txt = it.text?.toString()?.lowercase() ?: ""
                    val desc = it.contentDescription?.toString()?.lowercase() ?: ""
                    txt.contains("bạn viết gì đi") || txt.contains("what's on your mind") ||
                    txt.contains("thành viên") || txt.contains("members") ||
                    desc.contains("bạn viết gì đi") || desc.contains("what's on your mind")
                }
                val hasComposerPlaceholder = findGroupComposerPlaceholder(root) != null
                recycleNodes(allNodes)

                if (hasGroupFeed || hasComposerPlaceholder) {
                    // Distinguish: is it because user hasn't joined the group, or is the post truly dead?
                    val allNodes2 = findAllNodes(root)
                    val hasJoinButton = allNodes2.any {
                        val txt = it.text?.toString()?.lowercase() ?: ""
                        val desc = it.contentDescription?.toString()?.lowercase() ?: ""
                        if (txt.contains("đã tham gia") || desc.contains("đã tham gia") || 
                            txt.contains("đã gia nhập") || desc.contains("đã gia nhập")) return@any false
                        Engine.groupJoin.any { anchor -> txt.contains(anchor) || desc.contains(anchor) }
                    }
                    recycleNodes(allNodes2)

                    if (hasJoinButton) {
                        // User hasn't joined this group yet — post is still alive, just inaccessible to this user
                        debugLog("⚠️ Chưa tham gia nhóm này! Bài vẫn còn sống, chỉ là user chưa join group. Bỏ qua bài này (KHÔNG xóa khỏi hệ thống).")
                        dumpScreenToLog("NOT_IN_GROUP_SKIP")
                    } else {
                        // Post is truly dead or removed
                        debugLog("❌ Link bài viết đã redirect về trang Group (bài bị xoá hoặc không tồn tại). Đang xóa khỏi hệ thống...")
                        dumpScreenToLog("POST_DEAD_GROUP_REDIRECT")
                        val currentPostId = currentTask?.postId
                        if (!currentPostId.isNullOrEmpty()) {
                            onPostDead?.invoke(currentPostId)
                        }
                    }
                    markCurrentDone(success = false)
                    root.recycle()
                    return
                }
            }
        }
        root.recycle()
    }

    private fun targetSearchSignature(nodes: List<AccessibilityNodeInfo>): Int {
        return nodes.asSequence()
            .filter { it.isVisibleToUser }
            .mapNotNull { node ->
                val label = nodeFields(node).joinToString(" ").take(100)
                if (label.isBlank()) null else {
                    val bounds = nodeBounds(node)
                    "$label@${bounds.top}:${bounds.bottom}"
                }
            }
            .take(30)
            .joinToString("|")
            .hashCode()
    }

    private fun dispatchSearchSwipe(): Boolean {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels * 0.5f
        val startY = metrics.heightPixels * 0.78f
        val endY = metrics.heightPixels * 0.28f
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 550))
                .build(),
            null,
            null
        )
    }

    private fun handleSeekingTargetPost() {
        val root = rootInActiveWindow ?: return
        val nodes = findAllNodes(root)

        if (interceptDeadLink(nodes)) {
            recycleNodes(nodes)
            root.recycle()
            return
        }

        val region = resolveTargetPostRegion(root, nodes)
        if (region != null) {
            debugLog("🎯 Đã tìm thấy bài mục tiêu sau $targetSearchScrollCount lần cuộn (điểm=${region.confidence}).")
            recycleNodes(nodes)
            root.recycle()
            currentStep = firstInteractionStep(currentTask)
            retryCount = 0
            setNextStepDelay(STEP_DELAY)
            return
        }

        val elapsed = System.currentTimeMillis() - targetSearchStartedAt
        if (targetSearchScrollCount >= 15 || elapsed >= 30_000L || unchangedTargetSearchCount >= 5) {
            debugLog("❌ TARGET_POST_NOT_FOUND: Không tìm thấy bài sau $targetSearchScrollCount lần cuộn/${elapsed / 1000}s.")
            dumpScreenToLog("TARGET_POST_NOT_FOUND")
            recycleNodes(nodes)
            root.recycle()
            markCurrentDone(success = false)
            return
        }

        val signature = targetSearchSignature(nodes)
        if (lastTargetSearchSignature != 0 && signature == lastTargetSearchSignature) {
            unchangedTargetSearchCount++
        } else {
            unchangedTargetSearchCount = 0
        }
        lastTargetSearchSignature = signature

        val scrollable = nodes.asSequence()
            .filter { it.isVisibleToUser && it.isScrollable }
            .maxByOrNull {
                val bounds = nodeBounds(it)
                bounds.width().toLong() * bounds.height().toLong()
            }
        var scrollStarted = scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
        recycleNodes(nodes)
        root.recycle()

        if (!scrollStarted) scrollStarted = dispatchSearchSwipe()
        targetSearchScrollCount++
        currentStatusText.value = "Đang tự cuộn tìm bài mục tiêu... ($targetSearchScrollCount/15)"
        debugLog("🔎 Chưa thấy bài mục tiêu; cuộn lần $targetSearchScrollCount/15 (${if (scrollStarted) "đã gửi lệnh" else "không cuộn được"}).")
        if (!scrollStarted) unchangedTargetSearchCount++
        setNextStepDelay(1_100L)
    }

    private fun handleScrapingGroupInfo() {
        if (currentStep != Step.SCRAPING_GROUP_INFO) return
        val root = rootInActiveWindow ?: return
        val nodes = findAllNodes(root)
        
        val textNodes = nodes.filter { !it.text.isNullOrBlank() }.map { it.text.toString().trim() }
        val memberIdx = textNodes.indexOfFirst { it.contains("thành viên", ignoreCase = true) || it.contains("members", ignoreCase = true) }
        
        if (memberIdx != -1) {
            val memberCountStr = textNodes[memberIdx]
            // Strict regex: must start with digit, then capture numerical points, optionally followed by space and magnitude letters (K, M, tr)
            val memberCount = Regex("(?i)[0-9]+[.,0-9]*\\s*[a-z]*").find(memberCountStr)?.value?.trim() ?: "0"
            val nameCanditates = textNodes.subList(0, memberIdx).filter { it.length > 3 && !it.contains("Tham gia", true) && !it.contains("Join", true) }
            val groupName = nameCanditates.lastOrNull() ?: "Nhóm Facebook"

            Intent("com.example.commenthelper.GROUP_SCRAPED").apply {
                putExtra("name", groupName)
                putExtra("memberCount", memberCount.replace(",", ".").uppercase())
                putExtra("url", currentTask?.url ?: "")
                sendBroadcast(this)
            }
            markCurrentDone(true)
        }
        recycleNodes(nodes)
    }

    private fun handleLookingForLike() {
        debugLog("Đang tìm nút Like...")
        val root = rootInActiveWindow ?: return
        val task = currentTask ?: run {
            root.recycle()
            return
        }

        if (!task.actionLike) {
            currentStep = Step.LOOKING_FOR_COMMENT_FIELD
            retryCount = 0
            root.recycle()
            setNextStepDelay(STEP_DELAY)
            return
        }

        val likeNode = findLikeButton(root)
        if (likeNode != null) {
            dumpScreenToLog("LIKE_FOUND")
            // Check if already liked
            if (isAlreadyLiked(likeNode)) {
                debugLog("ℹ️ Bài đã Like rồi, bỏ qua.")
                task.executorJobId?.let { onActionProgress?.invoke(it, "like", "ALREADY_DONE") }
                likeNode.recycle()
            } else {
                debugLog("✅ Tìm thấy nút Like! Đang bấm...")
                likeNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                // Also try clicking parent if the node itself is not clickable
                if (!likeNode.isClickable) {
                    likeNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                task.executorJobId?.let { onActionProgress?.invoke(it, "like", "CONFIRMED") }
                likeNode.recycle()
            }
            if (!task.actionComment) {
                debugLog("✅ Job chỉ yêu cầu Like, hoàn thành sau bước Like.")
                root.recycle()
                markCurrentDone(success = true)
                return
            }
            currentStep = Step.LOOKING_FOR_COMMENT_FIELD
            retryCount = 0
            setNextStepDelay(STEP_DELAY)
        } else {
            // No like button found — maybe already liked or different layout
            debugLog("⚠️ Không tìm thấy nút Like, chuyển sang tìm Comment. (retry=$retryCount)")
            dumpScreenToLog("LIKE_NOT_FOUND")
            if (!task.actionComment) {
                root.recycle()
                markCurrentDone(success = true)
                return
            }
            currentStep = Step.LOOKING_FOR_COMMENT_FIELD
            retryCount = 0
        }
        root.recycle()
    }

    private fun handleLookingForCommentField() {
        debugLog("Đang tìm ô Bình luận... (retry=$retryCount)")
        val root = rootInActiveWindow ?: return
        val task = currentTask ?: run {
            root.recycle()
            return
        }
        if (!task.actionComment) {
            root.recycle()
            markCurrentDone(success = true)
            return
        }

        // Trước khi bình luận, quét xem mình đã cmt bài viết này chưa để tránh cmt trùng
        val prefs = getSharedPreferences("comment_helper_prefs", Context.MODE_PRIVATE)
        val fbProfileName = prefs.getString("fbProfileName", "") ?: ""
        val declaredFbName = prefs.getString("facebookName", "") ?: ""
        
        var alreadyCommented = false
        val allNodes = findAllNodes(root)
        for (n in allNodes) {
            val txt = n.text?.toString()?.trim() ?: ""
            if (txt.isNotBlank()) {
                if ((fbProfileName.isNotBlank() && txt.equals(fbProfileName, ignoreCase = true)) ||
                    (declaredFbName.isNotBlank() && txt.equals(declaredFbName, ignoreCase = true))) {
                    alreadyCommented = true
                    break
                }
            }
        }
        recycleNodes(allNodes)
        
        if (alreadyCommented) {
            debugLog("ℹ️ Phát hiện bạn đã bình luận bài viết này rồi! Bỏ qua comment.")
            markCurrentDone(success = true)
            root.recycle()
            return
        }

        // First try: find existing comment input that's already visible
        var commentInput = findCommentInput(root)

        if (commentInput == null) {
            // Try clicking on "Write a comment" placeholder to open the input
            val commentPlaceholder = findCommentPlaceholder(root)
            if (commentPlaceholder != null) {
                Log.d(TAG, "Clicking comment placeholder to open input")
                commentEntryOpened = performClick(commentPlaceholder)
                commentPlaceholder.recycle()
                root.recycle()
                // Wait and retry
                retryCount = 0
                setNextStepDelay(STEP_DELAY)
                return
            }
        }

        if (commentInput != null) {
            debugLog("✅ Tìm thấy ô Bình luận! Đang gõ: '${task.comment.take(30)}...'")
            dumpScreenToLog("COMMENT_FIELD_FOUND")

            // Focus the input
            commentInput.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            commentInput.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, task.comment)
            }
            val setSuccess = commentInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (!setSuccess) {
                Log.d(TAG, "SET_TEXT failed, falling back to PASTE")
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("comment", task.comment))
                commentInput.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
            commentInput.recycle()

            currentStep = Step.WAITING_FOR_COMMENT_SENT
            retryCount = 0
            // Give time for text to be set, then look for send button
            setNextStepDelay(STEP_DELAY)
        } else {
            debugLog("⚠️ Không tìm thấy ô comment lẫn placeholder! (retry=$retryCount)")
            dumpScreenToLog("COMMENT_FIELD_NOT_FOUND")
        }
        root.recycle()
    }

    private fun isExpectedCommentSubmitted(root: AccessibilityNodeInfo, expectedComment: String): Boolean {
        val expected = normalizeUiText(expectedComment)
        if (expected.isBlank()) return false
        val nodes = findAllNodes(root)
        val found = nodes.any { node ->
            node.isVisibleToUser && !node.isEditable &&
                node.className?.toString() != "android.widget.EditText" &&
                nodeFields(node).any { field -> field == expected }
        }
        recycleNodes(nodes)
        return found
    }

    private fun dispatchTap(node: AccessibilityNodeInfo): Boolean {
        val bounds = nodeBounds(node)
        if (bounds.isEmpty) return false
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 120))
                .build(),
            null,
            null
        )
    }

    private fun findAndClickSend() {
        val root = rootInActiveWindow ?: return
        val task = currentTask ?: return

        if (!task.isPublishingGroup && isExpectedCommentSubmitted(root, task.comment)) {
            debugLog("✅ Nội dung bình luận đã xuất hiện ngoài ô nhập; xác nhận Facebook đã gửi thành công.")
            task.executorJobId?.let { onActionProgress?.invoke(it, "comment", "CONFIRMED") }
            root.recycle()
            markCurrentDone(success = true)
            return
        }

        val sendButton = findSendButton(root)
        if (sendButton != null) {
            debugLog("✅ Tìm thấy nút Gửi/Đăng! Đang bấm...")
            dumpScreenToLog("SEND_BUTTON_FOUND")
            val checkpointAccepted = task.executorJobId?.let { onIrreversibleAction?.invoke(it) ?: false } ?: true
            if (!checkpointAccepted) {
                debugLog("❌ Không xác nhận được checkpoint với server; không bấm Gửi/Đăng.")
                sendButton.recycle()
                root.recycle()
                markCurrentDone(false, "CHECKPOINT_REJECTED", "Server không xác nhận checkpoint nên app không bấm Gửi/Đăng.")
                return
            }
            val clicked = performClick(sendButton) || dispatchTap(sendButton)
            if (!clicked) {
                debugLog("⚠️ Đã nhận diện nút Gửi nhưng không thể click node/ancestor/tọa độ.")
                sendButton.recycle()
                root.recycle()
                setNextStepDelay(800)
                return
            }
            sendButton.recycle()
            root.recycle()

            // Wait a moment for the comment to be submitted
            if (task.isPublishingGroup) {
                currentStep = Step.WAITING_FOR_POST_TO_UPLOAD
                retryCount = 0
                setNextStepDelay(3000)
            } else {
                setNextStepDelay(3000)
                handler.postDelayed({
                    task.executorJobId?.let { onActionProgress?.invoke(it, "comment", "CONFIRMED") }
                    markCurrentDone(success = true)
                }, 3000)
            }
        } else {
            if (retryCount % 3 == 0) {
                debugLog("⚠️ Không tìm thấy nút Gửi/Đăng! (retry=$retryCount)")
                dumpScreenToLog("SEND_BUTTON_NOT_FOUND")
            }
            root.recycle()
            setNextStepDelay(500)
        }
    }

    private fun handleWaitingForCommentSent() {
        debugLog("Đang tìm nút Đăng...")
        findAndClickSend()
    }

    private fun handleLookingForComposer() {
        debugLog("Đang tìm ô Soạn bài...")
        val root = rootInActiveWindow ?: return
        val composer = findGroupComposerPlaceholder(root)
        if (composer != null) {
            debugLog("✅ Tìm thấy ô Soạn bài! Đang mở...")
            dumpScreenToLog("COMPOSER_FOUND")
            performClick(composer)
            composer.recycle()
            
            currentStep = Step.WAITING_FOR_COMPOSER_INPUT
            retryCount = 0
            setNextStepDelay(STEP_DELAY)
        } else {
            if (retryCount % 3 == 0) {
                debugLog("⚠️ Không tìm thấy ô Soạn bài! (retry=$retryCount)")
                dumpScreenToLog("COMPOSER_NOT_FOUND")
            }
            setNextStepDelay(500)
        }
        root.recycle()
    }

    private fun handleWaitingForComposerInput() {
        debugLog("Đang gõ nội dung bài viết...")
        val root = rootInActiveWindow ?: return
        val task = currentTask ?: return

        val inputNode = findGroupComposerInput(root)
        if (inputNode != null) {
            debugLog("✅ Tìm thấy ô nhập nội dung! Đang gõ: '${task.comment.take(30)}...'")
            dumpScreenToLog("COMPOSER_INPUT_FOUND")
            inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, task.comment) }
            val setSuccess = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (!setSuccess) {
                Log.d(TAG, "SET_TEXT failed, falling back to PASTE")
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("comment", task.comment))
                inputNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
            inputNode.recycle()

            if (task.imageCount > 0) {
                currentStep = Step.LOOKING_FOR_PHOTO_BUTTON
                retryCount = 0
                setNextStepDelay(STEP_DELAY)
            } else {
                currentStep = Step.LOOKING_FOR_COMPOSER_DONE
                retryCount = 0
                setNextStepDelay(STEP_DELAY)
            }
        } else {
            if (retryCount % 3 == 0) {
                debugLog("⚠️ Không tìm thấy ô nhập nội dung bài! (retry=$retryCount)")
                dumpScreenToLog("COMPOSER_INPUT_NOT_FOUND")
            }
            setNextStepDelay(500)
        }
        root.recycle()
    }

    private fun handleLookingForComposerDone() {
        val root = rootInActiveWindow ?: return
        val doneButton = findBestCandidate(root, ActionTarget.COMPOSER_DONE)
        if (doneButton != null) {
            debugLog("✅ Tìm thấy nút Xong sau khi nhập nội dung; đang bấm...")
            dumpScreenToLog("COMPOSER_DONE_FOUND")
            val clicked = performClick(doneButton) || dispatchTap(doneButton)
            doneButton.recycle()
            if (clicked) {
                currentStep = Step.WAITING_FOR_COMMENT_SENT
                retryCount = 0
                setNextStepDelay(1_200L)
            } else {
                debugLog("⚠️ Nhận diện được nút Xong nhưng chưa click được.")
                setNextStepDelay(700L)
            }
            root.recycle()
            return
        }

        // Gallery may return directly to the final publish screen without a
        // separate Done button. In that case, move on only after scoring Post.
        val publishButton = findBestCandidate(root, ActionTarget.PUBLISH_POST)
        if (publishButton != null) {
            debugLog("ℹ️ Không còn bước Xong; màn hình đã sẵn sàng nút Đăng.")
            publishButton.recycle()
            currentStep = Step.WAITING_FOR_COMMENT_SENT
            retryCount = 0
            setNextStepDelay(300L)
        } else {
            if (retryCount % 3 == 0) {
                debugLog("⚠️ Chưa tìm thấy nút Xong hoặc Đăng (retry=$retryCount).")
                dumpScreenToLog("COMPOSER_DONE_NOT_FOUND")
            }
            setNextStepDelay(700L)
        }
        root.recycle()
    }

    private fun handleLookingForPhotoButton() {
        debugLog("Đang tìm nút Thêm Ảnh...")
        val root = rootInActiveWindow ?: return
        val photoBtn = findNodeByContentDescription(root, Engine.photoButton)
            ?: findNodeByHint(root, Engine.photoButton)

        if (photoBtn != null) {
            debugLog("✅ Tìm thấy nút Thêm Ảnh! Đang mở Gallery...")
            dumpScreenToLog("PHOTO_BUTTON_FOUND")
            performClick(photoBtn)
            photoBtn.recycle()
            
            currentStep = Step.SELECTING_PHOTOS
            retryCount = 0
            setNextStepDelay(2500) // Gallery load buffer
        } else {
            if (retryCount % 3 == 0) {
                debugLog("⚠️ Không tìm thấy nút Thêm Ảnh! (retry=$retryCount)")
                dumpScreenToLog("PHOTO_BUTTON_NOT_FOUND")
            }
            if (retryCount == 10) {
                debugLog("⚠️ Đang thử đóng bàn phím để tìm nút ảnh...")
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            setNextStepDelay(500)
        }
        root.recycle()
    }

    private var multiSelectClicked = false

    private fun isHighValueLog(msg: String): Boolean {
        val lowValueKeywords = listOf(
            "Đang chờ Facebook",
            "Đang tìm nút Like",
            "Đang tìm ô Bình luận",
            "Đang tìm ô Soạn bài",
            "Đang tìm nút Thêm Ảnh",
            "Đang gõ nội dung bài viết",
            "Đang xử lý lấy link bài viết",
            "Đang dọn dẹp FB",
            "Đợi lâu không thấy bài đăng"
        )
        return lowValueKeywords.none { msg.contains(it) }
    }

    private fun debugLog(msg: String, alwaysToast: Boolean = false) {
        Log.d(TAG, "DEBUG_TRACE: $msg")
        try {
            val file = java.io.File(filesDir, "debug_logs.txt")
            val now = java.util.Calendar.getInstance()
            if (now.get(java.util.Calendar.HOUR_OF_DAY) == 3 && file.exists() && System.currentTimeMillis() - file.lastModified() > 2 * 24 * 3600 * 1000L) {
                file.delete()
            }
            val timestamp = java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            file.appendText("[$timestamp] $msg\n")
            if (file.length() > 2 * 1024 * 1024) { // Keep log under 2MB
                file.writeText("--- Log Truncated ---\n")
            }
        } catch (e: Exception) {}

        if (isHighValueLog(msg)) {
            Thread {
                try {
                    val prefs = getSharedPreferences("comment_helper_prefs", android.content.Context.MODE_PRIVATE)
                    val user = prefs.getString("username", "unknown") ?: "unknown"
                    val conn = java.net.URL("$SERVER_URL/api/logs/apk").openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    val payload = org.json.JSONObject().apply {
                        put("log", msg)
                        put("username", user)
                    }
                    conn.outputStream.write(payload.toString().toByteArray())
                    conn.responseCode
                } catch (e: Exception) {}
            }.start()
        }

        if (isDebugMode || alwaysToast) {
            try { handler.post { android.widget.Toast.makeText(this, "🐢 $msg", android.widget.Toast.LENGTH_SHORT).show() } } catch(_: Exception) {}
        }
    }

    /** Dump top visible nodes to debug log — call at every failure point */
    private fun dumpScreenToLog(stepName: String) {
        try {
            val root = rootInActiveWindow ?: run { debugLog("  🔍 X-RAY[$stepName]: rootInActiveWindow = NULL (app không nắm được màn hình)"); return }
            val nodes = findAllNodes(root)
            val relevant = nodes.filter { n ->
                val t = n.text?.toString() ?: ""
                val d = n.contentDescription?.toString() ?: ""
                t.isNotBlank() || d.isNotBlank() || n.isClickable
            }
            debugLog("  🔍 X-RAY[$stepName]: ${relevant.size} node hiển thị (tổng ${nodes.size}):")
            var count = 0
            for (n in relevant) {
                val c = n.className?.toString()?.substringAfterLast('.') ?: ""
                val t = n.text?.toString()?.take(80) ?: ""
                val d = n.contentDescription?.toString()?.take(80) ?: ""
                debugLog("    [$c] text='$t' | desc='$d' | click=${n.isClickable} | edit=${n.isEditable}")
                count++
                if (count >= 25) { debugLog("    ... (cắt bớt, còn ${relevant.size - 25} node)"); break }
            }
            recycleNodes(nodes)
            root.recycle()
        } catch (e: Exception) { debugLog("  🔍 X-RAY[$stepName]: Lỗi dump: ${e.message}") }
    }

    private fun handleSelectingPhotos() {
        val root = rootInActiveWindow ?: return
        val task = currentTask ?: return

        // Step 0: If we need multiple photos, click multi-select button first
        if (task.imageCount > 1 && !multiSelectClicked) {
            val multiBtn = findNodeByText(root, Engine.multiSelectButton)
                ?: findNodeByContentDescription(root, Engine.multiSelectButton)
            if (multiBtn != null) {
                debugLog("📸 Bấm 'Chọn nhiều file'...")
                Log.d(TAG, "Clicking multi-select button")
                multiBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!multiBtn.isClickable) multiBtn.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                multiBtn.recycle()
                multiSelectClicked = true
                root.recycle()
                setNextStepDelay(2000)
                return
            }
            if (retryCount >= 5) {
                debugLog("⚠️ Không tìm thấy nút Chọn nhiều, bỏ qua...")
                multiSelectClicked = true
            }
        }

        val allImages = findAllGalleryImages(root)
        if (allImages.isNotEmpty()) {
            val count = Math.min(task.imageCount, allImages.size)
            debugLog("✅ Tìm thấy ${allImages.size} ảnh trong Gallery, chọn $count...")
            dumpScreenToLog("GALLERY_IMAGES_FOUND")
            allImages.forEachIndexed { idx, n ->
                Log.d(TAG, "  Node $idx: cd='${n.contentDescription}' class=${n.className} click=${n.isClickable}")
            }

            for (i in 0 until count) {
                val node = allImages[i]
                handler.postDelayed({
                    try {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (!node.isClickable) node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        debugLog("✅ Chọn ảnh ${i + 1}/$count")
                        Log.d(TAG, "Clicked photo $i/$count")
                        node.recycle()
                    } catch(e: Exception) { Log.e(TAG, "Photo click $i failed", e) }
                }, i * Engine.galleryClickDelay)
            }
            for (i in count until allImages.size) allImages[i].recycle()

            val waitTime = count * Engine.galleryClickDelay + 2000L
            nextStepTime = System.currentTimeMillis() + waitTime + 1500L
            handler.postDelayed({
                debugLog("📸 Đang tìm nút 'Tiếp'...")
                val r2 = rootInActiveWindow
                if (r2 != null) {
                    val doneBtn = findNodeByContentDescription(r2, Engine.galleryNextButton)
                        ?: findNodeByText(r2, Engine.galleryNextButton)
                    if (doneBtn != null) {
                        debugLog("✅ Bấm 'Tiếp'!")
                        Log.d(TAG, "Clicking gallery Done/Next")
                        doneBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (!doneBtn.isClickable) doneBtn.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        doneBtn.recycle()
                    } else {
                        debugLog("⚠️ Không thấy nút Tiếp! Đang chờ timeout...")
                        Log.w(TAG, "Missing NEXT button")
                    }
                    r2.recycle()
                } else {
                    debugLog("⚠️ rootInActiveWindow null, bỏ qua tìm nút Tiếp...")
                }
                
                currentStep = Step.LOOKING_FOR_COMPOSER_DONE
                retryCount = 0
                multiSelectClicked = false
                setNextStepDelay(2500)
            }, waitTime)
        } else {
            // We do NOT increment retryCount here anymore because startRetryChecker already increments it!
            // Let's just use the existing retryCount.

            if (retryCount % 5 == 0 || retryCount % 5 == 1) {
                debugLog("📸 Đang chờ ảnh load... ($retryCount/30)")
            }
            dumpScreenToLog("GALLERY_IMAGES_NOT_FOUND")

            if (retryCount >= 30) {
                debugLog("❌ Không tìm được ảnh, đăng text!")
                Log.w(TAG, "Gallery stuck $retryCount retries. Posting text only.")
                multiSelectClicked = false
                performGlobalAction(GLOBAL_ACTION_BACK)
                currentStep = Step.LOOKING_FOR_COMPOSER_DONE
                retryCount = 0
                setNextStepDelay(1500)
            } else {
                setNextStepDelay(1000) // Give it more time to load between checks
            }
        }
        root.recycle()
    }


    private fun handleWaitingForPostToUpload() {
        debugLog("Đang chờ bài đăng upload...")
        val root = rootInActiveWindow ?: return
        val task = currentTask ?: return
        
        val allNodes = findAllNodes(root)
        
        val isUploading = allNodes.any {
            val text = it.text?.toString()?.lowercase() ?: ""
            text.contains("đang đăng") || text.contains("posting")
        }
        if (isUploading) {
            postUploadStableChecks = 0
            setNextStepDelay(1000)
            recycleNodes(allNodes)
            root.recycle()
            return
        }
        
        val submittedTexts = listOf("bài viết của bạn đã được gửi", "submitted to admins", "đã gửi", "chờ phê duyệt", "pending", "đang chờ xử lý")
        val isPending = allNodes.any {
            val txt = it.text?.toString()?.lowercase() ?: ""
            val desc = it.contentDescription?.toString()?.lowercase() ?: ""
            submittedTexts.any { st -> txt.contains(st) || desc.contains(st) }
        }
        
        if (isPending) {
            Log.d(TAG, "Post requires admin approval. Cannot grab link.")
            markCurrentDone(success = true) 
            recycleNodes(allNodes)
            root.recycle()
            return
        }

        // Executor jobs only need a reliable success acknowledgement. Copying the
        // newly-created link via the fragile "Bạn" tab is a separate concern.
        if (task.executorJobId != null || !task.reportLegacyCompletion) {
            val hasComposerInput = allNodes.any {
                it.isVisibleToUser && (it.isEditable || it.className?.toString() == "android.widget.EditText")
            }
            val postTerms = Engine.postButton.map(::normalizeUiText)
            val hasPostButton = allNodes.any { node ->
                val fields = nodeFields(node)
                fields.any { field -> postTerms.any { term -> field == term } }
            }
            val composerStillOpen = hasComposerInput && hasPostButton

            if (composerStillOpen) {
                postUploadStableChecks = 0
                debugLog("⏳ Composer vẫn đang mở sau khi bấm Đăng; tiếp tục chờ xác nhận...")
                setNextStepDelay(1000)
                recycleNodes(allNodes)
                root.recycle()
                return
            }

            postUploadStableChecks++
            if (postUploadStableChecks >= 2) {
                debugLog("✅ Composer đã đóng ổn định; xác nhận bài đăng thành công.")
                recycleNodes(allNodes)
                root.recycle()
                markCurrentDone(success = true)
                return
            }

            debugLog("🔍 Composer đã đóng; kiểm tra ổn định ${postUploadStableChecks}/2...")
            setNextStepDelay(800)
            recycleNodes(allNodes)
            root.recycle()
            return
        }

        // Upload is done! Navigate to the "Bạn" (You) tab to easily find our post
        debugLog("Upload xong! Đang tìm tab 'Bạn' để vào danh sách bài viết...")
        currentStep = Step.CLICKING_YOU_TAB
        retryCount = 0
        setNextStepDelay(500)
        
        recycleNodes(allNodes)
        root.recycle()
    }

    private fun handleClickingYouTab() {
        val root = rootInActiveWindow ?: return
        val allNodes = findAllNodes(root)

        // Find the "Bạn" (You) tab button
        val youTab = allNodes.firstOrNull { 
            val txt = it.text?.toString()?.lowercase()?.trim() ?: ""
            val desc = it.contentDescription?.toString()?.lowercase()?.trim() ?: ""
            (txt == "bạn" || txt == "you" || desc == "bạn" || desc == "you") && 
            (it.isClickable || it.parent?.isClickable == true || it.parent?.parent?.isClickable == true)
        }

        if (youTab != null) {
            debugLog("Đã tìm thấy tab 'Bạn', đang click...")
            if (youTab.isClickable) {
                youTab.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else if (youTab.parent?.isClickable == true) {
                youTab.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                youTab.parent?.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            
            // Go to next step to search for our post inside the "Bạn" screen
            currentStep = Step.LOOKING_FOR_MY_POST
            retryCount = 0
            setNextStepDelay(2500) // Wait for profile screen to load
        } else {
            if (retryCount >= 10) {
                // Fallback if no "Bạn" tab exists in this group
                debugLog("⚠️ Không tìm thấy tab 'Bạn', thử tìm bài viết trực tiếp...")
                currentStep = Step.LOOKING_FOR_MY_POST
                retryCount = 0
                setNextStepDelay(500)
            } else {
                setNextStepDelay(1000)
            }
        }
        recycleNodes(allNodes)
        root.recycle()
    }

    private fun handleLookingForMyPost() {
        val root = rootInActiveWindow ?: return
        val allNodes = findAllNodes(root)
        val task = currentTask ?: return

        // Wait for OUR post text to appear
        val snippet = task.comment.take(30).trim()
        
        // Try to learn our FB profile name from "..." menu buttons on screen
        learnProfileName(allNodes)
        
        val ourPostNode = allNodes.firstOrNull { 
            val t = it.text?.toString() ?: ""
            val cd = it.contentDescription?.toString() ?: ""
            val textMatch = t.contains(snippet, ignoreCase = true) || cd.contains(snippet, ignoreCase = true)
            if (textMatch) {
                // Verify this post actually belongs to US, not someone else
                val idx = allNodes.indexOf(it)
                isMyPost(allNodes, idx)
            } else false
        }

        if (ourPostNode != null) {
            debugLog("Đã thấy bài đăng của mình! Đang tìm nút chia sẻ...")
            
            val nodeIndex = allNodes.indexOf(ourPostNode)
            
            // "Share" button (Public groups) usually appears AFTER the text node
            var shareBtn: AccessibilityNodeInfo? = null
            for (i in nodeIndex until minOf(allNodes.size, nodeIndex + 50)) {
                val node = allNodes[i]
                val txt = node.text?.toString()?.lowercase() ?: ""
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                if (txt == "chia sẻ" || txt == "share" || desc == "chia sẻ" || desc == "share") {
                    shareBtn = node
                    break
                }
            }

            if (shareBtn != null) {
                Log.d(TAG, "Found Share button near our post, clicking it...")
                shareBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!shareBtn.isClickable) shareBtn.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                currentStep = Step.CLICKING_SHARE_AND_COPY
                retryCount = 0
                setNextStepDelay(2000)
            } else {
                // Private groups: "..." More options menu usually appears BEFORE the text node
                var menuBtn: AccessibilityNodeInfo? = null
                for (i in nodeIndex downTo maxOf(0, nodeIndex - 30)) {
                    val node = allNodes[i]
                    val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                    if (desc.contains("lựa chọn khác cho bài viết của") || desc.contains("more options for") || desc == "tùy chọn" || desc == "options") {
                        menuBtn = node
                        break
                    }
                }
                if (menuBtn == null) {
                    for (i in nodeIndex until minOf(allNodes.size, nodeIndex + 30)) {
                        val node = allNodes[i]
                        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                        if (desc.contains("lựa chọn khác cho bài viết của") || desc.contains("more options for") || desc == "tùy chọn" || desc == "options") {
                            menuBtn = node
                            break
                        }
                    }
                }

                if (menuBtn != null) {
                    Log.d(TAG, "Found '...' menu near our post, clicking...")
                    menuBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (!menuBtn.isClickable) menuBtn.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    currentStep = Step.CLICKING_SHARE_AND_COPY
                    retryCount = 0
                    setNextStepDelay(2500) 
                } else {
                    setNextStepDelay(1000)
                }
            }

        } else {
            // Our post hasn't appeared yet. Wait.
            if (retryCount >= 25) {
                debugLog("⚠️ Đợi lâu không thấy bài đăng, có thể đang chờ duyệt. Bỏ qua lấy link.")
                markCurrentDone(success = true) // Treat as success because we clicked Post
            } else {
                // Scroll down a bit in case it's further down the list
                if (retryCount > 0 && retryCount % 5 == 0) {
                    val scrollable = allNodes.firstOrNull { it.isScrollable }
                    scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
                setNextStepDelay(1000)
            }
        }
        recycleNodes(allNodes)
        root.recycle()
    }

    private fun handleClickingNotificationTab() {
        val root = rootInActiveWindow ?: return
        val nodes = findAllNodes(root)
        
        var notifTab: android.view.accessibility.AccessibilityNodeInfo? = null
        for (node in nodes) {
            val cd = node.contentDescription?.toString()?.lowercase() ?: ""
            if (cd.contains("thông báo, tab") || cd.contains("notifications, tab")) {
                notifTab = node
                break
            }
        }
        
        if (notifTab != null) {
            debugLog("Đang chuyển sang Tab Thông báo...")
            notifTab.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            currentStep = Step.SCANNING_NOTIFICATIONS
            retryCount = 0
            setNextStepDelay(2000)
        } else {
            val header = nodes.find { it.text?.toString()?.lowercase() == "thông báo" || it.text?.toString()?.lowercase() == "notifications" }
            if (header != null) {
                currentStep = Step.SCANNING_NOTIFICATIONS
                retryCount = 0
                setNextStepDelay(500)
            }
        }
        recycleNodes(nodes)
        root.recycle()
    }

    private fun handleScanningNotifications() {
        val root = rootInActiveWindow ?: return
        val nodes = findAllNodes(root)
        
        var targetNode: android.view.accessibility.AccessibilityNodeInfo? = null
        for (node in nodes) {
            val txt = node.text?.toString()?.lowercase() ?: ""
            val cd = node.contentDescription?.toString()?.lowercase() ?: ""
            val fullText = "$txt $cd"
            
            if (fullText.length > 500) continue // Skip huge ViewGroups/RecyclerViews containing multiple merged notifications
            
            // Explicitly ignore login/member approvals
            if (Engine.notificationIgnore.any { fullText.contains(it) }) {
                continue
            }
            
            // Only match post or photo approvals
            if (Engine.notificationApprove.any { fullText.contains(it) }) {
                if (!processedNotifications.contains(fullText)) {
                    targetNode = node
                    processedNotifications.add(fullText)
                    break
                }
            }
        }
        
        if (targetNode != null) {
            debugLog("Phát hiện thông báo Phê duyệt. Đang click...")
            targetNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) ?: targetNode.parent?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            
            currentStep = Step.WAITING_FOR_OPENED_POST
            retryCount = 0
            setNextStepDelay(3000)
        } else {
            debugLog("Không tìm thấy/đã hết thông báo phê duyệt. Kết thúc quét.")
            markCurrentDone(success = true)
        }
        recycleNodes(nodes)
        root.recycle()
    }

    private fun handleClickingShareAndCopy() {
        debugLog("Đang xử lý lấy link bài viết...")
        val root = rootInActiveWindow ?: return
        val task = currentTask ?: return

        val allNodes = findAllNodes(root)

        // Wait to find "Copy link" (Sao chép liên kết)
        val copyBtn = allNodes.firstOrNull {
            val txt = it.text?.toString()?.lowercase() ?: ""
            val desc = it.contentDescription?.toString()?.lowercase() ?: ""
            txt.contains("sao chép") || txt.contains("copy link") || desc.contains("sao chép") || desc.contains("copy link")
        }

        if (copyBtn != null) {
            var clicked = false
            var target: AccessibilityNodeInfo? = copyBtn
            for (i in 0..3) { // Try clicking up to 3 levels up
                if (target?.isClickable == true) {
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    clicked = true
                    break
                }
                target = target?.parent
            }
            if (!clicked) {
                // Fallback force click
                copyBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                copyBtn.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            
            // Set next step to WAITING_FOR_CLIPBOARD to avoid race condition
            currentStep = Step.WAITING_FOR_CLIPBOARD
            retryCount = 0
            setNextStepDelay(1500)
        } else {
            // Scroll down the menu to find the Copy button (sometimes it's at the bottom)
            val allNodes = findAllNodes(root)
            val scrollable = allNodes.firstOrNull { it.isScrollable }
            scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            
            // Menu might be closed or click missed. Retry opening the menu every 5 retries!
            if (retryCount > 0 && retryCount % 5 == 0) {
                debugLog("⚠️ Vẫn chưa thấy nút Copy, thử cuộn hoặc bấm lại nút Chia sẻ/Menu...")
                val allNodes = findAllNodes(root)
                val shareBtn = findNodeByContentDescription(root, listOf("share", "chia sẻ"))
                    ?: findNodeByText(root, listOf("share", "chia sẻ"))
                if (shareBtn != null) {
                    shareBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (!shareBtn.isClickable) shareBtn.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    shareBtn.recycle()
                } else {
                    val menuBtn = allNodes.firstOrNull { 
                        val desc = it.contentDescription?.toString()?.lowercase() ?: ""
                        desc.contains("lựa chọn khác cho bài viết của") || desc.contains("more options for") || desc == "tùy chọn" || desc == "options"
                    }
                    if (menuBtn != null) {
                        menuBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (!menuBtn.isClickable) menuBtn.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        menuBtn.recycle()
                    }
                }
                recycleNodes(allNodes)
            }
            setNextStepDelay(500)
        }
        root.recycle()
    }

    private fun handleWaitingForOpenedPost() {
        val root = rootInActiveWindow ?: return
        val nodes = findAllNodes(root)
        
        // Find the "..." menu button
        var menuBtn: android.view.accessibility.AccessibilityNodeInfo? = null
        for (node in nodes) {
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (desc.contains("lựa chọn khác cho bài viết của") || desc.contains("more options for") || desc == "tùy chọn" || desc == "options") {
                menuBtn = node
                break
            }
        }
        
        if (menuBtn != null) {
            debugLog("Đã mở bài phê duyệt, đang tìm nút Share/Copy...")
            menuBtn.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            if (!menuBtn.isClickable) menuBtn.parent?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            
            // Create a dummy task for submitCopiedLinkToBackend
            currentTask = TaskItem(postId = "APPROVED_POST", url = "", comment = "[PHÊ DUYỆT TRỄ]", isPublishingGroup = true)
            currentStep = Step.CLICKING_SHARE_AND_COPY
            retryCount = 0
            
            setNextStepDelay(2500)
        } else {
            if (retryCount >= 10) {
                debugLog("⚠️ Không tìm thấy menu bài viết. Hủy bỏ quét thông báo phê duyệt.")
                currentStep = Step.IDLE
                retryCount = 0
            }
            setNextStepDelay(1000)
        }
        recycleNodes(nodes)
        root.recycle()
    }

    private fun submitCopiedLinkToBackend(task: TaskItem) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val cd = cm.primaryClip
            if (cd != null && cd.itemCount > 0) {
                val copiedLink = cd.getItemAt(0).text?.toString() ?: ""
                if (copiedLink.contains("facebook.com") || copiedLink.contains("fb.com")) {
                    // Check if it's a pure group link (not a post)
                    val isPureGroup = copiedLink.contains("/groups/") && 
                        !copiedLink.contains("/posts/") && 
                        !copiedLink.contains("/permalink/") && 
                        !copiedLink.contains("/permalink.php") &&
                        !copiedLink.contains("multi_permalinks") &&
                        !copiedLink.contains("story_fbid")
                        
                    if (isPureGroup) {
                        debugLog("⚠️ Bỏ qua link nhóm (không phải bài viết): $copiedLink")
                        Log.w(TAG, "Skipping pure group link: $copiedLink")
                    } else {
                        Log.d(TAG, "Extracted link successfully: $copiedLink")
                        val prefs = getSharedPreferences("comment_helper_prefs", Context.MODE_PRIVATE)
                        val token = prefs.getString("auth_token", "")
                        if (!token.isNullOrBlank()) {
                            Thread {
                                try {
                                    val urlObj = java.net.URL("$SERVER_URL/api/posts/bulk")
                                    val conn = urlObj.openConnection() as java.net.HttpURLConnection
                                    conn.requestMethod = "POST"
                                    conn.setRequestProperty("Authorization", "Bearer $token")
                                    conn.setRequestProperty("Content-Type", "application/json")
                                    conn.doOutput = true
                                    val titleJson = task.comment.substring(0, Math.min(task.comment.length, 30)).replace("\n", " ")
                                    val payload = """{"items": [{"url": "$copiedLink", "title": "[TỰ ĐỘNG] $titleJson..."}]}"""
                                    conn.outputStream.write(payload.toByteArray())
                                    val rc = conn.responseCode
                                    Log.d(TAG, "Bulk submit seeded back: $rc")
                                    if ((rc == 200 || rc == 201) && !task.postId.isNullOrBlank() && task.postId != "APPROVED_POST") {
                                        try {
                                            val delUrl = java.net.URL("$SERVER_URL/api/posts/${task.postId}")
                                            val delConn = delUrl.openConnection() as java.net.HttpURLConnection
                                            delConn.requestMethod = "DELETE"
                                            delConn.setRequestProperty("Authorization", "Bearer $token")
                                            val delRc = delConn.responseCode
                                            Log.d(TAG, "Deleted temporary group post: $delRc")
                                        } catch (de: Exception) {
                                            Log.e(TAG, "Failed to delete temporary group post", de)
                                        }
                                    }
                                } catch (e: Exception) { Log.e(TAG, "C2 submit link failed", e) }
                            }.start()
                        }
                    }
                }
            }
        } catch(e: Exception) {}
        
        if (task.url == "ACTION_SCAN_NOTIFICATIONS") {
            debugLog("Đã lưu link bài phê duyệt. Quay lại check tiếp...")
            performGlobalAction(GLOBAL_ACTION_BACK)
            currentStep = Step.SCANNING_NOTIFICATIONS
            retryCount = 0
            setNextStepDelay(1500)
        } else {
            markCurrentDone(success = true)
        }
    }

    /* ================== NODE FINDERS ================== */

    private enum class ActionTarget {
        LIKE,
        COMMENT_INPUT,
        COMMENT_TRIGGER,
        SEND_COMMENT,
        COMPOSER_DONE,
        PUBLISH_POST
    }

    private data class TargetPostRegion(
        val bounds: Rect,
        val anchorBounds: Rect,
        val confidence: Int
    )

    private data class NodeCandidate(
        val node: AccessibilityNodeInfo,
        val score: Int,
        val reasons: List<String>
    )

    private fun normalizeUiText(value: CharSequence?): String {
        if (value == null) return ""
        return Normalizer.normalize(value.toString(), Normalizer.Form.NFD)
            .replace(UI_DIACRITICS, "")
            .replace('đ', 'd')
            .replace('Đ', 'd')
            .lowercase()
            .replace(UI_NON_ALPHANUMERIC, " ")
            .trim()
    }

    private fun nodeBounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

    private fun nodeFields(node: AccessibilityNodeInfo): List<String> = listOf(
        normalizeUiText(node.text),
        normalizeUiText(node.hintText),
        normalizeUiText(node.contentDescription),
        normalizeUiText(node.viewIdResourceName)
    ).filter { it.isNotBlank() }

    private fun isNodeActionable(node: AccessibilityNodeInfo): Boolean {
        var cursor: AccessibilityNodeInfo? = node
        var depth = 0
        while (cursor != null && depth <= 5) {
            val actionable = cursor.isClickable || cursor.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            val parent = if (actionable) null else cursor.parent
            if (depth > 0) cursor.recycle()
            if (actionable) return true
            cursor = parent
            depth++
        }
        cursor?.recycle()
        return false
    }

    private fun targetFingerprints(task: TaskItem): List<String> {
        val supplied = task.targetPostAnchors.map(::normalizeUiText)
        val textFallback = normalizeUiText(task.targetPostText).let { text ->
            if (text.length >= 12) listOf(text.take(160)) else emptyList()
        }
        return (supplied + textFallback).filter { it.length >= 8 }.distinct().take(6)
    }

    private fun resolveTargetPostRegion(
        root: AccessibilityNodeInfo,
        nodes: List<AccessibilityNodeInfo>
    ): TargetPostRegion? {
        val task = currentTask ?: return null
        if (task.isPublishingGroup) return null
        val fingerprints = targetFingerprints(task)
        val author = normalizeUiText(task.targetPostAuthor)
        if (fingerprints.isEmpty() && author.isBlank()) return null

        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        for (node in nodes) {
            if (!node.isVisibleToUser) continue
            val content = nodeFields(node).joinToString(" ")
            if (content.isBlank()) continue
            var score = 0
            for (anchor in fingerprints) {
                score += when {
                    content == anchor -> 90
                    content.contains(anchor) -> 70
                    anchor.contains(content) && content.length >= 18 -> 35
                    else -> 0
                }
            }
            if (author.isNotBlank()) {
                if (content == author) score += 30
                else if (content.contains(author)) score += 18
            }
            if (score > bestScore) {
                bestNode = node
                bestScore = score
            }
        }
        val anchorNode = bestNode ?: return null
        if (bestScore < 35) return null

        val rootBounds = nodeBounds(root)
        val anchorBounds = nodeBounds(anchorNode)
        var selected: Rect? = null
        var cursor = anchorNode.parent
        while (cursor != null) {
            val bounds = nodeBounds(cursor)
            val minHeight = maxOf(220, anchorBounds.height() + 140)
            val wideEnough = rootBounds.width() <= 0 || bounds.width() >= rootBounds.width() * 0.60
            val notWholeScreen = rootBounds.height() <= 0 || bounds.height() <= rootBounds.height() * 0.88
            if (wideEnough && notWholeScreen && bounds.height() >= minHeight) {
                selected = Rect(bounds)
                cursor.recycle()
                break
            }
            val parent = cursor.parent
            cursor.recycle()
            cursor = parent
        }

        val region = selected ?: Rect(
            rootBounds.left,
            maxOf(rootBounds.top, anchorBounds.top - 220),
            rootBounds.right,
            minOf(rootBounds.bottom, anchorBounds.bottom + 850)
        )
        return TargetPostRegion(region, anchorBounds, bestScore)
    }

    private fun referenceCommentInputBounds(nodes: List<AccessibilityNodeInfo>): Rect? {
        val expected = normalizeUiText(currentTask?.comment)
        val commentTerms = Engine.commentButton.map(::normalizeUiText)
        return nodes.asSequence()
            .filter { it.isVisibleToUser && (it.isEditable || it.className?.toString() == "android.widget.EditText") }
            .map { node ->
                val fields = nodeFields(node)
                val score = when {
                    expected.isNotBlank() && fields.any { it.contains(expected) || expected.contains(it) } -> 100
                    fields.any { field -> commentTerms.any(field::contains) } -> 60
                    else -> 10
                }
                score to nodeBounds(node)
            }
            .maxByOrNull { it.first }
            ?.second
    }

    private fun scoreNode(
        node: AccessibilityNodeInfo,
        target: ActionTarget,
        targetRegion: TargetPostRegion?,
        inputBounds: Rect?,
        targetRequested: Boolean
    ): NodeCandidate {
        val reasons = mutableListOf<String>()
        if (!node.isVisibleToUser) return NodeCandidate(node, -1000, listOf("ẩn"))
        val fields = nodeFields(node)
        val text = normalizeUiText(node.text)
        val hint = normalizeUiText(node.hintText)
        val description = normalizeUiText(node.contentDescription)
        val combined = fields.joinToString(" ")
        val bounds = nodeBounds(node)
        var score = 10
        reasons += "+10 hiển thị"

        if (isNodeActionable(node)) {
            score += 15
            reasons += "+15 click"
        } else if (target != ActionTarget.COMMENT_INPUT) {
            score -= 55
            reasons += "-55 không click được"
        }

        val needsPostScope = target == ActionTarget.LIKE || target == ActionTarget.COMMENT_TRIGGER ||
            (target == ActionTarget.COMMENT_INPUT && !commentEntryOpened)
        if (needsPostScope && targetRegion != null) {
            if (targetRegion.bounds.contains(bounds.centerX(), bounds.centerY())) {
                score += 35
                reasons += "+35 đúng vùng bài"
                if (bounds.top >= targetRegion.anchorBounds.top) {
                    score += 8
                    reasons += "+8 dưới nội dung"
                }
            } else {
                score -= 70
                reasons += "-70 ngoài vùng bài"
            }
        } else if (needsPostScope && targetRequested) {
            score -= 45
            reasons += "-45 chưa nhận diện bài"
        }

        when (target) {
            ActionTarget.LIKE -> {
                val exact = text in listOf("like", "thich", "unlike", "bo thich") ||
                    description in listOf("like", "thich", "unlike", "bo thich")
                if (exact) {
                    score += 50
                    reasons += "+50 Like chính xác"
                } else if (listOf("like", "thich", "unlike", "bo thich").any(combined::contains)) {
                    score += 32
                    reasons += "+32 có từ Like"
                }
                if (combined.contains("thich binh luan") || combined.contains("like comment") ||
                    combined.contains("reaction to comment")) {
                    score -= 160
                    reasons += "-160 Like của bình luận"
                }
            }
            ActionTarget.COMMENT_INPUT -> {
                val editable = node.isEditable || node.className?.toString() == "android.widget.EditText"
                if (editable) {
                    score += 45
                    reasons += "+45 ô nhập"
                } else {
                    score -= 50
                }
                val terms = Engine.commentButton.map(::normalizeUiText)
                if (fields.any { field -> terms.any(field::contains) }) {
                    score += 35
                    reasons += "+35 mô tả bình luận"
                }
            }
            ActionTarget.COMMENT_TRIGGER -> {
                val terms = Engine.commentButton.map(::normalizeUiText)
                if (terms.any { it == text || it == description }) {
                    score += 50
                    reasons += "+50 Bình luận chính xác"
                } else if (fields.any { field -> terms.any(field::contains) }) {
                    score += 30
                    reasons += "+30 có từ Bình luận"
                }
                if (combined.contains("tra loi binh luan") || combined.contains("reply to comment") ||
                    combined.contains("thich binh luan") || combined.contains("like comment")) {
                    score -= 140
                    reasons += "-140 hành động của bình luận"
                }
            }
            ActionTarget.SEND_COMMENT -> {
                val terms = Engine.sendComment.map(::normalizeUiText)
                    .filter { it.isNotBlank() && it !in listOf("tiep", "next", "done", "xong") }
                if (terms.any { it == text || it == description }) {
                    score += 55
                    reasons += "+55 Gửi chính xác"
                    if (!isNodeActionable(node)) {
                        score += 60
                        reasons += "+60 Gửi semantic"
                    }
                } else if (fields.any { field -> terms.any(field::contains) }) {
                    score += 28
                    reasons += "+28 có từ Gửi"
                }
                if (listOf("messenger", "tin nhắn", "tin nhan", "ban be", "chia se", "share to").any(combined::contains)) {
                    score -= 180
                    reasons += "-180 sai ngữ cảnh"
                }
                if (inputBounds != null) {
                    val verticalDistance = abs(bounds.centerY() - inputBounds.centerY())
                    if (verticalDistance <= maxOf(90, inputBounds.height())) {
                        score += 25
                        reasons += "+25 cùng hàng ô nhập"
                    } else if (verticalDistance > inputBounds.height() * 2 + 160) {
                        score -= 45
                        reasons += "-45 xa ô nhập"
                    }
                    if (bounds.centerX() >= inputBounds.centerX()) {
                        score += 12
                        reasons += "+12 bên phải ô nhập"
                    }
                }
            }
            ActionTarget.COMPOSER_DONE -> {
                val terms = listOf("xong", "done")
                if (terms.any { it == text || it == description }) {
                    score += 65
                    reasons += "+65 Xong chính xác"
                } else if (fields.any { field -> terms.any(field::contains) }) {
                    score += 30
                    reasons += "+30 có từ Xong"
                }
                val metrics = resources.displayMetrics
                if (bounds.centerY() < metrics.heightPixels * 0.45f) {
                    score += 10
                    reasons += "+10 vùng trên"
                }
                if (bounds.centerX() > metrics.widthPixels * 0.55f) {
                    score += 8
                    reasons += "+8 bên phải"
                }
            }
            ActionTarget.PUBLISH_POST -> {
                val terms = Engine.postButton.map(::normalizeUiText)
                if (terms.any { it == text || it == description }) {
                    score += 65
                    reasons += "+65 Đăng chính xác"
                } else if (fields.any { field -> terms.any(field::contains) }) {
                    score += 25
                    reasons += "+25 có từ Đăng"
                }
                if (combined.contains("dang cheo") || combined.contains("crosspost") || combined.contains("cross post")) {
                    score -= 180
                    reasons += "-180 Đăng chéo"
                }
                if (node.isEditable) {
                    score -= 120
                    reasons += "-120 ô nhập"
                }
            }
        }
        return NodeCandidate(node, score, reasons)
    }

    private fun findBestCandidate(root: AccessibilityNodeInfo, target: ActionTarget): AccessibilityNodeInfo? {
        val nodes = findAllNodes(root)
        val task = currentTask
        val targetRequested = task != null &&
            (task.targetPostAnchors.isNotEmpty() || task.targetPostText.isNotBlank() || task.targetPostAuthor.isNotBlank())
        val region = resolveTargetPostRegion(root, nodes)
        val inputBounds = if (target == ActionTarget.SEND_COMMENT && currentTask?.isPublishingGroup != true)
            referenceCommentInputBounds(nodes) else null
        val ranked = nodes.map { scoreNode(it, target, region, inputBounds, targetRequested) }
            .sortedByDescending { it.score }
        val threshold = when (target) {
            ActionTarget.LIKE -> 60
            ActionTarget.COMMENT_INPUT -> 65
            ActionTarget.COMMENT_TRIGGER -> 60
            ActionTarget.SEND_COMMENT -> 70
            ActionTarget.COMPOSER_DONE -> 65
            ActionTarget.PUBLISH_POST -> 70
        }
        val best = ranked.firstOrNull()
        if (isDebugMode || retryCount % 5 == 0) {
            val summary = ranked.take(3).joinToString(" | ") { candidate ->
                val label = nodeFields(candidate.node).joinToString(" / ").take(80)
                "${candidate.score}đ '$label' [${candidate.reasons.joinToString()}]"
            }
            debugLog("🎯 SCORE[$target] region=${region?.confidence ?: 0}: $summary")
        }
        val runnerUp = ranked.getOrNull(1)
        val ambiguous = best != null && runnerUp != null && best.score >= threshold &&
            best.score - runnerUp.score < 8 && run {
                val firstBounds = nodeBounds(best.node)
                val secondBounds = nodeBounds(runnerUp.node)
                abs(firstBounds.centerX() - secondBounds.centerX()) + abs(firstBounds.centerY() - secondBounds.centerY()) > 80
            }
        if (ambiguous) debugLog("⚠️ SCORE[$target] có hai ứng viên quá sát điểm; chờ giao diện rõ hơn.")
        val result = best?.takeIf { it.score >= threshold && !ambiguous }
            ?.let { AccessibilityNodeInfo.obtain(it.node) }
        recycleNodes(nodes)
        return result
    }

    private fun findLikeButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findBestCandidate(root, ActionTarget.LIKE)
    }

    private fun isAlreadyLiked(node: AccessibilityNodeInfo): Boolean {
        val state = nodeFields(node).joinToString(" ")
        // If it says "Unlike" or "Bỏ thích", it's already liked
        return state.contains("unlike") || state.contains("bo thich") || node.isSelected
    }

    private fun findCommentInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findBestCandidate(root, ActionTarget.COMMENT_INPUT)
    }

    private fun findCommentPlaceholder(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findBestCandidate(root, ActionTarget.COMMENT_TRIGGER)
    }

    private fun findGroupComposerPlaceholder(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (hint in Engine.composeButton) {
            val nodes = root.findAccessibilityNodeInfosByText(hint)
            for (node in nodes) {
                if (node.isClickable || node.parent?.isClickable == true) {
                    return node
                }
                node.recycle()
            }
        }

        // Heavy Fallback: Manual scan
        val allNodes = findAllNodes(root)
        var resultNode: AccessibilityNodeInfo? = null
        for (node in allNodes) {
            val text = node.text?.toString()?.lowercase() ?: ""
            val cd = node.contentDescription?.toString()?.lowercase() ?: ""
            if (Engine.composeButton.any { text.contains(it) || cd.contains(it) }) {
                if (node.isClickable || node.parent?.isClickable == true) {
                    resultNode = AccessibilityNodeInfo.obtain(node)
                    break
                }
            }
        }
        recycleNodes(allNodes)
        return resultNode
    }

    private fun findGroupComposerInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val nodes = findAllNodes(root)
        var editNode: AccessibilityNodeInfo? = null
        for (node in nodes) {
            if (node.isEditable || 
                node.className?.toString() == "android.widget.EditText" || 
                node.className?.toString() == "android.widget.MultiAutoCompleteTextView") {
                editNode = AccessibilityNodeInfo.obtain(node)
                break
            }
        }
        recycleNodes(nodes)
        return editNode
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findBestCandidate(
            root,
            if (currentTask?.isPublishingGroup == true) ActionTarget.PUBLISH_POST else ActionTarget.SEND_COMMENT
        )
    }

    /* ================== NODE SEARCH UTILS ================== */

    private fun findNodeByClassName(root: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.className?.toString() == className && node.isVisibleToUser) {
                return AccessibilityNodeInfo.obtain(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findNodeByHint(root: AccessibilityNodeInfo, hints: List<String>): AccessibilityNodeInfo? {
        for (hint in hints) {
            val nodes = root.findAccessibilityNodeInfosByText(hint)
            var foundNode: AccessibilityNodeInfo? = null
            for (node in nodes) {
                if (foundNode == null && (node.className?.toString() == "android.widget.EditText" || node.isEditable)) {
                    foundNode = node
                } else {
                    node.recycle()
                }
            }
            if (foundNode != null) return foundNode
        }
        return null
    }

    private fun findNodeByContentDescription(
        root: AccessibilityNodeInfo,
        descriptions: List<String>
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val cd = node.contentDescription?.toString()?.lowercase() ?: ""
            if (descriptions.any { cd.contains(it) } &&
                node.isVisibleToUser &&
                (node.isClickable || node.parent?.isClickable == true)
            ) {
                val result = AccessibilityNodeInfo.obtain(node)
                if (node != root) node.recycle()
                for (qNode in queue) { if (qNode != root) qNode.recycle() }
                return result
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            if (node != root) node.recycle()
        }
        return null
    }

    private fun findNodeByText(
        root: AccessibilityNodeInfo,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        for (txt in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(txt)
            var foundNode: AccessibilityNodeInfo? = null
            for (node in nodes) {
                if (foundNode == null && node.text?.toString()?.equals(txt, true) == true && node.isVisibleToUser && (node.isClickable || node.parent?.isClickable == true)) {
                    foundNode = node
                } else {
                    node.recycle()
                }
            }
            if (foundNode != null) return foundNode
        }
        return null
    }

    private fun findAllNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val rootCopy = AccessibilityNodeInfo.obtain(root)
        if (rootCopy != null) queue.add(rootCopy)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            list.add(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return list
    }

    private fun recycleNodes(nodes: List<AccessibilityNodeInfo>) {
        for (node in nodes) {
            try { node.recycle() } catch (e: Exception) {}
        }
    }

    private fun findAllGalleryImages(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val cd = node.contentDescription?.toString()?.lowercase() ?: ""
            if ((cd.contains("photo") || cd.contains("\u1EA3nh") || cd.contains("ch\u1ECDn")) && 
                node.isVisibleToUser && (node.isClickable || node.isCheckable || node.parent?.isClickable == true)) {
                // OTA-configurable exclusion list - update via server, no APK rebuild
                if (Engine.galleryExclude.none { cd.contains(it) }) {
                    list.add(AccessibilityNodeInfo.obtain(node))
                }
            } else if (node.className?.toString() == "android.widget.CheckBox" && node.isVisibleToUser) {
                list.add(AccessibilityNodeInfo.obtain(node))
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            if (node != root) node.recycle()
        }
        return list
    }

    private fun performClick(node: AccessibilityNodeInfo?): Boolean {
        var temp = node
        while (temp != null) {
            if (temp.isClickable) {
                val success = temp.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (success) return true
            }
            temp = temp.parent
        }
        return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    /* ================== STATE MANAGEMENT ================== */

    private var isMarkingDone = false

    private fun markCurrentDone(
        success: Boolean,
        reasonCode: String = "",
        error: String = "",
        retryable: Boolean = true
    ) {
        if (isMarkingDone) {
            debugLog("⚠️ Bỏ qua markCurrentDone vì đang trong quá trình chuyển bài.")
            return
        }
        isMarkingDone = true
        handler.postDelayed({
            if (isMarkingDone) {
                debugLog("⚠️ Khôi phục isMarkingDone về false do quá thời gian chờ chuyển bài.")
                isMarkingDone = false
            }
        }, 15000)

        val task = currentTask ?: run {
            isMarkingDone = false
            return
        }
        debugLog("🏁 Hoàn thành bài ${task.postId} (Thành công=$success). Index hiện tại: $currentIndex, Tổng hàng đợi: ${taskQueue.value.size}")
        
        if (task.postId == "APPROVED_POST") {
            debugLog("Chuyển sang check thông báo tiếp theo...")
            performGlobalAction(GLOBAL_ACTION_BACK)
            
            // Restore currentTask back to NOTIF_SCAN to prevent infinite BACK loop
            val q = taskQueue.value
            if (currentIndex >= 0 && currentIndex < q.size) {
                currentTask = q[currentIndex]
            }
            
            currentStep = Step.SCANNING_NOTIFICATIONS
            retryCount = 0
            handler.postDelayed({
                isMarkingDone = false
                startRetryChecker()
            }, 1000)
            return
        }

        try {
            val prefs = getSharedPreferences("comment_helper_prefs", Context.MODE_PRIVATE)
            val postsStr = prefs.getString("posts_v1", null)
            if (!postsStr.isNullOrBlank()) {
                val arr = org.json.JSONArray(postsStr)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.getString("id") == task.postId) {
                        o.put("status", if (success) "DONE" else "FAILED")
                        o.put("interactedAt", System.currentTimeMillis())
                        break
                    }
                }
                prefs.edit().putString("posts_v1", arr.toString()).apply()
            }

            if (success && task.reportLegacyCompletion) {
                val token = prefs.getString("auth_token", "")
                if (!token.isNullOrBlank()) {
                    Thread {
                        try {
                            val conn = java.net.URL("$SERVER_URL/api/posts/${task.postId}/done").openConnection() as java.net.HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.setRequestProperty("Authorization", "Bearer $token")
                            conn.setRequestProperty("Content-Type", "application/json")
                            conn.doOutput = true
                            conn.outputStream.write("{}".toByteArray())
                            conn.responseCode
                        } catch(e: Exception) {}
                    }.start()
                }
            }
        } catch(e: Exception) {}

        val completed = progress.value.first + 1
        val total = progress.value.second
        progress.value = completed to total

        // Notify MainActivity about completion via broadcast
        val intent = Intent("com.example.commenthelper.POST_DONE").apply {
            putExtra("postId", task.postId)
            putExtra("success", success)
            if (!success) {
                val inferredCode = reasonCode.ifBlank {
                    when (currentStep) {
                        Step.SEEKING_TARGET_POST -> "TARGET_POST_NOT_FOUND"
                        Step.LOOKING_FOR_LIKE -> "LIKE_BUTTON_NOT_FOUND"
                        Step.LOOKING_FOR_COMMENT_FIELD -> "COMMENT_FIELD_NOT_FOUND"
                        Step.WAITING_FOR_COMMENT_SENT -> "COMMENT_NOT_CONFIRMED"
                        Step.LOOKING_FOR_COMPOSER, Step.WAITING_FOR_COMPOSER_INPUT -> "COMPOSER_NOT_FOUND"
                        Step.SELECTING_PHOTOS, Step.LOOKING_FOR_PHOTO_BUTTON -> "PHOTO_SELECTION_FAILED"
                        Step.WAITING_FOR_POST_TO_UPLOAD -> "POST_UPLOAD_NOT_CONFIRMED"
                        else -> "ACCESSIBILITY_FAILED"
                    }
                }
                putExtra("reasonCode", inferredCode)
                putExtra("error", error.ifBlank { "Không hoàn thành được thao tác ở bước ${currentStep.name}." })
                putExtra("step", currentStep.name)
                putExtra("retryable", retryable)
            }
            setPackage(packageName)
        }
        sendBroadcast(intent)

        currentIndex++
        currentStep = Step.IDLE
        currentTask = null

        if (currentIndex < taskQueue.value.size && !stopRequested.value) {
            debugLog("Đang chuẩn bị sang bài ${currentIndex + 1}/${taskQueue.value.size}...")
            // Kill Facebook completely before next task
            forceStopFacebook()
            
            // Switch back to our app briefly
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            try { startActivity(launchIntent) } catch(e: Exception) {}

            var countdown = 3
            val runnable = object : Runnable {
                override fun run() {
                    if (countdown > 0) {
                        currentStatusText.value = "Đang dọn dẹp FB... Bắt đầu bài tiếp theo sau ${countdown}s"
                        countdown--
                        handler.postDelayed(this, 1000)
                    } else {
                        isMarkingDone = false
                        processNextPost()
                    }
                }
            }
            handler.post(runnable)
        } else {
            forceStopFacebook()
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            try { startActivity(launchIntent) } catch(e: Exception) {}
            
            val finishedNaturally = !stopRequested.value
            resetState()
            
            if (finishedNaturally) {
                val prefs = getSharedPreferences("FB_PREFS", android.content.Context.MODE_PRIVATE)
                val wakeInterval = prefs.getInt("autowake_interval_hours", 1)
                val pubInterval = prefs.getInt("autopublish_interval_minutes", 15)
                debugLog("✅ Đã hoàn thành toàn bộ hàng đợi. Chuyển sang trạng thái RẢNH (IDLE). (Lịch trình tự động tiếp theo: Check bài mới sau $wakeInterval giờ, Đăng nhóm sau $pubInterval phút).")
                onQueueFinished?.invoke()
            }
        }
    }

    private fun resetState() {
        isRunning.value = false
        currentStep = Step.IDLE
        currentTask = null
        currentPostId.value = null
        stopRequested.value = false
        healingCount = 0
        multiSelectClicked = false
        isMarkingDone = false
        processedNotifications.clear()
        resetTargetSearch()
        postUploadStableChecks = 0
        isRetryCheckerRunning = false
        handler.removeCallbacksAndMessages(null)
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released")
            }
        } catch (_: Exception) {}
    }
}
