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
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
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
    val context = ApplicationProvider.getApplicationContext<Context>()
    val resId =
      context.resources.getIdentifier("firestore_database_id", "string", context.packageName)
    databaseId = if (resId != 0) context.getString(resId) else "(default)"

    ensureEmulatorsAndRulesReady(databaseId)

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

  protected fun isEmulatorAvailable(): Boolean =
    isPortOpen(AUTH_PORT) && isPortOpen(FIRESTORE_PORT)

  private companion object {
    const val EMULATOR_HOST = "127.0.0.1"
    const val FIRESTORE_PORT = 8085
    const val AUTH_PORT = 9099
    const val PROJECT_ID = "demo-no-project"
    const val DEFAULT_PASSWORD = "password123"
    const val AUTH_TIMEOUT_MS = 10000L
    const val EMULATOR_BOOT_TIMEOUT_MS = 60000L

    private val setupLock = Any()
    @Volatile private var rulesUploadedForDb: String? = null
    @Volatile private var managedEmulatorProcess: Process? = null

    private fun isPortOpen(port: Int): Boolean =
      try {
        Socket().use { socket ->
          socket.connect(InetSocketAddress(EMULATOR_HOST, port), 500)
          true
        }
      } catch (_: Throwable) {
        false
      }

    private fun resolveWorkspaceRoot(): File {
      val candidates = listOf(File("."), File(".."), File("../.."))
      return candidates
        .map { it.canonicalFile }
        .firstOrNull { dir -> File(dir, "firestore.rules").isFile }
        ?: error("Could not locate firestore.rules from ${File(".").canonicalPath}")
    }

    private fun resolveFirebaseCommand(rootDir: File): List<String> {
      val hasEmulatorConfig = File(rootDir, "firebase.emulator.json").isFile
      val configArgs =
        if (hasEmulatorConfig) listOf("--config", "firebase.emulator.json") else emptyList()
      val baseArgs =
        listOf(
          "emulators:start",
          *configArgs.toTypedArray(),
          "--project",
          PROJECT_ID,
          "--only",
          "auth,firestore"
        )
      val hasGlobalFirebase =
        runCatching {
          val probe = ProcessBuilder("firebase", "--version").redirectErrorStream(true).start()
          probe.waitFor() == 0
        }.getOrDefault(false)
      return if (hasGlobalFirebase) {
        listOf("firebase") + baseArgs
      } else {
        listOf("npx", "--yes", "firebase-tools") + baseArgs
      }
    }

    private fun ensureEmulatorsAndRulesReady(databaseId: String) {
      synchronized(setupLock) {
        val rootDir = resolveWorkspaceRoot()

        if (!isPortOpen(AUTH_PORT) || !isPortOpen(FIRESTORE_PORT)) {
          val cmd = resolveFirebaseCommand(rootDir)
          val logLines = Collections.synchronizedList(mutableListOf<String>())
          val process =
            ProcessBuilder(cmd)
              .directory(rootDir)
              .redirectErrorStream(true)
              .start()
          managedEmulatorProcess = process

          Runtime.getRuntime().addShutdownHook(
            Thread {
              runCatching {
                process.destroy()
                if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                  process.destroyForcibly()
                }
              }
            }
          )

          Thread(
            {
              runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                  lines.forEach { line ->
                    synchronized(logLines) {
                      if (logLines.size >= 50) logLines.removeAt(0)
                      logLines.add(line)
                    }
                  }
                }
              }
            },
            "firebase-emulator-drain"
          )
            .apply { isDaemon = true }
            .start()

          val deadline = System.currentTimeMillis() + EMULATOR_BOOT_TIMEOUT_MS
          while (System.currentTimeMillis() < deadline) {
            if (isPortOpen(AUTH_PORT) && isPortOpen(FIRESTORE_PORT)) {
              break
            }
            if (!process.isAlive) {
              error(
                "Firebase Emulator exited unexpectedly (exit=${process.exitValue()}). Logs:\n" +
                  logLines.joinToString("\n")
              )
            }
            Thread.sleep(250L)
          }

          check(isPortOpen(AUTH_PORT) && isPortOpen(FIRESTORE_PORT)) {
            "Timed out waiting for Firebase Auth/Firestore emulators on $EMULATOR_HOST:$AUTH_PORT/$FIRESTORE_PORT. Logs:\n" +
              logLines.joinToString("\n")
          }
        }

        if (rulesUploadedForDb != databaseId) {
          val rulesContent = File(rootDir, "firestore.rules").readText(Charsets.UTF_8)
          val body =
            JSONObject()
              .put(
                "rules",
                JSONObject()
                  .put(
                    "files",
                    JSONArray()
                      .put(JSONObject().put("name", "firestore.rules").put("content", rulesContent))
                  )
              )
              .toString()

          val targets = linkedSetOf(databaseId, "(default)")
          for (targetDb in targets) {
            val url =
              URL(
                "http://$EMULATOR_HOST:$FIRESTORE_PORT/emulator/v1/projects/$PROJECT_ID/databases/$targetDb:securityRules"
              )
            val conn =
              (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
              }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val errText =
              if (status !in 200..299) {
                conn.errorStream?.bufferedReader()?.readText().orEmpty()
              } else {
                ""
              }
            conn.disconnect()
            check(status in 200..299) {
              "Failed to load firestore.rules onto emulator database '$targetDb' (HTTP $status): $errText"
            }
          }
          rulesUploadedForDb = databaseId
        }
      }
    }
  }
}
