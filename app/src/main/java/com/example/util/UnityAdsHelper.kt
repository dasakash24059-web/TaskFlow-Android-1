package com.example.util

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.unity3d.ads.IUnityAdsInitializationListener
import com.unity3d.ads.IUnityAdsLoadListener
import com.unity3d.ads.IUnityAdsShowListener
import com.unity3d.ads.UnityAds
import com.unity3d.ads.UnityAdsShowOptions
import com.unity3d.services.banners.BannerView
import com.unity3d.services.banners.UnityBannerSize

object UnityAdsHelper {
  const val GAME_ID = "800430673"
  const val REWARDED_PLACEMENT_ID = "BP_Rewarded_Android"
  const val BANNER_PLACEMENT_ID = "BP_Banner_Android"
  const val INTERSTITIAL_PLACEMENT_ID = "BP_Interstitial_Android"
  const val TEST_MODE = true

  private var isInitialized = false

  fun initialize(context: Context) {
    if (isInitialized) return
    try {
      if (android.os.Build.FINGERPRINT == "robolectric") {
        isInitialized = true
        return
      }
      UnityAds.initialize(context.applicationContext, GAME_ID, TEST_MODE, object : IUnityAdsInitializationListener {
        override fun onInitializationComplete() {
          isInitialized = true
          Log.d("UnityAdsHelper", "Unity Ads Initialization Complete.")
        }

        override fun onInitializationFailed(error: UnityAds.UnityAdsInitializationError, message: String) {
          Log.e("UnityAdsHelper", "Unity Ads Initialization Failed: $error - $message")
        }
      })
    } catch (e: Throwable) {
      Log.e("UnityAdsHelper", "Exception during Unity Ads init: ${e.message}")
      isInitialized = true
    }
  }

  fun loadRewardedAd(onLoaded: () -> Unit, onError: (String) -> Unit) {
    try {
      if (android.os.Build.FINGERPRINT == "robolectric") {
        onLoaded()
        return
      }
      UnityAds.load(REWARDED_PLACEMENT_ID, object : IUnityAdsLoadListener {
        override fun onUnityAdsAdLoaded(placementId: String) {
          Log.d("UnityAdsHelper", "Rewarded ad loaded: $placementId")
          onLoaded()
        }

        override fun onUnityAdsFailedToLoad(placementId: String, error: UnityAds.UnityAdsLoadError, message: String) {
          Log.e("UnityAdsHelper", "Rewarded ad failed to load: $error - $message")
          onError(message)
        }
      })
    } catch (e: Throwable) {
      onError(e.message ?: "Failed to load rewarded ad")
    }
  }

  fun showRewardedAd(
    activity: Activity,
    onComplete: () -> Unit,
    onFailed: (String) -> Unit
  ) {
    try {
      if (android.os.Build.FINGERPRINT == "robolectric") {
        onComplete()
        return
      }
      val options = UnityAdsShowOptions()
      UnityAds.show(activity, REWARDED_PLACEMENT_ID, options, object : IUnityAdsShowListener {
        override fun onUnityAdsShowStart(placementId: String) {
          Log.d("UnityAdsHelper", "Rewarded ad show start: $placementId")
        }

        override fun onUnityAdsShowClick(placementId: String) {
          Log.d("UnityAdsHelper", "Rewarded ad show click: $placementId")
        }

        override fun onUnityAdsShowComplete(placementId: String, state: com.unity3d.ads.UnityAds.UnityAdsShowCompletionState) {
          Log.d("UnityAdsHelper", "Rewarded ad show complete: $state")
          if (state == com.unity3d.ads.UnityAds.UnityAdsShowCompletionState.COMPLETED) {
            onComplete()
          } else {
            onFailed("Ad was not completed fully (State: $state)")
          }
        }

        override fun onUnityAdsShowFailure(placementId: String, error: UnityAds.UnityAdsShowError, message: String) {
          Log.e("UnityAdsHelper", "Rewarded ad show failure: $error - $message")
          onFailed(message)
        }
      })
    } catch (e: Throwable) {
      onFailed(e.message ?: "Exception showing ad")
    }
  }

  fun watchAndClaimReward(
    activity: Activity,
    onComplete: () -> Unit,
    onFailed: (String) -> Unit
  ) {
    loadRewardedAd(
      onLoaded = {
        showRewardedAd(
          activity = activity,
          onComplete = onComplete,
          onFailed = onFailed
        )
      },
      onError = onFailed
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
          while (current is android.content.ContextWrapper) {
            if (current is Activity) {
              activity = current
              break
            }
            current = current.baseContext
          }
          if (activity != null) {
            BannerView(activity, UnityAdsHelper.BANNER_PLACEMENT_ID, UnityBannerSize(320, 50)).apply {
              load()
            }
          } else {
            android.view.View(ctx)
          }
        },
        modifier = Modifier.fillMaxWidth()
      )
    }
  }
}
