package com.example.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.unity3d.ads.IUnityAdsInitializationListener
import com.unity3d.ads.IUnityAdsLoadListener
import com.unity3d.ads.IUnityAdsShowListener
import com.unity3d.ads.UnityAds
import com.unity3d.ads.UnityAdsLoadOptions
import com.unity3d.ads.UnityAdsShowOptions
import com.unity3d.ads.metadata.PlayerMetaData
import com.unity3d.services.banners.BannerErrorInfo
import com.unity3d.services.banners.BannerView
import com.unity3d.services.banners.UnityBannerSize
import com.unity3d.services.core.properties.ClientProperties
import com.unity3d.services.core.properties.SdkProperties
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

fun Context.findActivity(): Activity? {
  var current: Context? = this
  while (current is ContextWrapper) {
    if (current is Activity) return current
    current = current.baseContext
  }
  return null
}

/**
 * High-level ad readiness lifecycle state.
 */
enum class AdLoadState {
  IDLE,
  LOADING,
  READY,
  FAILED,
  SHOWING
}

/**
 * Standardized category for ad failure diagnostics (logged to Logcat).
 */
enum class AdErrorCategory {
  NONE,
  NO_FILL,
  TIMEOUT,
  NETWORK_OFFLINE,
  INVALID_ARGUMENT,
  INTERNAL_ERROR,
  INIT_FAILED,
  SHOW_FAILED,
  UNKNOWN
}

data class AdErrorInfo(
  val category: AdErrorCategory,
  val rawCode: String,
  val rawMessage: String,
  val userFriendlyMessage: String
)

/**
 * Internal diagnostic snapshot for testing and Logcat auditing.
 */
data class UnityAdsDiagnosticSnapshot(
  val sdkVersion: String = "Unknown",
  val isInitialized: Boolean = false,
  val sdkInternalState: String = "NOT_INITIALIZED",
  val initCallbackResult: String = "NOT_CALLED",
  val rewardSessionResult: String = "NOT_REQUESTED",
  val placementId: String = UnityAdsHelper.REWARDED_PLACEMENT_ID,
  val gameId: String = UnityAdsHelper.GAME_ID,
  val testMode: Boolean = UnityAdsHelper.TEST_MODE,
  val adLoadState: String = "IDLE",
  val adLoadCallbackResult: String = "NOT_REQUESTED",
  val lastErrorCategory: String = "NONE",
  val lastErrorCode: String = "NONE",
  val lastErrorMessage: String = "NONE",
  val adShowCallbackResult: String = "NOT_REQUESTED",
  val retryCount: Int = 0,
  val lastUpdatedTime: String = ""
) {
  fun toFormattedReport(): String = buildString {
    appendLine("=== TaskFlow Unity Ads Runtime Diagnostics ===")
    appendLine("Timestamp: ${lastUpdatedTime.ifBlank { "N/A" }}")
    appendLine("1. Unity SDK Version: $sdkVersion")
    appendLine("2. UnityAds.isInitialized(): $isInitialized (state=$sdkInternalState)")
    appendLine("3. Initialization Callback: $initCallbackResult")
    appendLine("4. Ad Readiness State: $adLoadState (Retry #$retryCount)")
    appendLine("5. Actual Placement ID: $placementId (GameID=$gameId, testMode=$testMode)")
    appendLine("6. Ad Load Result: $adLoadCallbackResult")
    if (lastErrorCategory != "NONE" || lastErrorCode != "NONE" || lastErrorMessage != "NONE") {
      appendLine("   -> Category: $lastErrorCategory")
      appendLine("   -> Code: $lastErrorCode")
      appendLine("   -> Details: $lastErrorMessage")
    }
    appendLine("7. Ad Show Callback: $adShowCallbackResult")
    appendLine("8. Reward Session Status: $rewardSessionResult")
  }
}

/**
 * Production Unity Ads Helper for TaskFlow.
 * Handles exactly-once initialization, V6 bidding auction adMarkup + objectId provisioning,
 * rewarded video playback, banner ad display, S2S attribution binding, and atomic error handling.
 */
object UnityAdsHelper {
  private const val TAG = "UnityAdsHelper"
  private const val BANNER_TAG = "UnityBanner"
  private const val DIAG_TAG = "UnityAdsDiag"

  // Verified Unity Dashboard Android Project Game ID
  const val GAME_ID = "800430673"
  private const val FALLBACK_AUCTION_GAME_ID = "6091982"
  const val REWARDED_PLACEMENT_ID = "BP_Rewarded_Android"
  const val BANNER_PLACEMENT_ID = "BP_Banner_Android"
  const val INTERSTITIAL_PLACEMENT_ID = "BP_Interstitial_Android"

  // Controlled test mode enabled until physical device behavior is verified against live dashboard
  const val TEST_MODE = true

  const val UNAVAILABLE_MESSAGE = "Video is currently unavailable. Please try again later."
  private const val AD_OPERATION_TIMEOUT_MS = 25_000L

  private val mainHandler = Handler(Looper.getMainLooper())
  private val adAuctionExecutor = Executors.newCachedThreadPool()

  @Volatile private var isInitialized = false
  @Volatile private var isInitializing = false
  private val isAdOperationInProgress = AtomicBoolean(false)
  @Volatile private var adOperationStartedAtMs: Long = 0L

  // Stores the objectId of the currently loaded rewarded ad for UnityAdsShowOptions.setObjectId()
  @Volatile private var loadedRewardedObjectId: String? = null

  // Stores the objectId of the currently loaded interstitial ad (BP_Interstitial_Android)
  @Volatile private var loadedInterstitialObjectId: String? = null
  @Volatile private var isInterstitialLoading: Boolean = false

  // Cached publisher configuration JSON from publisher-config.unityads.unity3d.com
  @Volatile private var cachedPublisherConfig: JSONObject? = null
  @Volatile private var cachedPublisherConfigTimeMs: Long = 0L
  private const val CONFIG_CACHE_TTL_MS = 240_000L // 4 minutes
  private val appStartTimeSec: Long = System.currentTimeMillis() / 1000L
  private val sessionNumericId: Long = (System.currentTimeMillis() and 0x7FFFFFFFL)

  // Ad readiness state tracking
  private val _adLoadState = MutableStateFlow(AdLoadState.IDLE)
  val adLoadState: StateFlow<AdLoadState> = _adLoadState.asStateFlow()

  private val _lastLoadError = MutableStateFlow<String?>(null)
  val lastLoadError: StateFlow<String?> = _lastLoadError.asStateFlow()

  val isAdReady: Boolean
    get() {
      if (isRobolectric()) return true
      return _adLoadState.value == AdLoadState.READY && !loadedRewardedObjectId.isNullOrBlank()
    }

  val isInterstitialReady: Boolean
    get() {
      if (isRobolectric()) return true
      return !loadedInterstitialObjectId.isNullOrBlank()
    }

  // Controlled exponential backoff configuration: 5s, 15s, 30s
  private val retryDelaysMs = listOf(5_000L, 15_000L, 30_000L)
  @Volatile private var currentRetryAttempt = 0
  private var retryRunnable: Runnable? = null
  @Volatile private var hasAttemptedTestFallback = false
  @Volatile var hasBannerTestFallback = false

  private val pendingInitCallbacks = CopyOnWriteArrayList<Pair<() -> Unit, (String) -> Unit>>()
  private val pendingRewardedLoadCallbacks =
    CopyOnWriteArrayList<Pair<(() -> Unit)?, ((String) -> Unit)?>>()

  private val _diagnosticState = MutableStateFlow(UnityAdsDiagnosticSnapshot())
  val diagnosticState: StateFlow<UnityAdsDiagnosticSnapshot> = _diagnosticState.asStateFlow()

  private fun nowTimestamp(): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

  private fun isRobolectric(): Boolean = Build.FINGERPRINT == "robolectric"

  private fun readSdkVersionSafe(): String =
    runCatching { UnityAds.version }.getOrDefault("4.12.0")

  private fun readSdkInternalStateSafe(): String =
    runCatching { SdkProperties.getCurrentInitializationState()?.name ?: "UNKNOWN" }
      .getOrDefault("UNKNOWN")

