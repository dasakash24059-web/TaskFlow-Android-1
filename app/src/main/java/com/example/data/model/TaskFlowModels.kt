package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
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

  fun label(status: String): String =
    when (status.lowercase(Locale.US)) {
      PENDING -> "Available"
      SUBMITTED -> "Pending Review"
      APPROVED -> "Approved"
      REJECTED -> "Rejected"
      else -> status.replaceFirstChar { it.uppercase() }
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

data class UserProfile(
  @DocumentId val id: String = "",
  val userId: String = "",
  val email: String = "",
  val displayName: String = "",
  val role: String = "user",
  val approvalStatus: String = "approved",
  val balanceCents: Long = 0L,
  val completedTasksCount: Long = 0L,
  val assignedTaskId: String = "",
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
  val proofText: String = "",
  val proofImageUrl: String = "",
  val proofStoragePath: String = "",
  val adminFeedback: String = "",
  val createdBy: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
)

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
)

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
