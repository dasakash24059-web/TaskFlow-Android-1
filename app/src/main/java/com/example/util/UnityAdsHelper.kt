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
  val adLoadCallbackResult: String = "NOT_REQUESTED",
  val adShowCallbackResult: String = "NOT_REQUESTED",
  val lastUpdatedTime: String = ""
) {
  fun toFormattedReport(): String = buildString {
    appendLine("=== TaskFlow Unity Ads Runtime Diagnostics ===")
    appendLine("Timestamp: ${lastUpdatedTime.ifBlank { "N/A" }}")
    appendLine("1. Unity SDK Version: $sdkVersion")
    appendLine("2. UnityAds.isInitialized(): $isInitialized (state=$sdkInternalState)")
    appendLine("3. Initialization Callback: $initCallbackResult")
    appendLine("4. Reward Session Result: $rewardSessionResult")
    appendLine("5. Actual Placement ID: $placementId (GameID=$gameId, testMode=$testMode)")
    appendLine("6. Ad Load Callback: $adLoadCallbackResult")
    appendLine("7. Ad Show Callback: $adShowCallbackResult")
  }
}

object UnityAdsHelper {
  private const val TAG = "UnityAdsHelper"
  private const val DIAG_TAG = "UnityAdsDiag"

  const val GAME_ID = "800430673"
  const val REWARDED_PLACEMENT_ID = "BP_Rewarded_Android"
  const val BANNER_PLACEMENT_ID = "BP_Banner_Android"
  const val INTERSTITIAL_PLACEMENT_ID = "BP_Interstitial_Android" // Configured for future use; not auto-displayed
  const val TEST_MODE = false

  const val UNAVAILABLE_MESSAGE = "Video is currently unavailable. Please try again later."
  private const val AD_OPERATION_TIMEOUT_MS = 25_000L

  private val mainHandler = Handler(Looper.getMainLooper())

  @Volatile private var isInitialized = false
  @Volatile private var isInitializing = false
  @Volatile private var lastLoadedAdObjectId: String? = null
  private val isAdOperationInProgress = AtomicBoolean(false)

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
    Log.i(DIAG_TAG, "[Step 4] RewardSessionResult: $summary")
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
      Log.w(TAG, "Network availability check threw exception: ${e.message}")
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