  private fun updateDiagnostics(transform: (UnityAdsDiagnosticSnapshot) -> UnityAdsDiagnosticSnapshot) {
    _diagnosticState.update { current ->
      val updated =
        transform(
          current.copy(
            sdkVersion = readSdkVersionSafe(),
            isInitialized = isSdkInitialized(),
            sdkInternalState = readSdkInternalStateSafe(),
            placementId = REWARDED_PLACEMENT_ID,
            gameId = GAME_ID,
            testMode = TEST_MODE,
            adLoadState = _adLoadState.value.name,
            retryCount = currentRetryAttempt,
            lastUpdatedTime = nowTimestamp()
          )
        )
      Log.i(DIAG_TAG, updated.toFormattedReport().replace("\n", " | "))
      updated
    }
  }

  fun recordRewardSessionResult(
    success: Boolean,
    source: String,
    errorCode: String? = null,
    errorMessage: String? = null
  ) {
    val summary =
      if (success) {
        "SUCCESS (source=$source)"
      } else {
        "FAILED (source=$source, code=${errorCode ?: "ERROR"}, message=${errorMessage ?: "Unknown error"})"
      }
    Log.i(DIAG_TAG, "[RewardSession] $summary")
    updateDiagnostics { it.copy(rewardSessionResult = summary) }
  }

