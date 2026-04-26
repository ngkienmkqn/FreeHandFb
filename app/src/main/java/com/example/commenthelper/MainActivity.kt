package com.example.commenthelper

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import coil.compose.AsyncImage

private const val PREFS = "comment_helper_prefs"
private const val KEY_POSTS = "posts_v1"
private const val KEY_TEMPLATES = "templates"
private const val KEY_AUTH_TOKEN = "auth_token"
private const val KEY_USERNAME = "username"
private const val KEY_GROUP = "user_group"
private const val SERVER_URL = "http://dt.ungthien.com"
private const val APP_VERSION = "1.0.0"

/* ================== DATA MODEL ================== */

enum class PostStatus { PENDING, DONE }

data class Post(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String? = null,
    val status: PostStatus = PostStatus.PENDING,
    val addedAt: Long = System.currentTimeMillis(),
    val interactedAt: Long? = null,
    val note: String? = null,
    val addedBy: String? = null
)

data class Article(
    val id: String,
    val category: String,
    val title: String,
    val content: String,
    val images: List<String>
)

/* ================== STORAGE ================== */

private fun postsToJson(posts: List<Post>): String {
    val arr = JSONArray()
    posts.forEach { p ->
        arr.put(JSONObject().apply {
            put("id", p.id); put("url", p.url)
            put("title", p.title ?: JSONObject.NULL); put("status", p.status.name)
            put("addedAt", p.addedAt); put("interactedAt", p.interactedAt ?: JSONObject.NULL)
        })
    }
    return arr.toString()
}

private fun postsFromJson(raw: String?): List<Post> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(raw)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Post(
                id = o.getString("id"), url = o.getString("url"),
                title = if (o.isNull("title")) null else o.getString("title"),
                status = PostStatus.valueOf(o.optString("status", "PENDING")),
                addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                interactedAt = if (o.has("interactedAt") && !o.isNull("interactedAt")) o.getLong("interactedAt") else null,
                addedBy = if (o.has("addedBy") && !o.isNull("addedBy")) o.getString("addedBy") else null
            )
        }
    } catch (e: Exception) { emptyList() }
}

private fun loadPosts(prefs: SharedPreferences) = postsFromJson(prefs.getString(KEY_POSTS, null))
private fun savePosts(prefs: SharedPreferences, posts: List<Post>) { prefs.edit().putString(KEY_POSTS, postsToJson(posts)).apply() }
private fun loadTemplates(prefs: SharedPreferences) = prefs.getStringSet(KEY_TEMPLATES, emptySet())?.toList()?.sorted() ?: emptyList()
private fun saveTemplates(prefs: SharedPreferences, list: List<String>) { prefs.edit().putStringSet(KEY_TEMPLATES, list.toSet()).apply() }

/* ================== HTTP HELPERS ================== */

private suspend fun httpReq(url: String, method: String = "GET", json: String? = null, token: String? = null): Pair<Int, String?> = withContext(Dispatchers.IO) {
    try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8000; conn.readTimeout = 8000
        conn.setRequestProperty("Content-Type", "application/json")
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        if (json != null) { conn.doOutput = true; OutputStreamWriter(conn.outputStream).use { it.write(json) } }
        val code = conn.responseCode
        val body = try { BufferedReader(InputStreamReader(if (code in 200..299) conn.inputStream else conn.errorStream)).use { it.readText() } } catch (e: Exception) { null }
        code to body
    } catch (e: Exception) { -1 to e.message }
}

private fun parseServerPosts(json: String): List<Post> {
    return try {
        val arr = JSONArray(json)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Post(
                id = o.getString("id"), url = o.getString("url"),
                title = if (o.isNull("title")) null else o.optString("title"),
                status = PostStatus.valueOf(o.optString("status", "PENDING")),
                addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                interactedAt = if (o.has("interactedAt") && !o.isNull("interactedAt")) o.getLong("interactedAt") else null,
                addedBy = if (o.has("addedBy") && !o.isNull("addedBy")) o.getString("addedBy") else null
            )
        }
    } catch (e: Exception) { emptyList() }
}

private fun parseServerTemplates(json: String): List<String> {
    return try { val arr = JSONArray(json); List(arr.length()) { arr.getString(it) } } catch (e: Exception) { emptyList() }
}

private fun parseServerArticles(json: String): List<Article> {
    return try {
        val arr = JSONArray(json)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val imgArr = o.optJSONArray("images")
            val imgs = mutableListOf<String>()
            if (imgArr != null) { for (j in 0 until imgArr.length()) imgs.add(imgArr.getString(j)) }
            Article(
                id = o.getString("id"), category = o.getString("category"),
                title = o.getString("title"), content = o.getString("content"),
                images = imgs
            )
        }
    } catch (e: Exception) { emptyList() }
}

