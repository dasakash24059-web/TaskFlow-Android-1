package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.example.data.model.AdminRecord
import com.example.data.model.BOOTSTRAPPED_ADMIN_EMAIL
import com.example.data.model.DailyCheckInConstants
import com.example.data.model.DemoWithdrawalRequest
import com.example.data.model.MIN_WITHDRAWAL_CENTS
import com.example.data.model.SubmissionItem
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.TransactionRecord
import com.example.data.model.TransactionType
import com.example.data.model.UserProfile
import com.example.data.model.WithdrawalMethod
import com.example.data.model.formatRewardInr
import com.example.data.model.isValidImageUrlOrDataUri
import com.example.data.model.isValidTaskUrl
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.storage.FirebaseStorage
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

data class UploadedProofPhoto(
  val downloadUrl: String,
  val storagePath: String
)

fun interface ProofPhotoUploader {
  suspend fun uploadProofPhoto(
    userId: String,
    taskId: String,
    imageUri: Uri,
    onProgress: (Int) -> Unit
  ): UploadedProofPhoto
}

/**
 * Encodes a Gallery image URI into a compact JPEG data URI (<= 180 KB) for Firestore-safe persistence.
 */
fun encodeGalleryImageAsDataUri(
  context: Context,
  imageUri: Uri,
  maxDimensionPx: Int = 900,
  initialQuality: Int = 72
): String? {
  return runCatching {
    val rawBytes =
      context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
        ?: return null
    if (rawBytes.isEmpty()) return null

    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOptions)

    var sampleSize = 1
    val width = boundsOptions.outWidth
    val height = boundsOptions.outHeight
    if (width > 0 && height > 0) {
      while (width / sampleSize > maxDimensionPx * 2 || height / sampleSize > maxDimensionPx * 2) {
        sampleSize *= 2
      }
    }

    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val decodedBitmap =
      BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions) ?: return null

    val scaledBitmap =
      if (decodedBitmap.width > maxDimensionPx || decodedBitmap.height > maxDimensionPx) {
        val ratio =
          minOf(
            maxDimensionPx.toFloat() / decodedBitmap.width.toFloat(),
            maxDimensionPx.toFloat() / decodedBitmap.height.toFloat()
          )
        val targetW = (decodedBitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (decodedBitmap.height * ratio).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(decodedBitmap, targetW, targetH, true)
      } else {
        decodedBitmap
      }

    var quality = initialQuality
    var compressedBytes: ByteArray
    do {
      val out = ByteArrayOutputStream()
      scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
      compressedBytes = out.toByteArray()
      quality -= 12
    } while (compressedBytes.size > 160_000 && quality >= 30)

    val base64Str = Base64.encodeToString(compressedBytes, Base64.NO_WRAP)
    "data:image/jpeg;base64,$base64Str"
  }.getOrNull()
}

class FirebaseStorageProofUploader(
  private val storageProvider: () -> FirebaseStorage = { FirebaseStorage.getInstance() },
  private val contextProvider: () -> Context? = {
    runCatching { FirebaseApp.getInstance().applicationContext }.getOrNull()
  }
) : ProofPhotoUploader {
  override suspend fun uploadProofPhoto(
    userId: String,
    taskId: String,
    imageUri: Uri,
    onProgress: (Int) -> Unit
  ): UploadedProofPhoto {
    val timestamp = System.currentTimeMillis()
    val storagePath = "submissions/$userId/$taskId/proof_${timestamp}.jpg"
    onProgress(10)

    // 1. Primary path: Upload to Firebase Storage and retrieve HTTPS download URL
    val firebaseUploadResult = runCatching {
      val storageRef = storageProvider().reference.child(storagePath)
      val uploadTask = storageRef.putFile(imageUri)
      uploadTask.addOnProgressListener { snapshot ->
        val total = snapshot.totalByteCount
        if (total > 0L) {
          val pct = ((snapshot.bytesTransferred * 100L) / total).toInt().coerceIn(10, 95)
          onProgress(pct)
        }
      }
      uploadTask.await()
      val downloadUrl = storageRef.downloadUrl.await().toString().trim()
      check(downloadUrl.isNotEmpty()) {
        "Firebase Storage did not return a valid download URL for the uploaded proof image."
      }
      onProgress(100)
      UploadedProofPhoto(
        downloadUrl = downloadUrl,
        storagePath = storagePath
      )
    }

    if (firebaseUploadResult.isSuccess) {
      return firebaseUploadResult.getOrThrow()
    }

    // 2. Resilient fallback: If Firebase Storage rules/bucket are not yet published in Console,
    // compress & encode the selected Gallery screenshot as a real JPEG data URI stored in Firestore
    val appContext = contextProvider()
    if (appContext != null) {
      onProgress(55)
      val encodedDataUri = encodeGalleryImageAsDataUri(appContext, imageUri)
      if (!encodedDataUri.isNullOrBlank()) {
        onProgress(100)
        return UploadedProofPhoto(
          downloadUrl = encodedDataUri,
          storagePath = "inline://$storagePath"
        )
      }
    }

    throw firebaseUploadResult.exceptionOrNull()
      ?: IllegalStateException("Failed to upload proof image. Please select a valid screenshot from Gallery.")
  }
}