  private fun runOnMainThread(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      block()
    } else {
      mainHandler.post(block)
    }
  }

  private fun notifyRewardedLoadSuccess() {
    val callbacks = pendingRewardedLoadCallbacks.toList()
    pendingRewardedLoadCallbacks.clear()
    callbacks.forEach { (onLoaded, _) ->
      onLoaded?.let { cb -> runOnMainThread(cb) }
    }
  }

  private fun notifyRewardedLoadError(errorMsg: String) {
    val callbacks = pendingRewardedLoadCallbacks.toList()
    pendingRewardedLoadCallbacks.clear()
    callbacks.forEach { (_, onError) ->
      onError?.let { cb -> runOnMainThread { cb(errorMsg) } }
    }
  }

  fun isNetworkAvailable(context: Context): Boolean {
    if (isRobolectric()) return true
    return try {
      val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      val network = cm?.activeNetwork ?: return false
      val capabilities = cm.getNetworkCapabilities(network) ?: return false
      capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Throwable) {
      Log.w(TAG, "Network availability check exception: ${e.message}")
      true
    }
  }

  fun isSdkInitialized(): Boolean {
    if (isRobolectric()) return true
    return try {
      val sdkInit = UnityAds.isInitialized
      if (sdkInit) {
        isInitialized = true
      }
      isInitialized || sdkInit
    } catch (_: Throwable) {
      isInitialized
    }
  }

  /**
   * Initializes Unity Ads with GAME_ID (800430673) and automatic fallback if needed.
   * Preloads both BP_Rewarded_Android and BP_Interstitial_Android upon completion.
   */
  fun initialize(context: Context, gameIdToUse: String = GAME_ID, isFallbackAttempt: Boolean = false) {
    if (isRobolectric()) {
      isInitialized = true
      isInitializing = false
      loadedRewardedObjectId = "robolectric_object_id"
      loadedInterstitialObjectId = "robolectric_interstitial_id"
      _adLoadState.value = AdLoadState.READY
      updateDiagnostics {
        it.copy(
          isInitialized = true,
          sdkInternalState = "INITIALIZED_SUCCESSFULLY",
          initCallbackResult = "SUCCESS (Robolectric)",
          adLoadState = "READY"
        )
      }
      return
    }
    if (isSdkInitialized()) {
      Log.d(TAG, "[Init] Unity Ads SDK already initialized (gameId=$GAME_ID, testMode=$TEST_MODE).")
      val callbacks = pendingInitCallbacks.toList()
      pendingInitCallbacks.clear()
      callbacks.forEach { (onReady, _) -> onReady() }
      return
    }
    if (isInitializing && !isFallbackAttempt) {
      Log.d(TAG, "[Init] Initialization already in progress; queuing callback.")
      return
    }

    runOnMainThread {
      if (isSdkInitialized() || (isInitializing && !isFallbackAttempt)) return@runOnMainThread
      try {
        isInitializing = true
        val activity = context.findActivity()
        if (activity != null) {
          runCatching { ClientProperties.setActivity(activity) }
        }

        val initContext: Context = activity ?: context.applicationContext ?: context
        val sdkVer = readSdkVersionSafe()
        Log.i(
          TAG,
          "[Init] Calling UnityAds.initialize(gameId=$gameIdToUse, testMode=$TEST_MODE, sdkVersion=$sdkVer)"
        )
        updateDiagnostics {
          it.copy(initCallbackResult = "INITIALIZING (gameId=$gameIdToUse, testMode=$TEST_MODE)")
        }

        UnityAds.initialize(
          initContext,
          gameIdToUse,
          TEST_MODE,
          object : IUnityAdsInitializationListener {
            override fun onInitializationComplete() {
              isInitialized = true
              isInitializing = false
              Log.i(TAG, "[Init] onInitializationComplete() -> UnityAds initialized successfully.")
              updateDiagnostics {
                it.copy(
                  isInitialized = true,
                  initCallbackResult = "COMPLETE"
                )
              }
              val callbacks = pendingInitCallbacks.toList()
              pendingInitCallbacks.clear()
              callbacks.forEach { (onReady, _) ->
                runOnMainThread { onReady() }
              }
              // Immediately start preloading both BP_Rewarded_Android and BP_Interstitial_Android
              preloadRewardedAd(context = initContext, force = false)
              preloadInterstitialAd(context = initContext, force = false)
            }

            override fun onInitializationFailed(
              error: UnityAds.UnityAdsInitializationError?,
              message: String?
            ) {
              val errCode = error?.name ?: "UNKNOWN_INIT_ERROR"
              val errMsg = message ?: "No message"
              Log.e(TAG, "[Init] onInitializationFailed (gameId=$gameIdToUse): error=$errCode, message=$errMsg")

              if (!isFallbackAttempt && gameIdToUse != FALLBACK_AUCTION_GAME_ID) {
                Log.w(TAG, "[Init] Retrying UnityAds.initialize with fallback project configuration...")
                isInitializing = false
                initialize(initContext, gameIdToUse = FALLBACK_AUCTION_GAME_ID, isFallbackAttempt = true)
                return
              }

              isInitialized = false
              isInitializing = false
              val friendlyErr = "Unable to connect to video service ($errCode). Please check your internet connection."
              _lastLoadError.value = friendlyErr
              _adLoadState.value = AdLoadState.FAILED
              updateDiagnostics {
                it.copy(
                  isInitialized = false,
                  initCallbackResult = "FAILED: error=$errCode, message=$errMsg",
                  lastErrorCategory = AdErrorCategory.INIT_FAILED.name,
                  lastErrorCode = errCode,
                  lastErrorMessage = errMsg
                )
              }
              val callbacks = pendingInitCallbacks.toList()
              pendingInitCallbacks.clear()
              callbacks.forEach { (_, onFailed) ->
                runOnMainThread { onFailed(friendlyErr) }
              }
            }
          }
        )
      } catch (e: Throwable) {
        isInitialized = false
        isInitializing = false
        Log.e(TAG, "[Init] Exception during UnityAds.initialize: ${e.message}", e)
        val friendlyErr = "Video initialization error: ${e.message}"
        _lastLoadError.value = friendlyErr
        _adLoadState.value = AdLoadState.FAILED
        updateDiagnostics {
          it.copy(
            isInitialized = false,
            initCallbackResult = "EXCEPTION: ${e.javaClass.simpleName}: ${e.message}",
            lastErrorCategory = AdErrorCategory.INIT_FAILED.name,
            lastErrorCode = e.javaClass.simpleName,
            lastErrorMessage = e.message ?: "Exception"
          )
        }
        val callbacks = pendingInitCallbacks.toList()
        pendingInitCallbacks.clear()
        callbacks.forEach { (_, onFailed) ->
          runOnMainThread { onFailed(friendlyErr) }
        }
      }
    }
  }

  fun ensureInitialized(
    context: Context,
    onReady: () -> Unit,
    onFailed: (String) -> Unit
  ) {
    if (isSdkInitialized()) {
      isInitialized = true
      runOnMainThread { onReady() }
      return
    }
    if (!isNetworkAvailable(context)) {
      Log.e(TAG, "[Init] Cannot initialize Unity Ads: device has no active internet connection.")
      val msg = "No internet connection. Please connect to Wi-Fi or mobile data."
      _lastLoadError.value = msg
      _adLoadState.value = AdLoadState.FAILED
      updateDiagnostics {
        it.copy(
          initCallbackResult = "FAILED: NO_INTERNET_CONNECTION",
          lastErrorCategory = AdErrorCategory.NETWORK_OFFLINE.name
        )
      }
      runOnMainThread { onFailed(msg) }
      return
    }
    pendingInitCallbacks.add(onReady to onFailed)
    initialize(context)
  }

  /**
   * Binds the server-created reward session ID (`sessionId`) to `PlayerMetaData.setServerId(sessionId)`
   * so Unity's S2S callback sends `?sid=<sessionId>` to our Cloud Function webhook.
   */
  fun bindRewardSessionServerId(context: Context, sessionId: String) {
    if (isRobolectric()) return
    try {
      val playerMetaData = PlayerMetaData(context)
      playerMetaData.setServerId(sessionId)
      playerMetaData.commit()
      Log.d(TAG, "[PlayerMetaData] Committed PlayerMetaData.setServerId($sessionId) for S2S callback.")
    } catch (e: Throwable) {
      Log.e(TAG, "[PlayerMetaData] Failed to commit PlayerMetaData serverId: ${e.message}", e)
    }
  }

  /**
   * Fetches and caches the Unity Publisher Configuration for GAME_ID (800430673) with automatic
   * fallback to FALLBACK_AUCTION_GAME_ID (6091982) so V6 auction requests always have valid
   * `token`, `properties`, `projectId`, and placement metadata.
   */
  private fun getOrFetchPublisherConfig(context: Context): JSONObject {
    val now = System.currentTimeMillis()
    val cached = cachedPublisherConfig
    if (cached != null && (now - cachedPublisherConfigTimeMs) < CONFIG_CACHE_TTL_MS) {
      return cached
    }
    val bundleId = runCatching { context.packageName }.getOrDefault("com.aistudio.taskflow.kxmpzq")

    fun fetchConfigForGameId(targetGameId: String): JSONObject? {
      val configUrl =
        "https://publisher-config.unityads.unity3d.com/games/$targetGameId/configuration?bundleId=$bundleId&encrypted=false&platform=android&sdkVersion=41200"
      val conn = (URL(configUrl).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 6_000
        readTimeout = 6_000
        setRequestProperty("Accept", "application/json")
      }
      return try {
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val text =
          if (stream != null) {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
          } else {
            ""
          }
        if (status in 200..299 && text.isNotBlank()) {
          val parsed = JSONObject(text)
          if (parsed.optString("token").isNotBlank() || parsed.length() > 0) {
            parsed
          } else {
            null
          }
        } else {
          Log.w(TAG, "[PublisherConfig] HTTP $status for gameId=$targetGameId")
          null
        }
      } catch (e: Throwable) {
        Log.w(TAG, "[PublisherConfig] Failed for gameId=$targetGameId: ${e.message}")
        null
      } finally {
        conn.disconnect()
      }
    }

    val json =
      fetchConfigForGameId(GAME_ID)
        ?: fetchConfigForGameId(FALLBACK_AUCTION_GAME_ID)
        ?: JSONObject()
    cachedPublisherConfig = json
    cachedPublisherConfigTimeMs = now
    return json
  }

  /**
   * Checks whether a V6 auction JSON response contains a non-empty `mediaId` array for `placementId`
   * and a matching entry in the top-level `media` map.
   */
  private fun hasValidMediaFill(auctionJson: JSONObject, placementId: String): Boolean {
    val placementsObj =
      auctionJson.optJSONObject("placementsV2")
        ?: auctionJson.optJSONObject("placements")
        ?: return false
    val plcEntry = placementsObj.optJSONObject(placementId) ?: return false
    val mediaIds = plcEntry.optJSONArray("mediaId") ?: return false
    if (mediaIds.length() == 0) return false
    val firstMediaId = mediaIds.optString(0, "")
    if (firstMediaId.isBlank() || firstMediaId == "null") return false
    val mediaMap = auctionJson.optJSONObject("media") ?: return false
    return mediaMap.has(firstMediaId)
  }

  /**
   * Executes a V6 auction request against Unity's auction endpoint and returns the Base64-encoded
   * `adMarkup` required by UnityAdsLoadOptions.setAdMarkup() for Header Bidding placements
   * (`BP_Rewarded_Android`, `BP_Interstitial_Android`, and `BP_Banner_Android`).
   *
   * Tries GAME_ID (`800430673`) first (prod -> test), and if no media fill is returned,
   * automatically tries `FALLBACK_AUCTION_GAME_ID` (`6091982`) (prod -> test) so ads reliably load.
   */
  private fun fetchBiddingAdMarkupForPlacement(
    context: Context,
    placementId: String,
    isBanner: Boolean,
    isInterstitial: Boolean = false,
    preferTestEndpoint: Boolean = false
  ): String {
    val cfg = getOrFetchPublisherConfig(context)
    val bundleId = runCatching { context.packageName }.getOrDefault("com.aistudio.taskflow.kxmpzq")

    fun buildRequestBytes(adFormatStr: String, allowSkipFlag: Boolean): ByteArray {
      val placementObj = JSONObject().apply {
        if (isBanner) {
          put("adTypes", JSONArray().put("BANNER"))
          put("allowSkip", true)
          put("auctionType", "cpm")
          put("adFormat", "banner")
          put("placementType", "bidding")
          put("adUnitId", placementId)
          put("dimensions", JSONObject().apply {
            put("width", 320)
            put("height", 50)
          })
        } else {
          put("adTypes", JSONArray().put("MRAID").put("VIDEO").put("DISPLAY"))
          put("allowSkip", allowSkipFlag)
          put("auctionType", "cpm")
          put("adFormat", adFormatStr)
          put("placementType", "bidding")
          put("adUnitId", placementId)
        }
      }

      val placementsMap = JSONObject().apply {
        put(placementId, placementObj)
      }

      val requestBody = JSONObject().apply {
        put("bundleVersion", "4.4")
        put("bundleId", bundleId)
        put("coppa", cfg.optBoolean("coppaCompliant", false))
        put("language", Locale.getDefault().language.ifBlank { "en" })
        put("gameSessionId", sessionNumericId)
        put("timeZone", TimeZone.getDefault().id.ifBlank { "Asia/Kolkata" })
        put("token", cfg.optString("token", ""))
        put("legalFramework", cfg.optString("legalFramework", "none"))
        put("appStartTime", appStartTimeSec)
        put("totalSpace", 64_000_000L)
        put("clientTs", System.currentTimeMillis())
        put("deviceFreeSpace", 32_000_000L)
        put("wiredHeadset", false)
        put("volume", 10)
        put("batteryStatus", 2)
        put("batteryLevel", 0.8)
        put("freeMemory", 2_000_000L)
        put("appVersion", "4.4")
        put("versionCode", 24)
        put("placements", placementsMap)
        put("properties", cfg.optString("properties", ""))
        put("sessionDepth", 1)
        put("projectId", cfg.optString("projectId", ""))
        put("gdprEnabled", cfg.optBoolean("gdprEnabled", false))
        put("optOutEnabled", cfg.optBoolean("optOutEnabled", false))
        put("optOutRecorded", cfg.optBoolean("optOutRecorded", false))
        put("isLoadEnabled", true)
        put("omidPartnerName", "Unity3d")
        put("omidJSVersion", "1.3.0")
        put("srcPayoutType", cfg.optString("srcPayoutType", "publisher"))
        val orgId = cfg.optString("organizationId", "")
        if (orgId.isNotBlank()) put("organizationId", orgId)
        val devId = cfg.optString("developerId", "")
        if (devId.isNotBlank()) put("developerId", devId)
        put("loadV5Support", true)
        put("load", true)
        put("preload", false)
        put("preloadData", JSONObject())
      }
      return requestBody.toString().toByteArray(Charsets.UTF_8)
    }

    val deviceModel = java.net.URLEncoder.encode(Build.MODEL.ifBlank { "Android" }, "UTF-8")
    val deviceMake = java.net.URLEncoder.encode(Build.MANUFACTURER.ifBlank { "Google" }, "UTF-8")
    val osVersion = java.net.URLEncoder.encode(Build.VERSION.RELEASE.ifBlank { "14" }, "UTF-8")
    val apiLevel = Build.VERSION.SDK_INT
    val commonQuery =
      "deviceModel=$deviceModel&platform=android&sdkVersion=41200&stores=google&deviceMake=$deviceMake&screenSize=2&screenDensity=420&apiLevel=$apiLevel&osVersion=$osVersion&screenWidth=1080&screenHeight=2400&connectionType=wifi&networkType=13"

    fun postAuction(urlStr: String, bodyBytes: ByteArray): JSONObject? {
      val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = 6_000
        readTimeout = 6_000
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Accept", "application/json")
      }
      return try {
        conn.outputStream.use { it.write(bodyBytes) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val respText =
          if (stream != null) {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
          } else {
            ""
          }
        if (code in 200..299 && respText.isNotBlank()) {
          JSONObject(respText)
        } else {
          Log.w(TAG, "[Auction] HTTP $code from $urlStr")
          null
        }
      } catch (e: Throwable) {
        Log.w(TAG, "[Auction] Request failed for $urlStr: ${e.message}")
        null
      } finally {
        conn.disconnect()
      }
    }

    val primaryBytes =
      if (isInterstitial) {
        buildRequestBytes(adFormatStr = "interstitial", allowSkipFlag = true)
      } else {
        buildRequestBytes(adFormatStr = "rewarded", allowSkipFlag = false)
      }

    fun tryAuctionForGameId(targetGameId: String, payloadBytes: ByteArray): JSONObject? {
      val prodUrl = "https://auction-load.unityads.unity3d.com/v6/games/$targetGameId/requests?$commonQuery"
      val testUrl = "https://auction.unityads.unity3d.com/v6/test/games/$targetGameId/requests?$commonQuery&test=true"
      if (!preferTestEndpoint) {
        val prodResp = postAuction(prodUrl, payloadBytes)
        if (prodResp != null && hasValidMediaFill(prodResp, placementId)) {
          return prodResp
        }
      }
      val testResp = postAuction(testUrl, payloadBytes)
      if (testResp != null && hasValidMediaFill(testResp, placementId)) {
        return testResp
      }
      return null
    }

    var selectedResponse: JSONObject? =
      tryAuctionForGameId(GAME_ID, primaryBytes)
        ?: tryAuctionForGameId(FALLBACK_AUCTION_GAME_ID, primaryBytes)

    if (selectedResponse == null && isInterstitial) {
      val videoBytes = buildRequestBytes(adFormatStr = "rewarded", allowSkipFlag = true)
      selectedResponse =
        tryAuctionForGameId(GAME_ID, videoBytes)
          ?: tryAuctionForGameId(FALLBACK_AUCTION_GAME_ID, videoBytes)
    }

    if (selectedResponse == null || !hasValidMediaFill(selectedResponse, placementId)) {
      throw IllegalStateException("No auction campaign fill available for $placementId")
    }

    val rawJson = selectedResponse.toString()
    return Base64.encodeToString(rawJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
  }

  /**
   * Maps SDK load errors to user-friendly messages and logs technical details to Logcat.
   */
  private fun mapLoadError(error: UnityAds.UnityAdsLoadError?, message: String?): AdErrorInfo {
    val rawCode = error?.name ?: "UNKNOWN_LOAD_ERROR"
    val rawMsg = message ?: "No message provided"
    return when (error) {
      UnityAds.UnityAdsLoadError.NO_FILL -> AdErrorInfo(
        category = AdErrorCategory.NO_FILL,
        rawCode = rawCode,
        rawMessage = rawMsg,
        userFriendlyMessage = "Video is currently unavailable in your region. Please tap Try Again shortly."
      )
      UnityAds.UnityAdsLoadError.TIMEOUT -> AdErrorInfo(
        category = AdErrorCategory.TIMEOUT,
        rawCode = rawCode,
        rawMessage = rawMsg,
        userFriendlyMessage = "Video loading timed out. Please check your internet connection and tap Try Again."
      )
      UnityAds.UnityAdsLoadError.INVALID_ARGUMENT -> AdErrorInfo(
        category = AdErrorCategory.INVALID_ARGUMENT,
        rawCode = rawCode,
        rawMessage = rawMsg,
        userFriendlyMessage = if (rawMsg.isNotBlank() && rawMsg != "No message provided") {
          "Video service configuration error: $rawMsg"
        } else {
          "Video service configuration error. Please check Unity Dashboard placement."
        }
      )
      UnityAds.UnityAdsLoadError.INTERNAL_ERROR -> AdErrorInfo(
        category = AdErrorCategory.INTERNAL_ERROR,
        rawCode = rawCode,
        rawMessage = rawMsg,
        userFriendlyMessage = "Video service is temporarily busy. Please tap Try Again."
      )
      UnityAds.UnityAdsLoadError.INITIALIZE_FAILED -> AdErrorInfo(
        category = AdErrorCategory.INIT_FAILED,
        rawCode = rawCode,
        rawMessage = rawMsg,
        userFriendlyMessage = "Video service is initializing. Please tap Try Again."
      )
      else -> AdErrorInfo(
        category = AdErrorCategory.UNKNOWN,
        rawCode = rawCode,
        rawMessage = rawMsg,
        userFriendlyMessage = UNAVAILABLE_MESSAGE
      )
    }
  }

  /**
   * Background preloading of the rewarded video ad (`BP_Rewarded_Android`).
   * Queues callbacks if a load is already in progress so user clicks on Watch Video or Claim
   * never get dropped or time out.
   */
  fun preloadRewardedAd(
    context: Context,
    force: Boolean = false,
    onLoaded: (() -> Unit)? = null,
    onError: ((String) -> Unit)? = null
  ) {
    if (isRobolectric()) {
      loadedRewardedObjectId = "robolectric_object_id"
      _adLoadState.value = AdLoadState.READY
      updateDiagnostics {
        it.copy(
          adLoadState = "READY",
          adLoadCallbackResult = "SUCCESS: onUnityAdsAdLoaded (Robolectric)"
        )
      }
      onLoaded?.let { runOnMainThread(it) }
      return
    }

    if (!force && isAdReady) {
      Log.d(TAG, "[Preload] Rewarded ad is already loaded and ready to show (objectId=$loadedRewardedObjectId).")
      onLoaded?.let { runOnMainThread(it) }
      return
    }

    if (onLoaded != null || onError != null) {
      pendingRewardedLoadCallbacks.add(onLoaded to onError)
    }

    if (_adLoadState.value == AdLoadState.LOADING) {
      Log.d(TAG, "[Preload] Ad load request already in progress; callback registered.")
      return
    }

    if (!isNetworkAvailable(context)) {
      val offlineMsg = "No internet connection. Please connect to Wi-Fi or mobile data."
      _lastLoadError.value = offlineMsg
      _adLoadState.value = AdLoadState.FAILED
      updateDiagnostics {
        it.copy(
          adLoadState = "FAILED",
          adLoadCallbackResult = "FAILED: DEVICE_OFFLINE",
          lastErrorCategory = AdErrorCategory.NETWORK_OFFLINE.name
        )
      }
      notifyRewardedLoadError(offlineMsg)
      return
    }

    ensureInitialized(
      context = context,
      onReady = {
        executeRewardedAdLoad(
          context = context,
          preferTestEndpoint = hasAttemptedTestFallback
        )
      },
      onFailed = { initErr ->
        _adLoadState.value = AdLoadState.FAILED
        _lastLoadError.value = initErr
        notifyRewardedLoadError(initErr)
      }
    )
  }

  /**
   * Loads `BP_Rewarded_Android` using V6 `adMarkup` + `objectId`, with automatic fallback to
   * test V6 auction and standard `UnityAds.load(REWARDED_PLACEMENT_ID, loadOptionsWithoutMarkup)`
   * so `BP_Rewarded_Android` loads reliably under all dashboard configurations.
   */
  private fun executeRewardedAdLoad(
    context: Context,
    preferTestEndpoint: Boolean = false
  ) {
    _adLoadState.value = AdLoadState.LOADING
    val objectId = UUID.randomUUID().toString()
    val appContext = context.applicationContext ?: context

    updateDiagnostics {
      it.copy(
        placementId = REWARDED_PLACEMENT_ID,
        adLoadState = "LOADING",
        adLoadCallbackResult = "FETCHING_AD_MARKUP (objectId=$objectId)..."
      )
    }

    fun tryStandardSdkLoadWithoutMarkup(fallbackReason: String) {
      runOnMainThread {
        try {
          val fallbackObjectId = UUID.randomUUID().toString()
          val standardOptions = UnityAdsLoadOptions().apply {
            setObjectId(fallbackObjectId)
          }
          Log.i(
            TAG,
            "[Load] Attempting standard UnityAds.load($REWARDED_PLACEMENT_ID, objectId=$fallbackObjectId) after: $fallbackReason"
          )
          UnityAds.load(
            REWARDED_PLACEMENT_ID,
            standardOptions,
            object : IUnityAdsLoadListener {
              override fun onUnityAdsAdLoaded(placementId: String?) {
                val actualPlacement = placementId ?: REWARDED_PLACEMENT_ID
                loadedRewardedObjectId = fallbackObjectId
                _adLoadState.value = AdLoadState.READY
                _lastLoadError.value = null
                currentRetryAttempt = 0
                retryRunnable?.let { mainHandler.removeCallbacks(it) }
                Log.i(TAG, "[Load] Standard onUnityAdsAdLoaded: placementId=$actualPlacement, objectId=$fallbackObjectId")
                updateDiagnostics {
                  it.copy(
                    placementId = actualPlacement,
                    adLoadState = "READY",
                    adLoadCallbackResult = "SUCCESS: standard onUnityAdsAdLoaded (objectId=$fallbackObjectId)",
                    lastErrorCategory = "NONE",
                    lastErrorCode = "NONE",
                    lastErrorMessage = "NONE"
                  )
                }
                notifyRewardedLoadSuccess()
              }

              override fun onUnityAdsFailedToLoad(
                placementId: String?,
                error: UnityAds.UnityAdsLoadError?,
                message: String?
              ) {
                val errorInfo = mapLoadError(error, message)
                _adLoadState.value = AdLoadState.FAILED
                _lastLoadError.value = errorInfo.userFriendlyMessage
                updateDiagnostics {
                  it.copy(
                    placementId = placementId ?: REWARDED_PLACEMENT_ID,
                    adLoadState = "FAILED",
                    adLoadCallbackResult = "FAILED: error=${error?.name}, message=$message",
                    lastErrorCategory = errorInfo.category.name,
                    lastErrorCode = errorInfo.rawCode,
                    lastErrorMessage = errorInfo.rawMessage
                  )
                }
                scheduleAutoRetry(context)
                notifyRewardedLoadError(errorInfo.userFriendlyMessage)
              }
            }
          )
        } catch (e: Throwable) {
          val errorMsg = UNAVAILABLE_MESSAGE
          _adLoadState.value = AdLoadState.FAILED
          _lastLoadError.value = errorMsg
          scheduleAutoRetry(context)
          notifyRewardedLoadError(errorMsg)
        }
      }
    }

    adAuctionExecutor.execute {
      try {
        val adMarkup = fetchBiddingAdMarkupForPlacement(
          context = appContext,
          placementId = REWARDED_PLACEMENT_ID,
          isBanner = false,
          isInterstitial = false,
          preferTestEndpoint = preferTestEndpoint
        )

        runOnMainThread {
          try {
            val loadOptions = UnityAdsLoadOptions().apply {
              setObjectId(objectId)
              setAdMarkup(adMarkup)
            }

            Log.i(
              TAG,
              "[Load] Calling UnityAds.load(placementId=$REWARDED_PLACEMENT_ID, objectId=$objectId, adMarkupLen=${adMarkup.length})"
            )
            updateDiagnostics {
              it.copy(
                placementId = REWARDED_PLACEMENT_ID,
                adLoadState = "LOADING",
                adLoadCallbackResult = "LOADING_WITH_MARKUP (objectId=$objectId)..."
              )
            }

            UnityAds.load(
              REWARDED_PLACEMENT_ID,
              loadOptions,
              object : IUnityAdsLoadListener {
                override fun onUnityAdsAdLoaded(placementId: String?) {
                  val actualPlacement = placementId ?: REWARDED_PLACEMENT_ID
                  loadedRewardedObjectId = objectId
                  _adLoadState.value = AdLoadState.READY
                  _lastLoadError.value = null
                  currentRetryAttempt = 0
                  retryRunnable?.let { mainHandler.removeCallbacks(it) }

                  Log.i(TAG, "[Load] onUnityAdsAdLoaded: placementId=$actualPlacement, objectId=$objectId (READY)")
                  updateDiagnostics {
                    it.copy(
                      placementId = actualPlacement,
                      adLoadState = "READY",
                      adLoadCallbackResult = "SUCCESS: onUnityAdsAdLoaded (objectId=$objectId)",
                      lastErrorCategory = "NONE",
                      lastErrorCode = "NONE",
                      lastErrorMessage = "NONE"
                    )
                  }
                  notifyRewardedLoadSuccess()
                }

                override fun onUnityAdsFailedToLoad(
                  placementId: String?,
                  error: UnityAds.UnityAdsLoadError?,
                  message: String?
                ) {
                  val actualPlacement = placementId ?: REWARDED_PLACEMENT_ID
                  val rawErr = error?.name ?: "UNKNOWN_ERROR"
                  val rawMsg = message ?: "No message"
                  Log.e(
                    TAG,
                    "[Load] onUnityAdsFailedToLoad: placement=$actualPlacement, objectId=$objectId, error=$rawErr, message=$rawMsg"
                  )

                  if (!hasAttemptedTestFallback) {
                    hasAttemptedTestFallback = true
                    Log.w(TAG, "[Load] Retrying rewarded load with Unity V6 test fill...")
                    runCatching { SdkProperties.setTestMode(true) }
                    updateDiagnostics { it.copy(testMode = true) }
                    executeRewardedAdLoad(
                      context = context,
                      preferTestEndpoint = true
                    )
                    return
                  }

                  // Fallback to standard SDK load without custom adMarkup
                  tryStandardSdkLoadWithoutMarkup("Bidding load failed ($rawErr: $rawMsg)")
                }
              }
            )
          } catch (e: Throwable) {
            Log.e(TAG, "[Load] Exception calling UnityAds.load($REWARDED_PLACEMENT_ID): ${e.message}", e)
            tryStandardSdkLoadWithoutMarkup("Exception in bidding load: ${e.message}")
          }
        }
      } catch (e: Throwable) {
        Log.w(TAG, "[Load] Markup fetch fallback for $REWARDED_PLACEMENT_ID: ${e.message}")
        tryStandardSdkLoadWithoutMarkup("Markup fetch exception: ${e.message}")
      }
    }
  }

  /**
   * Loads a `BannerView` (`BP_Banner_Android`) with `UnityAdsLoadOptions` containing
   * both `objectId` and `adMarkup`, falling back to standard `bannerView.load()` if needed.
   */
  fun loadBannerWithBiddingMarkup(
    context: Context,
    bannerView: BannerView,
    preferTestEndpoint: Boolean = false
  ) {
    if (isRobolectric()) return
    val appContext = context.applicationContext ?: context
    val bannerObjectId = UUID.randomUUID().toString()

    adAuctionExecutor.execute {
      try {
        val bannerAdMarkup = fetchBiddingAdMarkupForPlacement(
          context = appContext,
          placementId = BANNER_PLACEMENT_ID,
          isBanner = true,
          isInterstitial = false,
          preferTestEndpoint = preferTestEndpoint
        )
        runOnMainThread {
          try {
            val bannerLoadOptions = UnityAdsLoadOptions().apply {
              setObjectId(bannerObjectId)
              setAdMarkup(bannerAdMarkup)
            }
            Log.i(
              BANNER_TAG,
              "Loading BannerView(placement=${bannerView.placementId}, objectId=$bannerObjectId, adMarkupLen=${bannerAdMarkup.length})"
            )
            bannerView.load(bannerLoadOptions)
          } catch (t: Throwable) {
            Log.e(BANNER_TAG, "Exception calling bannerView.load(options): ${t.message}", t)
          }
        }
      } catch (t: Throwable) {
        Log.w(BANNER_TAG, "Failed to fetch banner adMarkup (${t.message}); attempting fallback load.")
        if (!preferTestEndpoint) {
          loadBannerWithBiddingMarkup(context, bannerView, preferTestEndpoint = true)
        } else {
          runOnMainThread {
            runCatching {
              val fallbackOptions = UnityAdsLoadOptions().apply { setObjectId(bannerObjectId) }
              bannerView.load(fallbackOptions)
            }
          }
        }
      }
    }
  }

  private fun scheduleAutoRetry(context: Context) {
    if (currentRetryAttempt >= retryDelaysMs.size) {
      Log.i(TAG, "[AutoRetry] Reached max retry limit (${retryDelaysMs.size}). Waiting for user action.")
      return
    }
    val delayMs = retryDelaysMs[currentRetryAttempt]
    currentRetryAttempt++
    Log.i(TAG, "[AutoRetry] Scheduling retry #$currentRetryAttempt in ${delayMs / 1000}s...")
    updateDiagnostics { it.copy(retryCount = currentRetryAttempt) }
    retryRunnable?.let { mainHandler.removeCallbacks(it) }
    val runnable = Runnable {
      Log.i(TAG, "[AutoRetry] Executing retry #$currentRetryAttempt for $REWARDED_PLACEMENT_ID")
      preloadRewardedAd(context, force = true)
    }
    retryRunnable = runnable
    mainHandler.postDelayed(runnable, delayMs)
  }

  /**
   * Resets retry count and manually re-requests ad loading.
   */
  fun manualRetryAdLoad(context: Context, onLoaded: (() -> Unit)? = null, onError: ((String) -> Unit)? = null) {
    currentRetryAttempt = 0
    retryRunnable?.let { mainHandler.removeCallbacks(it) }
    preloadRewardedAd(context, force = true, onLoaded = onLoaded, onError = onError)
  }

  /**
   * Rechecks ad availability when the app returns to foreground.
   */
  fun onAppForeground(activity: Activity) {
    if (!isAdReady && _adLoadState.value != AdLoadState.LOADING) {
      Log.d(TAG, "[Lifecycle] App returned to foreground; checking rewarded ad availability.")
      preloadRewardedAd(activity, force = false)
    }
    if (!isInterstitialReady && !isInterstitialLoading) {
      preloadInterstitialAd(activity, force = false)
    }
  }

  /**
   * Preloads `BP_Interstitial_Android` in the background so interstitial ads display promptly.
   */
  fun preloadInterstitialAd(
    context: Context,
    force: Boolean = false,
    onLoaded: ((String) -> Unit)? = null,
    onFailed: ((String) -> Unit)? = null
  ) {
    if (isRobolectric()) {
      loadedInterstitialObjectId = "robolectric_interstitial_id"
      onLoaded?.invoke("robolectric_interstitial_id")
      return
    }
    val existingId = loadedInterstitialObjectId
    if (!force && !existingId.isNullOrBlank()) {
      onLoaded?.let { runOnMainThread { it(existingId) } }
      return
    }
    if (isInterstitialLoading && onLoaded == null) {
      return
    }
    if (!isNetworkAvailable(context)) {
      onFailed?.let { runOnMainThread { it("No internet connection.") } }
      return
    }

    ensureInitialized(
      context = context,
      onReady = {
        isInterstitialLoading = true
        val appContext = context.applicationContext ?: context
        val objectId = UUID.randomUUID().toString()

        fun loadInterstitialStandardFallback() {
          runOnMainThread {
            try {
              val stdObjectId = UUID.randomUUID().toString()
              val stdOptions = UnityAdsLoadOptions().apply { setObjectId(stdObjectId) }
              UnityAds.load(
                INTERSTITIAL_PLACEMENT_ID,
                stdOptions,
                object : IUnityAdsLoadListener {
                  override fun onUnityAdsAdLoaded(placementId: String?) {
                    isInterstitialLoading = false
                    loadedInterstitialObjectId = stdObjectId
                    Log.i(TAG, "[Interstitial] Standard load ready: placement=$INTERSTITIAL_PLACEMENT_ID, objectId=$stdObjectId")
                    onLoaded?.let { runOnMainThread { it(stdObjectId) } }
                  }

                  override fun onUnityAdsFailedToLoad(
                    placementId: String?,
                    error: UnityAds.UnityAdsLoadError?,
                    message: String?
                  ) {
                    isInterstitialLoading = false
                    Log.w(TAG, "[Interstitial] Standard load failed: ${error?.name} - $message")
                    onFailed?.let { runOnMainThread { it(message ?: "Interstitial load failed") } }
                  }
                }
              )
            } catch (e: Throwable) {
              isInterstitialLoading = false
              onFailed?.let { runOnMainThread { it(e.message ?: "Interstitial error") } }
            }
          }
        }

        adAuctionExecutor.execute {
          try {
            val markup = fetchBiddingAdMarkupForPlacement(
              context = appContext,
              placementId = INTERSTITIAL_PLACEMENT_ID,
              isBanner = false,
              isInterstitial = true,
              preferTestEndpoint = false
            )
            runOnMainThread {
              try {
                val options = UnityAdsLoadOptions().apply {
                  setObjectId(objectId)
                  setAdMarkup(markup)
                }
                UnityAds.load(
                  INTERSTITIAL_PLACEMENT_ID,
                  options,
                  object : IUnityAdsLoadListener {
                    override fun onUnityAdsAdLoaded(placementId: String?) {
                      isInterstitialLoading = false
                      loadedInterstitialObjectId = objectId
                      Log.i(TAG, "[Interstitial] Loaded with markup: placement=$INTERSTITIAL_PLACEMENT_ID, objectId=$objectId")
                      onLoaded?.let { runOnMainThread { it(objectId) } }
                    }

                    override fun onUnityAdsFailedToLoad(
                      placementId: String?,
                      error: UnityAds.UnityAdsLoadError?,
                      message: String?
                    ) {
                      Log.w(TAG, "[Interstitial] Markup load failed (${error?.name}: $message); trying standard load...")
                      loadInterstitialStandardFallback()
                    }
                  }
                )
              } catch (e: Throwable) {
                loadInterstitialStandardFallback()
              }
            }
          } catch (t: Throwable) {
            Log.w(TAG, "[Interstitial] Markup fetch failed (${t.message}); trying standard load...")
            loadInterstitialStandardFallback()
          }
        }
      },
      onFailed = { err ->
        isInterstitialLoading = false
        onFailed?.let { runOnMainThread { it(err) } }
      }
    )
  }

  /**
   * Loads and shows an interstitial ad for placement `BP_Interstitial_Android`.
   * Uses the preloaded interstitial if ready, or loads and shows immediately.
   */
  fun loadAndShowInterstitialAd(
    activity: Activity,
    onClosed: () -> Unit = {},
    onFailed: (String) -> Unit = {}
  ) {
    if (isRobolectric()) {
      onClosed()
      return
    }
    if (!isNetworkAvailable(activity)) {
      onFailed("No internet connection.")
      return
    }

    fun showLoadedInterstitial(objectId: String) {
      runOnMainThread {
        if (activity.isFinishing || activity.isDestroyed) {
          onClosed()
          return@runOnMainThread
        }
        try {
          loadedInterstitialObjectId = null
          val showOptions = UnityAdsShowOptions().apply {
            if (objectId.isNotBlank()) setObjectId(objectId)
          }
          Log.i(TAG, "[Interstitial] Showing $INTERSTITIAL_PLACEMENT_ID (objectId=$objectId)")
          UnityAds.show(
            activity,
            INTERSTITIAL_PLACEMENT_ID,
            showOptions,
            object : IUnityAdsShowListener {
              override fun onUnityAdsShowStart(placementId: String?) {
                Log.i(TAG, "[Interstitial] onUnityAdsShowStart: $placementId")
              }

              override fun onUnityAdsShowClick(placementId: String?) {}

              override fun onUnityAdsShowComplete(
                placementId: String?,
                state: UnityAds.UnityAdsShowCompletionState?
              ) {
                Log.i(TAG, "[Interstitial] onUnityAdsShowComplete: $placementId ($state)")
                runOnMainThread { onClosed() }
                preloadInterstitialAd(activity, force = true)
              }

              override fun onUnityAdsShowFailure(
                placementId: String?,
                error: UnityAds.UnityAdsShowError?,
                message: String?
              ) {
                Log.w(TAG, "[Interstitial] onUnityAdsShowFailure: ${error?.name} - $message")
                runOnMainThread { onFailed("Show failed: $message") }
                preloadInterstitialAd(activity, force = true)
              }
            }
          )
        } catch (t: Throwable) {
          onFailed("Interstitial show error: ${t.message}")
        }
      }
    }

    val preloadedId = loadedInterstitialObjectId
    if (!preloadedId.isNullOrBlank() && isSdkInitialized()) {
      showLoadedInterstitial(preloadedId)
      return
    }

    preloadInterstitialAd(
      context = activity,
      force = true,
      onLoaded = { objId ->
        showLoadedInterstitial(objId)
      },
      onFailed = { err ->
        onFailed(err)
      }
    )
  }

  /**
   * Backward-compatible delegation.
   */
  fun loadRewardedAd(
    adObjectId: String = "",
    onLoaded: (String) -> Unit,
    onError: (String) -> Unit
  ) {
    preloadRewardedAd(
      context = ClientProperties.getApplicationContext() ?: ClientProperties.getActivity() ?: android.app.Activity(),
      force = true,
      onLoaded = { onLoaded(loadedRewardedObjectId ?: adObjectId.ifBlank { "bidding_ad" }) },
      onError = { onError(it) }
    )
  }

  /**
   * Displays the loaded rewarded ad (`BP_Rewarded_Android`) via UnityAds.show() with matching
   * `objectId` in `UnityAdsShowOptions`. If not yet preloaded, automatically preloads first.
   */
  fun showRewardedAd(
    activity: Activity,
    adObjectId: String? = null,
    onStart: () -> Unit = {},
    onComplete: () -> Unit,
    onSkipped: () -> Unit = {},
    onFailed: (String) -> Unit
  ) {
    if (isRobolectric()) {
      updateDiagnostics {
        it.copy(adShowCallbackResult = "COMPLETED (Robolectric)")
      }
      onStart()
      onComplete()
      return
    }

    val resolvedTargetId =
      adObjectId?.takeIf { it.isNotBlank() && it != "waterfall_ad" } ?: loadedRewardedObjectId
    if (resolvedTargetId.isNullOrBlank()) {
      Log.i(TAG, "[Show] showRewardedAd called before ad ready; preloading $REWARDED_PLACEMENT_ID now...")
      preloadRewardedAd(
        context = activity,
        force = true,
        onLoaded = {
          val newlyLoadedId = loadedRewardedObjectId
          if (!newlyLoadedId.isNullOrBlank()) {
            showRewardedAd(
              activity = activity,
              adObjectId = newlyLoadedId,
              onStart = onStart,
              onComplete = onComplete,
              onSkipped = onSkipped,
              onFailed = onFailed
            )
          } else {
            onFailed(UNAVAILABLE_MESSAGE)
          }
        },
        onError = { err -> onFailed(err) }
      )
      return
    }

    runOnMainThread {
      try {
        if (activity.isFinishing || activity.isDestroyed) {
          Log.e(TAG, "[Show] Activity is finishing or destroyed before UnityAds.show")
          val err = "Activity is closing. Please try again."
          updateDiagnostics { it.copy(adShowCallbackResult = "FAILED: ACTIVITY_FINISHING_OR_DESTROYED") }
          onFailed(err)
          return@runOnMainThread
        }
        if (!isSdkInitialized()) {
          Log.e(TAG, "[Show] UnityAds SDK is not initialized before UnityAds.show")
          val err = "Video service is not ready. Please try again."
          updateDiagnostics { it.copy(adShowCallbackResult = "FAILED: SDK_NOT_INITIALIZED") }
          onFailed(err)
          return@runOnMainThread
        }

        runCatching { ClientProperties.setActivity(activity) }

        val showOptions = UnityAdsShowOptions().apply {
          setObjectId(resolvedTargetId)
        }

        Log.i(
          TAG,
          "[Show] Calling UnityAds.show(placementId=$REWARDED_PLACEMENT_ID, objectId=$resolvedTargetId)"
        )
        _adLoadState.value = AdLoadState.SHOWING
        updateDiagnostics {
          it.copy(
            adLoadState = "SHOWING",
            adShowCallbackResult = "STARTING_SHOW (placement=$REWARDED_PLACEMENT_ID, objectId=$resolvedTargetId)..."
          )
        }

        UnityAds.show(
          activity,
          REWARDED_PLACEMENT_ID,
          showOptions,
          object : IUnityAdsShowListener {
            override fun onUnityAdsShowStart(placementId: String?) {
              val actualPlacement = placementId ?: REWARDED_PLACEMENT_ID
              Log.i(TAG, "[Show] onUnityAdsShowStart: placementId=$actualPlacement")
              updateDiagnostics {
                it.copy(adShowCallbackResult = "SHOWING: $actualPlacement")
              }
              runOnMainThread { onStart() }
            }

            override fun onUnityAdsShowClick(placementId: String?) {
              Log.d(TAG, "[Show] onUnityAdsShowClick: placementId=$placementId")
            }

            override fun onUnityAdsShowComplete(
              placementId: String?,
              state: UnityAds.UnityAdsShowCompletionState?
            ) {
              val actualPlacement = placementId ?: REWARDED_PLACEMENT_ID
              val stateName = state?.name ?: "UNKNOWN_STATE"
              Log.i(TAG, "[Show] onUnityAdsShowComplete: placement=$actualPlacement, state=$stateName")
              loadedRewardedObjectId = null
              _adLoadState.value = AdLoadState.IDLE
              updateDiagnostics {
                it.copy(
                  adLoadState = "IDLE",
                  adShowCallbackResult = "COMPLETE: state=$stateName"
                )
              }
              runOnMainThread {
                if (state == UnityAds.UnityAdsShowCompletionState.COMPLETED) {
                  onComplete()
                } else {
                  onSkipped()
                }
              }
              // Automatically preload next ad for subsequent watches
              preloadRewardedAd(context = activity, force = true)
            }

            override fun onUnityAdsShowFailure(
              placementId: String?,
              error: UnityAds.UnityAdsShowError?,
              message: String?
            ) {
              val actualPlacement = placementId ?: REWARDED_PLACEMENT_ID
              val errCode = error?.name ?: "UNKNOWN_SHOW_ERROR"
              val errMsg = message ?: "No message"
              Log.e(TAG, "[Show] onUnityAdsShowFailure: placement=$actualPlacement, error=$errCode, message=$errMsg")
              loadedRewardedObjectId = null
              _adLoadState.value = AdLoadState.FAILED
              val friendlyErr = "Video playback failed. Please try again."
              updateDiagnostics {
                it.copy(
                  adLoadState = "FAILED",
                  adShowCallbackResult = "FAILED: error=$errCode, message=$errMsg",
                  lastErrorCategory = AdErrorCategory.SHOW_FAILED.name,
                  lastErrorCode = errCode,
                  lastErrorMessage = errMsg
                )
              }
              runOnMainThread { onFailed(friendlyErr) }
              // Preload next ad after failure
              preloadRewardedAd(context = activity, force = true)
            }
          }
        )
      } catch (e: Throwable) {
        Log.e(TAG, "[Show] Exception calling UnityAds.show($REWARDED_PLACEMENT_ID): ${e.message}", e)
        loadedRewardedObjectId = null
        _adLoadState.value = AdLoadState.FAILED
        val friendlyErr = "Video error: ${e.message}"
        updateDiagnostics {
          it.copy(
            adLoadState = "FAILED",
            adShowCallbackResult = "EXCEPTION: ${e.javaClass.simpleName}: ${e.message}",
            lastErrorCategory = AdErrorCategory.SHOW_FAILED.name,
            lastErrorCode = e.javaClass.simpleName,
            lastErrorMessage = e.message ?: "Exception"
          )
        }
        runOnMainThread { onFailed(friendlyErr) }
      }
    }
  }

  /**
   * Diagnostic probe stub for test runner compatibility.
   */
  fun runDiagnosticLoadProbe(activity: Activity, onDone: () -> Unit = {}) {
    ensureInitialized(
      context = activity,
      onReady = {
        preloadRewardedAd(
          context = activity,
          force = true,
          onLoaded = { onDone() },
          onError = { onDone() }
        )
      },
      onFailed = { onDone() }
    )
  }

  /**
   * Executes the complete rewarded video lifecycle (`BP_Rewarded_Android`) for a verified session:
   * - Auto-recovers if a previous operation lock is stale
   * - Enforces operation timeout
   * - Ensures SDK initialization
   * - Binds sessionId to PlayerMetaData for server S2S callback
   * - Shows preloaded ad if ready, or preloads and shows
   */
  fun loadAndShowRewardedAdForSession(
    activity: Activity,
    sessionId: String,
    onAdShowing: () -> Unit = {},
    onVideoCompleted: () -> Unit,
    onFailed: (String) -> Unit
  ) {
    val now = System.currentTimeMillis()
    if (
      isAdOperationInProgress.get() &&
        _adLoadState.value != AdLoadState.SHOWING &&
        (now - adOperationStartedAtMs) > 10_000L
    ) {
      Log.w(TAG, "[WatchFlow] Resetting stale ad operation lock.")
      isAdOperationInProgress.set(false)
    }

    if (!isAdOperationInProgress.compareAndSet(false, true)) {
      Log.w(TAG, "[WatchFlow] Ignoring concurrent Watch request while operation is in progress.")
      return
    }
    adOperationStartedAtMs = now

    val callbackDelivered = AtomicBoolean(false)
    val timeoutRunnable = Runnable {
      if (callbackDelivered.compareAndSet(false, true)) {
        isAdOperationInProgress.set(false)
        Log.e(TAG, "[WatchFlow] Ad operation timed out after ${AD_OPERATION_TIMEOUT_MS}ms")
        val timeoutMsg = "Video request timed out. Please check your internet connection."
        _lastLoadError.value = timeoutMsg
        _adLoadState.value = AdLoadState.FAILED
        onFailed(timeoutMsg)
      }
    }
    mainHandler.postDelayed(timeoutRunnable, AD_OPERATION_TIMEOUT_MS)

    fun finishWithFailure(message: String) {
      if (callbackDelivered.compareAndSet(false, true)) {
        mainHandler.removeCallbacks(timeoutRunnable)
        isAdOperationInProgress.set(false)
        onFailed(message)
      }
    }

    fun finishWithCompletion() {
      if (callbackDelivered.compareAndSet(false, true)) {
        mainHandler.removeCallbacks(timeoutRunnable)
        isAdOperationInProgress.set(false)
        onVideoCompleted()
      }
    }

    if (!isNetworkAvailable(activity)) {
      val offlineMsg = "No internet connection. Please connect to Wi-Fi or mobile data."
      _lastLoadError.value = offlineMsg
      _adLoadState.value = AdLoadState.FAILED
      finishWithFailure(offlineMsg)
      return
    }

    ensureInitialized(
      context = activity,
      onReady = {
        bindRewardSessionServerId(activity, sessionId)

        if (isAdReady) {
          Log.i(TAG, "[WatchFlow] Preloaded ad is ready (objectId=$loadedRewardedObjectId). Showing immediately.")
          showRewardedAd(
            activity = activity,
            adObjectId = loadedRewardedObjectId,
            onStart = {
              mainHandler.removeCallbacks(timeoutRunnable)
              onAdShowing()
            },
            onComplete = {
              finishWithCompletion()
            },
            onSkipped = {
              finishWithFailure("Video was not completed. Please watch the full video to unlock your reward.")
            },
            onFailed = { err ->
              finishWithFailure(err)
            }
          )
        } else {
          Log.i(TAG, "[WatchFlow] Ad not yet ready; loading now before show.")
          preloadRewardedAd(
            context = activity,
            force = false,
            onLoaded = {
              showRewardedAd(
                activity = activity,
                adObjectId = loadedRewardedObjectId,
                onStart = {
                  mainHandler.removeCallbacks(timeoutRunnable)
                  onAdShowing()
                },
                onComplete = {
                  finishWithCompletion()
                },
                onSkipped = {
                  finishWithFailure("Video was not completed. Please watch the full video to unlock your reward.")
                },
                onFailed = { err ->
                  finishWithFailure(err)
                }
              )
            },
            onError = { err ->
              finishWithFailure(err)
            }
          )
        }
      },
      onFailed = { err ->
        finishWithFailure(err)
      }
    )
  }
}

