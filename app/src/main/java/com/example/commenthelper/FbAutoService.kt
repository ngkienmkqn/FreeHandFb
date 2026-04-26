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
        val comment: String
    )

    private val handler = Handler(Looper.getMainLooper())

    // State machine for current post processing
    private enum class Step {
        IDLE,
        WAITING_FOR_FB_LOAD,
        LOOKING_FOR_LIKE,
        LOOKING_FOR_COMMENT_FIELD,
        WAITING_FOR_COMMENT_SENT,
        DONE
    }

    private var currentStep = Step.IDLE
    private var currentTask: TaskItem? = null
    private var currentIndex = 0
    private var retryCount = 0
    private val MAX_RETRIES = 30 // ~15 seconds at 500ms interval
    private val STEP_DELAY = 800L // ms between actions for stability

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
                val intentLite = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    setPackage("com.facebook.lite")
                }
                try {
                    startActivity(intentLite)
                } catch (e2: Exception) {
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
            }
        }, 500) // Small delay after back press
    }

    private fun startRetryChecker() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isRunning.value || currentStep == Step.IDLE || currentStep == Step.DONE) return
                retryCount++
                if (retryCount > MAX_RETRIES) {
                    Log.w(TAG, "Timeout waiting for step: $currentStep")
                    markCurrentDone(success = false)
                    return
                }
                // Actively try to find elements
                when (currentStep) {
                    Step.WAITING_FOR_FB_LOAD -> handleWaitingForLoad()
                    Step.LOOKING_FOR_LIKE -> handleLookingForLike()
                    Step.LOOKING_FOR_COMMENT_FIELD -> handleLookingForCommentField()
                    Step.WAITING_FOR_COMMENT_SENT -> handleWaitingForCommentSent()
                    else -> return
                }
                handler.postDelayed(this, 500)
            }
        }, 1500) // Initial delay to let FB open
    }

    /* ================== STEP HANDLERS ================== */

    private fun handleWaitingForLoad() {
        val root = rootInActiveWindow ?: return
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
        root.recycle()
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
                markCurrentDone(success = true)
            }, 1500)
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
        // Look for EditText nodes (comment input fields)
        return findNodeByClassName(root, "android.widget.EditText")
            ?: findNodeByHint(root, listOf(
                "Write a comment", "Viết bình luận",
                "Write a public comment", "Viết bình luận công khai",
                "Comment", "Bình luận"
            ))
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

    /* ================== STATE MANAGEMENT ================== */

    private fun markCurrentDone(success: Boolean) {
        val task = currentTask ?: return
        Log.d(TAG, "Post ${task.postId} done, success=$success")
        
        if (success) {
            try {
                val prefs = getSharedPreferences("comment_helper_prefs", Context.MODE_PRIVATE)
                val postsStr = prefs.getString("posts_v1", null)
                if (!postsStr.isNullOrBlank()) {
                    val arr = org.json.JSONArray(postsStr)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        if (o.getString("id") == task.postId) {
                            o.put("status", "DONE")
                            o.put("interactedAt", System.currentTimeMillis())
                            break
                        }
                    }
                    prefs.edit().putString("posts_v1", arr.toString()).apply()
                }
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
            } catch(e: Exception) {}
        }

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
