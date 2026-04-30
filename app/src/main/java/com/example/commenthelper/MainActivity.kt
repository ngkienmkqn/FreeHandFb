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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.toArgb
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
private const val KEY_PHONE = "user_phone"
private const val KEY_ZALO = "user_zalo"
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
    val images: List<String>,
    val scope: String = "global",
    val status: String = "approved",
    val addedBy: String = ""
)

data class SuggestedGroup(val id: String, val name: String, val memberCount: String, val url: String, val addedBy: String)

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

fun applySpintaxAndVars(text: String, prefs: SharedPreferences): String {
    val phone = prefs.getString(KEY_PHONE, "") ?: ""
    val zalo = prefs.getString(KEY_ZALO, "") ?: ""

    var res = text
        .replace("{PHONE}", phone).replace("{SDT}", phone).replace("{PHONE_NUMBER}", phone)
        .replace("{ZALO}", zalo).replace("{ZALO_LINK}", zalo)
        // Also support formats without braces if the user pasted them exactly
        .replace("ZALO_LINK", zalo).replace("ZALO_ME", zalo)

    // Evaluate spintax: {A|B|C}
    val spintaxRegex = Regex("\\{([^\\{\\}]+)\\}")
    while (res.contains(spintaxRegex)) {
        res = res.replace(spintaxRegex) { match ->
            val options = match.groupValues[1].split("|")
            options.random()
        }
    }
    return res
}

private fun parseServerPosts(json: String, currentUsername: String): List<Post> {
    return try {
        val arr = JSONArray(json)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            
            var isRemoteDone = false
            val completedByArr = o.optJSONArray("completedBy")
            if (completedByArr != null) {
                for (j in 0 until completedByArr.length()) {
                    if (completedByArr.getString(j) == currentUsername) {
                        isRemoteDone = true
                        break
                    }
                }
            }
            
            Post(
                id = o.getString("id"), url = o.getString("url"),
                title = if (o.isNull("title")) null else o.optString("title"),
                status = if (isRemoteDone) PostStatus.DONE else PostStatus.valueOf(o.optString("status", "PENDING")),
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
                images = imgs,
                scope = o.optString("scope", "global"),
                status = o.optString("status", "approved"),
                addedBy = o.optString("addedBy", "")
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

class AutoWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val interval = prefs.getInt("autowake_interval_hours", 0)
        
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) { 
            scheduleAutoWake(context, interval)
            val pubInterval = prefs.getInt("autopublish_interval_minutes", 0)
            AutoPublishReceiver.schedule(context, pubInterval)
            return 
        }

        if (interval > 0) {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("EXTRA_AUTO_START", true)
            }
            context.startActivity(launchIntent)
            scheduleAutoWake(context, interval)
        }
    }

    companion object {
        fun scheduleAutoWake(context: Context, intervalHours: Int) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val pendingIntent = android.app.PendingIntent.getBroadcast(context, 100, Intent(context, AutoWakeReceiver::class.java), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            if (intervalHours <= 0) alarmManager.cancel(pendingIntent)
            else {
                val triggerAt = System.currentTimeMillis() + intervalHours * 3600 * 1000L
                try { alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent) }
                catch (e: Exception) { alarmManager.set(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent) }
            }
        }
    }
}

/* ================== ACTIVITY ================== */

class MainActivity : ComponentActivity() {
    private val localReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val action = intent.action
            if (action == "com.example.commenthelper.GROUP_SCRAPED") {
                val name = intent.getStringExtra("name") ?: ""
                val count = intent.getStringExtra("memberCount") ?: ""
                val url = intent.getStringExtra("url") ?: ""
                val token = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY_AUTH_TOKEN, "") ?: ""
                if (token.isNotEmpty() && name.isNotEmpty() && context != null) {
                    Thread {
                        try {
                            val js = JSONObject().apply { put("name", name); put("url", url); put("memberCount", count) }
                            val conn = URL("$SERVER_URL/api/suggested-groups").openConnection() as HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.setRequestProperty("Content-Type", "application/json")
                            conn.setRequestProperty("Authorization", "Bearer $token")
                            conn.doOutput = true
                            java.io.OutputStreamWriter(conn.outputStream).use { it.write(js.toString()) }
                            if (conn.responseCode in 200..299) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post { 
                                    Toast.makeText(context, "Đã tự động lấy và đề xuất thông tin nhóm!", Toast.LENGTH_SHORT).show() 
                                }
                            }
                        } catch (e: Exception) {}
                    }.start()
                }
                return
            }
            val postId = intent.getStringExtra("postId") ?: return
            PostDoneReceiver.onPostDone?.invoke(postId, intent.getBooleanExtra("success", false))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Wake setup
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        
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

        val filter = IntentFilter()
        filter.addAction("com.example.commenthelper.POST_DONE")
        filter.addAction("com.example.commenthelper.GROUP_SCRAPED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(localReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(localReceiver, filter)

        val autoStart = intent?.getBooleanExtra("EXTRA_AUTO_START", false) ?: false
        checkAndHandleAutoIntent(intent)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { AppRoot(initialUrl = incomingUrl, autoStart = autoStart) } } }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { checkAndHandleAutoIntent(it) }
    }

    private fun checkAndHandleAutoIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra("EXTRA_AUTO_PUBLISH", false)) {
            val text = intent.getStringExtra("EXTRA_TEXT") ?: ""
            val groups = intent.getStringArrayExtra("EXTRA_GROUPS")?.toList() ?: emptyList()
            val images = intent.getStringArrayListExtra("EXTRA_IMAGES") ?: arrayListOf()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                if (images.isNotEmpty()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(this@MainActivity, "Headless Trigger: Downloading images", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    downloadImages(this@MainActivity, images)
                }
                FbAutoService.instance?.startPublishing(text, images, groups)
            }
            intent.removeExtra("EXTRA_AUTO_PUBLISH")
        }
    }

    override fun onDestroy() { super.onDestroy(); try { unregisterReceiver(localReceiver) } catch (_: Exception) {} }
}