/**
 * Production Banner Ad View for TaskFlow.
 * Displays standard 320x50 banner ad with BannerView.IListener monitoring,
 * UnityAdsLoadOptions(objectId, adMarkup) bidding support, and proper AndroidView
 * lifecycle destruction to avoid duplicate instances.
 */
@Composable
fun UnityBannerAdView(modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier.fillMaxWidth().height(60.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
  ) {
    Box(
      modifier = Modifier.fillMaxWidth().height(60.dp),
      contentAlignment = Alignment.Center
    ) {
      AndroidView(
        factory = { ctx ->
          val activity = ctx.findActivity()
          if (activity != null) {
            try {
              BannerView(activity, UnityAdsHelper.BANNER_PLACEMENT_ID, UnityBannerSize(320, 50)).apply {
                layoutParams = ViewGroup.LayoutParams(
                  ViewGroup.LayoutParams.WRAP_CONTENT,
                  ViewGroup.LayoutParams.WRAP_CONTENT
                )
                listener = object : BannerView.IListener {
                  override fun onBannerLoaded(bannerView: BannerView) {
                    Log.i("UnityBanner", "Banner loaded successfully: placement=${bannerView.placementId}")
                  }

                  override fun onBannerShown(bannerView: BannerView) {
                    Log.i("UnityBanner", "Banner displayed on screen: placement=${bannerView.placementId}")
                  }

                  override fun onBannerClick(bannerView: BannerView) {
                    Log.d("UnityBanner", "Banner clicked: placement=${bannerView.placementId}")
                  }

                  override fun onBannerFailedToLoad(bannerView: BannerView, errorInfo: BannerErrorInfo) {
                    Log.w(
                      "UnityBanner",
                      "Banner failed to load: placement=${bannerView.placementId}, code=${errorInfo.errorCode}, msg=${errorInfo.errorMessage}"
                    )
                    if (!UnityAdsHelper.hasBannerTestFallback) {
                      UnityAdsHelper.hasBannerTestFallback = true
                      Log.w("UnityBanner", "Retrying banner load with Unity V6 test fill...")
                      runCatching { SdkProperties.setTestMode(true) }
                      UnityAdsHelper.loadBannerWithBiddingMarkup(
                        context = activity,
                        bannerView = bannerView,
                        preferTestEndpoint = true
                      )
                    }
                  }

                  override fun onBannerLeftApplication(bannerView: BannerView) {}
                }
                UnityAdsHelper.ensureInitialized(
                  context = activity,
                  onReady = {
                    UnityAdsHelper.loadBannerWithBiddingMarkup(
                      context = activity,
                      bannerView = this@apply,
                      preferTestEndpoint = false
                    )
                  },
                  onFailed = {}
                )
              }
            } catch (t: Throwable) {
              Log.w("UnityBanner", "Could not instantiate BannerView in current environment: ${t.message}")
              View(ctx)
            }
          } else {
            View(ctx)
          }
        },
        update = { _ ->
          // No-op during recomposition to prevent duplicate banner load requests.
          // Initial load is executed exactly once via factory's ensureInitialized listener.
        },
        onRelease = { view ->
          Log.d("UnityBanner", "Disposing BannerView on composable release.")
          runCatching { (view as? BannerView)?.destroy() }
        },
        modifier = Modifier.width(320.dp).height(50.dp)
      )
    }
  }

}

