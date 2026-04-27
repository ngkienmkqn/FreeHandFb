package com.example.commenthelper

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.InputStreamReader
import java.io.BufferedReader
import java.util.Calendar

class AutoPublishWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val SERVER_URL = "http://dt.ungthien.com"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("comment_helper_prefs", Context.MODE_PRIVATE)
        val startHour = prefs.getInt("start_active_hour", 7)
        val endHour = prefs.getInt("end_active_hour", 23)
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (currentHour < startHour || currentHour >= endHour) {
            Log.d("AutoPublishWorker", "Outside operating hours ($startHour - $endHour). Sleeping.")
            return@withContext Result.success()
        }

        val blockTimeout = prefs.getLong("block_timeout_epoch", 0L)
        if (System.currentTimeMillis() < blockTimeout) {
            Log.w("AutoPublishWorker", "Currently serving Facebook Sandbox Blockade. Aborting publish cycle until epoch $blockTimeout")
            return@withContext Result.success() // Fail cleanly so we don't retry and trigger more warnings
        }

        Log.d("AutoPublishWorker", "Waking up to perform auto-publish...")
        
        val token = prefs.getString("auth_token", null) ?: return@withContext Result.failure()
        val username = prefs.getString("username", "") ?: ""
        
        // 1. Fetch Approved Groups
        val groupsRes = httpReq("$SERVER_URL/api/suggested-groups", "GET", null, token)
        if (groupsRes.first != 200 || groupsRes.second.isNullOrBlank()) return@withContext Result.retry()
        
        val allGroups = mutableListOf<String>()
        try {
            val arr = JSONObject(groupsRes.second!!).getJSONArray("approved")
            for (i in 0 until arr.length()) {
                allGroups.add(arr.getJSONObject(i).getString("url"))
            }
        } catch (e: Exception) { return@withContext Result.failure() }

        if (allGroups.isEmpty()) return@withContext Result.success()

        // 2. Fetch User's Today History
        val historyRes = httpReq("$SERVER_URL/api/posts", "GET", null, token)
        val postedFbGroupCounts = mutableMapOf<String, Int>()
        val startOfDay = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        var todayTotalPosts = 0

        try {
            if (historyRes.first == 200 && !historyRes.second.isNullOrBlank()) {
                val arr = JSONArray(historyRes.second!!)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("addedBy") == username && o.optLong("addedAt", 0) >= startOfDay) {
                        todayTotalPosts++
                        val urlMatch = Regex("/groups/([0-9a-zA-Z.]+)/?").find(o.getString("url"))
                        urlMatch?.let { 
                            val gId = it.groupValues[1]
                            postedFbGroupCounts[gId] = (postedFbGroupCounts[gId] ?: 0) + 1
                        }
                    }
                }
            }
        } catch (e: Exception) {}

        if (todayTotalPosts >= 20) {
            Log.d("AutoPublishWorker", "Global daily quota of 20 reached. Going back to sleep.")
            return@withContext Result.success()
        }

        // Fetch Global App Settings (Max per Group)
        var maxGroupPosts = 1
        val settingsRes = httpReq("$SERVER_URL/api/settings", "GET", null, token)
        if (settingsRes.first == 200 && !settingsRes.second.isNullOrBlank()) {
            try { maxGroupPosts = JSONObject(settingsRes.second!!).optInt("maxGroupPostsPerDay", 1) } catch(e: Exception) {}
        }

        // 3. Filter group URLs verifying usage <= Max Posts Configuration
        val targetGroups = allGroups.filter { url ->
            val match = Regex("/groups/([0-9a-zA-Z.]+)/?").find(url)
            val groupId = match?.groupValues?.get(1)
            groupId == null || (postedFbGroupCounts[groupId] ?: 0) < maxGroupPosts
        }

        if (targetGroups.isEmpty()) return@withContext Result.success()
        val groupToPost = targetGroups.random()

        // 4. Fetch Articles & Pick Random
        val articlesRes = httpReq("$SERVER_URL/api/articles", "GET", null, token)
        if (articlesRes.first != 200 || articlesRes.second.isNullOrBlank()) return@withContext Result.retry()
        
        try {
            val arr = JSONArray(articlesRes.second!!)
            if (arr.length() == 0) return@withContext Result.success()
            val pick = arr.getJSONObject((0 until arr.length()).random())
            val content = pick.getString("content")
            
            val images = mutableListOf<String>()
            val imgArr = pick.optJSONArray("images")
            if (imgArr != null) {
                for (i in 0 until imgArr.length()) images.add(imgArr.getString(i))
            }

            // Apply spintax dynamically (Duplicate logic from MainActivity)
            var finalContent = content.replace("{PHONE}", prefs.getString("user_phone", "") ?: "")
                .replace("{ZALO}", prefs.getString("user_zalo", "") ?: "")
            val spintaxRegex = Regex("\\{([^\\{\\}]+)\\}")
            while (finalContent.contains(spintaxRegex)) {
                finalContent = finalContent.replace(spintaxRegex) { m -> m.groupValues[1].split("|").random() }
            }

            Log.d("AutoPublishWorker", "Selected group for today: $groupToPost")

            // Wait until screen turns on, then wake the Service via Intent
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("EXTRA_AUTO_PUBLISH", true)
                putExtra("EXTRA_TEXT", finalContent)
                putExtra("EXTRA_GROUPS", arrayOf(groupToPost))
                putStringArrayListExtra("EXTRA_IMAGES", ArrayList(images))
            }
            context.startActivity(launchIntent)

            return@withContext Result.success()
        } catch (e: Exception) {
            return@withContext Result.failure()
        }
    }

    private fun httpReq(url: String, method: String = "GET", json: String? = null, token: String? = null): Pair<Int, String?> {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (json != null) { conn.doOutput = true; java.io.OutputStreamWriter(conn.outputStream).use { it.write(json) } }
            val code = conn.responseCode
            val body = try { BufferedReader(InputStreamReader(if (code in 200..299) conn.inputStream else conn.errorStream)).use { it.readText() } } catch (e: Exception) { null }
            code to body
        } catch (e: Exception) { -1 to e.message }
    }
}
