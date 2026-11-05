package com.example.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.R
import com.example.ensureFirebaseInitialized
import com.example.ui.components.TaskFlowBrandLogo
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.Firebase
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class PortalLoginTarget {
  USER_APP,
  ADMIN_DASHBOARD
}

/**
 * Provisioned Web Application OAuth 2.0 Client ID (`client_type: 3`) for Firebase project `taskflow-8852d`.
 * Note: Credential Manager (`GetSignInWithGoogleOption` / `GetGoogleIdOption`) strictly requires the
 * Web Client ID (`client_type: 3`), never the Android Client ID (`client_type: 1`).
 */
internal const val VERIFIED_TASKFLOW_WEB_CLIENT_ID =
  "54814309263-uralqcp874c3v5nrk9r4ajj9scpt93ug.apps.googleusercontent.com"

private const val LEGACY_PLACEHOLDER_WEB_CLIENT_ID =
  "54814309263-29a7s7d81k2v8a9s3d0f1g2h3j4k5l6m.apps.googleusercontent.com"

private const val ANDROID_CLIENT_TYPE_1_ID =
  "54814309263-gf2g5te3e12cpuhpdrvu7ash8b5pj9ai.apps.googleusercontent.com"

private const val GOOGLE_ID_TOKEN_BUNDLE_KEY =
  "com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID_TOKEN"

internal tailrec fun Context.findActivity(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }

internal fun resolveWebClientId(context: Context): String {
  val fromRes =
    runCatching { context.getString(R.string.default_web_client_id).trim() }.getOrDefault("")
  return if (
    fromRes.isNotEmpty() &&
      fromRes != "YOUR_WEB_CLIENT_ID" &&
      fromRes != LEGACY_PLACEHOLDER_WEB_CLIENT_ID &&
      fromRes != ANDROID_CLIENT_TYPE_1_ID
  ) {
    fromRes
  } else {
    VERIFIED_TASKFLOW_WEB_CLIENT_ID
  }
}

/**
 * 1. Professional Splash Screen:
 * - Displays ONLY the TaskFlow logo, app name ("TaskFlow"), and tagline ("Complete Tasks. Track Progress.").
 * - Subtle, smooth logo entrance and breathing animation.
 * - Zero debug information, Firebase details, or developer text.
 * - Automatically transitions to the authentication check when complete.
 */
@Composable
fun TaskFlowSplashScreen(
  durationMillis: Long = 450L,
  onSplashFinished: () -> Unit
) {
  var startAnimation by remember { mutableStateOf(false) }

  val entranceScale by animateFloatAsState(
    targetValue = if (startAnimation) 1f else 0.86f,
    animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing),
    label = "splash_entrance_scale"
  )
  val entranceAlpha by animateFloatAsState(
    targetValue = if (startAnimation) 1f else 0f,
    animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
    label = "splash_entrance_alpha"
  )

  val infiniteTransition = rememberInfiniteTransition(label = "splash_pulse")
  val subtlePulse by infiniteTransition.animateFloat(
    initialValue = 1f,
    targetValue = 1.04f,
    animationSpec =
      infiniteRepeatable(
        animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        repeatMode = RepeatMode.Reverse
      ),
    label = "splash_logo_pulse"
  )

  LaunchedEffect(durationMillis) {
    startAnimation = true
    if (durationMillis > 0L) {
      delay(durationMillis)
    }
    onSplashFinished()
  }

  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(
          Brush.verticalGradient(
            colors =
              listOf(
                Color(0xFF2E1065),
                Color(0xFF4C1D95),
                Color(0xFF5B21B6)
              )
          )
        )
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .testTag("splash_screen"),
    contentAlignment = Alignment.Center
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      drawCircle(
        brush =
          Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.12f), Color.Transparent),
            center = Offset(size.width * 0.5f, size.height * 0.42f),
            radius = size.minDimension * 0.55f
          ),
        radius = size.minDimension * 0.55f,
        center = Offset(size.width * 0.5f, size.height * 0.42f)
      )
    }

    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
      modifier =
        Modifier.padding(horizontal = 32.dp)
          .scale(entranceScale)
          .alpha(entranceAlpha)
    ) {
      Box(
        modifier =
          Modifier.scale(subtlePulse)
            .testTag("splash_logo")
      ) {
        TaskFlowBrandLogo(size = 92.dp)
      }

      Spacer(modifier = Modifier.height(22.dp))

      Text(
        text = stringResource(R.string.app_name),
        style = MaterialTheme.typography.displayLarge,
        color = Color.White,
        fontWeight = FontWeight.ExtraBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag("splash_app_name")
      )

      Spacer(modifier = Modifier.height(10.dp))

      Text(
        text = stringResource(R.string.splash_tagline),
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFFEDE9FE),
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag("splash_tagline")
      )
    }
  }
}

