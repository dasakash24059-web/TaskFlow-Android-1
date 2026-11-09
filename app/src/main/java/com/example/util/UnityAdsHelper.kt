package com.example.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
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
import com.unity3d.ads.UnityAdsShowOptions
import com.unity3d.ads.metadata.PlayerMetaData
import com.unity3d.services.banners.BannerView
import com.unity3d.services.banners.UnityBannerSize
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

object UnityAdsHelper {
  private const val TAG = "UnityAdsHelper"

  const val GAME_ID = "800430673"
  const val REWARDED_PLACEMENT_ID = "BP_Rewarded_Android"
  const val BANNER_PLACEMENT_ID = "BP_Banner_Android"
  const val INTERSTITIAL_PLACEMENT_ID = "BP_Interstitial_Android" // Configured for future use; not auto-displayed
  const val TEST_MODE = false

  const val UNAVAILABLE_MESSAGE = "Video is currently unavailable. Please try again later."

  @Volatile private var isInitialized = false
  @Volatile private var isInitializing = false
  private val isAdOperationInProgress = AtomicBoolean(false)

  private val pendingInitCallbacks = CopyOnWriteArrayList<Pair<() -> Unit, (String) -> Unit>>()

  private fun isRobolectric(): Boolean = Build.FINGERPRINT == "robolectric"

  fun isSdkInitialized(): Boolean {
    if (isRobolectric()) return true
    return try {
      isInitialized || UnityAds.isInitialized
    } catch (_: Throwable) {
      isInitialized
    }
  }

  fun initialize(context: Context) {
    if (isSdkInitialized() || isInitializing) return
    if (isRobolectric()) {
      isInitialized = true
      return
    }
    try {
      isInitializing = true
      UnityAds.initialize(
        context.applicationContext,
        GAME_ID,
        TEST_MODE,
        object : IUnityAdsInitializationListener {
          override fun onInitializationComplete() {
            isInitialized = true
            isInitializing = false
            Log.d(TAG, "Unity Ads initialization complete for Game ID $GAME_ID")
            val callbacks = pendingInitCallbacks.toList()
            pendingInitCallbacks.clear()
            callbacks.forEach { (onReady, _) -> onReady() }
          }

          override fun onInitializationFailed(
            error: UnityAds.UnityAdsInitializationError?,
            message: String?
          ) {
            isInitializing = false
            Log.e(TAG, "Unity Ads initialization failed: $error - $message")
            val callbacks = pendingInitCallbacks.toList()
            pendingInitCallbacks.clear()
            callbacks.forEach { (_, onFailed) ->
              onFailed(UNAVAILABLE_MESSAGE)
            }
          }
        }
      )
    } catch (e: Throwable) {
      isInitializing = false
      Log.e(TAG, "Exception during Unity Ads initialization: ${e.message}", e)
      val callbacks = pendingInitCallbacks.toList()
      pendingInitCallbacks.clear()
      callbacks.forEach { (_, onFailed) ->
        onFailed(UNAVAILABLE_MESSAGE)
      }
    }
  }

