package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import java.net.URI
import java.util.Locale

/**
 * Bootstrapped Owner / Administrator email configured in zero-trust Firestore Security Rules.
 */
const val BOOTSTRAPPED_ADMIN_EMAIL = "dasakash24059@gmail.com"

const val ADMIN_EMAIL_PRIVACY_PLACEHOLDER = "Authorized Administrator"

/**
 * Minimum withdrawal amount in INR paise (₹200.00 = 20,000 paise).
 */
const val MIN_WITHDRAWAL_CENTS = 20_000L

/**
 * Returns true if the given email or role belongs to the administrator.
 */
fun isHiddenAdminIdentity(email: String?, role: String? = null): Boolean {
  val normalizedEmail = email?.trim()?.lowercase(Locale.US) ?: ""
  val normalizedRole = role?.trim()?.lowercase(Locale.US) ?: ""
  return normalizedEmail == BOOTSTRAPPED_ADMIN_EMAIL.lowercase(Locale.US) ||
    normalizedRole == "admin"
}

/**
 * Hides the Admin's personal email address from UI screens while preserving regular user emails.
 */
fun formatVisibleUserEmail(email: String?, role: String? = null): String {
  if (isHiddenAdminIdentity(email, role)) {
    return ADMIN_EMAIL_PRIVACY_PLACEHOLDER
  }
  val trimmed = email?.trim() ?: ""
  return if (trimmed.isNotEmpty()) trimmed else "Verified Member"
}

/**
 * Validates that a Task URL is a well-formed HTTPS link (e.g., Google Play Store or website link).
 */
fun isValidTaskUrl(rawUrl: String): Boolean {
  val trimmed = rawUrl.trim()
  if (trimmed.isEmpty() || trimmed.length > 1000) return false
  if (!trimmed.startsWith("https://", ignoreCase = true)) return false
  return try {
    val uri = URI(trimmed)
    val scheme = uri.scheme?.lowercase(Locale.US)
    val host = uri.host
    scheme == "https" && !host.isNullOrBlank() && host.contains(".") && !trimmed.contains(" ")
  } catch (e: Exception) {
    false
  }
}

/**
 * Validates optional App Icon / Image reference (blank, valid HTTPS URL, content URI, or data:image URI).
 */
fun isValidImageUrlOrDataUri(rawValue: String): Boolean {
  val trimmed = rawValue.trim()
  if (trimmed.isEmpty()) return true
  if (trimmed.length > 350000) return false
  return isValidTaskUrl(trimmed) ||
    trimmed.startsWith("data:image/", ignoreCase = true) ||
    trimmed.startsWith("content://", ignoreCase = true) ||
    trimmed.startsWith("file://", ignoreCase = true)
}

object TaskStatus {
  const val PENDING = "pending"
  const val SUBMITTED = "submitted"
  const val APPROVED = "approved"
  const val REJECTED = "rejected"

  fun label(status: String, isActive: Boolean = true): String {
    if (!isActive && status.lowercase(Locale.US) == PENDING) {
      return "Deactivated"
    }
    return when (status.lowercase(Locale.US)) {
      PENDING -> "Available"
      SUBMITTED -> "Pending Review"
      APPROVED -> "Approved"
      REJECTED -> "Rejected"
      else -> status.replaceFirstChar { it.uppercase() }
    }
  }

  fun historyLabel(status: String): String =
    when (status.lowercase(Locale.US)) {
      APPROVED -> "Approved"
      REJECTED -> "Rejected"
      SUBMITTED, PENDING -> "Pending"
      else -> "Pending"
    }
}

object WithdrawalMethod {
  const val BANK_ACCOUNT = "Bank Account"
  const val UPI_ID = "UPI ID"

  val ALL = listOf(BANK_ACCOUNT, UPI_ID)
}

object TaskCategories {
  val ALL =
    listOf(
      "App Install",
      "Website Visit",
      "QA Testing",
      "Content Review",
      "Data Verification",
      "UX Research",
      "Documentation"
    )
}

object TransactionType {
  const val DAILY_CHECKIN = "DAILY_CHECKIN"
  const val TASK_REWARD = "TASK_REWARD"
  const val DEMO_WITHDRAWAL = "DEMO_WITHDRAWAL"
  const val WATCH_EARN = "WATCH_EARN"
}

