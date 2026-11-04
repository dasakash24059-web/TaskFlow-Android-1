package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.BOOTSTRAPPED_ADMIN_EMAIL
import com.example.data.model.SubmissionItem
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.UserProfile
import com.example.data.remote.OperationType
import com.example.data.remote.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class TaskFlowRepository(private val db: FirebaseFirestore) {

  constructor(
    context: Context
  ) : this(
    kotlin
      .runCatching {
        val dbId =
          context.applicationContext.getString(R.string.firestore_database_id).trim()
        if (dbId.isEmpty() || dbId == "(default)") {
          FirebaseFirestore.getInstance()
        } else {
          FirebaseFirestore.getInstance(dbId)
        }
      }
      .getOrElse { FirebaseFirestore.getInstance() }
  )

  private val auth
    get() = Firebase.auth

  private fun requireUserId(): String {
    return auth.currentUser?.uid
      ?: throw IllegalStateException("User must be signed in before accessing Firestore.")
  }

  private fun requireVerifiedTokenEmail(): String {
    val currentUser =
      auth.currentUser
        ?: throw IllegalStateException("User must be signed in before accessing Firestore.")
    check(currentUser.isEmailVerified) {
      "Authenticated user email must be verified before performing this operation."
    }
    val tokenEmail =
      currentUser.email?.trim().orEmpty().takeIf { it.isNotEmpty() }
        ?: throw IllegalStateException("Authenticated user email is required.")
    return tokenEmail.take(254)
  }

  suspend fun ensureUserProfile(
    uid: String,
    email: String,
    displayName: String
  ): Result<UserProfile> {
    val docRef = db.collection("users").document(uid)
    return try {
      val currentUid = requireUserId()
      check(currentUid == uid) { "Users may only initialize their own profile." }
      val safeEmail = requireVerifiedTokenEmail()
      val isBootstrappedAdmin =
        safeEmail.equals(BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true) &&
          auth.currentUser?.isEmailVerified == true
      val safeName =
        displayName
          .ifBlank {
            if (isBootstrappedAdmin) {
              "Administrator"
            } else {
              safeEmail.substringBefore("@").ifBlank { "TaskFlow User" }
            }
          }
          .take(100)

      val existingSnap = docRef.get().await()
      if (existingSnap.exists()) {
        val profile =
          existingSnap.toObject(
            UserProfile::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
          ) ?: UserProfile(id = uid, userId = uid, email = safeEmail, displayName = safeName)
        if (isBootstrappedAdmin) {
          ensureAdminRecord(uid, safeEmail)
        }
        Result.success(profile)
      } else {
        val assignedRole = if (isBootstrappedAdmin) "admin" else "user"
        val initialApprovalStatus = if (isBootstrappedAdmin) "approved" else "pending"
        val payload =
          mapOf(
            "userId" to uid,
            "email" to safeEmail,
            "displayName" to safeName,
            "role" to assignedRole,
            "approvalStatus" to initialApprovalStatus,
            "balanceCents" to 0L,
            "completedTasksCount" to 0L,
            "assignedTaskId" to "",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
          )
        docRef.set(payload).await()
        if (isBootstrappedAdmin) {
          ensureAdminRecord(uid, safeEmail)
        }
        val createdSnap = docRef.get().await()
        val createdProfile =
          createdSnap.toObject(
            UserProfile::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
          )
            ?: UserProfile(
              id = uid,
              userId = uid,
              email = safeEmail,
              displayName = safeName,
              role = assignedRole,
              approvalStatus = initialApprovalStatus,
              balanceCents = 0L,
              completedTasksCount = 0L,
              assignedTaskId = ""
            )
        Result.success(createdProfile)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, docRef.path)
      Result.failure(e)
    }
  }

  private suspend fun ensureAdminRecord(uid: String, email: String) {
    val adminRef = db.collection("admins").document(uid)
    try {
      val snap = adminRef.get().await()
      if (!snap.exists()) {
        adminRef
          .set(
            mapOf(
              "userId" to uid,
              "email" to email.take(254),
              "createdAt" to FieldValue.serverTimestamp()
            )
          )
          .await()
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, adminRef.path)
    }
  }

  suspend fun checkIsAdmin(uid: String, email: String?): Boolean {
    if (
      email?.equals(BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true) == true &&
        auth.currentUser?.isEmailVerified == true
    ) {
      return true
    }
    val adminRef = db.collection("admins").document(uid)
    return try {
      val snap = adminRef.get().await()
      snap.exists()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.GET, adminRef.path)
      false
    }
  }

  fun observeUserProfile(userId: String): Flow<UserProfile?> {
    if (userId.isBlank()) return kotlinx.coroutines.flow.flowOf(null)
    val path = "users/$userId"
    return db
      .collection("users")
      .document(userId)
      .snapshots()
      .map { snapshot ->
        if (snapshot.exists()) {
          runCatching {
              snapshot.toObject(
                UserProfile::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
              )
            }
            .getOrNull()
        } else {
          null
        }
      }
      .catch { error ->
        if (error is Exception) handleFirestoreError(error, OperationType.GET, path)
        throw error
      }
  }

  suspend fun getUserProfile(userId: String): Result<UserProfile?> {
    val docRef = db.collection("users").document(userId)
    return try {
      val snapshot = docRef.get().await()
      val profile =
        if (snapshot.exists()) {
          snapshot.toObject(
            UserProfile::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
          )
        } else {
          null
        }
      Result.success(profile)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.GET, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun updateDisplayName(userId: String, newDisplayName: String): Result<Unit> {
    val trimmed = newDisplayName.trim().take(100)
    require(trimmed.isNotEmpty()) { "Display name cannot be blank" }
    val docRef = db.collection("users").document(userId)
    return try {
      docRef
        .update(
          mapOf("displayName" to trimmed, "updatedAt" to FieldValue.serverTimestamp())
        )
        .await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, docRef.path)
      Result.failure(e)
    }
  }

  fun observeUserTasks(userId: String): Flow<List<TaskItem>> {
    if (userId.isBlank()) return kotlinx.coroutines.flow.flowOf(emptyList())
    val path = "tasks"
    return db
      .collection(path)
      .snapshots()
      .map { snapshot ->
        snapshot.documents
          .mapNotNull { doc ->
            runCatching {
                doc.toObject(
                  TaskItem::class.java,
                  DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
              }
              .getOrNull()
          }
          .sortedWith(
            compareByDescending<TaskItem> { it.assignedUserId == userId }
              .thenByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
          )
      }
      .catch { error ->
        if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
        throw error
      }
  }

  fun observeUserSubmissions(userId: String): Flow<List<SubmissionItem>> {
    if (userId.isBlank()) return kotlinx.coroutines.flow.flowOf(emptyList())
    val path = "submissions"
    return db
      .collection(path)
      .whereEqualTo("userId", userId)
      .snapshots()
      .map { snapshot ->
        snapshot.documents
          .mapNotNull { doc ->
            runCatching {
                doc.toObject(
                  SubmissionItem::class.java,
                  DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
              }
              .getOrNull()
          }
          .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
      }
      .catch { error ->
        if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
        throw error
      }
  }

  suspend fun createSubmissionRecord(
    taskId: String,
    taskTitle: String,
    rewardCents: Long,
    userName: String,
    userEmail: String,
    proofText: String
  ): Result<String> {
    val uid = requireUserId()
    val taskItem =
      TaskItem(
        id = taskId,
        title = taskTitle,
        rewardCents = rewardCents,
        assignedUserId = uid,
        assignedUserName = userName,
        assignedUserEmail = userEmail,
        status = TaskStatus.PENDING
      )
    return submitTaskProof(taskItem, proofText).map { taskId }
  }

  suspend fun getUserTasks(userId: String = requireUserId()): Result<List<TaskItem>> {
    val path = "tasks"
    return try {
      val snapshot = db.collection(path).get().await()
      val list =
        snapshot.documents
          .mapNotNull { doc ->
            runCatching {
                doc.toObject(
                  TaskItem::class.java,
                  DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
              }
              .getOrNull()
          }
          .sortedWith(
            compareByDescending<TaskItem> { it.assignedUserId == userId }
              .thenByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
          )
      Result.success(list)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.LIST, path)
      Result.failure(e)
    }
  }

  suspend fun getTaskById(taskId: String): Result<TaskItem?> {
    val docRef = db.collection("tasks").document(taskId)
    return try {
      val snapshot = docRef.get().await()
      val item =
        if (snapshot.exists()) {
          snapshot.toObject(
            TaskItem::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
          )
        } else {
          null
        }
      Result.success(item)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.GET, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun submitTaskProof(task: TaskItem, proofText: String): Result<Unit> {
    val taskId = task.id.trim()
    require(taskId.isNotEmpty()) { "Task ID cannot be blank." }
    val cleanProof = proofText.trim().take(4000)
    require(cleanProof.isNotEmpty()) { "Proof text cannot be empty." }
    val taskRef = db.collection("tasks").document(taskId)
    val subRef = db.collection("submissions").document(taskId)

    return try {
      val uid = requireUserId()
      val verifiedEmail = requireVerifiedTokenEmail()
      val userRef = db.collection("users").document(uid)

      val userSnap = userRef.get().await()
      check(userSnap.exists()) { "User profile must exist before submitting task proof." }
      val approvalStatus = userSnap.getString("approvalStatus").orEmpty()
      check(approvalStatus == "approved") {
        "Your account must be approved by an administrator before submitting task proof."
      }

      val taskSnap = taskRef.get().await()
      check(taskSnap.exists()) { "Assigned task $taskId does not exist." }
      val assignedUserId = taskSnap.getString("assignedUserId").orEmpty()
      check(assignedUserId == uid) { "You can only submit proof for a task assigned to you." }
      val currentTaskStatus = taskSnap.getString("status").orEmpty()
      check(currentTaskStatus == TaskStatus.PENDING) {
        "Task $taskId is not in 'pending' status (current: $currentTaskStatus)."
      }

      val subSnap = subRef.get().await()
      check(!subSnap.exists()) { "A submission already exists for task $taskId." }

      val authoritativeTitle =
        taskSnap.getString("title")?.trim()?.takeIf { it.isNotEmpty() }?.take(120)
          ?: task.title.trim().ifBlank { "Task" }.take(120)
      val authoritativeReward =
        (taskSnap.getLong("rewardCents") ?: task.rewardCents).coerceIn(1L, 10000000L)
      val authoritativeUserName =
        taskSnap.getString("assignedUserName")?.trim()?.takeIf { it.isNotEmpty() }?.take(100)
          ?: task.assignedUserName.trim().ifBlank { "TaskFlow User" }.take(100)

      val batch = db.batch()
      batch.update(
        taskRef,
        mapOf(
          "status" to TaskStatus.SUBMITTED,
          "proofText" to cleanProof,
          "updatedAt" to FieldValue.serverTimestamp()
        )
      )
      batch.set(
        subRef,
        mapOf(
          "taskId" to taskId,
          "taskTitle" to authoritativeTitle,
          "rewardCents" to authoritativeReward,
          "userId" to uid,
          "userName" to authoritativeUserName,
          "userEmail" to verifiedEmail,
          "proofText" to cleanProof,
          "status" to TaskStatus.SUBMITTED,
          "adminFeedback" to "",
          "createdAt" to FieldValue.serverTimestamp(),
          "updatedAt" to FieldValue.serverTimestamp()
        )
      )
      batch.commit().await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, subRef.path)
      Result.failure(e)
    }
  }

  // --- ADMIN OPERATIONS ---

  fun observeAdminTasks(adminUid: String, isAdminAuthorized: Boolean): Flow<List<TaskItem>> {
    if (!isAdminAuthorized && adminUid.isBlank()) return kotlinx.coroutines.flow.flowOf(emptyList())
    val path = "tasks"
    val query =
      if (isAdminAuthorized) {
        db.collection(path)
      } else {
        db.collection(path).whereEqualTo("assignedUserId", adminUid)
      }
    return query
      .snapshots()
      .map { snapshot ->
        snapshot.documents
          .mapNotNull { doc ->
            runCatching {
                doc.toObject(
                  TaskItem::class.java,
                  DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
              }
              .getOrNull()
          }
          .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
      }
      .catch { error ->
        if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
        throw error
      }
  }

  fun observeRegisteredUsers(
    adminUid: String,
    isAdminAuthorized: Boolean
  ): Flow<List<UserProfile>> {
    if (!isAdminAuthorized && adminUid.isBlank()) return kotlinx.coroutines.flow.flowOf(emptyList())
    val path = "users"
    val query =
      if (isAdminAuthorized) {
        db.collection(path)
      } else {
        db.collection(path).whereEqualTo("userId", adminUid)
      }
    return query
      .snapshots()
      .map { snapshot ->
        snapshot.documents
          .mapNotNull { doc ->
            runCatching {
                doc.toObject(
                  UserProfile::class.java,
                  DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
              }
              .getOrNull()
          }
          .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
      }
      .catch { error ->
        if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
        throw error
      }
  }

  suspend fun createTask(
    title: String,
    description: String,
    category: String,
    rewardCents: Long,
    assignedUserId: String,
    assignedUserName: String,
    assignedUserEmail: String
  ): Result<String> {
    val cleanAssignedUserId = assignedUserId.trim()
    require(cleanAssignedUserId.isNotEmpty()) { "Assigned user ID cannot be blank." }
    val tasksRef = db.collection("tasks")
    val docRef = tasksRef.document()
    return try {
      val adminUid = requireUserId()
      val userRef = db.collection("users").document(cleanAssignedUserId)
      val userSnap = userRef.get().await()
      check(userSnap.exists()) { "Assigned user profile $cleanAssignedUserId does not exist." }

      val payload =
        mapOf(
          "title" to title.trim().take(120),
          "description" to description.trim().take(2000),
          "category" to category.trim().ifBlank { "General" }.take(60),
          "rewardCents" to rewardCents.coerceIn(1L, 10000000L),
          "assignedUserId" to cleanAssignedUserId,
          "assignedUserName" to assignedUserName.trim().ifBlank { "Assigned User" }.take(100),
          "assignedUserEmail" to assignedUserEmail.trim().ifBlank { "user@taskflow.app" }.take(254),
          "status" to TaskStatus.PENDING,
          "proofText" to "",
          "adminFeedback" to "",
          "createdBy" to adminUid,
          "createdAt" to FieldValue.serverTimestamp(),
          "updatedAt" to FieldValue.serverTimestamp()
        )

      val batch = db.batch()
      batch.set(docRef, payload)
      batch.update(
        userRef,
        mapOf("assignedTaskId" to docRef.id, "updatedAt" to FieldValue.serverTimestamp())
      )
      batch.commit().await()
      Result.success(docRef.id)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun updateTaskDetails(
    taskId: String,
    title: String,
    description: String,
    category: String,
    rewardCents: Long,
    assignedUserId: String,
    assignedUserName: String,
    assignedUserEmail: String
  ): Result<Unit> {
    val cleanTaskId = taskId.trim()
    val cleanAssignedUserId = assignedUserId.trim()
    require(cleanTaskId.isNotEmpty()) { "Task ID cannot be blank." }
    require(cleanAssignedUserId.isNotEmpty()) { "Assigned user ID cannot be blank." }
    val docRef = db.collection("tasks").document(cleanTaskId)
    return try {
      val taskSnap = docRef.get().await()
      check(taskSnap.exists()) { "Task $cleanTaskId does not exist." }
      val currentStatus = taskSnap.getString("status").orEmpty()
      check(currentStatus == TaskStatus.PENDING) {
        "Only pending tasks can be edited (current status: $currentStatus)."
      }
      val previousAssignedUserId = taskSnap.getString("assignedUserId").orEmpty().trim()
      val userRef = db.collection("users").document(cleanAssignedUserId)
      val userSnap = userRef.get().await()
      check(userSnap.exists()) { "Assigned user profile $cleanAssignedUserId does not exist." }

      val prevUserRef =
        if (previousAssignedUserId.isNotEmpty() && previousAssignedUserId != cleanAssignedUserId) {
          db.collection("users").document(previousAssignedUserId)
        } else {
          null
        }
      val prevUserSnap = prevUserRef?.get()?.await()

      val batch = db.batch()
      batch.update(
        docRef,
        mapOf(
          "title" to title.trim().take(120),
          "description" to description.trim().take(2000),
          "category" to category.trim().ifBlank { "General" }.take(60),
          "rewardCents" to rewardCents.coerceIn(1L, 10000000L),
          "assignedUserId" to cleanAssignedUserId,
          "assignedUserName" to assignedUserName.trim().ifBlank { "Assigned User" }.take(100),
          "assignedUserEmail" to
            assignedUserEmail.trim().ifBlank { "user@taskflow.app" }.take(254),
          "updatedAt" to FieldValue.serverTimestamp()
        )
      )
      batch.update(
        userRef,
        mapOf("assignedTaskId" to cleanTaskId, "updatedAt" to FieldValue.serverTimestamp())
      )
      if (
        prevUserRef != null &&
          prevUserSnap != null &&
          prevUserSnap.exists() &&
          prevUserSnap.getString("assignedTaskId") == cleanTaskId
      ) {
        batch.update(
          prevUserRef,
          mapOf("assignedTaskId" to "", "updatedAt" to FieldValue.serverTimestamp())
        )
      }
      batch.commit().await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun deleteTask(taskId: String): Result<Unit> {
    val cleanTaskId = taskId.trim()
    require(cleanTaskId.isNotEmpty()) { "Task ID cannot be blank." }
    val docRef = db.collection("tasks").document(cleanTaskId)
    return try {
      val taskSnap = docRef.get().await()
      check(taskSnap.exists()) { "Task $cleanTaskId does not exist." }
      val currentStatus = taskSnap.getString("status").orEmpty()
      check(currentStatus == TaskStatus.PENDING) {
        "Only pending tasks can be deleted (current status: $currentStatus)."
      }
      val assignedUserId = taskSnap.getString("assignedUserId").orEmpty().trim()
      val userRef =
        if (assignedUserId.isNotEmpty()) db.collection("users").document(assignedUserId) else null
      val userSnap = userRef?.get()?.await()

      val batch = db.batch()
      batch.delete(docRef)
      if (
        userRef != null &&
          userSnap != null &&
          userSnap.exists() &&
          userSnap.getString("assignedTaskId") == cleanTaskId
      ) {
        batch.update(
          userRef,
          mapOf("assignedTaskId" to "", "updatedAt" to FieldValue.serverTimestamp())
        )
      }
      batch.commit().await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun reviewSubmission(
    task: TaskItem,
    approve: Boolean,
    adminFeedback: String
  ): Result<Unit> {
    val taskId = task.id.trim()
    require(taskId.isNotEmpty()) { "Task ID cannot be blank" }
    val taskRef = db.collection("tasks").document(taskId)
    val subRef = db.collection("submissions").document(taskId)
    val cleanFeedback = adminFeedback.trim().take(1000)
    val newStatus = if (approve) TaskStatus.APPROVED else TaskStatus.REJECTED

    return try {
      val taskSnap = taskRef.get().await()
      check(taskSnap.exists()) { "Task $taskId does not exist." }
      val currentTaskStatus = taskSnap.getString("status").orEmpty()
      check(currentTaskStatus == TaskStatus.SUBMITTED) {
        "Task $taskId must be in 'submitted' status to review (current: $currentTaskStatus)."
      }

      val subSnap = subRef.get().await()
      check(subSnap.exists()) { "Submission $taskId does not exist." }
      val currentSubStatus = subSnap.getString("status").orEmpty()
      check(currentSubStatus == TaskStatus.SUBMITTED) {
        "Submission $taskId must be in 'submitted' status to review (current: $currentSubStatus)."
      }

      val assignedUserId = taskSnap.getString("assignedUserId").orEmpty().trim()
      val submissionUserId = subSnap.getString("userId").orEmpty().trim()
      check(assignedUserId.isNotEmpty() && assignedUserId == submissionUserId) {
        "Task assignee and submission user ID mismatch."
      }

      val taskRewardCents = taskSnap.getLong("rewardCents") ?: 0L
      val subRewardCents = subSnap.getLong("rewardCents") ?: 0L
      check(taskRewardCents > 0L && taskRewardCents == subRewardCents) {
        "Task and submission reward amounts are invalid or mismatched."
      }

      val userRef = db.collection("users").document(assignedUserId)
      val userSnap = userRef.get().await()
      check(userSnap.exists()) { "Assigned user profile $assignedUserId does not exist." }

      val batch = db.batch()
      batch.update(
        taskRef,
        mapOf(
          "status" to newStatus,
          "adminFeedback" to cleanFeedback,
          "updatedAt" to FieldValue.serverTimestamp()
        )
      )
      batch.update(
        subRef,
        mapOf(
          "status" to newStatus,
          "adminFeedback" to cleanFeedback,
          "updatedAt" to FieldValue.serverTimestamp()
        )
      )
      if (approve) {
        batch.update(
          userRef,
          mapOf(
            "balanceCents" to FieldValue.increment(taskRewardCents),
            "completedTasksCount" to FieldValue.increment(1L),
            "assignedTaskId" to taskId,
            "updatedAt" to FieldValue.serverTimestamp()
          )
        )
      }
      batch.commit().await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, taskRef.path)
      Result.failure(e)
    }
  }

  suspend fun updateUserApprovalStatus(userId: String, approvalStatus: String): Result<Unit> {
    val docRef = db.collection("users").document(userId)
    return try {
      docRef
        .update(
          mapOf("approvalStatus" to approvalStatus, "updatedAt" to FieldValue.serverTimestamp())
        )
        .await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, docRef.path)
      Result.failure(e)
    }
  }
}
