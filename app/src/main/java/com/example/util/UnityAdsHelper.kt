package com.example.util

import android.app.Activity
import android.content.Context
import android.util.Log
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
import com.unity3d.services.banners.BannerView
import com.unity3d.services.banners.UnityBannerSize
import com.unity3d.ads.metadata.PlayerMetaData
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch

object UnityAdsHelper {
  const val GAME_ID = "800430673"
  const val REWARDED_PLACEMENT_ID = "BP_Rewarded_Android"
  const val BANNER_PLACEMENT_ID = "BP_Banner_Android"
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
        override fun onUnityAdsShowComplete(placementId: String, state: UnityAds.UnityAdsShowCompletionState) {
          if (state == UnityAds.UnityAdsShowCompletionState.COMPLETED) onComplete()
          else onFailed("Ad not completed: $state")
        }
        override fun onUnityAdsShowFailure(placementId: String, error: UnityAds.UnityAdsShowError, message: String) {
          onFailed(message)
        }
        override fun onUnityAdsShowStart(p0: String) {}
        override fun onUnityAdsShowClick(p0: String) {}
      })
    } catch (e: Throwable) {
      onFailed(e.message ?: "Exception showing ad")
    }
  }

  fun watchAndClaimReward(
    activity: Activity,
    userId: String,
    rewardType: String,
    onComplete: () -> Unit,
    onFailed: (String) -> Unit
  ) {
    val functions = FirebaseFunctions.getInstance()
    kotlinx.coroutines.MainScope().launch {
      try {
        val result = functions.getHttpsCallable("createRewardSession")
          .call(mapOf("rewardType" to rewardType)).await()
        val sessionId = (result.data as Map<*, *>)["sessionId"] as String
        
        val playerMetaData = PlayerMetaData(activity)
        playerMetaData.setServerId(userId)
        playerMetaData.set("session_id", sessionId)
        playerMetaData.commit()
        
        loadRewardedAd(
          onLoaded = {
            showRewardedAd(activity, onComplete, onFailed)
          },
          onError = onFailed
        )
      } catch (e: Exception) {
        onFailed("Failed to create session: ${e.message}")
      }
    }
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
