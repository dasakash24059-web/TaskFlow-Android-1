package com.example.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug-only App Check installer (H-02).
 * Installs [DebugAppCheckProviderFactory] before any Cloud Function / Firestore calls.
 * If an explicit `FIREBASE_APPCHECK_DEBUG_TOKEN` extra is passed via launch intent (CI/QA),
 * persists it via synchronous `.commit()` before installing the factory.
 */
internal fun configureAppCheck(context: Context, intent: Intent? = null): Boolean {
  if (Build.FINGERPRINT == "robolectric") {
    return false
  }
  return runCatching {
    val app = FirebaseApp.getInstance()
    val debugToken = intent?.getStringExtra("FIREBASE_APPCHECK_DEBUG_TOKEN")?.trim().orEmpty()
    if (debugToken.isNotBlank()) {
      context
        .getSharedPreferences(
          "com.google.firebase.appcheck.debug.store.${app.persistenceKey}",
          Context.MODE_PRIVATE
        )
        .edit()
        .putString("com.google.firebase.appcheck.debug.DEBUG_SECRET", debugToken)
        .commit()
    }
    FirebaseAppCheck.getInstance(app)
      .installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    Log.i("TaskFlowAppCheck", "Installed DebugAppCheckProviderFactory for debug build")
    true
  }.getOrElse { err ->
    Log.w("TaskFlowAppCheck", "App Check debug provider setup skipped: ${err.message}")
    false
  }
}

internal fun activeAppCheckProviderNameForTesting(): String = "DEBUG"
