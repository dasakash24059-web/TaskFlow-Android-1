package com.example

import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.app.DownloadManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Task
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.model.BOOTSTRAPPED_ADMIN_EMAIL
import com.example.data.model.NotificationItem
import com.example.data.model.NotificationRewardType
import com.example.data.model.WatchStats
import com.example.data.model.WithdrawalMethodType
import com.example.data.model.formatCoins
import com.example.data.model.getEffectiveBalanceCoins
import com.example.data.model.mergeTasksWithPreservedSubmissions
import com.example.data.repository.TaskFlowRepository
import com.example.ui.admin.AdminDashboardScreen
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.PortalLoginTarget
import com.example.ui.auth.TaskFlowSplashScreen
import com.example.ui.auth.signOutUser
import com.example.ui.components.FirebaseConfigSecurityDialog
import com.example.ui.components.TaskFlowBrandLogo
import com.example.ui.theme.TaskFlowTheme
import com.example.ui.user.DedicatedDailyCheckInScreen
import com.example.ui.user.GiftCardRedemptionScreen
import com.example.ui.user.OfferWallScreen
import com.example.ui.user.PromoCodeRedemptionScreen
import com.example.ui.user.ScratchCardScreen
import com.example.ui.user.SpinAndEarnScreen
import com.example.ui.user.UpiRedemptionScreen
import com.example.ui.user.UserHomeScreen
import com.example.ui.user.UserProfileScreen
import com.example.ui.user.UserTaskDetailScreen
import com.example.ui.user.UserTrackingScreen
import com.example.ui.user.UserWalletScreen
import com.example.ui.user.WithdrawalConfirmationScreen
import com.example.ui.user.WithdrawalHistoryScreen
import com.example.ui.user.WithdrawalMainScreen
import com.example.ui.viewmodel.AdminTab
import com.example.ui.viewmodel.ProfileSubScreen
import com.example.ui.viewmodel.TaskFlowViewModel
import com.example.ui.viewmodel.UiState
import com.example.ui.viewmodel.UserTab
import com.example.ui.viewmodel.WithdrawalScreenRoute
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class MainActivity : ComponentActivity() {

  private val downloadReceiver = object : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (intent.action == android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
        val downloadId = intent.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (downloadId != -1L) {
          android.util.Log.d("AppUpdateManager", "Download complete broadcast received: $downloadId")
        }
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    androidx.core.content.ContextCompat.registerReceiver(
      this,
      downloadReceiver,
      android.content.IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE),
      androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
    )
    com.example.ui.theme.ThemePreferences.init(applicationContext)
    com.example.util.TaskFlowNotificationHelper.ensureNotificationChannels(applicationContext)
    ensureFirebaseInitialized(applicationContext)
    com.example.util.UnityAdsHelper.initialize(this)
    runCatching { enableEdgeToEdge() }
    setContent {
      val themeMode by com.example.ui.theme.ThemePreferences.currentThemeMode.collectAsStateWithLifecycle()
      TaskFlowTheme(themeMode = themeMode) {
        TaskFlowAppRoot()
      }
    }
  }

  override fun onResume() {
    super.onResume()
    com.example.util.UnityAdsHelper.onAppForeground(this)
  }

  override fun onDestroy() {
    super.onDestroy()
    unregisterReceiver(downloadReceiver)
  }
}

internal fun ensureFirebaseInitialized(context: Context): Boolean {
  val appContext = context.applicationContext
  return try {
    if (FirebaseApp.getApps(appContext).isNotEmpty()) {
      true
    } else {
      val defaultApp = FirebaseApp.initializeApp(appContext)
      if (defaultApp != null) {
        true
      } else {
        // Verified fallback configuration matching project taskflow-8852d and com.aistudio.taskflow.kxmpzq
        val options =
          FirebaseOptions.Builder()
            .setProjectId("taskflow-8852d")
            .setApplicationId("1:54814309263:android:0fbf81074748ad213129fa")
            .setApiKey("AIzaSyDCTK19jN75ZS7gFaJz5aWuYi-Y-2xeH8o")
            .setStorageBucket("taskflow-8852d.firebasestorage.app")
            .setGcmSenderId("54814309263")
            .build()
        FirebaseApp.initializeApp(appContext, options)
        true
      }
    }
  } catch (e: Throwable) {
    Log.e("MainActivity", "Failed to initialize FirebaseApp", e)
    false
  }
}

internal fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
  trySend(currentUser)
  val listener = FirebaseAuth.AuthStateListener { auth -> trySend(auth.currentUser) }
  addAuthStateListener(listener)
  awaitClose { removeAuthStateListener(listener) }
}