data class TransactionRecord(
  @DocumentId val id: String = "",
  val userId: String = "",
  val userName: String = "",
  val type: String = TransactionType.DAILY_CHECKIN,
  val title: String = "",
  val amountCents: Long = 0L,
  val status: String = "CREDITED",
  val referenceId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null
)

data class WatchStats(
  val userId: String = "",
  val dateKey: String = "",
  val videosWatched: Long = 0L,
  val totalEarnedCents: Long = 0L
)

enum class WatchEarnFlowStatus {
  IDLE,
  LOADING_AD,
  SHOWING_AD,
  READY_TO_CLAIM,
  CLAIMING,
  CLAIMED,
  ERROR
}

data class WatchEarnUiState(
  val status: WatchEarnFlowStatus = WatchEarnFlowStatus.IDLE,
  val activeSessionId: String = "",
  val statusMessage: String = "",
  val errorMessage: String? = null
) {
  val isLoadingAd: Boolean
    get() = status == WatchEarnFlowStatus.LOADING_AD || status == WatchEarnFlowStatus.SHOWING_AD
  val isReadyToClaim: Boolean
    get() = status == WatchEarnFlowStatus.READY_TO_CLAIM && activeSessionId.isNotBlank()
  val isClaiming: Boolean
    get() = status == WatchEarnFlowStatus.CLAIMING
  val isClaimed: Boolean
    get() = status == WatchEarnFlowStatus.CLAIMED
  val hasError: Boolean
    get() = status == WatchEarnFlowStatus.ERROR
}

object DailyCheckInConstants {
  // 7-Day Reward Structure: Day 1-6: ₹1 (100 paise), Day 7: ₹7 (700 paise). Total = ₹13 (1300 paise).
  val REWARDS_CENTS = listOf(100L, 100L, 100L, 100L, 100L, 100L, 700L)
  const val TOTAL_7_DAY_CENTS = 1300L

  const val AD_VERIFICATION_REQUIRED_MESSAGE =
    "Daily Check-in is pending. Advertisement completion is required after which rewards can be credited."

  fun getRewardForDay(day: Int): Long {
    val index = (day - 1).coerceIn(0, 6)
    return REWARDS_CENTS[index]
  }
}

data class DailyCheckInState(
  val currentStreak: Int = 0,
  val nextClaimDay: Int = 1,
  val canCheckInToday: Boolean = true,
  val lastCheckInDate: String = "",
  val todayDate: String = "",
  val nextRewardCents: Long = 100L
)

fun getTodayDateKey(): String {
  val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
  return sdf.format(java.util.Date())
}

fun getYesterdayDateKey(): String {
  val cal = java.util.Calendar.getInstance()
  cal.add(java.util.Calendar.DATE, -1)
  val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
  return sdf.format(cal.time)
}

fun calculateDailyCheckInState(lastDate: String, streak: Long): DailyCheckInState {
  val today = getTodayDateKey()
  val yesterday = getYesterdayDateKey()
  val rawStreak = streak.toInt()

  return if (lastDate == today) {
    // Already checked in today
    val streakDay = if (rawStreak in 1..7) rawStreak else 1
    val nextDay = if (streakDay >= 7) 1 else streakDay + 1
    DailyCheckInState(
      currentStreak = streakDay,
      nextClaimDay = nextDay,
      canCheckInToday = false,
      lastCheckInDate = lastDate,
      todayDate = today,
      nextRewardCents = DailyCheckInConstants.getRewardForDay(nextDay)
    )
  } else if (lastDate == yesterday) {
    // Consecutive check-in: continuing streak
    val nextStreak = if (rawStreak >= 7) 1 else rawStreak + 1
    DailyCheckInState(
      currentStreak = rawStreak,
      nextClaimDay = nextStreak,
      canCheckInToday = true,
      lastCheckInDate = lastDate,
      todayDate = today,
      nextRewardCents = DailyCheckInConstants.getRewardForDay(nextStreak)
    )
  } else {
    // Missed a day or first time -> Reset streak to Day 1
    DailyCheckInState(
      currentStreak = 0,
      nextClaimDay = 1,
      canCheckInToday = true,
      lastCheckInDate = lastDate,
      todayDate = today,
      nextRewardCents = DailyCheckInConstants.getRewardForDay(1)
    )
  }
}

