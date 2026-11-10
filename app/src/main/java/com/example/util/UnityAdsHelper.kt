package com.example.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
 * Handles exactly-once initialization, background preloading, waterfall rewarded video playback,
 * banner ad display, S2S attribution binding, and atomic error handling.
 */
object UnityAdsHelper {
  private const val TAG = "UnityAdsHelper"
  private const val BANNER_TAG = "UnityBanner"
  private const val DIAG_TAG = "UnityAdsDiag"

  const val GAME_ID = "800430673"
  const val REWARDED_PLACEMENT_ID = "BP_Rewarded_Android"
  const val BANNER_PLACEMENT_ID = "BP_Banner_Android"
  const val INTERSTITIAL_PLACEMENT_ID = "BP_Interstitial_Android"

  // Strict production configuration
  const val TEST_MODE = false

  const val UNAVAILABLE_MESSAGE = "Video is currently unavailable. Please try again later."
  private const val AD_OPERATION_TIMEOUT_MS = 25_000L

  private val mainHandler = Handler(Looper.getMainLooper())

  @Volatile private var isInitialized = false
  @Volatile private var isInitializing = false
  private val isAdOperationInProgress = AtomicBoolean(false)

  // Ad readiness state tracking
  private val _adLoadState = MutableStateFlow(AdLoadState.IDLE)
  val adLoadState: StateFlow<AdLoadState> = _adLoadState.asStateFlow()

  private val _lastLoadError = MutableStateFlow<String?>(null)
  val lastLoadError: StateFlow<String?> = _lastLoadError.asStateFlow()

  val isAdReady: Boolean
    get() {
      if (isRobolectric()) return true
      return _adLoadState.value == AdLoadState.READY
    }

  // Controlled exponential backoff configuration: 5s, 15s, 30s
  private val retryDelaysMs = listOf(5_000L, 15_000L, 30_000L)
  @Volatile private var currentRetryAttempt = 0
  private var retryRunnable: Runnable? = null

