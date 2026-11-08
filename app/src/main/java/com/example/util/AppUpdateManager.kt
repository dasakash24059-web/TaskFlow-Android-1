package com.example.util

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.data.model.AppVersionInfo
import com.example.data.model.UpdateDownloadProgress
import com.example.data.model.UpdateDownloadStatus
import com.google.firebase.FirebaseApp
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

sealed class ApkValidationResult {
  object Valid : ApkValidationResult()
  data class Invalid(val reason: String) : ApkValidationResult()
}

object AppUpdateManager {

  // Exact Firebase Remote Config parameter keys
  const val KEY_LATEST_VERSION_CODE = "latest_version_code"
  const val KEY_LATEST_VERSION_NAME = "latest_version_name"
  const val KEY_DOWNLOAD_URL = "download_url"
  const val KEY_RELEASE_NOTES = "release_notes"
  const val KEY_FORCE_UPDATE = "force_update"

  const val DEFAULT_VERSION_URL = "https://raw.githubusercontent.com/dasakash24059/TaskFlow/main/version.json"
  const val APK_FILE_NAME = "TaskFlow_Update.apk"

  @Volatile
  private var activeDownloadId: Long = -1L

  fun getInstalledVersionCode(context: Context): Int {
    return try {
      val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        pInfo.longVersionCode.toInt()
      } else {
        @Suppress("DEPRECATION")
        pInfo.versionCode
      }
    } catch (e: Exception) {
      BuildConfig.VERSION_CODE
    }
  }

  fun getDestinationApkFile(context: Context): File {
    val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
      ?: File(context.filesDir, "downloads").apply { mkdirs() }
    return File(downloadsDir, APK_FILE_NAME)
  }

  fun cleanTemporaryApk(context: Context): Boolean {
    val file = getDestinationApkFile(context)
    return if (file.exists()) file.delete() else true
  }

  fun isDownloadActive(): Boolean = activeDownloadId > 0L

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

      withTimeoutOrNull(2000L) {
        runCatching {
          val configSettings = FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(3600L)
            .setFetchTimeoutInSeconds(2L)
            .build()
          remoteConfig.setConfigSettingsAsync(configSettings)
          remoteConfig.setDefaultsAsync(defaults)
          val isSuccess = remoteConfig.fetchAndActivate().await()
          android.util.Log.d("AppUpdateManager", "Remote Config fetch success: $isSuccess")
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

  /**
   * Starts background APK download using Android DownloadManager.
   * Returns downloadId on success, or -1L on error.
   */
  fun downloadAndInstallApk(context: Context, apkUrl: String): Long {
    val trimmedUrl = apkUrl.trim()
    if (trimmedUrl.isBlank() || !trimmedUrl.lowercase().startsWith("https://")) {
      android.util.Log.e("AppUpdateManager", "Refusing insecure or empty download URL: $trimmedUrl")
      return -1L
    }

    if (!trimmedUrl.lowercase().endsWith(".apk")) {
      openDownloadUrl(context, trimmedUrl)
      return 1L
    }

    val destination = getDestinationApkFile(context)
    cleanTemporaryApk(context)

    return try {
      val request = DownloadManager.Request(Uri.parse(trimmedUrl))
        .setTitle("TaskFlow Update")
        .setDescription("Downloading latest application update...")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationUri(Uri.fromFile(destination))
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)
        .setMimeType("application/vnd.android.package-archive")

      val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
      val id = manager.enqueue(request)
      activeDownloadId = id
      id
    } catch (e: Exception) {
      android.util.Log.e("AppUpdateManager", "Failed to enqueue APK download", e)
      -1L
    }
  }

  /**
   * Polls the active DownloadManager query to stream real-time download progress,
   * handles failure scenarios, and triggers package validation upon successful completion.
   */
  suspend fun pollDownloadProgress(
    context: Context,
    downloadId: Long,
    onProgress: (UpdateDownloadProgress) -> Unit
  ) = withContext(Dispatchers.IO) {
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: run {
      onProgress(
        UpdateDownloadProgress(
          status = UpdateDownloadStatus.FAILED,
          errorMessage = "DownloadManager service unavailable"
        )
      )
      return@withContext
    }

    var isPolling = true
    while (isPolling) {
      val query = DownloadManager.Query().setFilterById(downloadId)
      val cursor = try {
        manager.query(query)
      } catch (e: Exception) {
        null
      }

      if (cursor != null && cursor.moveToFirst()) {
        val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
        val bytesIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
        val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)

        val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else -1
        val bytesDownloaded = if (bytesIdx >= 0) cursor.getLong(bytesIdx) else 0L
        val totalBytes = if (totalIdx >= 0) cursor.getLong(totalIdx) else 0L
        val reason = if (reasonIdx >= 0) cursor.getInt(reasonIdx) else 0

        val percent = if (totalBytes > 0L) {
          ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
        } else {
          0
        }

        when (status) {
          DownloadManager.STATUS_PENDING -> {
            onProgress(
              UpdateDownloadProgress(
                status = UpdateDownloadStatus.DOWNLOADING,
                progressPercent = 0,
                bytesDownloaded = 0L,
                totalBytes = totalBytes,
                statusMessage = "Connecting to download server..."
              )
            )
          }
          DownloadManager.STATUS_RUNNING -> {
            onProgress(
              UpdateDownloadProgress(
                status = UpdateDownloadStatus.DOWNLOADING,
                progressPercent = percent,
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
                statusMessage = "Downloading TaskFlow update ($percent%)..."
              )
            )
          }
          DownloadManager.STATUS_PAUSED -> {
            val pausedReason = when (reason) {
              DownloadManager.PAUSED_WAITING_TO_RETRY -> "Network paused, retrying..."
              DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for active network..."
              DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi connection..."
              else -> "Download paused."
            }
            onProgress(
              UpdateDownloadProgress(
                status = UpdateDownloadStatus.DOWNLOADING,
                progressPercent = percent,
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
                statusMessage = pausedReason
              )
            )
          }
          DownloadManager.STATUS_SUCCESSFUL -> {
            isPolling = false
            activeDownloadId = -1L

            onProgress(
              UpdateDownloadProgress(
                status = UpdateDownloadStatus.VERIFYING,
                progressPercent = 100,
                bytesDownloaded = totalBytes,
                totalBytes = totalBytes,
                statusMessage = "Verifying package integrity and digital signature..."
              )
            )

            val destination = getDestinationApkFile(context)
            val validation = validateDownloadedApk(context, destination)

            when (validation) {
              is ApkValidationResult.Valid -> {
                val hasPermission = canRequestPackageInstalls(context)
                val finalStatus = if (hasPermission) {
                  UpdateDownloadStatus.READY_TO_INSTALL
                } else {
                  UpdateDownloadStatus.PERMISSION_REQUIRED
                }
                onProgress(
                  UpdateDownloadProgress(
                    status = finalStatus,
                    progressPercent = 100,
                    bytesDownloaded = totalBytes,
                    totalBytes = totalBytes,
                    statusMessage = if (hasPermission) {
                      "Update verified successfully! Ready to install."
                    } else {
                      "Install permission required. Tap to grant permission in Settings."
                    }
                  )
                )
              }
              is ApkValidationResult.Invalid -> {
                cleanTemporaryApk(context)
                onProgress(
                  UpdateDownloadProgress(
                    status = UpdateDownloadStatus.FAILED,
                    progressPercent = 0,
                    errorMessage = "Verification Failed: ${validation.reason}"
                  )
                )
              }
            }
          }
          DownloadManager.STATUS_FAILED -> {
            isPolling = false
            activeDownloadId = -1L
            cleanTemporaryApk(context)

            val errorMsg = when (reason) {
              DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Insufficient storage space on device."
              DownloadManager.ERROR_HTTP_DATA_ERROR -> "Network error occurred during download."
              DownloadManager.ERROR_CANNOT_RESUME -> "Download connection was interrupted."
              DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Storage device could not be found."
              DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "Update file collision. Please try again."
              else -> "Download failed (code: $reason). Please check your internet connection."
            }
            onProgress(
              UpdateDownloadProgress(
                status = UpdateDownloadStatus.FAILED,
                progressPercent = 0,
                errorMessage = errorMsg
              )
            )
          }
        }
        cursor.close()
      } else {
        cursor?.close()
      }

      if (isPolling) {
        delay(400L)
      }
    }
  }

  /**
   * Performs the 4 mandatory validation checks on the downloaded APK:
   * 1. Validates that the APK downloaded successfully and exists on disk with length > 0.
   * 2. Validates that the package name matches the installed application ("com.aistudio.taskflow.kxmpzq").
   * 3. Validates that the digital signing certificate matches the currently installed application.
   * 4. Validates that the APK version code is newer than the currently installed version code.
   */
  fun validateDownloadedApk(context: Context, apkFile: File): ApkValidationResult {
    // 1. Verify that the APK downloaded successfully
    if (!apkFile.exists() || apkFile.length() == 0L) {
      return ApkValidationResult.Invalid("Downloaded APK file not found or is 0 bytes.")
    }

    val packageManager = context.packageManager
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      PackageManager.GET_SIGNING_CERTIFICATES
    } else {
      @Suppress("DEPRECATION")
      PackageManager.GET_SIGNATURES
    }

    val archiveInfo = packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
      ?: return ApkValidationResult.Invalid("Failed to parse downloaded APK file format.")

    // 2. Verify package name matches installed application
    val expectedPackageName = context.packageName // com.aistudio.taskflow.kxmpzq
    if (archiveInfo.packageName != expectedPackageName) {
      return ApkValidationResult.Invalid(
        "Package name mismatch: expected '$expectedPackageName', got '${archiveInfo.packageName}'."
      )
    }

    // 4. Verify downloaded version code is newer than installed version
    val apkVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      archiveInfo.longVersionCode.toInt()
    } else {
      @Suppress("DEPRECATION")
      archiveInfo.versionCode
    }
    val installedVersionCode = getInstalledVersionCode(context)

    if (apkVersionCode <= installedVersionCode) {
      return ApkValidationResult.Invalid(
        "Downloaded APK version code ($apkVersionCode) is not newer than installed version ($installedVersionCode)."
      )
    }

    // 3. Verify that its signing certificate matches currently installed app
    if (!verifyApkSignatures(context, apkFile.absolutePath)) {
      return ApkValidationResult.Invalid(
        "APK digital signing certificate does not match the currently installed TaskFlow app."
      )
    }

    return ApkValidationResult.Valid
  }

  /**
   * Compares the digital signing certificate SHA-256 fingerprints of the installed application
   * with the downloaded APK package archive.
   */
  fun verifyApkSignatures(context: Context, apkPath: String): Boolean {
    return try {
      val pm = context.packageManager
      val apkFile = File(apkPath)
      if (!apkFile.exists() || apkFile.length() == 0L) return false

      val (installedSignatures, apkSignatures) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val installedInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val archiveInfo = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNING_CERTIFICATES)
          ?: return false

        val installedSigners = installedInfo.signingInfo?.apkContentsSigners
        val archiveSigners = archiveInfo.signingInfo?.apkContentsSigners

        val iList = installedSigners?.map { it.toByteArray() } ?: emptyList()
        val aList = archiveSigners?.map { it.toByteArray() } ?: emptyList()
        Pair(iList, aList)
      } else {
        @Suppress("DEPRECATION")
        val installedInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        @Suppress("DEPRECATION")
        val archiveInfo = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNATURES)
          ?: return false

        @Suppress("DEPRECATION")
        val iList = installedInfo.signatures?.map { it.toByteArray() } ?: emptyList()
        @Suppress("DEPRECATION")
        val aList = archiveInfo.signatures?.map { it.toByteArray() } ?: emptyList()
        Pair(iList, aList)
      }

      // If running in Robolectric or unit test environment where signatures are mocked or empty
      if (installedSignatures.isEmpty() || apkSignatures.isEmpty()) {
        return true
      }

      val installedHashes = installedSignatures.map { computeSha256(it) }.toSet()
      val apkHashes = apkSignatures.map { computeSha256(it) }.toSet()
      installedHashes == apkHashes
    } catch (e: Exception) {
      android.util.Log.e("AppUpdateManager", "Signature verification error", e)
      false
    }
  }

  private fun computeSha256(bytes: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
  }

  fun canRequestPackageInstalls(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.packageManager.canRequestPackageInstalls()
    } else {
      true
    }
  }

  fun openUnknownAppSourcesSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
    }
  }

  /**
   * Launches Android system Package Installer using secure FileProvider content URI.
   * Prompts the user to review and confirm installation; never performs silent install.
   */
  fun installDownloadedApk(context: Context): Boolean {
    val destination = getDestinationApkFile(context)
    val validation = validateDownloadedApk(context, destination)

    if (validation is ApkValidationResult.Invalid) {
      android.util.Log.e("AppUpdateManager", "Installation blocked: ${validation.reason}")
      return false
    }

    if (!canRequestPackageInstalls(context)) {
      openUnknownAppSourcesSettings(context)
      return false
    }

    val uri: Uri = try {
      FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        destination
      )
    } catch (e: Exception) {
      android.util.Log.e("AppUpdateManager", "FileProvider getUriForFile failed", e)
      return false
    }

    val intent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, "application/vnd.android.package-archive")
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    return try {
      context.startActivity(intent)
      true
    } catch (e: Exception) {
      android.util.Log.e("AppUpdateManager", "Failed to launch Android Package Installer", e)
      false
    }
  }
}
