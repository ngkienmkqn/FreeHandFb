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

        /** Reference to the running service instance */
        var instance: FbAutoService? = null
            private set

        fun isServiceEnabled(context: Context): Boolean {
            val enabledServices = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains("${context.packageName}/${FbAutoService::class.java.canonicalName}")
        }
    }

    data class TaskItem(
        val postId: String,
        val url: String,
        val comment: String,
        val isPublishingGroup: Boolean = false,
        val imageCount: Int = 0,
        val isScrapingGroup: Boolean = false
    )

    private val handler = Handler(Looper.getMainLooper())

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
        CLICKING_SHARE_AND_COPY,
        SCRAPING_GROUP_INFO,
        DONE
    }

    private var currentStep = Step.IDLE
    private var currentTask: TaskItem? = null
    private var currentIndex = 0
    private var retryCount = 0
    private val MAX_RETRIES = 40 // Allow 20s for upload to finish
    private val STEP_DELAY = 800L 

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Service connected")
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

        // Process based on current step
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

        taskQueue.value = tasks
        progress.value = 0 to tasks.size
        currentIndex = 0
        stopRequested.value = false
        isRunning.value = true

        processNextPost()
    }

    fun startPublishing(text: String, images: List<String>, groupLinks: List<String>) {
        if (groupLinks.isEmpty()) return
        val count = images.size

        val tasks = groupLinks.mapIndexed { index, link ->
            TaskItem(
                postId = "GROUP_PUB_$index",
                url = link,
                comment = text,
                isPublishingGroup = true,
                imageCount = count
            )
        }

        taskQueue.value = tasks
        progress.value = 0 to tasks.size
        currentIndex = 0
        stopRequested.value = false
        isRunning.value = true

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
        currentStep = Step.WAITING_FOR_FB_LOAD
        retryCount = 0

        Log.d(TAG, "Processing post ${currentIndex + 1}/${tasks.size}: ${task.url}")

        // Clear clipboard first to avoid grabbing old links
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("", ""))

        // Open the FB link
        openFacebookLink(task.url)

        // Start a timeout checker
        startRetryChecker()
    }

    private fun openFacebookLink(url: String) {
        // Press Back first to dismiss any existing FB overlay/dialog
        performGlobalAction(GLOBAL_ACTION_BACK)

        val cleanUrl = url.replace("m.facebook.com", "www.facebook.com")
                          .replace("mbasic.facebook.com", "www.facebook.com")

        handler.postDelayed({
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
                    markCurrentDone(success = false)
                }
            }
        }, 500) // Small delay after back press
    }

    private fun startRetryChecker() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isRunning.value || stopRequested.value || currentStep == Step.IDLE || currentStep == Step.DONE) return

                val root = rootInActiveWindow
                if (root != null) {
                    if (interceptWrongScreen(root)) {
                        retryCount = 0
                        root.recycle()
                        handler.postDelayed(this, 1500)
                        return
                    }
                    if (interceptGroupJoin(root)) {
                        retryCount = 0
                        root.recycle()
                        handler.postDelayed(this, 1500)
                        return
                    }
                    root.recycle()
                }
                
                if (currentStep == Step.WAITING_FOR_FB_LOAD) {
                    if (currentTask?.isScrapingGroup == true) {
                        currentStep = Step.SCRAPING_GROUP_INFO
                        return
                    }
                    if (currentTask?.isPublishingGroup == true) currentStep = Step.LOOKING_FOR_COMPOSER
                    else currentStep = Step.LOOKING_FOR_LIKE
                    return
                }

                retryCount++
                if (retryCount > MAX_RETRIES) {
                    Log.w(TAG, "Timeout waiting for step: $currentStep")
                    // If we timeout on upload, try grabbing anyway
                    if (currentStep == Step.WAITING_FOR_POST_TO_UPLOAD) {
                        currentStep = Step.CLICKING_SHARE_AND_COPY
                        retryCount = 0
                        handleClickingShareAndCopy()
                        handler.postDelayed(this, 500)
                        return
                    }
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
                    Step.CLICKING_SHARE_AND_COPY -> { handleClickingShareAndCopy() }
                    else -> return
                }
                handler.postDelayed(this, 500)
            }
        }, 1500) // Initial delay to let FB open
    }

    /* ================== DOM INTERCEPTOR ================== */

    private fun interceptWrongScreen(root: AccessibilityNodeInfo): Boolean {
        val nodes = findAllNodes(root)
        val isWrongScreen = nodes.any { 
            val txt = it.text?.toString()?.lowercase() ?: ""
            txt.contains("gửi bằng messenger") || txt.contains("gửi trong messenger") || 
            txt.contains("chia sẻ lên tin") || txt.contains("share to story") ||
            txt.contains("gửi cho") || txt.contains("tìm kiếm người") || txt.contains("search people")
        }
        if (isWrongScreen) {
            Log.w(TAG, "Intercepted Wrong Screen (Share/Messenger Sheet). Pressing BACK.")
            performGlobalAction(GLOBAL_ACTION_BACK)
            return true
        }
        return false
    }

    private fun interceptGroupJoin(root: AccessibilityNodeInfo): Boolean {
        var altered = false
        val nodes = findAllNodes(root)

        // 1. Click "Tham gia nhóm" (Join Group)
        val joinBtn = nodes.firstOrNull { 
            val txt = it.text?.toString()?.lowercase() ?: ""
            (txt == "tham gia nhóm" || txt == "join group") && (it.isClickable || it.parent?.isClickable == true)
        }
        if (joinBtn != null) {
            Log.d(TAG, "Intercepted Join Group Request")
            joinBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: joinBtn.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }

        // 2. Look for Questionnaire Items (Only fill if we see Submit buttons or Checkboxes)
        val submitCanditates = listOf("gửi", "đồng ý", "submit", "i agree")
        val submitBtn = nodes.firstOrNull { 
            val txt = it.text?.toString()?.lowercase() ?: ""
            val cd = it.contentDescription?.toString()?.lowercase() ?: ""
            ((submitCanditates.contains(txt)) && (it.isClickable || it.parent?.isClickable == true)) ||
            ((submitCanditates.contains(cd)) && (it.isClickable || it.parent?.isClickable == true))
        }

        val editTexts = nodes.filter { it.className?.toString() == "android.widget.EditText" }
        val checkBoxes = nodes.filter { it.className?.toString() == "android.widget.CheckBox" || it.className?.toString() == "android.widget.RadioButton" }
        
        val isQuestionnaire = nodes.any { 
            val txt = it.text?.toString()?.lowercase() ?: ""
            txt.contains("tham gia nhóm") || txt.contains("câu hỏi") || txt.contains("quy tắc")
        }

        if (editTexts.isNotEmpty() || checkBoxes.isNotEmpty() || (isQuestionnaire && submitBtn != null)) {
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
    private fun interceptDeadLink(root: AccessibilityNodeInfo): Boolean {
        // Detect "This content isn't available right now" / "Bài viết không khả dụng"
        val isDead = findNodeByText(root, listOf(
            "không khả dụng",
            "không tồn tại",
            "đã bị gỡ",
            "content isn't available",
            "content not found"
        )) != null

        if (isDead) {
            Log.w(TAG, "DEAD LINK detected! Aborting interaction to preserve safety.")
            // Mark as done but with success = false (Server will not deduct points safely)
            markCurrentDone(success = false)
            return true
        }
        return false
    }

    /* ================== STEP HANDLERS ================== */

    private fun handleWaitingForLoad() {
        val root = rootInActiveWindow ?: return
        
        // Dead link check takes absolute priority
        if (interceptDeadLink(root)) {
            root.recycle()
            return
        }

        if (currentTask?.isScrapingGroup == true) {
            val hasGroupInfo = findAllNodes(root).any { it.text?.toString()?.contains("thành viên", ignoreCase = true) == true }
            if (hasGroupInfo) currentStep = Step.SCRAPING_GROUP_INFO
            return
        }

        if (currentTask?.isPublishingGroup == true) {
            // Wait for group to load (composer placeholder visible)
            val composer = findGroupComposerPlaceholder(root)
            if (composer != null) {
                Log.d(TAG, "Group loaded — found composer")
                composer.recycle()
                currentStep = Step.LOOKING_FOR_COMPOSER
                retryCount = 0
                handler.postDelayed({ handleLookingForComposer() }, STEP_DELAY)
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
                handler.postDelayed({ handleLookingForLike() }, STEP_DELAY)
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
    }

    private fun handleLookingForLike() {
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
            handler.postDelayed({ handleLookingForCommentField() }, STEP_DELAY)
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
                handler.postDelayed({ handleLookingForCommentField() }, STEP_DELAY)
                return
            }
        }

        if (commentInput != null) {
            Log.d(TAG, "Found comment input, setting text: ${task.comment}")

            // Focus the input
            commentInput.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            commentInput.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            // Set the text
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, task.comment)
            }
            commentInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            commentInput.recycle()

            currentStep = Step.WAITING_FOR_COMMENT_SENT
            retryCount = 0
            // Give time for text to be set, then look for send button
            handler.postDelayed({ findAndClickSend() }, STEP_DELAY)
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
            handler.postDelayed({
                if (task.isPublishingGroup) {
                    // Shift to URL extraction phase
                    currentStep = Step.WAITING_FOR_POST_TO_UPLOAD
                    retryCount = 0
                } else {
                    markCurrentDone(success = true)
                }
            }, 3000)
        } else {
            root.recycle()
            // Retry finding send button
            retryCount++
            if (retryCount < 10) {
                handler.postDelayed({ findAndClickSend() }, 500)
            } else {
                Log.w(TAG, "Send button not found after retries")
                markCurrentDone(success = false)
            }
        }
    }

    private fun handleWaitingForCommentSent() {
        // Handled by findAndClickSend callback
    }

    private fun handleLookingForComposer() {
        val root = rootInActiveWindow ?: return
        val composer = findGroupComposerPlaceholder(root)
        if (composer != null) {
            Log.d(TAG, "Clicking composer placeholder to open input")
            composer.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!composer.isClickable) composer.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            composer.recycle()
            
            currentStep = Step.WAITING_FOR_COMPOSER_INPUT
            retryCount = 0
            handler.postDelayed({ handleWaitingForComposerInput() }, STEP_DELAY)
        } else {
            retryCount++
        }
        root.recycle()
    }

    private fun handleWaitingForComposerInput() {
        val root = rootInActiveWindow ?: return
        val task = currentTask ?: return

        val inputNode = findGroupComposerInput(root)
        if (inputNode != null) {
            Log.d(TAG, "Found composer input, setting text")
            inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, task.comment) }
            inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            inputNode.recycle()

            if (task.imageCount > 0) {
                currentStep = Step.LOOKING_FOR_PHOTO_BUTTON
                retryCount = 0
                handler.postDelayed({ handleLookingForPhotoButton() }, STEP_DELAY)
            } else {
                currentStep = Step.WAITING_FOR_COMMENT_SENT
                retryCount = 0
                handler.postDelayed({ findAndClickSend() }, STEP_DELAY)
            }
        } else {
            retryCount++
        }
        root.recycle()
    }

    private fun handleLookingForPhotoButton() {
        val root = rootInActiveWindow ?: return
        val photoBtn = findNodeByContentDescription(root, listOf("photo", "video", "ảnh", "video"))
            ?: findNodeByHint(root, listOf("photo", "ảnh", "video"))

        if (photoBtn != null) {
            Log.d(TAG, "Found Photo Picker trigger")
            photoBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!photoBtn.isClickable) photoBtn.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            photoBtn.recycle()
            
            currentStep = Step.SELECTING_PHOTOS
            retryCount = 0
            handler.postDelayed({ handleSelectingPhotos() }, 2500) // Gallery load buffer
        } else {
            retryCount++
        }
        root.recycle()
    }

    private fun handleSelectingPhotos() {
        val root = rootInActiveWindow ?: return
        val task = currentTask ?: return
        
        val allImages = findAllGalleryImages(root)
        if (allImages.isNotEmpty()) {
            val count = Math.min(task.imageCount, allImages.size)
            for (i in 0 until count) {
                val node = allImages[i]
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!node.isClickable) node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
            }
            for (i in count until allImages.size) allImages[i].recycle()

            val doneBtn = findNodeByContentDescription(root, listOf("next", "tiếp", "done", "xong"))
                ?: findNodeByText(root, listOf("next", "tiếp", "done", "xong"))

            if (doneBtn != null) {
                doneBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!doneBtn.isClickable) doneBtn.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                doneBtn.recycle()
            } else {
                // If "Next" is missing, we might already be out of gallery if the layout is different?
                Log.w(TAG, "Missing NEXT button, will attempt posting anyway")
            }

            currentStep = Step.WAITING_FOR_COMMENT_SENT
            retryCount = 0
            handler.postDelayed({ findAndClickSend() }, 2000)
        } else {
            retryCount++
            // If we timeout while searching for gallery nodes, we'll organically fail.
        }
        root.recycle()
    }

    private fun handleWaitingForPostToUpload() {
        val root = rootInActiveWindow ?: return
        
        val submittedTexts = listOf("bài viết của bạn đã được gửi", "submitted to admins", "đã gửi", "chờ phê duyệt", "pending")
        if (findNodeByText(root, submittedTexts) != null) {
            Log.d(TAG, "Post requires admin approval. Cannot grab link.")
            markCurrentDone(success = true) 
            root.recycle()
            return
        }

        // 1. Try finding Share button (Public groups)
        val shareBtn = findNodeByContentDescription(root, listOf("share", "chia sẻ"))
            ?: findNodeByText(root, listOf("share", "chia sẻ"))

        if (shareBtn != null) {
            Log.d(TAG, "Found Share button, clicking it...")
            shareBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!shareBtn.isClickable) shareBtn.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            shareBtn.recycle()
            currentStep = Step.CLICKING_SHARE_AND_COPY
            retryCount = 0
            handler.postDelayed({ handleClickingShareAndCopy() }, 1500)
        } else {
            // 2. Fallback for Private Groups (No share button -> Click "..." More options menu)
            // The content description on FB Android usually includes "options" or "tùy chọn"
            val menuBtn = findNodeByContentDescription(root, listOf("post options", "tùy chọn bài viết", "tùy chọn khác", "more options"))
                ?: findNodeByContentDescription(root, listOf("tùy chọn", "options"))

            if (menuBtn != null) {
                Log.d(TAG, "Private group detected (No Share). Found '...' menu, clicking...")
                menuBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!menuBtn.isClickable) menuBtn.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                menuBtn.recycle()
                currentStep = Step.CLICKING_SHARE_AND_COPY
                retryCount = 0
                // Takes slightly longer for the BottomSheet to render from the '...' menu
                handler.postDelayed({ handleClickingShareAndCopy() }, 2000) 
            } else {
                retryCount++
            }
        }
        root.recycle()
    }

    private fun handleClickingShareAndCopy() {
        val root = rootInActiveWindow ?: return
        val task = currentTask ?: return

        // Wait to find "Copy link" (Sao chép liên kết)
        val copyBtn = findNodeByContentDescription(root, listOf("copy link", "sao chép liên kết", "sao chép"))
            ?: findNodeByText(root, listOf("copy link", "sao chép liên kết", "sao chép"))

        if (copyBtn != null) {
            copyBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!copyBtn.isClickable) copyBtn.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            copyBtn.recycle()
            
            // Wait 1.5s for clipboard to populate, then send to API and proceed
            handler.postDelayed({
                submitCopiedLinkToBackend(task)
            }, 1500)
        } else {
            retryCount++
        }
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
        
        markCurrentDone(success = true)
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
        val hints = listOf(
            "write a comment", "viết bình luận",
            "write a public comment", "viết bình luận công khai",
            "comment", "bình luận", "trả lời", "reply"
        )
        val editTexts = findAllNodes(root).filter { it.className?.toString() == "android.widget.EditText" }
        for (et in editTexts) {
            val hintText = et.hintText?.toString()?.lowercase() ?: ""
            val txt = et.text?.toString()?.lowercase() ?: ""
            val cd = et.contentDescription?.toString()?.lowercase() ?: ""
            val combined = "$hintText $txt $cd"
            if (hints.any { combined.contains(it) }) {
                return AccessibilityNodeInfo.obtain(et)
            }
        }
        return null
    }

    private fun findCommentPlaceholder(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val hints = listOf(
            "Write a comment", "Viết bình luận",
            "Write a public comment", "Viết bình luận công khai",
            "Comment", "Bình luận"
        )
        for (hint in hints) {
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
        val hints = listOf("Write something...", "Write a public post...", "Viết gì đó...", "Bài viết mới...")
        for (hint in hints) {
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

    private fun findGroupComposerInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findNodeByClassName(root, "android.widget.EditText")
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Look for send/submit button
        val sendTexts = listOf("Send", "Gửi", "Submit", "Đăng", "Post")
        for (text in sendTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                val cd = node.contentDescription?.toString()?.lowercase() ?: ""
                val t = node.text?.toString() ?: ""
                
                if (cd.contains("messenger") || cd.contains("tin nhắn") || cd.contains("bạn bè")) {
                    node.recycle()
                    continue
                }
                
                if (cd.contains("send") || cd.contains("gửi") ||
                    cd.contains("submit") || cd.contains("đăng") ||
                    cd.contains("post") ||
                    t.equals(text, ignoreCase = true)
                ) {
                    if (node.isClickable || node.parent?.isClickable == true) {
                        return node
                    }
                }
                node.recycle()
            }
        }

        // Fallback: look for ImageButton or ImageView with send-like description
        return findNodeByContentDescription(root, listOf("send", "gửi", "submit"))
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
            for (node in nodes) {
                if (node.className?.toString() == "android.widget.EditText" ||
                    node.isEditable
                ) {
                    return node
                }
                node.recycle()
            }
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
                return AccessibilityNodeInfo.obtain(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findNodeByText(
        root: AccessibilityNodeInfo,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        for (txt in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(txt)
            for (node in nodes) {
                if (node.text?.toString()?.equals(txt, true) == true && node.isVisibleToUser) {
                    if (node.isClickable || node.parent?.isClickable == true) {
                        return node
                    }
                }
                node.recycle()
            }
        }
        return null
    }

    private fun findAllNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            list.add(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return list
    }

    private fun findAllGalleryImages(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val cd = node.contentDescription?.toString()?.lowercase() ?: ""
            if ((cd.contains("photo") || cd.contains("ảnh") || cd.contains("chọn")) && 
                node.isVisibleToUser && (node.isClickable || node.isCheckable || node.parent?.isClickable == true)) {
                if (!cd.contains("take") && !cd.contains("chụp") && !cd.contains("camera")) {
                    list.add(AccessibilityNodeInfo.obtain(node))
                }
            } else if (node.className?.toString() == "android.widget.CheckBox" && node.isVisibleToUser) {
                list.add(AccessibilityNodeInfo.obtain(node))
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return list
    }

    /* ================== STATE MANAGEMENT ================== */

    private fun markCurrentDone(success: Boolean) {
        val task = currentTask ?: return
        Log.d(TAG, "Post ${task.postId} done, success=$success")
        
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
            // Switch back to our app briefly, then open next post
            handler.postDelayed({
                processNextPost()
            }, 2000) // Wait 2s between posts for stability
        } else {
            resetState()
        }
    }

    private fun resetState() {
        isRunning.value = false
        currentStep = Step.IDLE
        currentTask = null
        currentPostId.value = null
        stopRequested.value = false
        handler.removeCallbacksAndMessages(null)
    }
}