/**
 * 2. Professional Google Login Screen:
 * - TaskFlow logo and app name ("TaskFlow")
 * - Heading: "Welcome to TaskFlow"
 * - Subtitle: "Sign in to continue"
 * - One prominent "Continue with Google" button with a Google icon
 * - Clean Material 3 design with no unnecessary login forms or extra portal tabs
 */
@Composable
fun AuthScreen(
  selectedPortal: PortalLoginTarget = PortalLoginTarget.USER_APP,
  onSelectPortal: (PortalLoginTarget) -> Unit = {},
  onAuthSuccess: (FirebaseUser?) -> Unit
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val composeScope = rememberCoroutineScope()
  val credentialManager = remember(context) { CredentialManager.create(context) }

  var isLoading by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }
  var activeSignInJob by remember { mutableStateOf<Job?>(null) }

  val currentOnAuthSuccess by rememberUpdatedState(onAuthSuccess)

  val setLoadingState: (Boolean) -> Unit =
    remember {
      { loading ->
        isLoading = loading
      }
    }
  val setErrorState: (String) -> Unit =
    remember {
      { msg ->
        isLoading = false
        errorMessage = msg
      }
    }

  val webClientId = remember(context) { resolveWebClientId(context) }
  val isConfigValid = webClientId.isNotEmpty() && webClientId != "YOUR_WEB_CLIENT_ID"

  // 1. Immediate check on composition entry
  LaunchedEffect(Unit) {
    val existingUser =
      runCatching {
          ensureFirebaseInitialized(context.applicationContext)
          Firebase.auth.currentUser
        }
        .getOrNull()
    if (existingUser != null) {
      isLoading = false
      currentOnAuthSuccess(existingUser)
    }
  }

  // 2. Watchdog while isLoading == true:
  // Polls Firebase.auth.currentUser every 250ms so any completed Firebase session transitions
  // immediately, and enforces a strict ceiling so "Signing in…" can never hang indefinitely.
  LaunchedEffect(isLoading) {
    if (!isLoading) return@LaunchedEffect
    var elapsedMs = 0L
    var resumedAfterPauseMs = 0L
    var sawPause = false

    while (isLoading && elapsedMs < 28_000L) {
      delay(250L)
      elapsedMs += 250L

      val signedIn =
        runCatching {
            ensureFirebaseInitialized(context.applicationContext)
            Firebase.auth.currentUser
          }
          .getOrNull()
      if (signedIn != null) {
        isLoading = false
        currentOnAuthSuccess(signedIn)
        return@LaunchedEffect
      }

      val isResumed =
        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
      if (!isResumed) {
        sawPause = true
        resumedAfterPauseMs = 0L
      } else if (sawPause) {
        resumedAfterPauseMs += 250L
        // Once the Google Account Picker sheet has closed and MainActivity has been RESUMED
        // for 12+ seconds with no FirebaseUser, abort the stuck sign-in and surface an error.
        if (resumedAfterPauseMs >= 12_000L) {
          activeSignInJob?.cancel()
          isLoading = false
          if (errorMessage == null) {
            errorMessage =
              "Sign-in timed out after Google account selection. Please check your internet connection and try again."
          }
          return@LaunchedEffect
        }
      }
    }

    if (isLoading) {
      activeSignInJob?.cancel()
      isLoading = false
      if (errorMessage == null) {
        errorMessage =
          "Google Sign-In timed out without a response from Google Play Services. Please try again."
      }
    }
  }

  // 3. Lifecycle observer for immediate detection when returning from the Google Account Picker sheet
  DisposableEffect(lifecycleOwner, context) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME || event == Lifecycle.Event.ON_START) {
        val signedIn =
          runCatching {
              ensureFirebaseInitialized(context.applicationContext)
              Firebase.auth.currentUser
            }
            .getOrNull()
        if (signedIn != null) {
          isLoading = false
          currentOnAuthSuccess(signedIn)
        }
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(
          Brush.verticalGradient(
            colors =
              listOf(
                Color(0xFF2E1065),
                Color(0xFF4C1D95),
                Color(0xFF1E1B4B)
              )
          )
        )
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .testTag("google_login_screen"),
    contentAlignment = Alignment.Center
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      drawCircle(
        brush =
          Brush.radialGradient(
            colors = listOf(Color(0xFF8B5CF6).copy(alpha = 0.22f), Color.Transparent),
            center = Offset(size.width * 0.82f, size.height * 0.18f),
            radius = size.width * 0.65f
          ),
        radius = size.width * 0.65f,
        center = Offset(size.width * 0.82f, size.height * 0.18f)
      )
      drawCircle(
        brush =
          Brush.radialGradient(
            colors = listOf(Color(0xFF10B981).copy(alpha = 0.15f), Color.Transparent),
            center = Offset(size.width * 0.18f, size.height * 0.84f),
            radius = size.width * 0.55f
          ),
        radius = size.width * 0.55f,
        center = Offset(size.width * 0.18f, size.height * 0.84f)
      )
    }

    Column(
      modifier =
        Modifier.fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 24.dp, vertical = 32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 440.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        // TaskFlow Logo & App Name
        Box(modifier = Modifier.testTag("login_logo")) {
          TaskFlowBrandLogo(size = 84.dp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
          text = stringResource(R.string.app_name),
          style = MaterialTheme.typography.displayMedium,
          color = Color.White,
          fontWeight = FontWeight.ExtraBold,
          textAlign = TextAlign.Center,
          modifier = Modifier.testTag("login_app_name")
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Clean Material 3 Login Card
        Card(
          modifier = Modifier.fillMaxWidth().testTag("login_card"),
          shape = RoundedCornerShape(28.dp),
          colors =
            CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surface
            ),
          elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Text(
              text = stringResource(R.string.welcome_to_taskflow),
              style = MaterialTheme.typography.headlineMedium,
              color = MaterialTheme.colorScheme.onSurface,
              fontWeight = FontWeight.ExtraBold,
              textAlign = TextAlign.Center,
              modifier = Modifier.testTag("login_welcome_heading")
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
              text = stringResource(R.string.sign_in_to_continue),
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
              modifier = Modifier.testTag("login_subtitle")
            )

            Spacer(modifier = Modifier.height(28.dp))

            if (!isConfigValid) {
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
              ) {
                Text(
                  text = "OAuth configuration error: default_web_client_id is missing.",
                  color = MaterialTheme.colorScheme.onErrorContainer,
                  style = MaterialTheme.typography.bodyMedium,
                  modifier = Modifier.padding(12.dp)
                )
              }
              Spacer(modifier = Modifier.height(14.dp))
            }

            if (errorMessage != null) {
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().testTag("auth_error_banner")
              ) {
                Text(
                  text = errorMessage ?: "",
                  color = MaterialTheme.colorScheme.onErrorContainer,
                  style = MaterialTheme.typography.bodyMedium,
                  modifier = Modifier.padding(12.dp)
                )
              }
              Spacer(modifier = Modifier.height(14.dp))
            }

            // Prominent "Continue with Google" Button
            Button(
              onClick = {
                val activity = context.findActivity()
                if (activity == null) {
                  errorMessage =
                    "Unable to find active window for Google Sign-In. Please restart the app."
                  return@Button
                }
                errorMessage = null
                activeSignInJob?.cancel()
                val launchScope =
                  (activity as? LifecycleOwner)?.lifecycleScope ?: composeScope
                activeSignInJob =
                  signInWithGoogle(
                    activity = activity,
                    credentialManager = credentialManager,
                    webClientId = webClientId,
                    scope = launchScope,
                    onLoadingChange = setLoadingState,
                    onSuccess = { user ->
                      isLoading = false
                      currentOnAuthSuccess(user)
                    },
                    onError = setErrorState
                  )
              },
              enabled = !isLoading && isConfigValid,
              shape = RoundedCornerShape(16.dp),
              colors =
                ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.primary,
                  contentColor = MaterialTheme.colorScheme.onPrimary
                ),
              modifier =
                Modifier.fillMaxWidth()
                  .height(56.dp)
                  .testTag("google_sign_in_button")
            ) {
              if (isLoading) {
                CircularProgressIndicator(
                  modifier = Modifier.size(22.dp),
                  color = MaterialTheme.colorScheme.onPrimary,
                  strokeWidth = 2.5.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                  text = "Signing in…",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold
                )
              } else {
                Surface(
                  shape = CircleShape,
                  color = Color.White,
                  modifier = Modifier.size(28.dp)
                ) {
                  Box(contentAlignment = Alignment.Center) {
                    Text(
                      text = "G",
                      style = MaterialTheme.typography.labelLarge,
                      color = Color(0xFF4C1D95),
                      fontWeight = FontWeight.ExtraBold
                    )
                  }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                  text = stringResource(R.string.sign_in_with_google),
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold
                )
              }
            }
          }
        }
      }
    }
  }
}