/* ================== UI ROOT ================== */

data class SplashInfo(val imageUrl: String, val text: String, val durationMs: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(initialUrl: String?, autoStart: Boolean = false) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var showSplash by remember { mutableStateOf(true) }
    var splashInfo by remember { mutableStateOf<SplashInfo?>(null) }
    var splashLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val (code, body) = httpReq("$SERVER_URL/api/splash")
                if (code in 200..299 && body != null) {
                    val json = org.json.JSONObject(body)
                    splashInfo = SplashInfo(
                        imageUrl = json.optString("imageUrl", ""),
                        text = json.optString("text", "Chào mừng bạn đến với FreeHand Fb"),
                        durationMs = json.optLong("durationMs", 3000)
                    )
                }
            } catch (e: Exception) {
            }
            splashLoaded = true
        }
    }

    if (showSplash) {
        if (splashLoaded && splashInfo != null) {
            LaunchedEffect(splashInfo) {
                kotlinx.coroutines.delay(splashInfo!!.durationMs)
                showSplash = false
            }
            Box(modifier = Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (splashInfo!!.imageUrl.isNotBlank()) {
                        coil.compose.AsyncImage(
                            model = splashInfo!!.imageUrl,
                            contentDescription = "Splash Image",
                            modifier = Modifier.fillMaxWidth(0.8f).aspectRatio(1f),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(100.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    val primaryColor = MaterialTheme.colorScheme.primary
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            android.widget.TextView(ctx).apply {
                                text = splashInfo!!.text
                                textSize = 20f
                                gravity = android.view.Gravity.CENTER
                                setTextColor(primaryColor.toArgb())
                                autoLinkMask = android.text.util.Linkify.WEB_URLS
                                linksClickable = true
                                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                                setLinkTextColor(primaryColor.toArgb())
                            }
                        },
                        update = { 
                            it.text = splashInfo!!.text
                            it.setTextColor(primaryColor.toArgb())
                            it.setLinkTextColor(primaryColor.toArgb())
                        }
                    )
                }
            }
        } else if (splashLoaded && splashInfo == null) {
            LaunchedEffect(Unit) { showSplash = false }
        } else {
            Box(modifier = Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }

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
            onLogin = { user, token, group, phone, zalo ->
                username = user; authToken = token; userGroup = group
                prefs.edit()
                    .putString(KEY_AUTH_TOKEN, token)
                    .putString(KEY_USERNAME, user)
                    .putString(KEY_GROUP, group)
                    .putString(KEY_PHONE, phone)
                    .putString(KEY_ZALO, zalo)
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
            autoStart = autoStart,
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
fun LoginScreen(onLogin: (username: String, token: String, group: String, phone: String, zalo: String) -> Unit) {
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
                                onLogin(u.getString("username"), token, u.getString("group"), u.optString("phone", ""), u.optString("zaloLink", ""))
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
    autoStart: Boolean,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var points by remember { mutableStateOf(0) }
    var role by remember { mutableStateOf("user") }
    
    var notifyInterval by remember { mutableIntStateOf(prefs.getInt("notify_interval", 30)) }
    var lastNotifyTime by remember { mutableLongStateOf(prefs.getLong("last_notify", 0L)) }
    var autoWakeIntervalHours by remember { mutableIntStateOf(prefs.getInt("autowake_interval_hours", 1)) }
    var autoPublishIntervalMinutes by remember { mutableIntStateOf(prefs.getInt("autopublish_interval_minutes", 15)) }

    var posts by remember { mutableStateOf(loadPosts(prefs)) }
    var templates by remember { mutableStateOf(loadTemplates(prefs)) }
    var articles by remember { mutableStateOf(emptyList<Article>()) }
    var suggestedGroups by remember { mutableStateOf(emptyList<SuggestedGroup>()) }
    var tab by remember { mutableIntStateOf(0) }
    var isSyncing by remember { mutableStateOf(false) }
    var lastSyncStatus by remember { mutableStateOf("") }

    var isServiceEnabled by remember { mutableStateOf(FbAutoService.isServiceEnabled(context)) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    val isAutoRunning by FbAutoService.isRunning.collectAsState()
    val currentPostId by FbAutoService.currentPostId.collectAsState()
    val autoProgress by FbAutoService.progress.collectAsState()
    val currentStatusText by FbAutoService.currentStatusText.collectAsState()

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
                try {
                    val j = JSONObject(mb)
                    points = j.getInt("points")
                    role = j.getString("role")
                    val phone = j.optString("phone", "")
                    val zalo = j.optString("zaloLink", "")
                    val e = prefs.edit()
                    e.putString(KEY_PHONE, phone).putString(KEY_ZALO, zalo)
                    e.putBoolean("global_debug_mode", j.optBoolean("isDebug", false))
                    
                    val settings = j.optJSONObject("settings")
                    if (settings != null) {
                        if (settings.has("start_active_hour")) e.putInt("start_active_hour", settings.getInt("start_active_hour"))
                        if (settings.has("end_active_hour")) e.putInt("end_active_hour", settings.getInt("end_active_hour"))
                        if (settings.has("block_timeout_hours")) e.putInt("block_timeout_hours", settings.getInt("block_timeout_hours"))
                        if (settings.has("notify_interval")) e.putInt("notify_interval", settings.getInt("notify_interval"))
                        if (settings.has("autowake_interval_hours")) e.putInt("autowake_interval_hours", settings.getInt("autowake_interval_hours"))
                        if (settings.has("autopublish_interval_minutes")) e.putInt("autopublish_interval_minutes", settings.getInt("autopublish_interval_minutes"))
                        if (settings.has("publish_groups")) e.putString("publish_groups", settings.getString("publish_groups"))
                        if (settings.has("selected_article_ids")) e.putString("selected_article_ids", settings.getString("selected_article_ids"))
                    }
                    e.apply()
                } catch (_: Exception) {}
            }

            val (pc, pb) = httpReq("$SERVER_URL/api/posts", token = authToken)
            val (tc, tb) = httpReq("$SERVER_URL/api/templates", token = authToken)
            val (ac, ab) = httpReq("$SERVER_URL/api/articles", token = authToken)
            val (sgc, sgb) = httpReq("$SERVER_URL/api/suggested-groups", token = authToken)

            val selectedOta = prefs.getString("selected_ota_version", "latest") ?: "latest"
            val (ecList, ebList) = httpReq("$SERVER_URL/api/engine/scripts", token = authToken)
            val (ec, eb) = httpReq("$SERVER_URL/api/engine/script?version=$selectedOta", token = authToken)

            if (pc == 401) { onLogout(); return@launch }

            if (pc == 200 && pb != null) { posts = parseServerPosts(pb, username); savePosts(prefs, posts) }
            if (tc == 200 && tb != null) { templates = parseServerTemplates(tb); saveTemplates(prefs, templates) }
            if (ac == 200 && ab != null) { articles = parseServerArticles(ab) }
            if (ecList == 200 && ebList != null) { prefs.edit().putString("ota_available_versions", ebList).apply() }
            if (ec == 200 && eb != null) { prefs.edit().putString("engine_script", eb).apply() }
            if (sgc == 200 && sgb != null) {
                try {
                    val arr = JSONObject(sgb).optJSONArray("approved")
                    if (arr != null) {
                        val lst = mutableListOf<SuggestedGroup>()
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            lst.add(SuggestedGroup(o.optString("id",""), o.optString("name",""), o.optString("memberCount",""), o.optString("url",""), o.optString("addedBy","")))
                        }
                        suggestedGroups = lst
                    }
                } catch(_: Exception){}
            }
            
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
                if (c == 401 || c == 403) {
                    onLogout()
                    break
                }
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

    // Unattended Auto Start
    LaunchedEffect(posts, autoStart, isServiceEnabled) {
        if (autoStart && isServiceEnabled) {
            val pendingPosts = posts.filter { it.status == PostStatus.PENDING && it.addedBy != username }
            if (pendingPosts.isNotEmpty()) {
                val isFbInstalled = try { context.packageManager.getPackageInfo("com.facebook.katana", 0); true } catch (e: Exception) { false }
                if (!isFbInstalled) {
                    toast(context, "Lỗi: Không tìm thấy ứng dụng Facebook (com.facebook.katana). Vui lòng cài đặt và đăng nhập trước!")
                    return@LaunchedEffect
                } else {
                    val tasks = pendingPosts.map { p -> FbAutoService.TaskItem(p.id, p.url, templates.randomOrNull() ?: "") }
                    FbAutoService.instance?.startProcessing(tasks)
                    FbAutoService.isRunning.value = true
                }
            }
        }
    }

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

    fun pushSettingsToServer() {
        scope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject()
                val setList = JSONObject()
                setList.put("start_active_hour", prefs.getInt("start_active_hour", 7))
                setList.put("end_active_hour", prefs.getInt("end_active_hour", 23))
                setList.put("block_timeout_hours", prefs.getInt("block_timeout_hours", 24))
                setList.put("notify_interval", prefs.getInt("notify_interval", 0))
                setList.put("autowake_interval_hours", prefs.getInt("autowake_interval_hours", 0))
                setList.put("autopublish_interval_minutes", prefs.getInt("autopublish_interval_minutes", 0))
                setList.put("publish_groups", prefs.getString("publish_groups", ""))
                setList.put("selected_article_ids", prefs.getString("selected_article_ids", ""))
                
                json.put("settings", setList)
                json.put("phone", prefs.getString(KEY_PHONE, ""))
                json.put("zaloLink", prefs.getString(KEY_ZALO, ""))
                httpReq("$SERVER_URL/api/me", "PUT", json.toString(), authToken)
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(isServiceEnabled) { if (!isServiceEnabled) showPermissionDialog = true }

    Scaffold(
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = { Text("FreeHand") },
                    actions = {
                        if (isAutoRunning) {
                            TextButton(onClick = { FbAutoService.instance?.stopProcessing() }) {
                                Text("⏹ Dừng", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                            }
                        } else {
                            TextButton(onClick = {
                                if (!isServiceEnabled) {
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                } else {
                                    // Master Start: sync → interact pending → then schedule publish
                                    val pendingPosts = posts.filter { it.status == PostStatus.PENDING && it.addedBy != username }
                                    val isFbInstalled = try { context.packageManager.getPackageInfo("com.facebook.katana", 0); true } catch (e: Exception) { false }
                                    if (!isFbInstalled) {
                                        toast(context, "Cần cài Facebook trước!")
                                    } else if (pendingPosts.isEmpty()) {
                                        toast(context, "Không có bài cần tương tác.")
                                    } else if (templates.isEmpty()) {
                                        toast(context, "Đang tải comments, chờ chút...")
                                    } else {
                                        FbAutoService.instance?.startProcessing(pendingPosts.map { p -> FbAutoService.TaskItem(p.id, p.url, templates.random()) })
                                        FbAutoService.isRunning.value = true
                                        toast(context, "▶ Bắt đầu tương tác ${pendingPosts.size} bài!")
                                    }
                                }
                            }) {
                                Text("▶ Bắt đầu", color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
                            }
                        }
                        TextButton(onClick = onLogout) { Text("Thoát", color = Color(0xFFEF4444), style = MaterialTheme.typography.labelSmall) }
                    }
                )
                // Master status bar
                if (isAutoRunning) {
                    Surface(color = Color(0xFF065F46), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("⚡ Đang chạy: ${autoProgress.first}/${autoProgress.second}", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                LinearProgressIndicator(
                                    progress = { if (autoProgress.second > 0) autoProgress.first.toFloat() / autoProgress.second else 0f },
                                    modifier = Modifier.width(100.dp).height(6.dp),
                                    color = Color(0xFF10B981),
                                    trackColor = Color(0xFF134E4A)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(currentStatusText, color = Color(0xFFA7F3D0), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
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
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Bài viết", maxLines = 1) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Comments", maxLines = 1) })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Bài Mẫu", maxLines = 1) })
                    Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Thành viên", maxLines = 1) })
                    Tab(selected = tab == 4, onClick = { tab = 4 }, text = { Text("⚙️", maxLines = 1) })
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
                        val isFbInstalled = try { context.packageManager.getPackageInfo("com.facebook.katana", 0); true } catch (e: Exception) { false }
                        
                        if (!isFbInstalled) {
                            toast(context, "Lỗi: Không tìm thấy ứng dụng Facebook. Vui lòng cài đặt Facebook và đăng nhập trước!")
                        } else if (pendingPosts.isEmpty()) { 
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
                2 -> ArticlesScreen(articles, suggestedGroups, prefs, authToken, onSettingsChanged = { pushSettingsToServer() })
                3 -> LeaderboardScreen(authToken)
                4 -> SettingsScreen(isSyncing, lastSyncStatus, isServiceEnabled,
                    startActiveHour = prefs.getInt("start_active_hour", 7),
                    onStartActiveHourChange = { v -> prefs.edit().putInt("start_active_hour", v).apply(); pushSettingsToServer() },
                    endActiveHour = prefs.getInt("end_active_hour", 23),
                    onEndActiveHourChange = { v -> prefs.edit().putInt("end_active_hour", v).apply(); pushSettingsToServer() },
                    notifyInterval = notifyInterval,
                    onIntervalChange = { v -> notifyInterval = v; prefs.edit().putInt("notify_interval", v).apply(); pushSettingsToServer() },
                    autoWakeIntervalHours = autoWakeIntervalHours,
                    onAutoWakeIntervalChange = { v -> 
                        autoWakeIntervalHours = v
                        prefs.edit().putInt("autowake_interval_hours", v).apply()
                        pushSettingsToServer()
                        AutoWakeReceiver.scheduleAutoWake(context, v)
                    },
                    autoPublishIntervalMinutes = autoPublishIntervalMinutes,
                    onAutoPublishIntervalChange = { v ->
                        autoPublishIntervalMinutes = v
                        prefs.edit().putInt("autopublish_interval_minutes", v).apply()
                        pushSettingsToServer()
                        AutoPublishReceiver.schedule(context, v)
                    },
                    onTriggerNow = {
                        val wm = androidx.work.WorkManager.getInstance(context)
                        val req = androidx.work.OneTimeWorkRequestBuilder<AutoPublishWorker>().build()
                        wm.enqueue(req)
                        toast(context, "Đã kích hoạt ngầm 1 luồng thả Bài!")
                    },
                    onTestNotify = { showNotification(context, "Test thông báo FreeHand thành công!\nHiện có ${posts.count { it.status == PostStatus.PENDING && it.addedBy != username }} bài đang PENDING.") },
                    onSync = { syncWithServer() },
                    onRequestPermission = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    prefs = prefs,
                    onExplicitSave = { pushSettingsToServer() }
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
    var filter by remember { mutableStateOf("Cần Giúp") }
    var showAdd by remember { mutableStateOf(false) }
    var pickFor by remember { mutableStateOf<Post?>(null) }

    val visible = remember(posts, filter) {
        when (filter) { 
            "Cần Giúp" -> posts.filter { it.status == PostStatus.PENDING && it.addedBy != currentUsername }
            "Đã Giúp" -> posts.filter { it.status == PostStatus.DONE && it.addedBy != currentUsername }
            "Bài Của Tôi" -> posts.filter { it.addedBy == currentUsername }
            else -> posts 
        }.sortedByDescending { it.addedAt }
    }
    val pending = posts.count { it.status == PostStatus.PENDING && it.addedBy != currentUsername }
    val done = posts.count { it.status == PostStatus.DONE && it.addedBy != currentUsername }
    val mine = posts.count { it.addedBy == currentUsername }

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
            FilterChip(filter == "Cần Giúp", { filter = "Cần Giúp" }, label = { Text("Cần Giúp ($pending)") })
            Spacer(Modifier.width(8.dp))
            FilterChip(filter == "Đã Giúp", { filter = "Đã Giúp" }, label = { Text("Đã Giúp ($done)") })
            Spacer(Modifier.width(8.dp))
            FilterChip(filter == "Bài Của Tôi", { filter = "Bài Của Tôi" }, label = { Text("Của tôi ($mine)") })
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
            Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { Text(when (filter) { "Cần Giúp" -> "Không có bài cần giúp đỡ."; "Đã Giúp" -> "Chưa giúp ai bài nào."; "Bài Của Tôi" -> "Bạn chưa đăng bài nào. Bấm + Thêm để thêm bài."; else -> "Trống." }) }
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
            val isFbInstalled = try { context.packageManager.getPackageInfo("com.facebook.katana", 0); true } catch (e: Exception) { false }
            if (!isFbInstalled) {
                toast(context, "Lỗi: Không tìm thấy ứng dụng Facebook. Vui lòng cài và đăng nhập trước!")
            } else {
                FbAutoService.instance?.startProcessing(listOf(FbAutoService.TaskItem(post.id, post.url, tpl)))
                FbAutoService.isRunning.value = true
                toast(context, "Đang xử lý tương tác...")
            }
            pickFor = null
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

@Composable fun SpintaxComposerDialog(onAdd: (String, String, String, List<String>, List<String>, String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cat by remember { mutableStateOf("Chung") }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var imageUrls by remember { mutableStateOf("") }
    var scopeState by remember { mutableStateOf("global") }

    var selectedImageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var isCompressing by remember { mutableStateOf(false) }

    val photoPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()) { uris ->
        selectedImageUris = selectedImageUris + uris
    }

    AlertDialog(
        onDismissRequest = if (isCompressing) { {} } else onDismiss,
        title = { Text("📝 Đóng Góp Bài Mẫu") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(cat, { cat = it }, label = { Text("Danh mục (Vd: Bất Động Sản)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(title, { title = it }, label = { Text("Tiêu đề") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(content, { content = it }, label = { Text("Nội dung bài viết") }, modifier = Modifier.fillMaxWidth().height(150.dp))
                OutlinedTextField(imageUrls, { imageUrls = it }, label = { Text("Link ảnh ngoài (nếu có)") }, modifier = Modifier.fillMaxWidth().height(100.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ảnh từ máy: ${selectedImageUris.size} ảnh")
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { photoPickerLauncher.launch("image/*") }) {
                        Text("Chọn Kèm Ảnh")
                    }
                }
                if (selectedImageUris.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(selectedImageUris) { uri ->
                            AsyncImage(model = uri, contentDescription = null, modifier = Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                        }
                    }
                }
                
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = scopeState == "global", onCheckedChange = { if(it) scopeState = "global" })
                    Text("Lưu làm Bài Chung (Gửi duyệt)", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = scopeState == "personal", onCheckedChange = { if(it) scopeState = "personal" })
                    Text("Lưu nháp Cá nhân (Tự tôi dùng auto)", style = MaterialTheme.typography.bodyMedium)
                }

                Text(if (isCompressing) "Đang nén ảnh..." else "Công cụ Hỗ Trợ (Spintax):", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected=false, onClick={ content += "{PHONE}" }, label={Text("[+] SĐT")}) }
                    item { FilterChip(selected=false, onClick={ content += "{ZALO}" }, label={Text("[+] Zalo")}) }
                    item { FilterChip(selected=false, onClick={ content += "{Đoạn 1|Đoạn 2}" }, label={Text("[+] Trộn Từ")}) }
                }
            }
        },
        confirmButton = {
            Button(enabled = !isCompressing, onClick = {
                val imgs = imageUrls.split("\n").map { it.trim() }.filter { it.startsWith("http") }
                if (content.isNotBlank() && title.isNotBlank()) {
                    isCompressing = true
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val base64List = mutableListOf<String>()
                        selectedImageUris.forEach { uri ->
                            try {
                                val ins = context.contentResolver.openInputStream(uri)
                                val bitmap = android.graphics.BitmapFactory.decodeStream(ins)
                                ins?.close()
                                if (bitmap != null) {
                                    val out = java.io.ByteArrayOutputStream()
                                    val scale = kotlin.math.min(800f / bitmap.width.toFloat(), 800f / bitmap.height.toFloat())
                                    val scaledBitmap = if (scale < 1) android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
                                    scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                                    val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
                                    base64List.add("data:image/jpeg;base64,$b64")
                                }
                            } catch (e: Exception) { e.printStackTrace() }
                        }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            isCompressing = false
                            onAdd(cat, title, content, imgs, base64List, scopeState)
                        }
                    }
                }
            }) { Text(if (scopeState == "global") "Gửi Chờ Duyệt" else "Lưu Bài Cá Nhân") }
        },
        dismissButton = { TextButton(enabled = !isCompressing, onClick = onDismiss) { Text("Huỷ") } }
    )
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
    startActiveHour: Int, onStartActiveHourChange: (Int) -> Unit,
    endActiveHour: Int, onEndActiveHourChange: (Int) -> Unit,
    notifyInterval: Int, onIntervalChange: (Int) -> Unit, 
    autoWakeIntervalHours: Int, onAutoWakeIntervalChange: (Int) -> Unit,
    autoPublishIntervalMinutes: Int, onAutoPublishIntervalChange: (Int) -> Unit,
    onTriggerNow: () -> Unit,
    onTestNotify: () -> Unit,
    onSync: () -> Unit, onRequestPermission: () -> Unit,
    prefs: SharedPreferences, onExplicitSave: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var phone by remember { mutableStateOf(prefs.getString(KEY_PHONE, "") ?: "") }
    var zalo by remember { mutableStateOf(prefs.getString(KEY_ZALO, "") ?: "") }
    var wakeHoursTxt by remember { mutableStateOf(autoWakeIntervalHours.toString()) }
    var publishMinTxt by remember { mutableStateOf(autoPublishIntervalMinutes.toString()) }
    var notifyTxt by remember { mutableStateOf(notifyInterval.toString()) }
    var startHourTxt by remember { mutableStateOf(startActiveHour.toString()) }
    var endHourTxt by remember { mutableStateOf(endActiveHour.toString()) }
    var blockHourTxt by remember { mutableStateOf(prefs.getInt("block_timeout_hours", 24).toString()) }
    var delayTxt by remember { mutableStateOf(prefs.getLong("local_gallery_delay", 0L).let { if (it > 0) it.toString() else "" }) }
    val otaScript = prefs.getString("engine_script", "{}") ?: "{}"
    val otaVersion = try { org.json.JSONObject(otaScript).optString("version", "Chưa tải") } catch(e: Exception) { "?" }
    var selectedOta by remember { mutableStateOf(prefs.getString("selected_ota_version", "latest") ?: "latest") }
    var expandedOta by remember { mutableStateOf(false) }
    val availableVersions = try { val j = org.json.JSONObject(prefs.getString("ota_available_versions", "{}") ?: "{}"); val arr = j.optJSONArray("available"); if (arr != null) { val l = mutableListOf("latest"); for (i in 0 until arr.length()) l.add(arr.getString(i)); l.distinct() } else listOf("latest") } catch(e: Exception) { listOf("latest") }
    val cn = android.content.ComponentName(context, FbNotificationListener::class.java)
    val enabledListeners = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    val isNotifEnabled = enabledListeners != null && enabledListeners.contains(cn.flattenToString())
    var showLogs by remember { mutableStateOf(false) }
    var logsContent by remember { mutableStateOf("") }
    if (showLogs) { AlertDialog(onDismissRequest = { showLogs = false }, title = { Text("Debug Logs") }, text = { Column(Modifier.verticalScroll(rememberScrollState()).fillMaxHeight(0.7f)) { Text(logsContent, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth()) } }, confirmButton = { Button(onClick = { copyToClipboard(context, logsContent); toast(context, "Đã copy!") }) { Text("Copy") } }, dismissButton = { Button(onClick = { showLogs = false }) { Text("Đóng") } }) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(12.dp))
            // == PERMISSIONS ==
            Text("⚡ Quyền Hệ Thống", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("♿ Accessibility:", style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.weight(1f)); Text(if (isServiceEnabled) "✅ Bật" else "❌ Tắt", color = if (isServiceEnabled) Color(0xFF2E7D32) else Color(0xFFB71C1C)); if (!isServiceEnabled) { Spacer(Modifier.width(8.dp)); FilledTonalButton(onClick = onRequestPermission) { Text("Bật", style = MaterialTheme.typography.labelSmall) } } }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { Text("🔔 Đọc Thông Báo:", style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.weight(1f)); Text(if (isNotifEnabled) "✅ Bật" else "❌ Tắt", color = if (isNotifEnabled) Color(0xFF2E7D32) else Color(0xFFB71C1C)); if (!isNotifEnabled) { Spacer(Modifier.width(8.dp)); FilledTonalButton(onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Bật", style = MaterialTheme.typography.labelSmall) } } }
            } }
            Spacer(Modifier.height(16.dp))
            // == SCHEDULING ==
            Text("⏰ Lịch Trình Tự Động", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("Tải từ Server · Tùy chỉnh rồi bấm Lưu ở dưới", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(Modifier.height(8.dp))
            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("🔄 Check bài mới", style = MaterialTheme.typography.bodyMedium); Text("Tự dậy đi comment", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }; OutlinedTextField(value = wakeHoursTxt, onValueChange = { wakeHoursTxt = it }, modifier = Modifier.width(65.dp), singleLine = true); Text(" giờ") }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("📝 Đăng bài nhóm", style = MaterialTheme.typography.bodyMedium); Text("Tự chọn bài mẫu + nhóm", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }; OutlinedTextField(value = publishMinTxt, onValueChange = { publishMinTxt = it }, modifier = Modifier.width(65.dp), singleLine = true); Text(" phút") }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("🔔 Nhắc nhở", style = MaterialTheme.typography.bodyMedium); Text("Push notification", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }; OutlinedTextField(value = notifyTxt, onValueChange = { notifyTxt = it }, modifier = Modifier.width(65.dp), singleLine = true); Text(" phút") }
                Spacer(Modifier.height(10.dp)); Divider(); Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { Text("🕒 Hoạt động từ "); OutlinedTextField(value = startHourTxt, onValueChange = { startHourTxt = it }, modifier = Modifier.width(50.dp), singleLine = true); Text("h → "); OutlinedTextField(value = endHourTxt, onValueChange = { endHourTxt = it }, modifier = Modifier.width(50.dp), singleLine = true); Text("h") }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { Text("🛑 Nghỉ khi Block"); Spacer(Modifier.weight(1f)); OutlinedTextField(value = blockHourTxt, onValueChange = { blockHourTxt = it }, modifier = Modifier.width(65.dp), singleLine = true); Text(" giờ") }
                Spacer(Modifier.height(6.dp)); Text("Nhập 0 để tắt.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFEF4444))
            } }
            Spacer(Modifier.height(16.dp))
            // == PERSONAL ==
            Text("📱 Cá Nhân", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("SĐT (tự trộn vào comment)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = zalo, onValueChange = { zalo = it }, label = { Text("Link Zalo") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            } }
            Spacer(Modifier.height(16.dp))
            // == ADVANCED ==
            Text("🔧 Nâng Cao", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("📡 Script OTA: "); Text(otaVersion, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(8.dp))
                Box { OutlinedButton(onClick = { expandedOta = true }, modifier = Modifier.fillMaxWidth()) { Text("Phiên bản: $selectedOta") }; DropdownMenu(expanded = expandedOta, onDismissRequest = { expandedOta = false }) { availableVersions.forEach { v -> DropdownMenuItem(text = { Text(v) }, onClick = { selectedOta = v; prefs.edit().putString("selected_ota_version", v).apply(); expandedOta = false }) } } }
                Spacer(Modifier.height(10.dp)); Divider(); Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("🐢 Tốc độ chọn ảnh (ms)"); Text("Tăng 3000-5000 để debug", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }; OutlinedTextField(value = delayTxt, onValueChange = { delayTxt = it }, modifier = Modifier.width(80.dp), singleLine = true, placeholder = { Text("Auto") }) }
            } }
            Spacer(Modifier.height(16.dp))
            // == TOOLS ==
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSync, modifier = Modifier.weight(1f), enabled = !isSyncing) { Text(if (isSyncing) "⏳..." else "🔄 Sync") }
                OutlinedButton(onClick = onTestNotify, modifier = Modifier.weight(1f)) { Text("🔔 Test") }
                OutlinedButton(onClick = onTriggerNow, modifier = Modifier.weight(1f)) { Text("📝 Đăng ngay") }
            }
            if (lastSyncStatus.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(lastSyncStatus, style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
            Spacer(Modifier.height(16.dp))
            // == LOGS ==
            Text("🛠 Debug Logs", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { try { logsContent = java.io.File(context.filesDir, "debug_logs.txt").readText(); if (logsContent.isBlank()) logsContent = "Chưa có." } catch (e: Exception) { logsContent = "Chưa có." }; showLogs = true }, modifier = Modifier.weight(1f)) { Text("Xem Logs") }
                OutlinedButton(onClick = { try { java.io.File(context.filesDir, "debug_logs.txt").writeText(""); toast(context, "Đã xóa!") } catch (e: Exception) {} }, modifier = Modifier.weight(1f)) { Text("Xóa Logs") }
            }
            Spacer(Modifier.height(24.dp))
        }
        // == FIXED SAVE ALL BUTTON ==
        Surface(tonalElevation = 8.dp, shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {
                val e = prefs.edit(); e.putString(KEY_PHONE, phone); e.putString(KEY_ZALO, zalo)
                wakeHoursTxt.toIntOrNull()?.let { v -> e.putInt("autowake_interval_hours", v); onAutoWakeIntervalChange(v) }
                publishMinTxt.toIntOrNull()?.let { v -> e.putInt("autopublish_interval_minutes", v); onAutoPublishIntervalChange(v) }
                notifyTxt.toIntOrNull()?.let { v -> e.putInt("notify_interval", v); onIntervalChange(v) }
                startHourTxt.toIntOrNull()?.let { v -> e.putInt("start_active_hour", v); onStartActiveHourChange(v) }
                endHourTxt.toIntOrNull()?.let { v -> e.putInt("end_active_hour", v); onEndActiveHourChange(v) }
                blockHourTxt.toIntOrNull()?.let { v -> e.putInt("block_timeout_hours", v) }
                val ms = delayTxt.toLongOrNull(); if (ms != null && ms > 0) { e.putLong("local_gallery_delay", ms); FbAutoService.Engine.galleryClickDelay = ms } else { e.remove("local_gallery_delay"); FbAutoService.Engine.load(context) }
                e.apply(); onExplicitSave(); toast(context, "✅ Đã lưu toàn bộ!")
            }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))) { Text("💾 LƯU TOÀN BỘ CÀI ĐẶT", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
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
@Composable fun ArticlesScreen(articles: List<Article>, suggestedGroups: List<SuggestedGroup>, prefs: SharedPreferences, authToken: String, onSettingsChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var filterCategory by remember { mutableStateOf<String?>(null) }
    var showProposeDialog by remember { mutableStateOf(false) }
    var showSpintaxDialog by remember { mutableStateOf(false) }
    
    val categories = articles.map { it.category }.distinct().sorted()
    val visible = if (filterCategory != null) articles.filter { it.category == filterCategory } else articles

    if (showSpintaxDialog) {
        SpintaxComposerDialog(
            onAdd = { cat, title, content, imgs, b64s, sc ->
                showSpintaxDialog = false
                scope.launch {
                    toast(context, "Đang tải ảnh lên máy chủ...")
                    val imgJsonArray = org.json.JSONArray(imgs).toString()
                    val b64JsonArray = org.json.JSONArray(b64s).toString()
                    val body = """{"category":"${cat}","title":"${title}","content":"${content.replace("\"", "\\\"").replace("\n", "\\n")}","images":$imgJsonArray,"base64Images":$b64JsonArray,"scope":"$sc"}"""
                    val (code, _) = httpReq("$SERVER_URL/api/articles", "POST", body, authToken)
                    if (code in 200..299) toast(context, "Đã gửi bài mẫu thành công!")
                    else toast(context, "Lỗi khi gửi bài: $code")
                }
            },
            onDismiss = { showSpintaxDialog = false }
        )
    }

    if (showProposeDialog) {
        var newSugUrl by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showProposeDialog = false },
            title = { Text("💡 Đề Xuất Nhóm Gợi Ý") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newSugUrl, onValueChange = {newSugUrl=it}, label = {Text("Link Share Nhóm FB")})
                    Text("Hệ thống sẽ tự động chuyển sang ứng dụng Facebook để lấy Tên và Số lượng thành viên của nhóm rùi gửi về Server.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newSugUrl.isNotBlank()) {
                        FbAutoService.instance?.startScrapingGroup(newSugUrl)
                        showProposeDialog = false
                    } else toast(context, "Vui lòng nhập Link nhóm FB!")
                }) { Text("Lấy Thông Tin") }
            },
            dismissButton = { TextButton({ showProposeDialog = false }) { Text("Đóng") } }
        )
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (categories.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    item { FilterChip(filterCategory == null, { filterCategory = null }, label = { Text("Tất cả") }) }
                    items(categories) { cat -> FilterChip(filterCategory == cat, { filterCategory = cat }, label = { Text(cat) }) }
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { showSpintaxDialog = true }) { Text("📝 Viết Bài") }
            }
            Spacer(Modifier.height(12.dp))
        } else {
            Button(onClick = { showSpintaxDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("📝 Viết Bài Mẫu Mới") }
            Spacer(Modifier.height(12.dp))
        }

        var groupLinks by remember { mutableStateOf(prefs.getString("publish_groups", "") ?: "") }
        var selectedArticleIds by remember { mutableStateOf(prefs.getString("selected_article_ids", "")?.split(",")?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()) }
        OutlinedTextField(
            value = groupLinks,
            onValueChange = { groupLinks = it; prefs.edit().putString("publish_groups", it).apply(); onSettingsChanged() },
            label = { Text("Link Nhóm Zalo/Facebook cần auto đăng bài (Mỗi dòng 1 link)") },
            modifier = Modifier.fillMaxWidth().height(90.dp),
            maxLines = 5,
            singleLine = false
        )
        Spacer(Modifier.height(8.dp))
        
        var expandedGroups by remember { mutableStateOf(false) }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { expandedGroups = !expandedGroups }.padding(vertical = 8.dp)) {
            Text("🎯 Nhóm Gợi Ý (${suggestedGroups.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showProposeDialog = true }) { Text("💡 Đề xuất thêm", style = MaterialTheme.typography.labelSmall) }
            Text(if (expandedGroups) "▲" else "▼", color = Color.Gray, modifier = Modifier.padding(start = 8.dp))
        }

        androidx.compose.animation.AnimatedVisibility(visible = expandedGroups) {
            if (suggestedGroups.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestedGroups.forEach { g ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(g.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    Text("👥 ${if(g.memberCount.isBlank()) "0" else g.memberCount} TV", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                                OutlinedButton(onClick = {
                                    val newLinks = if (groupLinks.isBlank()) g.url else groupLinks + "\n" + g.url
                                    groupLinks = newLinks
                                    prefs.edit().putString("publish_groups", newLinks).apply()
                                    toast(context, "Đã thêm ${g.name}!")
                                }) { Text("➕ Lấy", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            } else {
                Text("Chưa có nhóm nào được duyệt.", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
        Spacer(Modifier.height(12.dp))

        if (visible.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { Text("Chưa có bài mẫu.") }
        } else {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val allVisibleIds = visible.map { it.id }.toSet()
                val allSelected = allVisibleIds.all { selectedArticleIds.contains(it) }
                OutlinedButton(onClick = {
                    val newSet = selectedArticleIds.toMutableSet()
                    if (allSelected) newSet.removeAll(allVisibleIds) else newSet.addAll(allVisibleIds)
                    selectedArticleIds = newSet
                    prefs.edit().putString("selected_article_ids", newSet.joinToString(",")).apply()
                    onSettingsChanged()
                }, modifier = Modifier.weight(1f)) {
                    Text(if (allSelected) "❌ Bỏ chọn tất cả" else "✅ Chọn tất cả (${visible.size})")
                }
                Text("\uD83D\uDCCB Đã chọn: ${selectedArticleIds.size}/${articles.size}", style = MaterialTheme.typography.labelMedium, color = Color.Gray, modifier = Modifier.align(Alignment.CenterVertically))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.id }) { art ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            val isPersonal = art.scope == "personal"
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isPersonal) {
                                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(4.dp)) {
                                        Text("Cá Nhân", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp), color=MaterialTheme.colorScheme.onSecondaryContainer)
                                    }
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text("[${art.category}] ${art.title}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
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
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().clickable {
                                val newSet = selectedArticleIds.toMutableSet()
                                if (selectedArticleIds.contains(art.id)) newSet.remove(art.id) else newSet.add(art.id)
                                selectedArticleIds = newSet
                                prefs.edit().putString("selected_article_ids", newSet.joinToString(",")).apply()
                                onSettingsChanged()
                            }) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal=8.dp, vertical=4.dp)) {
                                    Checkbox(checked = selectedArticleIds.contains(art.id), onCheckedChange = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Gán vào Robot Auto (Hẹn Giờ)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.weight(1f))
                                    if (art.status == "pending") Text("⏳ Chờ Admin duyệt", style = MaterialTheme.typography.labelSmall, color = Color(0xFFF59E0B))
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row {
                                FilledTonalButton(onClick = { copyToClipboard(context, applySpintaxAndVars(art.content, prefs)); toast(context, "Đã copy nội dung") }) { Text("Copy", style = MaterialTheme.typography.bodySmall) }
                                Spacer(Modifier.width(8.dp))
                                FilledTonalButton(
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    onClick = { 
                                        if (groupLinks.isBlank()) { toast(context, "Cần dán link Nhóm ở trên trước!"); return@FilledTonalButton }
                                        val links = groupLinks.split("\n").map{it.trim()}.filter{it.startsWith("http")}
                                        if (links.isEmpty()) { toast(context, "Không có link hợp lệ!"); return@FilledTonalButton }
                                        
                                        scope.launch {
                                            if (art.images.isNotEmpty()) {
                                                toast(context, "Đang bung nén Album ảnh...\nXin đừng khóa màn hình!")
                                                downloadImages(context, art.images)
                                            }
                                            FbAutoService.instance?.startPublishing(
                                                applySpintaxAndVars(art.content, prefs), art.images, links
                                            )
                                            toast(context, "Đã chạy Robot ném ${art.images.size} ảnh vào ${links.size} nhóm 🚀")
                                        }
                                    }
                                ) { Text("Đăng Group 🚀", style = MaterialTheme.typography.bodySmall, color = Color.White) }
                                Spacer(Modifier.weight(1f))
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
            successCount == urls.size -> "✓ Đã đưa $successCount/${urls.size} ảnh vào thư viện"
            successCount > 0 -> "Tải $successCount/${urls.size}. Lỗi: ${errors.firstOrNull() ?: ""}"
            else -> "Tải thất bại: ${errors.firstOrNull() ?: "không rõ"}"
        }
        toast(context, msg)
    }
}

private fun downloadViaMediaStore(context: Context, url: String, index: Int): Boolean {
    return try {
        val ext = if (url.contains("png")) "png" else "jpg"
        val fileName = "CommentHelper_${System.currentTimeMillis()}_$index.$ext"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/$ext")
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
            resolver.openOutputStream(itemUri)?.use { out ->
                if (url.startsWith("data:image")) {
                    // Xử lý base64 string
                    val base64Data = url.substringAfter(",")
                    val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                    out.write(decodedBytes)
                } else {
                    // Xử lý pure URL
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000; conn.readTimeout = 30000
                    conn.connect()
                    if (conn.responseCode !in 200..299) { throw Exception("HTTP ${conn.responseCode}") }
                    conn.inputStream.use { input -> input.copyTo(out) }
                }
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
