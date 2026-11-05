package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import java.net.URI
import java.util.Locale

const val BOOTSTRAPPED_ADMIN_EMAIL = "dasakash24059@gmail.com"

object TaskStatus {
  const val PENDING = "pending"
  const val SUBMITTED = "submitted"
  const val APPROVED = "approved"
  const val REJECTED = "rejected"

  fun label(status: String): String =
    when (status.lowercase(Locale.US)) {
      PENDING -> "Pending"
      SUBMITTED -> "Submitted"
      APPROVED -> "Approved"
      REJECTED -> "Rejected"
      else -> status.replaceFirstChar { it.uppercase() }
    }
}

object TaskCategories {
  val ALL = listOf(
    "QA Testing",
    "Content Review",
    "Data Verification",
    "UX Research",
    "Documentation"
  )
}

@IgnoreExtraProperties
data class UserProfile(
  @DocumentId val id: String = "",
  val userId: String = "",
  val email: String = "",
  val displayName: String = "",
  val role: String = "user",
  val approvalStatus: String = "pending",
  val balanceCents: Long = 0L,
  val completedTasksCount: Long = 0L,
  val assignedTaskId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class TaskItem(
  @DocumentId val id: String = "",
  val title: String = "",
  val description: String = "",
  val instructions: String = "",
  val taskLink: String = "",
  val category: String = "QA Testing",
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

@IgnoreExtraProperties
data class SubmissionItem(
  @DocumentId val id: String = "",
  val taskId: String = "",
  val taskTitle: String = "",
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

@IgnoreExtraProperties
data class AdminRecord(
  @DocumentId val id: String = "",
  val userId: String = "",
  val email: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null
)

fun formatRewardInr(amountMinorUnits: Long): String {
  val rupees = amountMinorUnits / 100.0
  return String.format(Locale.US, "₹%.2f", rupees)
}

fun formatRewardUsd(cents: Long): String = formatRewardInr(cents)

fun isAdminIdentity(email: String?, role: String? = null): Boolean {
  return role?.trim()?.equals("admin", ignoreCase = true) == true ||
    email?.trim()?.equals(BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true) == true
}

fun formatVisibleUserEmail(email: String?, role: String? = null): String {
  if (isAdminIdentity(email, role)) {
    return "Authorized Administrator"
  }
  return email?.trim()?.takeIf { it.isNotEmpty() } ?: "Verified User"
}

fun isValidTaskUrl(url: String): Boolean {
  val trimmed = url.trim()
  if (trimmed.isEmpty() || trimmed.length > 1000 || trimmed.contains(" ")) return false
  if (!trimmed.startsWith("https://", ignoreCase = true)) return false
  return try {
    val uri = URI(trimmed)
    val host = uri.host?.trim().orEmpty()
    uri.scheme.equals("https", ignoreCase = true) &&
      host.length >= 4 &&
      host.contains(".") &&
      !host.startsWith(".") &&
      !host.endsWith(".")
  } catch (_: Exception) {
    false
  }
}
