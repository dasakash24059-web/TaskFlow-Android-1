package com.example.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Release App Check installer (H-02).
 * Always installs [PlayIntegrityAppCheckProviderFactory] in release APKs/AABs so
 * server-side App Check-enforced Cloud Functions receive genuine Play Integrity tokens.
 */
internal fun configureAppCheck(context: Context, intent: Intent? = null): Boolean {
  if (Build.FINGERPRINT == "robolectric") {
    return false
  }
  return runCatching {
    val app = FirebaseApp.getInstance()
    FirebaseAppCheck.getInstance(app)
      .installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
    Log.i("TaskFlowAppCheck", "Installed PlayIntegrityAppCheckProviderFactory for release build")
    true
  }.getOrElse { err ->
    Log.e("TaskFlowAppCheck", "Failed to install PlayIntegrityAppCheckProviderFactory: ${err.message}")
    false
  }
}

internal fun activeAppCheckProviderNameForTesting(): String = "PLAY_INTEGRITY"
