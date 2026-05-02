package com.example.commenthelper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow

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

        fun load(context: Context) {
            try {
                val script = context.getSharedPreferences("comment_helper_prefs", Context.MODE_PRIVATE).getString("engine_script", "{}") ?: "{}"
                val j = org.json.JSONObject(script).optJSONObject("anchors") ?: return
                
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
                galleryClickDelay = j.optLong("gallery_click_delay", galleryClickDelay)
                // Allow local override from Settings UI
                val localDelay = context.getSharedPreferences("comment_helper_prefs", Context.MODE_PRIVATE).getLong("local_gallery_delay", 0L)
                if (localDelay > 0) galleryClickDelay = localDelay
                Log.d(TAG, "OTA Engine loaded. Version: ${org.json.JSONObject(script).optString("version", "?")}. GalleryDelay: ${galleryClickDelay}ms")
            } catch(e: Exception) { Log.e(TAG, "Failed loading OTA script", e) }
        }
    }

    data class TaskItem(
        val postId: String,
        val url: String,
        val comment: String,
        val isPublishingGroup: Boolean = false,
        val imageCount: Int = 0,
        val isScrapingGroup: Boolean = false,
        val postIndex: Int = 0
    )

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private enum class Step {
        IDLE,
        WAITING_FOR_FB_LOAD,
        LOOKING_FOR_LIKE,
        LOOKING_FOR_COMMENT_FIELD,
        WAITING_FOR_COMMENT_SENT,
        LOOKING_FOR_COMPOSER, 
        WAITING_FOR_COMPOSER_INPUT, 
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
            Step.LOOKING_FOR_LIKE -> "Đang tìm kiếm nút Thích..."
            Step.LOOKING_FOR_COMMENT_FIELD -> "Đang tìm kiếm ô nhập Bình luận..."
            Step.WAITING_FOR_COMMENT_SENT -> "Đang gửi bình luận..."
            Step.LOOKING_FOR_COMPOSER -> "Đang chuẩn bị viết bài mới..."
            Step.WAITING_FOR_COMPOSER_INPUT -> "Đang nhập nội dung bài viết..."
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
        get() = if (currentStep == Step.WAITING_FOR_POST_TO_UPLOAD || currentStep == Step.WAITING_FOR_COMMENT_SENT) 80 else 40 // 40s for upload, 20s for other steps
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
            txt.contains("tiếp") || cd.contains("tiếp") || txt.contains("chọn nhiều") || cd.contains("chọn nhiều")
        }
        if (hasGalleryUI) result = ScreenType.GALLERY

        // 2. Composer check (Tạo bài viết, Bạn đang nghĩ gì, or Thêm ảnh)
        if (result == ScreenType.UNKNOWN) {
            val hasComposerUI = nodes.any {
                val txt = it.text?.toString()?.lowercase() ?: ""
                val cd = it.contentDescription?.toString()?.lowercase() ?: ""
                Engine.composeButton.any { cb -> txt.contains(cb) || cd.contains(cb) } ||
                Engine.photoButton.any { pb -> txt.contains(pb) || cd.contains(pb) }
            }
            if (hasComposerUI) result = ScreenType.COMPOSER
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
            Step.LOOKING_FOR_LIKE -> handleLookingForLike()
            Step.LOOKING_FOR_COMMENT_FIELD -> handleLookingForCommentField()
            Step.WAITING_FOR_COMMENT_SENT -> findAndClickSend()
            Step.LOOKING_FOR_COMPOSER -> handleLookingForComposer()
            Step.WAITING_FOR_COMPOSER_INPUT -> handleWaitingForComposerInput()
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

    fun startProcessing(tasks: List<TaskItem>) {
        if (tasks.isEmpty()) return
        if (isRunning.value) {
            Log.w(TAG, "⚠️ LOCK: Đang chạy task khác, từ chối startProcessing mới. Hãy đợi task cũ hoàn thành.")
            return
        }
        val finalTasks = tasks.toMutableList()
        finalTasks.add(TaskItem("NOTIF_SCAN", "ACTION_SCAN_NOTIFICATIONS", ""))
        
        taskQueue.value = finalTasks
        progress.value = 0 to finalTasks.size
        currentIndex = 0
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

    fun startPublishing(tasks: List<TaskItem>) {
        if (tasks.isEmpty()) return
        if (isRunning.value) {
            Log.w(TAG, "⚠️ LOCK: Đang chạy task khác, từ chối startPublishing mới. Hãy đợi task cũ hoàn thành.")
            return
        }
        val finalTasks = tasks.toMutableList()
        finalTasks.add(TaskItem("NOTIF_SCAN", "ACTION_SCAN_NOTIFICATIONS", ""))

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
        
        currentStep = Step.WAITING_FOR_FB_LOAD
        retryCount = 0
        healingCount = 0
        multiSelectClicked = false

        Log.d(TAG, "Processing post ${currentIndex + 1}/${tasks.size}: ${task.url}")
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
        val cleanUrl = url.replace("m.facebook.com", "www.facebook.com")
                          .replace("mbasic.facebook.com", "www.facebook.com")

        val intentKatana = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).apply {
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
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
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
                markCurrentDone(success = false)
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
                    markCurrentDone(success = false)
                    return
                }
                // Actively try to find elements
                when (currentStep) {
                    Step.WAITING_FOR_FB_LOAD -> handleWaitingForLoad()
                    Step.LOOKING_FOR_LIKE -> handleLookingForLike()
                    Step.LOOKING_FOR_COMMENT_FIELD -> handleLookingForCommentField()
                    Step.WAITING_FOR_COMMENT_SENT -> handleWaitingForCommentSent()
                    Step.LOOKING_FOR_COMPOSER -> handleLookingForComposer()
                    Step.WAITING_FOR_COMPOSER_INPUT -> handleWaitingForComposerInput()
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
            
            markCurrentDone(success = false)
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
            Log.w(TAG, "DEAD LINK detected! Aborting interaction to preserve safety.")
            debugLog("❌ LỖI NGHIÊM TRỌNG: Phát hiện bài viết đã bị xóa hoặc nhóm bị khóa ('Nội dung này hiện không hiển thị'). Đang tiến hành xóa vĩnh viễn bài đăng khỏi hệ thống để bảo vệ các máy khác...")
            val currentPostId = currentTask?.postId
            if (!currentPostId.isNullOrEmpty()) {
                onPostDead?.invoke(currentPostId)
            }
            // Mark as done locally to clear from queue
            markCurrentDone(success = false)
            return true
        }
        return false
    }

    /* ================== STEP HANDLERS ================== */

    private fun handleWaitingForLoad() {
        debugLog("Đang chờ Facebook tải xong...")
        val root = rootInActiveWindow ?: return
        
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
                Log.d(TAG, "FB loaded — found interactive elements")
                likeNode?.recycle()
                commentArea?.recycle()
                currentStep = Step.LOOKING_FOR_LIKE
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
                    debugLog("⚠️ Link bài viết đã redirect về trang Group (bài bị xoá hoặc không tồn tại). Bỏ qua!")
                    val currentPostId = currentTask?.postId
                    if (!currentPostId.isNullOrEmpty()) {
                        onPostDead?.invoke(currentPostId)
                    }
                    markCurrentDone(success = false)
                    root.recycle()
                    return
                }
            }
        }
        root.recycle()
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

        val likeNode = findLikeButton(root)
        if (likeNode != null) {
            // Check if already liked
            if (isAlreadyLiked(likeNode)) {
                Log.d(TAG, "Already liked, skipping")
                likeNode.recycle()
            } else {
                Log.d(TAG, "Clicking Like button")
                likeNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                // Also try clicking parent if the node itself is not clickable
                if (!likeNode.isClickable) {
                    likeNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                likeNode.recycle()
            }
            // Move to comment step
            currentStep = Step.LOOKING_FOR_COMMENT_FIELD
            retryCount = 0
            setNextStepDelay(STEP_DELAY)
        } else {
            // No like button found — maybe already liked or different layout
            // Skip to comment
            Log.d(TAG, "Like button not found, proceeding to comment")
            currentStep = Step.LOOKING_FOR_COMMENT_FIELD
            retryCount = 0
        }
        root.recycle()
    }

    private fun handleLookingForCommentField() {
        debugLog("Đang tìm ô Bình luận...")
        val root = rootInActiveWindow ?: return
        val task = currentTask ?: return

        // First try: find existing comment input that's already visible
        var commentInput = findCommentInput(root)

        if (commentInput == null) {
            // Try clicking on "Write a comment" placeholder to open the input
            val commentPlaceholder = findCommentPlaceholder(root)
            if (commentPlaceholder != null) {
                Log.d(TAG, "Clicking comment placeholder to open input")
                commentPlaceholder.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!commentPlaceholder.isClickable) {
                    commentPlaceholder.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                commentPlaceholder.recycle()
                root.recycle()
                // Wait and retry
                retryCount = 0
                setNextStepDelay(STEP_DELAY)
                return
            }
        }

        if (commentInput != null) {
            Log.d(TAG, "Found comment input, setting text: ${task.comment}")

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
        }
        root.recycle()
    }

    private fun findAndClickSend() {
        val root = rootInActiveWindow ?: return
        val task = currentTask ?: return

        val sendButton = findSendButton(root)
        if (sendButton != null) {
            Log.d(TAG, "Clicking Send button")
            sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!sendButton.isClickable) {
                sendButton.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
                    markCurrentDone(success = true)
                }, 3000)
            }
        } else {
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
            Log.d(TAG, "Clicking composer placeholder to open input")
            composer.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!composer.isClickable) composer.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            composer.recycle()
            
            currentStep = Step.WAITING_FOR_COMPOSER_INPUT
            retryCount = 0
            setNextStepDelay(STEP_DELAY)
        } else {
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
            Log.d(TAG, "Found composer input, setting text")
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
                currentStep = Step.WAITING_FOR_COMMENT_SENT
                retryCount = 0
                setNextStepDelay(STEP_DELAY)
            }
        } else {
            setNextStepDelay(500)
        }
        root.recycle()
    }

    private fun handleLookingForPhotoButton() {
        debugLog("Đang tìm nút Thêm Ảnh...")
        val root = rootInActiveWindow ?: return
        val photoBtn = findNodeByContentDescription(root, Engine.photoButton)
            ?: findNodeByHint(root, Engine.photoButton)

        if (photoBtn != null) {
            Log.d(TAG, "Found Photo Picker trigger")
            photoBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!photoBtn.isClickable) photoBtn.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            photoBtn.recycle()
            
            currentStep = Step.SELECTING_PHOTOS
            retryCount = 0
            setNextStepDelay(2500) // Gallery load buffer
        } else {
            // Wait, we don't increment retryCount here because startRetryChecker does it!
            if (retryCount == 10) {
                debugLog("⚠️ Đang thử đóng bàn phím để tìm nút ảnh...")
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            setNextStepDelay(500)
        }
        root.recycle()
    }

    private var multiSelectClicked = false

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
        if (isDebugMode || alwaysToast) {
            try { handler.post { android.widget.Toast.makeText(this, "🐢 $msg", android.widget.Toast.LENGTH_SHORT).show() } } catch(_: Exception) {}
        }
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
            debugLog("📸 Tìm thấy ${allImages.size} ảnh, chọn $count...")
            Log.d(TAG, "Found ${allImages.size} gallery images, selecting $count")
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
                
                currentStep = Step.WAITING_FOR_COMMENT_SENT
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

            if (retryCount >= 30) {
                debugLog("❌ Không tìm được ảnh, đăng text!")
                Log.w(TAG, "Gallery stuck $retryCount retries. Posting text only.")
                multiSelectClicked = false
                performGlobalAction(GLOBAL_ACTION_BACK)
                currentStep = Step.WAITING_FOR_COMMENT_SENT
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
            
            if ((fullText.contains("chưa đọc") || fullText.contains("unread")) &&
                (fullText.contains("phê duyệt") || fullText.contains("approved"))) {
                targetNode = node
                break
            }
        }
        
        if (targetNode != null) {
            debugLog("Phát hiện thông báo Phê duyệt chưa đọc. Đang click...")
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
                if (copiedLink.contains("facebook.com")) {
                    Log.d(TAG, "Extracted link successfully: $copiedLink")
                    val prefs = getSharedPreferences("comment_helper_prefs", Context.MODE_PRIVATE)
                    val token = prefs.getString("auth_token", "")
                    if (!token.isNullOrBlank()) {
                        Thread {
                            try {
                                val urlObj = java.net.URL("http://dt.ungthien.com/api/posts/bulk")
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
                            } catch (e: Exception) { Log.e(TAG, "C2 submit link failed", e) }
                        }.start()
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

    private fun findLikeButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Strategy 1: Find by content description containing "Like" (most reliable)
        val likeDescriptions = listOf("Like", "Thích", "like", "thích")
        for (desc in likeDescriptions) {
            val nodes = root.findAccessibilityNodeInfosByText(desc)
            for (node in nodes) {
                val cd = node.contentDescription?.toString()?.lowercase() ?: ""
                val text = node.text?.toString()?.lowercase() ?: ""
                // Match "Like" but not "Unlike" / "Bỏ thích"
                if ((cd.contains("like") && !cd.contains("unlike")) ||
                    (cd.contains("thích") && !cd.contains("bỏ thích")) ||
                    (text == "like" || text == "thích")
                ) {
                    return node
                }
                node.recycle()
            }
        }
        return null
    }

    private fun isAlreadyLiked(node: AccessibilityNodeInfo): Boolean {
        val cd = node.contentDescription?.toString()?.lowercase() ?: ""
        // If it says "Unlike" or "Bỏ thích", it's already liked
        return cd.contains("unlike") || cd.contains("bỏ thích") || node.isSelected
    }

    private fun findCommentInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val editTexts = findAllNodes(root).filter { it.className?.toString() == "android.widget.EditText" }
        for (et in editTexts) {
            val hintText = et.hintText?.toString()?.lowercase() ?: ""
            val txt = et.text?.toString()?.lowercase() ?: ""
            val cd = et.contentDescription?.toString()?.lowercase() ?: ""
            val combined = "$hintText $txt $cd"
            if (Engine.commentButton.any { combined.contains(it) }) {
                return AccessibilityNodeInfo.obtain(et)
            }
        }
        return null
    }

    private fun findCommentPlaceholder(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (hint in Engine.commentButton) {
            val nodes = root.findAccessibilityNodeInfosByText(hint)
            for (node in nodes) {
                if (node.isClickable || node.parent?.isClickable == true) {
                    return node
                }
                node.recycle()
            }
        }
        return null
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
        
        if (editNode == null) {
            // Fallback: look for common composer hints dynamically synced from server
            for (node in nodes) {
                val text = node.text?.toString()?.lowercase() ?: ""
                val cd = node.contentDescription?.toString()?.lowercase() ?: ""
                if (Engine.composeButton.any { text.contains(it) || cd.contains(it) }) {
                    editNode = AccessibilityNodeInfo.obtain(node)
                    break
                }
            }
        }
        recycleNodes(nodes)
        return editNode
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Look for send/submit button
        for (text in Engine.sendComment) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                val cd = node.contentDescription?.toString()?.lowercase() ?: ""
                val t = node.text?.toString()?.lowercase() ?: ""
                
                if (cd.contains("messenger") || cd.contains("tin nhắn") || cd.contains("bạn bè")) {
                    node.recycle()
                    continue
                }
                
                if (Engine.sendComment.any { cd.contains(it) } || t.contains(text)) {
                    if (node.isClickable || node.parent?.isClickable == true) {
                        return node
                    }
                }
                node.recycle()
            }
        }

        // Fallback: look for ImageButton or ImageView with send-like description
        return findNodeByContentDescription(root, Engine.sendComment)
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

    /* ================== STATE MANAGEMENT ================== */

    private fun markCurrentDone(success: Boolean) {
        val task = currentTask ?: return
        Log.d(TAG, "Post ${task.postId} done, success=$success")
        
        if (task.postId == "APPROVED_POST") {
            Log.d(TAG, "Approved post processed. Moving to next notification.")
            performGlobalAction(GLOBAL_ACTION_BACK)
            currentStep = Step.SCANNING_NOTIFICATIONS
            retryCount = 0
            handler.postDelayed({
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

            if (success) {
                val token = prefs.getString("auth_token", "")
                if (!token.isNullOrBlank()) {
                    Thread {
                        try {
                            val conn = java.net.URL("http://dt.ungthien.com/api/posts/${task.postId}/done").openConnection() as java.net.HttpURLConnection
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
            setPackage(packageName)
        }
        sendBroadcast(intent)

        currentIndex++
        currentStep = Step.IDLE
        currentTask = null

        if (currentIndex < taskQueue.value.size && !stopRequested.value) {
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
        handler.removeCallbacksAndMessages(null)
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released")
            }
        } catch (_: Exception) {}
    }
}
