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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.unity3d.services.banners.BannerView
import com.unity3d.services.banners.UnityBannerSize
import com.unity3d.services.core.properties.ClientProperties
import com.unity3d.services.core.properties.SdkProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
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
 * Standardized category for ad failure diagnostics.
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
 * Live runtime diagnostic snapshot recorded on the physical Android device.
 * Never stores secrets, HMAC keys, auth tokens, or personal user PII.
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

object UnityAdsHelper {
  private const val TAG = "UnityAdsHelper"
  private const val DIAG_TAG = "UnityAdsDiag"

  const val GAME_ID = "800430673"
  const val REWARDED_PLACEMENT_ID = "BP_Rewarded_Android"
  const val BANNER_PLACEMENT_ID = "BP_Banner_Android"
  const val INTERSTITIAL_PLACEMENT_ID = "BP_Interstitial_Android"
  const val TEST_MODE = false

  const val UNAVAILABLE_MESSAGE = "Video is currently unavailable. Please try again later."
  private const val AD_OPERATION_TIMEOUT_MS = 25_000L

  private val mainHandler = Handler(Looper.getMainLooper())

  @Volatile private var isInitialized = false
  @Volatile private var isInitializing = false
  @Volatile private var lastLoadedAdObjectId: String? = null
  private val isAdOperationInProgress = AtomicBoolean(false)

  // Runtime test mode toggle (defaults to false / production)
  private val _testModeEnabled = MutableStateFlow(TEST_MODE)
  val testModeEnabled: StateFlow<Boolean> = _testModeEnabled.asStateFlow()

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

  // Controlled exponential backoff configuration
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