data class UserProfile(
  @DocumentId val id: String = "",
  val userId: String = "",
  val email: String = "",
  val displayName: String = "",
  val role: String = "user",
  val approvalStatus: String = "approved",
  val balanceCents: Long = 0L,
  val virtualPoints: Long = 0L,
  val completedTasksCount: Long = 0L,
  val assignedTaskId: String = "",
  val lastCheckInDate: String = "",
  val checkInStreak: Long = 0L,
  @ServerTimestamp val lastCheckInTimestamp: Timestamp? = null,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
)

data class TaskItem(
  @DocumentId val id: String = "",
  val title: String = "",
  val appName: String = "",
  val appIconUrl: String = "",
  val description: String = "",
  val instructions: String = "",
  val requiredProof: String = "",
  val taskLink: String = "",
  val category: String = "App Install",
  val rewardCents: Long = 0L,
  val assignedUserId: String = "",
  val assignedUserName: String = "",
  val assignedUserEmail: String = "",
  val status: String = TaskStatus.PENDING,
  @get:PropertyName("isActive") val isActive: Boolean = true,
  val proofText: String = "",
  val proofImageUrl: String = "",
  val proofStoragePath: String = "",
  val adminFeedback: String = "",
  val createdBy: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
) {
  fun isAvailableForNewSubmission(): Boolean =
    isActive && status == TaskStatus.PENDING
}

data class SubmissionItem(
  @DocumentId val id: String = "",
  val taskId: String = "",
  val taskTitle: String = "",
  val appName: String = "",
  val appIconUrl: String = "",
  val rewardCents: Long = 0L,
  val userId: String = "",
  val userName: String = "",
  val userEmail: String = "",
  val proofText: String = "",
  val proofImageUrl: String = "",
  val proofStoragePath: String = "",
  val status: String = TaskStatus.SUBMITTED,
  val adminFeedback: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
) {
  /**
   * Converts a preserved `/submissions/{id}` document into a `TaskItem` representation so user
   * history and activity remain intact even if the original task in `/tasks` was deleted by Admin.
   */
  fun toHistoryTaskItem(existingTask: TaskItem? = null): TaskItem {
    val resolvedId = taskId.ifBlank { id }
    return TaskItem(
      id = resolvedId,
      title = taskTitle.ifBlank { existingTask?.title ?: "Completed Task" },
      appName = appName.ifBlank { existingTask?.appName ?: "" },
      appIconUrl = appIconUrl.ifBlank { existingTask?.appIconUrl ?: "" },
      description = existingTask?.description ?: "Submitted task proof record preserved in history.",
      instructions = existingTask?.instructions ?: "",
      requiredProof = existingTask?.requiredProof ?: "",
      taskLink = existingTask?.taskLink ?: "",
      category = existingTask?.category ?: "App Install",
      rewardCents = if (rewardCents > 0L) rewardCents else (existingTask?.rewardCents ?: 0L),
      assignedUserId = userId,
      assignedUserName = userName,
      assignedUserEmail = userEmail,
      status = status,
      isActive = existingTask?.isActive ?: false,
      proofText = proofText,
      proofImageUrl = proofImageUrl,
      proofStoragePath = proofStoragePath,
      adminFeedback = adminFeedback,
      createdBy = existingTask?.createdBy ?: "",
      createdAt = createdAt ?: existingTask?.createdAt,
      updatedAt = updatedAt ?: existingTask?.updatedAt ?: createdAt
    )
  }
}

/**
 * Merges active/existing tasks from `/tasks` with the user's preserved `/submissions` documents
 * so deleting a task from `/tasks` never erases the user's submission history or activity records.
 */
fun mergeTasksWithPreservedSubmissions(
  tasks: List<TaskItem>,
  submissions: List<SubmissionItem>,
  currentUserId: String
): List<TaskItem> {
  val taskMap = tasks.associateBy { it.id }.toMutableMap()
  submissions
    .filter { currentUserId.isBlank() || it.userId == currentUserId }
    .forEach { sub ->
      val key = sub.taskId.ifBlank { sub.id }
      if (key.isNotBlank()) {
        val existing = taskMap[key]
        // If task was deleted from /tasks or doesn't reflect this user's submission, preserve it
        if (existing == null || existing.status == TaskStatus.PENDING) {
          taskMap[key] = sub.toHistoryTaskItem(existing)
        }
      }
    }
  return taskMap.values.sortedByDescending {
    (it.updatedAt ?: it.createdAt)?.seconds ?: 0L
  }
}