/**
 * Executes `credentialManager.getCredential(...)` with both:
 * 1. An overall timeout (`overallTimeoutMs`), and
 * 2. A post-resume watchdog (`postResumeTimeoutMs`): once the Google Account Picker sheet opens
 *    (`ON_PAUSE`) and closes (`ON_RESUME`), if Google Play Services hangs without delivering the
 *    `GetCredentialResponse` within `postResumeTimeoutMs`, cancels the hung call immediately.
 */
private suspend fun getCredentialWithLifecycleWatchdog(
  credentialManager: CredentialManager,
  activity: Activity,
  request: GetCredentialRequest,
  overallTimeoutMs: Long = 22_000L,
  postResumeTimeoutMs: Long = 6_000L
): GetCredentialResponse =
  withTimeout(overallTimeoutMs) {
    coroutineScope {
      val resumedAfterSheetClosed = CompletableDeferred<Unit>()
      val lifecycleOwner = activity as? LifecycleOwner
      var observer: LifecycleEventObserver? = null

      if (lifecycleOwner != null) {
        var sawPause = false
        val obs = LifecycleEventObserver { _, event ->
          when (event) {
            Lifecycle.Event.ON_PAUSE,
            Lifecycle.Event.ON_STOP -> {
              sawPause = true
            }
            Lifecycle.Event.ON_RESUME -> {
              if (sawPause) {
                resumedAfterSheetClosed.complete(Unit)
              }
            }
            else -> {}
          }
        }
        observer = obs
        withContext(Dispatchers.Main.immediate) {
          lifecycleOwner.lifecycle.addObserver(obs)
        }
      }

      val credentialDeferred = asyncCredential(credentialManager, activity, request)
      val watchdogJob = launch {
        resumedAfterSheetClosed.await()
        delay(postResumeTimeoutMs)
        if (credentialDeferred.isActive) {
          Log.w(
            "AuthScreen",
            "CredentialManager did not return within ${postResumeTimeoutMs}ms after account picker closed; aborting hung request."
          )
          credentialDeferred.cancel(
            CancellationException(
              "CREDENTIAL_POST_RESUME_TIMEOUT: Google Play Services did not return a credential after account selection."
            )
          )
        }
      }

      try {
        credentialDeferred.await()
      } finally {
        watchdogJob.cancel()
        if (lifecycleOwner != null && observer != null) {
          withContext(NonCancellable + Dispatchers.Main.immediate) {
            lifecycleOwner.lifecycle.removeObserver(observer)
          }
        }
      }
    }
  }

