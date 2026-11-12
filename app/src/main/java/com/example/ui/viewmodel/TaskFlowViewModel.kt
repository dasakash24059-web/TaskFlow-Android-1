package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.model.BOOTSTRAPPED_ADMIN_EMAIL
import com.example.data.model.CampaignItem
import com.example.data.model.DailyCheckInConstants
import com.example.data.model.DailyCheckInState
import com.example.data.model.DemoWithdrawalRequest
import com.example.data.model.MIN_WITHDRAWAL_CENTS
import com.example.data.model.MIN_WITHDRAWAL_COINS
import com.example.data.model.NotificationItem
import com.example.data.model.SpinEarnUiState
import com.example.data.model.SpinSessionOutcome
import com.example.data.model.SpinWheelConstants
import com.example.data.model.AppItem
import com.example.data.model.SubmissionItem
import com.example.data.model.SupportTicket
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.TaskStep
import com.example.data.model.TransactionRecord
import com.example.data.model.UserProfile
import com.example.data.model.UserTaskProgress
import com.example.data.model.UpdateDownloadProgress
import com.example.data.model.UpdateDownloadStatus
import com.example.data.model.WatchStats
import com.example.data.model.WithdrawalMethod
import com.example.data.model.calculateDailyCheckInState
import com.example.data.model.centsToCoins
import com.example.data.model.coinsToCents
import com.example.data.model.formatCoins
import com.example.data.model.formatRewardInr
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
  TASKS,
  SUBMISSIONS,
  USERS,
  WITHDRAWALS,
  CAMPAIGNS
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

  private val _activeAdminTab = MutableStateFlow(AdminTab.TASKS)
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

  fun submitDemoWithdrawal(
    amountInrInput: String,
    method: String,
    accountHolderName: String = "",
    bankName: String = "",
    accountNumber: String = "",
    ifscCode: String = "",
    upiId: String = "",
    onSuccess: () -> Unit = {}
  ) {
    _withdrawalValidationError.value = null
    val cleanedMethod = method.trim()
    if (cleanedMethod !in WithdrawalMethod.ALL) {
      val msg = "Please select a withdrawal method (Bank Account or UPI ID)."
      _withdrawalValidationError.value = msg
      return
    }

    if (cleanedMethod == WithdrawalMethod.BANK_ACCOUNT) {
      if (accountHolderName.isBlank() ||
        bankName.isBlank() ||
        accountNumber.isBlank() ||
        ifscCode.isBlank()
      ) {
        val msg =
          "Please fill in Account Holder Name, Bank Name, Account Number, and IFSC Code."
        _withdrawalValidationError.value = msg
        return
      }
    } else if (cleanedMethod == WithdrawalMethod.UPI_ID) {
      if (upiId.isBlank() || !upiId.contains("@")) {
        val msg = "Please enter a valid UPI ID (e.g., name@upi)."
        _withdrawalValidationError.value = msg
        return
      }
    }

    val cleanedInput = amountInrInput.trim().replace(",", "")
    val parsedValue = cleanedInput.toDoubleOrNull()
    if (parsedValue == null || parsedValue <= 0.0) {
      val msg = "Please enter a valid withdrawal amount in Coins (Minimum: 25,000 Coins)."
      _withdrawalValidationError.value = msg
      return
    }

    // Support both Coin input (>= 25,000 Coins) and legacy INR test input (200..24999)
    val amountCents =
      if (parsedValue >= MIN_WITHDRAWAL_COINS.toDouble()) {
        coinsToCents(parsedValue.roundToLong())
      } else {
        (parsedValue * 100.0).roundToLong()
      }
    val requestedCoins = centsToCoins(amountCents)

    if (amountCents < MIN_WITHDRAWAL_CENTS) {
      val msg = "Minimum withdrawal amount is 25,000 Coins."
      _withdrawalValidationError.value = msg
      return
    }

    val profile = (userProfileState.value as? UiState.Success)?.data
    val availableBalanceCents = profile?.balanceCents ?: 0L
    val availableEligibleCoins = profile?.getEligibleWithdrawalCoins() ?: 0L
    if (amountCents > availableBalanceCents) {
      val msg =
        "Entered amount (${formatCoins(requestedCoins)}) exceeds your eligible withdrawable balance of ${formatCoins(availableEligibleCoins)}."
      _withdrawalValidationError.value = msg
      return
    }

    val resolvedName =
      profile?.displayName?.ifBlank { currentUserDisplayName } ?: currentUserDisplayName

    // Sensitive demo inputs (accountNumber, ifscCode, bankName, upiId) are intentionally NOT
    // passed to the repository or stored in Firestore.
    viewModelScope.launch {
      _isBusy.value = true
      repository
        .submitDemoWithdrawalRequest(
          uid = currentUserId,
          userName = resolvedName,
          amountCents = amountCents,
          method = cleanedMethod
        )
        .onSuccess {
          _withdrawalValidationError.value = null
          _bannerMessage.value =
            "Withdrawal request of ${formatCoins(requestedCoins)} via $cleanedMethod submitted!"
          onSuccess()
        }
        .onFailure { err ->
          val msg = err.localizedMessage ?: "Could not submit withdrawal request."
          _withdrawalValidationError.value = msg
          _bannerMessage.value = msg
        }
      _isBusy.value = false
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
    referenceImages: List<String> = emptyList()
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
          referenceImages = referenceImages
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
    referenceImages: List<String> = emptyList()
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
          referenceImages = referenceImages
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

    val rewardCoins = DailyCheckInConstants.getRewardCoinsForDay(targetStreak)
    val todayKey = com.example.data.model.getTodayDateKey()
    val uid = currentUserId
    val name = (userProfileState.value as? UiState.Success)?.data?.displayName ?: "TaskFlow Member"

    if (uid.isBlank()) {
      _bannerMessage.value = "Please sign in to claim daily check-in rewards."
      return
    }

    if (_isBusy.value) {
      return
    }

    val executeClaim: () -> Unit = {
      viewModelScope.launch {
        _isBusy.value = true
        repository
          .claimDailyCheckIn(
            userId = uid,
            streakDay = targetStreak,
            rewardCents = rewardCoins,
            dateKey = todayKey,
            userName = name,
            verifiedAdCallbackToken = null
          )
          .onSuccess { earned ->
            _bannerMessage.value =
              "Success! Day $targetStreak check-in claimed: +${formatCoins(earned)}"
            onSuccess(earned)
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
        _isBusy.value = false
      }
    }

    if (activity != null) {
      _isBusy.value = true
      val adSessionId = "checkin_${uid}_${todayKey}_${UUID.randomUUID()}"
      com.example.util.UnityAdsHelper.loadAndShowRewardedAdForSession(
        activity = activity,
        sessionId = adSessionId,
        onAdShowing = {
          _bannerMessage.value = "Watching video to unlock Day $targetStreak check-in reward..."
        },
        onVideoCompleted = {
          _isBusy.value = false
          executeClaim()
        },
        onFailed = { errMsg ->
          _isBusy.value = false
          _bannerMessage.value =
            errMsg.ifBlank { com.example.util.UnityAdsHelper.UNAVAILABLE_MESSAGE }
        }
      )
    } else {
      executeClaim()
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
          val msg = err.localizedMessage ?: ""
          if (
            msg.contains("Daily limit", ignoreCase = true) ||
              msg.contains("sign in", ignoreCase = true) ||
              msg.contains("approved", ignoreCase = true)
          ) {
            _watchEarnUiState.value =
              com.example.data.model.WatchEarnUiState(
                status = com.example.data.model.WatchEarnFlowStatus.ERROR,
                errorMessage = msg
              )
            return@launch
          }
          "ad_${UUID.randomUUID().toString().replace("-", "")}"
        }

      com.example.util.UnityAdsHelper.loadAndShowRewardedAdForSession(
        activity = activity,
        sessionId = sessionId,
        onAdShowing = {
          _watchEarnUiState.value =
            com.example.data.model.WatchEarnUiState(
              status = com.example.data.model.WatchEarnFlowStatus.SHOWING_AD,
              activeSessionId = sessionId,
              videosCompletedInCycle = 0,
              statusMessage = "Watching video... Please watch until the end."
            )
        },
        onVideoCompleted = {
          viewModelScope.launch {
            repository
              .verifyWatchEarnSessionCompleted(uid, sessionId)
              .onSuccess {
                _watchEarnUiState.value =
                  com.example.data.model.WatchEarnUiState(
                    status = com.example.data.model.WatchEarnFlowStatus.READY_TO_CLAIM,
                    activeSessionId = sessionId,
                    videosCompletedInCycle = 1,
                    statusMessage = "Video completed! Tap Claim below to watch the final ad and collect your Coins."
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
    onSuccess: (Long) -> Unit = {}
  ) {
    val uid = currentUserId
    val currentFlow = _watchEarnUiState.value
    val sessionId = currentFlow.activeSessionId

    if (uid.isBlank() || !currentFlow.isReadyToClaim || currentFlow.isClaiming || sessionId.isBlank()) {
      return
    }

    val todayKey = getTodayDateKey()
    val name = (userProfileState.value as? UiState.Success)?.data?.displayName ?: "TaskFlow Member"

    val finalizeRewardCredit: () -> Unit = {
      _watchEarnUiState.value =
        currentFlow.copy(
          status = com.example.data.model.WatchEarnFlowStatus.CLAIMING,
          videosCompletedInCycle = 2,
          statusMessage = "Crediting your 1–200 Coins...",
          errorMessage = null
        )

      viewModelScope.launch {
        _isBusy.value = true
        repository
          .claimVerifiedWatchEarnSession(
            userId = uid,
            userName = name,
            sessionId = sessionId,
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
                errorMessage = rawMsg
              )
            _bannerMessage.value = rawMsg
          }
        _isBusy.value = false
      }
    }

    if (activity != null) {
      _watchEarnUiState.value =
        currentFlow.copy(
          status = com.example.data.model.WatchEarnFlowStatus.LOADING_AD,
          videosCompletedInCycle = 1,
          statusMessage = "Loading final ad to collect coins...",
          errorMessage = null
        )
      val secondVideoSessionId = "${sessionId}_claim2"
      com.example.util.UnityAdsHelper.loadAndShowRewardedAdForSession(
        activity = activity,
        sessionId = secondVideoSessionId,
        onAdShowing = {
          _watchEarnUiState.value =
            currentFlow.copy(
              status = com.example.data.model.WatchEarnFlowStatus.SHOWING_AD,
              videosCompletedInCycle = 1,
              statusMessage = "Watching final ad... Coins will be credited automatically!"
            )
        },
        onVideoCompleted = {
          finalizeRewardCredit()
        },
        onFailed = { errorMsg ->
          _watchEarnUiState.value =
            currentFlow.copy(
              status = com.example.data.model.WatchEarnFlowStatus.READY_TO_CLAIM,
              videosCompletedInCycle = 1,
              statusMessage = "Ad not completed (${errorMsg.ifBlank { "Ad unavailable" }}). Tap Claim to try again."
            )
        }
      )
    } else {
      finalizeRewardCredit()
    }
  }

  fun claimWatchEarnReward(onSuccess: (Long) -> Unit = {}) {
    val currentFlow = _watchEarnUiState.value
    if (currentFlow.isReadyToClaim && currentFlow.activeSessionId.isNotBlank()) {
      claimVerifiedWatchEarnReward(activity = null, onSuccess = onSuccess)
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
            "You landed on ${formatCoins(current.selectedRewardCoins)}! Tap Claim to watch 1 video and credit your Coins."
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

    _spinEarnUiState.value =
      current.copy(
        isLoadingAd = true,
        errorMessage = null,
        statusMessage =
          "Loading video (${current.extraSpinVideosWatched + 1}/${SpinWheelConstants.EXTRA_SPIN_REQUIRED_VIDEOS})..."
      )

    val adSessionId = "extraspin_${uid}_${UUID.randomUUID()}"
    com.example.util.UnityAdsHelper.loadAndShowRewardedAdForSession(
      activity = activity,
      sessionId = adSessionId,
      onAdShowing = {
        _spinEarnUiState.value =
          _spinEarnUiState.value.copy(
            isLoadingAd = true,
            statusMessage = "Watching video for Extra Spin..."
          )
      },
      onVideoCompleted = {
        viewModelScope.launch {
          repository
            .recordExtraSpinVideoWatched(uid)
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

  /**
   * Claims the pre-selected Spin reward ONLY after playing 1 separate rewarded video when the user
   * taps the "Claim" button on the Spin result panel.
   */
  fun claimSpinRewardWithAd(
    activity: android.app.Activity?,
    onSuccess: (Long) -> Unit = {}
  ) {
    val current = _spinEarnUiState.value
    val sessionId = current.activeSessionId
    if (sessionId.isBlank() || current.isClaiming || current.isLoadingAd) return

    val executeClaim: () -> Unit = {
      viewModelScope.launch {
        _isBusy.value = true
        _spinEarnUiState.value =
          _spinEarnUiState.value.copy(
            isLoadingAd = false,
            isClaiming = true,
            statusMessage = "Crediting your Spin reward...",
            errorMessage = null
          )
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
        _isBusy.value = false
      }
    }

    if (activity != null) {
      _spinEarnUiState.value =
        current.copy(
          isLoadingAd = true,
          errorMessage = null,
          statusMessage = "Loading reward claim video..."
        )
      com.example.util.UnityAdsHelper.loadAndShowRewardedAdForSession(
        activity = activity,
        sessionId = "${sessionId}_claim",
        onAdShowing = {
          _spinEarnUiState.value =
            _spinEarnUiState.value.copy(
              isLoadingAd = true,
              statusMessage = "Watching claim video... Complete to receive your Coins!"
            )
        },
        onVideoCompleted = {
          executeClaim()
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
    } else {
      executeClaim()
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
      _bannerMessage.value = "Spin & Earn reward claimed: +$coins Coins!"
      onSuccess(coins)
    }
  }

  fun claimSpinReward(sessionId: String, onSuccess: (Long) -> Unit) {
    viewModelScope.launch {
      _isBusy.value = true
      repository
        .claimSpinReward(sessionId)
        .onSuccess { reward ->
          _bannerMessage.value = "Spin reward claimed: +$reward Coins"
          onSuccess(reward)
        }
        .onFailure { _bannerMessage.value = it.localizedMessage ?: "Failed to claim spin reward." }
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

  fun setOpenWithdrawOnWalletNavigation(open: Boolean) {
    _openWithdrawOnWalletNavigation.value = open
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
        rewardPerTaskCents = rewardPerTaskCents
      ).onSuccess {
        _bannerMessage.value = "Campaign '$title' launched successfully!"
        onSuccess()
      }.onFailure { err ->
        _bannerMessage.value = err.localizedMessage ?: "Failed to create campaign."
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
    onSuccess: () -> Unit = {}
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
      }.onFailure { err ->
        _bannerMessage.value = err.localizedMessage ?: "Failed to submit ticket."
      }
      _isBusy.value = false
    }
  }

  fun updateSupportTicketStatus(ticketId: String, status: String, notes: String = "") {
    if (!isAdminAuthorized.value) return
    viewModelScope.launch {
      repository.updateSupportTicketStatus(ticketId, status, notes)
        .onSuccess {
          _bannerMessage.value = "Ticket marked as $status"
        }
        .onFailure { err ->
          _bannerMessage.value = err.localizedMessage ?: "Failed to update ticket."
        }
    }
  }
}