/**
 * Safe demo-only withdrawal request record stored in `/withdrawal_requests/{requestId}`.
 * Never stores real banking details, account numbers, IFSC codes, or UPI IDs.
 * Never deducts user wallet balance.
 */
data class DemoWithdrawalRequest(
  @DocumentId val id: String = "",
  val requestId: String = "",
  val userId: String = "",
  val userName: String = "",
  val amountCents: Long = 0L,
  val method: String = WithdrawalMethod.BANK_ACCOUNT,
  val status: String = "pending_demo",
  val isDemo: Boolean = true,
  @ServerTimestamp val createdAt: Timestamp? = null
)

data class UserActivityItem(
  val id: String,
  val title: String,
  val subtitle: String,
  val amountText: String = "",
  val statusLabel: String = "",
  val timestampMillis: Long = 0L,
  val relatedTaskId: String? = null
)

data class AdminRecord(
  @DocumentId val id: String = "",
  val userId: String = "",
  val email: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null
)

/**
 * Formats integer amounts (100 units = ₹1.00) into an Indian Rupee (INR ₹) currency string.
 */
fun formatRewardInr(cents: Long): String {
  val rupees = cents / 100.0
  return String.format(Locale.US, "₹%.2f", rupees)
}

/**
 * Alias retained for compatibility; formats amounts in Indian Rupee (INR ₹).
 */
fun formatRewardUsd(cents: Long): String = formatRewardInr(cents)

data class AppVersionInfo(
  val versionCode: Int = 1,
  val versionName: String = "1.0",
  val apkUrl: String = "",
  val releaseNotes: String = "",
  val forceUpdate: Boolean = false
)

enum class UpdateDownloadStatus {
  IDLE,
  CHECKING,
  UPDATE_AVAILABLE,
  DOWNLOADING,
  VERIFYING,
  DOWNLOAD_COMPLETED,
  READY_TO_INSTALL,
  PERMISSION_REQUIRED,
  WAITING_FOR_INSTALL_CONFIRMATION,
  INSTALLATION_STARTED,
  UPDATE_COMPLETED,
  FAILED
}

data class UpdateDownloadProgress(
  val status: UpdateDownloadStatus = UpdateDownloadStatus.IDLE,
  val progressPercent: Int = 0,
  val bytesDownloaded: Long = 0L,
  val totalBytes: Long = 0L,
  val statusMessage: String = "",
  val errorMessage: String? = null,
  val targetVersionCode: Int = 0,
  val targetVersionName: String = ""
) {
  val isChecking: Boolean get() = status == UpdateDownloadStatus.CHECKING
  val isUpdateAvailable: Boolean get() = status == UpdateDownloadStatus.UPDATE_AVAILABLE
  val isDownloading: Boolean get() = status == UpdateDownloadStatus.DOWNLOADING
  val isVerifying: Boolean get() = status == UpdateDownloadStatus.VERIFYING
  val isDownloadCompleted: Boolean get() = status == UpdateDownloadStatus.DOWNLOAD_COMPLETED || status == UpdateDownloadStatus.READY_TO_INSTALL
  val isReadyToInstall: Boolean get() = isDownloadCompleted
  val isPermissionRequired: Boolean get() = status == UpdateDownloadStatus.PERMISSION_REQUIRED
  val isWaitingForInstallConfirmation: Boolean get() = status == UpdateDownloadStatus.WAITING_FOR_INSTALL_CONFIRMATION
  val isInstallationStarted: Boolean get() = status == UpdateDownloadStatus.INSTALLATION_STARTED
  val isUpdateCompleted: Boolean get() = status == UpdateDownloadStatus.UPDATE_COMPLETED
  val hasFailed: Boolean get() = status == UpdateDownloadStatus.FAILED

  fun getFormattedSize(): String {
    if (totalBytes <= 0L) return ""
    val mbDownloaded = bytesDownloaded / (1024.0 * 1024.0)
    val mbTotal = totalBytes / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MB / %.1f MB", mbDownloaded, mbTotal)
  }
}


