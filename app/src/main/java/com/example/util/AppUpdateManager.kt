package com.example.util

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.example.data.model.AppVersionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

object AppUpdateManager {

  const val DEFAULT_VERSION_URL = "https://raw.githubusercontent.com/dasakash24059/TaskFlow/main/version.json"

  fun getInstalledVersionCode(context: Context): Int {
    return try {
      val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        pInfo.longVersionCode.toInt()
      } else {
        @Suppress("DEPRECATION")
        pInfo.versionCode
      }
    } catch (e: Exception) {
      1
    }
  }

  suspend fun checkForUpdate(context: Context, versionJsonUrl: String = DEFAULT_VERSION_URL): Result<AppVersionInfo?> = withContext(Dispatchers.IO) {
    try {
      if (!versionJsonUrl.lowercase().startsWith("https://")) {
        return@withContext Result.failure(SecurityException("Update URL must use HTTPS"))
      }
      val url = URL(versionJsonUrl)
      val connection = (url.openConnection() as HttpsURLConnection).apply {
        connectTimeout = 10000
        readTimeout = 10000
        requestMethod = "GET"
      }

      val responseCode = connection.responseCode
      if (responseCode != 200) {
        return@withContext Result.failure(Exception("Failed to fetch version info: HTTP $responseCode"))
      }

      val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
      connection.disconnect()

      val json = JSONObject(jsonString)
      val versionCode = json.optInt("versionCode", 1)
      val versionName = json.optString("versionName", "1.0")
      val apkUrl = json.optString("apkUrl", "")
      val releaseNotes = json.optString("releaseNotes", "")
      val forceUpdate = json.optBoolean("forceUpdate", false)

      if (!apkUrl.lowercase().startsWith("https://")) {
        return@withContext Result.failure(SecurityException("APK URL must use HTTPS"))
      }

      val versionInfo = AppVersionInfo(
        versionCode = versionCode,
        versionName = versionName,
        apkUrl = apkUrl,
        releaseNotes = releaseNotes,
        forceUpdate = forceUpdate
      )

      val installedCode = getInstalledVersionCode(context)
      if (versionInfo.versionCode > installedCode) {
        Result.success(versionInfo)
      } else {
        Result.success(null)
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  fun downloadAndInstallApk(context: Context, apkUrl: String): Long {
    val destination = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "TaskFlow_Update.apk")
    if (destination.exists()) {
      destination.delete()
    }

    val request = DownloadManager.Request(Uri.parse(apkUrl))
      .setTitle("TaskFlow Update")
      .setDescription("Downloading latest version...")
      .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
      .setDestinationUri(Uri.fromFile(destination))
      .setAllowedOverMetered(true)
      .setAllowedOverRoaming(true)

    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    return manager.enqueue(request)
  }

  fun installDownloadedApk(context: Context): Boolean {
    val destination = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "TaskFlow_Update.apk")
    if (!destination.exists() || destination.length() == 0L) {
      return false
    }

    if (context.packageName != "com.aistudio.taskflow.kxmpzq") {
      return false
    }

    val uri: Uri = FileProvider.getUriForFile(
      context,
      "${context.packageName}.fileprovider",
      destination
    )

    val intent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, "application/vnd.android.package-archive")
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    return try {
      context.startActivity(intent)
      true
    } catch (e: Exception) {
      false
    }
  }
}
