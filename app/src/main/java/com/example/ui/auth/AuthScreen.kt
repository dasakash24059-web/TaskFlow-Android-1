package com.example.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.example.ensureFirebaseInitialized
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

enum class PortalLoginTarget {
  USER_APP,
  ADMIN_DASHBOARD
}

private const val FALLBACK_WEB_CLIENT_ID =
  "54814309263-uralqcp874c3v5nrk9r4ajj9scpt93ug.apps.googleusercontent.com"

private tailrec fun Context.findActivity(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }

private fun resolveServerClientId(context: Context): String {
  val fromRes =
    runCatching { context.getString(R.string.default_web_client_id).trim() }.getOrDefault("")
  return if (fromRes.isNotEmpty() && !fromRes.startsWith("YOUR_")) {
    fromRes
  } else {
    FALLBACK_WEB_CLIENT_ID
  }
}

private fun extractGoogleIdToken(credential: Credential): String {
  return when {
    credential is GoogleIdTokenCredential -> credential.idToken
    credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL -> {
      GoogleIdTokenCredential.createFrom(credential.data).idToken
    }
    credential is CustomCredential -> {
      GoogleIdTokenCredential.createFrom(credential.data).idToken
    }
    else -> {
      throw IllegalStateException("Unsupported credential type received: ${credential.type}")
    }
  }
}

private suspend fun exchangeGoogleIdTokenWithFirebase(
  context: Context,
  idToken: String
): FirebaseUser {
  require(idToken.isNotBlank()) { "Google ID token is empty." }
  check(ensureFirebaseInitialized(context.applicationContext)) {
    "FirebaseApp is not initialized. Check app/google-services.json."
  }
  val firebaseAuth = Firebase.auth
  val authCredential = GoogleAuthProvider.getCredential(idToken, null)
  val authResult =
    withTimeout(25_000L) {
      firebaseAuth.signInWithCredential(authCredential).await()
    }
  return authResult.user
    ?: firebaseAuth.currentUser
    ?: throw IllegalStateException("Firebase signInWithCredential completed but user is null.")
}

fun onGoogleSignInClicked(
  context: Context,
  credentialManager: CredentialManager,
  onAuthSuccess: (FirebaseUser) -> Unit,
  onAuthError: (String) -> Unit,
  scope: CoroutineScope,
  onStatusUpdate: (String?) -> Unit = {}
) {
  val activity = context.findActivity()
  if (activity == null || activity.isFinishing || activity.isDestroyed) {
    onAuthError("Unable to launch Google Sign-In: Activity context is not active.")
    return
  }

  val clientId = resolveServerClientId(context)
  if (clientId.isEmpty()) {
    onAuthError("Google Sign-In configuration missing: default_web_client_id is empty.")
    return
  }

  scope.launch {
    try {
      onStatusUpdate("Step 1/3: Opening Google Account Chooser...")
      val credentialResult =
        try {
          val googleIdOption =
            GetGoogleIdOption.Builder()
              .setFilterByAuthorizedAccounts(false)
              .setServerClientId(clientId)
              .setAutoSelectEnabled(false)
              .build()
          val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()
          credentialManager.getCredential(context = activity, request = request)
        } catch (e: NoCredentialException) {
          Log.w("Auth", "NoCredentialException with GetGoogleIdOption, trying GetSignInWithGoogleOption", e)
          val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
          val fallbackRequest = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()
          credentialManager.getCredential(context = activity, request = fallbackRequest)
        }

      onStatusUpdate("Step 2/3: Extracting Google ID token...")
      val googleIdToken = extractGoogleIdToken(credentialResult.credential)

      onStatusUpdate("Step 3/3: Signing in to Firebase Auth...")
      val firebaseUser = exchangeGoogleIdTokenWithFirebase(context, googleIdToken)
      onStatusUpdate(null)
      Log.i("Auth", "Firebase Google Sign-In succeeded for uid=${firebaseUser.uid}")
      onAuthSuccess(firebaseUser)
    } catch (e: GetCredentialCancellationException) {
      onStatusUpdate(null)
      Log.w("Auth", "Google Sign-In cancelled or rejected by Play Services: ${e.message}", e)
      val detail = e.errorMessage?.toString() ?: e.localizedMessage ?: "RESULT_CANCELED"
      onAuthError(
        "Google Sign-In was closed or rejected by Google Play Services ($detail). " +
          "Check: (1) APK is signed with SHA-1 A7:AA:CD..., and (2) Support Email is set in Firebase Console -> Project Settings."
      )
    } catch (e: GoogleIdTokenParsingException) {
      onStatusUpdate(null)
      Log.e("Auth", "Invalid Google ID token returned by Credential Manager", e)
      onAuthError("Failed to parse Google ID token: ${e.localizedMessage ?: e.javaClass.simpleName}")
    } catch (e: GetCredentialException) {
      onStatusUpdate(null)
      Log.e("Auth", "Credential Manager error (${e.type}): ${e.message}", e)
      val detail = e.errorMessage?.toString() ?: e.localizedMessage ?: e.type
      onAuthError(
        "Credential Manager error (${e.type}): $detail"
      )
    } catch (e: CancellationException) {
      onStatusUpdate(null)
      throw e
    } catch (e: Throwable) {
      onStatusUpdate(null)
      Log.e("Auth", "Firebase signInWithCredential failed", e)
      onAuthError(
        "Firebase Auth error (${e.javaClass.simpleName}): ${e.localizedMessage ?: "Unable to complete sign-in."}"
      )
    }
  }
}