/* ================== BROADCAST RECEIVER ================== */

class PostDoneReceiver : BroadcastReceiver() {
    companion object { var onPostDone: ((postId: String, success: Boolean) -> Unit)? = null }
    override fun onReceive(context: Context?, intent: Intent?) {
        val postId = intent?.getStringExtra("postId") ?: return
        onPostDone?.invoke(postId, intent.getBooleanExtra("success", false))
    }
}

/* ================== ACTIVITY ================== */

class MainActivity : ComponentActivity() {
    private val localReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val postId = intent?.getStringExtra("postId") ?: return
            PostDoneReceiver.onPostDone?.invoke(postId, intent.getBooleanExtra("success", false))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val log = throwable.stackTraceToString()
            Thread {
                try {
                    val conn = URL("$SERVER_URL/api/logs/apk").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    java.io.OutputStreamWriter(conn.outputStream).use { 
                        it.write(JSONObject().put("log", log).toString()) 
                    }
                    conn.responseCode
                } catch (e: Exception) {}
            }.start()
            Thread.sleep(1000)
            oldHandler?.uncaughtException(thread, throwable)
        }

        val incomingUrl: String? = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.let { extractFirstUrl(it) }

        val filter = IntentFilter("com.example.commenthelper.POST_DONE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(localReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(localReceiver, filter)

        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { AppRoot(initialUrl = incomingUrl) } } }
    }

    override fun onDestroy() { super.onDestroy(); try { unregisterReceiver(localReceiver) } catch (_: Exception) {} }
}

/* ================== UI ROOT ================== */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(initialUrl: String?) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    // Auth state
    var authToken by remember { mutableStateOf(prefs.getString(KEY_AUTH_TOKEN, "") ?: "") }
    var username by remember { mutableStateOf(prefs.getString(KEY_USERNAME, "") ?: "") }
    var userGroup by remember { mutableStateOf(prefs.getString(KEY_GROUP, "") ?: "") }
    var isLoggedIn by remember { mutableStateOf(authToken.isNotBlank()) }

    // If logged in, verify token on launch
    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn && authToken.isNotBlank()) {
            val (code, _) = httpReq("$SERVER_URL/api/me", token = authToken)
            if (code == 401) {
                isLoggedIn = false
                authToken = ""
                prefs.edit().remove(KEY_AUTH_TOKEN).apply()
            }
        }
    }

    if (!isLoggedIn) {
        LoginScreen(
            onLogin = { user, token, group ->
                username = user; authToken = token; userGroup = group
                prefs.edit()
                    .putString(KEY_AUTH_TOKEN, token)
                    .putString(KEY_USERNAME, user)
                    .putString(KEY_GROUP, group)
                    .apply()
                isLoggedIn = true
            }
        )
    } else {
        MainApp(
            prefs = prefs,
            authToken = authToken,
            username = username,
            userGroup = userGroup,
            initialUrl = initialUrl,
            onLogout = {
                scope.launch { httpReq("$SERVER_URL/api/logout", "POST", token = authToken) }
                authToken = ""; username = ""; userGroup = ""
                prefs.edit().remove(KEY_AUTH_TOKEN).remove(KEY_USERNAME).remove(KEY_GROUP).apply()
                isLoggedIn = false
            }
        )
    }
}

/* ================== LOGIN SCREEN ================== */

@Composable
fun LoginScreen(onLogin: (username: String, token: String, group: String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("⚡ FreeHand", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(24.dp))

                OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Tên đăng nhập") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Mật khẩu") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)

                if (error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = Color(0xFFEF4444), style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (user.isBlank() || pass.isBlank()) { error = "Điền đủ thông tin"; return@Button }
                        loading = true; error = ""
                        scope.launch {
                            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: UUID.randomUUID().toString()
                            val (code, body) = httpReq("$SERVER_URL/api/login", "POST", """{"username":"${user.trim()}","password":"$pass","deviceId":"$androidId"}""")
                            loading = false
                            if (code == 200 && body != null) {
                                val json = JSONObject(body)
                                val token = json.getString("token")
                                val u = json.getJSONObject("user")
                                onLogin(u.getString("username"), token, u.getString("group"))
                            } else if (body != null) {
                                try { error = JSONObject(body).getString("error") } catch (_: Exception) { error = "Lỗi: $code" }
                            } else { error = "Không kết nối được server" }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading
                ) {
                    if (loading) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White); Spacer(Modifier.width(8.dp)) }
                    Text("Đăng nhập")
                }
            }
        }
    }
}