@Composable
fun TaskFlowAppRoot(
  auth: FirebaseAuth? = null,
  initialShowSplash: Boolean = true
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val resolvedAuth =
    remember(auth, context) {
      auth
        ?: runCatching {
            ensureFirebaseInitialized(context.applicationContext)
            Firebase.auth
          }
          .getOrNull()
    }

  var showSplash by rememberSaveable { mutableStateOf(initialShowSplash) }
  var preferredPortal by rememberSaveable { mutableStateOf(PortalLoginTarget.USER_APP) }
  var observedUser by remember(resolvedAuth) { mutableStateOf<FirebaseUser?>(resolvedAuth?.currentUser) }
  var manualUserOverride by remember { mutableStateOf<FirebaseUser?>(resolvedAuth?.currentUser) }

  // Keep AuthStateListener active across the entire composition lifetime (including when
  // MainActivity is paused/stopped behind the Google Play Services Account Picker overlay).
  DisposableEffect(resolvedAuth) {
    val authInstance = resolvedAuth
    if (authInstance != null) {
      observedUser = authInstance.currentUser
      val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val latest = firebaseAuth.currentUser
        observedUser = latest
        if (latest != null) {
          manualUserOverride = latest
        }
      }
      authInstance.addAuthStateListener(listener)
      onDispose { authInstance.removeAuthStateListener(listener) }
    } else {
      onDispose {}
    }
  }

  // Re-sync auth state immediately when MainActivity resumes from the Google Account Picker sheet.
  DisposableEffect(lifecycleOwner, resolvedAuth) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) {
        val latest = resolvedAuth?.currentUser
        if (latest != null) {
          observedUser = latest
          manualUserOverride = latest
        }
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  val activeUser = observedUser ?: manualUserOverride

  if (showSplash) {
    TaskFlowSplashScreen(
      onSplashFinished = { showSplash = false }
    )
    return
  }

  if (activeUser == null) {
    AuthScreen(
      selectedPortal = preferredPortal,
      onSelectPortal = { preferredPortal = it },
      onAuthSuccess = { signedInUser ->
        val resolvedUser =
          signedInUser ?: resolvedAuth?.currentUser ?: runCatching { Firebase.auth.currentUser }.getOrNull()
        observedUser = resolvedUser
        manualUserOverride = resolvedUser
      }
    )
  } else {
    val isBootstrappedAdmin =
      activeUser.email.orEmpty().trim().equals(BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true)
    val resolvedInitialPortal =
      if (isBootstrappedAdmin) {
        PortalLoginTarget.ADMIN_DASHBOARD
      } else {
        preferredPortal
      }
    AuthenticatedWorkspace(
      user = activeUser,
      initialPortal = resolvedInitialPortal,
      onSignedOut = {
        observedUser = null
        manualUserOverride = null
      }
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticatedWorkspace(
  user: FirebaseUser,
  initialPortal: PortalLoginTarget,
  onSignedOut: () -> Unit = {}
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val credentialManager = remember(context) { CredentialManager.create(context) }
  val databaseId = remember(context) {
    runCatching { context.getString(R.string.firestore_database_id).trim() }.getOrDefault("(default)")
  }

  val viewModel: TaskFlowViewModel =
    viewModel(
      key = user.uid,
      factory =
        viewModelFactory {
          initializer {
            val app =
              checkNotNull(this[APPLICATION_KEY]) {
                "APPLICATION_KEY missing from CreationExtras"
              }
            ensureFirebaseInitialized(app)
            val dbId =
              runCatching { app.getString(R.string.firestore_database_id).trim() }
                .getOrDefault("(default)")
            val db =
              runCatching {
                  if (dbId.isNotEmpty() && dbId != "(default)") {
                    FirebaseFirestore.getInstance(dbId)
                  } else {
                    FirebaseFirestore.getInstance()
                  }
                }
                .getOrElse { FirebaseFirestore.getInstance() }
            TaskFlowViewModel(
              repository = TaskFlowRepository(db),
              currentUserId = user.uid,
              currentUserEmail = user.email ?: "",
              currentUserDisplayName = user.displayName ?: "",
              initialPortal = initialPortal
            )
          }
        }
    )

  val activePortal by viewModel.activePortal.collectAsStateWithLifecycle()
  val activeUserTab by viewModel.activeUserTab.collectAsStateWithLifecycle()
  val profileSubScreen by viewModel.profileSubScreen.collectAsStateWithLifecycle()
  val activeAdminTab by viewModel.activeAdminTab.collectAsStateWithLifecycle()
  val selectedTaskId by viewModel.selectedTaskId.collectAsStateWithLifecycle()
  val trackingFilter by viewModel.trackingStatusFilter.collectAsStateWithLifecycle()
  val submissionFilter by viewModel.adminSubmissionFilter.collectAsStateWithLifecycle()
  val isAdminAuthorized by viewModel.isAdminAuthorized.collectAsStateWithLifecycle()
  val rawUserProfileState by viewModel.userProfileState.collectAsStateWithLifecycle()
  val googlePhotoUrl = remember(user) { user.photoUrl?.toString().orEmpty() }
  val userProfileState =
    remember(rawUserProfileState, googlePhotoUrl) {
      when (val s = rawUserProfileState) {
        is UiState.Success -> {
          if (s.data != null && s.data.photoUrl.isBlank() && googlePhotoUrl.isNotBlank()) {
            UiState.Success(s.data.copy(photoUrl = googlePhotoUrl))
          } else {
            s
          }
        }
        else -> s
      }
    }
  val currentActivity =
    remember(context) {
      (context as? android.app.Activity)
        ?: run {
          var cur = context
          while (cur is android.content.ContextWrapper && cur !is android.app.Activity) {
            cur = cur.baseContext
          }
          cur as? android.app.Activity
        }
    }
  val userTasksState by viewModel.userTasksState.collectAsStateWithLifecycle()
  val userSubmissionsState by viewModel.userSubmissionsState.collectAsStateWithLifecycle()
  val userWithdrawalRequestsState by viewModel.userWithdrawalRequestsState.collectAsStateWithLifecycle()
  val adminTasksState by viewModel.adminTasksState.collectAsStateWithLifecycle()
  val appsState by viewModel.appsState.collectAsStateWithLifecycle()
  val adminSubmissionsState by viewModel.adminSubmissionsState.collectAsStateWithLifecycle()
  val adminWithdrawalRequestsState by viewModel.adminWithdrawalRequestsState.collectAsStateWithLifecycle()
  val registeredUsersState by viewModel.registeredUsersState.collectAsStateWithLifecycle()
  val userTransactionsState by viewModel.userTransactionsState.collectAsStateWithLifecycle()
  val leaderboardUsersState by viewModel.leaderboardUsersState.collectAsStateWithLifecycle()
  val dailyCheckInState by viewModel.dailyCheckInState.collectAsStateWithLifecycle()
  val watchStats by viewModel.watchStats.collectAsStateWithLifecycle()
  val watchEarnUiState by viewModel.watchEarnUiState.collectAsStateWithLifecycle()
  val spinEarnUiState by viewModel.spinEarnUiState.collectAsStateWithLifecycle()
  val bannerMessage by viewModel.bannerMessage.collectAsStateWithLifecycle()
  val submissionError by viewModel.submissionError.collectAsStateWithLifecycle()
  val withdrawalValidationError by viewModel.withdrawalValidationError.collectAsStateWithLifecycle()
  val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
  val uploadProgress by viewModel.uploadProgress.collectAsStateWithLifecycle()
  val showSecuritySheet by viewModel.showSecuritySheet.collectAsStateWithLifecycle()
  val withdrawalRoute by viewModel.withdrawalRoute.collectAsStateWithLifecycle()
  val lastSubmittedWithdrawal by viewModel.lastSubmittedWithdrawal.collectAsStateWithLifecycle()
  val withdrawalAvailabilityConfig by viewModel.withdrawalAvailabilityState.collectAsStateWithLifecycle()
  val adminNotifications by viewModel.adminNotificationsState.collectAsStateWithLifecycle()
  val isDailyCheckInScreenVisible by viewModel.isDailyCheckInScreenVisible.collectAsStateWithLifecycle()
  val isSpinScreenVisible by viewModel.isSpinScreenVisible.collectAsStateWithLifecycle()
  val isOfferWallScreenVisible by viewModel.isOfferWallScreenVisible.collectAsStateWithLifecycle()
  val campaignsState by viewModel.campaignsState.collectAsStateWithLifecycle()
  val adminTransactionsState by viewModel.adminTransactionsState.collectAsStateWithLifecycle()
  val adminActivityLogsState by viewModel.adminActivityLogsState.collectAsStateWithLifecycle()
  val selectedTaskProgressState by viewModel.selectedTaskProgressState.collectAsStateWithLifecycle()
  val userNotifications by viewModel.userNotificationsState.collectAsStateWithLifecycle()
  val showNotificationDialog by viewModel.showNotificationDialog.collectAsStateWithLifecycle()
  val showSupportTicketDialog by viewModel.showSupportTicketDialog.collectAsStateWithLifecycle()
  val telegramTasks by viewModel.telegramTasksState.collectAsStateWithLifecycle()
  val telegramClaims by viewModel.telegramClaimsState.collectAsStateWithLifecycle()
  val telegramTaskUiStates by viewModel.telegramTaskUiStates.collectAsStateWithLifecycle()
  val nextTelegramTask by viewModel.nextTelegramTaskState.collectAsStateWithLifecycle()
  val promoCodes by viewModel.promoCodesState.collectAsStateWithLifecycle()
  val manualBonuses by viewModel.manualBonusesState.collectAsStateWithLifecycle()
  val promoRedemptionUiState by viewModel.promoRedemptionUiState.collectAsStateWithLifecycle()
  val promoAdminFeedback by viewModel.promoAdminFeedback.collectAsStateWithLifecycle()
  val isPromoCodeScreenVisible by viewModel.isPromoCodeScreenVisible.collectAsStateWithLifecycle()
  val isScratchCardScreenVisible by viewModel.isScratchCardScreenVisible.collectAsStateWithLifecycle()
  val scratchCardUiState by viewModel.scratchCardUiState.collectAsStateWithLifecycle()
  val leaderboardTransactions by viewModel.leaderboardCreditedTransactionsState.collectAsStateWithLifecycle()
  val userSupportTickets by viewModel.userSupportTicketsState.collectAsStateWithLifecycle()
  val adminSupportTickets by viewModel.adminSupportTicketsState.collectAsStateWithLifecycle()
  val isOpeningCampaignLink by viewModel.isOpeningCampaignLink.collectAsStateWithLifecycle()

  BackHandler(
    enabled =
      selectedTaskId != null ||
        (activePortal == PortalLoginTarget.USER_APP &&
          activeUserTab == UserTab.HOME &&
          (isScratchCardScreenVisible || isPromoCodeScreenVisible || isDailyCheckInScreenVisible || isSpinScreenVisible || isOfferWallScreenVisible)) ||
        (activePortal == PortalLoginTarget.USER_APP &&
          activeUserTab == UserTab.PROFILE &&
          profileSubScreen != ProfileSubScreen.MAIN) ||
        (activePortal == PortalLoginTarget.USER_APP && activeUserTab != UserTab.HOME) ||
        (activePortal == PortalLoginTarget.ADMIN_DASHBOARD && activeAdminTab != AdminTab.HOME)
  ) {
    when {
      selectedTaskId != null -> viewModel.selectTask(null)
      activePortal == PortalLoginTarget.USER_APP &&
        activeUserTab == UserTab.HOME &&
        isScratchCardScreenVisible ->
        viewModel.setScratchCardScreenVisible(false)
      activePortal == PortalLoginTarget.USER_APP &&
        activeUserTab == UserTab.HOME &&
        isPromoCodeScreenVisible ->
        viewModel.setPromoCodeScreenVisible(false)
      activePortal == PortalLoginTarget.USER_APP &&
        activeUserTab == UserTab.HOME &&
        isDailyCheckInScreenVisible ->
        viewModel.setDailyCheckInScreenVisible(false)
      activePortal == PortalLoginTarget.USER_APP &&
        activeUserTab == UserTab.HOME &&
        isSpinScreenVisible ->
        viewModel.setSpinScreenVisible(false)
      activePortal == PortalLoginTarget.USER_APP &&
        activeUserTab == UserTab.HOME &&
        isOfferWallScreenVisible ->
        viewModel.setOfferWallScreenVisible(false)
      activePortal == PortalLoginTarget.USER_APP &&
        activeUserTab == UserTab.PROFILE &&
        profileSubScreen != ProfileSubScreen.MAIN ->
        viewModel.setProfileSubScreen(ProfileSubScreen.MAIN)
      activePortal == PortalLoginTarget.USER_APP && activeUserTab != UserTab.HOME ->
        viewModel.setUserTab(UserTab.HOME)
      activePortal == PortalLoginTarget.ADMIN_DASHBOARD && activeAdminTab != AdminTab.HOME ->
        viewModel.setAdminTab(AdminTab.HOME)
    }
  }

  if (showSecuritySheet && isAdminAuthorized) {
    FirebaseConfigSecurityDialog(
      databaseId = databaseId,
      onDismiss = { viewModel.setShowSecuritySheet(false) }
    )
  }

  val showUpdateDialog by viewModel.showUpdateDialog.collectAsStateWithLifecycle()
  val updateInfo by viewModel.appUpdateInfo.collectAsStateWithLifecycle()

  val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
  androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
      if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        viewModel.verifyPostInstallation(context)
        viewModel.checkForAppUpdates(context, forceCheck = false)
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  val downloadProgress by viewModel.updateDownloadProgress.collectAsStateWithLifecycle()

  if (showUpdateDialog && updateInfo != null) {
    com.example.ui.components.AppUpdateDialog(
      versionInfo = updateInfo!!,
      downloadProgress = downloadProgress,
      onUpdateNow = {
        viewModel.downloadAndInstallUpdate(context)
      },
      onInstallNow = {
        viewModel.triggerInstalledApkInstall(context)
      },
      onOpenSettings = {
        viewModel.requestUnknownAppSourcesPermission(context)
      },
      onReopenApp = {
        com.example.util.AppUpdateManager.restartApp(context)
      },
      onDismiss = {
        viewModel.dismissUpdateDialog(context)
      }
    )
  }

  Scaffold(
    contentWindowInsets = WindowInsets.safeDrawing,
    topBar = {
      TopAppBar(
        title = {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            TaskFlowBrandLogo(size = 36.dp)
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
              Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
              )
              Text(
                text = "Complete • Earn • Grow",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
              )
            }
          }
        },
        actions = {
          // Single Compact Header TF Token Balance Badge beside Notification Bell (User Mode)
          if (activePortal == PortalLoginTarget.USER_APP || !isAdminAuthorized) {
            val headerBalanceCoins =
              (userProfileState as? UiState.Success)?.data?.getEffectiveBalanceCoins() ?: 0L
            com.example.ui.components.TFBalanceDisplay(
              balanceCoins = headerBalanceCoins,
              tokenSize = 22.dp,
              amountTestTag = "home_header_token_amount",
              onClick = {
                viewModel.closeWithdrawalFlow()
                viewModel.setUserTab(UserTab.WALLET)
              },
              modifier = Modifier.testTag("home_header_token_badge")
            )
          }

          val unreadCount = userNotifications.count { !it.isRead }
          IconButton(
            onClick = { viewModel.setNotificationDialogVisible(true) },
            modifier = Modifier.testTag("top_bar_notifications_button")
          ) {
            BadgedBox(
              badge = {
                if (unreadCount > 0) {
                  Badge { Text(unreadCount.toString()) }
                }
              }
            ) {
              Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = "Notifications",
                tint = MaterialTheme.colorScheme.onSurface
              )
            }
          }

          // Only show Admin Mode Switcher & Security Info to verified Admin accounts
          if (isAdminAuthorized) {
            Surface(
              shape = RoundedCornerShape(50),
              color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
              border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
              modifier =
                Modifier.clip(RoundedCornerShape(50))
                  .clickable {
                    val next =
                      if (activePortal == PortalLoginTarget.USER_APP) {
                        PortalLoginTarget.ADMIN_DASHBOARD
                      } else {
                        PortalLoginTarget.USER_APP
                      }
                    viewModel.setPortal(next)
                  }
                  .testTag("portal_mode_toggle_pill")
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
              ) {
                Icon(
                  imageVector =
                    if (activePortal == PortalLoginTarget.ADMIN_DASHBOARD) {
                      Icons.Default.AdminPanelSettings
                    } else {
                      Icons.Default.Person
                    },
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(15.dp)
                )
                Text(
                  text =
                    if (activePortal == PortalLoginTarget.ADMIN_DASHBOARD) {
                      "Admin Mode"
                    } else {
                      "User Mode"
                    },
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.primary,
                  maxLines = 1,
                  softWrap = false
                )
              }
            }

            IconButton(
              onClick = { viewModel.setShowSecuritySheet(true) },
              modifier = Modifier.testTag("top_bar_security_info_button")
            ) {
              Icon(
                imageVector = Icons.Default.Security,
                contentDescription = stringResource(R.string.security_info_title),
                tint = MaterialTheme.colorScheme.primary
              )
            }
          }
        },
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
          )
      )
    },
    bottomBar = {
      if (activePortal == PortalLoginTarget.USER_APP || !isAdminAuthorized) {
        NavigationBar(
          containerColor = MaterialTheme.colorScheme.surface,
          tonalElevation = 2.dp
        ) {
          NavigationBarItem(
            selected = activeUserTab == UserTab.HOME,
            onClick = { viewModel.setUserTab(UserTab.HOME) },
            icon = { Icon(Icons.Default.Home, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_home), maxLines = 1, softWrap = false) },
            modifier = Modifier.testTag("nav_user_home")
          )
          NavigationBarItem(
            selected = activeUserTab == UserTab.TRACKING,
            onClick = { viewModel.setUserTab(UserTab.TRACKING) },
            icon = { Icon(Icons.AutoMirrored.Filled.FactCheck, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_tasks), maxLines = 1, softWrap = false) },
            modifier = Modifier.testTag("nav_user_tracking")
          )
          NavigationBarItem(
            selected = activeUserTab == UserTab.WALLET,
            onClick = {
              viewModel.closeWithdrawalFlow()
              viewModel.setUserTab(UserTab.WALLET)
            },
            icon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_wallet), maxLines = 1, softWrap = false) },
            modifier = Modifier.testTag("nav_user_wallet")
          )
          NavigationBarItem(
            selected = activeUserTab == UserTab.PROFILE,
            onClick = {
              viewModel.setUserTab(UserTab.PROFILE)
              viewModel.setProfileSubScreen(ProfileSubScreen.MAIN)
            },
            icon = { Icon(Icons.Default.Person, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_profile), maxLines = 1, softWrap = false) },
            modifier = Modifier.testTag("nav_user_profile")
          )
        }
      }
    }
  ) { innerPadding ->
    Box(
      modifier =
        Modifier.fillMaxSize()
          .padding(innerPadding)
          .background(MaterialTheme.colorScheme.background),
      contentAlignment = Alignment.TopCenter
    ) {
      Column(modifier = Modifier.fillMaxSize().widthIn(max = 640.dp)) {
        // Dismissible Status / Feedback Banner
        AnimatedVisibility(visible = bannerMessage != null) {
          bannerMessage?.let { msg ->
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = MaterialTheme.colorScheme.primaryContainer,
              modifier =
                Modifier.fillMaxWidth()
                  .padding(horizontal = 18.dp, vertical = 6.dp)
                  .clickable { viewModel.clearBannerMessage() }
                  .testTag("feedback_banner")
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.CheckCircle,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onPrimaryContainer,
                  modifier = Modifier.size(18.dp)
                )
                Text(
                  text = msg,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onPrimaryContainer,
                  modifier = Modifier.weight(1f)
                )
              }
            }
          }
        }

        if (activePortal == PortalLoginTarget.USER_APP || !isAdminAuthorized) {
          val rawTasks = (userTasksState as? UiState.Success)?.data ?: emptyList()
          val userSubmissions =
            (userSubmissionsState as? UiState.Success)?.data ?: emptyList()
          val mergedTasks =
            remember(rawTasks, userSubmissions, user.uid) {
              mergeTasksWithPreservedSubmissions(rawTasks, userSubmissions, user.uid)
            }
          val selectedTask = mergedTasks.firstOrNull { it.id == selectedTaskId }

          if (selectedTask != null) {
            val currentApprovalStatus =
              (userProfileState as? UiState.Success)?.data?.approvalStatus ?: "approved"
            UserTaskDetailScreen(
              task = selectedTask,
              isBusy = isBusy,
              onBack = { viewModel.selectTask(null) },
              onSubmitProof = { proof, imageUri ->
                viewModel.submitProof(selectedTask, proof, imageUri)
              },
              userApprovalStatus = currentApprovalStatus,
              currentUserId = user.uid,
              uploadProgress = uploadProgress,
              submissionError = submissionError,
              taskProgress = selectedTaskProgressState,
              onConfirmInstallation = { viewModel.confirmTaskInstallation(selectedTask) },
              onToggleStepCompleted = { stepNum, totalSteps ->
                viewModel.toggleTaskStepCompleted(selectedTask, stepNum, totalSteps)
              },
              isLaunchingTaskLink = isOpeningCampaignLink,
              onOpenTaskUrl = { targetUrl, onReady ->
                viewModel.openCampaignTaskLink(
                  task = selectedTask,
                  overrideUrl = targetUrl,
                  onTrackingUrlReady = onReady
                )
              }
            )
          } else {
            when (activeUserTab) {
              UserTab.HOME -> {
                if (isScratchCardScreenVisible) {
                  ScratchCardScreen(
                    uiState = scratchCardUiState,
                    onBack = { viewModel.setScratchCardScreenVisible(false) },
                    onWatchAd = { act -> viewModel.watchAdForScratchCard(act) },
                    onScratchRevealed = { viewModel.onScratchRevealed() },
                    onClaimReward = { viewModel.claimScratchCardReward() },
                    onDismissCongratulations = { viewModel.dismissScratchCongratulations() },
                    onClearFeedback = { viewModel.clearScratchFeedback() },
                    onRetryPrepare = { viewModel.prepareScratchCard() }
                  )
                } else if (isPromoCodeScreenVisible) {
                  PromoCodeRedemptionScreen(
                    uiState = promoRedemptionUiState,
                    onRedeemCode = { rawCode -> viewModel.redeemPromoCode(rawCode) },
                    onClearFeedback = { viewModel.clearPromoRedemptionFeedback() },
                    onBack = { viewModel.setPromoCodeScreenVisible(false) }
                  )
                } else if (isDailyCheckInScreenVisible) {
                  DedicatedDailyCheckInScreen(
                    dailyCheckInState = dailyCheckInState,
                    approvedEarningsCents = viewModel.getApprovedTaskEarningsCents(),
                    onClaimDay = { day ->
                      val activity =
                        (context as? android.app.Activity)
                          ?: run {
                            var cur = context
                            while (cur is android.content.ContextWrapper && cur !is android.app.Activity) {
                              cur = cur.baseContext
                            }
                            cur as? android.app.Activity
                          }
                      viewModel.claimDailyCheckIn(day = day, activity = activity)
                    },
                    onBack = { viewModel.setDailyCheckInScreenVisible(false) },
                    isBusy = isBusy,
                    onOpenPromoCodeScreen = {
                      viewModel.setPromoCodeScreenVisible(true)
                    }
                  )
                } else if (isSpinScreenVisible) {
                  SpinAndEarnScreen(
                    onBack = { viewModel.setSpinScreenVisible(false) },
                    spinState = spinEarnUiState,
                    onStartSpin = { onOutcomeReady ->
                      viewModel.startSpinWithBackendOutcome(onOutcomeReady)
                    },
                    onWheelAnimationFinished = {
                      viewModel.onSpinWheelAnimationFinished()
                    },
                    onWatchExtraSpinVideo = {
                      if (currentActivity != null) {
                        viewModel.watchAdForExtraSpin(currentActivity)
                      }
                    },
                    onClaimSpinRewardWithAd = {
                      viewModel.claimSpinRewardWithAd(currentActivity)
                    },
                    onClaimReward = { coins ->
                      viewModel.claimSpinReward(coins)
                    },
                    onShowRewardedAd = { onAdCompleted ->
                      if (currentActivity != null) {
                        com.example.util.UnityAdsHelper.showRewardedAd(
                          activity = currentActivity,
                          onComplete = { onAdCompleted() },
                          onFailed = { onAdCompleted() }
                        )
                      } else {
                        onAdCompleted()
                      }
                    },
                    onShowInterstitialAd = { onAdCompleted ->
                      if (currentActivity != null) {
                        com.example.util.UnityAdsHelper.loadAndShowInterstitialAd(
                          activity = currentActivity,
                          onClosed = { onAdCompleted() },
                          onFailed = { onAdCompleted() }
                        )
                      } else {
                        onAdCompleted()
                      }
                    }
                  )
                } else if (isOfferWallScreenVisible) {
                  OfferWallScreen(
                    tasks = mergedTasks,
                    onBack = { viewModel.setOfferWallScreenVisible(false) },
                    onSelectOffer = { taskId ->
                      viewModel.setOfferWallScreenVisible(false)
                      viewModel.selectTask(taskId)
                    }
                  )
                } else {
                  UserHomeScreen(
                    profileState = userProfileState,
                    tasksState = userTasksState,
                    isAdminAuthorized = isAdminAuthorized,
                    onSelectTask = { viewModel.selectTask(it) },
                    onNavigateToTracking = { filter ->
                      viewModel.setTrackingStatusFilter(filter)
                      viewModel.setUserTab(UserTab.TRACKING)
                    },
                    onSwitchToAdmin = {
                      viewModel.setPortal(PortalLoginTarget.ADMIN_DASHBOARD)
                    },
                    submissionsState = userSubmissionsState,
                    dailyCheckInState = dailyCheckInState,
                    onClaimDailyCheckIn = {},
                    onNavigateToDailyCheckIn = {
                      viewModel.setDailyCheckInScreenVisible(true)
                      com.example.util.UnityAdsHelper.preloadRewardedAd(context, force = false)
                      if (currentActivity != null) {
                        com.example.util.UnityAdsHelper.loadAndShowInterstitialAd(currentActivity)
                      }
                    },
                    transactionsState = userTransactionsState,
                    leaderboardState = leaderboardUsersState,
                    onNavigateToWallet = { openWithdraw ->
                      if (openWithdraw) {
                        viewModel.openWithdrawalMain()
                      } else {
                        viewModel.closeWithdrawalFlow()
                        viewModel.setUserTab(UserTab.WALLET)
                      }
                    },
                    watchStats = watchStats,
                    onClaimWatchEarn = {
                      viewModel.claimWatchEarnReward(activity = currentActivity)
                    },
                    watchEarnUiState = watchEarnUiState,
                    onStartWatchVideo = {
                      if (currentActivity != null) {
                        viewModel.startWatchEarnVideo(currentActivity)
                      }
                    },
                    onClaimVerifiedWatchReward = {
                      viewModel.claimVerifiedWatchEarnReward(activity = currentActivity)
                    },
                    onOpenWatchDialog = {
                      viewModel.prepareWatchEarnDialog(context)
                    },
                    onShowInterstitial = {
                      if (currentActivity != null) {
                        com.example.util.UnityAdsHelper.loadAndShowInterstitialAd(currentActivity)
                      }
                    },
                    onNavigateToSpin = {
                      viewModel.setSpinScreenVisible(true)
                      com.example.util.UnityAdsHelper.preloadRewardedAd(context, force = false)
                    },
                    onNavigateToOfferWall = {
                      viewModel.setOfferWallScreenVisible(true)
                      if (currentActivity != null) {
                        com.example.util.UnityAdsHelper.loadAndShowInterstitialAd(currentActivity)
                      }
                    },
                    onNavigateToScratchCard = {
                      viewModel.setScratchCardScreenVisible(true)
                    },
                    onNavigateToPromoCode = {
                      viewModel.setPromoCodeScreenVisible(true)
                    },
                    leaderboardTransactions = leaderboardTransactions
                  )
                }
              }
              UserTab.TRACKING -> {
                val activeTelegramTask = nextTelegramTask
                val activeTelegramState = activeTelegramTask?.let { telegramTaskUiStates[it.effectiveTaskId] }
                UserTrackingScreen(
                  tasksState = userTasksState,
                  selectedFilter = trackingFilter,
                  onSelectFilter = { viewModel.setTrackingStatusFilter(it) },
                  onSelectTask = { viewModel.selectTask(it) },
                  currentUserId = user.uid,
                  nextTelegramTask = activeTelegramTask,
                  telegramTaskState = activeTelegramState,
                  isTelegramBusy = isBusy,
                  onJoinTelegramTask = { tgTask ->
                    viewModel.recordTelegramTaskJoinClick(tgTask.effectiveTaskId)
                    runCatching {
                      val rawUrl = tgTask.channelUrl.trim()
                      val normalizedUrl =
                        if (rawUrl.startsWith("http://", ignoreCase = true) ||
                          rawUrl.startsWith("https://", ignoreCase = true) ||
                          rawUrl.startsWith("tg://", ignoreCase = true)
                        ) {
                          rawUrl
                        } else if (rawUrl.startsWith("@")) {
                          "https://t.me/${rawUrl.removePrefix("@")}"
                        } else {
                          "https://$rawUrl"
                        }
                      val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(normalizedUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                      }
                      context.startActivity(intent)
                    }
                  },
                  onVerifyTelegramTask = { tgTask ->
                    viewModel.verifyTelegramTask(
                      taskId = tgTask.effectiveTaskId,
                      onOpenBotLink = { botLinkUrl ->
                        runCatching {
                          val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(botLinkUrl)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                          }
                          context.startActivity(intent)
                        }
                      }
                    )
                  },
                  onClaimTelegramTask = { tgTask ->
                    viewModel.claimTelegramTask(tgTask.effectiveTaskId)
                  }
                )
              }
              UserTab.WALLET -> {
                val activeProfile = (userProfileState as? UiState.Success)?.data
                val totalBalanceCoins = activeProfile?.getEffectiveBalanceCoins() ?: 0L
                val userWithdrawals =
                  (userWithdrawalRequestsState as? UiState.Success)?.data ?: emptyList()

                when (withdrawalRoute) {
                  WithdrawalScreenRoute.MAIN -> {
                    WithdrawalMainScreen(
                      totalBalanceCoins = totalBalanceCoins,
                      availability = withdrawalAvailabilityConfig,
                      onSelectRoute = { route -> viewModel.navigateWithdrawal(route) },
                      onOpenHistory = { viewModel.openWithdrawalHistory() },
                      onBack = { viewModel.closeWithdrawalFlow() }
                    )
                  }
                  WithdrawalScreenRoute.UPI -> {
                    UpiRedemptionScreen(
                      totalBalanceCoins = totalBalanceCoins,
                      availability = withdrawalAvailabilityConfig,
                      isBusy = isBusy,
                      validationError = withdrawalValidationError,
                      onClearError = { viewModel.clearWithdrawalValidationError() },
                      onSubmit = { rupees, upiId ->
                        viewModel.submitWithdrawalRedemption(
                          method = WithdrawalMethodType.UPI,
                          amountRupees = rupees,
                          upiId = upiId
                        )
                      },
                      onBack = { viewModel.navigateWithdrawal(WithdrawalScreenRoute.MAIN) }
                    )
                  }
                  WithdrawalScreenRoute.AMAZON_GIFT_CARD -> {
                    GiftCardRedemptionScreen(
                      method = WithdrawalMethodType.AMAZON_GIFT_CARD,
                      totalBalanceCoins = totalBalanceCoins,
                      availability = withdrawalAvailabilityConfig,
                      isBusy = isBusy,
                      validationError = withdrawalValidationError,
                      onClearError = { viewModel.clearWithdrawalValidationError() },
                      onSubmit = { rupees ->
                        viewModel.submitWithdrawalRedemption(
                          method = WithdrawalMethodType.AMAZON_GIFT_CARD,
                          amountRupees = rupees
                        )
                      },
                      onBack = { viewModel.navigateWithdrawal(WithdrawalScreenRoute.MAIN) }
                    )
                  }
                  WithdrawalScreenRoute.GOOGLE_PLAY_GIFT_CARD -> {
                    GiftCardRedemptionScreen(
                      method = WithdrawalMethodType.GOOGLE_PLAY_GIFT_CARD,
                      totalBalanceCoins = totalBalanceCoins,
                      availability = withdrawalAvailabilityConfig,
                      isBusy = isBusy,
                      validationError = withdrawalValidationError,
                      onClearError = { viewModel.clearWithdrawalValidationError() },
                      onSubmit = { rupees ->
                        viewModel.submitWithdrawalRedemption(
                          method = WithdrawalMethodType.GOOGLE_PLAY_GIFT_CARD,
                          amountRupees = rupees
                        )
                      },
                      onBack = { viewModel.navigateWithdrawal(WithdrawalScreenRoute.MAIN) }
                    )
                  }
                  WithdrawalScreenRoute.FLIPKART_GIFT_CARD -> {
                    GiftCardRedemptionScreen(
                      method = WithdrawalMethodType.FLIPKART_GIFT_CARD,
                      totalBalanceCoins = totalBalanceCoins,
                      availability = withdrawalAvailabilityConfig,
                      isBusy = isBusy,
                      validationError = withdrawalValidationError,
                      onClearError = { viewModel.clearWithdrawalValidationError() },
                      onSubmit = { rupees ->
                        viewModel.submitWithdrawalRedemption(
                          method = WithdrawalMethodType.FLIPKART_GIFT_CARD,
                          amountRupees = rupees
                        )
                      },
                      onBack = { viewModel.navigateWithdrawal(WithdrawalScreenRoute.MAIN) }
                    )
                  }
                  WithdrawalScreenRoute.CONFIRMATION -> {
                    WithdrawalConfirmationScreen(
                      request = lastSubmittedWithdrawal,
                      onViewHistory = { viewModel.openWithdrawalHistory() },
                      onDone = { viewModel.closeWithdrawalFlow() }
                    )
                  }
                  WithdrawalScreenRoute.HISTORY -> {
                    WithdrawalHistoryScreen(
                      requests = userWithdrawals,
                      onBack = { viewModel.closeWithdrawalFlow() }
                    )
                  }
                  WithdrawalScreenRoute.NONE -> {
                    UserWalletScreen(
                      profileState = userProfileState,
                      tasksState = userTasksState,
                      withdrawalRequestsState = userWithdrawalRequestsState,
                      currentUserId = user.uid,
                      submissionsState = userSubmissionsState,
                      transactionsState = userTransactionsState,
                      onOpenWithdrawalMain = { viewModel.openWithdrawalMain() },
                      onOpenWithdrawalHistory = { viewModel.openWithdrawalHistory() }
                    )
                  }
                }
              }
              UserTab.PROFILE -> {
                UserProfileScreen(
                  profileState = userProfileState,
                  tasksState = userTasksState,
                  userEmail = user.email ?: "",
                  userUid = user.uid,
                  onUpdateDisplayName = { viewModel.updateProfileDisplayName(it) },
                  onOpenSecurityInfo = { viewModel.setShowSecuritySheet(true) },
                  onSelectTask = { viewModel.selectTask(it) },
                  onSignOut = {
                    signOutUser(
                      context = context,
                      credentialManager = credentialManager,
                      onSignOutComplete = onSignedOut,
                      scope = scope
                    )
                  },
                  withdrawalRequestsState = userWithdrawalRequestsState,
                  submissionsState = userSubmissionsState,
                  activeSubScreen = profileSubScreen,
                  onNavigateSubScreen = { viewModel.setProfileSubScreen(it) },
                  onNavigateScreen = { target ->
                    when (target) {
                      "tasks", "tracking" -> viewModel.setUserTab(UserTab.TRACKING)
                      "wallet" -> viewModel.setUserTab(UserTab.WALLET)
                      "profile" -> viewModel.setUserTab(UserTab.PROFILE)
                      "settings" -> {
                        viewModel.setUserTab(UserTab.PROFILE)
                        viewModel.setProfileSubScreen(ProfileSubScreen.SETTINGS)
                      }
                      "support" -> {
                        viewModel.setUserTab(UserTab.PROFILE)
                        viewModel.setProfileSubScreen(ProfileSubScreen.SUPPORT)
                      }
                    }
                  },
                  onSubmitSupportTicket = { subject, message, category, onResult ->
                    viewModel.createSupportTicket(
                      subject = subject,
                      message = message,
                      category = category,
                      onResult = onResult
                    )
                  },
                  supportTickets = userSupportTickets,
                  onReplySupportTicket = { ticketId, replyMessage, onResult ->
                    viewModel.userReplySupportTicket(
                      ticketId = ticketId,
                      replyMessage = replyMessage,
                      onResult = onResult
                    )
                  }
                )
              }
            }
          }
        } else {
          AdminDashboardScreen(
            isAdminAuthorized = isAdminAuthorized,
            currentUserId = user.uid,
            currentUserEmail = user.email ?: "",
            currentUserDisplayName = user.displayName ?: "Admin",
            activeTab = activeAdminTab,
            adminTasksState = adminTasksState,
            registeredUsersState = registeredUsersState,
            submissionFilter = submissionFilter,
            onSelectSubmissionFilter = { viewModel.setAdminSubmissionFilter(it) },
            onCreateTask = { title, desc, cat, rewardStr, assignee, instructions, taskLink ->
              viewModel.createTask(
                title = title,
                description = desc,
                category = cat,
                rewardAmountCoins = rewardStr,
                assignedUser = assignee,
                instructions = instructions,
                taskLink = taskLink
              )
            },
            onUpdateTask = { taskId, title, desc, cat, rewardStr, assignee, instructions, taskLink ->
              viewModel.updateTask(
                taskId = taskId,
                title = title,
                description = desc,
                category = cat,
                rewardAmountCoins = rewardStr,
                assignedUser = assignee,
                instructions = instructions,
                taskLink = taskLink
              )
            },
            onDeleteTask = { viewModel.deleteTask(it) },
            onReviewSubmission = { task, approve, feedback ->
              viewModel.reviewSubmission(task, approve, feedback)
            },
            onToggleUserStatus = { viewModel.toggleUserApprovalStatus(it) },
            onUpdateUserStatus = { user, status -> viewModel.updateUserApprovalStatus(user, status) },
            onUpdateUserName = { user, newName -> viewModel.updateUserDisplayNameByAdmin(user, newName) },
            adminSubmissionsState = adminSubmissionsState,
            adminWithdrawalRequestsState = adminWithdrawalRequestsState,
            onToggleTaskActive = { task, isActive ->
              viewModel.setTaskActive(task.id, task.title, isActive)
            },
            onCreateTaskExtended = {
                title,
                desc,
                cat,
                rewardStr,
                assignee,
                instructions,
                taskLink,
                appName,
                appIconUrl,
                requiredProof ->
              viewModel.createTask(
                title = title,
                description = desc,
                category = cat,
                rewardAmountCoins = rewardStr,
                assignedUser = assignee,
                instructions = instructions,
                taskLink = taskLink,
                appName = appName,
                appIconUrl = appIconUrl,
                requiredProof = requiredProof
              )
            },
            onUpdateTaskExtended = {
                taskId,
                title,
                desc,
                cat,
                rewardStr,
                assignee,
                instructions,
                taskLink,
                appName,
                appIconUrl,
                requiredProof ->
              viewModel.updateTask(
                taskId = taskId,
                title = title,
                description = desc,
                category = cat,
                rewardAmountCoins = rewardStr,
                assignedUser = assignee,
                instructions = instructions,
                taskLink = taskLink,
                appName = appName,
                appIconUrl = appIconUrl,
                requiredProof = requiredProof
              )
            },
            campaignsState = campaignsState,
            onCreateCampaign = { title, client, desc, budget, target, cat, reward ->
              viewModel.createCampaign(title, client, desc, budget, target, cat, reward)
            },
            onUpdateCampaignStatus = { campaignId, status ->
              viewModel.updateCampaignStatus(campaignId, status)
            },
            appsState = appsState,
            onCreateApp = { name, logo -> viewModel.createApp(name, logo) },
            onUpdateApp = { id, name, logo, archived -> viewModel.updateApp(id, name, logo, archived) },
            onSelectAdminTab = { viewModel.setAdminTab(it) },
            adminTransactionsState = adminTransactionsState,
            adminActivityLogsState = UiState.Success(adminActivityLogsState),
            onCreateTaskFull = { payload ->
              viewModel.createTask(
                title = payload.title,
                description = payload.description,
                category = payload.category,
                rewardAmountCoins = payload.rewardCoinsStr,
                assignedUser = payload.assignee,
                instructions = payload.instructions,
                taskLink = payload.taskLink,
                appName = payload.appName,
                appIconUrl = payload.appIconUrl,
                requiredProof = payload.requiredProof,
                steps = payload.steps,
                referenceImages = payload.referenceImages,
                installationInstructions = payload.installationInstructions,
                completionDurationMinutes = payload.completionDurationMinutes,
                targetSubmissions = payload.targetSubmissions,
                startDate = payload.startDate,
                endDate = payload.endDate,
                eligibilityRules = payload.eligibilityRules,
                campaignId = payload.campaignId,
                isActive = payload.isActive
              )
            },
            onUpdateTaskFull = { taskId, payload ->
              viewModel.updateTask(
                taskId = taskId,
                title = payload.title,
                description = payload.description,
                category = payload.category,
                rewardAmountCoins = payload.rewardCoinsStr,
                assignedUser = payload.assignee,
                instructions = payload.instructions,
                taskLink = payload.taskLink,
                appName = payload.appName,
                appIconUrl = payload.appIconUrl,
                requiredProof = payload.requiredProof,
                steps = payload.steps,
                referenceImages = payload.referenceImages,
                installationInstructions = payload.installationInstructions,
                completionDurationMinutes = payload.completionDurationMinutes,
                targetSubmissions = payload.targetSubmissions,
                startDate = payload.startDate,
                endDate = payload.endDate,
                eligibilityRules = payload.eligibilityRules,
                campaignId = payload.campaignId
              )
              viewModel.setTaskActive(taskId, payload.title, payload.isActive)
            },
            onCreateCampaignFull = { title, client, desc, budget, target, cat, reward, startDate, endDate, eligibility, iconUrl, taskLink ->
              viewModel.createCampaign(
                title = title,
                clientName = client,
                description = desc,
                budgetCents = budget,
                targetSubmissions = target,
                category = cat,
                rewardPerTaskCents = reward,
                startDate = startDate,
                endDate = endDate,
                eligibilityRules = eligibility,
                appIconUrl = iconUrl,
                taskLink = taskLink
              )
            },
            onUpdateCampaignDetails = { id, title, client, desc, budget, target, cat, reward, startDate, endDate, eligibility, _, _ ->
              viewModel.updateCampaignDetails(
                campaignId = id,
                title = title,
                clientName = client,
                description = desc,
                budgetCents = budget,
                targetSubmissions = target,
                category = cat,
                rewardPerTaskCents = reward,
                startDate = startDate,
                endDate = endDate,
                eligibilityRules = eligibility
              )
            },
            databaseId = databaseId,
            onOpenSecuritySheet = { viewModel.setShowSecuritySheet(true) },
            onTriggerUpdateCheck = { viewModel.checkForAppUpdates(context, forceCheck = true) },
            withdrawalAvailabilityConfig = withdrawalAvailabilityConfig,
            adminNotifications = adminNotifications,
            isWithdrawalBusy = isBusy,
            onAcceptWithdrawalRequest = { reqId, targetStatus, note ->
              viewModel.adminAcceptWithdrawal(reqId, targetStatus, note)
            },
            onSendGiftCardCode = { reqId, giftCode, note, onSuccess ->
              viewModel.adminSendGiftCardCode(reqId, giftCode, note, onSuccess)
            },
            onCompleteUpiWithdrawal = { reqId, ref, note, onSuccess ->
              viewModel.adminCompleteUpiWithdrawal(reqId, ref, note, onSuccess)
            },
            onRejectWithdrawalAndRefund = { reqId, reason, onSuccess ->
              viewModel.adminRejectWithdrawalAndRefund(reqId, reason, onSuccess)
            },
            onUpdateWithdrawalAvailability = { config, onSuccess ->
              viewModel.adminUpdateWithdrawalAvailability(config, onSuccess)
            },
            onMarkAdminNotificationRead = { notifId ->
              viewModel.markAdminNotificationRead(notifId)
            },
            telegramTasks = telegramTasks,
            telegramClaims = telegramClaims,
            promoCodes = promoCodes,
            manualBonuses = manualBonuses,
            promoAdminFeedback = promoAdminFeedback,
            onClearPromoFeedback = { viewModel.clearPromoAdminFeedback() },
            onCreateTelegramTask = { title, channelUrl, channelChatId, rewardCoins, isActive ->
              viewModel.adminCreateTelegramTask(
                title = title,
                channelUrl = channelUrl,
                channelChatId = channelChatId,
                rewardCoins = rewardCoins,
                isActive = isActive
              )
            },
            onToggleTelegramTaskActive = { taskId, isActive ->
              viewModel.adminToggleTelegramTaskActive(taskId = taskId, isActive = isActive)
            },
            onCreatePromoCode = { rawCode, rewardCoins, maxSlots, expiresAtMillis, expiresAtLabel, isActive ->
              viewModel.adminCreatePromoCode(
                rawCode = rawCode,
                rewardCoins = rewardCoins,
                maxSlots = maxSlots,
                expiresAtMillis = expiresAtMillis,
                expiresAtLabel = expiresAtLabel,
                isActive = isActive
              )
            },
            onTogglePromoCodeActive = { promoId, isActive ->
              viewModel.adminTogglePromoCodeActive(promoId, isActive)
            },
            onCreditManualBonus = { targetUserId, amountCoins, reason ->
              viewModel.adminCreditManualBonus(
                targetUserId = targetUserId,
                amountCoins = amountCoins,
                reason = reason
              )
            },
            supportTickets = (adminSupportTickets as? com.example.ui.viewmodel.UiState.Success)?.data ?: emptyList(),
            onUpdateSupportTicket = { ticketId, status, adminReply, onResult ->
              viewModel.updateSupportTicketStatus(
                ticketId = ticketId,
                newStatus = status,
                adminReply = adminReply,
                onResult = onResult
              )
            }
          )
        }
      }
    }

    if (showNotificationDialog) {
      NotificationListDialog(
        notifications = userNotifications,
        onDismiss = { viewModel.setNotificationDialogVisible(false) },
        onMarkRead = { viewModel.markNotificationRead(it) }
      )
    }
  }
}

