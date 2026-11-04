package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.BOOTSTRAPPED_ADMIN_EMAIL
import com.example.data.model.TaskItem
import com.example.data.model.UserProfile
import com.example.data.repository.TaskFlowRepository
import com.example.ui.auth.PortalLoginTarget
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

sealed interface UiState<out T> {
  data object Loading : UiState<Nothing>
  data class Success<T>(val data: T) : UiState<T>
  data class Error(val message: String) : UiState<Nothing>
}

enum class UserTab {
  HOME,
  TRACKING,
  PROFILE
}

enum class AdminTab {
  TASKS,
  SUBMISSIONS,
  USERS
}

class TaskFlowViewModel(
  private val repository: TaskFlowRepository,
  val currentUserId: String,
  val currentUserEmail: String,
  val currentUserDisplayName: String,
  initialPortal: PortalLoginTarget = PortalLoginTarget.USER_APP
) : ViewModel() {

  val isVerifiedBootstrappedAdmin: Boolean =
    currentUserEmail.equals(BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true)

  private val _isAdminAuthorized = MutableStateFlow(isVerifiedBootstrappedAdmin)
  val isAdminAuthorized: StateFlow<Boolean> = _isAdminAuthorized.asStateFlow()

  private val _activePortal = MutableStateFlow(initialPortal)
  val activePortal: StateFlow<PortalLoginTarget> = _activePortal.asStateFlow()

  private val _activeUserTab = MutableStateFlow(UserTab.HOME)
  val activeUserTab: StateFlow<UserTab> = _activeUserTab.asStateFlow()

  private val _activeAdminTab = MutableStateFlow(AdminTab.TASKS)
  val activeAdminTab: StateFlow<AdminTab> = _activeAdminTab.asStateFlow()

  private val _selectedTaskId = MutableStateFlow<String?>(null)
  val selectedTaskId: StateFlow<String?> = _selectedTaskId.asStateFlow()

  private val _trackingStatusFilter = MutableStateFlow("all")
  val trackingStatusFilter: StateFlow<String> = _trackingStatusFilter.asStateFlow()

  private val _adminSubmissionFilter = MutableStateFlow("submitted")
  val adminSubmissionFilter: StateFlow<String> = _adminSubmissionFilter.asStateFlow()

  private val _bannerMessage = MutableStateFlow<String?>(null)
  val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

  private val _isBusy = MutableStateFlow(false)
  val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

  private val _showSecuritySheet = MutableStateFlow(false)
  val showSecuritySheet: StateFlow<Boolean> = _showSecuritySheet.asStateFlow()

  val userProfileState: StateFlow<UiState<UserProfile?>> =
    repository
      .observeUserProfile(currentUserId)
      .map<UserProfile?, UiState<UserProfile?>> { UiState.Success(it) }
      .catch { e ->
        Log.w(TAG, "Error observing user profile", e)
        emit(UiState.Error(e.localizedMessage ?: "Failed to load user profile"))
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = UiState.Loading
      )

  val userTasksState: StateFlow<UiState<List<TaskItem>>> =
    repository
      .observeUserTasks(currentUserId)
      .map<List<TaskItem>, UiState<List<TaskItem>>> { UiState.Success(it) }
      .catch { e ->
        Log.w(TAG, "Error observing user tasks", e)
        emit(UiState.Error(e.localizedMessage ?: "Failed to load assigned tasks"))
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = UiState.Loading
      )

  @OptIn(ExperimentalCoroutinesApi::class)
  val adminTasksState: StateFlow<UiState<List<TaskItem>>> =
    _isAdminAuthorized
      .flatMapLatest { isAdmin ->
        repository
          .observeAdminTasks(currentUserId, isAdmin)
          .map<List<TaskItem>, UiState<List<TaskItem>>> { UiState.Success(it) }
          .catch { e ->
            Log.w(TAG, "Error observing admin tasks", e)
            emit(UiState.Error(e.localizedMessage ?: "Failed to load admin tasks"))
          }
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = UiState.Loading
      )

  @OptIn(ExperimentalCoroutinesApi::class)
  val registeredUsersState: StateFlow<UiState<List<UserProfile>>> =
    _isAdminAuthorized
      .flatMapLatest { isAdmin ->
        repository
          .observeRegisteredUsers(currentUserId, isAdmin)
          .map<List<UserProfile>, UiState<List<UserProfile>>> { UiState.Success(it) }
          .catch { e ->
            Log.w(TAG, "Error observing registered users", e)
            emit(UiState.Error(e.localizedMessage ?: "Failed to load registered users"))
          }
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = UiState.Loading
      )

  init {
    viewModelScope.launch {
      try {
        repository.ensureUserProfile(
          uid = currentUserId,
          email = currentUserEmail,
          displayName = currentUserDisplayName
        )
        val adminStatus = repository.checkIsAdmin(currentUserId, currentUserEmail)
        _isAdminAuthorized.value = adminStatus
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Throwable) {
        Log.e(TAG, "Failed to initialize user/admin profile state", e)
      }
    }
  }

  fun setPortal(portal: PortalLoginTarget) {
    _selectedTaskId.value = null
    _activePortal.value = portal
  }

  fun setUserTab(tab: UserTab) {
    _selectedTaskId.value = null
    _activeUserTab.value = tab
  }

  fun setAdminTab(tab: AdminTab) {
    _selectedTaskId.value = null
    _activeAdminTab.value = tab
  }

  fun selectTask(taskId: String?) {
    _selectedTaskId.value = taskId
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

  fun submitProof(task: TaskItem, proofText: String, onSuccess: () -> Unit = {}) {
    if (proofText.isBlank()) {
      _bannerMessage.value = "Please enter your text proof before submitting."
      return
    }
    viewModelScope.launch {
      _isBusy.value = true
      val result = repository.submitTaskProof(task, proofText)
      _isBusy.value = false
      result
        .onSuccess {
          _bannerMessage.value = "Task proof saved to submissions & status updated to Submitted!"
          onSuccess()
        }
        .onFailure { e ->
          _bannerMessage.value = e.localizedMessage ?: "Failed to submit task proof."
        }
    }
  }

  fun updateProfileDisplayName(newName: String) {
    if (newName.isBlank()) {
      _bannerMessage.value = "Display name cannot be empty."
      return
    }
    viewModelScope.launch {
      _isBusy.value = true
      val result = repository.updateDisplayName(currentUserId, newName)
      _isBusy.value = false
      result
        .onSuccess { _bannerMessage.value = "Profile display name updated." }
        .onFailure { e -> _bannerMessage.value = e.localizedMessage ?: "Failed to update name." }
    }
  }

  fun createTask(
    title: String,
    description: String,
    category: String,
    rewardAmountInr: String,
    assignedUser: UserProfile,
    onSuccess: () -> Unit = {}
  ) {
    val rewardCents = parseInrToPaise(rewardAmountInr)
    if (title.isBlank() || description.isBlank() || rewardCents <= 0L) {
      _bannerMessage.value = "Please provide a valid title, description, and positive INR (₹) reward."
      return
    }
    viewModelScope.launch {
      _isBusy.value = true
      val result =
        repository.createTask(
          title = title,
          description = description,
          category = category,
          rewardCents = rewardCents,
          assignedUserId = assignedUser.userId.ifBlank { assignedUser.id },
          assignedUserName = assignedUser.displayName,
          assignedUserEmail = assignedUser.email
        )
      _isBusy.value = false
      result
        .onSuccess {
          _bannerMessage.value = "Task assigned to ${assignedUser.displayName}!"
          onSuccess()
        }
        .onFailure { e ->
          _bannerMessage.value = e.localizedMessage ?: "Failed to create task."
        }
    }
  }

  fun updateTask(
    taskId: String,
    title: String,
    description: String,
    category: String,
    rewardAmountInr: String,
    assignedUser: UserProfile,
    onSuccess: () -> Unit = {}
  ) {
    val rewardCents = parseInrToPaise(rewardAmountInr)
    if (title.isBlank() || description.isBlank() || rewardCents <= 0L) {
      _bannerMessage.value = "Please provide a valid title, description, and positive INR (₹) reward."
      return
    }
    viewModelScope.launch {
      _isBusy.value = true
      val result =
        repository.updateTaskDetails(
          taskId = taskId,
          title = title,
          description = description,
          category = category,
          rewardCents = rewardCents,
          assignedUserId = assignedUser.userId.ifBlank { assignedUser.id },
          assignedUserName = assignedUser.displayName,
          assignedUserEmail = assignedUser.email
        )
      _isBusy.value = false
      result
        .onSuccess {
          _bannerMessage.value = "Task updated successfully."
          onSuccess()
        }
        .onFailure { e ->
          _bannerMessage.value = e.localizedMessage ?: "Failed to update task."
        }
    }
  }

  fun deleteTask(taskId: String) {
    viewModelScope.launch {
      _isBusy.value = true
      val result = repository.deleteTask(taskId)
      _isBusy.value = false
      result
        .onSuccess { _bannerMessage.value = "Task deleted." }
        .onFailure { e -> _bannerMessage.value = e.localizedMessage ?: "Failed to delete task." }
    }
  }

  fun reviewSubmission(task: TaskItem, approve: Boolean, feedback: String) {
    viewModelScope.launch {
      _isBusy.value = true
      val result = repository.reviewSubmission(task, approve, feedback)
      _isBusy.value = false
      result
        .onSuccess {
          _bannerMessage.value =
            if (approve) {
              "Submission approved & reward credited to ${task.assignedUserName}!"
            } else {
              "Submission rejected."
            }
        }
        .onFailure { e ->
          _bannerMessage.value = e.localizedMessage ?: "Failed to review submission."
        }
    }
  }

  fun toggleUserApprovalStatus(user: UserProfile) {
    val targetUid = user.userId.ifBlank { user.id }
    val nextStatus = if (user.approvalStatus == "approved") "suspended" else "approved"
    viewModelScope.launch {
      _isBusy.value = true
      val result = repository.updateUserApprovalStatus(targetUid, nextStatus)
      _isBusy.value = false
      result
        .onSuccess {
          _bannerMessage.value = "${user.displayName} status changed to $nextStatus."
        }
        .onFailure { e ->
          _bannerMessage.value = e.localizedMessage ?: "Failed to update user status."
        }
    }
  }

  private fun parseInrToPaise(input: String): Long {
    val cleaned =
      input
        .replace("₹", "")
        .replace("INR", "", ignoreCase = true)
        .replace("Rs.", "", ignoreCase = true)
        .replace("Rs", "", ignoreCase = true)
        .replace("$", "")
        .replace(",", "")
        .trim()
    val amount = cleaned.toDoubleOrNull() ?: return 0L
    return Math.round(amount * 100.0)
  }

  private companion object {
    const val TAG = "TaskFlowVM"
    const val STOP_TIMEOUT_MILLIS = 5000L
  }
}