class TaskFlowRepository(
  private val firestore: FirebaseFirestore,
  private val authProvider: () -> FirebaseAuth = { Firebase.auth },
  private val photoUploader: ProofPhotoUploader = FirebaseStorageProofUploader()
) {
  private val usersCollection = firestore.collection("users")
  private val tasksCollection = firestore.collection("tasks")
  private val submissionsCollection = firestore.collection("submissions")
  private val adminsCollection = firestore.collection("admins")
  private val withdrawalRequestsCollection = firestore.collection("withdrawal_requests")
  private val transactionsCollection = firestore.collection("transactions")

  /**
   * Checks if the current user is an authorized administrator via `/admins/{uid}` (and bootstrapped owner).
   * Never trusts client-editable `role` in `/users/{uid}`.
   */
  fun observeAdminAuthorization(uid: String, email: String): Flow<Boolean> = callbackFlow {
    require(uid.isNotBlank()) { "uid must not be blank" }
    val normalizedEmail = email.trim().lowercase(Locale.US)
    val isBootstrappedEmail = normalizedEmail == BOOTSTRAPPED_ADMIN_EMAIL.lowercase(Locale.US)
    if (isBootstrappedEmail) {
      trySend(true)
    }

    val listener =
      adminsCollection.document(uid).addSnapshotListener { snapshot, error ->
        if (error != null) {
          trySend(isBootstrappedEmail)
          return@addSnapshotListener
        }
        val existsInAdmins = snapshot != null && snapshot.exists()
        trySend(isBootstrappedEmail || existsInAdmins)
      }
    awaitClose { listener.remove() }
  }

  /**
   * Performs a one-shot secure Admin authorization check against `/admins/{uid}` in Firestore
   * (and initializes `/admins/{uid}` if the user is the verified bootstrap Admin).
   * Never trusts client-editable `role` in `/users/{uid}`.
   */
  suspend fun checkAndEnsureAdminAuthorization(uid: String, email: String): Boolean {
    if (uid.isBlank()) return false
    val safeEmail = email.trim()
    val isBootstrappedAdmin =
      safeEmail.lowercase(Locale.US) == BOOTSTRAPPED_ADMIN_EMAIL.lowercase(Locale.US)
    return runCatching {
      val adminDocRef = adminsCollection.document(uid)
      val adminSnap = adminDocRef.get().await()
      if (adminSnap.exists()) {
        true
      } else if (isBootstrappedAdmin) {
        adminDocRef
          .set(
            mapOf(
              "userId" to uid,
              "email" to safeEmail,
              "createdAt" to FieldValue.serverTimestamp()
            )
          )
          .await()
        true
      } else {
        false
      }
    }.getOrElse { isBootstrappedAdmin }
  }

  /**
   * Ensures the signed-in user has a document in `/users/{userId}` and, if they match the
   * bootstrapped admin email, also initializes `/admins/{userId}` so security rules recognize them.
   */
  suspend fun ensureUserProfile(
    uid: String,
    email: String,
    displayName: String
  ): Result<UserProfile> = runCatching {
    require(uid.isNotBlank()) { "uid must not be blank" }
    val safeEmail = email.trim().ifEmpty { "user@taskflow.app" }
    val safeName =
      displayName.trim().ifEmpty {
        safeEmail.substringBefore("@").replaceFirstChar { it.uppercase() }
      }
    val isBootstrappedAdmin =
      safeEmail.lowercase(Locale.US) == BOOTSTRAPPED_ADMIN_EMAIL.lowercase(Locale.US)

    if (isBootstrappedAdmin) {
      val adminDocRef = adminsCollection.document(uid)
      val adminSnap = adminDocRef.get().await()
      if (!adminSnap.exists()) {
        adminDocRef
          .set(
            mapOf(
              "userId" to uid,
              "email" to safeEmail,
              "createdAt" to FieldValue.serverTimestamp()
            )
          )
          .await()
      }
    }

    val userDocRef = usersCollection.document(uid)
    val snapshot = userDocRef.get().await()
    if (!snapshot.exists()) {
      val initialRole = if (isBootstrappedAdmin) "admin" else "user"
      val initialApproval = if (isBootstrappedAdmin) "approved" else "pending"
      val data =
        mapOf(
          "userId" to uid,
          "email" to safeEmail,
          "displayName" to safeName,
          "role" to initialRole,
          "approvalStatus" to initialApproval,
          "balanceCents" to 0L,
          "completedTasksCount" to 0L,
          "assignedTaskId" to "",
          "createdAt" to FieldValue.serverTimestamp(),
          "updatedAt" to FieldValue.serverTimestamp()
        )
      userDocRef.set(data).await()
    } else if (isBootstrappedAdmin) {
      val currentRole = snapshot.getString("role") ?: "user"
      val currentApproval = snapshot.getString("approvalStatus") ?: "pending"
      if (currentRole != "admin" || currentApproval != "approved") {
        userDocRef
          .update(
            mapOf(
              "role" to "admin",
              "approvalStatus" to "approved",
              "updatedAt" to FieldValue.serverTimestamp()
            )
          )
          .await()
      }
    }

    val updatedSnap = userDocRef.get().await()
    updatedSnap.toObject(UserProfile::class.java)
      ?: error("Failed to deserialize UserProfile for $uid")
  }

  /**
   * Directly fetches a single user profile by UID.
   */
  suspend fun getUserProfile(uid: String): Result<UserProfile?> = runCatching {
    require(uid.isNotBlank()) { "uid must not be blank" }
    val snap = usersCollection.document(uid).get().await()
    snap.toObject(UserProfile::class.java)
  }

  /**
   * Observes the current user's profile in real time.
   */
  fun observeUserProfile(uid: String): Flow<UserProfile?> = callbackFlow {
    require(uid.isNotBlank()) { "uid must not be blank" }
    val listener =
      usersCollection.document(uid).addSnapshotListener { snapshot, error ->
        if (error != null) {
          close(error)
          return@addSnapshotListener
        }
        trySend(snapshot?.toObject(UserProfile::class.java))
      }
    awaitClose { listener.remove() }
  }

  /**
   * Updates only the current user's displayName.
   */
  suspend fun updateUserDisplayName(uid: String, newDisplayName: String): Result<Unit> =
    runCatching {
      require(uid.isNotBlank()) { "uid must not be blank" }
      val cleaned = newDisplayName.trim()
      require(cleaned.isNotEmpty() && cleaned.length <= 100) {
        "Display name must be between 1 and 100 characters."
      }
      usersCollection
        .document(uid)
        .update(
          mapOf(
            "displayName" to cleaned,
            "updatedAt" to FieldValue.serverTimestamp()
          )
        )
        .await()
    }

  private fun DocumentSnapshot.toTaskItem(): TaskItem? {
    val parsed = toObject(TaskItem::class.java) ?: return null
    val explicitActive = getBoolean("isActive") ?: true
    return parsed.copy(isActive = explicitActive)
  }

  /**
   * Observes all tasks in `/tasks` for any authenticated user.
   */
  fun observeUserTasks(uid: String): Flow<List<TaskItem>> = callbackFlow {
    require(uid.isNotBlank()) { "uid must not be blank" }
    val currentAuthUser = authProvider().currentUser
    if (currentAuthUser == null) {
      close(
        FirebaseFirestoreException(
          "Sign-in required to observe tasks.",
          FirebaseFirestoreException.Code.PERMISSION_DENIED
        )
      )
      return@callbackFlow
    }
    val listener =
      tasksCollection.addSnapshotListener { snapshot, error ->
        if (error != null) {
          close(error)
          return@addSnapshotListener
        }
        val items =
          snapshot
            ?.documents
            ?.mapNotNull { it.toTaskItem() }
            ?.sortedByDescending { it.createdAt?.seconds ?: 0L }
            ?: emptyList()
        trySend(items)
      }
    awaitClose { listener.remove() }
  }

  /**
   * One-shot fetch of all visible tasks for an authenticated user.
   */
  suspend fun getUserTasks(uid: String): Result<List<TaskItem>> = runCatching {
    require(uid.isNotBlank()) { "uid must not be blank" }
    val snapshot = tasksCollection.get().await()
    snapshot.documents
      .mapNotNull { it.toTaskItem() }
      .sortedByDescending { it.createdAt?.seconds ?: 0L }
  }

  /**
   * Observes the current user's own submissions in `/submissions` so user history and activity
   * remain preserved even if an Admin deletes the original task from `/tasks`.
   */
  fun observeUserSubmissions(uid: String): Flow<List<SubmissionItem>> = callbackFlow {
    require(uid.isNotBlank()) { "uid must not be blank" }
    val listener =
      submissionsCollection
        .whereEqualTo("userId", uid)
        .addSnapshotListener { snapshot, error ->
          if (error != null) {
            close(error)
            return@addSnapshotListener
          }
          val items =
            snapshot
              ?.documents
              ?.mapNotNull { it.toObject(SubmissionItem::class.java) }
              ?.sortedByDescending {
                it.updatedAt?.seconds ?: it.createdAt?.seconds ?: 0L
              }
              ?: emptyList()
          trySend(items)
        }
    awaitClose { listener.remove() }
  }

  /**
   * One-shot fetch of a user's own submissions in `/submissions`.
   */
  suspend fun getUserSubmissions(uid: String): Result<List<SubmissionItem>> = runCatching {
    require(uid.isNotBlank()) { "uid must not be blank" }
    val snapshot = submissionsCollection.whereEqualTo("userId", uid).get().await()
    snapshot.documents
      .mapNotNull { it.toObject(SubmissionItem::class.java) }
      .sortedByDescending { it.updatedAt?.seconds ?: it.createdAt?.seconds ?: 0L }
  }

  /**
   * Directly fetches a single task by ID.
   */
  suspend fun getTaskById(taskId: String): Result<TaskItem?> = runCatching {
    require(taskId.isNotBlank()) { "taskId must not be blank" }
    val snap = tasksCollection.document(taskId).get().await()
    snap.toTaskItem()
  }

  /**
   * Directly fetches a single submission by ID.
   */
  suspend fun getSubmissionById(submissionId: String): Result<SubmissionItem?> = runCatching {
    require(submissionId.isNotBlank()) { "submissionId must not be blank" }
    val snap = submissionsCollection.document(submissionId).get().await()
    snap.toObject(SubmissionItem::class.java)
  }

  /**
   * Observes all tasks in `/tasks` for an authorized admin.
   */
  fun observeAdminTasks(adminUid: String, isAdminAuthorized: Boolean): Flow<List<TaskItem>> =
    callbackFlow {
      require(adminUid.isNotBlank()) { "adminUid must not be blank" }
      if (!isAdminAuthorized) {
        trySend(emptyList())
        awaitClose {}
        return@callbackFlow
      }
      val listener =
        tasksCollection.addSnapshotListener { snapshot, error ->
          if (error != null) {
            close(error)
            return@addSnapshotListener
          }
          val items =
            snapshot
              ?.documents
              ?.mapNotNull { it.toTaskItem() }
              ?.sortedByDescending { it.createdAt?.seconds ?: 0L }
              ?: emptyList()
          trySend(items)
        }
      awaitClose { listener.remove() }
    }

  /**
   * Observes all submissions in `/submissions` in real time for an authorized admin.
   */
  fun observeAdminSubmissions(
    adminUid: String,
    isAdminAuthorized: Boolean
  ): Flow<List<SubmissionItem>> = callbackFlow {
    require(adminUid.isNotBlank()) { "adminUid must not be blank" }
    if (!isAdminAuthorized) {
      trySend(emptyList())
      awaitClose {}
      return@callbackFlow
    }
    val listener =
      submissionsCollection.addSnapshotListener { snapshot, error ->
        if (error != null) {
          close(error)
          return@addSnapshotListener
        }
        val items =
          snapshot
            ?.documents
            ?.mapNotNull { it.toObject(SubmissionItem::class.java) }
            ?.sortedByDescending {
              it.updatedAt?.seconds ?: it.createdAt?.seconds ?: 0L
            }
            ?: emptyList()
        trySend(items)
      }
    awaitClose { listener.remove() }
  }

  /**
   * One-shot fetch of all submissions for an authorized admin.
   */
  suspend fun getAdminSubmissions(): Result<List<SubmissionItem>> = runCatching {
    val snapshot = submissionsCollection.get().await()
    snapshot.documents
      .mapNotNull { it.toObject(SubmissionItem::class.java) }
      .sortedByDescending { it.updatedAt?.seconds ?: it.createdAt?.seconds ?: 0L }
  }

  /**
   * Observes all registered user profiles in `/users` for an authorized admin.
   */
  fun observeRegisteredUsers(
    adminUid: String,
    isAdminAuthorized: Boolean
  ): Flow<List<UserProfile>> = callbackFlow {
    require(adminUid.isNotBlank()) { "adminUid must not be blank" }
    if (!isAdminAuthorized) {
      trySend(emptyList())
      awaitClose {}
      return@callbackFlow
    }
    val listener =
      usersCollection.addSnapshotListener { snapshot, error ->
        if (error != null) {
          close(error)
          return@addSnapshotListener
        }
        val users =
          snapshot
            ?.documents
            ?.mapNotNull { it.toObject(UserProfile::class.java) }
            ?.sortedBy { it.displayName.lowercase(Locale.US) }
            ?: emptyList()
        trySend(users)
      }
    awaitClose { listener.remove() }
  }

  /**
   * Creates a new task in `/tasks` and updates the assigned user's `assignedTaskId` atomically.
   */
  suspend fun createTask(
    title: String,
    description: String,
    category: String,
    rewardCents: Long,
    assignedUserId: String,
    assignedUserName: String,
    assignedUserEmail: String,
    instructions: String = "",
    taskLink: String = "",
    appName: String = "",
    appIconUrl: String = "",
    requiredProof: String = ""
  ): Result<String> = runCatching {
    val adminUid =
      authProvider().currentUser?.uid ?: error("Must be signed in as Admin to create a task.")
    val cleanedTitle = title.trim()
    val cleanedAppName = appName.trim()
    val cleanedAppIconUrl = appIconUrl.trim()
    val cleanedDesc = description.trim()
    val cleanedInstructions = instructions.trim().ifBlank { cleanedDesc }
    val cleanedRequiredProof =
      requiredProof.trim().ifBlank {
        "Provide written confirmation notes and a clear screenshot showing task completion."
      }
    val cleanedLink = taskLink.trim()
    val cleanedCat = category.trim().ifEmpty { "App Install" }
    require(cleanedTitle.isNotEmpty() && cleanedTitle.length <= 120) {
      "Task title must be 1–120 characters."
    }
    require(cleanedAppName.length <= 120) {
      "App or website name must be up to 120 characters."
    }
    require(isValidImageUrlOrDataUri(cleanedAppIconUrl)) {
      "App icon must be a valid HTTPS image URL or selected Gallery image."
    }
    require(cleanedDesc.isNotEmpty() && cleanedDesc.length <= 2000) {
      "Task description must be 1–2000 characters."
    }
    require(cleanedInstructions.length <= 4000) {
      "Step-by-step instructions must be up to 4000 characters."
    }
    require(cleanedRequiredProof.length <= 2000) {
      "Required proof description must be up to 2000 characters."
    }
    require(cleanedLink.isEmpty() || isValidTaskUrl(cleanedLink)) {
      "Task link must be a valid HTTPS URL (e.g., https://play.google.com/...)."
    }
    require(rewardCents in 1..10_000_000) { "Reward must be greater than ₹0.00." }
    require(assignedUserId.isNotBlank()) { "An assigned user must be selected." }

    val docRef = tasksCollection.document()
    val payload =
      mapOf(
        "title" to cleanedTitle,
        "appName" to cleanedAppName,
        "appIconUrl" to cleanedAppIconUrl,
        "description" to cleanedDesc,
        "instructions" to cleanedInstructions,
        "requiredProof" to cleanedRequiredProof,
        "taskLink" to cleanedLink,
        "category" to cleanedCat,
        "rewardCents" to rewardCents,
        "assignedUserId" to assignedUserId,
        "assignedUserName" to assignedUserName.trim().ifEmpty { "Assigned User" },
        "assignedUserEmail" to assignedUserEmail.trim().ifEmpty { "user@taskflow.app" },
        "status" to TaskStatus.PENDING,
        "isActive" to true,
        "proofText" to "",
        "proofImageUrl" to "",
        "proofStoragePath" to "",
        "adminFeedback" to "",
        "createdBy" to adminUid,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
      )

    val batch = firestore.batch()
    batch.set(docRef, payload)
    batch.update(
      usersCollection.document(assignedUserId),
      mapOf(
        "assignedTaskId" to docRef.id,
        "updatedAt" to FieldValue.serverTimestamp()
      )
    )
    batch.commit().await()
    docRef.id
  }

  /**
   * Updates an existing pending task's details or assigned user.
   */
  suspend fun updateTaskDetails(
    taskId: String,
    title: String,
    description: String,
    category: String,
    rewardCents: Long,
    assignedUserId: String,
    assignedUserName: String,
    assignedUserEmail: String,
    instructions: String = "",
    taskLink: String = "",
    appName: String = "",
    appIconUrl: String = "",
    requiredProof: String = ""
  ): Result<Unit> = runCatching {
    require(taskId.isNotBlank()) { "taskId must not be blank" }
    val cleanedTitle = title.trim()
    val cleanedAppName = appName.trim()
    val cleanedAppIconUrl = appIconUrl.trim()
    val cleanedDesc = description.trim()
    val cleanedInstructions = instructions.trim().ifBlank { cleanedDesc }
    val cleanedRequiredProof =
      requiredProof.trim().ifBlank {
        "Provide written confirmation notes and a clear screenshot showing task completion."
      }
    val cleanedLink = taskLink.trim()
    val cleanedCat = category.trim().ifEmpty { "App Install" }
    require(cleanedTitle.isNotEmpty() && cleanedTitle.length <= 120) {
      "Task title must be 1–120 characters."
    }
    require(cleanedAppName.length <= 120) {
      "App or website name must be up to 120 characters."
    }
    require(isValidImageUrlOrDataUri(cleanedAppIconUrl)) {
      "App icon must be a valid HTTPS image URL or selected Gallery image."
    }
    require(cleanedDesc.isNotEmpty() && cleanedDesc.length <= 2000) {
      "Task description must be 1–2000 characters."
    }
    require(cleanedInstructions.length <= 4000) {
      "Step-by-step instructions must be up to 4000 characters."
    }
    require(cleanedRequiredProof.length <= 2000) {
      "Required proof description must be up to 2000 characters."
    }
    require(cleanedLink.isEmpty() || isValidTaskUrl(cleanedLink)) {
      "Task link must be a valid HTTPS URL (e.g., https://play.google.com/...)."
    }
    require(rewardCents in 1..10_000_000) { "Reward must be greater than ₹0.00." }
    require(assignedUserId.isNotBlank()) { "An assigned user must be selected." }

    val taskRef = tasksCollection.document(taskId)
    val existingTaskSnap = taskRef.get().await()
    check(existingTaskSnap.exists()) { "Task $taskId does not exist." }
    val previousAssigneeId = existingTaskSnap.getString("assignedUserId") ?: ""

    val batch = firestore.batch()
    batch.update(
      taskRef,
      mapOf(
        "title" to cleanedTitle,
        "appName" to cleanedAppName,
        "appIconUrl" to cleanedAppIconUrl,
        "description" to cleanedDesc,
        "instructions" to cleanedInstructions,
        "requiredProof" to cleanedRequiredProof,
        "taskLink" to cleanedLink,
        "category" to cleanedCat,
        "rewardCents" to rewardCents,
        "assignedUserId" to assignedUserId,
        "assignedUserName" to assignedUserName.trim().ifEmpty { "Assigned User" },
        "assignedUserEmail" to assignedUserEmail.trim().ifEmpty { "user@taskflow.app" },
        "updatedAt" to FieldValue.serverTimestamp()
      )
    )
    batch.update(
      usersCollection.document(assignedUserId),
      mapOf(
        "assignedTaskId" to taskId,
        "updatedAt" to FieldValue.serverTimestamp()
      )
    )

    if (previousAssigneeId.isNotBlank() && previousAssigneeId != assignedUserId) {
      val prevUserRef = usersCollection.document(previousAssigneeId)
      val prevUserSnap = prevUserRef.get().await()
      if (prevUserSnap.exists() && prevUserSnap.getString("assignedTaskId") == taskId) {
        batch.update(
          prevUserRef,
          mapOf(
            "assignedTaskId" to "",
            "updatedAt" to FieldValue.serverTimestamp()
          )
        )
      }
    }

    batch.commit().await()
  }

  /**
   * Activates, deactivates, or reactivates a task (`isActive: Boolean`) in `/tasks/{taskId}`.
   * Deactivated tasks cannot accept new user submissions until reactivated by Admin.
   */
  suspend fun setTaskActiveStatus(taskId: String, isActive: Boolean): Result<Unit> = runCatching {
    require(taskId.isNotBlank()) { "taskId must not be blank" }
    val taskRef = tasksCollection.document(taskId)
    val taskSnap = taskRef.get().await()
    check(taskSnap.exists()) { "Task $taskId does not exist." }
    taskRef
      .update(
        mapOf(
          "isActive" to isActive,
          "updatedAt" to FieldValue.serverTimestamp()
        )
      )
      .await()
  }

  /**
   * Deletes a task from `/tasks/{taskId}` (Admin only) and clears the assignee's `assignedTaskId`
   * if it points to this task, while preserving `/submissions/{taskId}` and user history.
   */
  suspend fun deleteTask(taskId: String): Result<Unit> = runCatching {
    require(taskId.isNotBlank()) { "taskId must not be blank" }
    val taskRef = tasksCollection.document(taskId)
    val taskSnap = taskRef.get().await()
    val assignedUserId = taskSnap.getString("assignedUserId") ?: ""

    val batch = firestore.batch()
    batch.delete(taskRef)

    if (assignedUserId.isNotBlank()) {
      val userRef = usersCollection.document(assignedUserId)
      val userSnap = userRef.get().await()
      if (userSnap.exists() && userSnap.getString("assignedTaskId") == taskId) {
        batch.update(
          userRef,
          mapOf(
            "assignedTaskId" to "",
            "updatedAt" to FieldValue.serverTimestamp()
          )
        )
      }
    }

    batch.commit().await()
  }

  /**
   * Submits MANDATORY written text proof AND MANDATORY Gallery photo/screenshot proof for an open task.
   *
   * Any active (non-suspended) authenticated user can submit proof on an active ('pending' and `isActive == true`) task.
   * Uploads/persists the selected image first, then atomically updates `/tasks/{taskId}` to `submitted`
   * (associating the submitting user's UID, name, and email) and creates `/submissions/{taskId}`.
   */
  suspend fun submitTaskProof(
    task: TaskItem,
    userName: String,
    userEmail: String,
    proofText: String,
    imageUri: Uri?,
    onProgress: (Int) -> Unit = {}
  ): Result<Unit> = runCatching {
    val authUser = authProvider().currentUser ?: error("Must be signed in to submit task proof.")
    val uid = authUser.uid
    val cleanedProofText = proofText.trim()
    require(cleanedProofText.isNotEmpty() && cleanedProofText.length <= 4000) {
      "Written text proof is mandatory (1–4000 characters)."
    }
    require(imageUri != null) {
      "At least one photo or screenshot from your phone Gallery is mandatory."
    }

    // 1. Ensure user profile exists and is active (not suspended)
    val userRef = usersCollection.document(uid)
    val userSnap = userRef.get().await()
    val resolvedEmail =
      (userSnap.getString("email") ?: userEmail.trim().ifBlank { authUser.email ?: "" }).trim()
    val resolvedName =
      (userSnap.getString("displayName") ?: userName.trim().ifBlank { authUser.displayName ?: "" })
        .trim()
        .ifEmpty { "TaskFlow User" }

    if (!userSnap.exists()) {
      ensureUserProfile(uid, resolvedEmail, resolvedName).getOrThrow()
    } else {
      val approvalStatus = userSnap.getString("approvalStatus") ?: "pending"
      check(approvalStatus != "suspended") {
        "Your account is currently suspended and cannot submit task proof."
      }
    }

    // 2. Verify task exists, is still open ('pending'), and is active ('isActive' != false)
    val taskRef = tasksCollection.document(task.id)
    val latestTaskSnap = taskRef.get().await()
    check(latestTaskSnap.exists()) { "Task ${task.id} no longer exists." }
    val isActive = latestTaskSnap.getBoolean("isActive") ?: true
    check(isActive) {
      "This task is currently deactivated by Admin and is not accepting new submissions."
    }
    val currentStatus = latestTaskSnap.getString("status") ?: ""
    check(currentStatus == TaskStatus.PENDING) {
      "Task proof can only be submitted while the task is Available (current status: ${TaskStatus.label(currentStatus)})."
    }

    // 3. Upload / encode mandatory photo proof and verify non-empty reference
    val uploadedPhoto =
      photoUploader.uploadProofPhoto(
        userId = uid,
        taskId = task.id,
        imageUri = imageUri,
        onProgress = onProgress
      )
    check(uploadedPhoto.downloadUrl.isNotBlank() && uploadedPhoto.storagePath.isNotBlank()) {
      "Photo proof upload did not complete. Please try again."
    }

    val latestTitle = latestTaskSnap.getString("title") ?: task.title
    val latestAppName = latestTaskSnap.getString("appName") ?: task.appName
    val latestAppIconUrl = latestTaskSnap.getString("appIconUrl") ?: task.appIconUrl
    val latestRewardCents = latestTaskSnap.getLong("rewardCents") ?: task.rewardCents

    // 4. Atomically update /tasks/{taskId} and create /submissions/{taskId}
    val submissionRef = submissionsCollection.document(task.id)
    val batch = firestore.batch()
    batch.update(
      taskRef,
      mapOf(
        "assignedUserId" to uid,
        "assignedUserName" to resolvedName,
        "assignedUserEmail" to resolvedEmail.ifEmpty { "user@taskflow.app" },
        "status" to TaskStatus.SUBMITTED,
        "proofText" to cleanedProofText,
        "proofImageUrl" to uploadedPhoto.downloadUrl,
        "proofStoragePath" to uploadedPhoto.storagePath,
        "updatedAt" to FieldValue.serverTimestamp()
      )
    )
    batch.set(
      submissionRef,
      mapOf(
        "taskId" to task.id,
        "taskTitle" to latestTitle,
        "appName" to latestAppName,
        "appIconUrl" to latestAppIconUrl,
        "rewardCents" to latestRewardCents,
        "userId" to uid,
        "userName" to resolvedName,
        "userEmail" to resolvedEmail.ifEmpty { "user@taskflow.app" },
        "proofText" to cleanedProofText,
        "proofImageUrl" to uploadedPhoto.downloadUrl,
        "proofStoragePath" to uploadedPhoto.storagePath,
        "status" to TaskStatus.SUBMITTED,
        "adminFeedback" to "",
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
      )
    )
    batch.commit().await()
  }

  /**
   * Convenience wrapper for submitting mandatory text + photo proof by task parameters.
   */
  suspend fun createSubmissionRecord(
    taskId: String,
    taskTitle: String,
    rewardCents: Long,
    userName: String,
    userEmail: String,
    proofText: String,
    imageUri: Uri?
  ): Result<Unit> {
    val uid = authProvider().currentUser?.uid ?: ""
    val task =
      TaskItem(
        id = taskId,
        title = taskTitle,
        rewardCents = rewardCents,
        assignedUserId = uid
      )
    return submitTaskProof(
      task = task,
      userName = userName,
      userEmail = userEmail,
      proofText = proofText,
      imageUri = imageUri
    )
  }

  /**
   * Reviews a submitted task (Approve or Reject) atomically.
   * When approved, credits the submitting user's `balanceCents` and increments `completedTasksCount`
   * in the same batch as updating `/tasks/{taskId}` and `/submissions/{taskId}`.
   */
  suspend fun reviewSubmission(
    task: TaskItem,
    approve: Boolean,
    adminFeedback: String
  ): Result<Unit> = runCatching {
    require(task.id.isNotBlank()) { "task.id must not be blank" }
    val cleanedFeedback =
      adminFeedback.trim().ifEmpty {
        if (approve) "Approved by Admin" else "Needs revision"
      }
    require(cleanedFeedback.length <= 1000) { "Feedback must be under 1000 characters." }

    val taskRef = tasksCollection.document(task.id)
    val submissionRef = submissionsCollection.document(task.id)

    val latestTaskSnap = taskRef.get().await()
    check(latestTaskSnap.exists()) { "Task ${task.id} does not exist." }
    val currentTaskStatus = latestTaskSnap.getString("status") ?: ""
    check(currentTaskStatus == TaskStatus.SUBMITTED) {
      "Task ${task.id} must be in 'Pending Review' status to review (current: $currentTaskStatus)."
    }

    val latestSubSnap = submissionRef.get().await()
    check(latestSubSnap.exists()) {
      "Submission record for task ${task.id} does not exist."
    }
    val currentSubStatus = latestSubSnap.getString("status") ?: ""
    check(currentSubStatus == TaskStatus.SUBMITTED) {
      "Submission ${task.id} has already been reviewed (status: $currentSubStatus)."
    }

    val submittingUserId =
      (latestSubSnap.getString("userId") ?: latestTaskSnap.getString("assignedUserId") ?: task.assignedUserId)
        .trim()
    val rewardCents = latestTaskSnap.getLong("rewardCents") ?: task.rewardCents
    val newStatus = if (approve) TaskStatus.APPROVED else TaskStatus.REJECTED

    val batch = firestore.batch()
    batch.update(
      taskRef,
      mapOf(
        "status" to newStatus,
        "adminFeedback" to cleanedFeedback,
        "updatedAt" to FieldValue.serverTimestamp()
      )
    )
    batch.update(
      submissionRef,
      mapOf(
        "status" to newStatus,
        "adminFeedback" to cleanedFeedback,
        "updatedAt" to FieldValue.serverTimestamp()
      )
    )

    if (approve && submittingUserId.isNotBlank()) {
      val userRef = usersCollection.document(submittingUserId)
      batch.update(
        userRef,
        mapOf(
          "balanceCents" to FieldValue.increment(rewardCents),
          "completedTasksCount" to FieldValue.increment(1L),
          "assignedTaskId" to task.id,
          "updatedAt" to FieldValue.serverTimestamp()
        )
      )
      val txRef = transactionsCollection.document()
      val submittingUserName =
        (latestSubSnap.getString("userName") ?: task.assignedUserName).ifEmpty { "TaskFlow Member" }
      val taskTitle = (latestTaskSnap.getString("title") ?: task.title).ifEmpty { "Task" }
      batch.set(
        txRef,
        mapOf(
          "userId" to submittingUserId,
          "userName" to submittingUserName,
          "type" to TransactionType.TASK_REWARD,
          "title" to "Task Reward: ${taskTitle.take(60)}",
          "amountCents" to rewardCents,
          "status" to "CREDITED",
          "referenceId" to task.id,
          "createdAt" to FieldValue.serverTimestamp()
        )
      )
    }

    batch.commit().await()
  }

  /**
   * Admin updates a user's `approvalStatus` (`approved`, `pending`, `suspended`).
   */
  suspend fun updateUserApprovalStatus(userId: String, approvalStatus: String): Result<Unit> =
    runCatching {
      require(userId.isNotBlank()) { "userId must not be blank" }
      require(approvalStatus in listOf("approved", "pending", "suspended")) {
        "Invalid approvalStatus: $approvalStatus"
      }
      usersCollection
        .document(userId)
        .update(
          mapOf(
            "approvalStatus" to approvalStatus,
            "updatedAt" to FieldValue.serverTimestamp()
          )
        )
        .await()
    }

  /**
   * Submits a safe demo-only withdrawal request to `/withdrawal_requests/{requestId}`.
   *
   * Security & Privacy Guarantees:
   * - Never collects or stores bank account numbers, IFSC codes, bank names, or UPI IDs in Firestore.
   * - Validates minimum withdrawal amount of ₹200.00 (20,000 paise).
   * - Validates that the requested amount does not exceed the user's available wallet balance.
   * - Never deducts the user's wallet balance (`balanceCents` remains unchanged).
   */
  suspend fun submitDemoWithdrawalRequest(
    uid: String,
    userName: String,
    amountCents: Long,
    method: String
  ): Result<String> = runCatching {
    require(uid.isNotBlank()) { "Must be signed in to submit a withdrawal request." }
    val cleanedMethod = method.trim()
    require(cleanedMethod in WithdrawalMethod.ALL) {
      "Please select a valid withdrawal method (Bank Account or UPI ID)."
    }
    require(amountCents >= MIN_WITHDRAWAL_CENTS) {
      "Minimum withdrawal amount is ₹200.00."
    }
    require(amountCents <= 10_000_000L) {
      "Maximum single demo withdrawal request is ₹1,00,000.00."
    }

    val userSnap = usersCollection.document(uid).get().await()
    check(userSnap.exists()) { "User profile not found." }
    val approvalStatus = userSnap.getString("approvalStatus") ?: "pending"
    check(approvalStatus != "suspended") {
      "Suspended accounts cannot submit withdrawal requests."
    }
    val availableBalanceCents = userSnap.getLong("balanceCents") ?: 0L
    require(amountCents <= availableBalanceCents) {
      "Entered amount (${formatRewardInr(amountCents)}) exceeds your available balance of ${formatRewardInr(availableBalanceCents)}."
    }

    val resolvedUserName =
      (userSnap.getString("displayName") ?: userName).trim().ifEmpty { "TaskFlow User" }

    val docRef = withdrawalRequestsCollection.document()
    val payload =
      mapOf(
        "requestId" to docRef.id,
        "userId" to uid,
        "userName" to resolvedUserName,
        "amountCents" to amountCents,
        "method" to cleanedMethod,
        "status" to "pending_demo",
        "isDemo" to true,
        "createdAt" to FieldValue.serverTimestamp()
      )
    val txRef = transactionsCollection.document()
    val batch = firestore.batch()
    batch.set(docRef, payload)
    batch.set(
      txRef,
      mapOf(
        "userId" to uid,
        "userName" to resolvedUserName,
        "type" to TransactionType.DEMO_WITHDRAWAL,
        "title" to "Demo Withdrawal ($cleanedMethod)",
        "amountCents" to amountCents,
        "status" to "PENDING_DEMO",
        "referenceId" to docRef.id,
        "createdAt" to FieldValue.serverTimestamp()
      )
    )
    batch.commit().await()
    docRef.id
  }

  /**
   * Observes the signed-in user's own demo withdrawal requests in real time.
   */
  fun observeUserWithdrawalRequests(uid: String): Flow<List<DemoWithdrawalRequest>> = callbackFlow {
    require(uid.isNotBlank()) { "uid must not be blank" }
    val listener =
      withdrawalRequestsCollection
        .whereEqualTo("userId", uid)
        .addSnapshotListener { snapshot, error ->
          if (error != null) {
            close(error)
            return@addSnapshotListener
          }
          val items =
            snapshot
              ?.documents
              ?.mapNotNull { it.toObject(DemoWithdrawalRequest::class.java) }
              ?.sortedByDescending { it.createdAt?.seconds ?: 0L }
              ?: emptyList()
          trySend(items)
        }
    awaitClose { listener.remove() }
  }

  /**
   * One-shot fetch of a user's own demo withdrawal requests.
   */
  suspend fun getUserWithdrawalRequests(uid: String): Result<List<DemoWithdrawalRequest>> =
    runCatching {
      require(uid.isNotBlank()) { "uid must not be blank" }
      val snap = withdrawalRequestsCollection.whereEqualTo("userId", uid).get().await()
      snap.documents
        .mapNotNull { it.toObject(DemoWithdrawalRequest::class.java) }
        .sortedByDescending { it.createdAt?.seconds ?: 0L }
    }

  /**
   * Observes all demo withdrawal requests in `/withdrawal_requests` for an authorized admin.
   */
  fun observeAdminWithdrawalRequests(
    adminUid: String,
    isAdminAuthorized: Boolean
  ): Flow<List<DemoWithdrawalRequest>> = callbackFlow {
    require(adminUid.isNotBlank()) { "adminUid must not be blank" }
    if (!isAdminAuthorized) {
      trySend(emptyList())
      awaitClose {}
      return@callbackFlow
    }
    val listener =
      withdrawalRequestsCollection.addSnapshotListener { snapshot, error ->
        if (error != null) {
          close(error)
          return@addSnapshotListener
        }
        val items =
          snapshot
            ?.documents
            ?.mapNotNull { it.toObject(DemoWithdrawalRequest::class.java) }
            ?.sortedByDescending { it.createdAt?.seconds ?: 0L }
            ?: emptyList()
        trySend(items)
      }
    awaitClose { listener.remove() }
  }

  /**
   * One-shot fetch of all demo withdrawal requests for an authorized admin.
   */
  suspend fun getAdminWithdrawalRequests(): Result<List<DemoWithdrawalRequest>> = runCatching {
    val snap = withdrawalRequestsCollection.get().await()
    snap.documents
      .mapNotNull { it.toObject(DemoWithdrawalRequest::class.java) }
      .sortedByDescending { it.createdAt?.seconds ?: 0L }
  }

  /**
   * Claims daily check-in reward for the current user and records the transaction atomically.
   */
  suspend fun claimDailyCheckIn(
    userId: String,
    streakDay: Int,
    rewardCents: Long,
    dateKey: String,
    userName: String,
    verifiedAdCallbackToken: String? = null
  ): Result<Long> = runCatching {
    require(userId.isNotBlank()) { "userId must not be blank" }
    require(streakDay in 1..7) { "streakDay must be between 1 and 7" }
    require(rewardCents == 100L || rewardCents == 700L) { "Invalid check-in reward" }
    require(dateKey.isNotBlank() && dateKey.length == 10) { "Invalid dateKey format" }

    val userRef = usersCollection.document(userId)
    val checkInDocRef = userRef.collection("check_ins").document(dateKey)
    val transactionRef = transactionsCollection.document()

    val batch = firestore.batch()
    batch.set(
      checkInDocRef,
      mapOf(
        "userId" to userId,
        "dateKey" to dateKey,
        "streakDay" to streakDay,
        "rewardCents" to rewardCents,
        "claimedAt" to FieldValue.serverTimestamp()
      )
    )
    batch.update(
      userRef,
      mapOf(
        "balanceCents" to FieldValue.increment(rewardCents),
        "checkInStreak" to streakDay.toLong(),
        "lastCheckInDate" to dateKey,
        "lastCheckInTimestamp" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
      )
    )
    batch.set(
      transactionRef,
      mapOf(
        "userId" to userId,
        "userName" to userName.trim().ifEmpty { "TaskFlow Member" },
        "type" to TransactionType.DAILY_CHECKIN,
        "title" to "Day $streakDay Daily Check-in Reward",
        "amountCents" to rewardCents,
        "status" to "CREDITED",
        "referenceId" to dateKey,
        "createdAt" to FieldValue.serverTimestamp()
      )
    )
    batch.commit().await()
    rewardCents
  }

  /**
   * Observes transactions for a specific user in real time.
   */
  fun observeUserTransactions(uid: String): Flow<List<TransactionRecord>> = callbackFlow {
    require(uid.isNotBlank()) { "uid must not be blank" }
    val listener =
      transactionsCollection
        .whereEqualTo("userId", uid)
        .addSnapshotListener { snapshot, error ->
          if (error != null) {
            close(error)
            return@addSnapshotListener
          }
          val items =
            snapshot
              ?.documents
              ?.mapNotNull { it.toObject(TransactionRecord::class.java) }
              ?.sortedByDescending { it.createdAt?.seconds ?: 0L }
              ?: emptyList()
          trySend(items)
        }
    awaitClose { listener.remove() }
  }

  /**
   * Observes all transactions in real time for authorized Admin.
   */
  fun observeAllTransactions(
    adminUid: String,
    isAdminAuthorized: Boolean
  ): Flow<List<TransactionRecord>> = callbackFlow {
    require(adminUid.isNotBlank()) { "adminUid must not be blank" }
    if (!isAdminAuthorized) {
      trySend(emptyList())
      awaitClose {}
      return@callbackFlow
    }
    val listener =
      transactionsCollection.addSnapshotListener { snapshot, error ->
        if (error != null) {
          close(error)
          return@addSnapshotListener
        }
        val items =
          snapshot
            ?.documents
            ?.mapNotNull { it.toObject(TransactionRecord::class.java) }
            ?.sortedByDescending { it.createdAt?.seconds ?: 0L }
            ?: emptyList()
        trySend(items)
      }
    awaitClose { listener.remove() }
  }

  /**
   * Observes approved users ordered by balance for the Leaderboard.
   */
  fun observeLeaderboardUsers(): Flow<List<UserProfile>> = callbackFlow {
    val listener =
      usersCollection
        .whereEqualTo("approvalStatus", "approved")
        .addSnapshotListener { snapshot, error ->
          if (error != null) {
            close(error)
            return@addSnapshotListener
          }
          val items =
            snapshot
              ?.documents
              ?.mapNotNull { it.toObject(UserProfile::class.java) }
              ?.sortedByDescending { it.balanceCents }
              ?.take(50)
              ?: emptyList()
          trySend(items)
        }
    awaitClose { listener.remove() }
  }

  /**
   * Allows an Admin to update any user's display name.
   */
  suspend fun updateUserDisplayNameByAdmin(
    targetUserId: String,
    newDisplayName: String
  ): Result<Unit> = runCatching {
    require(targetUserId.isNotBlank()) { "targetUserId must not be blank" }
    val cleaned = newDisplayName.trim()
    require(cleaned.isNotEmpty() && cleaned.length <= 100) {
      "Display name must be between 1 and 100 characters."
    }
    usersCollection
      .document(targetUserId)
      .update(
        mapOf(
          "displayName" to cleaned,
          "updatedAt" to FieldValue.serverTimestamp()
        )
      )
      .await()
  }

  suspend fun claimWatchEarnReward(
    userId: String,
    userName: String,
    dateKey: String
  ): Result<Long> = runCatching {
    require(userId.isNotBlank()) { "userId must not be blank" }
    require(dateKey.isNotBlank()) { "Invalid dateKey" }

    val userRef = usersCollection.document(userId)
    val watchStatRef = userRef.collection("watch_stats").document(dateKey)
    val transactionRef = transactionsCollection.document()

    val rewardCents = 100L // ₹1 per video

    firestore.runTransaction { transaction ->
      val snapshot = transaction.get(watchStatRef)
      val currentVideos = snapshot.getLong("videosWatched") ?: 0L
      val currentEarned = snapshot.getLong("totalEarnedCents") ?: 0L

      if (currentVideos >= 25L) {
        throw IllegalStateException("You have reached the maximum limit of 25 watched videos for today.")
      }
      if (currentEarned + rewardCents > 4000L) {
        throw IllegalStateException("You have reached the daily Watch & Earn earning limit of ₹40.")
      }

      val newVideos = currentVideos + 1L
      val newEarned = currentEarned + rewardCents

      transaction.set(
        watchStatRef,
        mapOf(
          "userId" to userId,
          "dateKey" to dateKey,
          "videosWatched" to newVideos,
          "totalEarnedCents" to newEarned,
          "updatedAt" to FieldValue.serverTimestamp()
        ),
        com.google.firebase.firestore.SetOptions.merge()
      )

      transaction.update(
        userRef,
        mapOf(
          "balanceCents" to FieldValue.increment(rewardCents),
          "updatedAt" to FieldValue.serverTimestamp()
        )
      )

      transaction.set(
        transactionRef,
        mapOf(
          "userId" to userId,
          "userName" to userName.trim().ifEmpty { "TaskFlow Member" },
          "type" to TransactionType.WATCH_EARN,
          "title" to "Watch & Earn Video Reward (#$newVideos)",
          "amountCents" to rewardCents,
          "status" to "CREDITED",
          "referenceId" to dateKey,
          "createdAt" to FieldValue.serverTimestamp()
        )
      )
    }.await()

    rewardCents
  }

  fun observeWatchStats(uid: String, dateKey: String): Flow<com.example.data.model.WatchStats> = callbackFlow {
    if (uid.isBlank()) {
      trySend(com.example.data.model.WatchStats())
      close()
      return@callbackFlow
    }
    val listener = usersCollection.document(uid).collection("watch_stats").document(dateKey)
      .addSnapshotListener { snapshot, error ->
        if (error != null) {
          trySend(com.example.data.model.WatchStats())
          return@addSnapshotListener
        }
        val stats = snapshot?.toObject(com.example.data.model.WatchStats::class.java) 
          ?: com.example.data.model.WatchStats(userId = uid, dateKey = dateKey)
        trySend(stats)
      }
    awaitClose { listener.remove() }
  }
}