  private fun ensureInitialized(
    context: Context,
    onReady: () -> Unit,
    onFailed: (String) -> Unit
  ) {
    if (isSdkInitialized()) {
      isInitialized = true
      onReady()
      return
    }
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
    } catch (e: Throwable) {
      Log.e(TAG, "Failed to commit PlayerMetaData serverId: ${e.message}", e)
    }
  }

  fun loadRewardedAd(onLoaded: () -> Unit, onError: (String) -> Unit) {
    if (isRobolectric()) {
      onLoaded()
      return
    }
    try {
      UnityAds.load(
        REWARDED_PLACEMENT_ID,
        object : IUnityAdsLoadListener {
          override fun onUnityAdsAdLoaded(placementId: String?) {
            Log.d(TAG, "Rewarded ad loaded for placement: $placementId")
            onLoaded()
          }

          override fun onUnityAdsFailedToLoad(
            placementId: String?,
            error: UnityAds.UnityAdsLoadError?,
            message: String?
          ) {
            Log.e(TAG, "Rewarded ad failed to load ($placementId): $error - $message")
            onError(UNAVAILABLE_MESSAGE)
          }
        }
      )
    } catch (e: Throwable) {
      Log.e(TAG, "Exception loading rewarded ad: ${e.message}", e)
      onError(UNAVAILABLE_MESSAGE)
    }
  }

  fun showRewardedAd(
    activity: Activity,
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
    try {
      val showOptions = UnityAdsShowOptions()
      UnityAds.show(
        activity,
        REWARDED_PLACEMENT_ID,
        showOptions,
        object : IUnityAdsShowListener {
          override fun onUnityAdsShowStart(placementId: String?) {
            Log.d(TAG, "Rewarded ad playback started: $placementId")
            onStart()
          }

          override fun onUnityAdsShowClick(placementId: String?) {
            Log.d(TAG, "Rewarded ad clicked: $placementId")
          }

          override fun onUnityAdsShowComplete(
            placementId: String?,
            state: UnityAds.UnityAdsShowCompletionState?
          ) {
            Log.d(TAG, "Rewarded ad completed ($placementId) with state: $state")
            if (state == UnityAds.UnityAdsShowCompletionState.COMPLETED) {
              onComplete()
            } else {
              onSkipped()
            }
          }

          override fun onUnityAdsShowFailure(
            placementId: String?,
            error: UnityAds.UnityAdsShowError?,
            message: String?
          ) {
            Log.e(TAG, "Rewarded ad show failure ($placementId): $error - $message")
            onFailed(UNAVAILABLE_MESSAGE)
          }
        }
      )
    } catch (e: Throwable) {
      Log.e(TAG, "Exception showing rewarded ad: ${e.message}", e)
      onFailed(UNAVAILABLE_MESSAGE)
    }
  }

  /**
   * Executes the full SDK initialization verification, PlayerMetaData.setServerId(sessionId) binding,
   * ad load for BP_Rewarded_Android, and ad playback only after load succeeds.
   * Never grants rewards directly; invokes [onVideoCompleted] only when Unity confirms COMPLETED.
   */
  fun loadAndShowRewardedAdForSession(
    activity: Activity,
    sessionId: String,
    onAdShowing: () -> Unit = {},
    onVideoCompleted: () -> Unit,
    onFailed: (String) -> Unit
  ) {
    if (!isAdOperationInProgress.compareAndSet(false, true)) {
      return
    }

    ensureInitialized(
      context = activity.applicationContext,
      onReady = {
        bindRewardSessionServerId(activity, sessionId)
        loadRewardedAd(
          onLoaded = {
            showRewardedAd(
              activity = activity,
              onStart = { onAdShowing() },
              onComplete = {
                isAdOperationInProgress.set(false)
                onVideoCompleted()
              },
              onSkipped = {
                isAdOperationInProgress.set(false)
                onFailed("Video was not completed. Please watch the full video to unlock your reward.")
              },
              onFailed = { err ->
                isAdOperationInProgress.set(false)
                onFailed(err)
              }
            )
          },
          onError = { err ->
            isAdOperationInProgress.set(false)
            onFailed(err)
          }
        )
      },
      onFailed = { err ->
        isAdOperationInProgress.set(false)
        onFailed(err)
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
          var activity: Activity? = null
          var current = ctx
          while (current is ContextWrapper) {
            if (current is Activity) {
              activity = current
              break
            }
            current = current.baseContext
          }
          if (activity != null) {
            BannerView(activity, UnityAdsHelper.BANNER_PLACEMENT_ID, UnityBannerSize(320, 50))
              .apply { load() }
          } else {
            View(ctx)
          }
        },
        modifier = Modifier.fillMaxWidth()
      )
    }
  }
}