private fun CoroutineScope.asyncCredential(
  credentialManager: CredentialManager,
  activity: Activity,
  request: GetCredentialRequest
): CompletableDeferred<GetCredentialResponse> {
  val deferred = CompletableDeferred<GetCredentialResponse>()
  val job =
    launch(Dispatchers.Main.immediate) {
      try {
        val response = credentialManager.getCredential(request = request, context = activity)
        deferred.complete(response)
      } catch (t: Throwable) {
        deferred.completeExceptionally(t)
      }
    }
  deferred.invokeOnCompletion { cause ->
    if (cause is CancellationException && job.isActive) {
      job.cancel(cause)
    }
  }
  return deferred
}

private fun signInWithGoogle(
  activity: Activity,
  credentialManager: CredentialManager,
  webClientId: String,
  scope: CoroutineScope,
  onLoadingChange: (Boolean) -> Unit,
  onSuccess: (FirebaseUser?) -> Unit,
  onError: (String) -> Unit
): Job {
  ensureFirebaseInitialized(activity.applicationContext)

  val existingUser = runCatching { Firebase.auth.currentUser }.getOrNull()
  if (existingUser != null) {
    onLoadingChange(false)
    onSuccess(existingUser)
    return Job().apply { complete() }
  }

  val signInOption = GetSignInWithGoogleOption.Builder(webClientId).build()
  val primaryRequest = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()

  return scope.launch(Dispatchers.Main.immediate) {
    onLoadingChange(true)
    try {
      val result =
        try {
          getCredentialWithLifecycleWatchdog(
            credentialManager = credentialManager,
            activity = activity,
            request = primaryRequest
          )
        } catch (primaryError: Throwable) {
          // If Firebase already signed the user in, succeed immediately
          val alreadySignedIn = runCatching { Firebase.auth.currentUser }.getOrNull()
          if (alreadySignedIn != null) {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
              onLoadingChange(false)
              onSuccess(alreadySignedIn)
            }
            return@launch
          }

          val isPostResumeTimeout =
            primaryError is CancellationException &&
              primaryError.message?.contains("CREDENTIAL_POST_RESUME_TIMEOUT") == true

          if (primaryError is GetCredentialException ||
            primaryError is TimeoutCancellationException ||
            isPostResumeTimeout
          ) {
            Log.w(
              "AuthScreen",
              "Primary GetSignInWithGoogleOption failed (${primaryError.javaClass.simpleName}: ${primaryError.localizedMessage}); retrying with GetGoogleIdOption",
              primaryError
            )
            val fallbackGoogleIdOption =
              GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(false)
                .build()
            val fallbackRequest =
              GetCredentialRequest.Builder().addCredentialOption(fallbackGoogleIdOption).build()
            getCredentialWithLifecycleWatchdog(
              credentialManager = credentialManager,
              activity = activity,
              request = fallbackRequest
            )
          } else {
            throw primaryError
          }
        }

      handleSignInResult(
        result = result,
        onSuccess = onSuccess,
        onLoadingChange = onLoadingChange,
        onError = onError
      )
    } catch (e: GetCredentialCancellationException) {
      Log.w(
        "AuthScreen",
        "Google Sign-In cancelled or rejected by Google Play Services: ${e.localizedMessage}",
        e
      )
      withContext(NonCancellable + Dispatchers.Main.immediate) {
        onLoadingChange(false)
        val currentUser = runCatching { Firebase.auth.currentUser }.getOrNull()
        if (currentUser != null) {
          onSuccess(currentUser)
        } else {
          onError(
            "Google Sign-In was cancelled or rejected by Google Play Services. Please select your Google account and try again."
          )
        }
      }
    } catch (e: NoCredentialException) {
      Log.w("AuthScreen", "No Google account available on device", e)
      withContext(NonCancellable + Dispatchers.Main.immediate) {
        onLoadingChange(false)
        onError(
          "No Google account found on this device. Please ensure a Google account is signed in on your phone and try again."
        )
      }
    } catch (e: GetCredentialException) {
      Log.e("AuthScreen", "Credential Manager error (${e.type}): ${e.localizedMessage}", e)
      withContext(NonCancellable + Dispatchers.Main.immediate) {
        onLoadingChange(false)
        onError(
          "Google Sign-In failed (${e.type}): ${e.localizedMessage ?: "Please try again."}"
        )
      }
    } catch (e: TimeoutCancellationException) {
      Log.e("AuthScreen", "Google Sign-In timed out", e)
      withContext(NonCancellable + Dispatchers.Main.immediate) {
        onLoadingChange(false)
        val currentUser = runCatching { Firebase.auth.currentUser }.getOrNull()
        if (currentUser != null) {
          onSuccess(currentUser)
        } else {
          onError(
            "Google Sign-In timed out waiting for response. Please check your internet connection and try again."
          )
        }
      }
    } catch (e: CancellationException) {
      Log.w("AuthScreen", "Google Sign-In coroutine cancelled: ${e.localizedMessage}", e)
      withContext(NonCancellable + Dispatchers.Main.immediate) {
        onLoadingChange(false)
        val currentUser = runCatching { Firebase.auth.currentUser }.getOrNull()
        if (currentUser != null) {
          onSuccess(currentUser)
        } else if (e.message?.contains("CREDENTIAL_POST_RESUME_TIMEOUT") == true) {
          onError(
            "Google Play Services did not return a credential after account selection. Please try again."
          )
        }
      }
      throw e
    } catch (e: Throwable) {
      Log.e("AuthScreen", "Unexpected error during Google Sign-In", e)
      withContext(NonCancellable + Dispatchers.Main.immediate) {
        onLoadingChange(false)
        onError("Sign-in failed: ${e.localizedMessage ?: "Unknown error"}")
      }
    } finally {
      withContext(NonCancellable + Dispatchers.Main.immediate) {
        onLoadingChange(false)
      }
    }
  }
}

