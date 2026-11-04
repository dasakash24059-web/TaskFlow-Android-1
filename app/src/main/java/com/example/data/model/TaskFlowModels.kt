package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
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
  val category: String = "QA Testing",
  val rewardCents: Long = 0L,
  val assignedUserId: String = "",
  val assignedUserName: String = "",
  val assignedUserEmail: String = "",
  val status: String = TaskStatus.PENDING,
  val proofText: String = "",
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

fun formatRewardUsd(cents: Long): String {
  val dollars = cents / 100.0
  return String.format(Locale.US, "$%.2f", dollars)
}
