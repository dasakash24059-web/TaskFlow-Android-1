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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
  var startZoomOut by remember { mutableStateOf(false) }

  val zoomOutScale by animateFloatAsState(
    targetValue = if (startZoomOut) 0.88f else 1.12f,
    animationSpec = tween(durationMillis = durationMillis.coerceAtLeast(300L).toInt(), easing = FastOutSlowInEasing),
    label = "splash_zoom_out_scale"
  )
  val splashAlpha by animateFloatAsState(
    targetValue = if (startZoomOut) 1f else 0.90f,
    animationSpec = tween(durationMillis = durationMillis.coerceAtLeast(300L).toInt(), easing = FastOutSlowInEasing),
    label = "splash_alpha"
  )

  val infiniteTransition = rememberInfiniteTransition(label = "splash_pulse")
  val subtlePulse by infiniteTransition.animateFloat(
    initialValue = 1f,
    targetValue = 1.03f,
    animationSpec =
      infiniteRepeatable(
        animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
        repeatMode = RepeatMode.Reverse
      ),
    label = "splash_logo_pulse"
  )

  LaunchedEffect(durationMillis) {
    startZoomOut = true
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
                Color(0xFF0284C7),
                Color(0xFF1D4ED8),
                Color(0xFF2563EB),
                Color(0xFF4338CA),
                Color(0xFF6D28D9),
                Color(0xFF581C87)
              )
          )
        )
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .testTag("splash_screen"),
    contentAlignment = Alignment.Center
  ) {
    // Bottom subtle ambient waves
    Canvas(modifier = Modifier.fillMaxSize()) {
      val w = size.width
      val h = size.height

      // Soft ambient radial glow behind logo
      drawCircle(
        brush =
          Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.15f), Color.Transparent),
            center = Offset(w * 0.5f, h * 0.32f),
            radius = w * 0.5f
          ),
        radius = w * 0.5f,
        center = Offset(w * 0.5f, h * 0.32f)
      )

      // Decorative wave layers at bottom
      val wavePath1 =
        Path().apply {
          moveTo(0f, h * 0.82f)
          cubicTo(w * 0.25f, h * 0.76f, w * 0.70f, h * 0.88f, w, h * 0.80f)
          lineTo(w, h)
          lineTo(0f, h)
          close()
        }
      drawPath(
        path = wavePath1,
        color = Color(0xFF4F46E5).copy(alpha = 0.25f)
      )

      val wavePath2 =
        Path().apply {
          moveTo(0f, h * 0.88f)
          cubicTo(w * 0.35f, h * 0.94f, w * 0.75f, h * 0.82f, w, h * 0.90f)
          lineTo(w, h)
          lineTo(0f, h)
          close()
        }
      drawPath(
        path = wavePath2,
        color = Color(0xFF6D28D9).copy(alpha = 0.35f)
      )
    }

    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.SpaceBetween,
      modifier =
        Modifier.fillMaxSize()
          .padding(horizontal = 32.dp, vertical = 48.dp)
          .scale(zoomOutScale)
          .alpha(splashAlpha)
    ) {
      Spacer(modifier = Modifier.height(16.dp))

      // Center Brand Identity: Stylized Logo, App Name, Tagline & Growth Chart Graphic
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
      ) {
        Box(
          modifier =
            Modifier.scale(subtlePulse)
              .testTag("splash_logo")
        ) {
          TaskFlowBrandLogo(size = 104.dp, isLightMode = false)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
          text =
            buildAnnotatedString {
              withStyle(style = SpanStyle(color = Color.White)) {
                append("Task")
              }
              withStyle(style = SpanStyle(color = Color(0xFFA78BFA))) {
                append("Flow")
              }
            },
          style = MaterialTheme.typography.displayMedium,
          fontWeight = FontWeight.ExtraBold,
          fontSize = 42.sp,
          modifier = Modifier.testTag("splash_app_name")
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
          text = stringResource(R.string.splash_tagline),
          style = MaterialTheme.typography.titleMedium,
          color = Color.White.copy(alpha = 0.92f),
          fontWeight = FontWeight.Medium,
          textAlign = TextAlign.Center,
          modifier = Modifier.testTag("splash_tagline")
        )

        Spacer(modifier = Modifier.height(42.dp))

        // Upward trending bar chart with glowing ascending arrow illustration
        Box(
          modifier = Modifier.size(width = 220.dp, height = 110.dp),
          contentAlignment = Alignment.Center
        ) {
          Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // 5 glassmorphism translucent blue bars
            val barCount = 5
            val barWidth = w * 0.11f
            val spacing = (w - (barCount * barWidth)) / (barCount + 1)
            val heights = listOf(0.24f, 0.40f, 0.56f, 0.72f, 0.92f)

            for (i in 0 until barCount) {
              val barH = h * heights[i]
              val x = spacing + i * (barWidth + spacing)
              val y = h - barH

              drawRoundRect(
                brush =
                  Brush.verticalGradient(
                    colors =
                      listOf(
                        Color(0xFF60A5FA).copy(alpha = 0.75f),
                        Color(0xFF2563EB).copy(alpha = 0.45f)
                      ),
                    startY = y,
                    endY = h
                  ),
                topLeft = Offset(x, y),
                size = androidx.compose.ui.geometry.Size(barWidth, barH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx(), 6.dp.toPx())
              )
            }

            // Upward trending ascending cyan/blue arrow
            val arrowPath =
              Path().apply {
                moveTo(spacing * 0.8f, h * 0.78f)
                cubicTo(w * 0.35f, h * 0.65f, w * 0.60f, h * 0.42f, w * 0.88f, h * 0.16f)
              }
            drawPath(
              path = arrowPath,
              brush =
                Brush.linearGradient(
                  colors = listOf(Color(0xFF38BDF8), Color(0xFF60A5FA), Color(0xFF93C5FD)),
                  start = Offset(0f, h * 0.8f),
                  end = Offset(w, 0f)
                ),
              style =
                androidx.compose.ui.graphics.drawscope.Stroke(
                  width = 4.dp.toPx(),
                  cap = StrokeCap.Round
                )
            )

            // Arrow head pointing upward-right
            val headPath =
              Path().apply {
                moveTo(w * 0.88f, h * 0.16f)
                lineTo(w * 0.74f, h * 0.18f)
                moveTo(w * 0.88f, h * 0.16f)
                lineTo(w * 0.85f, h * 0.30f)
              }
            drawPath(
              path = headPath,
              color = Color(0xFFBAE6FD),
              style =
                androidx.compose.ui.graphics.drawscope.Stroke(
                  width = 4.dp.toPx(),
                  cap = StrokeCap.Round
                )
            )
          }
        }
      }

      // Bottom circular progress loading indicator
      CircularProgressIndicator(
        modifier = Modifier.size(28.dp).testTag("splash_loading_indicator"),
        color = Color.White.copy(alpha = 0.85f),
        strokeWidth = 2.5.dp
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
                Color(0xFFFFFFFF),
                Color(0xFFF8FAFC),
                Color(0xFFF1F5F9)
              )
          )
        )
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .testTag("google_login_screen"),
    contentAlignment = Alignment.TopCenter
  ) {
    // Ambient soft lavender/blue blurred circles matching the screenshot
    Canvas(modifier = Modifier.fillMaxSize()) {
      drawCircle(
        color = Color(0xFFC7D2FE).copy(alpha = 0.35f),
        radius = size.width * 0.38f,
        center = Offset(size.width * 1.05f, size.height * 0.16f)
      )
      drawCircle(
        color = Color(0xFFDDD6FE).copy(alpha = 0.30f),
        radius = size.width * 0.28f,
        center = Offset(size.width * -0.05f, size.height * 0.04f)
      )
      drawCircle(
        color = Color(0xFFE0E7FF).copy(alpha = 0.40f),
        radius = size.width * 0.45f,
        center = Offset(size.width * 0.15f, size.height * 0.94f)
      )
    }

    Column(
      modifier =
        Modifier.fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 24.dp, vertical = 28.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.SpaceBetween
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 440.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Spacer(modifier = Modifier.height(24.dp))

        // TaskFlow Brand Logo (matching screenshot)
        Box(modifier = Modifier.testTag("login_logo")) {
          TaskFlowBrandLogo(size = 88.dp)
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Brand Name "TaskFlow"
        Text(
          text = stringResource(R.string.app_name),
          style = MaterialTheme.typography.displayMedium,
          color = Color(0xFF1E1B4B),
          fontWeight = FontWeight.ExtraBold,
          textAlign = TextAlign.Center,
          modifier = Modifier.testTag("login_app_name")
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Welcome Headings (inside login_card for test compatibility)
        Box(
          modifier = Modifier.fillMaxWidth().testTag("login_card"),
          contentAlignment = Alignment.Center
        ) {
          Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Text(
              text = stringResource(R.string.welcome_to_taskflow),
              style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp),
              color = Color(0xFF0F172A),
              fontWeight = FontWeight.ExtraBold,
              textAlign = TextAlign.Center,
              modifier = Modifier.testTag("login_welcome_heading")
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
              text = stringResource(R.string.sign_in_to_continue),
              style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
              color = Color(0xFF475569),
              textAlign = TextAlign.Center,
              modifier = Modifier.testTag("login_subtitle")
            )
          }
        }

        Spacer(modifier = Modifier.height(26.dp))

        if (!isConfigValid) {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth()
          ) {
            Text(
              text = "OAuth configuration error: default_web_client_id is missing.",
              color = MaterialTheme.colorScheme.onErrorContainer,
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(14.dp)
            )
          }
          Spacer(modifier = Modifier.height(14.dp))
        }

        if (errorMessage != null) {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth().testTag("auth_error_banner")
          ) {
            Text(
              text = errorMessage ?: "",
              color = MaterialTheme.colorScheme.onErrorContainer,
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(14.dp)
            )
          }
          Spacer(modifier = Modifier.height(14.dp))
        }

        // Pill-Shaped "Continue with Google" Button (matching screenshot)
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
          shape = RoundedCornerShape(50),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = Color.Transparent,
              disabledContainerColor = Color(0xFF94A3B8)
            ),
          contentPadding = PaddingValues(0.dp),
          modifier =
            Modifier.fillMaxWidth()
              .height(58.dp)
              .clip(RoundedCornerShape(50))
              .background(
                Brush.horizontalGradient(
                  colors =
                    listOf(
                      Color(0xFF2563EB),
                      Color(0xFF1D4ED8),
                      Color(0xFF3B82F6)
                    )
                )
              )
              .testTag("google_sign_in_button")
        ) {
          if (isLoading) {
            CircularProgressIndicator(
              modifier = Modifier.size(24.dp),
              color = Color.White,
              strokeWidth = 2.5.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
              text = "Signing in…",
              style = MaterialTheme.typography.titleMedium,
              color = Color.White,
              fontWeight = FontWeight.Bold
            )
          } else {
            Row(
              modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              // Google 'G' icon badge on crisp white circular pill
              Surface(
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 2.dp,
                modifier = Modifier.size(42.dp)
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Canvas(modifier = Modifier.size(22.dp)) {
                    val w = size.width
                    val h = size.height
                    // Multi-color Google G icon arcs
                    val strokeW = 3.5.dp.toPx()

                    // Blue horizontal bar
                    drawLine(
                      color = Color(0xFF4285F4),
                      start = Offset(w * 0.5f, h * 0.5f),
                      end = Offset(w * 0.95f, h * 0.5f),
                      strokeWidth = strokeW,
                      cap = StrokeCap.Round
                    )

                    // Blue top-right arc
                    drawArc(
                      color = Color(0xFF4285F4),
                      startAngle = 0f,
                      sweepAngle = -45f,
                      useCenter = false,
                      style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW)
                    )

                    // Red top arc
                    drawArc(
                      color = Color(0xFFEA4335),
                      startAngle = -45f,
                      sweepAngle = -110f,
                      useCenter = false,
                      style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW)
                    )

                    // Yellow left arc
                    drawArc(
                      color = Color(0xFFFBBC05),
                      startAngle = -155f,
                      sweepAngle = -65f,
                      useCenter = false,
                      style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW)
                    )

                    // Green bottom arc
                    drawArc(
                      color = Color(0xFF34A853),
                      startAngle = -220f,
                      sweepAngle = -95f,
                      useCenter = false,
                      style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW)
                    )
                  }
                }
              }

              Spacer(modifier = Modifier.weight(1f))

              Text(
                text = stringResource(R.string.sign_in_with_google),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                color = Color.White,
                fontWeight = FontWeight.Bold
              )

              Spacer(modifier = Modifier.weight(1f))
              Spacer(modifier = Modifier.width(42.dp)) // Optical balance with left badge
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(32.dp))

      // 3D-styled Clipboard and Golden Trophy Cup illustration matching Screenshot_20261106-193534
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .height(260.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
      ) {
        TaskFlowAuthIllustration(modifier = Modifier.fillMaxSize())
      }
    }
  }
}