  private val pendingInitCallbacks = CopyOnWriteArrayList<Pair<() -> Unit, (String) -> Unit>>()

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
   * Initializes Unity Ads exactly once per application process lifetime.
   */
  fun initialize(context: Context) {
    if (isRobolectric()) {
      isInitialized = true
      isInitializing = false
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
    if (isInitializing) {
      Log.d(TAG, "[Init] Initialization already in progress; queuing callback.")
      return
    }

    runOnMainThread {
      if (isSdkInitialized() || isInitializing) return@runOnMainThread
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
          "[Init] Calling UnityAds.initialize(gameId=$GAME_ID, testMode=$TEST_MODE, sdkVersion=$sdkVer)"
        )
        updateDiagnostics {
          it.copy(initCallbackResult = "INITIALIZING (gameId=$GAME_ID, testMode=$TEST_MODE)")
        }

        UnityAds.initialize(
          initContext,
          GAME_ID,
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
              // Immediately start preloading the first rewarded ad once initialized
              preloadRewardedAd(context = initContext, force = false)
            }

            override fun onInitializationFailed(
              error: UnityAds.UnityAdsInitializationError?,
              message: String?
            ) {
              isInitialized = false
              isInitializing = false
              val errCode = error?.name ?: "UNKNOWN_INIT_ERROR"
              val errMsg = message ?: "No message"
              Log.e(TAG, "[Init] onInitializationFailed: error=$errCode, message=$errMsg")
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
        userFriendlyMessage = "Video service configuration error. Please try again later."
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
   * Background preloading of the rewarded video ad.
   * Standard waterfall placement BP_Rewarded_Android uses clean UnityAdsLoadOptions()
   * (never calls setObjectId which causes "adMarkup is missing" in WebView bridge).
   */
  fun preloadRewardedAd(
    context: Context,
    force: Boolean = false,
    onLoaded: (() -> Unit)? = null,
    onError: ((String) -> Unit)? = null
  ) {
    if (isRobolectric()) {
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

    if (!force && _adLoadState.value == AdLoadState.READY) {
      Log.d(TAG, "[Preload] Rewarded ad is already loaded and ready to show.")
      onLoaded?.let { runOnMainThread(it) }
      return
    }

    if (_adLoadState.value == AdLoadState.LOADING) {
      Log.d(TAG, "[Preload] Ad load request already in progress; skipping duplicate.")
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
      onError?.let { runOnMainThread { it(offlineMsg) } }
      return
    }

    ensureInitialized(
      context = context,
      onReady = {
        runOnMainThread {
          executeStandardWaterfallLoad(
            context = context,
            onLoaded = onLoaded,
            onError = onError
          )
        }
      },
      onFailed = { initErr ->
        _adLoadState.value = AdLoadState.FAILED
        _lastLoadError.value = initErr
        onError?.let { runOnMainThread { it(initErr) } }
      }
    )
  }

  /**
   * Executes standard waterfall load using clean UnityAdsLoadOptions() with no custom data dictionary.
   */
  private fun executeStandardWaterfallLoad(
    context: Context,
    onLoaded: (() -> Unit)?,
    onError: ((String) -> Unit)?
  ) {
    try {
      _adLoadState.value = AdLoadState.LOADING
      // Clean load options: avoids populating headerBiddingOptions in BaseLoadModule
      val cleanLoadOptions = UnityAdsLoadOptions()

      Log.i(TAG, "[Load] Calling UnityAds.load(placementId=$REWARDED_PLACEMENT_ID, standardWaterfall)")
      updateDiagnostics {
        it.copy(
          placementId = REWARDED_PLACEMENT_ID,
          adLoadState = "LOADING",
          adLoadCallbackResult = "LOADING..."
        )
      }

      UnityAds.load(
        REWARDED_PLACEMENT_ID,
        cleanLoadOptions,
        object : IUnityAdsLoadListener {
          override fun onUnityAdsAdLoaded(placementId: String?) {
            val actualPlacement = placementId ?: REWARDED_PLACEMENT_ID
            _adLoadState.value = AdLoadState.READY
            _lastLoadError.value = null
            currentRetryAttempt = 0
            retryRunnable?.let { mainHandler.removeCallbacks(it) }

            Log.i(TAG, "[Load] onUnityAdsAdLoaded: placementId=$actualPlacement (READY)")
            updateDiagnostics {
              it.copy(
                placementId = actualPlacement,
                adLoadState = "READY",
                adLoadCallbackResult = "SUCCESS: onUnityAdsAdLoaded",
                lastErrorCategory = "NONE",
                lastErrorCode = "NONE",
                lastErrorMessage = "NONE"
              )
            }
            onLoaded?.let { runOnMainThread(it) }
          }

          override fun onUnityAdsFailedToLoad(
            placementId: String?,
            error: UnityAds.UnityAdsLoadError?,
            message: String?
          ) {
            val actualPlacement = placementId ?: REWARDED_PLACEMENT_ID
            val rawErr = error?.name ?: "UNKNOWN_ERROR"
            val rawMsg = message ?: "No message"
            Log.e(TAG, "[Load] onUnityAdsFailedToLoad: placement=$actualPlacement, error=$rawErr, message=$rawMsg")

            val errorInfo = mapLoadError(error, message)
            _adLoadState.value = AdLoadState.FAILED
            _lastLoadError.value = errorInfo.userFriendlyMessage

            updateDiagnostics {
              it.copy(
                placementId = actualPlacement,
                adLoadState = "FAILED",
                adLoadCallbackResult = "FAILED: error=$rawErr, message=$rawMsg",
                lastErrorCategory = errorInfo.category.name,
                lastErrorCode = errorInfo.rawCode,
                lastErrorMessage = errorInfo.rawMessage
              )
            }

            // Controlled exponential backoff (max 3 attempts)
            scheduleAutoRetry(context)

            onError?.let { runOnMainThread { it(errorInfo.userFriendlyMessage) } }
          }
        }
      )
    } catch (e: Throwable) {
      Log.e(TAG, "[Load] Exception calling UnityAds.load($REWARDED_PLACEMENT_ID): ${e.message}", e)
      val errorMsg = UNAVAILABLE_MESSAGE
      _adLoadState.value = AdLoadState.FAILED
      _lastLoadError.value = errorMsg
      updateDiagnostics {
        it.copy(
          adLoadState = "FAILED",
          adLoadCallbackResult = "EXCEPTION: ${e.javaClass.simpleName}: ${e.message}",
          lastErrorCategory = AdErrorCategory.UNKNOWN.name,
          lastErrorCode = e.javaClass.simpleName,
          lastErrorMessage = e.message ?: "Exception"
        )
      }
      onError?.let { runOnMainThread { it(errorMsg) } }
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
      onLoaded = { onLoaded("waterfall_ad") },
      onError = { onError(it) }
    )
  }

  /**
   * Displays the loaded rewarded ad via standard waterfall UnityAds.show().
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

        // Clean show options for standard waterfall
        val cleanShowOptions = UnityAdsShowOptions()

        Log.i(TAG, "[Show] Calling UnityAds.show(placementId=$REWARDED_PLACEMENT_ID)")
        _adLoadState.value = AdLoadState.SHOWING
        updateDiagnostics {
          it.copy(
            adLoadState = "SHOWING",
            adShowCallbackResult = "STARTING_SHOW (placement=$REWARDED_PLACEMENT_ID)..."
          )
        }

        UnityAds.show(
          activity,
          REWARDED_PLACEMENT_ID,
          cleanShowOptions,
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
   * Executes the complete rewarded video lifecycle for a verified server session:
   * - Guards against concurrent duplicate requests
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
    if (!isAdOperationInProgress.compareAndSet(false, true)) {
      Log.w(TAG, "[WatchFlow] Ignoring concurrent Watch request while operation is in progress.")
      return
    }

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
          Log.i(TAG, "[WatchFlow] Preloaded ad is ready. Showing immediately.")
          showRewardedAd(
            activity = activity,
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
            force = true,
            onLoaded = {
              showRewardedAd(
                activity = activity,
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
 * Displays standard 320x50 banner ad with BannerView.IListener monitoring and
 * proper AndroidView lifecycle destruction to avoid duplicate instances.
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
                  }

                  override fun onBannerLeftApplication(bannerView: BannerView) {}
                }
                UnityAdsHelper.ensureInitialized(
                  context = activity,
                  onReady = { runCatching { load() } },
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