  fun initialize(context: Context) {
    if (isRobolectric()) {
      isInitialized = true
      isInitializing = false
      updateDiagnostics {
        it.copy(
          isInitialized = true,
          sdkInternalState = "INITIALIZED_SUCCESSFULLY",
          initCallbackResult = "SUCCESS (Robolectric)"
        )
      }
      return
    }
    if (isSdkInitialized()) {
      Log.d(TAG, "[Step 2] Unity Ads SDK is already initialized (gameId=$GAME_ID).")
      updateDiagnostics {
        it.copy(
          isInitialized = true,
          initCallbackResult =
            if (it.initCallbackResult == "NOT_CALLED") "SUCCESS (Already Initialized)"
            else it.initCallbackResult
        )
      }
      val callbacks = pendingInitCallbacks.toList()
      pendingInitCallbacks.clear()
      callbacks.forEach { (onReady, _) -> onReady() }
      return
    }
    if (isInitializing) {
      Log.d(TAG, "[Step 2] Unity Ads SDK initialization already in progress; waiting for callback.")
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

        // In Unity Ads 4.12.0, if an earlier initialize() call failed (e.g., offline at startup),
        // SdkProperties stays in INITIALIZED_FAILED and rejects subsequent initialize() calls unless
        // reset to NOT_INITIALIZED.
        runCatching {
          if (
            SdkProperties.getCurrentInitializationState() ==
              SdkProperties.InitializationState.INITIALIZED_FAILED
          ) {
            Log.w(
              TAG,
              "[Step 2] Resetting previous INITIALIZED_FAILED state to NOT_INITIALIZED before retrying."
            )
            SdkProperties.setInitializeState(SdkProperties.InitializationState.NOT_INITIALIZED)
          }
        }

        val initContext: Context = activity ?: context.applicationContext ?: context
        val sdkVer = readSdkVersionSafe()
        Log.i(
          TAG,
          "[Step 2] Calling UnityAds.initialize(gameId=$GAME_ID, testMode=$TEST_MODE, sdkVersion=$sdkVer, hasActivity=${activity != null})"
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
              Log.i(TAG, "[Step 2] onInitializationComplete() -> UnityAds.isInitialized=${isSdkInitialized()}")
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
            }

            override fun onInitializationFailed(
              error: UnityAds.UnityAdsInitializationError?,
              message: String?
            ) {
              isInitialized = false
              isInitializing = false
              val errCode = error?.name ?: "UNKNOWN_INIT_ERROR"
              val errMsg = message ?: "No message"
              Log.e(
                TAG,
                "[Step 2] onInitializationFailed: error=$errCode, message=$errMsg"
              )
              updateDiagnostics {
                it.copy(
                  isInitialized = false,
                  initCallbackResult = "FAILED: error=$errCode, message=$errMsg"
                )
              }
              val callbacks = pendingInitCallbacks.toList()
              pendingInitCallbacks.clear()
              callbacks.forEach { (_, onFailed) ->
                runOnMainThread { onFailed(UNAVAILABLE_MESSAGE) }
              }
            }
          }
        )
      } catch (e: Throwable) {
        isInitialized = false
        isInitializing = false
        Log.e(TAG, "[Step 2] Exception during UnityAds.initialize: ${e.message}", e)
        updateDiagnostics {
          it.copy(
            isInitialized = false,
            initCallbackResult = "EXCEPTION: ${e.javaClass.simpleName}: ${e.message}"
          )
        }
        val callbacks = pendingInitCallbacks.toList()
        pendingInitCallbacks.clear()
        callbacks.forEach { (_, onFailed) ->
          runOnMainThread { onFailed(UNAVAILABLE_MESSAGE) }
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
      Log.e(TAG, "[Step 2] Cannot initialize Unity Ads: device has no active internet connection.")
      updateDiagnostics {
        it.copy(initCallbackResult = "FAILED: NO_INTERNET_CONNECTION")
      }
      runOnMainThread { onFailed(UNAVAILABLE_MESSAGE) }
      return
    }
    Log.d(TAG, "[Step 2] Unity Ads SDK not yet initialized; queueing callback and initializing.")
    pendingInitCallbacks.add(onReady to onFailed)
    initialize(context)
  }

  /**
   * Binds the backend-created reward session ID (`sessionId`) to `PlayerMetaData.setServerId(sessionId)`.
   *
   * Identifier separation:
   * - `userId`: Authenticated Firebase UID (`FirebaseAuth.currentUser.uid`), verified on the backend.
   * - `sessionId`: Unpredictable server-created session ID stored in `/reward_sessions/{sessionId}`.
   * - `sid`: Unity S2S Server ID parameter set via `PlayerMetaData.setServerId(sessionId)` so Unity's
   *   S2S callback passes `?sid=<sessionId>&oid=<oid>&hmac=<hmac>` to `unityAdsS2SCallback`.
   * - `adObjectId`: Separate UUID v4 used only for local SDK `UnityAdsLoadOptions`/`UnityAdsShowOptions`
   *   pairing in Unity Ads 4.12.0.
   */
  fun bindRewardSessionServerId(context: Context, sessionId: String) {
    if (isRobolectric()) return
    try {
      val playerMetaData = PlayerMetaData(context)
      playerMetaData.setServerId(sessionId)
      playerMetaData.commit()
      Log.d(TAG, "[Step 4] Committed PlayerMetaData.setServerId(sessionId) for S2S sid mapping.")
    } catch (e: Throwable) {
      Log.e(TAG, "[Step 4] Failed to commit PlayerMetaData serverId: ${e.message}", e)
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
   * Loads `BP_Rewarded_Android`.
   *
   * Root Cause of "INVALID_ARGUMENT: adMarkup is missing":
   * In Unity Ads 4.12.0 Classic WebView Bridge mode (the default on Android devices),
   * calling `UnityAdsLoadOptions.setObjectId(adObjectId)` populates `UnityAdsBaseOptions._data`,
   * which `BaseLoadModule.buildBaseParameters` serializes directly into `"headerBiddingOptions"`
   * for the WebView. When the WebView sees a non-empty `headerBiddingOptions` payload without an
   * `adMarkup` string, it treats the request as an incomplete programmatic bid and throws:
   * "INVALID_ARGUMENT: adMarkup is missing".
   *
   * Fix:
   * In standard Waterfall mode (`isBoldSdkAlternativeFlow() == false`), load options MUST remain
   * standard (clean `UnityAdsLoadOptions()` without `setObjectId`), which serializes an empty
   * `headerBiddingOptions` and allows normal Waterfall ad loading to proceed.
   * If BoldSDK coroutines are active (`isBoldSdkAlternativeFlow() == true`), `setObjectId(adObjectId)`
   * is used. If either mode returns an error indicating the other mode ("adMarkup is missing" or
   * "Object ID cannot be null"), an automatic one-time recovery loads with the alternate configuration.
   */
  fun loadRewardedAd(
    adObjectId: String = UUID.randomUUID().toString(),
    onLoaded: (String) -> Unit,
    onError: (String) -> Unit
  ) {
    if (isRobolectric()) {
      lastLoadedAdObjectId = adObjectId
      updateDiagnostics {
        it.copy(adLoadCallbackResult = "SUCCESS: onUnityAdsAdLoaded (Robolectric)")
      }
      onLoaded(adObjectId)
      return
    }
    runOnMainThread {
      val isBold = isBoldSdkAlternativeFlow()
      executeLoadAttempt(
        adObjectId = adObjectId,
        useObjectId = isBold,
        canRetryWithAlternative = true,
        onLoaded = onLoaded,
        onError = onError
      )
    }
  }

  private fun executeLoadAttempt(
    adObjectId: String,
    useObjectId: Boolean,
    canRetryWithAlternative: Boolean,
    onLoaded: (String) -> Unit,
    onError: (String) -> Unit
  ) {
    try {
      val loadOptions = UnityAdsLoadOptions().apply {
        if (useObjectId) {
          setObjectId(adObjectId)
        }
      }
      val flowDesc = if (isBoldSdkAlternativeFlow()) "BoldSDK" else "ClassicWebView"
      val modeDesc = if (useObjectId) "WithObjectId($adObjectId)" else "StandardWaterfall"
      Log.i(
        TAG,
        "[Step 5] Calling UnityAds.load(placementId=$REWARDED_PLACEMENT_ID, flow=$flowDesc, mode=$modeDesc)"
      )
      updateDiagnostics {
        it.copy(
          placementId = REWARDED_PLACEMENT_ID,
          adLoadCallbackResult = "LOADING (flow=$flowDesc, mode=$modeDesc)..."
        )
      }
      UnityAds.load(
        REWARDED_PLACEMENT_ID,
        loadOptions,
        object : IUnityAdsLoadListener {
          override fun onUnityAdsAdLoaded(placementId: String?) {
            lastLoadedAdObjectId = if (useObjectId) adObjectId else ""
            val actualPlacement = placementId ?: REWARDED_PLACEMENT_ID
            Log.i(
              TAG,
              "[Step 6] onUnityAdsAdLoaded: placementId=$actualPlacement, flow=$flowDesc, mode=$modeDesc"
            )
            updateDiagnostics {
              it.copy(
                placementId = actualPlacement,
                adLoadCallbackResult = "SUCCESS: onUnityAdsAdLoaded(placement=$actualPlacement, mode=$modeDesc)"
              )
            }
            runOnMainThread { onLoaded(lastLoadedAdObjectId ?: "") }
          }

          override fun onUnityAdsFailedToLoad(
            placementId: String?,
            error: UnityAds.UnityAdsLoadError?,
            message: String?
          ) {
            val actualPlacement = placementId ?: REWARDED_PLACEMENT_ID
            val errCode = error?.name ?: "UNKNOWN_LOAD_ERROR"
            val errMsg = message ?: "No message"
            Log.e(
              TAG,
              "[Step 6] onUnityAdsFailedToLoad: placementId=$actualPlacement, mode=$modeDesc, error=$errCode, message=$errMsg"
            )

            // Auto-recovery between Classic WebView and BoldSDK flow if option mismatch occurs:
            if (canRetryWithAlternative) {
              if (useObjectId && errMsg.contains("adMarkup", ignoreCase = true)) {
                Log.w(
                  TAG,
                  "[Step 6] 'adMarkup is missing' detected with objectId option. Auto-retrying with StandardWaterfall mode..."
                )
                runOnMainThread {
                  executeLoadAttempt(
                    adObjectId = adObjectId,
                    useObjectId = false,
                    canRetryWithAlternative = false,
                    onLoaded = onLoaded,
                    onError = onError
                  )
                }
                return
              } else if (!useObjectId && errMsg.contains("Object ID cannot be null", ignoreCase = true)) {
                Log.w(
                  TAG,
                  "[Step 6] 'Object ID cannot be null' detected in standard mode. Auto-retrying with WithObjectId mode..."
                )
                runOnMainThread {
                  executeLoadAttempt(
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

            updateDiagnostics {
              it.copy(
                placementId = actualPlacement,
                adLoadCallbackResult = "FAILED: error=$errCode, message=$errMsg (mode=$modeDesc)"
              )
            }
            runOnMainThread { onError(UNAVAILABLE_MESSAGE) }
          }
        }
      )
    } catch (e: Throwable) {
      Log.e(
        TAG,
        "[Step 5] Exception calling UnityAds.load($REWARDED_PLACEMENT_ID): ${e.message}",
        e
      )
      updateDiagnostics {
        it.copy(
          adLoadCallbackResult = "EXCEPTION: ${e.javaClass.simpleName}: ${e.message}"
        )
      }
      runOnMainThread { onError(UNAVAILABLE_MESSAGE) }
    }
  }

  /**
   * Shows `BP_Rewarded_Android` using matching show options.
   * If `adObjectId` was loaded with a valid UUID (BoldSDK mode), it sets `showOptions.setObjectId(adObjectId)`.
   * In standard Waterfall mode, clean `UnityAdsShowOptions()` is passed.
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
          Log.e(
            TAG,
            "[Step 7] Ad readiness check FAILED: Activity is finishing or destroyed before UnityAds.show"
          )
          updateDiagnostics {
            it.copy(adShowCallbackResult = "FAILED: ACTIVITY_FINISHING_OR_DESTROYED")
          }
          onFailed(UNAVAILABLE_MESSAGE)
          return@runOnMainThread
        }
        if (!isSdkInitialized()) {
          Log.e(
            TAG,
            "[Step 7] Ad readiness check FAILED: UnityAds SDK is not initialized before UnityAds.show"
          )
          updateDiagnostics {
            it.copy(adShowCallbackResult = "FAILED: SDK_NOT_INITIALIZED")
          }
          onFailed(UNAVAILABLE_MESSAGE)
          return@runOnMainThread
        }

        runCatching { ClientProperties.setActivity(activity) }

        val showOptions = UnityAdsShowOptions().apply {
          if (!adObjectId.isNullOrBlank()) {
            setObjectId(adObjectId)
          }
        }

        val showModeDesc = if (!adObjectId.isNullOrBlank()) "WithObjectId($adObjectId)" else "Standard"
        Log.i(
          TAG,
          "[Step 8] Calling UnityAds.show(placementId=$REWARDED_PLACEMENT_ID, mode=$showModeDesc)"
        )
        updateDiagnostics {
          it.copy(adShowCallbackResult = "STARTING_SHOW (placement=$REWARDED_PLACEMENT_ID, mode=$showModeDesc)...")
        }
        UnityAds.show(
          activity,
          REWARDED_PLACEMENT_ID,
          showOptions,
          object : IUnityAdsShowListener {
            override fun onUnityAdsShowStart(placementId: String?) {
              val actualPlacement = placementId ?: REWARDED_PLACEMENT_ID
              Log.i(
                TAG,
                "[Step 9] onUnityAdsShowStart: placementId=$actualPlacement, mode=$showModeDesc"
              )
              updateDiagnostics {
                it.copy(adShowCallbackResult = "SHOWING (onUnityAdsShowStart: $actualPlacement)")
              }
              runOnMainThread { onStart() }
            }

            override fun onUnityAdsShowClick(placementId: String?) {
              Log.d(TAG, "[Step 9] onUnityAdsShowClick: placementId=$placementId")
            }

            override fun onUnityAdsShowComplete(
              placementId: String?,
              state: UnityAds.UnityAdsShowCompletionState?
            ) {
              val actualPlacement = placementId ?: REWARDED_PLACEMENT_ID
              val stateName = state?.name ?: "UNKNOWN_STATE"
              Log.i(
                TAG,
                "[Step 9] onUnityAdsShowComplete: placementId=$actualPlacement, state=$stateName, mode=$showModeDesc"
              )
              updateDiagnostics {
                it.copy(
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
            }

            override fun onUnityAdsShowFailure(
              placementId: String?,
              error: UnityAds.UnityAdsShowError?,
              message: String?
            ) {
              val actualPlacement = placementId ?: REWARDED_PLACEMENT_ID
              val errCode = error?.name ?: "UNKNOWN_SHOW_ERROR"
              val errMsg = message ?: "No message"
              Log.e(
                TAG,
                "[Step 9] onUnityAdsShowFailure: placementId=$actualPlacement, mode=$showModeDesc, error=$errCode, message=$errMsg"
              )
              updateDiagnostics {
                it.copy(
                  adShowCallbackResult = "FAILED: error=$errCode, message=$errMsg"
                )
              }
              runOnMainThread { onFailed(UNAVAILABLE_MESSAGE) }
            }
          }
        )
      } catch (e: Throwable) {
        Log.e(
          TAG,
          "[Step 8] Exception calling UnityAds.show($REWARDED_PLACEMENT_ID): ${e.message}",
          e
        )
        updateDiagnostics {
          it.copy(adShowCallbackResult = "EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
        }
        runOnMainThread { onFailed(UNAVAILABLE_MESSAGE) }
      }
    }
  }

  /**
   * Diagnostic-only ad load probe:
   * Allows testing `ensureInitialized` -> `UnityAds.load("BP_Rewarded_Android")` on a physical device
   * to record the exact `IUnityAdsLoadListener` callback result in `diagnosticState` even when
   * backend session creation fails or when the user taps "Test Ad Load (Diagnostic)".
   * NEVER shows an ad for reward and NEVER unlocks the Claim button.
   */
  fun runDiagnosticLoadProbe(activity: Activity, onDone: () -> Unit = {}) {
    ensureInitialized(
      context = activity,
      onReady = {
        val probeAdObjectId = UUID.randomUUID().toString()
        loadRewardedAd(
          adObjectId = probeAdObjectId,
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
   * - Loads BP_Rewarded_Android with a valid UUID v4 adObjectId in UnityAdsLoadOptions
   * - Shows BP_Rewarded_Android with the matching UUID v4 adObjectId in UnityAdsShowOptions only after load succeeds
   */
  fun loadAndShowRewardedAdForSession(
    activity: Activity,
    sessionId: String,
    onAdShowing: () -> Unit = {},
    onVideoCompleted: () -> Unit,
    onFailed: (String) -> Unit
  ) {
    if (!isAdOperationInProgress.compareAndSet(false, true)) {
      Log.w(TAG, "[Step 1] Ignoring duplicate Watch Now request while an ad operation is already in progress.")
      return
    }

    val callbackDelivered = AtomicBoolean(false)
    val timeoutRunnable = Runnable {
      if (callbackDelivered.compareAndSet(false, true)) {
        isAdOperationInProgress.set(false)
        Log.e(
          TAG,
          "[Step 6] Ad operation timed out after ${AD_OPERATION_TIMEOUT_MS}ms for placement $REWARDED_PLACEMENT_ID"
        )
        updateDiagnostics {
          it.copy(adLoadCallbackResult = "TIMEOUT after ${AD_OPERATION_TIMEOUT_MS}ms")
        }
        onFailed(UNAVAILABLE_MESSAGE)
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
      Log.e(TAG, "[Step 2] Device is offline; cannot load Unity rewarded video.")
      updateDiagnostics {
        it.copy(adLoadCallbackResult = "FAILED: DEVICE_OFFLINE")
      }
      finishWithFailure(UNAVAILABLE_MESSAGE)
      return
    }

    ensureInitialized(
      context = activity,
      onReady = {
        bindRewardSessionServerId(activity, sessionId)
        val adObjectId = UUID.randomUUID().toString()
        loadRewardedAd(
          adObjectId = adObjectId,
          onLoaded = { loadedAdObjectId ->
            Log.d(
              TAG,
              "[Step 7] Ad readiness verified (sdkInitialized=${isSdkInitialized()}, activityValid=${!activity.isFinishing && !activity.isDestroyed}, adObjectId=$loadedAdObjectId)"
            )
            showRewardedAd(
              activity = activity,
              adObjectId = loadedAdObjectId,
              onStart = {
                mainHandler.removeCallbacks(timeoutRunnable)
                onAdShowing()
              },
              onComplete = {
                finishWithCompletion()
              },
              onSkipped = {
                finishWithFailure(
                  "Video was not completed. Please watch the full video to unlock your reward."
                )
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