/**
 * Custom Vector Illustration matching Screenshot_20261106-193534:
 * - Ambient pedestal platform oval with soft shadow
 * - Lush green foliage leaves on the left
 * - Royal blue clipboard with rounded corners, silver/slate top clip, white paper checklist
 * - 3 checklist rows: blue square checkboxes with crisp white checkmarks, horizontal text lines
 * - Bright golden trophy cup on right with dual handles, star emblem, and dark pedestal base with yellow plaque
 */
@Composable
private fun TaskFlowAuthIllustration(modifier: Modifier = Modifier) {
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height

    // 1. Soft pedestal platform ellipse at bottom
    drawOval(
      color = Color(0xFFC7D2FE).copy(alpha = 0.45f),
      topLeft = Offset(w * 0.10f, h * 0.76f),
      size = androidx.compose.ui.geometry.Size(w * 0.80f, h * 0.20f)
    )
    drawOval(
      color = Color(0xFFA5B4FC).copy(alpha = 0.50f),
      topLeft = Offset(w * 0.16f, h * 0.79f),
      size = androidx.compose.ui.geometry.Size(w * 0.68f, h * 0.14f)
    )

    // 2. Green leaves on the left
    val leafPath1 =
      Path().apply {
        moveTo(w * 0.22f, h * 0.78f)
        cubicTo(w * 0.10f, h * 0.72f, w * 0.12f, h * 0.58f, w * 0.18f, h * 0.54f)
        cubicTo(w * 0.25f, h * 0.58f, w * 0.26f, h * 0.70f, w * 0.22f, h * 0.78f)
        close()
      }
    drawPath(path = leafPath1, color = Color(0xFF4ADE80))

    val leafPath2 =
      Path().apply {
        moveTo(w * 0.24f, h * 0.82f)
        cubicTo(w * 0.12f, h * 0.82f, w * 0.08f, h * 0.70f, w * 0.14f, h * 0.64f)
        cubicTo(w * 0.22f, h * 0.66f, w * 0.26f, h * 0.76f, w * 0.24f, h * 0.82f)
        close()
      }
    drawPath(path = leafPath2, color = Color(0xFF22C55E))

    // 3. Royal Blue Clipboard (behind trophy, on left-center)
    val boardLeft = w * 0.26f
    val boardTop = h * 0.28f
    val boardWidth = w * 0.42f
    val boardHeight = h * 0.52f
    val boardRadius = 24f

    // Board shadow
    drawRoundRect(
      color = Color(0xFF1E3A8A).copy(alpha = 0.20f),
      topLeft = Offset(boardLeft + 4f, boardTop + 6f),
      size = androidx.compose.ui.geometry.Size(boardWidth, boardHeight),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(boardRadius, boardRadius)
    )

    // Board body (vibrant royal blue)
    drawRoundRect(
      brush =
        Brush.verticalGradient(
          colors = listOf(Color(0xFF3B82F6), Color(0xFF1D4ED8)),
          startY = boardTop,
          endY = boardTop + boardHeight
        ),
      topLeft = Offset(boardLeft, boardTop),
      size = androidx.compose.ui.geometry.Size(boardWidth, boardHeight),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(boardRadius, boardRadius)
    )

    // White paper document
    val paperLeft = boardLeft + boardWidth * 0.08f
    val paperTop = boardTop + boardHeight * 0.10f
    val paperWidth = boardWidth * 0.84f
    val paperHeight = boardHeight * 0.84f
    drawRoundRect(
      color = Color.White,
      topLeft = Offset(paperLeft, paperTop),
      size = androidx.compose.ui.geometry.Size(paperWidth, paperHeight),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(14f, 14f)
    )

    // Top clip on clipboard
    val clipWidth = boardWidth * 0.38f
    val clipHeight = boardHeight * 0.10f
    val clipLeft = boardLeft + (boardWidth - clipWidth) / 2f
    val clipTop = boardTop - clipHeight * 0.35f
    drawRoundRect(
      color = Color(0xFF1E293B),
      topLeft = Offset(clipLeft, clipTop),
      size = androidx.compose.ui.geometry.Size(clipWidth, clipHeight),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f)
    )
    // Clip loop
    drawArc(
      color = Color(0xFF64748B),
      startAngle = 180f,
      sweepAngle = 180f,
      useCenter = false,
      topLeft = Offset(clipLeft + clipWidth * 0.28f, clipTop - 12f),
      size = androidx.compose.ui.geometry.Size(clipWidth * 0.44f, 22f),
      style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f, cap = StrokeCap.Round)
    )

    // 3 Checklist rows on the paper
    for (i in 0..2) {
      val rowY = paperTop + paperHeight * (0.20f + i * 0.26f)
      val boxSize = paperWidth * 0.18f
      val boxLeft = paperLeft + paperWidth * 0.08f
      val boxTop = rowY - boxSize * 0.4f

      // Checkbox square
      drawRoundRect(
        color = Color(0xFF2563EB),
        topLeft = Offset(boxLeft, boxTop),
        size = androidx.compose.ui.geometry.Size(boxSize, boxSize),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
      )

      // White checkmark inside checkbox
      val checkPath =
        Path().apply {
          moveTo(boxLeft + boxSize * 0.25f, boxTop + boxSize * 0.50f)
          lineTo(boxLeft + boxSize * 0.45f, boxTop + boxSize * 0.72f)
          lineTo(boxLeft + boxSize * 0.80f, boxTop + boxSize * 0.28f)
        }
      drawPath(
        path = checkPath,
        color = Color.White,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.5f, cap = StrokeCap.Round)
      )

      // Horizontal task description lines
      val lineLeft = boxLeft + boxSize + 10f
      val line1Width = paperWidth * 0.52f
      drawLine(
        color = Color(0xFF93C5FD),
        start = Offset(lineLeft, boxTop + boxSize * 0.35f),
        end = Offset(lineLeft + line1Width, boxTop + boxSize * 0.35f),
        strokeWidth = 5f,
        cap = StrokeCap.Round
      )
      val line2Width = paperWidth * 0.36f
      drawLine(
        color = Color(0xFFE2E8F0),
        start = Offset(lineLeft, boxTop + boxSize * 0.75f),
        end = Offset(lineLeft + line2Width, boxTop + boxSize * 0.75f),
        strokeWidth = 4f,
        cap = StrokeCap.Round
      )
    }

    // 4. Golden Trophy Cup (on front-right)
    val cupCenterX = w * 0.66f
    val cupCenterY = h * 0.56f
    val cupRadius = w * 0.17f

    // Handles on left & right
    val handleStroke = 10f
    // Left handle
    drawArc(
      color = Color(0xFFF59E0B),
      startAngle = 100f,
      sweepAngle = 160f,
      useCenter = false,
      topLeft = Offset(cupCenterX - cupRadius * 1.55f, cupCenterY - cupRadius * 0.80f),
      size = androidx.compose.ui.geometry.Size(cupRadius * 0.85f, cupRadius * 1.25f),
      style = androidx.compose.ui.graphics.drawscope.Stroke(width = handleStroke, cap = StrokeCap.Round)
    )
    // Right handle
    drawArc(
      color = Color(0xFFF59E0B),
      startAngle = -80f,
      sweepAngle = 160f,
      useCenter = false,
      topLeft = Offset(cupCenterX + cupRadius * 0.70f, cupCenterY - cupRadius * 0.80f),
      size = androidx.compose.ui.geometry.Size(cupRadius * 0.85f, cupRadius * 1.25f),
      style = androidx.compose.ui.graphics.drawscope.Stroke(width = handleStroke, cap = StrokeCap.Round)
    )

    // Cup body path
    val cupPath =
      Path().apply {
        moveTo(cupCenterX - cupRadius, cupCenterY - cupRadius * 0.85f)
        lineTo(cupCenterX + cupRadius, cupCenterY - cupRadius * 0.85f)
        lineTo(cupCenterX + cupRadius * 0.85f, cupCenterY + cupRadius * 0.15f)
        cubicTo(
          cupCenterX + cupRadius * 0.65f, cupCenterY + cupRadius * 0.80f,
          cupCenterX - cupRadius * 0.65f, cupCenterY + cupRadius * 0.80f,
          cupCenterX - cupRadius * 0.85f, cupCenterY + cupRadius * 0.15f
        )
        close()
      }
    drawPath(
      path = cupPath,
      brush =
        Brush.horizontalGradient(
          colors = listOf(Color(0xFFF59E0B), Color(0xFFFBBF24), Color(0xFFFEF08A), Color(0xFFF59E0B)),
          startX = cupCenterX - cupRadius,
          endX = cupCenterX + cupRadius
        )
    )

    // Cup rim ellipse
    drawOval(
      brush =
        Brush.horizontalGradient(
          colors = listOf(Color(0xFFD97706), Color(0xFFFDE68A), Color(0xFFD97706)),
          startX = cupCenterX - cupRadius,
          endX = cupCenterX + cupRadius
        ),
      topLeft = Offset(cupCenterX - cupRadius, cupCenterY - cupRadius * 0.95f),
      size = androidx.compose.ui.geometry.Size(cupRadius * 2f, cupRadius * 0.30f)
    )

    // Shield / Star emblem on the cup
    drawCircle(
      color = Color(0xFFF59E0B).copy(alpha = 0.55f),
      radius = cupRadius * 0.32f,
      center = Offset(cupCenterX, cupCenterY - cupRadius * 0.15f)
    )

    // Trophy Stem
    val stemWidth = cupRadius * 0.32f
    val stemTop = cupCenterY + cupRadius * 0.70f
    val stemHeight = cupRadius * 0.35f
    drawRect(
      brush =
        Brush.horizontalGradient(
          colors = listOf(Color(0xFFD97706), Color(0xFFFBBF24), Color(0xFFD97706)),
          startX = cupCenterX - stemWidth / 2f,
          endX = cupCenterX + stemWidth / 2f
        ),
      topLeft = Offset(cupCenterX - stemWidth / 2f, stemTop),
      size = androidx.compose.ui.geometry.Size(stemWidth, stemHeight)
    )

    // Trophy Base Pedestal (Dark Navy/Slate with yellow plaque)
    val baseWidth = cupRadius * 1.30f
    val baseHeight = cupRadius * 0.48f
    val baseTop = stemTop + stemHeight
    drawRoundRect(
      color = Color(0xFF1E293B),
      topLeft = Offset(cupCenterX - baseWidth / 2f, baseTop),
      size = androidx.compose.ui.geometry.Size(baseWidth, baseHeight),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
    )

    // Golden plaque on pedestal
    val plaqueWidth = baseWidth * 0.58f
    val plaqueHeight = baseHeight * 0.40f
    drawRoundRect(
      color = Color(0xFFFBBF24),
      topLeft = Offset(cupCenterX - plaqueWidth / 2f, baseTop + (baseHeight - plaqueHeight) / 2f),
      size = androidx.compose.ui.geometry.Size(plaqueWidth, plaqueHeight),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
    )
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
