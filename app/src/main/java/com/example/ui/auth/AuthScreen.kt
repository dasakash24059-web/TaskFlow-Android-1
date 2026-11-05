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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.example.ui.components.TaskFlowBrandLogo
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

enum class PortalLoginTarget {
  USER_APP,
  ADMIN_DASHBOARD
}

internal tailrec fun Context.findActivity(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
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
  val scope = rememberCoroutineScope()
  val credentialManager = remember(context) { CredentialManager.create(context) }

  var isLoading by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  val webClientId =
    remember(context) {
      runCatching { context.getString(R.string.default_web_client_id).trim() }
        .getOrDefault("")
    }
  val isConfigValid =
    webClientId.isNotEmpty() && webClientId != "YOUR_WEB_CLIENT_ID"

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
                signInWithGoogle(
                  activity = activity,
                  credentialManager = credentialManager,
                  webClientId = webClientId,
                  scope = scope,
                  onLoadingChange = { isLoading = it },
                  onSuccess = onAuthSuccess,
                  onError = { errorMessage = it }
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
  val signInOption = GetSignInWithGoogleOption.Builder(webClientId).build()
  val primaryRequest = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()

  scope.launch {
    onLoadingChange(true)
    try {
      val result =
        try {
          credentialManager.getCredential(
            request = primaryRequest,
            context = activity
          )
        } catch (noCredError: NoCredentialException) {
          Log.w(
            "AuthScreen",
            "GetSignInWithGoogleOption returned NoCredentialException; retrying with GetGoogleIdOption",
            noCredError
          )
          val googleIdOption =
            GetGoogleIdOption.Builder()
              .setFilterByAuthorizedAccounts(false)
              .setServerClientId(webClientId)
              .setAutoSelectEnabled(false)
              .build()
          val fallbackRequest =
            GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()
          credentialManager.getCredential(
            request = fallbackRequest,
            context = activity
          )
        }

      handleSignInResult(
        credential = result.credential,
        onSuccess = onSuccess,
        onError = onError
      )
    } catch (e: GetCredentialCancellationException) {
      Log.d("AuthScreen", "User cancelled Google Sign-In", e)
    } catch (e: NoCredentialException) {
      Log.w("AuthScreen", "No Google account available on device", e)
      onError(
        "No Google account found on this device, or Google Play Services rejected the request. Please ensure a Google account is signed in on your phone."
      )
    } catch (e: GetCredentialException) {
      Log.e("AuthScreen", "Credential Manager error (${e.type}): ${e.localizedMessage}", e)
      onError(
        "Google Sign-In failed (${e.type}): ${e.localizedMessage ?: "Please try again."}"
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      Log.e("AuthScreen", "Unexpected error during Google Sign-In", e)
      onError("Sign-in failed: ${e.localizedMessage ?: "Unknown error"}")
    } finally {
      onLoadingChange(false)
    }
  }
}

private suspend fun handleSignInResult(
  credential: Credential,
  onSuccess: (FirebaseUser?) -> Unit,
  onError: (String) -> Unit
) {
  if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
    try {
      val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
      val idToken = googleIdTokenCredential.idToken
      if (idToken.isBlank()) {
        onError("Google Sign-In returned an empty ID token. Please try again.")
        return
      }
      val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
      val authResult =
        withTimeout(25_000L) {
          Firebase.auth.signInWithCredential(firebaseCredential).await()
        }
      val signedInUser = authResult.user ?: Firebase.auth.currentUser
      if (signedInUser != null) {
        onSuccess(signedInUser)
      } else {
        onError("Firebase Auth completed but no user session was returned. Please try again.")
      }
    } catch (e: GoogleIdTokenParsingException) {
      Log.e("AuthScreen", "Invalid Google ID token response", e)
      onError("Invalid Google ID token. Verify the Web Client ID in google-services.json.")
    } catch (e: TimeoutCancellationException) {
      Log.e("AuthScreen", "Firebase signInWithCredential timed out", e)
      onError(
        "Network timeout while verifying Google account with Firebase. Please check your internet connection and try again."
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      Log.e("AuthScreen", "Firebase signInWithCredential failed", e)
      onError("Authentication failed: ${e.localizedMessage ?: "Could not verify credential"}")
    }
  } else {
    Log.w("AuthScreen", "Unexpected credential type: ${credential.type}")
    onError("Unsupported credential type selected.")
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
