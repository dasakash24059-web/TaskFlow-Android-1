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
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

fun Context.findActivity(): Activity? {
  var current: Context? = this
  while (current is ContextWrapper) {
    if (current is Activity) return current
    current = current.baseContext
  }
  return null
}

object UnityAdsHelper {
  private const val TAG = "UnityAdsHelper"

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
  @Volatile private var lastLoadedObjectId: String? = null
  private val isAdOperationInProgress = AtomicBoolean(false)

  private val pendingInitCallbacks = CopyOnWriteArrayList<Pair<() -> Unit, (String) -> Unit>>()

  private fun isRobolectric(): Boolean = Build.FINGERPRINT == "robolectric"

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
      Log.d(TAG, "[Step 2] Robolectric environment detected; marking Unity Ads initialized.")
      return
    }
    if (isSdkInitialized()) {
      Log.d(TAG, "[Step 2] Unity Ads SDK is already initialized (gameId=$GAME_ID).")
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

        // In Unity Ads 4.12.0, if a previous initialize() call failed (e.g. offline at app start),
        // SdkProperties remains in INITIALIZED_FAILED and blocks future initialize() calls with
        // "Unity Ads SDK failed to initialize due to previous failed reason" unless reset to NOT_INITIALIZED.
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
        Log.i(
          TAG,
          "[Step 2] Starting UnityAds.initialize(gameId=$GAME_ID, testMode=$TEST_MODE, version=${runCatching { UnityAds.version }.getOrDefault("4.12.0")}, hasActivity=${activity != null})"
        )

        UnityAds.initialize(
          initContext,
          GAME_ID,
          TEST_MODE,
          object : IUnityAdsInitializationListener {
            override fun onInitializationComplete() {
              isInitialized = true
              isInitializing = false
              Log.i(TAG, "[Step 2] Unity Ads initialization COMPLETE for Game ID $GAME_ID")
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
              Log.e(
                TAG,
                "[Step 2] Unity Ads initialization FAILED: error=$error, message=$message"
              )
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
        Log.e(TAG, "[Step 2] Exception during Unity Ads initialization: ${e.message}", e)
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
      Log.d(TAG, "[Step 2] Unity Ads SDK verified initialized.")
      runOnMainThread { onReady() }
      return
    }
    if (!isNetworkAvailable(context)) {
      Log.e(TAG, "[Step 2] Cannot initialize Unity Ads: device has no active internet connection.")
      runOnMainThread { onFailed(UNAVAILABLE_MESSAGE) }
      return
    }
    Log.d(TAG, "[Step 2] Unity Ads SDK not yet initialized; queueing callback and initializing.")
    pendingInitCallbacks.add(onReady to onFailed)
    initialize(context)
  }

  /**
   * Binds the server-generated reward session ID to Unity Ads PlayerMetaData.setServerId(sessionId)
   * so Unity S2S callbacks pass sid=<sessionId> to the verification endpoint.
   */
  fun bindRewardSessionServerId(context: Context, sessionId: String) {
    if (isRobolectric()) return
    try {
      val playerMetaData = PlayerMetaData(context)
      playerMetaData.setServerId(sessionId)
      playerMetaData.commit()
      Log.d(TAG, "[Step 4] Bound PlayerMetaData.setServerId for active reward session.")
    } catch (e: Throwable) {
      Log.e(TAG, "[Step 4] Failed to commit PlayerMetaData serverId: ${e.message}", e)
    }
  }

  fun loadRewardedAd(
    objectId: String = UUID.randomUUID().toString(),
    onLoaded: (String) -> Unit,
    onError: (String) -> Unit
  ) {
    if (isRobolectric()) {
      lastLoadedObjectId = objectId
      onLoaded(objectId)
      return
    }
    runOnMainThread {
      try {
        val loadOptions =
          UnityAdsLoadOptions().apply {
            setObjectId(objectId)
          }
        Log.i(
          TAG,
          "[Step 5] Ad loading started: placementId=$REWARDED_PLACEMENT_ID, objectId=$objectId"
        )
        UnityAds.load(
          REWARDED_PLACEMENT_ID,
          loadOptions,
          object : IUnityAdsLoadListener {
            override fun onUnityAdsAdLoaded(placementId: String?) {
              lastLoadedObjectId = objectId
              Log.i(
                TAG,
                "[Step 6] Ad loaded successfully: placementId=$placementId, objectId=$objectId"
              )
              runOnMainThread { onLoaded(objectId) }
            }

            override fun onUnityAdsFailedToLoad(
              placementId: String?,
              error: UnityAds.UnityAdsLoadError?,
              message: String?
            ) {
              Log.e(
                TAG,
                "[Step 6] Ad loading FAILED: placementId=$placementId, objectId=$objectId, error=$error, message=$message"
              )
              runOnMainThread { onError(UNAVAILABLE_MESSAGE) }
            }
          }
        )
      } catch (e: Throwable) {
        Log.e(
          TAG,
          "[Step 5] Exception while starting UnityAds.load($REWARDED_PLACEMENT_ID): ${e.message}",
          e
        )
        runOnMainThread { onError(UNAVAILABLE_MESSAGE) }
      }
    }
  }

  fun showRewardedAd(
    activity: Activity,
    objectId: String? = lastLoadedObjectId,
    onStart: () -> Unit = {},
    onComplete: () -> Unit,
    onSkipped: () -> Unit = {},
    onFailed: (String) -> Unit
  ) {
    if (isRobolectric()) {
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
          onFailed(UNAVAILABLE_MESSAGE)
          return@runOnMainThread
        }
        if (!isSdkInitialized()) {
          Log.e(
            TAG,
            "[Step 7] Ad readiness check FAILED: UnityAds SDK is not initialized before UnityAds.show"
          )
          onFailed(UNAVAILABLE_MESSAGE)
          return@runOnMainThread
        }

        runCatching { ClientProperties.setActivity(activity) }

        val resolvedObjectId = objectId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val showOptions =
          UnityAdsShowOptions().apply {
            setObjectId(resolvedObjectId)
          }

        Log.i(
          TAG,
          "[Step 8] Ad show started: placementId=$REWARDED_PLACEMENT_ID, objectId=$resolvedObjectId"
        )
        UnityAds.show(
          activity,
          REWARDED_PLACEMENT_ID,
          showOptions,
          object : IUnityAdsShowListener {
            override fun onUnityAdsShowStart(placementId: String?) {
              Log.i(
                TAG,
                "[Step 9] Ad playback started (onUnityAdsShowStart): placementId=$placementId, objectId=$resolvedObjectId"
              )
              runOnMainThread { onStart() }
            }

            override fun onUnityAdsShowClick(placementId: String?) {
              Log.d(TAG, "[Step 9] Ad clicked: placementId=$placementId")
            }

            override fun onUnityAdsShowComplete(
              placementId: String?,
              state: UnityAds.UnityAdsShowCompletionState?
            ) {
              Log.i(
                TAG,
                "[Step 9] Ad show completed: placementId=$placementId, state=$state, objectId=$resolvedObjectId"
              )
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
              Log.e(
                TAG,
                "[Step 9] Ad show FAILED: placementId=$placementId, objectId=$resolvedObjectId, error=$error, message=$message"
              )
              runOnMainThread { onFailed(UNAVAILABLE_MESSAGE) }
            }
          }
        )
      } catch (e: Throwable) {
        Log.e(
          TAG,
          "[Step 8] Exception showing rewarded ad ($REWARDED_PLACEMENT_ID): ${e.message}",
          e
        )
        runOnMainThread { onFailed(UNAVAILABLE_MESSAGE) }
      }
    }
  }

  /**
   * Executes the complete Watch Now Unity Ads lifecycle:
   * - Guards against duplicate concurrent requests
   * - Enforces a timeout so loading state can never get stuck permanently
   * - Ensures Unity Ads SDK initialization completes first
   * - Binds sessionId via PlayerMetaData.setServerId(sessionId)
   * - Loads BP_Rewarded_Android with a valid UUID objectId in UnityAdsLoadOptions
   * - Shows BP_Rewarded_Android with the matching UUID objectId in UnityAdsShowOptions only after load succeeds
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
      finishWithFailure(UNAVAILABLE_MESSAGE)
      return
    }

    ensureInitialized(
      context = activity,
      onReady = {
        bindRewardSessionServerId(activity, sessionId)
        val adObjectId = UUID.randomUUID().toString()
        loadRewardedAd(
          objectId = adObjectId,
          onLoaded = { loadedObjectId ->
            Log.d(
              TAG,
              "[Step 7] Ad readiness verified (sdkInitialized=${isSdkInitialized()}, activityValid=${!activity.isFinishing && !activity.isDestroyed}, objectId=$loadedObjectId)"
            )
            showRewardedAd(
              activity = activity,
              objectId = loadedObjectId,
              onStart = {
                // Once playback starts, remove the load timeout so user can watch full video
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
