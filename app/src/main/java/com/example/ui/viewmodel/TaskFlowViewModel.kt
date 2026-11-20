package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.model.AdminNotificationItem
import com.example.data.model.BOOTSTRAPPED_ADMIN_EMAIL
import com.example.data.model.CampaignItem
import com.example.data.model.DailyCheckInConstants
import com.example.data.model.DailyCheckInState
import com.example.data.model.DemoWithdrawalRequest
import com.example.data.model.ManualBonusRecord
import com.example.data.model.MIN_WITHDRAWAL_CENTS
import com.example.data.model.MIN_WITHDRAWAL_COINS
import com.example.data.model.NotificationItem
import com.example.data.model.PromoAdminUiFeedback
import com.example.data.model.PromoCodeItem
import com.example.data.model.PromoRedemptionRecord
import com.example.data.model.PromoRedemptionUiState
import com.example.data.model.ScratchCardConstants
import com.example.data.model.ScratchCardSession
import com.example.data.model.ScratchDailyState
import com.example.data.model.ScratchCardUiState
import com.example.data.model.SpinEarnUiState
import com.example.data.model.SpinSessionOutcome
import com.example.data.model.SpinWheelConstants
import com.example.util.UnityAdsHelper
import kotlinx.coroutines.flow.update
import com.example.data.model.AdminActivityLog
import com.example.data.model.AppItem
import com.example.data.model.SubmissionItem
import com.example.data.model.SupportTicket
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.TaskStep
import com.example.data.model.TelegramAccountLinkStatus
import com.example.data.model.TelegramTaskClaimRecord
import com.example.data.model.TelegramTaskItem
import com.example.data.model.TelegramUserTaskState
import com.example.data.model.TelegramVerifyResult
import com.example.data.model.TransactionRecord
import com.example.data.model.selectNextTelegramTaskForUser
import com.example.data.model.UserProfile
import com.example.data.model.UserTaskProgress
import com.example.data.model.UpdateDownloadProgress
import com.example.data.model.UpdateDownloadStatus
import com.example.data.model.WatchStats
import com.example.data.model.WithdrawalAvailabilityConfig
import com.example.data.model.WithdrawalCatalog
import com.example.data.model.WithdrawalMethod
import com.example.data.model.WithdrawalMethodType
import com.example.data.model.WithdrawalStatus
import com.example.data.model.calculateDailyCheckInState
import com.example.data.model.centsToCoins
import com.example.data.model.coinsToCents
import com.example.data.model.formatCoins
import com.example.data.model.formatRewardInr
import com.example.data.model.getEffectiveBalanceCoins
import com.example.data.model.getEligibleWithdrawalCoins
import com.example.data.model.getTodayDateKey
import com.example.data.model.isValidImageUrlOrDataUri
import com.example.data.model.isValidTaskUrl
import com.example.data.model.mergeTasksWithPreservedSubmissions
import com.example.data.repository.TaskFlowRepository
import com.example.ui.auth.PortalLoginTarget
import java.util.UUID
import kotlin.math.roundToLong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface UiState<out T> {
  data object Loading : UiState<Nothing>
  data class Success<T>(val data: T) : UiState<T>
  data class Error(val message: String) : UiState<Nothing>
}

enum class UserTab {
  HOME,
  TRACKING,
  WALLET,
  PROFILE
}

enum class ProfileSubScreen {
  MAIN,
  ACTIVITY,
  HISTORY,
  REWARDS,
  SETTINGS,
  ABOUT,
  SUPPORT
}

enum class AdminTab {
  HOME,
  OVERVIEW,
  USERS,
  TASKS,
  CAMPAIGNS,
  WITHDRAWALS,
  COINS_REWARDS,
  SUPPORT,
  ANALYTICS,
  VERIFICATION_FRAUD,
  APP_SETTINGS,
  ACTIVITY_LOGS,
  // Retained for backward compatibility with existing tests & deep links
  SUBMISSIONS,
  APPS
}

enum class WithdrawalScreenRoute {
  NONE,
  MAIN,
  UPI,
  AMAZON_GIFT_CARD,
  GOOGLE_PLAY_GIFT_CARD,
  FLIPKART_GIFT_CARD,
  CONFIRMATION,
  HISTORY
}

