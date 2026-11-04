package com.example

import android.content.Context
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Task
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.repository.TaskFlowRepository
import com.example.ui.admin.AdminDashboardScreen
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.PortalLoginTarget
import com.example.ui.auth.signOutUser
import com.example.ui.components.FirebaseConfigSecurityDialog
import com.example.ui.theme.TaskFlowTheme
import com.example.ui.user.UserHomeScreen
import com.example.ui.user.UserProfileScreen
import com.example.ui.user.UserTaskDetailScreen
import com.example.ui.user.UserTrackingScreen
import com.example.ui.viewmodel.AdminTab
import com.example.ui.viewmodel.TaskFlowViewModel
import com.example.ui.viewmodel.UiState
import com.example.ui.viewmodel.UserTab
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
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    ensureFirebaseInitialized(applicationContext)
    runCatching { enableEdgeToEdge() }
    setContent {
      TaskFlowTheme {
        TaskFlowAppRoot()
      }
    }
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
        FirebaseApp.initializeApp(appContext, options) != null
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
fun TaskFlowAppRoot(auth: FirebaseAuth? = null) {
  val context = LocalContext.current
  val resolvedAuth =
    remember(auth, context) {
      auth
        ?: runCatching {
            ensureFirebaseInitialized(context.applicationContext)
            Firebase.auth
          }
          .getOrNull()
    }

  var preferredPortal by rememberSaveable { mutableStateOf(PortalLoginTarget.USER_APP) }
  var manualUserOverride by remember { mutableStateOf<FirebaseUser?>(resolvedAuth?.currentUser) }

  val observedUser =
    if (resolvedAuth != null) {
      val authFlow = remember(resolvedAuth) { resolvedAuth.authStateFlow() }
      val flowUser by authFlow.collectAsStateWithLifecycle(initialValue = resolvedAuth.currentUser)
      flowUser
    } else {
      null
    }

  val activeUser = observedUser ?: manualUserOverride

  if (activeUser == null) {
    AuthScreen(
      selectedPortal = preferredPortal,
      onSelectPortal = { preferredPortal = it },
      onAuthSuccess = { signedInUser ->
        manualUserOverride =
          signedInUser ?: runCatching { Firebase.auth.currentUser }.getOrNull()
      }
    )
  } else {
    AuthenticatedWorkspace(
      user = activeUser,
      initialPortal = preferredPortal,
      onSignedOut = {
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
      key = "${user.uid}_${initialPortal.name}",
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
              if (dbId.isEmpty() || dbId == "(default)") {
                FirebaseFirestore.getInstance()
              } else {
                FirebaseFirestore.getInstance(dbId)
              }
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

  LaunchedEffect(initialPortal) {
    viewModel.setPortal(initialPortal)
  }

  val activePortal by viewModel.activePortal.collectAsStateWithLifecycle()
  val activeUserTab by viewModel.activeUserTab.collectAsStateWithLifecycle()
  val activeAdminTab by viewModel.activeAdminTab.collectAsStateWithLifecycle()
  val selectedTaskId by viewModel.selectedTaskId.collectAsStateWithLifecycle()
  val trackingFilter by viewModel.trackingStatusFilter.collectAsStateWithLifecycle()
  val submissionFilter by viewModel.adminSubmissionFilter.collectAsStateWithLifecycle()
  val isAdminAuthorized by viewModel.isAdminAuthorized.collectAsStateWithLifecycle()
  val userProfileState by viewModel.userProfileState.collectAsStateWithLifecycle()
  val userTasksState by viewModel.userTasksState.collectAsStateWithLifecycle()
  val adminTasksState by viewModel.adminTasksState.collectAsStateWithLifecycle()
  val registeredUsersState by viewModel.registeredUsersState.collectAsStateWithLifecycle()
  val bannerMessage by viewModel.bannerMessage.collectAsStateWithLifecycle()
  val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
  val showSecuritySheet by viewModel.showSecuritySheet.collectAsStateWithLifecycle()

  BackHandler(
    enabled =
      selectedTaskId != null ||
        (activePortal == PortalLoginTarget.USER_APP && activeUserTab != UserTab.HOME) ||
        (activePortal == PortalLoginTarget.ADMIN_DASHBOARD && activeAdminTab != AdminTab.TASKS)
  ) {
    when {
      selectedTaskId != null -> viewModel.selectTask(null)
      activePortal == PortalLoginTarget.USER_APP && activeUserTab != UserTab.HOME ->
        viewModel.setUserTab(UserTab.HOME)
      activePortal == PortalLoginTarget.ADMIN_DASHBOARD && activeAdminTab != AdminTab.TASKS ->
        viewModel.setAdminTab(AdminTab.TASKS)
    }
  }

  if (showSecuritySheet) {
    FirebaseConfigSecurityDialog(
      databaseId = databaseId,
      onDismiss = { viewModel.setShowSecuritySheet(false) }
    )
  }

  Scaffold(
    contentWindowInsets = WindowInsets.safeDrawing,
    topBar = {
      TopAppBar(
        title = {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              imageVector = Icons.Default.TaskAlt,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(24.dp)
            )
            Text(
              text = stringResource(R.string.app_name),
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold
            )
          }
        },
        actions = {
          // Portal Mode Switcher Pill (User App <-> Admin Dashboard)
          Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
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
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                modifier = Modifier.size(16.dp)
              )
              Text(
                text =
                  if (activePortal == PortalLoginTarget.ADMIN_DASHBOARD) {
                    "Admin Mode"
                  } else {
                    "User Mode"
                  },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
              )
            }
          }

          Spacer(modifier = Modifier.width(4.dp))

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
        },
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
          )
      )
    },
    bottomBar = {
      if (activePortal == PortalLoginTarget.USER_APP) {
        NavigationBar {
          NavigationBarItem(
            selected = activeUserTab == UserTab.HOME,
            onClick = { viewModel.setUserTab(UserTab.HOME) },
            icon = { Icon(Icons.Default.Home, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_home)) },
            modifier = Modifier.testTag("nav_user_home")
          )
          NavigationBarItem(
            selected = activeUserTab == UserTab.TRACKING,
            onClick = { viewModel.setUserTab(UserTab.TRACKING) },
            icon = { Icon(Icons.AutoMirrored.Filled.FactCheck, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_tracking)) },
            modifier = Modifier.testTag("nav_user_tracking")
          )
          NavigationBarItem(
            selected = activeUserTab == UserTab.PROFILE,
            onClick = { viewModel.setUserTab(UserTab.PROFILE) },
            icon = { Icon(Icons.Default.Person, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_profile)) },
            modifier = Modifier.testTag("nav_user_profile")
          )
        }
      } else {
        NavigationBar {
          NavigationBarItem(
            selected = activeAdminTab == AdminTab.TASKS,
            onClick = { viewModel.setAdminTab(AdminTab.TASKS) },
            icon = { Icon(Icons.Default.Task, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_admin_tasks)) },
            modifier = Modifier.testTag("nav_admin_tasks")
          )
          NavigationBarItem(
            selected = activeAdminTab == AdminTab.SUBMISSIONS,
            onClick = { viewModel.setAdminTab(AdminTab.SUBMISSIONS) },
            icon = { Icon(Icons.Default.RateReview, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_admin_submissions)) },
            modifier = Modifier.testTag("nav_admin_submissions")
          )
          NavigationBarItem(
            selected = activeAdminTab == AdminTab.USERS,
            onClick = { viewModel.setAdminTab(AdminTab.USERS) },
            icon = { Icon(Icons.Default.People, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_admin_users)) },
            modifier = Modifier.testTag("nav_admin_users")
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

        if (activePortal == PortalLoginTarget.USER_APP) {
          val tasks = (userTasksState as? UiState.Success)?.data ?: emptyList()
          val selectedTask = tasks.firstOrNull { it.id == selectedTaskId }

          if (selectedTask != null) {
            val currentApprovalStatus =
              (userProfileState as? UiState.Success)?.data?.approvalStatus ?: "pending"
            UserTaskDetailScreen(
              task = selectedTask,
              isBusy = isBusy,
              onBack = { viewModel.selectTask(null) },
              onSubmitProof = { proof ->
                viewModel.submitProof(selectedTask, proof)
              },
              userApprovalStatus = currentApprovalStatus
            )
          } else {
            when (activeUserTab) {
              UserTab.HOME -> {
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
                  }
                )
              }
              UserTab.TRACKING -> {
                UserTrackingScreen(
                  tasksState = userTasksState,
                  selectedFilter = trackingFilter,
                  onSelectFilter = { viewModel.setTrackingStatusFilter(it) },
                  onSelectTask = { viewModel.selectTask(it) }
                )
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
            onCreateTask = { title, desc, cat, rewardStr, assignee ->
              viewModel.createTask(title, desc, cat, rewardStr, assignee)
            },
            onUpdateTask = { taskId, title, desc, cat, rewardStr, assignee ->
              viewModel.updateTask(taskId, title, desc, cat, rewardStr, assignee)
            },
            onDeleteTask = { viewModel.deleteTask(it) },
            onReviewSubmission = { task, approve, feedback ->
              viewModel.reviewSubmission(task, approve, feedback)
            },
            onToggleUserStatus = { viewModel.toggleUserApprovalStatus(it) }
          )
        }
      }
    }
  }
}