internal fun extractGoogleIdToken(credential: Credential): String? {
  if (credential is GoogleIdTokenCredential) {
    val directToken = credential.idToken.trim()
    if (directToken.isNotEmpty()) return directToken
  }
  if (credential is CustomCredential) {
    val isGoogleType =
      credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ||
        credential.type == TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL ||
        credential.data.containsKey(GOOGLE_ID_TOKEN_BUNDLE_KEY)
    if (isGoogleType) {
      val parsed =
        runCatching { GoogleIdTokenCredential.createFrom(credential.data).idToken.trim() }
          .getOrNull()
      if (!parsed.isNullOrEmpty()) return parsed
    }
  }
  val fromBundle =
    runCatching { credential.data.getString(GOOGLE_ID_TOKEN_BUNDLE_KEY)?.trim() }.getOrNull()
  if (!fromBundle.isNullOrEmpty()) return fromBundle
  return null
}

/**
 * Awaits Firebase `signInWithCredential` using both:
 * 1. A real-time `FirebaseAuth.AuthStateListener` (resumes immediately if `auth.currentUser` becomes non-null), and
 * 2. A direct synchronous `Executor` on `Task.addOnCompleteListener` (never blocked by main-thread Handler stalls).
 */
private suspend fun awaitFirebaseSignIn(
  auth: FirebaseAuth,
  firebaseCredential: AuthCredential
): FirebaseUser = suspendCancellableCoroutine { cont ->
  val completed = AtomicBoolean(false)
  val directExecutor = Executor { runnable -> runnable.run() }

  val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
    val user = firebaseAuth.currentUser
    if (user != null && completed.compareAndSet(false, true)) {
      if (cont.isActive) {
        cont.resume(user)
      }
    }
  }
  auth.addAuthStateListener(authListener)
  cont.invokeOnCancellation {
    runCatching { auth.removeAuthStateListener(authListener) }
  }

  val task = auth.signInWithCredential(firebaseCredential)
  task.addOnCompleteListener(directExecutor) { completedTask ->
    runCatching { auth.removeAuthStateListener(authListener) }
    if (!cont.isActive) return@addOnCompleteListener
    val user =
      if (completedTask.isSuccessful) {
        completedTask.result?.user ?: auth.currentUser
      } else {
        auth.currentUser
      }
    if (user != null) {
      if (completed.compareAndSet(false, true)) {
        cont.resume(user)
      }
    } else if (completed.compareAndSet(false, true)) {
      val exception =
        completedTask.exception
          ?: IllegalStateException("Firebase signInWithCredential failed without returning a user.")
      cont.resumeWithException(exception)
    }
  }
}