class TaskFlowViewModel(
  private val repository: TaskFlowRepository,
  val currentUserId: String,
  val currentUserEmail: String,
  val currentUserDisplayName: String,
  initialPortal: PortalLoginTarget = PortalLoginTarget.USER_APP
) : ViewModel() {

  private val isBootstrappedAdminEmail: Boolean =
    currentUserEmail.trim().equals(BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true)

  private val _activePortal =
    MutableStateFlow(
      if (isBootstrappedAdminEmail) {
        PortalLoginTarget.ADMIN_DASHBOARD
      } else {
        initialPortal
      }
    )
  val activePortal: StateFlow<PortalLoginTarget> = _activePortal.asStateFlow()

  private val _activeUserTab = MutableStateFlow(UserTab.HOME)
  val activeUserTab: StateFlow<UserTab> = _activeUserTab.asStateFlow()

  private val _profileSubScreen = MutableStateFlow(ProfileSubScreen.MAIN)
  val profileSubScreen: StateFlow<ProfileSubScreen> = _profileSubScreen.asStateFlow()

  private val _activeAdminTab = MutableStateFlow(AdminTab.HOME)
  val activeAdminTab: StateFlow<AdminTab> = _activeAdminTab.asStateFlow()

  private val _selectedTaskId = MutableStateFlow<String?>(null)
  val selectedTaskId: StateFlow<String?> = _selectedTaskId.asStateFlow()

  private val _trackingStatusFilter = MutableStateFlow("all")
  val trackingStatusFilter: StateFlow<String> = _trackingStatusFilter.asStateFlow()

  private val _adminSubmissionFilter = MutableStateFlow(TaskStatus.SUBMITTED)
  val adminSubmissionFilter: StateFlow<String> = _adminSubmissionFilter.asStateFlow()

  private val _bannerMessage = MutableStateFlow<String?>(null)
  val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

  private val _submissionError = MutableStateFlow<String?>(null)
  val submissionError: StateFlow<String?> = _submissionError.asStateFlow()

  private val _withdrawalValidationError = MutableStateFlow<String?>(null)
  val withdrawalValidationError: StateFlow<String?> = _withdrawalValidationError.asStateFlow()

  private val _isBusy = MutableStateFlow(false)
  val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

  private val _isOpeningCampaignLink = MutableStateFlow(false)
  val isOpeningCampaignLink: StateFlow<Boolean> = _isOpeningCampaignLink.asStateFlow()

  private val _uploadProgress = MutableStateFlow<Int?>(null)
  val uploadProgress: StateFlow<Int?> = _uploadProgress.asStateFlow()

  private val _showSecuritySheet = MutableStateFlow(false)
  val showSecuritySheet: StateFlow<Boolean> = _showSecuritySheet.asStateFlow()

  val isAdminAuthorized: StateFlow<Boolean> =
    repository
      .observeAdminAuthorization(currentUserId, currentUserEmail)
      .catch { emit(isBootstrappedAdminEmail) }
      .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        isBootstrappedAdminEmail
      )

  val userProfileState: StateFlow<UiState<UserProfile?>> =
    repository
      .observeUserProfile(currentUserId)
      .map<UserProfile?, UiState<UserProfile?>> { UiState.Success(it) }
      .catch { emit(UiState.Error(it.localizedMessage ?: "Failed to load user profile")) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

  val userTasksState: StateFlow<UiState<List<TaskItem>>> =
    repository
      .observeUserTasks(currentUserId)
      .map<List<TaskItem>, UiState<List<TaskItem>>> { UiState.Success(it) }
      .catch { emit(UiState.Error(it.localizedMessage ?: "Failed to load tasks")) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

  val userSubmissionsState: StateFlow<UiState<List<SubmissionItem>>> =
    repository
      .observeUserSubmissions(currentUserId)
      .map<List<SubmissionItem>, UiState<List<SubmissionItem>>> { UiState.Success(it) }
      .catch { emit(UiState.Error(it.localizedMessage ?: "Failed to load user submissions")) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

  val userWithdrawalRequestsState: StateFlow<UiState<List<DemoWithdrawalRequest>>> =
    repository
      .observeUserWithdrawalRequests(currentUserId)
      .map<List<DemoWithdrawalRequest>, UiState<List<DemoWithdrawalRequest>>> { UiState.Success(it) }
      .catch { emit(UiState.Error(it.localizedMessage ?: "Failed to load withdrawal requests")) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

  val userTransactionsState: StateFlow<UiState<List<TransactionRecord>>> =
    repository
      .observeUserTransactions(currentUserId)
      .map<List<TransactionRecord>, UiState<List<TransactionRecord>>> { UiState.Success(it) }
      .catch { emit(UiState.Error(it.localizedMessage ?: "Failed to load transactions")) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

  val leaderboardUsersState: StateFlow<UiState<List<UserProfile>>> =
    repository
      .observeLeaderboardUsers()
      .map<List<UserProfile>, UiState<List<UserProfile>>> { UiState.Success(it) }
      .catch { emit(UiState.Error(it.localizedMessage ?: "Failed to load leaderboard")) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

  val leaderboardCreditedTransactionsState: StateFlow<List<TransactionRecord>> =
    repository
      .observeCreditedTransactionsForLeaderboard()
      .catch { emit(emptyList()) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  val watchStats: StateFlow<WatchStats> =
    repository
      .observeWatchStats(currentUserId, getTodayDateKey())
      .catch { emit(WatchStats(userId = currentUserId, dateKey = getTodayDateKey())) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchStats(userId = currentUserId, dateKey = getTodayDateKey()))

  val dailyCheckInState: StateFlow<DailyCheckInState> =
    userProfileState
      .map { state ->
        if (state is UiState.Success && state.data != null) {
          calculateDailyCheckInState(
            lastDate = state.data.lastCheckInDate,
            streak = state.data.checkInStreak
          )
        } else {
          calculateDailyCheckInState("", 0L)
        }
      }
      .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        calculateDailyCheckInState("", 0L)
      )

  @OptIn(ExperimentalCoroutinesApi::class)
  val adminTasksState: StateFlow<UiState<List<TaskItem>>> =
    isAdminAuthorized
      .flatMapLatest { authorized ->
        repository.observeAdminTasks(currentUserId, authorized)
      }
      .map<List<TaskItem>, UiState<List<TaskItem>>> { UiState.Success(it) }
      .catch { emit(UiState.Error(it.localizedMessage ?: "Failed to load admin tasks")) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

  val appsState: StateFlow<UiState<List<AppItem>>> =
    repository.observeApps()
      .map<List<AppItem>, UiState<List<AppItem>>> { UiState.Success(it) }
      .catch { emit(UiState.Error(it.localizedMessage ?: "Failed to load apps")) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

  fun createApp(name: String, logoUrl: String, onSuccess: () -> Unit = {}) {
    viewModelScope.launch {
      _isBusy.value = true
      repository.createApp(name, logoUrl)
        .onSuccess {
          _bannerMessage.value = "App '$name' created successfully!"
          onSuccess()
        }
        .onFailure {
          _bannerMessage.value = it.localizedMessage ?: "Failed to create app."
        }
      _isBusy.value = false
    }
  }

  fun updateApp(appId: String, name: String, logoUrl: String, isArchived: Boolean, onSuccess: () -> Unit = {}) {
    viewModelScope.launch {
      _isBusy.value = true
      repository.updateApp(appId, name, logoUrl, isArchived)
        .onSuccess {
          _bannerMessage.value = "App updated successfully!"
          onSuccess()
        }
        .onFailure {
          _bannerMessage.value = it.localizedMessage ?: "Failed to update app."
        }
      _isBusy.value = false
    }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  val adminSubmissionsState: StateFlow<UiState<List<SubmissionItem>>> =
    isAdminAuthorized
      .flatMapLatest { authorized ->
        repository.observeAdminSubmissions(currentUserId, authorized)
      }
      .map<List<SubmissionItem>, UiState<List<SubmissionItem>>> { UiState.Success(it) }
      .catch { emit(UiState.Error(it.localizedMessage ?: "Failed to load submissions")) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

  @OptIn(ExperimentalCoroutinesApi::class)
  val adminWithdrawalRequestsState: StateFlow<UiState<List<DemoWithdrawalRequest>>> =
    isAdminAuthorized
      .flatMapLatest { authorized ->
        repository.observeAdminWithdrawalRequests(currentUserId, authorized)
      }
      .map<List<DemoWithdrawalRequest>, UiState<List<DemoWithdrawalRequest>>> {
        UiState.Success(it)
      }
      .catch {
        emit(UiState.Error(it.localizedMessage ?: "Failed to load withdrawal requests"))
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

  @OptIn(ExperimentalCoroutinesApi::class)
  val registeredUsersState: StateFlow<UiState<List<UserProfile>>> =
    isAdminAuthorized
      .flatMapLatest { authorized ->
        repository.observeRegisteredUsers(currentUserId, authorized)
      }
      .map<List<UserProfile>, UiState<List<UserProfile>>> { UiState.Success(it) }
      .catch { emit(UiState.Error(it.localizedMessage ?: "Failed to load users")) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

  @OptIn(ExperimentalCoroutinesApi::class)
  val adminTransactionsState: StateFlow<UiState<List<TransactionRecord>>> =
    isAdminAuthorized
      .flatMapLatest { authorized ->
        repository.observeAllTransactions(currentUserId, authorized)
      }
      .map<List<TransactionRecord>, UiState<List<TransactionRecord>>> { UiState.Success(it) }
      .catch { emit(UiState.Error(it.localizedMessage ?: "Failed to load all transactions")) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

  val campaignsState: StateFlow<UiState<List<CampaignItem>>> =
    repository.observeCampaigns()
      .map<List<CampaignItem>, UiState<List<CampaignItem>>> { UiState.Success(it) }
      .catch { emit(UiState.Error(it.localizedMessage ?: "Failed to load campaigns")) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

  val userNotificationsState: StateFlow<List<NotificationItem>> =
    repository.observeUserNotifications(currentUserId)
      .catch { emit(emptyList()) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  val userSupportTicketsState: StateFlow<List<SupportTicket>> =
    repository.observeUserSupportTickets(currentUserId)
      .catch { emit(emptyList()) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  @OptIn(ExperimentalCoroutinesApi::class)
  val adminSupportTicketsState: StateFlow<UiState<List<SupportTicket>>> =
    isAdminAuthorized
      .flatMapLatest { authorized ->
        if (authorized) repository.observeAllSupportTickets()
        else kotlinx.coroutines.flow.flowOf(emptyList())
      }
      .map<List<SupportTicket>, UiState<List<SupportTicket>>> { UiState.Success(it) }
      .catch { emit(UiState.Error(it.localizedMessage ?: "Failed to load support tickets")) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

  @OptIn(ExperimentalCoroutinesApi::class)
  val adminActivityLogsState: StateFlow<List<AdminActivityLog>> =
    isAdminAuthorized
      .flatMapLatest { authorized ->
        repository.observeAdminActivityLogs(authorized)
      }
      .catch { emit(emptyList()) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  val withdrawalAvailabilityState: StateFlow<WithdrawalAvailabilityConfig> =
    repository
      .observeWithdrawalAvailability()
      .catch { emit(WithdrawalAvailabilityConfig()) }
      .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        WithdrawalAvailabilityConfig()
      )

  @OptIn(ExperimentalCoroutinesApi::class)
  val adminNotificationsState: StateFlow<List<AdminNotificationItem>> =
    isAdminAuthorized
      .flatMapLatest { authorized ->
        repository.observeAdminNotifications(authorized)
      }
      .catch { emit(emptyList()) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  @OptIn(ExperimentalCoroutinesApi::class)
  val selectedTaskProgressState: StateFlow<UserTaskProgress?> =
    _selectedTaskId
      .flatMapLatest { taskId ->
        if (taskId.isNullOrBlank() || currentUserId.isBlank()) {
          kotlinx.coroutines.flow.flowOf(null)
        } else {
          repository.observeUserTaskProgress(currentUserId, taskId)
        }
      }
      .catch { emit(null) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  fun confirmTaskInstallation(task: TaskItem) {
    if (currentUserId.isBlank() || task.id.isBlank()) return
    val current = selectedTaskProgressState.value
    val completedSteps = current?.completedSteps ?: emptyList()
    val nextStatus =
      if (completedSteps.isNotEmpty()) TaskStatus.STAGE_IN_PROGRESS
      else TaskStatus.STAGE_INSTALLATION_CONFIRMED
    viewModelScope.launch {
      repository.saveUserTaskProgress(
        userId = currentUserId,
        taskId = task.id,
        completedSteps = completedSteps,
        trackingEvents = (current?.trackingEvents ?: emptyMap()) + ("installation_confirmed" to true),
        status = nextStatus,
        installationConfirmed = true,
        currentStepNumber = (current?.currentStepNumber ?: 1).coerceAtLeast(1)
      )
    }
  }

  /**
   * Opens a task destination URL safely:
   * - For tracked campaigns (`campaignId` present), requests a server-issued click via
   *   `createCampaignClick` first, and only opens the returned attribution tracking URL.
   * - For legacy manual proof tasks without a linked campaign (`campaignId` blank),
   *   preserves the direct validated HTTPS launch flow.
   */
  fun openCampaignTaskLink(
    task: TaskItem,
    overrideUrl: String = "",
    onTrackingUrlReady: (String) -> Unit
  ) {
    val rawUrl = overrideUrl.trim().ifEmpty { task.taskLink.trim() }
    val cleanCampaignId = task.campaignId.trim()
    if (cleanCampaignId.isEmpty()) {
      if (!isValidTaskUrl(rawUrl)) {
        _bannerMessage.value = "Invalid or unsafe campaign URL."
        return
      }
      confirmTaskInstallation(task)
      onTrackingUrlReady(rawUrl)
      return
    }

    if (_isOpeningCampaignLink.value) return
    _isOpeningCampaignLink.value = true
    viewModelScope.launch {
      try {
        val attemptKey =
          "${task.id.ifBlank { cleanCampaignId }.take(40)}_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val result = repository.createCampaignClick(
          campaignId = cleanCampaignId,
          taskId = task.id,
          devicePlatform = "ANDROID",
          idempotencyKey = attemptKey
        )
        result
          .onSuccess { issued ->
            if (!isValidTaskUrl(issued.trackingUrl)) {
              _bannerMessage.value = "Server returned an unsafe tracking URL."
            } else {
              confirmTaskInstallation(task)
              onTrackingUrlReady(issued.trackingUrl)
            }
          }
          .onFailure { err ->
            _bannerMessage.value =
              err.localizedMessage ?: "Unable to start tracked campaign right now."
          }
      } finally {
        _isOpeningCampaignLink.value = false
      }
    }
  }

  fun toggleTaskStepCompleted(task: TaskItem, stepNumber: Int, totalSteps: Int) {
    if (currentUserId.isBlank() || task.id.isBlank()) return
    val current = selectedTaskProgressState.value
    val existingSteps = current?.completedSteps ?: emptyList()
    val updatedSteps =
      if (existingSteps.contains(stepNumber)) {
        existingSteps - stepNumber
      } else {
        (existingSteps + stepNumber).distinct().sorted()
      }
    val nextCurrentStep =
      (1..totalSteps.coerceAtLeast(1)).firstOrNull { !updatedSteps.contains(it) }
        ?: totalSteps.coerceAtLeast(1)
    viewModelScope.launch {
      repository.saveUserTaskProgress(
        userId = currentUserId,
        taskId = task.id,
        completedSteps = updatedSteps,
        trackingEvents = (current?.trackingEvents ?: emptyMap()) + ("step_$stepNumber" to true),
        status = TaskStatus.STAGE_IN_PROGRESS,
        installationConfirmed = true,
        currentStepNumber = nextCurrentStep
      )
    }
  }

  init {
    viewModelScope.launch {
      val authorizedAdmin =
        withTimeoutOrNull(10_000L) {
          repository.checkAndEnsureAdminAuthorization(currentUserId, currentUserEmail)
        } ?: isBootstrappedAdminEmail
      if (authorizedAdmin) {
        _activePortal.value = PortalLoginTarget.ADMIN_DASHBOARD
      }
    }

    viewModelScope.launch {
      withTimeoutOrNull(10_000L) {
        repository
          .ensureUserProfile(
            uid = currentUserId,
            email = currentUserEmail,
            displayName = currentUserDisplayName
          )
          .onFailure { err ->
            _bannerMessage.value =
              "Profile initialization notice: ${err.localizedMessage ?: "Check connection"}"
          }
      }
    }

    // Continuously synchronize Spin & Earn 24-hour Free Spin cooldown and Extra Spin progress from Firestore
    viewModelScope.launch {
      userProfileState.collect { state ->
        val profile = (state as? UiState.Success)?.data ?: return@collect
        val lastFreeSpinMillis = profile.lastFreeSpinAt?.toDate()?.time ?: 0L
        val nextAvailableAt =
          if (lastFreeSpinMillis > 0L) {
            lastFreeSpinMillis + SpinWheelConstants.FREE_SPIN_COOLDOWN_MS
          } else {
            0L
          }
        val now = System.currentTimeMillis()
        val isFreeAvailable = lastFreeSpinMillis <= 0L || now >= nextAvailableAt
        val videosWatched = profile.extraSpinVideosWatched.toInt().coerceIn(0, 2)
        val isExtraAvailable = profile.extraSpinAvailable || videosWatched >= 2
        val currentSpin = _spinEarnUiState.value
        _spinEarnUiState.value =
          currentSpin.copy(
            freeSpinAvailable = isFreeAvailable,
            freeSpinNextAvailableAtMillis = if (isFreeAvailable) 0L else nextAvailableAt,
            extraSpinVideosWatched = videosWatched,
            extraSpinAvailable = isExtraAvailable,
            adsWatchedForSpin = videosWatched
          )
      }
    }
  }

  fun setPortal(portal: PortalLoginTarget) {
    if (portal == PortalLoginTarget.ADMIN_DASHBOARD && !isAdminAuthorized.value) {
      _activePortal.value = PortalLoginTarget.USER_APP
      return
    }
    _activePortal.value = portal
    if (portal == PortalLoginTarget.ADMIN_DASHBOARD) {
      _activeAdminTab.value = AdminTab.HOME
    }
    _selectedTaskId.value = null
    _submissionError.value = null
    _withdrawalValidationError.value = null
  }

  fun setUserTab(tab: UserTab) {
    _activeUserTab.value = tab
    _selectedTaskId.value = null
    _submissionError.value = null
    _withdrawalValidationError.value = null
    if (tab != UserTab.PROFILE) {
      _profileSubScreen.value = ProfileSubScreen.MAIN
    }
    if (tab != UserTab.WALLET) {
      _withdrawalRoute.value = WithdrawalScreenRoute.NONE
      _openWithdrawOnWalletNavigation.value = false
    }
  }

  fun setProfileSubScreen(subScreen: ProfileSubScreen) {
    _profileSubScreen.value = subScreen
  }

  fun setAdminTab(tab: AdminTab) {
    _activeAdminTab.value = tab
    _selectedTaskId.value = null
    _submissionError.value = null
  }

  fun selectTask(taskId: String?) {
    _selectedTaskId.value = taskId
    _submissionError.value = null
  }

  fun setTrackingStatusFilter(filter: String) {
    _trackingStatusFilter.value = filter
  }

  fun setAdminSubmissionFilter(filter: String) {
    _adminSubmissionFilter.value = filter
  }

  fun setShowSecuritySheet(show: Boolean) {
    _showSecuritySheet.value = show
  }

  fun clearBannerMessage() {
    _bannerMessage.value = null
  }

  fun clearSubmissionError() {
    _submissionError.value = null
  }

  fun clearWithdrawalValidationError() {
    _withdrawalValidationError.value = null
  }

  fun updateProfileDisplayName(newName: String) {
    viewModelScope.launch {
      _isBusy.value = true
      repository
        .updateUserDisplayName(currentUserId, newName)
        .onSuccess { _bannerMessage.value = "Display name updated." }
        .onFailure { _bannerMessage.value = it.localizedMessage ?: "Could not update name." }
      _isBusy.value = false
    }
  }

  private var pendingWithdrawalAttemptSignature: String? = null
  private var pendingWithdrawalIdempotencyKey: String? = null

  private fun clearPendingWithdrawalAttempt() {
    pendingWithdrawalAttemptSignature = null
    pendingWithdrawalIdempotencyKey = null
  }

  fun submitWithdrawalRedemption(
    method: String,
    amountRupees: Long,
    upiId: String = "",
    onSuccess: (DemoWithdrawalRequest) -> Unit = {}
  ) {
    if (_isBusy.value) return
    _withdrawalValidationError.value = null
    val normalizedMethod = method.trim().uppercase(java.util.Locale.US)
    val cleanedUpi = upiId.trim()
    val requiredCoins = WithdrawalCatalog.resolveRequiredCoins(normalizedMethod, amountRupees)
    if (requiredCoins == null || requiredCoins <= 0L) {
      _withdrawalValidationError.value = "Please select a valid denomination."
      return
    }
    if (normalizedMethod == WithdrawalMethodType.UPI) {
      if (cleanedUpi.isEmpty() || !cleanedUpi.contains("@") || cleanedUpi.contains(" ")) {
        _withdrawalValidationError.value = "Please enter a valid UPI ID (e.g., name@upi)."
        return
      }
    }

    val avail = withdrawalAvailabilityState.value
    if (!avail.isDenominationAvailable(normalizedMethod, amountRupees)) {
      _withdrawalValidationError.value = "This option is currently unavailable."
      return
    }

    val profile = (userProfileState.value as? UiState.Success)?.data
    val totalAvailableCoins = profile?.getEffectiveBalanceCoins() ?: 0L
    if (totalAvailableCoins < requiredCoins) {
      _withdrawalValidationError.value =
        "Insufficient balance. You need ${formatCoins(requiredCoins)} (Your balance: ${formatCoins(totalAvailableCoins)})."
      return
    }

    val resolvedName =
      profile?.displayName?.ifBlank { currentUserDisplayName } ?: currentUserDisplayName
    val signature = "$currentUserId|$normalizedMethod|$amountRupees|$cleanedUpi"
    val idempotencyKey =
      if (pendingWithdrawalAttemptSignature == signature && !pendingWithdrawalIdempotencyKey.isNullOrBlank()) {
        pendingWithdrawalIdempotencyKey!!
      } else {
        UUID.randomUUID().toString().replace("-", "").also { generated ->
          pendingWithdrawalAttemptSignature = signature
          pendingWithdrawalIdempotencyKey = generated
        }
      }

    _isBusy.value = true
    viewModelScope.launch {
      try {
        repository
          .submitWithdrawalRedemption(
            uid = currentUserId,
            userName = resolvedName,
            method = normalizedMethod,
            amountRupees = amountRupees,
            upiId = cleanedUpi,
            idempotencyKey = idempotencyKey
          )
          .onSuccess { createdRequest ->
            clearPendingWithdrawalAttempt()
            _withdrawalValidationError.value = null
            _lastSubmittedWithdrawal.value = createdRequest
            _withdrawalRoute.value = WithdrawalScreenRoute.CONFIRMATION
            _bannerMessage.value = "Request Submitted Successfully!"
            onSuccess(createdRequest)
          }
          .onFailure { err ->
            val msg = err.localizedMessage ?: "Could not submit withdrawal request."
            _withdrawalValidationError.value = msg
          }
      } finally {
        _isBusy.value = false
      }
    }
  }

  fun adminAcceptWithdrawal(
    requestId: String,
    targetStatus: String = WithdrawalStatus.UNDER_REVIEW,
    statusNote: String = ""
  ) {
    if (!isAdminAuthorized.value || _isBusy.value) return
    _isBusy.value = true
    viewModelScope.launch {
      try {
        repository
          .adminAcceptWithdrawalRequest(
            requestId = requestId,
            targetStatus = targetStatus,
            statusNote = statusNote
          )
          .onSuccess {
            _bannerMessage.value =
              "Request marked as ${WithdrawalStatus.displayLabel(targetStatus)}."
          }
          .onFailure { err ->
            _bannerMessage.value = err.localizedMessage ?: "Failed to update request status."
          }
      } finally {
        _isBusy.value = false
      }
    }
  }

  fun adminSendGiftCardCode(
    requestId: String,
    giftCode: String,
    statusNote: String = "",
    onSuccess: () -> Unit = {}
  ) {
    if (!isAdminAuthorized.value || _isBusy.value) return
    _isBusy.value = true
    viewModelScope.launch {
      try {
        repository
          .adminSendGiftCardCode(
            requestId = requestId,
            rawGiftCode = giftCode,
            statusNote = statusNote
          )
          .onSuccess {
            _bannerMessage.value = "Gift Card code delivered and request completed."
            onSuccess()
          }
          .onFailure { err ->
            _bannerMessage.value = err.localizedMessage ?: "Failed to deliver Gift Card code."
          }
      } finally {
        _isBusy.value = false
      }
    }
  }

  fun adminCompleteUpiWithdrawal(
    requestId: String,
    paymentReference: String = "",
    statusNote: String = "",
    onSuccess: () -> Unit = {}
  ) {
    if (!isAdminAuthorized.value || _isBusy.value) return
    _isBusy.value = true
    viewModelScope.launch {
      try {
        repository
          .adminCompleteUpiWithdrawal(
            requestId = requestId,
            paymentReference = paymentReference,
            statusNote = statusNote
          )
          .onSuccess {
            _bannerMessage.value = "UPI payout marked as Completed."
            onSuccess()
          }
          .onFailure { err ->
            _bannerMessage.value = err.localizedMessage ?: "Failed to complete UPI payout."
          }
      } finally {
        _isBusy.value = false
      }
    }
  }

  fun adminRejectWithdrawalAndRefund(
    requestId: String,
    rejectionReason: String,
    onSuccess: () -> Unit = {}
  ) {
    if (!isAdminAuthorized.value || _isBusy.value) return
    _isBusy.value = true
    viewModelScope.launch {
      try {
        repository
          .adminRejectWithdrawalAndRefund(
            requestId = requestId,
            rejectionReason = rejectionReason
          )
          .onSuccess {
            _bannerMessage.value = "Request rejected and Coins refunded once atomically."
            onSuccess()
          }
          .onFailure { err ->
            _bannerMessage.value = err.localizedMessage ?: "Failed to reject and refund request."
          }
      } finally {
        _isBusy.value = false
      }
    }
  }

  fun adminUpdateWithdrawalAvailability(
    config: WithdrawalAvailabilityConfig,
    onSuccess: () -> Unit = {}
  ) {
    if (!isAdminAuthorized.value || _isBusy.value) return
    _isBusy.value = true
    viewModelScope.launch {
      try {
        repository
          .updateWithdrawalAvailabilityConfig(config)
          .onSuccess {
            _bannerMessage.value = "Withdrawal availability settings updated."
            onSuccess()
          }
          .onFailure { err ->
            _bannerMessage.value = err.localizedMessage ?: "Failed to update availability settings."
          }
      } finally {
        _isBusy.value = false
      }
    }
  }

  fun markAdminNotificationRead(notificationId: String) {
    if (!isAdminAuthorized.value) return
    viewModelScope.launch {
      repository.markAdminNotificationRead(notificationId)
    }
  }

  fun submitProof(
    task: TaskItem,
    proofText: String,
    imageUri: Uri?,
    onSuccess: () -> Unit = {}
  ) {
    val cleanedText = proofText.trim()
    if (cleanedText.isEmpty() && imageUri == null) {
      val msg = "Both written text proof AND at least one Gallery photo are mandatory."
      _submissionError.value = msg
      _bannerMessage.value = msg
      return
    }
    if (cleanedText.isEmpty()) {
      val msg = "Written text proof is mandatory. Please describe your task completion."
      _submissionError.value = msg
      _bannerMessage.value = msg
      return
    }
    if (imageUri == null) {
      val msg = "At least one Gallery photo or screenshot is mandatory."
      _submissionError.value = msg
      _bannerMessage.value = msg
      return
    }

    val profile = (userProfileState.value as? UiState.Success)?.data
    val resolvedName =
      profile?.displayName?.ifBlank { currentUserDisplayName } ?: currentUserDisplayName
    val resolvedEmail =
      profile?.email?.ifBlank { currentUserEmail } ?: currentUserEmail

    viewModelScope.launch {
      _isBusy.value = true
      _submissionError.value = null
      _uploadProgress.value = 5
      repository
        .submitTaskProof(
          task = task,
          userName = resolvedName,
          userEmail = resolvedEmail,
          proofText = cleanedText,
          imageUri = imageUri,
          onProgress = { pct -> _uploadProgress.value = pct }
        )
        .onSuccess {
          _submissionError.value = null
          _bannerMessage.value =
            "Proof text and screenshot submitted for '${task.title}'! Status is now Pending Review."
          onSuccess()
        }
        .onFailure {
          val errMsg = it.localizedMessage ?: "Failed to submit proof. Please try again."
          _submissionError.value = errMsg
          _bannerMessage.value = errMsg
        }
      _uploadProgress.value = null
      _isBusy.value = false
    }
  }

  fun createTask(
    title: String,
    description: String,
    category: String,
    rewardAmountCoins: String,
    assignedUser: UserProfile,
    instructions: String = "",
    taskLink: String = "",
    appName: String = "",
    appIconUrl: String = "",
    requiredProof: String = "",
    appId: String = "",
    steps: List<TaskStep> = emptyList(),
    referenceImages: List<String> = emptyList(),
    installationInstructions: String = "",
    completionDurationMinutes: Int = 5,
    targetSubmissions: Long = 0L,
    startDate: String = "",
    endDate: String = "",
    eligibilityRules: String = "All Approved Users",
    campaignId: String = "",
    isActive: Boolean = true,
    alsoCreateCampaign: Boolean = false,
    clientName: String = ""
  ) {
    val parsedCoins = rewardAmountCoins.trim().toLongOrNull()
    if (parsedCoins == null || parsedCoins <= 0L) {
      _bannerMessage.value = "Enter a valid reward amount in Coins (e.g., 500)."
      return
    }
    val cleanedLink = taskLink.trim()
    if (cleanedLink.isNotEmpty() && !isValidTaskUrl(cleanedLink)) {
      _bannerMessage.value =
        "Please enter a valid HTTPS URL (e.g., https://play.google.com/store/apps/details?id=...)."
      return
    }
    val cleanedIcon = appIconUrl.trim().ifBlank { "https://cdn-icons-png.flaticon.com/512/906/906334.png" }
    if (!isValidImageUrlOrDataUri(cleanedIcon)) {
      _bannerMessage.value = "Please enter a valid HTTPS icon URL or pick an icon from Gallery."
      return
    }
    val rewardCents = parsedCoins
    viewModelScope.launch {
      _isBusy.value = true
      var resolvedCampaignId = campaignId.trim()
      if (alsoCreateCampaign && resolvedCampaignId.isBlank() && targetSubmissions > 0L) {
        val partner = clientName.trim().ifBlank { appName.trim().ifBlank { "TaskFlow Partner" } }
        val budget = rewardCents * targetSubmissions
        repository
          .createCampaign(
            title = title,
            clientName = partner,
            description = description,
            budgetCents = budget,
            targetSubmissions = targetSubmissions,
            category = category,
            rewardPerTaskCents = rewardCents,
            startDate = startDate,
            endDate = endDate,
            eligibilityRules = eligibilityRules,
            appIconUrl = cleanedIcon,
            taskLink = cleanedLink
          )
          .onSuccess { createdId -> resolvedCampaignId = createdId }
      }
      repository
        .createTask(
          title = title,
          description = description,
          category = category,
          rewardCents = rewardCents,
          assignedUserId = assignedUser.userId.ifBlank { assignedUser.id },
          assignedUserName = assignedUser.displayName,
          assignedUserEmail = assignedUser.email,
          instructions = instructions,
          taskLink = cleanedLink,
          appName = appName,
          appIconUrl = cleanedIcon,
          requiredProof = requiredProof,
          appId = appId,
          steps = steps,
          referenceImages = referenceImages,
          installationInstructions = installationInstructions,
          completionDurationMinutes = completionDurationMinutes,
          targetSubmissions = targetSubmissions,
          startDate = startDate,
          endDate = endDate,
          eligibilityRules = eligibilityRules,
          campaignId = resolvedCampaignId,
          isActive = isActive
        )
        .onSuccess {
          _bannerMessage.value =
            "Task '$title' created (${formatCoins(rewardCents)}) and published to TaskFlow."
        }
        .onFailure {
          _bannerMessage.value = it.localizedMessage ?: "Failed to create task."
        }
      _isBusy.value = false
    }
  }

  fun updateTask(
    taskId: String,
    title: String,
    description: String,
    category: String,
    rewardAmountCoins: String,
    assignedUser: UserProfile,
    instructions: String = "",
    taskLink: String = "",
    appName: String = "",
    appIconUrl: String = "",
    requiredProof: String = "",
    appId: String = "",
    steps: List<TaskStep> = emptyList(),
    referenceImages: List<String> = emptyList(),
    installationInstructions: String = "",
    completionDurationMinutes: Int = 5,
    targetSubmissions: Long = 0L,
    startDate: String = "",
    endDate: String = "",
    eligibilityRules: String = "All Approved Users",
    campaignId: String = ""
  ) {
    val parsedCoins = rewardAmountCoins.trim().toLongOrNull()
    if (parsedCoins == null || parsedCoins <= 0L) {
      _bannerMessage.value = "Enter a valid reward amount in Coins (e.g., 500)."
      return
    }
    val cleanedLink = taskLink.trim()
    if (cleanedLink.isNotEmpty() && !isValidTaskUrl(cleanedLink)) {
      _bannerMessage.value =
        "Please enter a valid HTTPS URL (e.g., https://play.google.com/store/apps/details?id=...)."
      return
    }
    val cleanedIcon = appIconUrl.trim()
    if (!isValidImageUrlOrDataUri(cleanedIcon)) {
      _bannerMessage.value = "Please enter a valid HTTPS icon URL or pick an icon from Gallery."
      return
    }
    val rewardCents = parsedCoins
    viewModelScope.launch {
      _isBusy.value = true
      repository
        .updateTaskDetails(
          taskId = taskId,
          title = title,
          description = description,
          category = category,
          rewardCents = rewardCents,
          assignedUserId = assignedUser.userId.ifBlank { assignedUser.id },
          assignedUserName = assignedUser.displayName,
          assignedUserEmail = assignedUser.email,
          instructions = instructions,
          taskLink = cleanedLink,
          appName = appName,
          appIconUrl = cleanedIcon,
          requiredProof = requiredProof,
          appId = appId,
          steps = steps,
          referenceImages = referenceImages,
          installationInstructions = installationInstructions,
          completionDurationMinutes = completionDurationMinutes,
          targetSubmissions = targetSubmissions,
          startDate = startDate,
          endDate = endDate,
          eligibilityRules = eligibilityRules,
          campaignId = campaignId
        )
        .onSuccess {
          _bannerMessage.value = "Task '$title' updated."
        }
        .onFailure {
          _bannerMessage.value = it.localizedMessage ?: "Failed to update task."
        }
      _isBusy.value = false
    }
  }

  fun setTaskActive(taskId: String, title: String, isActive: Boolean) {
    viewModelScope.launch {
      _isBusy.value = true
      repository
        .setTaskActiveStatus(taskId = taskId, isActive = isActive)
        .onSuccess {
          _bannerMessage.value =
            if (isActive) {
              "Task '$title' activated and available for user submissions."
            } else {
              "Task '$title' deactivated. It will not accept new submissions."
            }
        }
        .onFailure {
          _bannerMessage.value = it.localizedMessage ?: "Failed to update task active state."
        }
      _isBusy.value = false
    }
  }

  fun deleteTask(taskId: String) {
    viewModelScope.launch {
      _isBusy.value = true
      repository
        .deleteTask(taskId)
        .onSuccess {
          _bannerMessage.value =
            "Task deleted. Existing submissions and user history remain preserved."
        }
        .onFailure { _bannerMessage.value = it.localizedMessage ?: "Failed to delete task." }
      _isBusy.value = false
    }
  }

  fun reviewSubmission(task: TaskItem, approve: Boolean, feedback: String) {
    viewModelScope.launch {
      _isBusy.value = true
      repository
        .reviewSubmission(task = task, approve = approve, adminFeedback = feedback)
        .onSuccess {
          val actionLabel =
            if (approve) {
              "approved & ${formatRewardInr(task.rewardCents)} credited"
            } else {
              "rejected"
            }
          _bannerMessage.value = "Submission for '${task.title}' $actionLabel."
        }
        .onFailure {
          _bannerMessage.value = it.localizedMessage ?: "Failed to review submission."
        }
      _isBusy.value = false
    }
  }

  fun toggleUserApprovalStatus(user: UserProfile) {
    val uid = user.userId.ifBlank { user.id }
    val nextStatus = if (user.approvalStatus == "approved") "suspended" else "approved"
    updateUserApprovalStatus(user, nextStatus)
  }

  fun updateUserApprovalStatus(user: UserProfile, targetStatus: String) {
    val uid = user.userId.ifBlank { user.id }
    viewModelScope.launch {
      _isBusy.value = true
      repository
        .updateUserApprovalStatus(uid, targetStatus)
        .onSuccess {
          _bannerMessage.value = "Updated ${user.displayName} status to $targetStatus."
        }
        .onFailure {
          _bannerMessage.value = it.localizedMessage ?: "Failed to update user status."
        }
      _isBusy.value = false
    }
  }

  fun getApprovedTaskEarningsCents(): Long {
    val tasks = (userTasksState.value as? UiState.Success)?.data ?: emptyList()
    val submissions = (userSubmissionsState.value as? UiState.Success)?.data ?: emptyList()
    val merged = mergeTasksWithPreservedSubmissions(tasks, submissions, currentUserId)
    return merged
      .filter { it.status == TaskStatus.APPROVED && (currentUserId.isBlank() || it.assignedUserId == currentUserId) }
      .sumOf { it.rewardCents }
  }

  fun claimDailyCheckIn(
    day: Int = 0,
    activity: android.app.Activity? = null,
    onSuccess: (Long) -> Unit = {}
  ) {
    val currentState = dailyCheckInState.value
    val targetStreak = if (day in 1..30) day else currentState.nextClaimDay

    if (!currentState.canCheckInToday) {
      _bannerMessage.value = "You have already claimed today's check-in reward! Come back tomorrow."
      return
    }

    if (targetStreak != currentState.nextClaimDay) {
      if (targetStreak <= currentState.currentStreak) {
        _bannerMessage.value = "Day $targetStreak has already been claimed!"
      } else {
        _bannerMessage.value =
          "Day $targetStreak is locked. Please complete Day ${currentState.nextClaimDay} first."
      }
      return
    }

    val uid = currentUserId

    if (uid.isBlank()) {
      _bannerMessage.value = "Please sign in to claim daily check-in rewards."
      return
    }

    if (_isBusy.value) {
      return
    }

    _isBusy.value = true
    viewModelScope.launch {
      try {
        repository
          .claimDailyCheckInDetailed(
            userId = uid,
            streakDay = targetStreak
          )
          .onSuccess { claimed ->
            val actualStreakDay = claimed.streakDay ?: targetStreak
            val actualCoins = claimed.rewardCents
            _bannerMessage.value =
              "Success! Day $actualStreakDay check-in claimed: +${formatCoins(actualCoins)}"
            onSuccess(actualCoins)
          }
          .onFailure { error ->
            val rawMsg = error.localizedMessage ?: "Failed to claim check-in reward."
            val cleanMsg =
              if (rawMsg.contains("NOT_FOUND", ignoreCase = true)) {
                "Unable to complete check-in right now. Please try again."
              } else {
                rawMsg
              }
            _bannerMessage.value = cleanMsg
          }
      } finally {
        _isBusy.value = false
      }
    }
  }

  private val _watchEarnUiState =
    MutableStateFlow(com.example.data.model.WatchEarnUiState())
  val watchEarnUiState: StateFlow<com.example.data.model.WatchEarnUiState> =
    _watchEarnUiState.asStateFlow()

  fun prepareWatchEarnDialog(context: android.content.Context? = null) {
    val current = _watchEarnUiState.value
    if (
      current.status == com.example.data.model.WatchEarnFlowStatus.CLAIMED ||
        current.status == com.example.data.model.WatchEarnFlowStatus.ERROR
    ) {
      _watchEarnUiState.value = com.example.data.model.WatchEarnUiState()
    }
    context?.let { ctx ->
      com.example.util.UnityAdsHelper.preloadRewardedAd(ctx, force = false)
    }
  }

  /**
   * Watch & Earn 2-Video Cycle — Step 1 (Video 1 of 2):
   * - User taps "Watch Video"
   * - Creates an authenticated reward session bound to currentUserId
   * - Plays 1st rewarded video via UnityAdsHelper
   * - Only after 1st video completes does the Claim button appear (videosCompletedInCycle = 1).
   */
  fun startWatchEarnVideo(activity: android.app.Activity) {
    android.util.Log.i("WatchEarnFlow", "startWatchEarnVideo invoked by user tap")
    val uid = currentUserId
    if (uid.isBlank()) {
      _bannerMessage.value = "Please sign in to watch videos."
      return
    }
    val currentFlow = _watchEarnUiState.value
    if (currentFlow.isLoadingAd || currentFlow.isClaiming) {
      return
    }
    val stats = watchStats.value
    if (stats.videosWatched >= 25L) {
      _watchEarnUiState.value =
        com.example.data.model.WatchEarnUiState(
          status = com.example.data.model.WatchEarnFlowStatus.ERROR,
          errorMessage = "Daily limit of 25 videos reached (25/25). Come back tomorrow!"
        )
      return
    }

    val todayKey = getTodayDateKey()
    _watchEarnUiState.value =
      com.example.data.model.WatchEarnUiState(
        status = com.example.data.model.WatchEarnFlowStatus.LOADING_AD,
        videosCompletedInCycle = 0,
        statusMessage = "Loading video..."
      )

    viewModelScope.launch {
      val sessionResult = repository.createWatchEarnSession(uid, todayKey)
      val sessionId =
        sessionResult.getOrElse { err ->
          val msg =
            err.localizedMessage?.takeIf { it.isNotBlank() }
              ?: "Unable to start Watch & Earn session. Please try again."
          _watchEarnUiState.value =
            com.example.data.model.WatchEarnUiState(
              status = com.example.data.model.WatchEarnFlowStatus.ERROR,
              errorMessage = msg
            )
          _bannerMessage.value = msg
          return@launch
        }
      val sessionRewardCoins = repository.getOrAssignWatchEarnSessionReward(sessionId)

      com.example.util.UnityAdsHelper.loadAndShowRewardedAdForSession(
        activity = activity,
        sessionId = sessionId,
        onAdShowing = {
          _watchEarnUiState.value =
            com.example.data.model.WatchEarnUiState(
              status = com.example.data.model.WatchEarnFlowStatus.SHOWING_AD,
              activeSessionId = sessionId,
              videosCompletedInCycle = 0,
              lastRewardCoins = sessionRewardCoins,
              statusMessage = "Watching video... Please watch until the end."
            )
        },
        onVideoCompleted = {
          viewModelScope.launch {
            repository
              .verifyWatchEarnSessionCompleted(uid, sessionId)
              .onSuccess {
                val verifiedCoins = repository.getOrAssignWatchEarnSessionReward(sessionId)
                val readyMsg =
                  if (verifiedCoins > 0L) {
                    "Video verified (+${formatCoins(verifiedCoins)})! Tap Claim to watch the next video and credit your wallet."
                  } else {
                    "Video verified! Tap Claim to watch the next video and credit your wallet."
                  }
                _watchEarnUiState.value =
                  com.example.data.model.WatchEarnUiState(
                    status = com.example.data.model.WatchEarnFlowStatus.READY_TO_CLAIM,
                    activeSessionId = sessionId,
                    videosCompletedInCycle = 1,
                    lastRewardCoins = verifiedCoins,
                    statusMessage = readyMsg
                  )
              }
              .onFailure { err ->
                val rawErr = err.localizedMessage ?: "Reward verification failed. Please try again."
                _watchEarnUiState.value =
                  com.example.data.model.WatchEarnUiState(
                    status = com.example.data.model.WatchEarnFlowStatus.ERROR,
                    errorMessage = rawErr
                  )
              }
          }
        },
        onFailed = { errorMsg ->
          _watchEarnUiState.value =
            com.example.data.model.WatchEarnUiState(
              status = com.example.data.model.WatchEarnFlowStatus.ERROR,
              errorMessage = errorMsg.ifBlank { com.example.util.UnityAdsHelper.UNAVAILABLE_MESSAGE }
            )
        }
      )
    }
  }

  fun claimVerifiedWatchEarnReward(
    activity: android.app.Activity? = null,
    secondSessionId: String? = null,
    onSuccess: (Long) -> Unit = {}
  ) {
    val uid = currentUserId
    val currentFlow = _watchEarnUiState.value
    val sessionId = currentFlow.activeSessionId

    if (
      uid.isBlank() ||
        !currentFlow.isReadyToClaim ||
        currentFlow.isClaiming ||
        currentFlow.isLoadingAd ||
        _isBusy.value ||
        sessionId.isBlank()
    ) {
      return
    }

    val todayKey = getTodayDateKey()
    val name = (userProfileState.value as? UiState.Success)?.data?.displayName ?: "TaskFlow Member"
    val expectedCoins =
      if (currentFlow.lastRewardCoins > 0L) {
        currentFlow.lastRewardCoins
      } else {
        repository.getOrAssignWatchEarnSessionReward(sessionId)
      }

    val executeClaim: (String) -> Unit = { verifiedSecondSessionId ->
      if (!_isBusy.value && !_watchEarnUiState.value.isClaiming) {
        _isBusy.value = true
        val claimingMsg =
          if (expectedCoins > 0L) {
            "Adding +${formatCoins(expectedCoins)} to your wallet..."
          } else {
            "Crediting your Watch & Earn reward..."
          }
        _watchEarnUiState.value =
          currentFlow.copy(
            status = com.example.data.model.WatchEarnFlowStatus.CLAIMING,
            videosCompletedInCycle = 2,
            lastRewardCoins = expectedCoins,
            statusMessage = claimingMsg,
            errorMessage = null
          )

        viewModelScope.launch {
          try {
            repository
              .claimVerifiedWatchEarnSession(
                userId = uid,
                userName = name,
                sessionId = sessionId,
                secondSessionId = verifiedSecondSessionId,
                dateKey = todayKey
              )
              .onSuccess { earned ->
                _watchEarnUiState.value =
                  com.example.data.model.WatchEarnUiState(
                    status = com.example.data.model.WatchEarnFlowStatus.CLAIMED,
                    activeSessionId = "",
                    videosCompletedInCycle = 2,
                    lastRewardCoins = earned,
                    statusMessage = "Success! +${formatCoins(earned)} added to your wallet."
                  )
                _bannerMessage.value = "Watch & Earn reward claimed! +${formatCoins(earned)} credited."
                onSuccess(earned)
              }
              .onFailure { err ->
                val rawMsg = err.localizedMessage ?: "Failed to claim reward."
                _watchEarnUiState.value =
                  currentFlow.copy(
                    status = com.example.data.model.WatchEarnFlowStatus.ERROR,
                    lastRewardCoins = expectedCoins,
                    errorMessage = rawMsg
                  )
                _bannerMessage.value = rawMsg
              }
          } finally {
            _isBusy.value = false
          }
        }
      }
    }

    val cleanedPreVerifiedSecondId = secondSessionId?.trim()
    if (!cleanedPreVerifiedSecondId.isNullOrBlank()) {
      if (
        cleanedPreVerifiedSecondId == sessionId ||
          cleanedPreVerifiedSecondId.startsWith("ad_") ||
          cleanedPreVerifiedSecondId.startsWith("fallback_")
      ) {
        val msg = "A valid second S2S-verified reward session is required."
        _watchEarnUiState.value =
          currentFlow.copy(
            status = com.example.data.model.WatchEarnFlowStatus.READY_TO_CLAIM,
            videosCompletedInCycle = 1,
            lastRewardCoins = expectedCoins,
            errorMessage = msg
          )
        _bannerMessage.value = msg
        return
      }
      executeClaim(cleanedPreVerifiedSecondId)
      return
    }

    if (activity == null) {
      val msg = "Please watch and verify the second rewarded video to claim your coins."
      _watchEarnUiState.value =
        currentFlow.copy(
          status = com.example.data.model.WatchEarnFlowStatus.READY_TO_CLAIM,
          videosCompletedInCycle = 1,
          lastRewardCoins = expectedCoins,
          errorMessage = msg
        )
      _bannerMessage.value = msg
      return
    }

    val loadingMsg =
      if (expectedCoins > 0L) {
        "Loading video to claim +${formatCoins(expectedCoins)}..."
      } else {
        "Loading second verification video..."
      }
    _watchEarnUiState.value =
      currentFlow.copy(
        status = com.example.data.model.WatchEarnFlowStatus.LOADING_AD,
        videosCompletedInCycle = 1,
        lastRewardCoins = expectedCoins,
        statusMessage = loadingMsg,
        errorMessage = null
      )

    viewModelScope.launch {
      val secondSessionRes = repository.createWatchEarnClaimSession(uid)
      val secondId = secondSessionRes.getOrElse { err ->
        val msg = err.localizedMessage ?: "Unable to start second reward session."
        _watchEarnUiState.value =
          currentFlow.copy(
            status = com.example.data.model.WatchEarnFlowStatus.ERROR,
            lastRewardCoins = expectedCoins,
            errorMessage = msg
          )
        _bannerMessage.value = msg
        return@launch
      }

      com.example.util.UnityAdsHelper.loadAndShowRewardedAdForSession(
        activity = activity,
        sessionId = secondId,
        onAdShowing = {
          val showingMsg =
            if (expectedCoins > 0L) {
              "Watching video... +${formatCoins(expectedCoins)} will be added to your wallet automatically!"
            } else {
              "Watching verification video... Your reward will be added automatically!"
            }
          _watchEarnUiState.value =
            currentFlow.copy(
              status = com.example.data.model.WatchEarnFlowStatus.SHOWING_AD,
              videosCompletedInCycle = 1,
              lastRewardCoins = expectedCoins,
              statusMessage = showingMsg
            )
        },
        onVideoCompleted = {
          viewModelScope.launch {
            repository.verifyWatchEarnSessionCompleted(uid, secondId)
              .onSuccess {
                executeClaim(secondId)
              }
              .onFailure { err ->
                val rawErr = err.localizedMessage ?: "Second ad verification failed. Please try again."
                _watchEarnUiState.value =
                  currentFlow.copy(
                    status = com.example.data.model.WatchEarnFlowStatus.READY_TO_CLAIM,
                    videosCompletedInCycle = 1,
                    lastRewardCoins = expectedCoins,
                    errorMessage = rawErr
                  )
                _bannerMessage.value = rawErr
              }
          }
        },
        onFailed = { errorMsg ->
          _watchEarnUiState.value =
            currentFlow.copy(
              status = com.example.data.model.WatchEarnFlowStatus.READY_TO_CLAIM,
              videosCompletedInCycle = 1,
              lastRewardCoins = expectedCoins,
              errorMessage = errorMsg.ifBlank { com.example.util.UnityAdsHelper.UNAVAILABLE_MESSAGE },
              statusMessage = "Ad not completed (${errorMsg.ifBlank { "Ad unavailable" }}). Tap Claim to try again."
            )
        }
      )
    }
  }

  fun claimWatchEarnReward(
    activity: android.app.Activity? = null,
    secondSessionId: String? = null,
    onSuccess: (Long) -> Unit = {}
  ) {
    val currentFlow = _watchEarnUiState.value
    if (currentFlow.isReadyToClaim && currentFlow.activeSessionId.isNotBlank()) {
      claimVerifiedWatchEarnReward(
        activity = activity,
        secondSessionId = secondSessionId,
        onSuccess = onSuccess
      )
      return
    }
  }

  private val _spinEarnUiState = MutableStateFlow(SpinEarnUiState())
  val spinEarnUiState: StateFlow<SpinEarnUiState> = _spinEarnUiState.asStateFlow()

  fun updateSpinState(newState: SpinEarnUiState) {
    _spinEarnUiState.value = newState
  }

  /**
   * Starts a server/transaction-validated spin session BEFORE the wheel rotates.
   * Returns the exact [SpinSessionOutcome] (`segmentIndex`, `rewardCoins`, `sessionId`) so the
   * wheel animation stops on the exact segment chosen by the backend.
   */
  fun startSpinWithBackendOutcome(onOutcomeReady: (SpinSessionOutcome) -> Unit) {
    val uid = currentUserId
    if (uid.isBlank()) {
      _bannerMessage.value = "Please sign in to spin the wheel."
      return
    }
    val current = _spinEarnUiState.value
    if (current.isSpinning || current.isClaiming || current.isLoadingAd) return

    viewModelScope.launch {
      _isBusy.value = true
      _spinEarnUiState.value = current.copy(errorMessage = null, statusMessage = "Preparing spin...")
      repository
        .createSpinSessionWithOutcome(uid)
        .onSuccess { outcome ->
          _spinEarnUiState.value =
            _spinEarnUiState.value.copy(
              isSpinning = true,
              isReadyToClaim = false,
              activeSessionId = outcome.sessionId,
              selectedSegmentIndex = outcome.segmentIndex,
              selectedRewardCoins = outcome.rewardCoins,
              freeSpinAvailable = if (outcome.usedFreeSpin) false else _spinEarnUiState.value.freeSpinAvailable,
              extraSpinAvailable = if (!outcome.usedFreeSpin) false else _spinEarnUiState.value.extraSpinAvailable,
              extraSpinVideosWatched = if (!outcome.usedFreeSpin) 0 else _spinEarnUiState.value.extraSpinVideosWatched,
              adsWatchedForSpin = if (!outcome.usedFreeSpin) 0 else _spinEarnUiState.value.adsWatchedForSpin,
              statusMessage = "Spinning the wheel...",
              errorMessage = null
            )
          onOutcomeReady(outcome)
        }
        .onFailure { err ->
          val msg = err.localizedMessage ?: "Unable to start spin."
          _spinEarnUiState.value =
            _spinEarnUiState.value.copy(
              isSpinning = false,
              errorMessage = msg
            )
          _bannerMessage.value = msg
        }
      _isBusy.value = false
    }
  }

  fun onSpinWheelAnimationFinished() {
    val current = _spinEarnUiState.value
    if (current.activeSessionId.isNotBlank() && current.selectedRewardCoins != null) {
      _spinEarnUiState.value =
        current.copy(
          isSpinning = false,
          isReadyToClaim = true,
          statusMessage =
            "You landed on ${formatCoins(current.selectedRewardCoins)}! Tap Claim to credit your Coins."
        )
    } else {
      _spinEarnUiState.value = current.copy(isSpinning = false)
    }
  }

  /**
   * Plays 1 rewarded video toward unlocking an Extra Spin (requires 2 completed videos).
   */
  fun watchAdForExtraSpin(activity: android.app.Activity) {
    val uid = currentUserId
    if (uid.isBlank()) {
      _bannerMessage.value = "Please sign in to watch videos."
      return
    }
    val current = _spinEarnUiState.value
    if (current.isSpinning || current.isLoadingAd || current.isClaiming) return

    val nextVideoNumber = (current.extraSpinVideosWatched + 1).coerceAtMost(SpinWheelConstants.EXTRA_SPIN_REQUIRED_VIDEOS)
    _spinEarnUiState.value =
      current.copy(
        isLoadingAd = true,
        errorMessage = null,
        statusMessage =
          "Loading video ($nextVideoNumber/${SpinWheelConstants.EXTRA_SPIN_REQUIRED_VIDEOS})..."
      )

    viewModelScope.launch {
      repository.createExtraSpinUnlockSession(uid)
        .onSuccess { sessionId ->
          com.example.util.UnityAdsHelper.loadAndShowRewardedAdForSession(
            activity = activity,
            sessionId = sessionId,
            onAdShowing = {
              _spinEarnUiState.value =
                _spinEarnUiState.value.copy(
                  isLoadingAd = true,
                  statusMessage = "Watching video for Extra Spin..."
                )
            },
            onVideoCompleted = {
              viewModelScope.launch {
                repository.verifyWatchEarnSessionCompleted(uid, sessionId)
                  .onSuccess {
                    repository.recordExtraSpinAdWatch(sessionId)
                      .onSuccess { newCount ->
                        val unlocked = newCount >= SpinWheelConstants.EXTRA_SPIN_REQUIRED_VIDEOS
                        _spinEarnUiState.value =
                          _spinEarnUiState.value.copy(
                            isLoadingAd = false,
                            extraSpinVideosWatched = newCount,
                            adsWatchedForSpin = newCount,
                            extraSpinAvailable = unlocked,
                            statusMessage =
                              if (unlocked) {
                                "2 / 2 Videos Completed! Your Extra Spin is unlocked!"
                              } else {
                                "$newCount / ${SpinWheelConstants.EXTRA_SPIN_REQUIRED_VIDEOS} Video Completed! Watch 1 more video to unlock Extra Spin."
                              },
                            errorMessage = null
                          )
                      }
                      .onFailure { err ->
                        _spinEarnUiState.value =
                          _spinEarnUiState.value.copy(
                            isLoadingAd = false,
                            errorMessage = err.localizedMessage ?: "Could not record video progress."
                          )
                      }
                  }
                  .onFailure { err ->
                    _spinEarnUiState.value =
                      _spinEarnUiState.value.copy(
                        isLoadingAd = false,
                        errorMessage = err.localizedMessage ?: "Ad verification failed."
                      )
                  }
              }
            },
            onFailed = { errorMsg ->
              _spinEarnUiState.value =
                _spinEarnUiState.value.copy(
                  isLoadingAd = false,
                  errorMessage =
                    errorMsg.ifBlank { com.example.util.UnityAdsHelper.UNAVAILABLE_MESSAGE }
                )
            }
          )
        }
        .onFailure { err ->
          _spinEarnUiState.value =
            current.copy(
              isLoadingAd = false,
              errorMessage = err.localizedMessage ?: "Unable to start Extra Spin session."
            )
        }
    }
  }

  /**
   * Claims the pre-selected server-authoritative Spin reward directly via `claimSpinReward`.
   * Does not require an extra Claim Ad.
   */
  fun claimSpinRewardWithAd(
    activity: android.app.Activity?,
    onSuccess: (Long) -> Unit = {}
  ) {
    val current = _spinEarnUiState.value
    val sessionId = current.activeSessionId
    if (sessionId.isBlank() || current.isClaiming || current.isLoadingAd || _isBusy.value) return

    _isBusy.value = true
    _spinEarnUiState.value =
      _spinEarnUiState.value.copy(
        isLoadingAd = false,
        isClaiming = true,
        statusMessage = "Crediting your Spin reward...",
        errorMessage = null
      )
    viewModelScope.launch {
      try {
        repository
          .claimSpinReward(sessionId)
          .onSuccess { rewardCoins ->
            _spinEarnUiState.value =
              _spinEarnUiState.value.copy(
                isClaiming = false,
                isReadyToClaim = false,
                activeSessionId = "",
                selectedRewardCoins = null,
                selectedSegmentIndex = null,
                lastClaimedCoins = rewardCoins,
                statusMessage = "Success! +${formatCoins(rewardCoins)} credited to your wallet!",
                errorMessage = null
              )
            _bannerMessage.value = "Spin & Earn reward claimed: +${formatCoins(rewardCoins)}!"
            onSuccess(rewardCoins)
          }
          .onFailure { err ->
            val msg = err.localizedMessage ?: "Failed to claim spin reward."
            _spinEarnUiState.value =
              _spinEarnUiState.value.copy(
                isClaiming = false,
                errorMessage = msg
              )
            _bannerMessage.value = msg
          }
      } finally {
        _isBusy.value = false
      }
    }
  }

  fun createSpinSession(onSuccess: (String, Int) -> Unit) {
    startSpinWithBackendOutcome { outcome ->
      onSuccess(outcome.sessionId, outcome.segmentIndex)
    }
  }

  fun claimSpinReward(coins: Long, onSuccess: (Long) -> Unit = {}) {
    val activeSession = _spinEarnUiState.value.activeSessionId
    if (activeSession.isNotBlank()) {
      claimSpinRewardWithAd(activity = null, onSuccess = onSuccess)
    } else {
      val msg = "No active spin session found. Please spin the wheel first."
      _spinEarnUiState.value = _spinEarnUiState.value.copy(errorMessage = msg)
      _bannerMessage.value = msg
    }
  }

  fun claimSpinReward(sessionId: String, onSuccess: (Long) -> Unit) {
    val cleanedSessionId = sessionId.trim()
    val current = _spinEarnUiState.value
    if (cleanedSessionId.isBlank() || current.isClaiming || current.isLoadingAd || _isBusy.value) {
      return
    }
    _isBusy.value = true
    _spinEarnUiState.value = current.copy(isClaiming = true, errorMessage = null)
    viewModelScope.launch {
      repository
        .claimSpinReward(cleanedSessionId)
        .onSuccess { reward ->
          _spinEarnUiState.value =
            _spinEarnUiState.value.copy(
              isClaiming = false,
              isReadyToClaim = false,
              activeSessionId = "",
              selectedRewardCoins = null,
              selectedSegmentIndex = null,
              lastClaimedCoins = reward,
              statusMessage = "Success! +${formatCoins(reward)} credited to your wallet!",
              errorMessage = null
            )
          _bannerMessage.value = "Spin reward claimed: +$reward Coins"
          onSuccess(reward)
        }
        .onFailure { err ->
          val msg = err.localizedMessage ?: "Failed to claim spin reward."
          _spinEarnUiState.value =
            _spinEarnUiState.value.copy(
              isClaiming = false,
              errorMessage = msg
            )
          _bannerMessage.value = msg
        }
      _isBusy.value = false
    }
  }

  fun updateUserDisplayNameByAdmin(user: UserProfile, newName: String) {
    val uid = user.userId.ifBlank { user.id }
    val cleaned = newName.trim()
    if (cleaned.isEmpty() || cleaned.length > 100) {
      _bannerMessage.value = "Display name must be between 1 and 100 characters."
      return
    }
    viewModelScope.launch {
      _isBusy.value = true
      repository
        .updateUserDisplayNameByAdmin(uid, cleaned)
        .onSuccess {
          _bannerMessage.value = "User name updated to '$cleaned'."
        }
        .onFailure { err ->
          _bannerMessage.value = err.localizedMessage ?: "Failed to update user name."
        }
      _isBusy.value = false
    }
  }

  private val _appUpdateInfo = MutableStateFlow<com.example.data.model.AppVersionInfo?>(null)
  val appUpdateInfo: StateFlow<com.example.data.model.AppVersionInfo?> = _appUpdateInfo.asStateFlow()

  private val _showUpdateDialog = MutableStateFlow(false)
  val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

  private val _isCheckingUpdate = MutableStateFlow(false)
  val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

  fun checkForAppUpdates(context: android.content.Context, forceCheck: Boolean = false) {
    if (_isCheckingUpdate.value) return
    val currentStatus = _updateDownloadProgress.value.status
    // Do not interrupt an active download, permission flow, or installation with a redundant check
    if (
      !forceCheck &&
        (currentStatus == UpdateDownloadStatus.DOWNLOADING ||
          currentStatus == UpdateDownloadStatus.VERIFYING ||
          currentStatus == UpdateDownloadStatus.DOWNLOAD_COMPLETED ||
          currentStatus == UpdateDownloadStatus.READY_TO_INSTALL ||
          currentStatus == UpdateDownloadStatus.PERMISSION_REQUIRED ||
          currentStatus == UpdateDownloadStatus.INSTALLATION_STARTED ||
          currentStatus == UpdateDownloadStatus.WAITING_FOR_INSTALL_CONFIRMATION)
    ) {
      _showUpdateDialog.value = true
      return
    }

    viewModelScope.launch {
      _isCheckingUpdate.value = true
      com.example.util.AppUpdateManager.checkForUpdate(context)
        .onSuccess { updateInfo ->
          val installedCode = com.example.util.AppUpdateManager.getInstalledVersionCode(context)
          if (updateInfo != null && installedCode < updateInfo.versionCode) {
            val mandatoryUpdateInfo = updateInfo.copy(forceUpdate = true)
            _appUpdateInfo.value = mandatoryUpdateInfo
            _showUpdateDialog.value = true
            if (_updateDownloadProgress.value.status == UpdateDownloadStatus.IDLE) {
              _updateDownloadProgress.value =
                UpdateDownloadProgress(
                  status = UpdateDownloadStatus.UPDATE_AVAILABLE,
                  targetVersionCode = mandatoryUpdateInfo.versionCode,
                  targetVersionName = mandatoryUpdateInfo.versionName
                )
            }
          } else {
            // Installed version is >= latest_version_code
            _appUpdateInfo.value = null
            _showUpdateDialog.value = false
            if (forceCheck) {
              _bannerMessage.value =
                "TaskFlow is up to date (Version ${BuildConfig.VERSION_NAME})."
            }
          }
        }
        .onFailure { err ->
          if (forceCheck) {
            _bannerMessage.value =
              "Update check failed: ${err.localizedMessage ?: "Network error"}"
          }
        }
      _isCheckingUpdate.value = false
    }
  }

  private val _updateDownloadProgress = MutableStateFlow(UpdateDownloadProgress())
  val updateDownloadProgress: StateFlow<UpdateDownloadProgress> = _updateDownloadProgress.asStateFlow()

  fun dismissUpdateDialog(context: android.content.Context) {
    // Mandatory updates cannot be dismissed while installedVersionCode < latest_version_code
    val info = _appUpdateInfo.value
    val installedCode = com.example.util.AppUpdateManager.getInstalledVersionCode(context)
    if (
      info != null &&
        installedCode < info.versionCode &&
        !_updateDownloadProgress.value.isUpdateCompleted
    ) {
      _showUpdateDialog.value = true
      return
    }
    _showUpdateDialog.value = false
    _updateDownloadProgress.value = UpdateDownloadProgress()
  }

  fun downloadAndInstallUpdate(context: android.content.Context) {
    val info = _appUpdateInfo.value ?: return
    val trimmedUrl = info.apkUrl.trim()
    if (trimmedUrl.isBlank()) {
      _bannerMessage.value = "Update download URL is missing."
      return
    }

    if (!trimmedUrl.lowercase().startsWith("https://")) {
      _bannerMessage.value = "Insecure download URL: HTTPS is required."
      return
    }

    // If a valid downloaded APK for this target version already exists on disk, install directly without re-downloading
    if (com.example.util.AppUpdateManager.hasValidDownloadedApk(context, info.versionCode)) {
      triggerInstalledApkInstall(context)
      return
    }

    // Prevent duplicate downloads when already downloading or verifying
    if (_updateDownloadProgress.value.isDownloading || _updateDownloadProgress.value.isVerifying) {
      _bannerMessage.value = "Download is already in progress..."
      return
    }

    _showUpdateDialog.value = true
    _updateDownloadProgress.value =
      UpdateDownloadProgress(
        status = UpdateDownloadStatus.DOWNLOADING,
        progressPercent = 0,
        statusMessage = "Connecting and starting download...",
        targetVersionCode = info.versionCode,
        targetVersionName = info.versionName
      )

    val downloadId = com.example.util.AppUpdateManager.downloadAndInstallApk(context, trimmedUrl)
    if (downloadId <= 0L) {
      _updateDownloadProgress.value =
        UpdateDownloadProgress(
          status = UpdateDownloadStatus.FAILED,
          errorMessage = "Unable to start download. Check network and storage.",
          targetVersionCode = info.versionCode,
          targetVersionName = info.versionName
        )
      _bannerMessage.value = "Failed to start download."
      return
    }

    viewModelScope.launch {
      com.example.util.AppUpdateManager.pollDownloadProgress(
        context = context,
        downloadId = downloadId,
        onProgress = { progress ->
          _updateDownloadProgress.value =
            progress.copy(
              targetVersionCode = info.versionCode,
              targetVersionName = info.versionName
            )
          if (
            progress.status == UpdateDownloadStatus.DOWNLOAD_COMPLETED ||
              progress.status == UpdateDownloadStatus.READY_TO_INSTALL
          ) {
            triggerInstalledApkInstall(context)
          }
        }
      )
    }
  }

  fun triggerInstalledApkInstall(
    context: android.content.Context,
    openSettingsIfMissingPermission: Boolean = true
  ) {
    val info = _appUpdateInfo.value
    val targetCode = info?.versionCode ?: _updateDownloadProgress.value.targetVersionCode
    val targetName = info?.versionName ?: _updateDownloadProgress.value.targetVersionName

    _showUpdateDialog.value = true

    if (!com.example.util.AppUpdateManager.canRequestPackageInstalls(context)) {
      com.example.util.AppUpdateManager.recordPendingInstallation(
        context,
        if (targetCode > 0) targetCode else 1,
        targetName.ifBlank { "Latest" }
      )
      _updateDownloadProgress.value =
        _updateDownloadProgress.value.copy(
          status = UpdateDownloadStatus.PERMISSION_REQUIRED,
          statusMessage =
            "Install permission required. Please allow 'Install unknown apps' for TaskFlow in Settings.",
          targetVersionCode = targetCode,
          targetVersionName = targetName
        )
      if (openSettingsIfMissingPermission) {
        com.example.util.AppUpdateManager.openUnknownAppSourcesSettings(context)
      }
      return
    }

    _updateDownloadProgress.value =
      _updateDownloadProgress.value.copy(
        status = UpdateDownloadStatus.INSTALLATION_STARTED,
        statusMessage =
          "Android Package Installer launched. Please tap 'Update' in the system dialog to complete installation.",
        targetVersionCode = targetCode,
        targetVersionName = targetName
      )

    val success =
      com.example.util.AppUpdateManager.installDownloadedApk(
        context = context,
        targetCode = targetCode,
        targetName = targetName,
        openSettingsIfMissingPermission = openSettingsIfMissingPermission
      )
    if (!success) {
      if (!com.example.util.AppUpdateManager.canRequestPackageInstalls(context)) {
        _updateDownloadProgress.value =
          _updateDownloadProgress.value.copy(
            status = UpdateDownloadStatus.PERMISSION_REQUIRED,
            statusMessage =
              "Install permission required. Please allow 'Install unknown apps' in Settings."
          )
      } else {
        _updateDownloadProgress.value =
          _updateDownloadProgress.value.copy(
            status = UpdateDownloadStatus.FAILED,
            errorMessage = "Unable to start installation. Verification or FileProvider error."
          )
        _bannerMessage.value = "Unable to start installation. Verification error."
      }
    }
  }

  /**
   * Called on Activity ON_RESUME to handle:
   * 1. Post-installation version verification (installedVersionCode >= latest_version_code).
   * 2. Returning from Android "Install Unknown Apps" Settings: if permission is now granted,
   *    automatically transitions out of PERMISSION_REQUIRED and launches the APK installer
   *    without looping or re-downloading!
   * 3. Returning from a cancelled Package Installer prompt: keeps the mandatory update dialog
   *    active in READY_TO_INSTALL state so the user cannot bypass the required update.
   */
  fun verifyPostInstallation(context: android.content.Context) {
    val currentProgress = _updateDownloadProgress.value
    val result = com.example.util.AppUpdateManager.verifyInstalledVersionAfterUpdate(context)

    when (result) {
      is com.example.util.AppUpdateManager.UpdateVerificationResult.Success -> {
        _showUpdateDialog.value = true
        _updateDownloadProgress.value =
          UpdateDownloadProgress(
            status = UpdateDownloadStatus.UPDATE_COMPLETED,
            progressPercent = 100,
            statusMessage =
              "Update completed successfully! TaskFlow version ${result.installedName} (Build ${result.installedCode}) is now installed.",
            targetVersionCode = result.installedCode,
            targetVersionName = result.installedName
          )
        _bannerMessage.value = "TaskFlow updated to version ${result.installedName}!"
        return
      }
      is com.example.util.AppUpdateManager.UpdateVerificationResult.StillPending -> {
        // Case A: User was in PERMISSION_REQUIRED state and just returned from Settings
        if (currentProgress.isPermissionRequired) {
          if (com.example.util.AppUpdateManager.canRequestPackageInstalls(context)) {
            val dest = com.example.util.AppUpdateManager.getDestinationApkFile(context)
            if (dest.exists() && dest.length() > 0L) {
              // Permission is now granted! Automatically resume installation without asking again
              triggerInstalledApkInstall(context, openSettingsIfMissingPermission = false)
            } else {
              // APK was not yet downloaded; start download now that permission is granted
              downloadAndInstallUpdate(context)
            }
          }
          return
        }

        // Case B: User was in INSTALLATION_STARTED / WAITING_FOR_INSTALL_CONFIRMATION and returned (e.g. cancelled system dialog)
        if (currentProgress.isInstallationStarted || currentProgress.isWaitingForInstallConfirmation) {
          val dest = com.example.util.AppUpdateManager.getDestinationApkFile(context)
          val isApkReady = dest.exists() && dest.length() > 0L
          _showUpdateDialog.value = true
          _updateDownloadProgress.value =
            currentProgress.copy(
              status =
                if (isApkReady) UpdateDownloadStatus.DOWNLOAD_COMPLETED
                else UpdateDownloadStatus.FAILED,
              statusMessage =
                "Installation was not completed. Please tap 'Install Now' to complete the required update.",
              errorMessage =
                if (!isApkReady)
                  "Installation cancelled and package file missing. Please retry update."
                else null
            )
          return
        }
      }
      is com.example.util.AppUpdateManager.UpdateVerificationResult.NoPendingUpdate -> {
        // Even if NoPendingUpdate was recorded in prefs, check if UI was waiting on PERMISSION_REQUIRED
        if (currentProgress.isPermissionRequired && com.example.util.AppUpdateManager.canRequestPackageInstalls(context)) {
          val dest = com.example.util.AppUpdateManager.getDestinationApkFile(context)
          if (dest.exists() && dest.length() > 0L) {
            triggerInstalledApkInstall(context, openSettingsIfMissingPermission = false)
            return
          }
        }
        val currentCode = com.example.util.AppUpdateManager.getInstalledVersionCode(context)
        val prefs =
          context.getSharedPreferences("app_update_prefs", android.content.Context.MODE_PRIVATE)
        val lastKnownCode = prefs.getInt("last_known_installed_code", currentCode)
        if (currentCode > lastKnownCode) {
          prefs.edit().putInt("last_known_installed_code", currentCode).apply()
        }
      }
    }

    // Always re-verify Remote Config version requirement if no active update dialog is showing
    if (_appUpdateInfo.value == null) {
      checkForAppUpdates(context, forceCheck = false)
    }
  }

  fun requestUnknownAppSourcesPermission(context: android.content.Context) {
    val info = _appUpdateInfo.value
    val targetCode = info?.versionCode ?: _updateDownloadProgress.value.targetVersionCode
    val targetName = info?.versionName ?: _updateDownloadProgress.value.targetVersionName
    if (targetCode > 0) {
      com.example.util.AppUpdateManager.recordPendingInstallation(
        context,
        targetCode,
        targetName.ifBlank { "Latest" }
      )
    }
    if (com.example.util.AppUpdateManager.canRequestPackageInstalls(context)) {
      // Permission is already granted — proceed directly to install!
      triggerInstalledApkInstall(context, openSettingsIfMissingPermission = false)
    } else {
      com.example.util.AppUpdateManager.openUnknownAppSourcesSettings(context)
    }
  }

  fun resetUpdateDownloadProgress() {
    _updateDownloadProgress.value = UpdateDownloadProgress()
  }

  private val _openWithdrawOnWalletNavigation = MutableStateFlow(false)
  val openWithdrawOnWalletNavigation: StateFlow<Boolean> = _openWithdrawOnWalletNavigation.asStateFlow()

  private val _withdrawalRoute = MutableStateFlow(WithdrawalScreenRoute.NONE)
  val withdrawalRoute: StateFlow<WithdrawalScreenRoute> = _withdrawalRoute.asStateFlow()

  private val _lastSubmittedWithdrawal = MutableStateFlow<DemoWithdrawalRequest?>(null)
  val lastSubmittedWithdrawal: StateFlow<DemoWithdrawalRequest?> =
    _lastSubmittedWithdrawal.asStateFlow()

  fun setOpenWithdrawOnWalletNavigation(open: Boolean) {
    _openWithdrawOnWalletNavigation.value = open
    _withdrawalRoute.value =
      if (open) WithdrawalScreenRoute.MAIN else WithdrawalScreenRoute.NONE
  }

  fun openWithdrawalMain() {
    clearPendingWithdrawalAttempt()
    _withdrawalValidationError.value = null
    _activeUserTab.value = UserTab.WALLET
    _openWithdrawOnWalletNavigation.value = true
    _withdrawalRoute.value = WithdrawalScreenRoute.MAIN
  }

  fun openWithdrawalHistory() {
    clearPendingWithdrawalAttempt()
    _withdrawalValidationError.value = null
    _activeUserTab.value = UserTab.WALLET
    _withdrawalRoute.value = WithdrawalScreenRoute.HISTORY
  }

  fun navigateWithdrawal(route: WithdrawalScreenRoute) {
    clearPendingWithdrawalAttempt()
    _withdrawalValidationError.value = null
    _withdrawalRoute.value = route
    if (route == WithdrawalScreenRoute.NONE) {
      _openWithdrawOnWalletNavigation.value = false
    }
  }

  fun closeWithdrawalFlow() {
    clearPendingWithdrawalAttempt()
    _withdrawalValidationError.value = null
    _withdrawalRoute.value = WithdrawalScreenRoute.NONE
    _openWithdrawOnWalletNavigation.value = false
  }

  private val _isDailyCheckInScreenVisible = MutableStateFlow(false)
  val isDailyCheckInScreenVisible: StateFlow<Boolean> = _isDailyCheckInScreenVisible.asStateFlow()

  fun setDailyCheckInScreenVisible(visible: Boolean) {
    _isDailyCheckInScreenVisible.value = visible
  }

  private val _isSpinScreenVisible = MutableStateFlow(false)
  val isSpinScreenVisible: StateFlow<Boolean> = _isSpinScreenVisible.asStateFlow()

  fun setSpinScreenVisible(visible: Boolean) {
    _isSpinScreenVisible.value = visible
  }

  private val _isOfferWallScreenVisible = MutableStateFlow(false)
  val isOfferWallScreenVisible: StateFlow<Boolean> = _isOfferWallScreenVisible.asStateFlow()

  fun setOfferWallScreenVisible(visible: Boolean) {
    _isOfferWallScreenVisible.value = visible
  }


  // ==========================================
  // CAMPAIGNS (Client / Partner Management)
  // ==========================================

  fun createCampaign(
    title: String,
    clientName: String,
    description: String,
    budgetCents: Long,
    targetSubmissions: Long,
    category: String,
    rewardPerTaskCents: Long,
    startDate: String = "",
    endDate: String = "",
    eligibilityRules: String = "All Approved Users",
    appIconUrl: String = "",
    taskLink: String = "",
    onSuccess: () -> Unit = {}
  ) {
    if (!isAdminAuthorized.value) return
    viewModelScope.launch {
      _isBusy.value = true
      repository.createCampaign(
        title = title,
        clientName = clientName,
        description = description,
        budgetCents = budgetCents,
        targetSubmissions = targetSubmissions,
        category = category,
        rewardPerTaskCents = rewardPerTaskCents,
        startDate = startDate,
        endDate = endDate,
        eligibilityRules = eligibilityRules,
        appIconUrl = appIconUrl,
        taskLink = taskLink
      ).onSuccess {
        _bannerMessage.value = "Campaign '$title' launched successfully!"
        onSuccess()
      }.onFailure { err ->
        _bannerMessage.value = err.localizedMessage ?: "Failed to create campaign."
      }
      _isBusy.value = false
    }
  }

  fun updateCampaignDetails(
    campaignId: String,
    title: String,
    clientName: String,
    description: String,
    budgetCents: Long,
    targetSubmissions: Long,
    category: String,
    rewardPerTaskCents: Long,
    startDate: String = "",
    endDate: String = "",
    eligibilityRules: String = "All Approved Users",
    onSuccess: () -> Unit = {}
  ) {
    if (!isAdminAuthorized.value) return
    viewModelScope.launch {
      _isBusy.value = true
      repository.updateCampaignDetails(
        campaignId = campaignId,
        title = title,
        clientName = clientName,
        description = description,
        budgetCents = budgetCents,
        targetSubmissions = targetSubmissions,
        category = category,
        rewardPerTaskCents = rewardPerTaskCents,
        startDate = startDate,
        endDate = endDate,
        eligibilityRules = eligibilityRules
      ).onSuccess {
        _bannerMessage.value = "Campaign '$title' updated!"
        onSuccess()
      }.onFailure { err ->
        _bannerMessage.value = err.localizedMessage ?: "Failed to update campaign."
      }
      _isBusy.value = false
    }
  }

  fun updateCampaignStatus(campaignId: String, newStatus: String) {
    if (!isAdminAuthorized.value) return
    viewModelScope.launch {
      repository.updateCampaignStatus(campaignId, newStatus)
        .onSuccess {
          _bannerMessage.value = "Campaign status updated to $newStatus"
        }
        .onFailure { err ->
          _bannerMessage.value = err.localizedMessage ?: "Failed to update campaign status."
        }
    }
  }

  // ==========================================
  // IN-APP NOTIFICATIONS
  // ==========================================

  private val _showNotificationDialog = MutableStateFlow(false)
  val showNotificationDialog: StateFlow<Boolean> = _showNotificationDialog.asStateFlow()

  fun setNotificationDialogVisible(visible: Boolean) {
    _showNotificationDialog.value = visible
  }

  fun markNotificationRead(notificationId: String) {
    viewModelScope.launch {
      repository.markNotificationRead(currentUserId, notificationId)
    }
  }

  // ==========================================
  // SUPPORT TICKETS / INQUIRIES
  // ==========================================

  private val _showSupportTicketDialog = MutableStateFlow(false)
  val showSupportTicketDialog: StateFlow<Boolean> = _showSupportTicketDialog.asStateFlow()

  fun setSupportTicketDialogVisible(visible: Boolean) {
    _showSupportTicketDialog.value = visible
  }

  fun createSupportTicket(
    subject: String,
    message: String,
    category: String = "General",
    onSuccess: () -> Unit = {},
    onResult: (Boolean) -> Unit = {}
  ) {
    val name = (userProfileState.value as? UiState.Success)?.data?.displayName ?: currentUserDisplayName
    val email = currentUserEmail
    viewModelScope.launch {
      _isBusy.value = true
      repository.createSupportTicket(
        userId = currentUserId,
        userName = name,
        userEmail = email,
        subject = subject,
        message = message,
        category = category
      ).onSuccess {
        _bannerMessage.value = "Support ticket submitted. Our team will review it shortly."
        _showSupportTicketDialog.value = false
        onSuccess()
        onResult(true)
      }.onFailure { err ->
        _bannerMessage.value = err.localizedMessage ?: "Failed to submit ticket."
        onResult(false)
      }
      _isBusy.value = false
    }
  }

  fun updateSupportTicketStatus(
    ticketId: String,
    status: String = com.example.data.model.SupportTicketStatus.OPEN,
    notes: String = "",
    newStatus: String = status,
    adminReply: String = notes,
    onSuccess: () -> Unit = {},
    onResult: (Boolean) -> Unit = {}
  ) {
    if (!isAdminAuthorized.value) {
      onResult(false)
      return
    }
    val effectiveStatus = newStatus.ifBlank { status }
    val effectiveNotes = adminReply.ifBlank { notes }
    viewModelScope.launch {
      _isBusy.value = true
      repository.adminReplyToSupportTicket(ticketId, effectiveStatus, effectiveNotes)
        .onSuccess {
          val label = com.example.data.model.SupportTicketStatus.displayLabel(effectiveStatus)
          _bannerMessage.value =
            if (effectiveNotes.isNotBlank()) "Reply sent & ticket marked as $label."
            else "Ticket marked as $label."
          onSuccess()
          onResult(true)
        }
        .onFailure { err ->
          _bannerMessage.value = err.localizedMessage ?: "Failed to update support ticket."
          onResult(false)
        }
      _isBusy.value = false
    }
  }

  fun userReplySupportTicket(
    ticketId: String,
    replyMessage: String,
    onSuccess: () -> Unit = {},
    onResult: (Boolean) -> Unit = {}
  ) {
    val cleanMsg = replyMessage.trim()
    if (cleanMsg.isEmpty()) {
      _bannerMessage.value = "Please enter a reply message."
      onResult(false)
      return
    }
    val name = (userProfileState.value as? UiState.Success)?.data?.displayName ?: currentUserDisplayName
    viewModelScope.launch {
      _isBusy.value = true
      repository
        .userReplyToSupportTicket(
          ticketId = ticketId,
          userId = currentUserId,
          userName = name,
          replyMessage = cleanMsg
        )
        .onSuccess {
          _bannerMessage.value = "Follow-up message sent to Support."
          onSuccess()
          onResult(true)
        }
        .onFailure { err ->
          _bannerMessage.value = err.localizedMessage ?: "Failed to send message."
          onResult(false)
        }
      _isBusy.value = false
    }
  }

  // ==========================================
  // TELEGRAM JOIN REWARD, PROMO CODES & MANUAL BONUS
  // ==========================================

  @OptIn(ExperimentalCoroutinesApi::class)
  val telegramTasksState: StateFlow<List<TelegramTaskItem>> =
    isAdminAuthorized
      .flatMapLatest { authorized ->
        repository.observeTelegramTasks(authorized)
      }
      .catch { emit(emptyList()) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  @OptIn(ExperimentalCoroutinesApi::class)
  val telegramClaimsState: StateFlow<List<TelegramTaskClaimRecord>> =
    isAdminAuthorized
      .flatMapLatest { authorized ->
        repository.observeTelegramClaims(currentUserId, authorized)
      }
      .catch { emit(emptyList()) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  val telegramUserTaskStates: StateFlow<Map<String, TelegramUserTaskState>> =
    repository
      .observeTelegramUserTaskStates(currentUserId)
      .catch { emit(emptyMap()) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

  val telegramAccountStatus: StateFlow<TelegramAccountLinkStatus> =
    repository
      .observeTelegramAccountStatus(currentUserId)
      .catch { emit(TelegramAccountLinkStatus(userId = currentUserId)) }
      .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        TelegramAccountLinkStatus(userId = currentUserId)
      )

  private val _telegramTaskFeedback = MutableStateFlow<Map<String, TelegramUserTaskState>>(emptyMap())

  val telegramTaskUiStates: StateFlow<Map<String, TelegramUserTaskState>> =
    kotlinx.coroutines.flow.combine(
      telegramUserTaskStates,
      telegramClaimsState,
      _telegramTaskFeedback
    ) { docStates, claims, feedbackMap ->
      val claimedIds = claims.mapNotNull { it.taskId.takeIf { id -> id.isNotBlank() } }.toSet()
      val mergedKeys = docStates.keys + feedbackMap.keys + claimedIds
      buildMap {
        mergedKeys.forEach { tId ->
          val doc = docStates[tId]
          val fb = feedbackMap[tId]
          val claimed = (doc?.isClaimed == true) || (fb?.isClaimed == true) || claimedIds.contains(tId)
          val verified = (doc?.isMembershipVerified == true) || (fb?.isMembershipVerified == true) || claimed
          val hasJoined = (doc?.hasClickedJoin == true) || (fb?.hasClickedJoin == true) || verified || claimed
          put(
            tId,
            TelegramUserTaskState(
              taskId = tId,
              userId = currentUserId,
              hasClickedJoin = hasJoined,
              isMembershipVerified = verified,
              isClaimed = claimed,
              rewardCoinsClaimed = maxOf(doc?.rewardCoinsClaimed ?: 0L, fb?.rewardCoinsClaimed ?: 0L),
              statusMessage = fb?.statusMessage ?: doc?.statusMessage.orEmpty(),
              errorMessage = fb?.errorMessage ?: doc?.errorMessage
            )
          )
        }
      }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

  val nextTelegramTaskState: StateFlow<TelegramTaskItem?> =
    kotlinx.coroutines.flow.combine(
      telegramTasksState,
      telegramTaskUiStates,
      telegramClaimsState
    ) { tasks, userStates, claims ->
      selectNextTelegramTaskForUser(tasks, userStates, claims)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  @OptIn(ExperimentalCoroutinesApi::class)
  val promoCodesState: StateFlow<List<PromoCodeItem>> =
    isAdminAuthorized
      .flatMapLatest { authorized ->
        repository.observePromoCodes(authorized)
      }
      .catch { emit(emptyList()) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  @OptIn(ExperimentalCoroutinesApi::class)
  val manualBonusesState: StateFlow<List<ManualBonusRecord>> =
    isAdminAuthorized
      .flatMapLatest { authorized ->
        repository.observeManualBonuses(authorized)
      }
      .catch { emit(emptyList()) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  private val _promoRedemptionUiState = MutableStateFlow(PromoRedemptionUiState())
  val promoRedemptionUiState: StateFlow<PromoRedemptionUiState> = _promoRedemptionUiState.asStateFlow()

  private val _promoAdminFeedback = MutableStateFlow(PromoAdminUiFeedback())
  val promoAdminFeedback: StateFlow<PromoAdminUiFeedback> = _promoAdminFeedback.asStateFlow()

  private val _isPromoCodeScreenVisible = MutableStateFlow(false)
  val isPromoCodeScreenVisible: StateFlow<Boolean> = _isPromoCodeScreenVisible.asStateFlow()

  fun setPromoCodeScreenVisible(visible: Boolean) {
    _isPromoCodeScreenVisible.value = visible
    if (!visible) {
      clearPromoRedemptionFeedback()
    }
  }

  private val _isScratchCardScreenVisible = MutableStateFlow(false)
  val isScratchCardScreenVisible: StateFlow<Boolean> = _isScratchCardScreenVisible.asStateFlow()

  private val _scratchCardUiState = MutableStateFlow(ScratchCardUiState())
  val scratchCardUiState: StateFlow<ScratchCardUiState> = _scratchCardUiState.asStateFlow()

  private var scratchDailyObserverJob: kotlinx.coroutines.Job? = null

  fun setScratchCardScreenVisible(visible: Boolean) {
    _isScratchCardScreenVisible.value = visible
    if (visible) {
      loadScratchCardDailyState()
    } else {
      scratchDailyObserverJob?.cancel()
      scratchDailyObserverJob = null
      clearScratchFeedback()
    }
  }

  fun loadScratchCardDailyState() {
    if (currentUserId.isBlank()) return
    scratchDailyObserverJob?.cancel()
    scratchDailyObserverJob =
      viewModelScope.launch {
        repository.observeScratchDailyState(currentUserId).collect { daily ->
          _scratchCardUiState.update { current ->
            current.copy(dailyState = daily)
          }
          val snapshot = _scratchCardUiState.value
          if (daily.canScratchNow && snapshot.activeSession == null && !snapshot.isLoading) {
            prepareScratchCard()
          }
        }
      }
  }

  fun prepareScratchCard() {
    if (currentUserId.isBlank()) return
    if (_scratchCardUiState.value.isLoading) return
    viewModelScope.launch {
      _scratchCardUiState.update { it.copy(isLoading = true, errorMessage = null) }
      try {
        val session = repository.getOrCreateActiveScratchCard(currentUserId)
        _scratchCardUiState.update {
          it.copy(
            isLoading = false,
            activeSession = session,
            revealedCoins = session.rewardCoins,
            isScratched = (session.status == "SCRATCHED") || it.dailyState.activeCardIsScratched,
            showCongratulations = false,
            errorMessage = null
          )
        }
      } catch (e: Exception) {
        _scratchCardUiState.update {
          it.copy(
            isLoading = false,
            errorMessage = e.message ?: "Unable to prepare Scratch Card."
          )
        }
      }
    }
  }

  fun watchAdForScratchCard(activity: android.app.Activity) {
    val uid = currentUserId
    if (uid.isBlank()) {
      _scratchCardUiState.update { it.copy(errorMessage = "Please sign in to unlock Scratch Cards.") }
      return
    }
    if (_isBusy.value || _scratchCardUiState.value.isAdLoading) return
    _scratchCardUiState.update { it.copy(isAdLoading = true, errorMessage = null) }

    viewModelScope.launch {
      val sessionResult = repository.createScratchCardAdSession(uid)
      val sessionId = sessionResult.getOrElse { err ->
        _scratchCardUiState.update {
          it.copy(
            isAdLoading = false,
            errorMessage = err.localizedMessage ?: "Failed to start ad session."
          )
        }
        return@launch
      }

      UnityAdsHelper.loadAndShowRewardedAdForSession(
        activity = activity,
        sessionId = sessionId,
        onAdShowing = {
          _scratchCardUiState.update { it.copy(isAdLoading = false) }
        },
        onVideoCompleted = {
          viewModelScope.launch {
            _scratchCardUiState.update { it.copy(isAdLoading = true, errorMessage = null) }
            val verifyResult = repository.verifyAndRecordScratchAdWatched(uid, sessionId)
            verifyResult
              .onSuccess { result ->
                val feedbackMsg = if (result.newlyUnlocked || result.unlockedScratchesCount > 0L) {
                  "You unlocked a new Scratch Card!"
                } else {
                  "1 of 2 videos watched! Watch 1 more to unlock your scratch card."
                }
                _scratchCardUiState.update {
                  it.copy(
                    isAdLoading = false,
                    dailyState = it.dailyState.copy(
                      adsWatchedForNextScratch = result.adsWatchedForNextScratch,
                      unlockedScratchesCount = result.unlockedScratchesCount
                    ),
                    successMessage = feedbackMsg
                  )
                }
                if (result.newlyUnlocked || result.unlockedScratchesCount > 0L) {
                  prepareScratchCard()
                }
              }
              .onFailure { err ->
                _scratchCardUiState.update {
                  it.copy(
                    isAdLoading = false,
                    errorMessage = err.localizedMessage ?: "Reward video completion has not been verified by server yet."
                  )
                }
              }
          }
        },
        onFailed = { err ->
          _scratchCardUiState.update {
            it.copy(
              isAdLoading = false,
              errorMessage = err.ifBlank { "Video is currently unavailable. Please try again." }
            )
          }
        }
      )
    }
  }

  fun onScratchRevealed() {
    val session = _scratchCardUiState.value.activeSession ?: return
    _scratchCardUiState.update {
      it.copy(isScratched = true, showCongratulations = true)
    }
    viewModelScope.launch {
      repository.markScratchCardScratched(session.cardId)
    }
  }

  fun claimScratchCardReward() {
    val session = _scratchCardUiState.value.activeSession ?: return
    if (_scratchCardUiState.value.isClaiming) return
    viewModelScope.launch {
      _scratchCardUiState.update { it.copy(isClaiming = true, errorMessage = null) }
      try {
        val coins = repository.claimScratchCardReward(session.cardId)
        var shouldPrepareNextCard = false
        _scratchCardUiState.update { current ->
          val prevDaily = current.dailyState
          val nextDaily =
            prevDaily.copy(
              scratchesCompletedToday = prevDaily.scratchesCompletedToday + 1L,
              hasUsedFreeScratchToday = true,
              activeCardId = "",
              activeCardRewardCoins = 0L,
              activeCardIsScratched = false,
              activeCardIsClaimed = true
            )
          shouldPrepareNextCard =
            !nextDaily.isDailyLimitReached && nextDaily.unlockedScratchesCount > 0L
          current.copy(
            isClaiming = false,
            showCongratulations = false,
            activeSession = null,
            isScratched = false,
            revealedCoins = 0L,
            dailyState = nextDaily,
            errorMessage = null,
            successMessage = "Successfully claimed +${formatCoins(coins)}!"
          )
        }
        if (shouldPrepareNextCard) {
          prepareScratchCard()
        }
      } catch (e: Exception) {
        _scratchCardUiState.update {
          it.copy(isClaiming = false, errorMessage = e.message ?: "Failed to claim reward.")
        }
      }
    }
  }

  fun dismissScratchCongratulations() {
    _scratchCardUiState.update { it.copy(showCongratulations = false) }
  }

  fun clearScratchFeedback() {
    _scratchCardUiState.update { it.copy(errorMessage = null, successMessage = null) }
  }

  fun clearPromoRedemptionFeedback() {
    _promoRedemptionUiState.value =
      _promoRedemptionUiState.value.copy(
        successMessage = null,
        errorMessage = null
      )
  }

  fun clearPromoAdminFeedback() {
    _promoAdminFeedback.update {
      it.copy(successMessage = null, errorMessage = null)
    }
  }

  fun recordTelegramTaskJoinClick(taskId: String) {
    val cleanTaskId = taskId.trim()
    if (cleanTaskId.isEmpty() || currentUserId.isBlank()) return
    val currentState =
      telegramTaskUiStates.value[cleanTaskId]
        ?: TelegramUserTaskState(taskId = cleanTaskId, userId = currentUserId)
    _telegramTaskFeedback.update { map ->
      map + (cleanTaskId to currentState.copy(
        hasClickedJoin = true,
        isMembershipVerified = currentState.isMembershipVerified,
        statusMessage =
          if (currentState.isMembershipVerified) currentState.statusMessage
          else "Telegram opened. Join the channel, then return here and tap VERIFY.",
        errorMessage = null
      ))
    }
    viewModelScope.launch {
      repository.markTelegramTaskJoinClicked(currentUserId, cleanTaskId)
    }
  }

  fun verifyTelegramTask(
    taskId: String,
    onOpenBotLink: (String) -> Unit = {},
    onSuccess: (TelegramVerifyResult) -> Unit = {}
  ) {
    if (_isBusy.value) return
    val cleanTaskId = taskId.trim()
    if (cleanTaskId.isEmpty()) return
    _isBusy.value = true
    viewModelScope.launch {
      try {
        repository
          .verifyTelegramTaskMembership(currentUserId, cleanTaskId)
          .onSuccess { result ->
            val currentState = telegramTaskUiStates.value[cleanTaskId] ?: TelegramUserTaskState(taskId = cleanTaskId, userId = currentUserId)
            if (result.verified) {
              val msg = result.message.ifBlank { "Telegram membership verified! Tap CLAIM to collect your coins." }
              _telegramTaskFeedback.update { map ->
                map + (cleanTaskId to currentState.copy(
                  hasClickedJoin = true,
                  isMembershipVerified = true,
                  statusMessage = msg,
                  errorMessage = null
                ))
              }
              _bannerMessage.value = msg
              onSuccess(result)
            } else if (result.needsAccountLink) {
              val msg = result.message.ifBlank { "Open the Telegram bot, tap Start, then tap VERIFY again." }
              _telegramTaskFeedback.update { map ->
                map + (cleanTaskId to currentState.copy(
                  isMembershipVerified = false,
                  statusMessage = msg,
                  errorMessage = null
                ))
              }
              _bannerMessage.value = msg
              if (result.botLinkUrl.isNotBlank()) {
                onOpenBotLink(result.botLinkUrl)
              }
            } else {
              val msg = result.message.ifBlank { "Please tap JOIN and join the Telegram channel first, then tap VERIFY." }
              _telegramTaskFeedback.update { map ->
                map + (cleanTaskId to currentState.copy(
                  isMembershipVerified = false,
                  statusMessage = "",
                  errorMessage = msg
                ))
              }
              _bannerMessage.value = msg
            }
          }
          .onFailure { err ->
            val currentState = telegramTaskUiStates.value[cleanTaskId] ?: TelegramUserTaskState(taskId = cleanTaskId, userId = currentUserId)
            val msg = err.localizedMessage?.takeIf { it.isNotBlank() }
              ?: "Telegram membership verification failed. Please join the channel first."
            _telegramTaskFeedback.update { map ->
              map + (cleanTaskId to currentState.copy(
                isMembershipVerified = false,
                statusMessage = "",
                errorMessage = msg
              ))
            }
            _bannerMessage.value = msg
          }
      } finally {
        _isBusy.value = false
      }
    }
  }

  fun claimTelegramTask(
    taskId: String,
    onSuccess: (Long) -> Unit = {}
  ) {
    if (_isBusy.value) return
    val cleanTaskId = taskId.trim()
    if (cleanTaskId.isEmpty()) return
    val currentState = telegramTaskUiStates.value[cleanTaskId] ?: TelegramUserTaskState(taskId = cleanTaskId, userId = currentUserId)
    if (currentState.isClaimed) {
      _bannerMessage.value = "You have already claimed this Telegram task."
      return
    }
    if (!currentState.isMembershipVerified) {
      val msg = "Please join the channel and tap VERIFY before claiming."
      _telegramTaskFeedback.update { map ->
        map + (cleanTaskId to currentState.copy(errorMessage = msg))
      }
      _bannerMessage.value = msg
      return
    }

    _isBusy.value = true
    viewModelScope.launch {
      try {
        repository
          .claimTelegramTaskReward(currentUserId, cleanTaskId)
          .onSuccess { creditedCoins ->
            _telegramTaskFeedback.update { map ->
              map + (cleanTaskId to currentState.copy(
                isMembershipVerified = true,
                isClaimed = true,
                rewardCoinsClaimed = creditedCoins,
                statusMessage = "Success! +${formatCoins(creditedCoins)} credited to your wallet.",
                errorMessage = null
              ))
            }
            _bannerMessage.value =
              "Telegram Join Reward claimed! +${formatCoins(creditedCoins)} added to your wallet."
            onSuccess(creditedCoins)
          }
          .onFailure { err ->
            val msg = err.localizedMessage?.takeIf { it.isNotBlank() } ?: "Failed to claim Telegram task reward."
            _telegramTaskFeedback.update { map ->
              map + (cleanTaskId to currentState.copy(errorMessage = msg))
            }
            _bannerMessage.value = msg
          }
      } finally {
        _isBusy.value = false
      }
    }
  }

  fun redeemPromoCode(
    rawCode: String,
    onSuccess: (PromoRedemptionRecord) -> Unit = {}
  ) {
    if (_isBusy.value || _promoRedemptionUiState.value.isRedeeming) return
    val cleaned = rawCode.trim()
    if (cleaned.isEmpty()) {
      _promoRedemptionUiState.value =
        PromoRedemptionUiState(
          codeInput = rawCode,
          isRedeeming = false,
          errorMessage = "Please enter a promo code."
        )
      return
    }
    if (currentUserId.isBlank()) {
      _promoRedemptionUiState.value =
        PromoRedemptionUiState(
          codeInput = cleaned,
          isRedeeming = false,
          errorMessage = "Please sign in to redeem promo codes."
        )
      return
    }

    _isBusy.value = true
    _promoRedemptionUiState.value =
      PromoRedemptionUiState(
        codeInput = cleaned,
        isRedeeming = true,
        successMessage = null,
        errorMessage = null
      )
    viewModelScope.launch {
      try {
        repository
          .redeemPromoCode(rawCode = cleaned)
          .onSuccess { record ->
            val msg =
              "Promo code '${record.code}' redeemed! +${formatCoins(record.rewardCoins)} credited to your wallet."
            _promoRedemptionUiState.value =
              PromoRedemptionUiState(
                codeInput = "",
                isRedeeming = false,
                lastRedeemedCode = record.code,
                lastCreditedCoins = record.rewardCoins,
                successMessage = msg,
                errorMessage = null
              )
            _bannerMessage.value = msg
            onSuccess(record)
          }
          .onFailure { err ->
            val msg = err.localizedMessage?.takeIf { it.isNotBlank() } ?: "Invalid or expired promo code."
            _promoRedemptionUiState.value =
              PromoRedemptionUiState(
                codeInput = cleaned,
                isRedeeming = false,
                successMessage = null,
                errorMessage = msg
              )
          }
      } finally {
        if (_promoRedemptionUiState.value.isRedeeming) {
          _promoRedemptionUiState.update { it.copy(isRedeeming = false) }
        }
        _isBusy.value = false
      }
    }
  }

  fun adminCreateTelegramTask(
    title: String,
    channelUrl: String,
    channelChatId: String = "",
    rewardCoins: Long,
    isActive: Boolean = true,
    onSuccess: (TelegramTaskItem) -> Unit = {}
  ) {
    if (!isAdminAuthorized.value) {
      _bannerMessage.value = "Only authorized administrators can create Telegram tasks."
      return
    }
    if (_isBusy.value) return
    _isBusy.value = true
    viewModelScope.launch {
      try {
        repository
          .createTelegramTask(
            title = title,
            channelUrl = channelUrl,
            channelChatId = channelChatId,
            rewardCoins = rewardCoins,
            isActive = isActive
          )
          .onSuccess { created ->
            _bannerMessage.value =
              "Telegram Join Task '${created.title}' created (+${formatCoins(created.rewardCoins)})."
            onSuccess(created)
          }
          .onFailure { err ->
            _bannerMessage.value =
              err.localizedMessage ?: "Failed to create Telegram Join Task."
          }
      } finally {
        _isBusy.value = false
      }
    }
  }

  fun adminToggleTelegramTaskActive(taskId: String, isActive: Boolean) {
    if (!isAdminAuthorized.value) {
      _bannerMessage.value = "Only authorized administrators can update Telegram tasks."
      return
    }
    if (_isBusy.value) return
    _isBusy.value = true
    viewModelScope.launch {
      try {
        repository
          .setTelegramTaskActiveStatus(taskId = taskId, isActive = isActive)
          .onSuccess { active ->
            _bannerMessage.value =
              "Telegram task marked as ${if (active) "Active" else "Inactive"}."
          }
          .onFailure { err ->
            _bannerMessage.value =
              err.localizedMessage ?: "Failed to update Telegram task status."
          }
      } finally {
        _isBusy.value = false
      }
    }
  }

  fun adminCreatePromoCode(
    rawCode: String,
    rewardCoins: Long,
    maxSlots: Long,
    expiresAtMillis: Long,
    expiresAtLabel: String = "",
    isActive: Boolean = true,
    onSuccess: () -> Unit = {}
  ) {
    if (!isAdminAuthorized.value) {
      val msg = "Only authorized administrators can create promo codes."
      _bannerMessage.value = msg
      _promoAdminFeedback.update { it.copy(errorMessage = msg, successMessage = null) }
      return
    }
    if (_isBusy.value || _promoAdminFeedback.value.isLoading) return
    _isBusy.value = true
    _promoAdminFeedback.update {
      it.copy(isLoading = true, successMessage = null, errorMessage = null)
    }
    viewModelScope.launch {
      try {
        repository
          .createPromoCode(
            rawCode = rawCode,
            rewardCoins = rewardCoins,
            maxSlots = maxSlots,
            expiresAtMillis = expiresAtMillis,
            expiresAtLabel = expiresAtLabel,
            isActive = isActive
          )
          .onSuccess { created ->
            val msg =
              "Promo code '${created.code}' created (+${formatCoins(created.rewardCoins)}, ${created.maxSlots} slots, ${created.status})."
            _bannerMessage.value = msg
            _promoAdminFeedback.value =
              PromoAdminUiFeedback(
                isLoading = false,
                lastCreatedCode = created.code,
                successMessage = msg,
                errorMessage = null
              )
            onSuccess()
          }
          .onFailure { err ->
            val msg =
              err.localizedMessage?.takeIf { it.isNotBlank() } ?: "Failed to create promo code."
            _bannerMessage.value = msg
            _promoAdminFeedback.update {
              it.copy(isLoading = false, successMessage = null, errorMessage = msg)
            }
          }
      } finally {
        _promoAdminFeedback.update { it.copy(isLoading = false) }
        _isBusy.value = false
      }
    }
  }

  fun adminTogglePromoCodeActive(promoId: String, isActive: Boolean) {
    if (!isAdminAuthorized.value) {
      val msg = "Only authorized administrators can modify promo codes."
      _bannerMessage.value = msg
      _promoAdminFeedback.update { it.copy(errorMessage = msg, successMessage = null) }
      return
    }
    if (_isBusy.value || _promoAdminFeedback.value.isLoading) return
    _isBusy.value = true
    _promoAdminFeedback.update {
      it.copy(isLoading = true, successMessage = null, errorMessage = null)
    }
    viewModelScope.launch {
      try {
        repository
          .setPromoCodeActiveStatus(promoId, isActive)
          .onSuccess { updated ->
            val statusLabel = if (updated.isActive) "Active" else "Inactive"
            val msg = "Promo code '${updated.promoId}' marked as $statusLabel."
            _bannerMessage.value = msg
            _promoAdminFeedback.update {
              it.copy(isLoading = false, successMessage = msg, errorMessage = null)
            }
          }
          .onFailure { err ->
            val msg =
              err.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "Failed to update promo code status."
            _bannerMessage.value = msg
            _promoAdminFeedback.update {
              it.copy(isLoading = false, successMessage = null, errorMessage = msg)
            }
          }
      } finally {
        _promoAdminFeedback.update { it.copy(isLoading = false) }
        _isBusy.value = false
      }
    }
  }

  private var pendingManualBonusSignature: String? = null
  private var pendingManualBonusKey: String? = null

  fun adminCreditManualBonus(
    targetUserId: String,
    amountCoins: Long,
    reason: String = "",
    explicitIdempotencyKey: String? = null,
    onSuccess: () -> Unit = {}
  ) {
    if (!isAdminAuthorized.value) {
      _bannerMessage.value = "Only authorized administrators can send manual bonuses."
      return
    }
    if (_isBusy.value) return
    val cleanTarget = targetUserId.trim()
    if (cleanTarget.isEmpty()) {
      _bannerMessage.value = "Please select a registered user."
      return
    }
    if (amountCoins <= 0L) {
      _bannerMessage.value = "Please enter a valid bonus amount greater than 0 Coins."
      return
    }
    val signature = "$currentUserId|$cleanTarget|$amountCoins|${reason.trim()}"
    val resolvedKey =
      explicitIdempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
        ?: UUID.randomUUID().toString().replace("-", "").also { generated ->
          pendingManualBonusSignature = signature
          pendingManualBonusKey = generated
        }

    _isBusy.value = true
    viewModelScope.launch {
      try {
        repository
          .creditAdminManualBonus(
            targetUserId = cleanTarget,
            amountCoins = amountCoins,
            reason = reason,
            idempotencyKey = resolvedKey
          )
          .onSuccess { record ->
            pendingManualBonusSignature = null
            pendingManualBonusKey = null
            _bannerMessage.value =
              "Credited +${formatCoins(record.amountCoins)} manual bonus to ${record.targetUserName}!"
            onSuccess()
          }
          .onFailure { err ->
            pendingManualBonusSignature = null
            pendingManualBonusKey = null
            _bannerMessage.value = err.localizedMessage ?: "Failed to credit manual bonus."
          }
      } finally {
        _isBusy.value = false
      }
    }
  }
}