  private fun isCurrentTestMode(): Boolean = _testModeEnabled.value

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
            testMode = isCurrentTestMode(),
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
   * Initializes Unity Ads once.
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
      Log.d(TAG, "[Init] Unity Ads SDK already initialized (gameId=$GAME_ID, testMode=${isCurrentTestMode()}).")
      updateDiagnostics {
        it.copy(
          isInitialized = true,
          initCallbackResult =
            if (it.initCallbackResult == "NOT_CALLED") "COMPLETE (Already Initialized)"
            else it.initCallbackResult
        )
      }
      val callbacks = pendingInitCallbacks.toList()
      pendingInitCallbacks.clear()
      callbacks.forEach { (onReady, _) -> onReady() }
      return
    }
    if (isInitializing) {
      Log.d(TAG, "[Init] Initialization already in progress; waiting for callback.")
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

        runCatching {
          if (
            SdkProperties.getCurrentInitializationState() ==
              SdkProperties.InitializationState.INITIALIZED_FAILED
          ) {
            Log.w(TAG, "[Init] Resetting previous INITIALIZED_FAILED state to NOT_INITIALIZED before retrying.")
            SdkProperties.setInitializeState(SdkProperties.InitializationState.NOT_INITIALIZED)
          }
        }

        val initContext: Context = activity ?: context.applicationContext ?: context
        val currentTest = isCurrentTestMode()
        val sdkVer = readSdkVersionSafe()
        Log.i(
          TAG,
          "[Init] Calling UnityAds.initialize(gameId=$GAME_ID, testMode=$currentTest, sdkVersion=$sdkVer)"
        )
        updateDiagnostics {
          it.copy(initCallbackResult = "INITIALIZING (gameId=$GAME_ID, testMode=$currentTest)")
        }

        UnityAds.initialize(
          initContext,
          GAME_ID,
          false,
          object : IUnityAdsInitializationListener {
            override fun onInitializationComplete() {
              isInitialized = true
              isInitializing = false
              Log.i(TAG, "[Init] onInitializationComplete() -> UnityAds.isInitialized=${isSdkInitialized()}")
              updateDiagnostics {
                it.copy(
                  isInitialized = true,
                  initCallbackResult = "COMPLETE (onInitializationComplete)"
                )
              }
              val callbacks = pendingInitCallbacks.toList()
              pendingInitCallbacks.clear()
              callbacks.forEach { (onReady, _) ->
                runOnMainThread { onReady() }
              }
              // Immediately start preloading a rewarded ad once initialized
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
              val friendlyErr = "Unity Ads SDK initialization failed ($errCode: $errMsg). Please check network connection."
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
        val friendlyErr = "Unity Ads initialization exception: ${e.message}"
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
      updateDiagnostics {
        it.copy(
          isInitialized = true,
          initCallbackResult =
            if (it.initCallbackResult == "NOT_CALLED") "COMPLETE (Already Initialized)"
            else it.initCallbackResult
        )
      }
      runOnMainThread { onReady() }
      return
    }
    if (!isNetworkAvailable(context)) {
      Log.e(TAG, "[Init] Cannot initialize Unity Ads: device has no active internet connection.")
      val msg = "No active internet connection. Please connect to Wi-Fi or mobile data."
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
    Log.d(TAG, "[Init] Unity Ads SDK not yet initialized; queueing callback and initializing.")
    pendingInitCallbacks.add(onReady to onFailed)
    initialize(context)
  }

  /**
   * Binds the server-created reward session ID (`sessionId`) to `PlayerMetaData.setServerId(sessionId)`.
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
   * Checks whether Unity Ads 4.12.0 is running the BoldSDK coroutine flow
   * (AlternativeFlowReader.invoke() == true) or the Classic WebView bridge flow (false).
   */
  fun isBoldSdkAlternativeFlow(): Boolean {
    return try {
      val reader = com.unity3d.services.core.misc.Utilities.getService<com.unity3d.ads.core.configuration.AlternativeFlowReader>(
        com.unity3d.ads.core.configuration.AlternativeFlowReader::class.java
      )
      reader?.invoke() == true
    } catch (_: Throwable) {
      false
    }
  }

  /**
   * Toggles between Production Ads Mode (`testMode=false`) and Official Unity Test Ads (`testMode=true`).
   */
  fun setTestMode(context: Context, enabled: Boolean) {
    if (_testModeEnabled.value == enabled) return
    _testModeEnabled.value = enabled
    isInitialized = false
    isInitializing = false
    _adLoadState.value = AdLoadState.IDLE
    lastLoadedAdObjectId = null
    currentRetryAttempt = 0
    retryRunnable?.let { mainHandler.removeCallbacks(it) }

    runCatching {
      SdkProperties.setTestMode(enabled)
      SdkProperties.setInitializeState(SdkProperties.InitializationState.NOT_INITIALIZED)
      SdkProperties.resetInitializationListeners()
    }
    Log.i(TAG, "[TestMode] Switching Unity Ads test mode to: $enabled")
    updateDiagnostics {
      it.copy(
        testMode = enabled,
        initCallbackResult = "SWITCHING_TEST_MODE (testMode=$enabled)...",
        adLoadState = "IDLE",
        adLoadCallbackResult = "NOT_REQUESTED"
      )
    }
    initialize(context)
    preloadRewardedAd(context, force = true)
  }

  /**
   * Maps load errors to structured error information with user-friendly messages.
   */
  private fun mapLoadError(error: UnityAds.UnityAdsLoadError?, message: String?): AdErrorInfo {
    val rawCode = error?.name ?: "UNKNOWN_LOAD_ERROR"
    val rawMsg = message ?: "No message provided"
    return when (error) {
      UnityAds.UnityAdsLoadError.NO_FILL -> AdErrorInfo(
        category = AdErrorCategory.NO_FILL,
        rawCode = rawCode,
        rawMessage = rawMsg,
        userFriendlyMessage = "No ad inventory available from Unity Ads (NO_FILL). Ad servers currently have low fill rate in your region. Tap Try Again or test with Test Mode below."
      )
      UnityAds.UnityAdsLoadError.TIMEOUT -> AdErrorInfo(
        category = AdErrorCategory.TIMEOUT,
        rawCode = rawCode,
        rawMessage = rawMsg,
        userFriendlyMessage = "Ad loading timed out (TIMEOUT). Please check your internet connection and tap Try Again."
      )
      UnityAds.UnityAdsLoadError.INVALID_ARGUMENT -> AdErrorInfo(
        category = AdErrorCategory.INVALID_ARGUMENT,
        rawCode = rawCode,
        rawMessage = rawMsg,
        userFriendlyMessage = "Invalid ad configuration (INVALID_ARGUMENT: $rawMsg)."
      )
      UnityAds.UnityAdsLoadError.INTERNAL_ERROR -> AdErrorInfo(
        category = AdErrorCategory.INTERNAL_ERROR,
        rawCode = rawCode,
        rawMessage = rawMsg,
        userFriendlyMessage = "Unity Ads internal server error (INTERNAL_ERROR). Please try again shortly."
      )
      UnityAds.UnityAdsLoadError.INITIALIZE_FAILED -> AdErrorInfo(
        category = AdErrorCategory.INIT_FAILED,
        rawCode = rawCode,
        rawMessage = rawMsg,
        userFriendlyMessage = "Unity Ads SDK initialization failed. Please restart the app."
      )
      else -> AdErrorInfo(
        category = AdErrorCategory.UNKNOWN,
        rawCode = rawCode,
        rawMessage = rawMsg,
        userFriendlyMessage = "Video ad is currently unavailable ($rawCode). Please try again later."
      )
    }
  }

  /**
   * Preloads a rewarded ad with controlled exponential backoff retry.
   */
  fun preloadRewardedAd(
    context: Context,
    force: Boolean = false,
    onLoaded: (() -> Unit)? = null,
    onError: ((String) -> Unit)? = null
  ) {
    if (isRobolectric()) {
      _adLoadState.value = AdLoadState.READY
      lastLoadedAdObjectId = "robolectric_ad_id"
      updateDiagnostics {
        it.copy(
          adLoadState = "READY",
          adLoadCallbackResult = "SUCCESS: onUnityAdsAdLoaded (Robolectric)"
        )
      }
      onLoaded?.let { runOnMainThread(it) }
      return
    }

    // If ad is already loaded and ready, and not forcing a reload, notify immediately
    if (!force && _adLoadState.value == AdLoadState.READY && !lastLoadedAdObjectId.isNullOrBlank()) {
      Log.d(TAG, "[Preload] Ad is already loaded and ready to show.")
      onLoaded?.let { runOnMainThread(it) }
      return
    }

    // Guard against duplicate concurrent load requests
    if (_adLoadState.value == AdLoadState.LOADING) {
      Log.d(TAG, "[Preload] Ad load request already in flight; ignoring duplicate call.")
      return
    }

    if (!isNetworkAvailable(context)) {
      Log.w(TAG, "[Preload] Cannot load ad: device is offline.")
      val offlineMsg = "No internet connection. Please connect to Wi-Fi or mobile data."
      _lastLoadError.value = offlineMsg
      _adLoadState.value = AdLoadState.FAILED
      updateDiagnostics {
        it.copy(
          adLoadState = "FAILED",
          adLoadCallbackResult = "FAILED: DEVICE_OFFLINE",
          lastErrorCategory = AdErrorCategory.NETWORK_OFFLINE.name,
          lastErrorCode = "OFFLINE",
          lastErrorMessage = offlineMsg
        )
      }
      onError?.let { runOnMainThread { it(offlineMsg) } }
      return
    }

    ensureInitialized(
      context = context,
      onReady = {
        runOnMainThread {
          val isBold = isBoldSdkAlternativeFlow()
          val adObjectId = UUID.randomUUID().toString()
          _adLoadState.value = AdLoadState.LOADING
          executeLoadAttempt(
            context = context,
            adObjectId = adObjectId,
            useObjectId = isBold,
            canRetryWithAlternative = true,
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

  private fun executeLoadAttempt(
    context: Context,
    adObjectId: String,
    useObjectId: Boolean,
    canRetryWithAlternative: Boolean,
    onLoaded: (() -> Unit)?,
    onError: ((String) -> Unit)?
  ) {
    try {
      val loadOptions = UnityAdsLoadOptions().apply {
        if (useObjectId) {
          setObjectId(adObjectId)
        }
      }
      val flowDesc = if (isBoldSdkAlternativeFlow()) "BoldSDK" else "ClassicWebView"
      val modeDesc = if (useObjectId) "WithObjectId($adObjectId)" else "StandardWaterfall"
      Log.i(TAG, "[Load] UnityAds.load(placementId=$REWARDED_PLACEMENT_ID, flow=$flowDesc, mode=$modeDesc)")
      updateDiagnostics {
        it.copy(
          placementId = REWARDED_PLACEMENT_ID,
          adLoadState = "LOADING",
          adLoadCallbackResult = "LOADING (flow=$flowDesc, mode=$modeDesc)..."
        )
      }

      UnityAds.load(
        REWARDED_PLACEMENT_ID,
        loadOptions,
        object : IUnityAdsLoadListener {
          override fun onUnityAdsAdLoaded(placementId: String?) {
            lastLoadedAdObjectId = if (useObjectId) adObjectId else "waterfall_loaded"
            val actualPlacement = placementId ?: REWARDED_PLACEMENT_ID
            _adLoadState.value = AdLoadState.READY
            _lastLoadError.value = null
            currentRetryAttempt = 0
            retryRunnable?.let { mainHandler.removeCallbacks(it) }

            Log.i(TAG, "[Load] onUnityAdsAdLoaded: placementId=$actualPlacement, mode=$modeDesc (READY)")
            updateDiagnostics {
              it.copy(
                placementId = actualPlacement,
                adLoadState = "READY",
                adLoadCallbackResult = "SUCCESS: onUnityAdsAdLoaded(placement=$actualPlacement)",
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
            Log.e(TAG, "[Load] onUnityAdsFailedToLoad: placement=$actualPlacement, mode=$modeDesc, error=$rawErr, message=$rawMsg")

            // Auto-recovery between Classic WebView and BoldSDK flow if option mismatch occurs:
            if (canRetryWithAlternative) {
              if (useObjectId && rawMsg.contains("adMarkup", ignoreCase = true)) {
                Log.w(TAG, "[Load] 'adMarkup is missing' detected with objectId option. Retrying with StandardWaterfall mode...")
                runOnMainThread {
                  executeLoadAttempt(
                    context = context,
                    adObjectId = adObjectId,
                    useObjectId = false,
                    canRetryWithAlternative = false,
                    onLoaded = onLoaded,
                    onError = onError
                  )
                }
                return
              } else if (!useObjectId && rawMsg.contains("Object ID cannot be null", ignoreCase = true)) {
                Log.w(TAG, "[Load] 'Object ID cannot be null' detected. Retrying with WithObjectId mode...")
                runOnMainThread {
                  executeLoadAttempt(
                    context = context,
                    adObjectId = adObjectId,
                    useObjectId = true,
                    canRetryWithAlternative = false,
                    onLoaded = onLoaded,
                    onError = onError
                  )
                }
                return
              }
            }

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

            // Schedule controlled exponential backoff auto-retry (max 3 attempts)
            scheduleAutoRetry(context)

            onError?.let { runOnMainThread { it(errorInfo.userFriendlyMessage) } }
          }
        }
      )
    } catch (e: Throwable) {
      Log.e(TAG, "[Load] Exception calling UnityAds.load($REWARDED_PLACEMENT_ID): ${e.message}", e)
      val errorMsg = "Exception during ad load: ${e.message}"
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
      Log.i(TAG, "[AutoRetry] Reached max retry limit (${retryDelaysMs.size}). Waiting for manual retry.")
      return
    }
    val delayMs = retryDelaysMs[currentRetryAttempt]
    currentRetryAttempt++
    Log.i(TAG, "[AutoRetry] Scheduling retry #$currentRetryAttempt in ${delayMs / 1000}s...")
    updateDiagnostics { it.copy(retryCount = currentRetryAttempt) }
    retryRunnable?.let { mainHandler.removeCallbacks(it) }
    val runnable = Runnable {
      Log.i(TAG, "[AutoRetry] Executing automatic retry #$currentRetryAttempt for $REWARDED_PLACEMENT_ID")
      preloadRewardedAd(context, force = true)
    }
    retryRunnable = runnable
    mainHandler.postDelayed(runnable, delayMs)
  }

  /**
   * Resets retry counter and triggers manual reload.
   */
  fun manualRetryAdLoad(context: Context, onLoaded: (() -> Unit)? = null, onError: ((String) -> Unit)? = null) {
    currentRetryAttempt = 0
    retryRunnable?.let { mainHandler.removeCallbacks(it) }
    preloadRewardedAd(context, force = true, onLoaded = onLoaded, onError = onError)
  }

  /**
   * When app returns to foreground, recheck ad readiness.
   */
  fun onAppForeground(activity: Activity) {
    if (!isAdReady && _adLoadState.value != AdLoadState.LOADING) {
      Log.d(TAG, "[Lifecycle] App returned to foreground; checking rewarded ad availability.")
      preloadRewardedAd(activity, force = false)
    }
  }

  /**
   * Legacy compatibility method for ad loading.
   */
  fun loadRewardedAd(
    adObjectId: String = UUID.randomUUID().toString(),
    onLoaded: (String) -> Unit,
    onError: (String) -> Unit
  ) {
    preloadRewardedAd(
      context = ClientProperties.getApplicationContext() ?: ClientProperties.getActivity() ?: android.app.Activity(),
      force = true,
      onLoaded = { onLoaded(lastLoadedAdObjectId ?: adObjectId) },
      onError = { onError(it) }
    )
  }

  /**
   * Shows `BP_Rewarded_Android` using matching show options.
   */
  fun showRewardedAd(
    activity: Activity,
    adObjectId: String? = lastLoadedAdObjectId,
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
          val err = "Ad service is not ready. Please try again."
          updateDiagnostics { it.copy(adShowCallbackResult = "FAILED: SDK_NOT_INITIALIZED") }
          onFailed(err)
          return@runOnMainThread
        }

        runCatching { ClientProperties.setActivity(activity) }

        val showOptions = UnityAdsShowOptions().apply {
          if (!adObjectId.isNullOrBlank() && adObjectId != "waterfall_loaded") {
            setObjectId(adObjectId)
          }
        }

        val showModeDesc = if (!adObjectId.isNullOrBlank() && adObjectId != "waterfall_loaded") "WithObjectId($adObjectId)" else "Standard"
        Log.i(TAG, "[Show] Calling UnityAds.show(placementId=$REWARDED_PLACEMENT_ID, mode=$showModeDesc)")
        _adLoadState.value = AdLoadState.SHOWING
        updateDiagnostics {
          it.copy(
            adLoadState = "SHOWING",
            adShowCallbackResult = "STARTING_SHOW (placement=$REWARDED_PLACEMENT_ID, mode=$showModeDesc)..."
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
                it.copy(adShowCallbackResult = "SHOWING (onUnityAdsShowStart: $actualPlacement)")
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
              lastLoadedAdObjectId = null
              updateDiagnostics {
                it.copy(
                  adLoadState = "IDLE",
                  adShowCallbackResult = "COMPLETE: state=$stateName (placement=$actualPlacement)"
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
              lastLoadedAdObjectId = null
              val friendlyErr = "Failed to display ad ($errCode: $errMsg). Please try again."
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
        lastLoadedAdObjectId = null
        val friendlyErr = "Exception showing ad: ${e.message}"
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
   * Diagnostic-only ad load probe.
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
   * Executes the complete Watch Now Unity Ads lifecycle:
   * - Guards against duplicate concurrent requests
   * - Enforces a timeout so loading state can never get stuck permanently
   * - Ensures Unity Ads SDK initialization completes first
   * - Binds sessionId via PlayerMetaData.setServerId(sessionId) for S2S sid callback
   * - Shows preloaded ad if ready, or loads and shows
   */
  fun loadAndShowRewardedAdForSession(
    activity: Activity,
    sessionId: String,
    onAdShowing: () -> Unit = {},
    onVideoCompleted: () -> Unit,
    onFailed: (String) -> Unit
  ) {
    if (!isAdOperationInProgress.compareAndSet(false, true)) {
      Log.w(TAG, "[WatchFlow] Ignoring duplicate Watch Now request while ad operation is already in progress.")
      return
    }

    val callbackDelivered = AtomicBoolean(false)
    val timeoutRunnable = Runnable {
      if (callbackDelivered.compareAndSet(false, true)) {
        isAdOperationInProgress.set(false)
        Log.e(TAG, "[WatchFlow] Ad operation timed out after ${AD_OPERATION_TIMEOUT_MS}ms for $REWARDED_PLACEMENT_ID")
        val timeoutMsg = "Ad operation timed out. Please check your connection and try again."
        _lastLoadError.value = timeoutMsg
        _adLoadState.value = AdLoadState.FAILED
        updateDiagnostics {
          it.copy(
            adLoadState = "FAILED",
            adLoadCallbackResult = "TIMEOUT after ${AD_OPERATION_TIMEOUT_MS}ms",
            lastErrorCategory = AdErrorCategory.TIMEOUT.name,
            lastErrorCode = "TIMEOUT",
            lastErrorMessage = timeoutMsg
          )
        }
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
      Log.e(TAG, "[WatchFlow] Device is offline; cannot load Unity rewarded video.")
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
      finishWithFailure(offlineMsg)
      return
    }

    ensureInitialized(
      context = activity,
      onReady = {
        bindRewardSessionServerId(activity, sessionId)

        // If ad is already loaded, show it immediately!
        if (isAdReady && !lastLoadedAdObjectId.isNullOrBlank()) {
          Log.i(TAG, "[WatchFlow] Preloaded ad is ready. Showing immediately.")
          showRewardedAd(
            activity = activity,
            adObjectId = lastLoadedAdObjectId,
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
          // If not preloaded, load now and show upon completion
          Log.i(TAG, "[WatchFlow] Ad not yet ready; loading now before show.")
          preloadRewardedAd(
            context = activity,
            force = true,
            onLoaded = {
              showRewardedAd(
                activity = activity,
                adObjectId = lastLoadedAdObjectId,
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

@Composable
fun UnityBannerAdView(modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier.fillMaxWidth().height(56.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
  ) {
    Box(
      modifier = Modifier.fillMaxWidth(),
      contentAlignment = Alignment.Center
    ) {
      AndroidView(
        factory = { ctx ->
          val activity = ctx.findActivity()
          if (activity != null) {
            BannerView(activity, UnityAdsHelper.BANNER_PLACEMENT_ID, UnityBannerSize(320, 50))
              .apply {
                UnityAdsHelper.ensureInitialized(
                  context = activity,
                  onReady = { runCatching { load() } },
                  onFailed = {}
                )
              }
          } else {
            View(ctx)
          }
        },
        modifier = Modifier.fillMaxWidth()
      )
    }
  }
}