internal suspend fun handleSignInResult(
  result: GetCredentialResponse,
  auth: FirebaseAuth? = null,
  onSuccess: (FirebaseUser?) -> Unit = {},
  onLoadingChange: (Boolean) -> Unit = {},
  onError: (String) -> Unit
) {
  val credential = result.credential
  try {
    val idToken = extractGoogleIdToken(credential)
    if (idToken == null) {
      withContext(NonCancellable + Dispatchers.Main.immediate) {
        onLoadingChange(false)
        if (credential is CustomCredential &&
          (credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ||
            credential.type == TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL)
        ) {
          onError("Google Sign-In returned an empty or invalid ID token. Please try again.")
        } else {
          Log.w("AuthScreen", "Unexpected credential type: ${credential.type}")
          onError("Unsupported credential type (${credential.type}) selected.")
        }
      }
      return
    }

    val resolvedAuth = auth ?: Firebase.auth
    val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
    val signedInUser =
      withTimeout(12_000L) {
        awaitFirebaseSignIn(resolvedAuth, firebaseCredential)
      }
    withContext(NonCancellable + Dispatchers.Main.immediate) {
      onLoadingChange(false)
      onSuccess(signedInUser)
    }
  } catch (e: GoogleIdTokenParsingException) {
    Log.e("AuthScreen", "Invalid Google ID token response", e)
    withContext(NonCancellable + Dispatchers.Main.immediate) {
      onLoadingChange(false)
      onError("Invalid Google ID token. Verify the Web Client ID in google-services.json.")
    }
  } catch (e: TimeoutCancellationException) {
    Log.e("AuthScreen", "Firebase signInWithCredential timed out", e)
    val fallbackUser = runCatching { (auth ?: Firebase.auth).currentUser }.getOrNull()
    withContext(NonCancellable + Dispatchers.Main.immediate) {
      onLoadingChange(false)
      if (fallbackUser != null) {
        onSuccess(fallbackUser)
      } else {
        onError(
          "Network timeout while verifying Google account with Firebase. Please check your internet connection and try again."
        )
      }
    }
  } catch (e: CancellationException) {
    withContext(NonCancellable + Dispatchers.Main.immediate) {
      onLoadingChange(false)
    }
    throw e
  } catch (e: Throwable) {
    Log.e("AuthScreen", "Firebase signInWithCredential failed", e)
    val fallbackUser = runCatching { (auth ?: Firebase.auth).currentUser }.getOrNull()
    withContext(NonCancellable + Dispatchers.Main.immediate) {
      onLoadingChange(false)
      if (fallbackUser != null) {
        onSuccess(fallbackUser)
      } else {
        onError("Authentication failed: ${e.localizedMessage ?: "Could not verify credential"}")
      }
    }
  } finally {
    withContext(NonCancellable + Dispatchers.Main.immediate) {
      onLoadingChange(false)
    }
  }
}

fun signOutUser(
  context: Context,
  credentialManager: CredentialManager,
  onSignOutComplete: () -> Unit,
  scope: CoroutineScope,
  auth: FirebaseAuth = Firebase.auth
) {
  scope.launch {
    try {
      auth.signOut()
      credentialManager.clearCredentialState(ClearCredentialStateRequest())
    } catch (e: Exception) {
      Log.e("AuthScreen", "Sign-out error: ${e.localizedMessage}")
    } finally {
      onSignOutComplete()
    }
  }
}