fun signOutUser(
  context: Context,
  credentialManager: CredentialManager,
  onSignOutComplete: () -> Unit,
  scope: CoroutineScope
) {
  runCatching { Firebase.auth.signOut() }
  scope.launch {
    try {
      credentialManager.clearCredentialState(ClearCredentialStateRequest())
    } catch (e: Throwable) {
      Log.e("Auth", "Failed to clear credential state", e)
    } finally {
      onSignOutComplete()
    }
  }
}

@Composable
fun AuthScreen(
  selectedPortal: PortalLoginTarget,
  onSelectPortal: (PortalLoginTarget) -> Unit,
  onAuthSuccess: (FirebaseUser?) -> Unit
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val credentialManager = remember(context) { CredentialManager.create(context) }
  var isLoading by remember { mutableStateOf(false) }
  var statusMessage by remember { mutableStateOf<String?>(null) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  // Check if Firebase already has an active authenticated session without launching a competing
  // background CredentialManager HiddenActivity on startup.
  LaunchedEffect(Unit) {
    ensureFirebaseInitialized(context.applicationContext)
    val existingUser = runCatching { Firebase.auth.currentUser }.getOrNull()
    if (existingUser != null) {
      onAuthSuccess(existingUser)
    }
  }

  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background
  ) {
    Box(
      modifier =
        Modifier.fillMaxSize()
          .windowInsetsPadding(WindowInsets.safeDrawing)
          .padding(horizontal = 20.dp),
      contentAlignment = Alignment.Center
    ) {
      Column(
        modifier =
          Modifier.fillMaxWidth()
            .widthIn(max = 480.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        // Brand Hero Illustration Card
        Box(
          modifier =
            Modifier.fillMaxWidth()
              .height(180.dp)
              .clip(RoundedCornerShape(28.dp))
              .background(
                Brush.linearGradient(
                  colors =
                    listOf(
                      Color(0xFF0B132B),
                      Color(0xFF1C2541),
                      Color(0xFF064E3B)
                    )
                )
              )
        ) {
          Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawCircle(
              color = Color(0xFF34D399).copy(alpha = 0.14f),
              radius = h * 0.65f,
              center = Offset(w * 0.85f, h * 0.2f)
            )
            drawCircle(
              color = Color(0xFF38BDF8).copy(alpha = 0.12f),
              radius = h * 0.5f,
              center = Offset(w * 0.15f, h * 0.85f)
            )
            val path =
              Path().apply {
                moveTo(0f, h * 0.75f)
                cubicTo(w * 0.3f, h * 0.45f, w * 0.65f, h * 0.9f, w, h * 0.35f)
              }
            drawPath(
              path = path,
              color = Color(0xFF34D399).copy(alpha = 0.35f),
              style = Stroke(width = 4f)
            )
          }

          Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF10B981).copy(alpha = 0.2f),
                modifier = Modifier.size(48.dp)
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Icon(
                    imageVector = Icons.Default.TaskAlt,
                    contentDescription = "TaskFlow Logo",
                    tint = Color(0xFF34D399),
                    modifier = Modifier.size(28.dp)
                  )
                }
              }
              Column {
                Text(
                  text = stringResource(R.string.app_name),
                  style = MaterialTheme.typography.headlineMedium,
                  color = Color.White
                )
                Text(
                  text = "MOBILE WORKFLOW & REWARDS",
                  style = MaterialTheme.typography.labelSmall,
                  color = Color(0xFF34D399)
                )
              }
            }

            Text(
              text = stringResource(R.string.tagline_subtitle),
              style = MaterialTheme.typography.bodyMedium,
              color = Color(0xFFE2E8F0)
            )
          }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Portal Selector: User App vs Admin Dashboard Login
        Text(
          text = "Select Workspace Portal",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))

        Row(
          modifier =
            Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(16.dp))
              .background(MaterialTheme.colorScheme.surfaceVariant)
              .padding(6.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          PortalTabItem(
            title = "User App",
            subtitle = "Tasks & Proof",
            icon = Icons.Default.Person,
            selected = selectedPortal == PortalLoginTarget.USER_APP,
            onClick = { onSelectPortal(PortalLoginTarget.USER_APP) },
            testTag = "portal_user_tab",
            modifier = Modifier.weight(1f)
          )
          PortalTabItem(
            title = "Admin Portal",
            subtitle = "Review & Manage",
            icon = Icons.Default.AdminPanelSettings,
            selected = selectedPortal == PortalLoginTarget.ADMIN_DASHBOARD,
            onClick = { onSelectPortal(PortalLoginTarget.ADMIN_DASHBOARD) },
            testTag = "portal_admin_tab",
            modifier = Modifier.weight(1f)
          )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Auth Card
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(24.dp),
          colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Icon(
              imageVector =
                if (selectedPortal == PortalLoginTarget.ADMIN_DASHBOARD) {
                  Icons.Default.AdminPanelSettings
                } else {
                  Icons.Default.CheckCircle
                },
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
              text =
                if (selectedPortal == PortalLoginTarget.ADMIN_DASHBOARD) {
                  "Administrator Sign In"
                } else {
                  "User Sign In & Registration"
                },
              style = MaterialTheme.typography.titleLarge,
              textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text =
                if (selectedPortal == PortalLoginTarget.ADMIN_DASHBOARD) {
                  "Sign in with an authorized administrator Google account to create tasks, inspect user proof submissions, and approve INR (₹) rewards."
                } else {
                  "Sign in or register instantly with Google Credential Manager. Your profile, assigned tasks, and INR (₹) reward history are secured by Cloud Firestore."
                },
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center
            )

            if (statusMessage != null) {
              Spacer(modifier = Modifier.height(16.dp))
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth().testTag("auth_status_banner")
              ) {
                Text(
                  text = statusMessage ?: "",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onPrimaryContainer,
                  modifier = Modifier.padding(12.dp),
                  textAlign = TextAlign.Center
                )
              }
            }

            if (errorMessage != null) {
              Spacer(modifier = Modifier.height(16.dp))
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().testTag("auth_error_banner")
              ) {
                Text(
                  text = errorMessage ?: "",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                  modifier = Modifier.padding(12.dp),
                  textAlign = TextAlign.Center
                )
              }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
              onClick = {
                errorMessage = null
                isLoading = true
                onGoogleSignInClicked(
                  context = context,
                  credentialManager = credentialManager,
                  onAuthSuccess = { user ->
                    isLoading = false
                    statusMessage = null
                    onAuthSuccess(user)
                  },
                  onAuthError = { msg ->
                    isLoading = false
                    statusMessage = null
                    errorMessage = msg
                  },
                  scope = scope,
                  onStatusUpdate = { status ->
                    statusMessage = status
                  }
                )
              },
              enabled = !isLoading,
              shape = RoundedCornerShape(14.dp),
              colors =
                ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.primary,
                  contentColor = MaterialTheme.colorScheme.onPrimary
                ),
              modifier =
                Modifier.fillMaxWidth()
                  .height(54.dp)
                  .testTag("google_sign_in_button")
            ) {
              if (isLoading) {
                CircularProgressIndicator(
                  modifier = Modifier.size(22.dp),
                  color = MaterialTheme.colorScheme.onPrimary,
                  strokeWidth = 2.5.dp
                )
              } else {
                Icon(
                  imageVector = Icons.Default.Lock,
                  contentDescription = null,
                  modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                  text =
                    if (selectedPortal == PortalLoginTarget.ADMIN_DASHBOARD) {
                      "Sign In to Admin Dashboard"
                    } else {
                      stringResource(R.string.sign_in_with_google)
                    },
                  style = MaterialTheme.typography.labelLarge
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Zero-Trust Security Footer Note
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(16.dp))
              .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                RoundedCornerShape(16.dp)
              )
              .padding(14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Box(
            modifier =
              Modifier.size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.Security,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.size(18.dp)
            )
          }
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Zero-Trust Firestore Protection",
              style = MaterialTheme.typography.labelLarge
            )
            Text(
              text = "Roles, approval statuses, and balances are strictly enforced by server-side security rules. No Admin SDK keys exist in client code.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }
    }
  }
}

@Composable
private fun PortalTabItem(
  title: String,
  subtitle: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  selected: Boolean,
  onClick: () -> Unit,
  testTag: String,
  modifier: Modifier = Modifier
) {
  val bgColor =
    if (selected) MaterialTheme.colorScheme.surface else Color.Transparent
  val contentColor =
    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

  Surface(
    shape = RoundedCornerShape(12.dp),
    color = bgColor,
    tonalElevation = if (selected) 2.dp else 0.dp,
    modifier =
      modifier
        .clip(RoundedCornerShape(12.dp))
        .clickable(onClick = onClick)
        .testTag(testTag)
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = contentColor,
        modifier = Modifier.size(20.dp)
      )
      Spacer(modifier = Modifier.width(8.dp))
      Column {
        Text(
          text = title,
          style = MaterialTheme.typography.labelLarge,
          color = if (selected) MaterialTheme.colorScheme.onSurface else contentColor,
          fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}