/* ================== MAIN APP (after login) ================== */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(
    prefs: SharedPreferences,
    authToken: String,
    username: String,
    userGroup: String,
    initialUrl: String?,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var points by remember { mutableStateOf(0) }
    var role by remember { mutableStateOf("user") }
    
    var notifyInterval by remember { mutableIntStateOf(prefs.getInt("notify_interval", 15)) }
    var lastNotifyTime by remember { mutableLongStateOf(prefs.getLong("last_notify", 0L)) }

    var posts by remember { mutableStateOf(loadPosts(prefs)) }
    var templates by remember { mutableStateOf(loadTemplates(prefs)) }
    var articles by remember { mutableStateOf(emptyList<Article>()) }
    var tab by remember { mutableIntStateOf(0) }
    var isSyncing by remember { mutableStateOf(false) }
    var lastSyncStatus by remember { mutableStateOf("") }

    var isServiceEnabled by remember { mutableStateOf(FbAutoService.isServiceEnabled(context)) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    val isAutoRunning by FbAutoService.isRunning.collectAsState()
    val currentPostId by FbAutoService.currentPostId.collectAsState()
    val autoProgress by FbAutoService.progress.collectAsState()

    // Auto-update check
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateVersion by remember { mutableStateOf("") }
    var updateUrl by remember { mutableStateOf("") }
    var updateChangelog by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val (code, body) = httpReq("$SERVER_URL/api/app-version")
        if (code == 200 && body != null) {
            try {
                val json = JSONObject(body)
                val latest = json.optString("appVersion", "")
                val url = json.optString("apkUrl", "")
                val log = json.optString("changelog", "")
                if (latest.isNotBlank() && latest != APP_VERSION && url.isNotBlank()) {
                    updateVersion = latest; updateUrl = url; updateChangelog = log
                    showUpdateDialog = true
                }
            } catch (_: Exception) {}
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> 
            if (event == Lifecycle.Event.ON_RESUME) {
                isServiceEnabled = FbAutoService.isServiceEnabled(context)
                // When coming back, maybe check notifications
                scope.launch { syncNotifications(authToken, context) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Post completion → mark done + report to server
    DisposableEffect(Unit) {
        PostDoneReceiver.onPostDone = { postId, success ->
            if (success) {
                posts = posts.map { if (it.id == postId) it.copy(status = PostStatus.DONE, interactedAt = System.currentTimeMillis()) else it }
                savePosts(prefs, posts)
                scope.launch { 
                    httpReq("$SERVER_URL/api/posts/$postId/done", "POST", "{}", authToken)
                    // Update points/notifications immediately after interact
                    val (mc, mb) = httpReq("$SERVER_URL/api/me", token = authToken)
                    if (mc == 200 && mb != null) try { points = JSONObject(mb).getInt("points") } catch (_: Exception) {}
                }
            }
        }
        onDispose { PostDoneReceiver.onPostDone = null }
    }

    fun syncWithServer() {
        isSyncing = true; lastSyncStatus = ""
        scope.launch {
            val (mc, mb) = httpReq("$SERVER_URL/api/me", token = authToken)
            if (mc == 200 && mb != null) {
                try { val j = JSONObject(mb); points = j.getInt("points"); role = j.getString("role") } catch (_: Exception) {}
            }

            val (pc, pb) = httpReq("$SERVER_URL/api/posts", token = authToken)
            val (tc, tb) = httpReq("$SERVER_URL/api/templates", token = authToken)
            val (ac, ab) = httpReq("$SERVER_URL/api/articles", token = authToken)

            if (pc == 401) { onLogout(); return@launch }

            if (pc == 200 && pb != null) { posts = parseServerPosts(pb); savePosts(prefs, posts) }
            if (tc == 200 && tb != null) { templates = parseServerTemplates(tb); saveTemplates(prefs, templates) }
            if (ac == 200 && ab != null) { articles = parseServerArticles(ab) }
            
            syncNotifications(authToken, context)

            isSyncing = false
            lastSyncStatus = "Đã đồng bộ lúc ${formatTime(System.currentTimeMillis())}"
        }
    }

    // Auto Sync Loop
    LaunchedEffect(authToken) {
        var lastSyncCheckedText = "0"
        while (true) {
            kotlinx.coroutines.delay(5000)
            if (authToken.isNotEmpty()) {
                val (c, b) = httpReq("$SERVER_URL/api/sync?after=$lastSyncCheckedText", token = authToken)
                if (c == 200 && b != null) {
                    try {
                        val jt = JSONObject(b)
                        if (jt.getBoolean("changed")) {
                            syncWithServer()
                        }
                        if (jt.has("serverTime")) {
                            lastSyncCheckedText = jt.getString("serverTime")
                        } else {
                            lastSyncCheckedText = System.currentTimeMillis().toString()
                        }
                    } catch(_: Exception) {}
                }
            }
        }
    }

    // Auto-sync on first load
    LaunchedEffect(Unit) { syncWithServer() }

    // Auto-Remind Notifications based on config
    LaunchedEffect(posts, notifyInterval) {
        if (notifyInterval > 0) {
            val pendingCount = posts.count { it.status == PostStatus.PENDING && it.addedBy != username }
            if (pendingCount > 0) {
                val now = System.currentTimeMillis()
                if (now - lastNotifyTime >= notifyInterval * 60 * 1000L) {
                    showNotification(context, "Sắp lười rùi! Bạn đang có $pendingCount bài chưa tương tác. Mở FreeHand ngay nhé!")
                    lastNotifyTime = now
                    prefs.edit().putLong("last_notify", now).apply()
                }
            }
        }
    }

    LaunchedEffect(initialUrl) {
        if (!initialUrl.isNullOrBlank()) {
            val (code, body) = httpReq("$SERVER_URL/api/posts", "POST", """{"url":"$initialUrl"}""", authToken)
            if (code == 200) { syncWithServer(); tab = 0; toast(context, "Đã thêm bài.") }
            else if (code == 429 && body != null) { try { toast(context, JSONObject(body).getString("error")) } catch (_: Exception) {} }
        }
    }

    LaunchedEffect(isServiceEnabled) { if (!isServiceEnabled) showPermissionDialog = true }

    Scaffold(
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = { Text("FreeHand") },
                    actions = {
                        TextButton(onClick = onLogout) { Text("Đăng xuất", color = Color(0xFFEF4444)) }
                    }
                )
                // User info bar
                Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("👤 $username", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(12.dp))
                        Surface(color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.small) {
                            Text(userGroup, color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text("⭐️ $points điểm", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            if (lastSyncStatus.isNotBlank()) Text(lastSyncStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Bài viết") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Comments") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Bài Mẫu") })
                    Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Thành viên") })
                    Tab(selected = tab == 4, onClick = { tab = 4 }, text = { Text("⚙️") })
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> PostsScreen(posts, templates, isServiceEnabled, isAutoRunning, currentPostId, autoProgress,
                    authToken = authToken,
                    currentUserRole = role,
                    currentUsername = username,
                    onRefresh = { syncWithServer() },
                    onRequestPermission = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    onStartAuto = {
                        val pendingPosts = posts.filter { it.status == PostStatus.PENDING && it.addedBy != username }
                        if (pendingPosts.isEmpty()) { 
                            toast(context, "Không có bài chưa làm.")
                        } else if (templates.isEmpty()) { 
                            toast(context, "Đang tải Comments mặc định, chờ chút.")
                        } else {
                            FbAutoService.instance?.startProcessing(pendingPosts.map { p -> FbAutoService.TaskItem(p.id, p.url, templates.random()) })
                            FbAutoService.isRunning.value = true
                        }
                    },
                    onStopAuto = { FbAutoService.instance?.stopProcessing() }
                )
                1 -> TemplatesScreen(templates, authToken, onRefresh = { syncWithServer() })
                2 -> ArticlesScreen(articles)
                3 -> LeaderboardScreen(authToken)
                4 -> SettingsScreen(isSyncing, lastSyncStatus, isServiceEnabled,
                    notifyInterval = notifyInterval,
                    onIntervalChange = { v -> notifyInterval = v; prefs.edit().putInt("notify_interval", v).apply() },
                    onTestNotify = { showNotification(context, "Test thông báo FreeHand thành công!\nHiện có ${posts.count { it.status == PostStatus.PENDING && it.addedBy != username }} bài đang PENDING.") },
                    onSync = { syncWithServer() },
                    onRequestPermission = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                )
            }
        }
    }

    if (showPermissionDialog && !isServiceEnabled) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            icon = { Text("⚙️", style = MaterialTheme.typography.headlineLarge) },
            title = { Text("Cần bật Accessibility Service") },
            text = { Text("Bấm \"Đi tới Cài đặt\" → tìm \"Comment Helper\" → bật lên.") },
            confirmButton = { FilledTonalButton(onClick = { showPermissionDialog = false; context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Đi tới Cài đặt") } },
            dismissButton = { TextButton(onClick = { showPermissionDialog = false }) { Text("Để sau") } }
        )
    }

    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            icon = { Text("📦", style = MaterialTheme.typography.headlineLarge) },
            title = { Text("Có phiên bản mới!") },
            text = {
                Column {
                    Text("Phiên bản $updateVersion đã sẵn sàng.", fontWeight = FontWeight.Bold)
                    if (updateChangelog.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(updateChangelog, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(8.dp))
                    Text("Phiên bản hiện tại: $APP_VERSION", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            },
            confirmButton = {
                FilledTonalButton(onClick = {
                    showUpdateDialog = false
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("⬇ Tải về") }
            },
            dismissButton = { TextButton(onClick = { showUpdateDialog = false }) { Text("Để sau") } }
        )
    }
}

/* ---------- POSTS TAB ---------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostsScreen(
    posts: List<Post>, templates: List<String>, isServiceEnabled: Boolean, isAutoRunning: Boolean,
    currentPostId: String?, autoProgress: Pair<Int, Int>,
    authToken: String, currentUserRole: String, currentUsername: String, onRefresh: () -> Unit,
    onRequestPermission: () -> Unit, onStartAuto: () -> Unit, onStopAuto: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("Chưa làm") }
    var showAdd by remember { mutableStateOf(false) }
    var pickFor by remember { mutableStateOf<Post?>(null) }

    val visible = remember(posts, filter) {
        when (filter) { 
            "Chưa làm" -> posts.filter { it.status == PostStatus.PENDING && it.addedBy != currentUsername }
            "Đã làm" -> posts.filter { it.status == PostStatus.DONE }
            else -> posts 
        }.sortedByDescending { it.addedAt }
    }
    val pending = posts.count { it.status == PostStatus.PENDING && it.addedBy != currentUsername }
    val done = posts.count { it.status == PostStatus.DONE }

    // Server API helpers
    fun serverAddPost(url: String, title: String) {
        scope.launch {
            val json = """{"url":"${url.trim()}","title":"${title.ifBlank { "" }}"}"""
            val (code, body) = httpReq("$SERVER_URL/api/posts", "POST", json, authToken)
            if (code == 200) { onRefresh(); toast(context, "Đã thêm bài.") }
            else if (code == 429 && body != null) { try { toast(context, JSONObject(body).getString("error")) } catch (_: Exception) { toast(context, "Quá 5 bài/ngày") } }
            else if (code == 409) { toast(context, "Bài đã tồn tại") }
            else { toast(context, "Lỗi thêm bài: $code") }
        }
    }

    fun serverDeletePost(postId: String) {
        scope.launch {
            httpReq("$SERVER_URL/api/posts/$postId", "DELETE", null, authToken)
            onRefresh()
        }
    }

    fun serverToggleDone(post: Post) {
        scope.launch {
            if (post.status == PostStatus.PENDING) {
                httpReq("$SERVER_URL/api/posts/${post.id}/done", "POST", "{}", authToken)
            }
            // Note: server doesn't have "undo done", so just refresh
            onRefresh()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (isAutoRunning) {
            ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚡ Đang tự động...", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        FilledTonalButton(onClick = onStopAuto, colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer)) { Text("⏹ Dừng") }
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { if (autoProgress.second > 0) autoProgress.first.toFloat() / autoProgress.second else 0f }, modifier = Modifier.fillMaxWidth())
                    Text("Xong ${autoProgress.first}/${autoProgress.second} bài", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(filter == "Chưa làm", { filter = "Chưa làm" }, label = { Text("Chưa ($pending)") })
            Spacer(Modifier.width(8.dp))
            FilterChip(filter == "Đã làm", { filter = "Đã làm" }, label = { Text("Đã ($done)") })
            Spacer(Modifier.width(8.dp))
            FilterChip(filter == "Tất cả", { filter = "Tất cả" }, label = { Text("Tất cả (${posts.size})") })
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!isAutoRunning && pending > 0) {
                Button(onClick = { if (!isServiceEnabled) onRequestPermission() else onStartAuto() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))) { Text("▶ Bắt đầu ($pending)") }
                Spacer(Modifier.width(8.dp))
            }
            Spacer(Modifier.weight(1f))
            FilledTonalButton(onClick = { showAdd = true }) { Text("+ Thêm") }
        }

        if (!isServiceEnabled) {
            Spacer(Modifier.height(8.dp))
            ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = .5f))) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("⚠️ Accessibility chưa bật", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRequestPermission) { Text("Bật") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (visible.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { Text(when (filter) { "Chưa làm" -> "Không có bài chưa làm."; "Đã làm" -> "Chưa bài nào xong."; else -> "Chưa có bài. Bấm + Thêm để thêm bài." }) }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.id }) { post -> 
                    PostRow(post, currentPostId == post.id, currentUserRole,
                        isMine = post.addedBy == currentUsername,
                        onAct = { if (templates.isEmpty()) toast(context, "Chưa có comment mẫu.") else pickFor = post },
                        onToggleDone = { serverToggleDone(post) },
                        onDelete = { serverDeletePost(post.id) }, 
                        onOpen = { openPost(context, post.url) }
                    ) 
                }
            }
        }
    }

    if (showAdd) AddPostDialog({ url, title -> serverAddPost(url, title); showAdd = false }, { showAdd = false })
    pickFor?.let { post -> 
        TemplatePickerDialog(templates, { tpl -> 
            FbAutoService.instance?.startProcessing(listOf(FbAutoService.TaskItem(post.id, post.url, tpl)))
            FbAutoService.isRunning.value = true
            pickFor = null
            toast(context, "Đang xử lý tương tác...")
        }, { pickFor = null }) 
    }
}

@Composable
private fun PostRow(post: Post, isProcessing: Boolean, currentUserRole: String, isMine: Boolean, onAct: () -> Unit, onToggleDone: () -> Unit, onDelete: () -> Unit, onOpen: () -> Unit) {
    val isDone = post.status == PostStatus.DONE
    ElevatedCard(Modifier.fillMaxWidth(), colors = if (isProcessing) CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer) else CardDefaults.elevatedCardColors()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isProcessing) { Text("⚡"); Spacer(Modifier.width(4.dp)) }
                StatusBadge(isDone); Spacer(Modifier.width(8.dp))
                Text(post.title ?: shortenUrl(post.url), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            }
            if (post.title != null) Text(shortenUrl(post.url), style = MaterialTheme.typography.bodySmall)
            post.interactedAt?.let { Text("Xong lúc ${formatTime(it)}", style = MaterialTheme.typography.bodySmall) }
            if (isProcessing) Text("🔄 Đang xử lý...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row {
                if (!isDone && !isProcessing && !isMine) { FilledTonalButton(onClick = onAct) { Text("Comment") }; Spacer(Modifier.width(8.dp)) }
                OutlinedButton(onClick = onOpen) { Text("Mở FB") }; Spacer(Modifier.width(8.dp))
                if (currentUserRole == "admin") {
                    TextButton(onClick = onToggleDone) { Text(if (isDone) "↺ Bỏ" else "✓ Xong") }
                    Spacer(Modifier.weight(1f)); TextButton(onClick = onDelete) { Text("Xoá") }
                }
            }
        }
    }
}

@Composable private fun StatusBadge(done: Boolean) {
    val (l, bg) = if (done) "ĐÃ" to Color(0xFF2E7D32) else "CHƯA" to Color(0xFFB26A00)
    Surface(color = bg, shape = MaterialTheme.shapes.small) { Text(l, color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
}

@Composable private fun AddPostDialog(onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }; var title by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Thêm bài viết") },
        text = { Column { OutlinedTextField(url, { url = it }, label = { Text("Link bài") }, modifier = Modifier.fillMaxWidth(), singleLine = true); Spacer(Modifier.height(8.dp)); OutlinedTextField(title, { title = it }, label = { Text("Tên (tuỳ)") }, modifier = Modifier.fillMaxWidth(), singleLine = true) } },
        confirmButton = { TextButton({ onAdd(url, title) }) { Text("Lưu") } }, dismissButton = { TextButton(onDismiss) { Text("Huỷ") } })
}

@Composable private fun TemplatePickerDialog(templates: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Chọn template") },
        text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) { items(templates) { tpl -> OutlinedCard(Modifier.fillMaxWidth()) { Text(tpl, Modifier.fillMaxWidth().padding(12.dp)); Row(Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 4.dp)) { Spacer(Modifier.weight(1f)); TextButton({ onPick(tpl) }) { Text("Dùng") } } } } } },
        confirmButton = { TextButton(onDismiss) { Text("Đóng") } })
}

/* ---------- TEMPLATES TAB ---------- */

@Composable fun TemplatesScreen(templates: List<String>, authToken: String, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var newTpl by remember { mutableStateOf("") }; var showAdd by remember { mutableStateOf(false) }

    fun serverAddTpl(text: String) {
        scope.launch {
            httpReq("$SERVER_URL/api/templates", "POST", """{"text":"$text"}""", authToken)
            onRefresh(); toast(context, "Đã thêm template.")
        }
    }
    fun serverDeleteTpl(text: String) {
        scope.launch {
            httpReq("$SERVER_URL/api/templates", "DELETE", """{"text":"$text"}""", authToken)
            onRefresh()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("${templates.size} template"); Spacer(Modifier.weight(1f)); FilledTonalButton(onClick = { showAdd = true }) { Text("+ Thêm") } }
        Spacer(Modifier.height(12.dp))
        if (templates.isEmpty()) Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { Text("Chưa có template.") }
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(templates, key = { it }) { tpl -> ElevatedCard(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Text(tpl, Modifier.weight(1f)); TextButton({ serverDeleteTpl(tpl) }) { Text("Xoá") } } } } }
    }
    if (showAdd) AlertDialog(onDismissRequest = { showAdd = false; newTpl = "" }, title = { Text("Thêm template") },
        text = { OutlinedTextField(newTpl, { newTpl = it }, label = { Text("Nội dung comment") }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton({ val t = newTpl.trim(); if (t.isNotEmpty()) serverAddTpl(t); newTpl = ""; showAdd = false }) { Text("Lưu") } },
        dismissButton = { TextButton({ showAdd = false; newTpl = "" }) { Text("Huỷ") } })
}

/* ---------- SETTINGS TAB ---------- */

@Composable fun SettingsScreen(
    isSyncing: Boolean, lastSyncStatus: String, isServiceEnabled: Boolean,
    notifyInterval: Int, onIntervalChange: (Int) -> Unit, onTestNotify: () -> Unit,
    onSync: () -> Unit, onRequestPermission: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Cài đặt", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("🔄 Đồng bộ Server", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onSync, modifier = Modifier.fillMaxWidth(), enabled = !isSyncing) {
                    if (isSyncing) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White); Spacer(Modifier.width(8.dp)); Text("Đang sync...") }
                    else Text("🔄 Sync từ Server")
                }
                if (lastSyncStatus.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(lastSyncStatus, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Spacer(Modifier.height(16.dp))
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("🔔 Cài đặt Thông báo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Khoảng cách nhắc nhở (phút):")
                    Spacer(Modifier.weight(1f))
                    var txt by remember { mutableStateOf(notifyInterval.toString()) }
                    OutlinedTextField(
                        value = txt,
                        onValueChange = { txt = it; it.toIntOrNull()?.let { v -> onIntervalChange(v) } },
                        modifier = Modifier.width(70.dp),
                        singleLine = true
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("Ghi 0 để tắt nhắc nhở gom nhóm.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onTestNotify, modifier = Modifier.fillMaxWidth()) {
                    Text("🔔 Test Thông Báo Ngay")
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("♿ Accessibility Service", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isServiceEnabled) "✅ Đã bật" else "❌ Chưa bật", color = if (isServiceEnabled) Color(0xFF2E7D32) else Color(0xFFB71C1C))
                    Spacer(Modifier.weight(1f))
                    if (!isServiceEnabled) FilledTonalButton(onClick = onRequestPermission) { Text("Bật") }
                }
            }
        }
    }
}

/* ================== HELPERS ================== */

private fun copyToClipboard(ctx: Context, text: String) { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("comment", text)) }
private fun openPost(ctx: Context, url: String) {
    val cleanUrl = url.replace("m.facebook.com", "www.facebook.com").replace("mbasic.facebook.com", "www.facebook.com")
    try { 
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).setPackage("com.facebook.katana"))
    } catch (e: Exception) { 
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).setPackage("com.facebook.lite"))
        } catch(e2: Exception) {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch(e3: Exception) { toast(ctx, "Lỗi: ${e3.message}") }
        }
    } 
}
private fun toast(ctx: Context, msg: String) { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
private val URL_REGEX = Regex("""https?://\S+""")
private fun extractFirstUrl(text: String): String? = URL_REGEX.find(text)?.value
private fun shortenUrl(url: String) = if (url.length <= 50) url else url.take(47) + "..."
private val TIME_FMT = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
private fun formatTime(t: Long): String = TIME_FMT.format(Date(t))

/* ---------- ARTICLES TAB ---------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ArticlesScreen(articles: List<Article>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var filterCategory by remember { mutableStateOf<String?>(null) }
    
    val categories = articles.map { it.category }.distinct().sorted()
    val visible = if (filterCategory != null) articles.filter { it.category == filterCategory } else articles

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (categories.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(filterCategory == null, { filterCategory = null }, label = { Text("Tất cả") })
                categories.forEach { cat ->
                    FilterChip(filterCategory == cat, { filterCategory = cat }, label = { Text(cat) })
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { Text("Chưa có bài mẫu.") }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.id }) { art ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("[${art.category}] ${art.title}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(4.dp))
                            Text(art.content, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            if (art.images.isNotEmpty()) {
                                Text("🖼 ${art.images.size} ảnh đi kèm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(art.images) { imgUrl ->
                                        AsyncImage(
                                            model = imgUrl,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.size(80.dp).clip(RoundedCornerShape(8.dp))
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row {
                                FilledTonalButton(onClick = { copyToClipboard(context, art.content); toast(context, "Đã copy nội dung") }) { Text("Copy", style = MaterialTheme.typography.bodySmall) }
                                Spacer(Modifier.width(8.dp))
                                if (art.images.isNotEmpty()) {
                                    OutlinedButton(onClick = { 
                                        scope.launch { downloadImages(context, art.images) }
                                    }) { Text("Tải tất cả ảnh", style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun downloadImages(context: Context, urls: List<String>) = withContext(Dispatchers.IO) {
    var successCount = 0
    val errors = mutableListOf<String>()

    urls.forEachIndexed { i, url ->
        try {
            // Validate URL
            val uri = Uri.parse(url)
            if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) {
                errors.add("Ảnh ${i + 1}: URL không hợp lệ")
                return@forEachIndexed
            }

            // Use MediaStore on Android 10+ (Q), DownloadManager fallback otherwise
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (downloadViaMediaStore(context, url, i)) successCount++
                else errors.add("Ảnh ${i + 1}: lưu thất bại")
            } else {
                if (downloadViaDownloadManager(context, url, i)) successCount++
                else errors.add("Ảnh ${i + 1}: enqueue thất bại")
            }
        } catch (e: Exception) {
            errors.add("Ảnh ${i + 1}: ${e.message ?: "lỗi không xác định"}")
        }
    }

    withContext(Dispatchers.Main) {
        val msg = when {
            successCount == urls.size -> "✓ Đã tải $successCount/${urls.size} ảnh xuống Pictures"
            successCount > 0 -> "Tải $successCount/${urls.size}. Lỗi: ${errors.firstOrNull() ?: ""}"
            else -> "Tải thất bại: ${errors.firstOrNull() ?: "không rõ"}"
        }
        toast(context, msg)
    }
}

private fun downloadViaMediaStore(context: Context, url: String, index: Int): Boolean {
    return try {
        val fileName = "CommentHelper_${System.currentTimeMillis()}_$index.jpg"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CommentHelper")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val itemUri = resolver.insert(collection, values) ?: return false

        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000; conn.readTimeout = 30000
            conn.connect()
            if (conn.responseCode !in 200..299) {
                resolver.delete(itemUri, null, null)
                return false
            }
            resolver.openOutputStream(itemUri)?.use { out ->
                conn.inputStream.use { input -> input.copyTo(out) }
            } ?: return false
        } catch(e: Exception) {
            resolver.delete(itemUri, null, null)
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
        }
        true
    } catch (e: Exception) {
        android.util.Log.e("downloadImages", "MediaStore failed for $url", e)
        false
    }
}

private fun downloadViaDownloadManager(context: Context, url: String, index: Int): Boolean {
    return try {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(url))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES, "CommentHelper_IMG_${System.currentTimeMillis()}_$index.jpg")
            .setTitle("Tải ảnh ${index + 1}")
            .setMimeType("image/jpeg")
        dm.enqueue(req)
        true
    } catch (e: Exception) {
        android.util.Log.e("downloadImages", "DownloadManager failed for $url", e)
        false
    }
}

/* ---------- NOTIFICATIONS SYNC ---------- */

private suspend fun syncNotifications(authToken: String, context: Context) = withContext(Dispatchers.IO) {
    if (authToken.isBlank()) return@withContext
    val (code, body) = httpReq("$SERVER_URL/api/notifications", token = authToken)
    if (code == 200 && body != null) {
        try {
            val arr = JSONArray(body)
            if (arr.length() > 0) {
                // Show notifications
                for (i in 0 until arr.length()) {
                    val n = arr.getJSONObject(i)
                    showNotification(context, n.getString("message"))
                }
                // Mark as read
                httpReq("$SERVER_URL/api/notifications/read", "POST", "{}", authToken)
            }
        } catch (_: Exception) {}
    }
}

private fun showNotification(context: Context, text: String) {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val ch = NotificationChannel("ch_updates", "Comment Helper", NotificationManager.IMPORTANCE_DEFAULT)
        nm.createNotificationChannel(ch)
    }
    val notif = NotificationCompat.Builder(context, "ch_updates")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Thông báo mới")
        .setContentText(text)
        .setAutoCancel(true)
        .build()
    nm.notify(System.currentTimeMillis().toInt(), notif)
}

@Composable fun LeaderboardScreen(authToken: String) {
    var members by remember { mutableStateOf<org.json.JSONArray?>(null) }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val (c, b) = httpReq("$SERVER_URL/api/group/members", token = authToken)
        if (c == 200 && b != null) { try { members = org.json.JSONArray(b) } catch(e:Exception){ err = "Lỗi dữ liệu" } }
        else { err = "Server phản hồi: $c. Có rớt mạng hoặc VPS chưa update backend." }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("🏆 Bảng xếp hạng nhóm", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        if (err.isNotEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(err, color = MaterialTheme.colorScheme.error) }
        else if (members == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(members!!.length()) { i -> 
                    val m = members!!.getJSONObject(i)
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i+1}.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(16.dp))
                            Text(m.getString("username"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text("⭐️ ${m.getInt("points")}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color(0xFFF59E0B))
                        }
                    }
                }
            }
        }
    }
}
