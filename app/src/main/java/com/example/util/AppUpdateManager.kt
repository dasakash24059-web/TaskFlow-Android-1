package com.example.util

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.data.model.AppVersionInfo
import com.google.firebase.FirebaseApp
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

object AppUpdateManager {

  // Exact Firebase Remote Config parameter keys
  const val KEY_LATEST_VERSION_CODE = "latest_version_code"
  const val KEY_LATEST_VERSION_NAME = "latest_version_name"
  const val KEY_DOWNLOAD_URL = "download_url"
  const val KEY_RELEASE_NOTES = "release_notes"
  const val KEY_FORCE_UPDATE = "force_update"

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
      BuildConfig.VERSION_CODE
    }
  }

  /**
   * Asynchronously fetches version info using Firebase Remote Config.
   * Compares installed BuildConfig.VERSION_CODE with remote "latest_version_code".
   * Returns AppVersionInfo if remote version code > installed version code, otherwise null.
   */
  suspend fun checkForRemoteConfigUpdate(context: Context): Result<AppVersionInfo?> = withContext(Dispatchers.IO) {
    try {
      if (FirebaseApp.getApps(context).isEmpty()) {
        return@withContext checkForUpdateViaFallbackJson(context)
      }

      val remoteConfig = try {
        FirebaseRemoteConfig.getInstance()
      } catch (e: Exception) {
        return@withContext checkForUpdateViaFallbackJson(context)
      }

      val installedCode = getInstalledVersionCode(context)
      val defaults = mapOf<String, Any>(
        KEY_LATEST_VERSION_CODE to installedCode,
        KEY_LATEST_VERSION_NAME to (BuildConfig.VERSION_NAME ?: "1.0"),
        KEY_DOWNLOAD_URL to "",
        KEY_RELEASE_NOTES to "",
        KEY_FORCE_UPDATE to false
      )

      // Fetch with timeout protection to prevent blocking UI or test runners
      withTimeoutOrNull(2000L) {
        runCatching {
          val configSettings = FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(3600L)
            .setFetchTimeoutInSeconds(2L)
            .build()
          remoteConfig.setConfigSettingsAsync(configSettings)
          remoteConfig.setDefaultsAsync(defaults)
          remoteConfig.fetchAndActivate().await()
        }
      }

      val latestVersionCode = remoteConfig.getLong(KEY_LATEST_VERSION_CODE).toInt()
      val latestVersionName = remoteConfig.getString(KEY_LATEST_VERSION_NAME)
      val downloadUrl = remoteConfig.getString(KEY_DOWNLOAD_URL)
      val releaseNotes = remoteConfig.getString(KEY_RELEASE_NOTES)
      val forceUpdate = remoteConfig.getBoolean(KEY_FORCE_UPDATE)

      if (latestVersionCode > installedCode) {
        val versionInfo = AppVersionInfo(
          versionCode = latestVersionCode,
          versionName = latestVersionName.ifBlank { "1.0" },
          apkUrl = downloadUrl,
          releaseNotes = releaseNotes,
          forceUpdate = forceUpdate
        )
        Result.success(versionInfo)
      } else {
        Result.success(null)
      }
    } catch (e: Exception) {
      checkForUpdateViaFallbackJson(context)
    }
  }

  suspend fun checkForUpdateViaFallbackJson(context: Context, versionJsonUrl: String = DEFAULT_VERSION_URL): Result<AppVersionInfo?> = withContext(Dispatchers.IO) {
    try {
      if (!versionJsonUrl.lowercase().startsWith("https://")) {
        return@withContext Result.failure(SecurityException("Update URL must use HTTPS"))
      }
      val url = URL(versionJsonUrl)
      val connection = (url.openConnection() as HttpsURLConnection).apply {
        connectTimeout = 3000
        readTimeout = 3000
        requestMethod = "GET"
      }

      val responseCode = connection.responseCode
      if (responseCode != 200) {
        return@withContext Result.failure(Exception("Failed to fetch version info: HTTP $responseCode"))
      }

      val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
      connection.disconnect()

      val json = JSONObject(jsonString)
      val versionCode = json.optInt("latest_version_code", json.optInt("versionCode", 1))
      val versionName = json.optString("latest_version_name", json.optString("versionName", "1.0"))
      val apkUrl = json.optString("download_url", json.optString("apkUrl", ""))
      val releaseNotes = json.optString("release_notes", json.optString("releaseNotes", ""))
      val forceUpdate = json.optBoolean("force_update", json.optBoolean("forceUpdate", false))

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

  suspend fun checkForUpdate(context: Context): Result<AppVersionInfo?> {
    return checkForRemoteConfigUpdate(context)
  }

  fun openDownloadUrl(context: Context, downloadUrl: String): Boolean {
    if (downloadUrl.isBlank()) return false
    return try {
      val uri = Uri.parse(downloadUrl)
      val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      true
    } catch (e: Exception) {
      false
    }
  }

  fun downloadAndInstallApk(context: Context, apkUrl: String): Long {
    if (apkUrl.isBlank() || !apkUrl.lowercase().startsWith("https://")) {
      return -1L
    }

    // Direct install if not a direct APK download
    if (!apkUrl.lowercase().endsWith(".apk")) {
      openDownloadUrl(context, apkUrl)
      return 1L
    }

    val destination = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "TaskFlow_Update.apk")
    if (destination.exists()) {
      destination.delete()
    }

    return try {
      val request = DownloadManager.Request(Uri.parse(apkUrl))
        .setTitle("TaskFlow Update")
        .setDescription("Downloading latest version...")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationUri(Uri.fromFile(destination))
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)
        .setMimeType("application/vnd.android.package-archive")

      val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
      manager.enqueue(request)
    } catch (e: Exception) {
      -1L
    }
  }

  fun installDownloadedApk(context: Context): Boolean {
    val destination = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "TaskFlow_Update.apk")
    if (!destination.exists() || destination.length() == 0L) {
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
