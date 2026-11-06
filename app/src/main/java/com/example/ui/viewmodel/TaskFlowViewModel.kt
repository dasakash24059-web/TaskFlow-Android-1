package com.example.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.BOOTSTRAPPED_ADMIN_EMAIL
import com.example.data.model.DailyCheckInState
import com.example.data.model.DemoWithdrawalRequest
import com.example.data.model.MIN_WITHDRAWAL_CENTS
import com.example.data.model.SubmissionItem
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.TransactionRecord
import com.example.data.model.UserProfile
import com.example.data.model.WithdrawalMethod
import com.example.data.model.calculateDailyCheckInState
import com.example.data.model.formatRewardInr
import com.example.data.model.isValidImageUrlOrDataUri
import com.example.data.model.isValidTaskUrl
import com.example.data.repository.TaskFlowRepository
import com.example.ui.auth.PortalLoginTarget
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
  WITHDRAWALS
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

    val parsedAmountInr = amountInrInput.trim().toDoubleOrNull()
    if (parsedAmountInr == null || parsedAmountInr <= 0.0) {
      val msg = "Please enter a valid withdrawal amount in INR (₹)."
      _withdrawalValidationError.value = msg
      return
    }

    val amountCents = (parsedAmountInr * 100.0).roundToLong()
    if (amountCents < MIN_WITHDRAWAL_CENTS) {
      val msg = "Minimum withdrawal amount is ₹200.00."
      _withdrawalValidationError.value = msg
      return
    }

    val profile = (userProfileState.value as? UiState.Success)?.data
    val availableBalanceCents = profile?.balanceCents ?: 0L
    if (amountCents > availableBalanceCents) {
      val msg =
        "Entered amount (${formatRewardInr(amountCents)}) exceeds your available balance of ${formatRewardInr(availableBalanceCents)}."
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
            "Demo withdrawal request of ${formatRewardInr(amountCents)} via $cleanedMethod submitted! Status: Demo / Pending."
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
    rewardAmountInr: String,
    assignedUser: UserProfile,
    instructions: String = "",
    taskLink: String = "",
    appName: String = "",
    appIconUrl: String = "",
    requiredProof: String = ""
  ) {
    val parsedAmountInr = rewardAmountInr.trim().toDoubleOrNull()
    if (parsedAmountInr == null || parsedAmountInr <= 0.0) {
      _bannerMessage.value = "Enter a valid reward amount in INR (e.g., 15.00)."
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
    val rewardCents = (parsedAmountInr * 100.0).roundToLong()
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
          requiredProof = requiredProof
        )
        .onSuccess {
          _bannerMessage.value =
            "Task '$title' created (${formatRewardInr(rewardCents)}) and published to TaskFlow."
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
    rewardAmountInr: String,
    assignedUser: UserProfile,
    instructions: String = "",
    taskLink: String = "",
    appName: String = "",
    appIconUrl: String = "",
    requiredProof: String = ""
  ) {
    val parsedAmountInr = rewardAmountInr.trim().toDoubleOrNull()
    if (parsedAmountInr == null || parsedAmountInr <= 0.0) {
      _bannerMessage.value = "Enter a valid reward amount in INR (e.g., 15.00)."
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
    val rewardCents = (parsedAmountInr * 100.0).roundToLong()
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
          requiredProof = requiredProof
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

  fun claimDailyCheckIn(onSuccess: (Long) -> Unit = {}) {
    val profile = (userProfileState.value as? UiState.Success)?.data
    val currentState = dailyCheckInState.value
    if (!currentState.canCheckInToday) {
      _bannerMessage.value = "You have already claimed today's check-in reward! Come back tomorrow."
      return
    }
    val targetStreak = currentState.nextClaimDay
    val rewardCents = currentState.nextRewardCents
    val dateKey = currentState.todayDate
    val resolvedName =
      profile?.displayName?.ifBlank { currentUserDisplayName } ?: currentUserDisplayName

    viewModelScope.launch {
      _isBusy.value = true
      repository
        .claimDailyCheckIn(
          userId = currentUserId,
          streakDay = targetStreak,
          rewardCents = rewardCents,
          dateKey = dateKey,
          userName = resolvedName
        )
        .onSuccess { claimed ->
          _bannerMessage.value =
            "Congratulations! Claimed Day $targetStreak reward: ${formatRewardInr(claimed)}."
          onSuccess(claimed)
        }
        .onFailure { err ->
          _bannerMessage.value = err.localizedMessage ?: "Failed to claim daily check-in reward."
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

  private var hasCheckedUpdateThisSession = false

  fun checkForAppUpdates(context: android.content.Context, forceCheck: Boolean = false) {
    if (hasCheckedUpdateThisSession && !forceCheck) return
    hasCheckedUpdateThisSession = true
    viewModelScope.launch {
      _isCheckingUpdate.value = true
      com.example.util.AppUpdateManager.checkForUpdate(context)
        .onSuccess { updateInfo ->
          if (updateInfo != null) {
            _appUpdateInfo.value = updateInfo
            _showUpdateDialog.value = true
          }
        }
        .onFailure {
          // Silent failure on network error
        }
      _isCheckingUpdate.value = false
    }
  }

  fun dismissUpdateDialog() {
    val info = _appUpdateInfo.value
    if (info == null || !info.forceUpdate) {
      _showUpdateDialog.value = false
    }
  }

  fun downloadAndInstallUpdate(context: android.content.Context) {
    val info = _appUpdateInfo.value ?: return
    if (info.apkUrl.isNotBlank()) {
      com.example.util.AppUpdateManager.downloadAndInstallApk(context, info.apkUrl)
      _bannerMessage.value = "Downloading update... You can install once complete."
    }
  }

  fun triggerInstalledApkInstall(context: android.content.Context) {
    val success = com.example.util.AppUpdateManager.installDownloadedApk(context)
    if (!success) {
      _bannerMessage.value = "Unable to start installation. Please check unknown app sources permission."
    }
  }

  private val _openWithdrawOnWalletNavigation = MutableStateFlow(false)
  val openWithdrawOnWalletNavigation: StateFlow<Boolean> = _openWithdrawOnWalletNavigation.asStateFlow()

  fun setOpenWithdrawOnWalletNavigation(open: Boolean) {
    _openWithdrawOnWalletNavigation.value = open
  }
}

