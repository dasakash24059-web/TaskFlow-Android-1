package com.example.base

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
abstract class FirestoreEmulatorTestBase {

  protected lateinit var firestore: FirebaseFirestore
  protected lateinit var auth: FirebaseAuth
  protected lateinit var databaseId: String

  @Before
  open fun setUpFirebase() {
    Assume.assumeTrue(
      "Firebase Auth/Firestore emulators not reachable on 127.0.0.1:8085 / 9099",
      isEmulatorAvailable()
    )

    val context = ApplicationProvider.getApplicationContext<Context>()
    val resId =
      context.resources.getIdentifier("firestore_database_id", "string", context.packageName)
    databaseId = if (resId != 0) context.getString(resId) else "(default)"

    val app =
      if (FirebaseApp.getApps(context).isEmpty()) {
        val newApp =
          FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
              .setApplicationId("com.aistudio.taskflow.kxmpzq")
              .setProjectId(PROJECT_ID)
              .setApiKey("fake-api-key-for-emulator")
              .build()
          )
        val newFirestore = FirebaseFirestore.getInstance(newApp, databaseId)
        newFirestore.useEmulator(EMULATOR_HOST, FIRESTORE_PORT)
        newFirestore.firestoreSettings =
          FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
            .build()

        val newAuth = FirebaseAuth.getInstance(newApp)
        newAuth.useEmulator(EMULATOR_HOST, AUTH_PORT)
        newApp
      } else {
        FirebaseApp.getInstance()
      }

    firestore = FirebaseFirestore.getInstance(app, databaseId)
    auth = FirebaseAuth.getInstance(app)
  }

  @After
  open fun tearDownFirebase() {
    runCatching { auth.signOut() }
  }

  // Local unit test helper ONLY (under app/src/test/). In application code (app/src/main/),
  // user login MUST use Google Sign-In with Credential Manager, NEVER signInWithEmailAndPassword.
  protected suspend fun signInTestUser(email: String, emailVerified: Boolean = true): String =
    withContext(Dispatchers.IO) {
      withTimeout(AUTH_TIMEOUT_MS) {
        val currentUser = auth.currentUser
        if (currentUser != null && currentUser.email.equals(email, ignoreCase = true)) {
          return@withTimeout currentUser.uid
        }

        runCatching { auth.signOut() }

        val user = try {
          auth.signInWithEmailAndPassword(email, DEFAULT_PASSWORD).await().user
        } catch (e1: Exception) {
          try {
            auth.createUserWithEmailAndPassword(email, DEFAULT_PASSWORD).await().user
          } catch (e2: Exception) {
            clearEmulatorAccounts()
            auth.createUserWithEmailAndPassword(email, DEFAULT_PASSWORD).await().user
          }
        }
        val targetUser = checkNotNull(user) { "User auth failed for $email" }
        runCatching { setEmulatorEmailVerified(targetUser.uid, emailVerified) }
        runCatching { targetUser.reload().await() }
        runCatching { targetUser.getIdToken(true).await() }
        targetUser.uid
      }
    }

  private fun clearEmulatorAccounts() {
    runCatching {
      val endpoint = URL("http://$EMULATOR_HOST:$AUTH_PORT/emulator/v1/projects/$PROJECT_ID/accounts")
      val conn = (endpoint.openConnection() as HttpURLConnection).apply {
        requestMethod = "DELETE"
        setRequestProperty("Authorization", "Bearer owner")
      }
      conn.responseCode
      conn.disconnect()
    }
  }

  private fun setEmulatorEmailVerified(uid: String, emailVerified: Boolean) {
    runCatching {
      val endpoint =
        URL(
          "http://$EMULATOR_HOST:$AUTH_PORT/identitytoolkit.googleapis.com/v1/projects/$PROJECT_ID/accounts:update"
        )
      val conn = (endpoint.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        setRequestProperty("Authorization", "Bearer owner")
        setRequestProperty("Content-Type", "application/json")
        doOutput = true
      }
      val payload = "{\"localId\":\"$uid\",\"emailVerified\":$emailVerified}"
      conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
      conn.responseCode
      conn.disconnect()
    }
  }

  protected fun Throwable.unwrapFirestoreException(): FirebaseFirestoreException? {
    var current: Throwable? = this
    while (current != null) {
      if (current is FirebaseFirestoreException) return current
      current = current.cause
    }
    return null
  }

  protected fun isEmulatorAvailable(): Boolean {
    return try {
      val url = URL("http://$EMULATOR_HOST:$AUTH_PORT")
      val conn = url.openConnection() as HttpURLConnection
      conn.connectTimeout = 300
      conn.readTimeout = 300
      conn.responseCode
      true
    } catch (_: Throwable) {
      false
    }
  }

  private companion object {
    const val EMULATOR_HOST = "127.0.0.1"
    const val FIRESTORE_PORT = 8085
    const val AUTH_PORT = 9099
    const val PROJECT_ID = "demo-no-project"
    const val DEFAULT_PASSWORD = "password123"
    const val AUTH_TIMEOUT_MS = 5000L
  }
}
