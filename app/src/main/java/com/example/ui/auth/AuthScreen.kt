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
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class PortalLoginTarget {
  USER_APP,
  ADMIN_DASHBOARD
}

internal const val VERIFIED_TASKFLOW_WEB_CLIENT_ID =
  "54814309263-uralqcp874c3v5nrk9r4ajj9scpt93ug.apps.googleusercontent.com"

private const val LEGACY_PLACEHOLDER_WEB_CLIENT_ID =
  "54814309263-29a7s7d81k2v8a9s3d0f1g2h3j4k5l6m.apps.googleusercontent.com"

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
      fromRes != LEGACY_PLACEHOLDER_WEB_CLIENT_ID
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

  val currentOnAuthSuccess by rememberUpdatedState(onAuthSuccess)

  val webClientId = remember(context) { resolveWebClientId(context) }
  val isConfigValid = webClientId.isNotEmpty() && webClientId != "YOUR_WEB_CLIENT_ID"

  // If Firebase Auth already has an authenticated user (e.g., when returning from the Google
  // Account Picker overlay), immediately clear loading state and navigate to the Dashboard.
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
                val launchScope =
                  (activity as? LifecycleOwner)?.lifecycleScope ?: composeScope
                signInWithGoogle(
                  activity = activity,
                  credentialManager = credentialManager,
                  webClientId = webClientId,
                  scope = launchScope,
                  onLoadingChange = { loading -> isLoading = loading },
                  onSuccess = { user ->
                    isLoading = false
                    currentOnAuthSuccess(user)
                  },
                  onError = { msg ->
                    isLoading = false
                    errorMessage = msg
                  }
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

private fun signInWithGoogle(
  activity: Activity,
  credentialManager: CredentialManager,
  webClientId: String,
  scope: CoroutineScope,
  onLoadingChange: (Boolean) -> Unit,
  onSuccess: (FirebaseUser?) -> Unit,
  onError: (String) -> Unit
) {
  ensureFirebaseInitialized(activity.applicationContext)

  val existingUser = runCatching { Firebase.auth.currentUser }.getOrNull()
  if (existingUser != null) {
    onLoadingChange(false)
    onSuccess(existingUser)
    return
  }

  val signInOption = GetSignInWithGoogleOption.Builder(webClientId).build()
  val primaryRequest = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()

  scope.launch(Dispatchers.Main.immediate) {
    onLoadingChange(true)
    try {
      val result =
        try {
          credentialManager.getCredential(
            request = primaryRequest,
            context = activity
          )
        } catch (primaryError: GetCredentialException) {
          if (primaryError is GetCredentialCancellationException) {
            throw primaryError
          }
          Log.w(
            "AuthScreen",
            "GetSignInWithGoogleOption failed (${primaryError.javaClass.simpleName}: ${primaryError.type}); retrying with GetGoogleIdOption",
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
          credentialManager.getCredential(
            request = fallbackRequest,
            context = activity
          )
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
        "Google Sign-In cancelled or certificate/OAuth mismatch: ${e.localizedMessage}",
        e
      )
      onLoadingChange(false)
    } catch (e: NoCredentialException) {
      Log.w("AuthScreen", "No Google account available on device", e)
      onLoadingChange(false)
      onError(
        "No Google account found on this device. Please ensure a Google account is signed in on your phone and try again."
      )
    } catch (e: GetCredentialException) {
      Log.e("AuthScreen", "Credential Manager error (${e.type}): ${e.localizedMessage}", e)
      onLoadingChange(false)
      onError(
        "Google Sign-In failed (${e.type}): ${e.localizedMessage ?: "Please try again."}"
      )
    } catch (e: CancellationException) {
      onLoadingChange(false)
      throw e
    } catch (e: Throwable) {
      Log.e("AuthScreen", "Unexpected error during Google Sign-In", e)
      onLoadingChange(false)
      onError("Sign-in failed: ${e.localizedMessage ?: "Unknown error"}")
    } finally {
      onLoadingChange(false)
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

private suspend fun awaitFirebaseSignIn(
  auth: FirebaseAuth,
  firebaseCredential: AuthCredential
): AuthResult = suspendCancellableCoroutine { cont ->
  val task = auth.signInWithCredential(firebaseCredential)
  task.addOnCompleteListener { completedTask ->
    if (!cont.isActive) return@addOnCompleteListener
    val exception = completedTask.exception
    if (completedTask.isSuccessful && exception == null) {
      cont.resume(completedTask.result)
    } else {
      cont.resumeWithException(
        exception ?: IllegalStateException("Firebase signInWithCredential failed without exception.")
      )
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
      if (credential is CustomCredential &&
        (credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ||
          credential.type == TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL)
      ) {
        onLoadingChange(false)
        onError("Google Sign-In returned an empty or invalid ID token. Please try again.")
      } else {
        Log.w("AuthScreen", "Unexpected credential type: ${credential.type}")
        onLoadingChange(false)
        onError("Unsupported credential type selected.")
      }
      return
    }

    val resolvedAuth = auth ?: Firebase.auth
    val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
    val authResult =
      withTimeout(15_000L) {
        awaitFirebaseSignIn(resolvedAuth, firebaseCredential)
      }
    val signedInUser = authResult.user ?: resolvedAuth.currentUser
    withContext(Dispatchers.Main.immediate) {
      onLoadingChange(false)
      if (signedInUser != null) {
        onSuccess(signedInUser)
      } else {
        onError("Firebase Auth completed but no user session was returned. Please try again.")
      }
    }
  } catch (e: GoogleIdTokenParsingException) {
    Log.e("AuthScreen", "Invalid Google ID token response", e)
    withContext(Dispatchers.Main.immediate) {
      onLoadingChange(false)
      onError("Invalid Google ID token. Verify the Web Client ID in google-services.json.")
    }
  } catch (e: TimeoutCancellationException) {
    Log.e("AuthScreen", "Firebase signInWithCredential timed out", e)
    val fallbackUser = runCatching { (auth ?: Firebase.auth).currentUser }.getOrNull()
    withContext(Dispatchers.Main.immediate) {
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
    onLoadingChange(false)
    throw e
  } catch (e: Throwable) {
    Log.e("AuthScreen", "Firebase signInWithCredential failed", e)
    val fallbackUser = runCatching { (auth ?: Firebase.auth).currentUser }.getOrNull()
    withContext(Dispatchers.Main.immediate) {
      onLoadingChange(false)
      if (fallbackUser != null) {
        onSuccess(fallbackUser)
      } else {
        onError("Authentication failed: ${e.localizedMessage ?: "Could not verify credential"}")
      }
    }
  } finally {
    onLoadingChange(false)
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
