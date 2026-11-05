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
import androidx.compose.material.icons.filled.VerifiedUser
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
import androidx.compose.ui.graphics.vector.ImageVector
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

internal const val FALLBACK_WEB_CLIENT_ID =
  "54814309263-uralqcp874c3v5nrk9r4ajj9scpt93ug.apps.googleusercontent.com"

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
  var errorMessage by remember { mutableStateOf<String?>(null) }

  val webClientId =
    remember(context) {
      runCatching { context.getString(R.string.default_web_client_id).trim() }
        .getOrDefault("")
        .ifBlank { FALLBACK_WEB_CLIENT_ID }
    }
  val isConfigValid =
    webClientId.isNotEmpty() &&
      webClientId != "YOUR_WEB_CLIENT_ID" &&
      webClientId.endsWith(".apps.googleusercontent.com")

  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(
          Brush.verticalGradient(
            colors =
              listOf(
                Color(0xFF070B14),
                Color(0xFF0E172A),
                Color(0xFF064E3B)
              )
          )
        )
        .windowInsetsPadding(WindowInsets.safeDrawing)
  ) {
    // Decorative ambient glow
    Canvas(modifier = Modifier.fillMaxSize()) {
      drawCircle(
        brush =
          Brush.radialGradient(
            colors = listOf(Color(0xFF10B981).copy(alpha = 0.22f), Color.Transparent),
            center = Offset(size.width * 0.85f, size.height * 0.14f),
            radius = size.width * 0.65f
          ),
        radius = size.width * 0.65f,
        center = Offset(size.width * 0.85f, size.height * 0.14f)
      )
      drawCircle(
        brush =
          Brush.radialGradient(
            colors = listOf(Color(0xFF0EA5E9).copy(alpha = 0.18f), Color.Transparent),
            center = Offset(size.width * 0.15f, size.height * 0.82f),
            radius = size.width * 0.55f
          ),
        radius = size.width * 0.55f,
        center = Offset(size.width * 0.15f, size.height * 0.82f)
      )
    }

    Column(
      modifier =
        Modifier.fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 22.dp, vertical = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        // Brand Logo & Welcome Header
        TaskFlowBrandLogo(size = 76.dp)

        Spacer(modifier = Modifier.height(16.dp))

        Surface(
          shape = RoundedCornerShape(50),
          color = Color(0xFF10B981).copy(alpha = 0.15f),
          modifier =
            Modifier.border(
              1.dp,
              Color(0xFF34D399).copy(alpha = 0.35f),
              RoundedCornerShape(50)
            )
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Icon(
              imageVector = Icons.Default.VerifiedUser,
              contentDescription = null,
              tint = Color(0xFF34D399),
              modifier = Modifier.size(14.dp)
            )
            Text(
              text = "VERIFIED MICRO-TASK & REWARD PLATFORM",
              style = MaterialTheme.typography.labelSmall,
              color = Color(0xFF34D399),
              fontWeight = FontWeight.Bold
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
          text = stringResource(R.string.app_name),
          style = MaterialTheme.typography.displayLarge,
          color = Color.White,
          textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
          text = stringResource(R.string.tagline_subtitle),
          style = MaterialTheme.typography.bodyLarge,
          color = Color(0xFFCBD5E1),
          textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(26.dp))

        // Main Portal Selection & Sign-In Card
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(28.dp),
          colors =
            CardDefaults.cardColors(
              containerColor = Color(0xFF111C2D).copy(alpha = 0.94f)
            ),
          elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Text(
              text = "SELECT ENTRY PORTAL",
              style = MaterialTheme.typography.labelSmall,
              color = Color(0xFF94A3B8),
              fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Two Distinct Entry Point Cards: User Portal vs Admin Portal
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              PortalEntryCard(
                title = "User Portal",
                subtitle = "Complete Tasks & Earn ₹",
                icon = Icons.Default.Person,
                selected = selectedPortal == PortalLoginTarget.USER_APP,
                onClick = { onSelectPortal(PortalLoginTarget.USER_APP) },
                modifier = Modifier.weight(1f).testTag("portal_user_tab")
              )
              PortalEntryCard(
                title = "Admin Portal",
                subtitle = "Manage & Review Proofs",
                icon = Icons.Default.AdminPanelSettings,
                selected = selectedPortal == PortalLoginTarget.ADMIN_DASHBOARD,
                onClick = { onSelectPortal(PortalLoginTarget.ADMIN_DASHBOARD) },
                modifier = Modifier.weight(1f).testTag("portal_admin_tab")
              )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Portal Info Box
            Surface(
              shape = RoundedCornerShape(16.dp),
              color = Color(0xFF1E293B).copy(alpha = 0.7f),
              modifier = Modifier.fillMaxWidth()
            ) {
              Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                if (selectedPortal == PortalLoginTarget.USER_APP) {
                  FeatureHighlightRow(
                    icon = Icons.Default.CheckCircle,
                    title = "Browse & Complete Verified Tasks",
                    subtitle = "Open app or website links and follow clear step-by-step instructions."
                  )
                  FeatureHighlightRow(
                    icon = Icons.Default.VerifiedUser,
                    title = "Submit Written + Screenshot Proof",
                    subtitle = "Upload your completion screenshot from Gallery to earn INR (₹) rewards."
                  )
                } else {
                  FeatureHighlightRow(
                    icon = Icons.Default.AdminPanelSettings,
                    title = "Publish Tasks & Inspect Submissions",
                    subtitle = "Create tasks with app details, links, and required proof guidelines."
                  )
                  FeatureHighlightRow(
                    icon = Icons.Default.Lock,
                    title = "Zero-Trust Admin Authorization",
                    subtitle = "Approve or reject user submissions with atomic INR reward crediting."
                  )
                }
              }
            }

            Spacer(modifier = Modifier.height(20.dp))

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
              Spacer(modifier = Modifier.height(12.dp))
            }

            if (errorMessage != null) {
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                modifier = Modifier.fillMaxWidth().testTag("auth_error_banner")
              ) {
                Text(
                  text = errorMessage ?: "",
                  color = MaterialTheme.colorScheme.onErrorContainer,
                  style = MaterialTheme.typography.bodyMedium,
                  modifier = Modifier.padding(12.dp)
                )
              }
              Spacer(modifier = Modifier.height(12.dp))
            }

            // Modern Google Sign-In Button
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
                  containerColor =
                    if (selectedPortal == PortalLoginTarget.USER_APP) {
                      Color(0xFF10B981)
                    } else {
                      Color(0xFF0EA5E9)
                    },
                  contentColor = Color(0xFF04130D)
                ),
              modifier =
                Modifier.fillMaxWidth()
                  .height(56.dp)
                  .testTag("google_sign_in_button")
            ) {
              if (isLoading) {
                CircularProgressIndicator(
                  modifier = Modifier.size(22.dp),
                  color = Color(0xFF04130D),
                  strokeWidth = 2.5.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                  text = "Signing in with Google…",
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.Bold
                )
              } else {
                Surface(
                  shape = CircleShape,
                  color = Color.White,
                  modifier = Modifier.size(26.dp)
                ) {
                  Box(contentAlignment = Alignment.Center) {
                    Text(
                      text = "G",
                      style = MaterialTheme.typography.labelLarge,
                      color = Color(0xFF0F172A),
                      fontWeight = FontWeight.ExtraBold
                    )
                  }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                  text =
                    if (selectedPortal == PortalLoginTarget.USER_APP) {
                      "Continue to User Portal with Google"
                    } else {
                      "Continue to Admin Portal with Google"
                    },
                  style = MaterialTheme.typography.labelLarge,
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

@Composable
private fun PortalEntryCard(
  title: String,
  subtitle: String,
  icon: ImageVector,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val borderColor =
    if (selected) Color(0xFF34D399) else Color(0xFF334155).copy(alpha = 0.6f)
  val containerColor =
    if (selected) Color(0xFF059669).copy(alpha = 0.22f) else Color(0xFF0B132B).copy(alpha = 0.55f)

  Surface(
    shape = RoundedCornerShape(16.dp),
    color = containerColor,
    modifier =
      modifier
        .clip(RoundedCornerShape(16.dp))
        .border(
          width = if (selected) 1.5.dp else 1.dp,
          color = borderColor,
          shape = RoundedCornerShape(16.dp)
        )
        .clickable(onClick = onClick)
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Box(
        modifier =
          Modifier.size(36.dp)
            .clip(CircleShape)
            .background(
              if (selected) Color(0xFF10B981) else Color(0xFF1E293B)
            ),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = if (selected) Color(0xFF031911) else Color(0xFF94A3B8),
          modifier = Modifier.size(18.dp)
        )
      }
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Color.White else Color(0xFFCBD5E1),
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = subtitle,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) Color(0xFF6EE7B7) else Color(0xFF94A3B8),
        textAlign = TextAlign.Center,
        maxLines = 1
      )
    }
  }
}

@Composable
private fun FeatureHighlightRow(
  icon: ImageVector,
  title: String,
  subtitle: String
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    Box(
      modifier =
        Modifier.size(32.dp)
          .clip(RoundedCornerShape(10.dp))
          .background(Color(0xFF10B981).copy(alpha = 0.16f)),
      contentAlignment = Alignment.Center
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = Color(0xFF34D399),
        modifier = Modifier.size(17.dp)
      )
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFF94A3B8)
      )
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
        "Google Sign-In failed (${e.type}): ${e.localizedMessage ?: "Check SHA-1 & OAuth setup."}"
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