@Composable
fun NotificationListDialog(
  notifications: List<NotificationItem>,
  onDismiss: () -> Unit,
  onMarkRead: (String) -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Icon(Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text("Notifications", fontWeight = FontWeight.SemiBold)
      }
    },
    text = {
      if (notifications.isEmpty()) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(24.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Text(
            text = "No notifications yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "You will receive updates when tasks, Telegram rewards, promo codes, or bonuses are credited.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
          )
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxWidth().height(340.dp).testTag("notification_bell_list"),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          items(notifications, key = { it.id }) { item ->
            val iconVector =
              when (item.type) {
                NotificationRewardType.TELEGRAM_REWARD -> Icons.Default.Send
                NotificationRewardType.PROMO_CODE_REWARD -> Icons.Default.ConfirmationNumber
                NotificationRewardType.ADMIN_BONUS -> Icons.Default.CardGiftcard
                NotificationRewardType.TASK_REWARD -> Icons.Default.CheckCircle
                "WITHDRAWAL" -> Icons.Default.Payment
                else -> Icons.Default.MonetizationOn
              }
            val timestampLabel =
              remember(item.createdAt) {
                val ts = item.createdAt
                if (ts == null) {
                  "Just now"
                } else {
                  java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.US)
                    .format(java.util.Date(ts.seconds * 1000L))
                }
              }

            Card(
              modifier = Modifier.fillMaxWidth().testTag("notification_item_${item.id}"),
              shape = RoundedCornerShape(12.dp),
              colors =
                CardDefaults.cardColors(
                  containerColor =
                    if (item.isRead) {
                      MaterialTheme.colorScheme.surface
                    } else {
                      MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    }
                )
            ) {
              Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                  ) {
                    Icon(
                      imageVector = iconVector,
                      contentDescription = item.type.ifBlank { "Notification" },
                      tint = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.size(18.dp)
                    )
                    Text(
                      text = item.title,
                      style = MaterialTheme.typography.titleSmall,
                      fontWeight = FontWeight.SemiBold
                    )
                  }
                  if (item.rewardCoins > 0L) {
                    Surface(
                      shape = RoundedCornerShape(50),
                      color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                      Text(
                        text = "+${formatCoins(item.rewardCoins)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier =
                          Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            .testTag("notification_coins_${item.id}")
                      )
                    }
                  }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                  text = item.body,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                  ) {
                    Text(
                      text = timestampLabel,
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                      modifier = Modifier.testTag("notification_timestamp_${item.id}")
                    )
                    Surface(
                      shape = RoundedCornerShape(50),
                      color =
                        if (item.isRead) {
                          MaterialTheme.colorScheme.surfaceVariant
                        } else {
                          MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        }
                    ) {
                      Text(
                        text = if (item.isRead) "Read" else "Unread",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color =
                          if (item.isRead) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                          } else {
                            MaterialTheme.colorScheme.primary
                          },
                        modifier =
                          Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("notification_status_${item.id}")
                      )
                    }
                  }
                  if (!item.isRead) {
                    TextButton(
                      onClick = { onMarkRead(item.id) },
                      modifier = Modifier.height(28.dp).testTag("notif_mark_read_${item.id}")
                    ) {
                      Text("Mark read", style = MaterialTheme.typography.labelSmall)
                    }
                  }
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.testTag("notification_dialog_close_button")
      ) {
        Text("Close")
      }
    }
  )
}

