const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");
const crypto = require("node:crypto");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ADMIN_UID = "admin_001";
const ADMIN_EMAIL = "dasakash24059@gmail.com";
const SECONDARY_ADMIN_UID = "admin_002";
const SECONDARY_ADMIN_EMAIL = "ops@taskflow.app";
const ALICE_UID = "alice_123";
const ALICE_EMAIL = "alice@example.com";
const BOB_UID = "bob_456";
const BOB_EMAIL = "bob@example.com";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function nowTimestamp() {
  return new Date();
}

test("1. Default deny & disabled withdrawals: unauthenticated or withdrawal writes always fail", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("tasks").get());
  await assertFails(unauthDb.collection("submissions").get());

  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, {
      email: ADMIN_EMAIL,
      email_verified: true,
    })
    .firestore();
  await assertFails(
    adminDb.collection("withdrawals").doc("w_1").set({
      userId: ALICE_UID,
      amountCents: 500,
    })
  );
});

test("2. Verified vs. unverified email, normal user registration ('pending'), and user attempting to change own role or balance", async () => {
  const ts = nowTimestamp();

  // Unverified email registration -> MUST FAIL
  const unverifiedAliceDb = testEnv
    .authenticatedContext(ALICE_UID, {
      email: ALICE_EMAIL,
      email_verified: false,
    })
    .firestore();

  await assertFails(
    unverifiedAliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: ALICE_EMAIL,
      displayName: "Alice",
      role: "user",
      approvalStatus: "pending",
      balanceCents: 0,
      completedTasksCount: 0,
      assignedTaskId: "",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Verified email context
  const aliceDb = testEnv
    .authenticatedContext(ALICE_UID, {
      email: ALICE_EMAIL,
      email_verified: true,
    })
    .firestore();

  // Attempting self-registration with approvalStatus='approved' -> MUST FAIL
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: ALICE_EMAIL,
      displayName: "Alice",
      role: "user",
      approvalStatus: "approved",
      balanceCents: 0,
      completedTasksCount: 0,
      assignedTaskId: "",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Normal user registration with verified email, role='user', approvalStatus='pending', balanceCents=0 -> MUST SUCCEED
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: ALICE_EMAIL,
      displayName: "Alice",
      role: "user",
      approvalStatus: "pending",
      balanceCents: 0,
      completedTasksCount: 0,
      assignedTaskId: "",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // User attempting to change their own role -> MUST FAIL
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      role: "admin",
      updatedAt: new Date(),
    })
  );

  // User attempting to change their own balance -> MUST FAIL
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      balanceCents: 5000,
      updatedAt: new Date(),
    })
  );

  // User attempting to self-approve -> MUST FAIL
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      approvalStatus: "approved",
      updatedAt: new Date(),
    })
  );

  // User updating only their displayName -> MUST SUCCEED
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      displayName: "Alice Updated",
      updatedAt: new Date(),
    })
  );
});

test("3. Only the verified bootstrap owner can create or delete /admins records", async () => {
  const ts = nowTimestamp();
  const bootstrapAdminDb = testEnv
    .authenticatedContext(ADMIN_UID, {
      email: ADMIN_EMAIL,
      email_verified: true,
    })
    .firestore();

  // Bootstrap owner can create secondary admin record -> MUST SUCCEED
  await assertSucceeds(
    bootstrapAdminDb.collection("admins").doc(SECONDARY_ADMIN_UID).set({
      userId: SECONDARY_ADMIN_UID,
      email: SECONDARY_ADMIN_EMAIL,
      createdAt: ts,
    })
  );

  // Secondary admin can list collections via isAdmin(), but CANNOT grant or delete /admins records
  const secondaryAdminDb = testEnv
    .authenticatedContext(SECONDARY_ADMIN_UID, {
      email: SECONDARY_ADMIN_EMAIL,
      email_verified: true,
    })
    .firestore();

  await assertSucceeds(secondaryAdminDb.collection("users").get());
  await assertFails(
    secondaryAdminDb.collection("admins").doc(BOB_UID).set({
      userId: BOB_UID,
      email: BOB_EMAIL,
      createdAt: ts,
    })
  );
  await assertFails(
    secondaryAdminDb.collection("admins").doc(ADMIN_UID).delete()
  );
});

test("4. Mandatory text + photo proof enforcement (including second user account submitting on Admin-created task), valid atomic approval & exact INR credit, and repeated approval failure", async () => {
  const ts = nowTimestamp();
  const taskId = "task_atomic_1";
  const sampleImageUrl =
    "https://firebasestorage.googleapis.com/v0/b/taskflow-8852d.firebasestorage.app/o/submissions%2Falice_123%2Ftask_atomic_1%2Fproof_1.jpg?alt=media";
  const sampleStoragePath =
    "submissions/alice_123/task_atomic_1/proof_1.jpg";

  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("users").doc(ADMIN_UID).set({
      userId: ADMIN_UID,
      email: ADMIN_EMAIL,
      displayName: "Admin",
      role: "admin",
      approvalStatus: "approved",
      balanceCents: 0,
      completedTasksCount: 0,
      assignedTaskId: taskId,
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: ALICE_EMAIL,
      displayName: "Alice",
      role: "user",
      approvalStatus: "pending",
      balanceCents: 0,
      completedTasksCount: 0,
      assignedTaskId: "",
      createdAt: ts,
      updatedAt: ts,
    });
    // Task initially created by Admin with Admin as initial assignee
    await db.collection("tasks").doc(taskId).set({
      title: "QA Check",
      appName: "TaskFlow Companion",
      appIconUrl: "https://example.com/icon.png",
      description: "Verify mobile layout",
      instructions: "1. Open app\n2. Capture screenshot",
      requiredProof: "Provide written notes and welcome screen screenshot",
      taskLink: "https://play.google.com/store/apps/details?id=com.example.app",
      category: "QA Testing",
      rewardCents: 1500,
      assignedUserId: ADMIN_UID,
      assignedUserName: "Admin",
      assignedUserEmail: ADMIN_EMAIL,
      status: "pending",
      proofText: "",
      proofImageUrl: "",
      proofStoragePath: "",
      adminFeedback: "",
      createdBy: ADMIN_UID,
      createdAt: ts,
      updatedAt: ts,
    });
  });

  const aliceDb = testEnv
    .authenticatedContext(ALICE_UID, {
      email: ALICE_EMAIL,
      email_verified: true,
    })
    .firestore();

  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, {
      email: ADMIN_EMAIL,
      email_verified: true,
    })
    .firestore();

  // Admin attempting an unrelated balance increase while task is pending -> MUST FAIL
  await assertFails(
    adminDb.collection("users").doc(ALICE_UID).update({
      balanceCents: 1500,
      completedTasksCount: 1,
      assignedTaskId: taskId,
      updatedAt: new Date(),
    })
  );

  // 4a. Text-only submission (missing photo proofImageUrl / proofStoragePath) -> MUST FAIL
  const textOnlyBatch = aliceDb.batch();
  textOnlyBatch.update(aliceDb.collection("tasks").doc(taskId), {
    assignedUserId: ALICE_UID,
    assignedUserName: "Alice",
    assignedUserEmail: ALICE_EMAIL,
    status: "submitted",
    proofText: "Completed QA check without screenshot.",
    proofImageUrl: "",
    proofStoragePath: "",
    updatedAt: ts,
  });
  textOnlyBatch.set(aliceDb.collection("submissions").doc(taskId), {
    taskId: taskId,
    taskTitle: "QA Check",
    rewardCents: 1500,
    userId: ALICE_UID,
    userName: "Alice",
    userEmail: ALICE_EMAIL,
    proofText: "Completed QA check without screenshot.",
    proofImageUrl: "",
    proofStoragePath: "",
    status: "submitted",
    adminFeedback: "",
    createdAt: ts,
    updatedAt: ts,
  });
  await assertFails(textOnlyBatch.commit());

  // 4b. Photo-only submission (missing written proofText) -> MUST FAIL
  const photoOnlyBatch = aliceDb.batch();
  photoOnlyBatch.update(aliceDb.collection("tasks").doc(taskId), {
    assignedUserId: ALICE_UID,
    assignedUserName: "Alice",
    assignedUserEmail: ALICE_EMAIL,
    status: "submitted",
    proofText: "",
    proofImageUrl: sampleImageUrl,
    proofStoragePath: sampleStoragePath,
    updatedAt: ts,
  });
  photoOnlyBatch.set(aliceDb.collection("submissions").doc(taskId), {
    taskId: taskId,
    taskTitle: "QA Check",
    rewardCents: 1500,
    userId: ALICE_UID,
    userName: "Alice",
    userEmail: ALICE_EMAIL,
    proofText: "",
    proofImageUrl: sampleImageUrl,
    proofStoragePath: sampleStoragePath,
    status: "submitted",
    adminFeedback: "",
    createdAt: ts,
    updatedAt: ts,
  });
  await assertFails(photoOnlyBatch.commit());

  // 4c. Pending user (Alice with approvalStatus == 'pending') attempting valid atomic task submission -> MUST FAIL
  const pendingUserBatch = aliceDb.batch();
  pendingUserBatch.update(aliceDb.collection("tasks").doc(taskId), {
    assignedUserId: ALICE_UID,
    assignedUserName: "Alice",
    assignedUserEmail: ALICE_EMAIL,
    status: "submitted",
    proofText: "Completed QA check across Android screens.",
    proofImageUrl: sampleImageUrl,
    proofStoragePath: sampleStoragePath,
    updatedAt: ts,
  });
  pendingUserBatch.set(aliceDb.collection("submissions").doc(taskId), {
    taskId: taskId,
    taskTitle: "QA Check",
    appName: "TaskFlow Companion",
    appIconUrl: "https://example.com/icon.png",
    rewardCents: 1500,
    userId: ALICE_UID,
    userName: "Alice",
    userEmail: ALICE_EMAIL,
    proofText: "Completed QA check across Android screens.",
    proofImageUrl: sampleImageUrl,
    proofStoragePath: sampleStoragePath,
    status: "submitted",
    adminFeedback: "",
    createdAt: ts,
    updatedAt: ts,
  });
  await assertFails(pendingUserBatch.commit());

  // 4d. Admin approves Alice (approvalStatus -> 'approved'), then Alice submits BOTH written proofText AND uploaded photo -> MUST SUCCEED
  await assertSucceeds(
    adminDb.collection("users").doc(ALICE_UID).update({
      approvalStatus: "approved",
      updatedAt: new Date(),
    })
  );

  const submitTs = new Date();
  const submitBatch = aliceDb.batch();
  submitBatch.update(aliceDb.collection("tasks").doc(taskId), {
    assignedUserId: ALICE_UID,
    assignedUserName: "Alice",
    assignedUserEmail: ALICE_EMAIL,
    status: "submitted",
    proofText: "Completed QA check across Android screens.",
    proofImageUrl: sampleImageUrl,
    proofStoragePath: sampleStoragePath,
    updatedAt: submitTs,
  });
  submitBatch.set(aliceDb.collection("submissions").doc(taskId), {
    taskId: taskId,
    taskTitle: "QA Check",
    appName: "TaskFlow Companion",
    appIconUrl: "https://example.com/icon.png",
    rewardCents: 1500,
    userId: ALICE_UID,
    userName: "Alice",
    userEmail: ALICE_EMAIL,
    proofText: "Completed QA check across Android screens.",
    proofImageUrl: sampleImageUrl,
    proofStoragePath: sampleStoragePath,
    status: "submitted",
    adminFeedback: "",
    createdAt: submitTs,
    updatedAt: submitTs,
  });
  await assertSucceeds(submitBatch.commit());

  // Admin can list and read the submission from /submissions
  await assertSucceeds(adminDb.collection("submissions").get());
  await assertSucceeds(adminDb.collection("submissions").doc(taskId).get());

  // Admin attempting an unrelated balance increase without approving task & submission in the same batch -> MUST FAIL
  await assertFails(
    adminDb.collection("users").doc(ALICE_UID).update({
      balanceCents: 1500,
      completedTasksCount: 1,
      assignedTaskId: taskId,
      updatedAt: new Date(),
    })
  );

  // Admin attempting an approval batch with an incorrect reward credit (2500 instead of 1500) -> MUST FAIL
  const wrongAmountBatch = adminDb.batch();
  wrongAmountBatch.update(adminDb.collection("tasks").doc(taskId), {
    status: "approved",
    adminFeedback: "Approved",
    updatedAt: new Date(),
  });
  wrongAmountBatch.update(adminDb.collection("submissions").doc(taskId), {
    status: "approved",
    adminFeedback: "Approved",
    updatedAt: new Date(),
  });
  wrongAmountBatch.update(adminDb.collection("users").doc(ALICE_UID), {
    balanceCents: 2500,
    completedTasksCount: 1,
    assignedTaskId: taskId,
    updatedAt: new Date(),
  });
  await assertFails(wrongAmountBatch.commit());

  // Valid Atomic Task Approval & Exact Reward Credit (1500 cents, +1 completedTasksCount) -> MUST SUCCEED
  const approveTs = new Date();
  const approveBatch = adminDb.batch();
  approveBatch.update(adminDb.collection("tasks").doc(taskId), {
    status: "approved",
    adminFeedback: "Verified and approved!",
    updatedAt: approveTs,
  });
  approveBatch.update(adminDb.collection("submissions").doc(taskId), {
    status: "approved",
    adminFeedback: "Verified and approved!",
    updatedAt: approveTs,
  });
  approveBatch.update(adminDb.collection("users").doc(ALICE_UID), {
    balanceCents: 1500,
    completedTasksCount: 1,
    assignedTaskId: taskId,
    updatedAt: approveTs,
  });
  await assertSucceeds(approveBatch.commit());

  // Repeated Approval Attempt on already approved task/submission -> MUST FAIL
  const replayBatch = adminDb.batch();
  replayBatch.update(adminDb.collection("tasks").doc(taskId), {
    status: "approved",
    adminFeedback: "Second approve attempt",
    updatedAt: new Date(),
  });
  replayBatch.update(adminDb.collection("submissions").doc(taskId), {
    status: "approved",
    adminFeedback: "Second approve attempt",
    updatedAt: new Date(),
  });
  replayBatch.update(adminDb.collection("users").doc(ALICE_UID), {
    balanceCents: 3000,
    completedTasksCount: 2,
    assignedTaskId: taskId,
    updatedAt: new Date(),
  });
  await assertFails(replayBatch.commit());
});

test("5. Task rejection without reward credit succeeds, and rejection cannot credit balance or be re-approved later", async () => {
  const ts = nowTimestamp();
  const taskId = "task_reject_1";
  const sampleImageUrl =
    "https://firebasestorage.googleapis.com/v0/b/taskflow-8852d.firebasestorage.app/o/submissions%2Falice_123%2Ftask_reject_1%2Fproof_1.jpg?alt=media";
  const sampleStoragePath =
    "submissions/alice_123/task_reject_1/proof_1.jpg";

  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: ALICE_EMAIL,
      displayName: "Alice",
      role: "user",
      approvalStatus: "approved",
      balanceCents: 500,
      completedTasksCount: 1,
      assignedTaskId: taskId,
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("tasks").doc(taskId).set({
      title: "Copy Review",
      description: "Verify onboarding text",
      category: "Content Review",
      rewardCents: 1200,
      assignedUserId: ALICE_UID,
      assignedUserName: "Alice",
      assignedUserEmail: ALICE_EMAIL,
      status: "submitted",
      proofText: "Reviewed onboarding text.",
      proofImageUrl: sampleImageUrl,
      proofStoragePath: sampleStoragePath,
      adminFeedback: "",
      createdBy: ADMIN_UID,
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("submissions").doc(taskId).set({
      taskId: taskId,
      taskTitle: "Copy Review",
      rewardCents: 1200,
      userId: ALICE_UID,
      userName: "Alice",
      userEmail: ALICE_EMAIL,
      proofText: "Reviewed onboarding text.",
      proofImageUrl: sampleImageUrl,
      proofStoragePath: sampleStoragePath,
      status: "submitted",
      adminFeedback: "",
      createdAt: ts,
      updatedAt: ts,
    });
  });

  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, {
      email: ADMIN_EMAIL,
      email_verified: true,
    })
    .firestore();

  // Attempt to reject while increasing user balance -> MUST FAIL
  const invalidRejectBatch = adminDb.batch();
  invalidRejectBatch.update(adminDb.collection("tasks").doc(taskId), {
    status: "rejected",
    adminFeedback: "Incomplete",
    updatedAt: new Date(),
  });
  invalidRejectBatch.update(adminDb.collection("submissions").doc(taskId), {
    status: "rejected",
    adminFeedback: "Incomplete",
    updatedAt: new Date(),
  });
  invalidRejectBatch.update(adminDb.collection("users").doc(ALICE_UID), {
    balanceCents: 1700,
    completedTasksCount: 2,
    assignedTaskId: taskId,
    updatedAt: new Date(),
  });
  await assertFails(invalidRejectBatch.commit());

  // Valid Atomic Rejection without reward credit -> MUST SUCCEED
  const rejectTs = new Date();
  const rejectBatch = adminDb.batch();
  rejectBatch.update(adminDb.collection("tasks").doc(taskId), {
    status: "rejected",
    adminFeedback: "Please include device logs.",
    updatedAt: rejectTs,
  });
  rejectBatch.update(adminDb.collection("submissions").doc(taskId), {
    status: "rejected",
    adminFeedback: "Please include device logs.",
    updatedAt: rejectTs,
  });
  await assertSucceeds(rejectBatch.commit());

  // Attempt to approve an already rejected task/submission -> MUST FAIL
  const postRejectApproveBatch = adminDb.batch();
  postRejectApproveBatch.update(adminDb.collection("tasks").doc(taskId), {
    status: "approved",
    adminFeedback: "Flipping rejected to approved",
    updatedAt: new Date(),
  });
  postRejectApproveBatch.update(adminDb.collection("submissions").doc(taskId), {
    status: "approved",
    adminFeedback: "Flipping rejected to approved",
    updatedAt: new Date(),
  });
  postRejectApproveBatch.update(adminDb.collection("users").doc(ALICE_UID), {
    balanceCents: 1700,
    completedTasksCount: 2,
    assignedTaskId: taskId,
    updatedAt: new Date(),
  });
  await assertFails(postRejectApproveBatch.commit());
});

test("6. Atomic task creation with HTTPS URL & instructions, task reassignment, task deletion consistency, and post-batch approval ownership tamper checks", async () => {
  const ts = nowTimestamp();
  const taskId = "task_assign_consistency_1";

  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: ALICE_EMAIL,
      displayName: "Alice",
      role: "user",
      approvalStatus: "approved",
      balanceCents: 0,
      completedTasksCount: 0,
      assignedTaskId: "",
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("users").doc(BOB_UID).set({
      userId: BOB_UID,
      email: BOB_EMAIL,
      displayName: "Bob",
      role: "user",
      approvalStatus: "approved",
      balanceCents: 0,
      completedTasksCount: 0,
      assignedTaskId: "",
      createdAt: ts,
      updatedAt: ts,
    });
  });

  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, {
      email: ADMIN_EMAIL,
      email_verified: true,
    })
    .firestore();

  // 1. Admin creating a task WITHOUT updating the assigned user's assignedTaskId -> MUST FAIL
  await assertFails(
    adminDb.collection("tasks").doc(taskId).set({
      title: "Standalone Task",
      description: "Created without updating user assignedTaskId",
      category: "General",
      rewardCents: 1000,
      assignedUserId: ALICE_UID,
      assignedUserName: "Alice",
      assignedUserEmail: ALICE_EMAIL,
      status: "pending",
      proofText: "",
      adminFeedback: "",
      createdBy: ADMIN_UID,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // 1b. Admin creating a task with an insecure non-HTTPS taskLink -> MUST FAIL
  const insecureUrlBatch = adminDb.batch();
  insecureUrlBatch.set(adminDb.collection("tasks").doc(taskId), {
    title: "Insecure URL Task",
    description: "Has http:// link",
    instructions: "Step 1",
    taskLink: "http://insecure.example.com",
    category: "General",
    rewardCents: 1000,
    assignedUserId: ALICE_UID,
    assignedUserName: "Alice",
    assignedUserEmail: ALICE_EMAIL,
    status: "pending",
    proofText: "",
    proofImageUrl: "",
    proofStoragePath: "",
    adminFeedback: "",
    createdBy: ADMIN_UID,
    createdAt: ts,
    updatedAt: ts,
  });
  insecureUrlBatch.update(adminDb.collection("users").doc(ALICE_UID), {
    assignedTaskId: taskId,
    updatedAt: ts,
  });
  await assertFails(insecureUrlBatch.commit());

  // 2. Admin creating a task with valid HTTPS taskLink & step-by-step instructions AND updating Alice's assignedTaskId atomically -> MUST SUCCEED
  const createBatch = adminDb.batch();
  createBatch.set(adminDb.collection("tasks").doc(taskId), {
    title: "Consistent Task",
    appName: "TaskFlow App",
    appIconUrl: "https://example.com/icon.png",
    description: "Created atomically with user assignedTaskId",
    instructions: "1. Open Google Play Store\n2. Install & screenshot",
    requiredProof: "Screenshot of installed app",
    taskLink: "https://play.google.com/store/apps/details?id=com.example.app",
    category: "App Install",
    rewardCents: 1000,
    assignedUserId: ALICE_UID,
    assignedUserName: "Alice",
    assignedUserEmail: ALICE_EMAIL,
    status: "pending",
    proofText: "",
    proofImageUrl: "",
    proofStoragePath: "",
    adminFeedback: "",
    createdBy: ADMIN_UID,
    createdAt: ts,
    updatedAt: ts,
  });
  createBatch.update(adminDb.collection("users").doc(ALICE_UID), {
    assignedTaskId: taskId,
    updatedAt: ts,
  });
  await assertSucceeds(createBatch.commit());

  // 2a. Any authenticated user (including newly registered Bob) can read and list tasks created by Admin -> MUST SUCCEED
  const bobDb = testEnv
    .authenticatedContext(BOB_UID, {
      email: BOB_EMAIL,
      email_verified: true,
    })
    .firestore();
  await assertSucceeds(bobDb.collection("tasks").doc(taskId).get());
  await assertSucceeds(bobDb.collection("tasks").get());

  // 2b. Admin attempting to clear Alice's assignedTaskId to "" alone while taskId still exists and is assigned to Alice -> MUST FAIL
  await assertFails(
    adminDb.collection("users").doc(ALICE_UID).update({
      assignedTaskId: "",
      updatedAt: new Date(),
    })
  );

  // 3. Admin reassigning pending task from Alice to Bob WITHOUT clearing Alice's assignedTaskId -> MUST FAIL
  const incompleteReassignBatch = adminDb.batch();
  incompleteReassignBatch.update(adminDb.collection("tasks").doc(taskId), {
    assignedUserId: BOB_UID,
    assignedUserName: "Bob",
    assignedUserEmail: BOB_EMAIL,
    updatedAt: new Date(),
  });
  incompleteReassignBatch.update(adminDb.collection("users").doc(BOB_UID), {
    assignedTaskId: taskId,
    updatedAt: new Date(),
  });
  await assertFails(incompleteReassignBatch.commit());

  // 4. Admin reassigning pending task from Alice to Bob atomically (updating Bob and clearing Alice) -> MUST SUCCEED
  const reassignTs = new Date();
  const validReassignBatch = adminDb.batch();
  validReassignBatch.update(adminDb.collection("tasks").doc(taskId), {
    assignedUserId: BOB_UID,
    assignedUserName: "Bob",
    assignedUserEmail: BOB_EMAIL,
    updatedAt: reassignTs,
  });
  validReassignBatch.update(adminDb.collection("users").doc(BOB_UID), {
    assignedTaskId: taskId,
    updatedAt: reassignTs,
  });
  validReassignBatch.update(adminDb.collection("users").doc(ALICE_UID), {
    assignedTaskId: "",
    updatedAt: reassignTs,
  });
  await assertSucceeds(validReassignBatch.commit());

  // 4b. Admin attempting to clear Bob's assignedTaskId to "" alone while taskId is now assigned to Bob -> MUST FAIL
  await assertFails(
    adminDb.collection("users").doc(BOB_UID).update({
      assignedTaskId: "",
      updatedAt: new Date(),
    })
  );

  // 5. Admin attempting to delete pending task WITHOUT clearing Bob's assignedTaskId -> MUST FAIL
  await assertFails(adminDb.collection("tasks").doc(taskId).delete());

  // 6. Admin deleting pending task AND clearing Bob's assignedTaskId atomically -> MUST SUCCEED
  const deleteBatch = adminDb.batch();
  deleteBatch.delete(adminDb.collection("tasks").doc(taskId));
  deleteBatch.update(adminDb.collection("users").doc(BOB_UID), {
    assignedTaskId: "",
    updatedAt: new Date(),
  });
  await assertSucceeds(deleteBatch.commit());
});

test("7. Fixed-Denomination Withdrawals & Gift Cards: immediate atomic coin deduction, unique gift codes, single atomic refund, and owner-only privacy", async () => {
  const ts = nowTimestamp();

  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("users").doc(ADMIN_UID).set({
      userId: ADMIN_UID,
      email: ADMIN_EMAIL,
      displayName: "Admin",
      role: "admin",
      approvalStatus: "approved",
      balanceCents: 0,
      balanceCoins: 0,
      eligibleCoins: 0,
      promotionalCoins: 0,
      completedTasksCount: 0,
      assignedTaskId: "",
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: ALICE_EMAIL,
      displayName: "Alice",
      role: "user",
      approvalStatus: "approved",
      balanceCents: 60000, // ₹600 = 75,000 eligible coins
      balanceCoins: 75000,
      eligibleCoins: 75000,
      promotionalCoins: 0,
      completedTasksCount: 3,
      assignedTaskId: "",
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("users").doc(BOB_UID).set({
      userId: BOB_UID,
      email: BOB_EMAIL,
      displayName: "Bob",
      role: "user",
      approvalStatus: "approved",
      balanceCents: 0,
      balanceCoins: 0,
      eligibleCoins: 0,
      promotionalCoins: 0,
      completedTasksCount: 0,
      assignedTaskId: "",
      createdAt: ts,
      updatedAt: ts,
    });
  });

  const aliceDb = testEnv
    .authenticatedContext(ALICE_UID, {
      email: ALICE_EMAIL,
      email_verified: true,
    })
    .firestore();

  const bobDb = testEnv
    .authenticatedContext(BOB_UID, {
      email: BOB_EMAIL,
      email_verified: true,
    })
    .firestore();

  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, {
      email: ADMIN_EMAIL,
      email_verified: true,
    })
    .firestore();

  // 1. Non-admin CANNOT modify /app_config/withdrawal_availability; Admin CAN
  await assertFails(
    aliceDb.collection("app_config").doc("withdrawal_availability").set({
      upiEnabled: false,
      updatedAt: ts,
    })
  );
  await assertSucceeds(
    adminDb.collection("app_config").doc("withdrawal_availability").set({
      upiEnabled: true,
      amazonEnabled: true,
      googlePlayEnabled: true,
      flipkartEnabled: true,
      updatedAt: ts,
    })
  );
  await assertSucceeds(aliceDb.collection("app_config").doc("withdrawal_availability").get());

  // 2. Unapproved / mismatched denomination (UPI ₹200 with wrong coins 25000 instead of 33000) -> MUST FAIL
  const badDenomId = "req_bad_denom";
  const badBatch = aliceDb.batch();
  badBatch.set(aliceDb.collection("withdrawal_requests").doc(badDenomId), {
    requestId: badDenomId,
    userId: ALICE_UID,
    userName: "Alice",
    method: "UPI",
    amountRupees: 200,
    amountCents: 20000,
    amountCoins: 25000,
    upiId: "alice@upi",
    status: "SUBMITTED",
    statusNote: "",
    adminActionBy: "",
    paymentReference: "",
    giftCode: "",
    giftCodeHash: "",
    coinsDeducted: true,
    coinsRefunded: false,
    deductedBalanceCoins: 25000,
    deductedEligibleCoins: 25000,
    deductedPromotionalCoins: 0,
    deductedBalanceCents: 20000,
    idempotencyKey: "idem_bad_1",
    isDemo: false,
    createdAt: ts,
    updatedAt: ts,
  });
  badBatch.update(aliceDb.collection("users").doc(ALICE_UID), {
    balanceCoins: 50000,
    eligibleCoins: 50000,
    promotionalCoins: 0,
    balanceCents: 40000,
    lastWithdrawalRequestId: badDenomId,
    updatedAt: ts,
  });
  badBatch.set(aliceDb.collection("transactions").doc(`wd_debit_${badDenomId}`), {
    userId: ALICE_UID,
    userName: "Alice",
    type: "WITHDRAWAL_DEBIT",
    title: "UPI Withdrawal (₹200)",
    amountCents: 20000,
    amountCoins: 25000,
    status: "DEBITED",
    referenceId: badDenomId,
    createdAt: ts,
  });
  await assertFails(badBatch.commit());

  // 3. Creating withdrawal request WITHOUT deducting user's coins -> MUST FAIL
  await assertFails(
    aliceDb.collection("withdrawal_requests").doc("req_no_deduct").set({
      requestId: "req_no_deduct",
      userId: ALICE_UID,
      userName: "Alice",
      method: "UPI",
      amountRupees: 200,
      amountCents: 20000,
      amountCoins: 33000,
      upiId: "alice@upi",
      status: "SUBMITTED",
      statusNote: "",
      adminActionBy: "",
      paymentReference: "",
      giftCode: "",
      giftCodeHash: "",
      coinsDeducted: true,
      coinsRefunded: false,
      deductedBalanceCoins: 33000,
      deductedEligibleCoins: 33000,
      deductedPromotionalCoins: 0,
      deductedBalanceCents: 26400,
      idempotencyKey: "idem_no_deduct",
      isDemo: false,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // 4. Valid atomic Amazon Gift Card ₹150 (33,000 Coins) submission -> MUST SUCCEED
  // 75,000 eligible - 33,000 = 42,000 remaining; floor(33000*100/125) = 26400 paise -> 60000 - 26400 = 33600
  const gcReqId = "req_gc_150";
  const gcBatch = aliceDb.batch();
  gcBatch.set(aliceDb.collection("withdrawal_requests").doc(gcReqId), {
    requestId: gcReqId,
    userId: ALICE_UID,
    userName: "Alice",
    method: "AMAZON_GIFT_CARD",
    amountRupees: 150,
    amountCents: 15000,
    amountCoins: 33000,
    upiId: "",
    status: "SUBMITTED",
    statusNote: "",
    adminActionBy: "",
    paymentReference: "",
    giftCode: "",
    giftCodeHash: "",
    coinsDeducted: true,
    coinsRefunded: false,
    deductedBalanceCoins: 33000,
    deductedEligibleCoins: 33000,
    deductedPromotionalCoins: 0,
    deductedBalanceCents: 26400,
    idempotencyKey: gcReqId,
    isDemo: false,
    createdAt: ts,
    updatedAt: ts,
  });
  gcBatch.update(aliceDb.collection("users").doc(ALICE_UID), {
    balanceCoins: 42000,
    eligibleCoins: 42000,
    promotionalCoins: 0,
    balanceCents: 33600,
    lastWithdrawalRequestId: gcReqId,
    updatedAt: ts,
  });
  gcBatch.set(aliceDb.collection("transactions").doc(`wd_debit_${gcReqId}`), {
    userId: ALICE_UID,
    userName: "Alice",
    type: "WITHDRAWAL_DEBIT",
    title: "Amazon Gift Card (₹150)",
    amountCents: 15000,
    amountCoins: 33000,
    status: "DEBITED",
    referenceId: gcReqId,
    createdAt: ts,
  });
  gcBatch.set(aliceDb.collection("admin_notifications").doc(`admin_wd_${gcReqId}`), {
    userId: ALICE_UID,
    userName: "Alice",
    title: "New Withdrawal Request",
    body: "Alice requested Amazon Gift Card ₹150",
    type: "WITHDRAWAL_REQUEST",
    referenceId: gcReqId,
    isRead: false,
    createdAt: ts,
  });
  await assertSucceeds(gcBatch.commit());

  // 5. Cross-user privacy: Bob CANNOT read Alice's withdrawal request
  await assertFails(bobDb.collection("withdrawal_requests").doc(gcReqId).get());

  // 6. Admin accepts request -> UNDER_REVIEW without modifying coins -> MUST SUCCEED
  await assertSucceeds(
    adminDb.collection("withdrawal_requests").doc(gcReqId).update({
      status: "UNDER_REVIEW",
      statusNote: "Accepted for manual review",
      adminActionBy: ADMIN_UID,
      reviewedAt: new Date(),
      updatedAt: new Date(),
    })
  );

  // 7. Admin fulfills Gift Card with unique codeHash -> MUST SUCCEED
  const codeHash = crypto.createHash("sha256").update("AMZN-TEST-9988-7766").digest("hex");
  const fulfillBatch = adminDb.batch();
  fulfillBatch.set(adminDb.collection("used_gift_codes").doc(codeHash), {
    codeHash,
    requestId: gcReqId,
    method: "AMAZON_GIFT_CARD",
    amountRupees: 150,
    assignedBy: ADMIN_UID,
    assignedAt: new Date(),
  });
  fulfillBatch.update(adminDb.collection("withdrawal_requests").doc(gcReqId), {
    status: "COMPLETED",
    statusNote: "Delivered",
    adminActionBy: ADMIN_UID,
    giftCode: "AMZN-TEST-9988-7766",
    giftCodeHash: codeHash,
    giftCodeAssignedAt: new Date(),
    completedAt: new Date(),
    updatedAt: new Date(),
  });
  await assertSucceeds(fulfillBatch.commit());

  // 8. Admin attempting to mutate terminal COMPLETED request -> MUST FAIL
  await assertFails(
    adminDb.collection("withdrawal_requests").doc(gcReqId).update({
      status: "REJECTED",
      statusNote: "Cannot reject completed",
      adminActionBy: ADMIN_UID,
      coinsRefunded: true,
      rejectedAt: new Date(),
      updatedAt: new Date(),
    })
  );

  // 9. Alice submits a UPI ₹200 (33,000 Coins) request using remaining 42,000 Coins -> MUST SUCCEED
  const upiReqId = "req_upi_200";
  const upiBatch = aliceDb.batch();
  upiBatch.set(aliceDb.collection("withdrawal_requests").doc(upiReqId), {
    requestId: upiReqId,
    userId: ALICE_UID,
    userName: "Alice",
    method: "UPI",
    amountRupees: 200,
    amountCents: 20000,
    amountCoins: 33000,
    upiId: "alice@upi",
    status: "SUBMITTED",
    statusNote: "",
    adminActionBy: "",
    paymentReference: "",
    giftCode: "",
    giftCodeHash: "",
    coinsDeducted: true,
    coinsRefunded: false,
    deductedBalanceCoins: 33000,
    deductedEligibleCoins: 33000,
    deductedPromotionalCoins: 0,
    deductedBalanceCents: 26400,
    idempotencyKey: upiReqId,
    isDemo: false,
    createdAt: ts,
    updatedAt: ts,
  });
  upiBatch.update(aliceDb.collection("users").doc(ALICE_UID), {
    balanceCoins: 9000,
    eligibleCoins: 9000,
    promotionalCoins: 0,
    balanceCents: 7200,
    lastWithdrawalRequestId: upiReqId,
    updatedAt: ts,
  });
  upiBatch.set(aliceDb.collection("transactions").doc(`wd_debit_${upiReqId}`), {
    userId: ALICE_UID,
    userName: "Alice",
    type: "WITHDRAWAL_DEBIT",
    title: "UPI Withdrawal (₹200)",
    amountCents: 20000,
    amountCoins: 33000,
    status: "DEBITED",
    referenceId: upiReqId,
    createdAt: ts,
  });
  await assertSucceeds(upiBatch.commit());

  // 10. Admin rejects UPI request and atomically refunds coins ONCE -> MUST SUCCEED
  const refundTs = new Date();
  const rejectBatch = adminDb.batch();
  rejectBatch.update(adminDb.collection("withdrawal_requests").doc(upiReqId), {
    status: "REJECTED",
    statusNote: "Invalid UPI handle",
    adminActionBy: ADMIN_UID,
    coinsRefunded: true,
    rejectedAt: refundTs,
    updatedAt: refundTs,
  });
  rejectBatch.update(adminDb.collection("users").doc(ALICE_UID), {
    balanceCoins: 42000,
    eligibleCoins: 42000,
    promotionalCoins: 0,
    balanceCents: 33600,
    lastRefundedWithdrawalId: upiReqId,
    updatedAt: refundTs,
  });
  rejectBatch.set(adminDb.collection("transactions").doc(`wd_refund_${upiReqId}`), {
    userId: ALICE_UID,
    userName: "Alice",
    type: "WITHDRAWAL_REFUND",
    title: "Withdrawal Refund (₹200)",
    amountCents: 20000,
    amountCoins: 33000,
    status: "REFUNDED",
    referenceId: upiReqId,
    createdAt: refundTs,
  });
  await assertSucceeds(rejectBatch.commit());

  // 11. Attempting a second refund on an already-REJECTED request -> MUST FAIL
  const doubleRefundBatch = adminDb.batch();
  doubleRefundBatch.update(adminDb.collection("withdrawal_requests").doc(upiReqId), {
    status: "REJECTED",
    statusNote: "Second refund attempt",
    adminActionBy: ADMIN_UID,
    coinsRefunded: true,
    rejectedAt: new Date(),
    updatedAt: new Date(),
  });
  doubleRefundBatch.update(adminDb.collection("users").doc(ALICE_UID), {
    balanceCoins: 75000,
    eligibleCoins: 75000,
    promotionalCoins: 0,
    balanceCents: 60000,
    lastRefundedWithdrawalId: upiReqId,
    updatedAt: new Date(),
  });
  await assertFails(doubleRefundBatch.commit());

  // 12. Disabled denomination in /app_config/withdrawal_availability blocks modified-client withdrawal -> MUST FAIL
  await assertSucceeds(
    adminDb.collection("app_config").doc("withdrawal_availability").set({
      upiEnabled: true,
      amazonEnabled: true,
      googlePlayEnabled: true,
      flipkartEnabled: true,
      upiDenominationsEnabled: { "200": false, "500": true },
      updatedAt: new Date(),
    })
  );
  const blockedReqId = "req_blocked_denom";
  const blockedBatch = aliceDb.batch();
  blockedBatch.set(aliceDb.collection("withdrawal_requests").doc(blockedReqId), {
    requestId: blockedReqId,
    userId: ALICE_UID,
    userName: "Alice",
    method: "UPI",
    amountRupees: 200,
    amountCents: 20000,
    amountCoins: 33000,
    upiId: "alice@upi",
    status: "SUBMITTED",
    statusNote: "",
    adminActionBy: "",
    paymentReference: "",
    giftCode: "",
    giftCodeHash: "",
    coinsDeducted: true,
    coinsRefunded: false,
    deductedBalanceCoins: 33000,
    deductedEligibleCoins: 33000,
    deductedPromotionalCoins: 0,
    deductedBalanceCents: 26400,
    idempotencyKey: blockedReqId,
    isDemo: false,
    createdAt: new Date(),
    updatedAt: new Date(),
  });
  blockedBatch.update(aliceDb.collection("users").doc(ALICE_UID), {
    balanceCoins: 9000,
    eligibleCoins: 9000,
    promotionalCoins: 0,
    balanceCents: 7200,
    lastWithdrawalRequestId: blockedReqId,
    updatedAt: new Date(),
  });
  blockedBatch.set(aliceDb.collection("transactions").doc(`wd_debit_${blockedReqId}`), {
    userId: ALICE_UID,
    userName: "Alice",
    type: "WITHDRAWAL_DEBIT",
    title: "UPI Withdrawal (₹200)",
    amountCents: 20000,
    amountCoins: 33000,
    status: "DEBITED",
    referenceId: blockedReqId,
    createdAt: new Date(),
  });
  await assertFails(blockedBatch.commit());

  // Re-enable UPI 200 for subsequent checks
  await assertSucceeds(
    adminDb.collection("app_config").doc("withdrawal_availability").set({
      upiEnabled: true,
      amazonEnabled: true,
      googlePlayEnabled: true,
      flipkartEnabled: true,
      upiDenominationsEnabled: { "200": true, "500": true },
      updatedAt: new Date(),
    })
  );

  // 13. Bucket-skip exploit (claiming deductedEligibleCoins: 0 when Alice has 42,000 eligibleCoins) -> MUST FAIL
  const skipReqId = "req_bucket_skip";
  const skipBatch = aliceDb.batch();
  skipBatch.set(aliceDb.collection("withdrawal_requests").doc(skipReqId), {
    requestId: skipReqId,
    userId: ALICE_UID,
    userName: "Alice",
    method: "UPI",
    amountRupees: 200,
    amountCents: 20000,
    amountCoins: 33000,
    upiId: "alice@upi",
    status: "SUBMITTED",
    statusNote: "",
    adminActionBy: "",
    paymentReference: "",
    giftCode: "",
    giftCodeHash: "",
    coinsDeducted: true,
    coinsRefunded: false,
    deductedBalanceCoins: 33000,
    deductedEligibleCoins: 0,
    deductedPromotionalCoins: 0,
    deductedBalanceCents: 0,
    idempotencyKey: skipReqId,
    isDemo: false,
    createdAt: new Date(),
    updatedAt: new Date(),
  });
  skipBatch.update(aliceDb.collection("users").doc(ALICE_UID), {
    balanceCoins: 9000,
    eligibleCoins: 42000,
    promotionalCoins: 0,
    balanceCents: 33600,
    lastWithdrawalRequestId: skipReqId,
    updatedAt: new Date(),
  });
  skipBatch.set(aliceDb.collection("transactions").doc(`wd_debit_${skipReqId}`), {
    userId: ALICE_UID,
    userName: "Alice",
    type: "WITHDRAWAL_DEBIT",
    title: "UPI Withdrawal (₹200)",
    amountCents: 20000,
    amountCoins: 33000,
    status: "DEBITED",
    referenceId: skipReqId,
    createdAt: new Date(),
  });
  await assertFails(skipBatch.commit());

  // 14. Cross-user notification spoofing (Bob writing into Alice's notifications) -> MUST FAIL
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("notifications").doc("spoof_notif").set({
      userId: ALICE_UID,
      title: "Fake Notification",
      body: "Spoofed message",
      type: "WITHDRAWAL",
      referenceId: upiReqId,
      isRead: false,
      createdAt: new Date(),
    })
  );

  // 15. Forged standalone /admin_notifications create without an atomic withdrawal request -> MUST FAIL
  await assertFails(
    bobDb.collection("admin_notifications").doc("forged_admin_alert").set({
      userId: BOB_UID,
      userName: "Bob",
      title: "Fake Withdrawal Alert",
      body: "Forged",
      type: "WITHDRAWAL_REQUEST",
      referenceId: "nonexistent_req",
      isRead: false,
      createdAt: new Date(),
    })
  );

  // 16. Client attempting direct balanceCoins inflation without promotionalCoins change -> MUST FAIL
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      balanceCoins: 999999,
      updatedAt: new Date(),
    })
  );

  // 17. Insufficient balance (Alice has 42,000 Coins, tries UPI ₹500 = 82,500 Coins) -> MUST FAIL
  const insuffReqId = "req_insuff_500";
  const insuffBatch = aliceDb.batch();
  insuffBatch.set(aliceDb.collection("withdrawal_requests").doc(insuffReqId), {
    requestId: insuffReqId,
    userId: ALICE_UID,
    userName: "Alice",
    method: "UPI",
    amountRupees: 500,
    amountCents: 50000,
    amountCoins: 82500,
    upiId: "alice@upi",
    status: "SUBMITTED",
    statusNote: "",
    adminActionBy: "",
    paymentReference: "",
    giftCode: "",
    giftCodeHash: "",
    coinsDeducted: true,
    coinsRefunded: false,
    deductedBalanceCoins: 82500,
    deductedEligibleCoins: 42000,
    deductedPromotionalCoins: 0,
    deductedBalanceCents: 33600,
    idempotencyKey: insuffReqId,
    isDemo: false,
    createdAt: new Date(),
    updatedAt: new Date(),
  });
  insuffBatch.update(aliceDb.collection("users").doc(ALICE_UID), {
    balanceCoins: -40500,
    eligibleCoins: 0,
    promotionalCoins: 0,
    balanceCents: 0,
    lastWithdrawalRequestId: insuffReqId,
    updatedAt: new Date(),
  });
  insuffBatch.set(aliceDb.collection("transactions").doc(`wd_debit_${insuffReqId}`), {
    userId: ALICE_UID,
    userName: "Alice",
    type: "WITHDRAWAL_DEBIT",
    title: "UPI Withdrawal (₹500)",
    amountCents: 50000,
    amountCoins: 82500,
    status: "DEBITED",
    referenceId: insuffReqId,
    createdAt: new Date(),
  });
  await assertFails(insuffBatch.commit());

  // 18. Under-deduction on users/{uid} (request 33,000 Coins while deducting only 1,000 from user) -> MUST FAIL
  const underDeductId = "req_under_deduct";
  const underBatch = aliceDb.batch();
  underBatch.set(aliceDb.collection("withdrawal_requests").doc(underDeductId), {
    requestId: underDeductId,
    userId: ALICE_UID,
    userName: "Alice",
    method: "UPI",
    amountRupees: 200,
    amountCents: 20000,
    amountCoins: 33000,
    upiId: "alice@upi",
    status: "SUBMITTED",
    statusNote: "",
    adminActionBy: "",
    paymentReference: "",
    giftCode: "",
    giftCodeHash: "",
    coinsDeducted: true,
    coinsRefunded: false,
    deductedBalanceCoins: 33000,
    deductedEligibleCoins: 33000,
    deductedPromotionalCoins: 0,
    deductedBalanceCents: 26400,
    idempotencyKey: underDeductId,
    isDemo: false,
    createdAt: new Date(),
    updatedAt: new Date(),
  });
  underBatch.update(aliceDb.collection("users").doc(ALICE_UID), {
    balanceCoins: 41000,
    eligibleCoins: 41000,
    promotionalCoins: 0,
    balanceCents: 32800,
    lastWithdrawalRequestId: underDeductId,
    updatedAt: new Date(),
  });
  underBatch.set(aliceDb.collection("transactions").doc(`wd_debit_${underDeductId}`), {
    userId: ALICE_UID,
    userName: "Alice",
    type: "WITHDRAWAL_DEBIT",
    title: "UPI Withdrawal (₹200)",
    amountCents: 20000,
    amountCoins: 33000,
    status: "DEBITED",
    referenceId: underDeductId,
    createdAt: new Date(),
  });
  await assertFails(underBatch.commit());

  // 19. Duplicate requestId reuse AND mismatched idempotencyKey != requestId -> MUST FAIL
  await assertFails(
    aliceDb.collection("withdrawal_requests").doc(gcReqId).set({
      requestId: gcReqId,
      userId: ALICE_UID,
      userName: "Alice",
      method: "AMAZON_GIFT_CARD",
      amountRupees: 150,
      amountCents: 15000,
      amountCoins: 33000,
      upiId: "",
      status: "SUBMITTED",
      statusNote: "",
      adminActionBy: "",
      paymentReference: "",
      giftCode: "",
      giftCodeHash: "",
      coinsDeducted: true,
      coinsRefunded: false,
      deductedBalanceCoins: 33000,
      deductedEligibleCoins: 33000,
      deductedPromotionalCoins: 0,
      deductedBalanceCents: 26400,
      idempotencyKey: gcReqId,
      isDemo: false,
      createdAt: new Date(),
      updatedAt: new Date(),
    })
  );
  const mismatchId = "req_mismatch_idem";
  const mismatchBatch = aliceDb.batch();
  mismatchBatch.set(aliceDb.collection("withdrawal_requests").doc(mismatchId), {
    requestId: mismatchId,
    userId: ALICE_UID,
    userName: "Alice",
    method: "UPI",
    amountRupees: 200,
    amountCents: 20000,
    amountCoins: 33000,
    upiId: "alice@upi",
    status: "SUBMITTED",
    statusNote: "",
    adminActionBy: "",
    paymentReference: "",
    giftCode: "",
    giftCodeHash: "",
    coinsDeducted: true,
    coinsRefunded: false,
    deductedBalanceCoins: 33000,
    deductedEligibleCoins: 33000,
    deductedPromotionalCoins: 0,
    deductedBalanceCents: 26400,
    idempotencyKey: "different_key",
    isDemo: false,
    createdAt: new Date(),
    updatedAt: new Date(),
  });
  mismatchBatch.update(aliceDb.collection("users").doc(ALICE_UID), {
    balanceCoins: 9000,
    eligibleCoins: 9000,
    promotionalCoins: 0,
    balanceCents: 7200,
    lastWithdrawalRequestId: mismatchId,
    updatedAt: new Date(),
  });
  mismatchBatch.set(aliceDb.collection("transactions").doc(`wd_debit_${mismatchId}`), {
    userId: ALICE_UID,
    userName: "Alice",
    type: "WITHDRAWAL_DEBIT",
    title: "UPI Withdrawal (₹200)",
    amountCents: 20000,
    amountCoins: 33000,
    status: "DEBITED",
    referenceId: mismatchId,
    createdAt: new Date(),
  });
  await assertFails(mismatchBatch.commit());

  // 20. Owner-only giftCode privacy, blocked cross-user history list, & Admin-only /used_gift_codes
  const ownerGcSnap = await assertSucceeds(
    aliceDb.collection("withdrawal_requests").doc(gcReqId).get()
  );
  if (ownerGcSnap.data().giftCode !== "AMZN-TEST-9988-7766") {
    throw new Error("Expected owner to read their own completed giftCode");
  }
  await assertFails(bobDb.collection("withdrawal_requests").get());
  await assertFails(aliceDb.collection("used_gift_codes").doc(codeHash).get());
  await assertFails(bobDb.collection("used_gift_codes").get());

  // 21. Valid second Gift Card request so we can test user completion forgery, duplicate gift code, and monotonic Admin status
  const gcReqId2 = "req_gc_150_second";
  const gcBatch2 = aliceDb.batch();
  gcBatch2.set(aliceDb.collection("withdrawal_requests").doc(gcReqId2), {
    requestId: gcReqId2,
    userId: ALICE_UID,
    userName: "Alice",
    method: "AMAZON_GIFT_CARD",
    amountRupees: 150,
    amountCents: 15000,
    amountCoins: 33000,
    upiId: "",
    status: "SUBMITTED",
    statusNote: "",
    adminActionBy: "",
    paymentReference: "",
    giftCode: "",
    giftCodeHash: "",
    coinsDeducted: true,
    coinsRefunded: false,
    deductedBalanceCoins: 33000,
    deductedEligibleCoins: 33000,
    deductedPromotionalCoins: 0,
    deductedBalanceCents: 26400,
    idempotencyKey: gcReqId2,
    isDemo: false,
    createdAt: new Date(),
    updatedAt: new Date(),
  });
  gcBatch2.update(aliceDb.collection("users").doc(ALICE_UID), {
    balanceCoins: 9000,
    eligibleCoins: 9000,
    promotionalCoins: 0,
    balanceCents: 7200,
    lastWithdrawalRequestId: gcReqId2,
    updatedAt: new Date(),
  });
  gcBatch2.set(aliceDb.collection("transactions").doc(`wd_debit_${gcReqId2}`), {
    userId: ALICE_UID,
    userName: "Alice",
    type: "WITHDRAWAL_DEBIT",
    title: "Amazon Gift Card (₹150)",
    amountCents: 15000,
    amountCoins: 33000,
    status: "DEBITED",
    referenceId: gcReqId2,
    createdAt: new Date(),
  });
  await assertSucceeds(gcBatch2.commit());

  // Ordinary user CANNOT mark their own request UNDER_REVIEW or COMPLETED (Gift Card or UPI forgery) -> MUST FAIL
  await assertFails(
    aliceDb.collection("withdrawal_requests").doc(gcReqId2).update({
      status: "COMPLETED",
      giftCode: "FORGED-CODE-1111",
      updatedAt: new Date(),
    })
  );
  await assertFails(
    bobDb.collection("withdrawal_requests").doc(gcReqId2).update({
      status: "UNDER_REVIEW",
      updatedAt: new Date(),
    })
  );

  // 22. Duplicate gift-code reuse by Admin AND backward PROCESSING -> UNDER_REVIEW transition -> MUST FAIL
  const dupCodeBatch = adminDb.batch();
  dupCodeBatch.set(adminDb.collection("used_gift_codes").doc(codeHash), {
    codeHash,
    requestId: gcReqId2,
    method: "AMAZON_GIFT_CARD",
    amountRupees: 150,
    assignedBy: ADMIN_UID,
    assignedAt: new Date(),
  });
  dupCodeBatch.update(adminDb.collection("withdrawal_requests").doc(gcReqId2), {
    status: "COMPLETED",
    statusNote: "Reused code",
    adminActionBy: ADMIN_UID,
    giftCode: "AMZN-TEST-9988-7766",
    giftCodeHash: codeHash,
    giftCodeAssignedAt: new Date(),
    completedAt: new Date(),
    updatedAt: new Date(),
  });
  await assertFails(dupCodeBatch.commit());

  await assertSucceeds(
    adminDb.collection("withdrawal_requests").doc(gcReqId2).update({
      status: "PROCESSING",
      statusNote: "Processing gift card",
      adminActionBy: ADMIN_UID,
      reviewedAt: new Date(),
      updatedAt: new Date(),
    })
  );
  await assertFails(
    adminDb.collection("withdrawal_requests").doc(gcReqId2).update({
      status: "UNDER_REVIEW",
      statusNote: "Backward transition",
      adminActionBy: ADMIN_UID,
      reviewedAt: new Date(),
      updatedAt: new Date(),
    })
  );
});

test("8. Admin task deactivate/reactivate controls, blocked submissions on deactivated tasks, and preserved /submissions when task is deleted", async () => {
  const ts = nowTimestamp();
  const taskId = "task_active_lifecycle_1";
  const sampleImageUrl =
    "https://firebasestorage.googleapis.com/v0/b/taskflow-8852d.firebasestorage.app/o/submissions%2Falice_123%2Ftask_active_lifecycle_1%2Fproof_1.jpg?alt=media";
  const sampleStoragePath =
    "submissions/alice_123/task_active_lifecycle_1/proof_1.jpg";

  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("users").doc(ADMIN_UID).set({
      userId: ADMIN_UID,
      email: ADMIN_EMAIL,
      displayName: "Admin",
      role: "admin",
      approvalStatus: "approved",
      balanceCents: 0,
      completedTasksCount: 0,
      assignedTaskId: "",
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: ALICE_EMAIL,
      displayName: "Alice",
      role: "user",
      approvalStatus: "approved",
      balanceCents: 0,
      completedTasksCount: 0,
      assignedTaskId: "",
      createdAt: ts,
      updatedAt: ts,
    });
  });

  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, {
      email: ADMIN_EMAIL,
      email_verified: true,
    })
    .firestore();

  const aliceDb = testEnv
    .authenticatedContext(ALICE_UID, {
      email: ALICE_EMAIL,
      email_verified: true,
    })
    .firestore();

  // 1. Admin creates an active task (isActive: true) -> MUST SUCCEED
  const createBatch = adminDb.batch();
  createBatch.set(adminDb.collection("tasks").doc(taskId), {
    title: "Install Partner App",
    appName: "Partner App",
    appIconUrl: "https://example.com/icon.png",
    description: "Install and verify onboarding",
    instructions: "1. Open link\n2. Screenshot home screen",
    requiredProof: "Written note and screenshot",
    taskLink: "https://play.google.com/store/apps/details?id=com.example.partner",
    category: "App Install",
    rewardCents: 2000,
    assignedUserId: ADMIN_UID,
    assignedUserName: "Admin",
    assignedUserEmail: ADMIN_EMAIL,
    status: "pending",
    isActive: true,
    proofText: "",
    proofImageUrl: "",
    proofStoragePath: "",
    adminFeedback: "",
    createdBy: ADMIN_UID,
    createdAt: ts,
    updatedAt: ts,
  });
  createBatch.update(adminDb.collection("users").doc(ADMIN_UID), {
    assignedTaskId: taskId,
    updatedAt: ts,
  });
  await assertSucceeds(createBatch.commit());

  // 2. Normal user attempting to deactivate a task -> MUST FAIL
  await assertFails(
    aliceDb.collection("tasks").doc(taskId).update({
      isActive: false,
      updatedAt: new Date(),
    })
  );

  // 3. Admin deactivates the task (isActive: false) -> MUST SUCCEED
  await assertSucceeds(
    adminDb.collection("tasks").doc(taskId).update({
      isActive: false,
      updatedAt: new Date(),
    })
  );

  // 4. User attempting to submit proof on a deactivated task -> MUST FAIL
  const deactivatedSubmitBatch = aliceDb.batch();
  deactivatedSubmitBatch.update(aliceDb.collection("tasks").doc(taskId), {
    assignedUserId: ALICE_UID,
    assignedUserName: "Alice",
    assignedUserEmail: ALICE_EMAIL,
    status: "submitted",
    proofText: "Attempted submission while deactivated.",
    proofImageUrl: sampleImageUrl,
    proofStoragePath: sampleStoragePath,
    updatedAt: new Date(),
  });
  deactivatedSubmitBatch.set(aliceDb.collection("submissions").doc(taskId), {
    taskId: taskId,
    taskTitle: "Install Partner App",
    appName: "Partner App",
    appIconUrl: "https://example.com/icon.png",
    rewardCents: 2000,
    userId: ALICE_UID,
    userName: "Alice",
    userEmail: ALICE_EMAIL,
    proofText: "Attempted submission while deactivated.",
    proofImageUrl: sampleImageUrl,
    proofStoragePath: sampleStoragePath,
    status: "submitted",
    adminFeedback: "",
    createdAt: ts,
    updatedAt: new Date(),
  });
  await assertFails(deactivatedSubmitBatch.commit());

  // 5. Admin reactivates the task (isActive: true) -> MUST SUCCEED
  await assertSucceeds(
    adminDb.collection("tasks").doc(taskId).update({
      isActive: true,
      updatedAt: new Date(),
    })
  );

  // 6. User submits proof on reactivated task -> MUST SUCCEED
  const activeSubmitBatch = aliceDb.batch();
  activeSubmitBatch.update(aliceDb.collection("tasks").doc(taskId), {
    assignedUserId: ALICE_UID,
    assignedUserName: "Alice",
    assignedUserEmail: ALICE_EMAIL,
    status: "submitted",
    proofText: "Completed task after reactivation.",
    proofImageUrl: sampleImageUrl,
    proofStoragePath: sampleStoragePath,
    updatedAt: ts,
  });
  activeSubmitBatch.set(aliceDb.collection("submissions").doc(taskId), {
    taskId: taskId,
    taskTitle: "Install Partner App",
    appName: "Partner App",
    appIconUrl: "https://example.com/icon.png",
    rewardCents: 2000,
    userId: ALICE_UID,
    userName: "Alice",
    userEmail: ALICE_EMAIL,
    proofText: "Completed task after reactivation.",
    proofImageUrl: sampleImageUrl,
    proofStoragePath: sampleStoragePath,
    status: "submitted",
    adminFeedback: "",
    createdAt: ts,
    updatedAt: ts,
  });
  await assertSucceeds(activeSubmitBatch.commit());

  // 7. Admin deletes the task from /tasks while /submissions/{taskId} remains preserved and cannot be deleted
  await assertSucceeds(adminDb.collection("tasks").doc(taskId).delete());
  await assertFails(adminDb.collection("submissions").doc(taskId).delete());
  await assertSucceeds(aliceDb.collection("submissions").doc(taskId).get());
  await assertSucceeds(adminDb.collection("submissions").doc(taskId).get());
});

test("9. Phase 1 Wallet, Check-in, Watch Stats & Reward Session Security: blocks all client-side balanceCents increments (+100/+500/+700) and client writes to reward_sessions, check_ins, watch_stats, and forged transactions", async () => {
  const ts = nowTimestamp();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: ALICE_EMAIL,
      displayName: "Alice",
      role: "user",
      approvalStatus: "approved",
      balanceCents: 0,
      completedTasksCount: 0,
      assignedTaskId: "",
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("reward_sessions").doc("session_srv_1").set({
      sessionId: "session_srv_1",
      userId: ALICE_UID,
      rewardType: "WATCH_AND_EARN",
      rewardCents: 100,
      status: "PENDING",
      s2sVerified: false,
      used: false,
      claimed: false,
      createdAt: ts,
      expiresAt: new Date(Date.now() + 600000),
    });
  });

  const aliceDb = testEnv
    .authenticatedContext(ALICE_UID, {
      email: ALICE_EMAIL,
      email_verified: true,
    })
    .firestore();

  // 1. Client attempting the old +100, +500, or +700 balanceCents self-increment loophole -> MUST FAIL
  for (const amount of [100, 500, 700, 5000]) {
    await assertFails(
      aliceDb.collection("users").doc(ALICE_UID).update({
        balanceCents: amount,
        checkInStreak: 1,
        lastCheckInDate: "2026-11-11",
        lastCheckInTimestamp: new Date(),
        updatedAt: new Date(),
      })
    );
  }

  // 2. Client attempting to create or update /users/{uid}/check_ins/{dateKey} directly -> MUST FAIL
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("check_ins")
      .doc("2026-11-11")
      .set({
        userId: ALICE_UID,
        dateKey: "2026-11-11",
        streakDay: 1,
        rewardCents: 100,
        claimedAt: ts,
      })
  );

  // 3. Client attempting to create or update /users/{uid}/watch_stats/{dateKey} or /watch_stats/{statId} -> MUST FAIL
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("watch_stats")
      .doc("2026-11-11")
      .set({
        userId: ALICE_UID,
        dateKey: "2026-11-11",
        videosWatched: 1,
        totalEarnedCents: 100,
        updatedAt: ts,
      })
  );
  await assertFails(
    aliceDb
      .collection("watch_stats")
      .doc(`${ALICE_UID}_2026-11-11`)
      .set({
        userId: ALICE_UID,
        dateKey: "2026-11-11",
        videosWatched: 1,
        totalEarnedCents: 100,
        updatedAt: ts,
      })
  );

  // 4. Client attempting to create or verify or claim /reward_sessions/{sessionId} -> MUST FAIL
  await assertFails(
    aliceDb.collection("reward_sessions").doc("client_fake_session").set({
      sessionId: "client_fake_session",
      userId: ALICE_UID,
      rewardType: "WATCH_AND_EARN",
      rewardCents: 100,
      status: "PENDING",
      used: false,
      claimed: false,
      createdAt: ts,
      expiresAt: new Date(Date.now() + 600000),
    })
  );
  await assertFails(
    aliceDb.collection("reward_sessions").doc("session_srv_1").update({
      status: "VERIFIED",
      s2sVerified: true,
    })
  );
  await assertFails(
    aliceDb.collection("reward_sessions").doc("session_srv_1").update({
      status: "CLAIMED",
      used: true,
      claimed: true,
    })
  );

  // Owner can still read their own reward session
  await assertSucceeds(
    aliceDb.collection("reward_sessions").doc("session_srv_1").get()
  );

  // 5. Client attempting to fabricate a CREDITED reward transaction in /transactions -> MUST FAIL
  await assertFails(
    aliceDb.collection("transactions").doc("forged_tx_1").set({
      userId: ALICE_UID,
      userName: "Alice",
      type: "DAILY_CHECKIN",
      title: "Day 7 Daily Check-in Reward",
      amountCents: 500,
      status: "CREDITED",
      referenceId: "2026-11-11",
      createdAt: ts,
    })
  );
  await assertFails(
    aliceDb.collection("transactions").doc("forged_tx_2").set({
      userId: ALICE_UID,
      userName: "Alice",
      type: "WATCH_EARN",
      title: "Watch & Earn Video Reward (1/25)",
      amountCents: 100,
      status: "CREDITED",
      referenceId: "session_srv_1",
      createdAt: ts,
    })
  );
});

test("10. Phase 1 User Approval Security: pending and suspended users cannot submit task proofs or create withdrawal requests", async () => {
  const ts = nowTimestamp();
  const pendingUid = "pending_user_99";
  const suspendedUid = "suspended_user_99";
  const taskId = "task_approval_gate_1";

  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("users").doc(pendingUid).set({
      userId: pendingUid,
      email: "pending@example.com",
      displayName: "Pending User",
      role: "user",
      approvalStatus: "pending",
      balanceCents: 30000,
      completedTasksCount: 0,
      assignedTaskId: "",
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("users").doc(suspendedUid).set({
      userId: suspendedUid,
      email: "suspended@example.com",
      displayName: "Suspended User",
      role: "user",
      approvalStatus: "suspended",
      balanceCents: 30000,
      completedTasksCount: 0,
      assignedTaskId: "",
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("tasks").doc(taskId).set({
      title: "Approval Gate Task",
      appName: "Gate App",
      appIconUrl: "https://example.com/icon.png",
      description: "Verify approval gate",
      instructions: "1. Open app",
      requiredProof: "Screenshot",
      taskLink: "https://play.google.com/store/apps/details?id=com.example.gate",
      category: "App Install",
      rewardCents: 1500,
      assignedUserId: ADMIN_UID,
      assignedUserName: "Admin",
      assignedUserEmail: ADMIN_EMAIL,
      status: "pending",
      isActive: true,
      proofText: "",
      proofImageUrl: "",
      proofStoragePath: "",
      adminFeedback: "",
      createdBy: ADMIN_UID,
      createdAt: ts,
      updatedAt: ts,
    });
  });

  const pendingDb = testEnv
    .authenticatedContext(pendingUid, {
      email: "pending@example.com",
      email_verified: true,
    })
    .firestore();
  const suspendedDb = testEnv
    .authenticatedContext(suspendedUid, {
      email: "suspended@example.com",
      email_verified: true,
    })
    .firestore();

  // Pending user attempting to create a withdrawal request -> MUST FAIL
  await assertFails(
    pendingDb.collection("withdrawal_requests").doc("req_pending_1").set({
      requestId: "req_pending_1",
      userId: pendingUid,
      userName: "Pending User",
      amountCents: 25000,
      method: "UPI ID",
      status: "pending_demo",
      isDemo: true,
      createdAt: ts,
    })
  );

  // Suspended user attempting to create a withdrawal request -> MUST FAIL
  await assertFails(
    suspendedDb.collection("withdrawal_requests").doc("req_suspended_1").set({
      requestId: "req_suspended_1",
      userId: suspendedUid,
      userName: "Suspended User",
      amountCents: 25000,
      method: "UPI ID",
      status: "pending_demo",
      isDemo: true,
      createdAt: ts,
    })
  );
});

test("11. Phase 1 Leaderboard Permissions & Privacy: non-admin cannot list /users or read private emails, but can query /leaderboard containing only public fields", async () => {
  const ts = nowTimestamp();

  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: ALICE_EMAIL,
      displayName: "Alice",
      role: "user",
      approvalStatus: "approved",
      balanceCents: 4500,
      completedTasksCount: 3,
      assignedTaskId: "",
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("users").doc(BOB_UID).set({
      userId: BOB_UID,
      email: BOB_EMAIL,
      displayName: "Bob",
      role: "user",
      approvalStatus: "approved",
      balanceCents: 2500,
      completedTasksCount: 2,
      assignedTaskId: "",
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("leaderboard").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      balanceCents: 4500,
      completedTasksCount: 3,
      approvalStatus: "approved",
      updatedAt: ts,
    });
    await db.collection("leaderboard").doc(BOB_UID).set({
      userId: BOB_UID,
      displayName: "Bob",
      balanceCents: 2500,
      completedTasksCount: 2,
      approvalStatus: "approved",
      updatedAt: ts,
    });
  });

  const aliceDb = testEnv
    .authenticatedContext(ALICE_UID, {
      email: ALICE_EMAIL,
      email_verified: true,
    })
    .firestore();
  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, {
      email: ADMIN_EMAIL,
      email_verified: true,
    })
    .firestore();

  // 1. Non-admin user attempting to list /users or query /users for leaderboard -> MUST FAIL (prevents private email exposure)
  await assertFails(aliceDb.collection("users").get());
  await assertFails(
    aliceDb.collection("users").where("approvalStatus", "==", "approved").get()
  );
  await assertFails(aliceDb.collection("users").doc(BOB_UID).get());

  // 2. Admin can still list /users -> MUST SUCCEED
  await assertSucceeds(adminDb.collection("users").get());

  // 3. Non-admin user can query /leaderboard where approvalStatus == 'approved' -> MUST SUCCEED and contain NO email
  const leaderboardSnap = await assertSucceeds(
    aliceDb
      .collection("leaderboard")
      .where("approvalStatus", "==", "approved")
      .get()
  );
  if (leaderboardSnap.docs.length !== 2) {
    throw new Error(`Expected 2 leaderboard docs, got ${leaderboardSnap.docs.length}`);
  }
  for (const doc of leaderboardSnap.docs) {
    if ("email" in doc.data()) {
      throw new Error("Leaderboard document must never expose email");
    }
  }

  // 4. Attempting to write an email field or tampered balanceCents to /leaderboard/{uid} -> MUST FAIL
  await assertFails(
    aliceDb.collection("leaderboard").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: ALICE_EMAIL,
      displayName: "Alice",
      balanceCents: 4500,
      completedTasksCount: 3,
      approvalStatus: "approved",
      updatedAt: new Date(),
    })
  );
  await assertFails(
    aliceDb.collection("leaderboard").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      balanceCents: 999999,
      completedTasksCount: 3,
      approvalStatus: "approved",
      updatedAt: new Date(),
    })
  );

  // 5. Valid atomic displayName update on /users/{uid} and /leaderboard/{uid} -> MUST SUCCEED
  const syncTs = new Date();
  const syncBatch = aliceDb.batch();
  syncBatch.update(aliceDb.collection("users").doc(ALICE_UID), {
    displayName: "Alice Pro",
    updatedAt: syncTs,
  });
  syncBatch.set(aliceDb.collection("leaderboard").doc(ALICE_UID), {
    userId: ALICE_UID,
    displayName: "Alice Pro",
    balanceCents: 4500,
    completedTasksCount: 3,
    approvalStatus: "approved",
    updatedAt: syncTs,
  });
  await assertSucceeds(syncBatch.commit());
});

test("12. Phase 1 Server-Side Daily Check-In & Day 7 Reward Calculation + Unity S2S Signature Verification", async () => {
  const {
    _verifyUnityS2SSignature,
    _getDailyCheckInRewardCents,
    _calculateServerDailyCheckInState,
  } = require("./functions/index.js");

  // 1. Days 1-6 = 100 paise (₹1.00), Day 7 = 500 paise (₹5.00)
  for (let d = 1; d <= 6; d++) {
    if (_getDailyCheckInRewardCents(d) !== 100) {
      throw new Error(`Expected Day ${d} reward to be 100 paise`);
    }
  }
  if (_getDailyCheckInRewardCents(7) !== 500) {
    throw new Error("Expected Day 7 reward to be 500 paise (₹5.00)");
  }

  // 2. Server-side streak progression & duplicate same-day check-in prevention
  const today = "2026-11-11";
  const yesterday = "2026-11-10";

  // Consecutive Day 6 -> Day 7 (500 paise)
  const day7State = _calculateServerDailyCheckInState(yesterday, 6, today, yesterday);
  if (!day7State.canCheckInToday || day7State.expectedStreakDay !== 7 || day7State.rewardCents !== 500) {
    throw new Error(`Unexpected Day 7 state: ${JSON.stringify(day7State)}`);
  }

  // Duplicate check-in on the same date -> canCheckInToday is false
  const duplicateTodayState = _calculateServerDailyCheckInState(today, 7, today, yesterday);
  if (duplicateTodayState.canCheckInToday !== false) {
    throw new Error("Expected duplicate check-in on the same date to be blocked");
  }

  // After Day 7 -> rolls over to Day 1 (100 paise) on the next day
  const rolloverState = _calculateServerDailyCheckInState(yesterday, 7, today, yesterday);
  if (!rolloverState.canCheckInToday || rolloverState.expectedStreakDay !== 1 || rolloverState.rewardCents !== 100) {
    throw new Error(`Unexpected rollover state after Day 7: ${JSON.stringify(rolloverState)}`);
  }

  // Missed day -> resets streak to Day 1 (100 paise)
  const missedDayState = _calculateServerDailyCheckInState("2026-11-05", 5, today, yesterday);
  if (!missedDayState.canCheckInToday || missedDayState.expectedStreakDay !== 1 || missedDayState.rewardCents !== 100) {
    throw new Error(`Unexpected missed-day state: ${JSON.stringify(missedDayState)}`);
  }

  // 3. Mandatory UNITY_S2S_SECRET & HMAC-MD5 Signature Verification
  const secret = "mandatory_prod_s2s_secret_99";
  const queryParams = {
    adid: "800430673",
    oid: "order_778899",
    sid: "session_abcdef123456",
  };
  const paramString = Object.keys(queryParams)
    .sort()
    .map((k) => `${k}=${queryParams[k]}`)
    .join(",");
  const validHmac = crypto.createHmac("md5", secret).update(paramString).digest("hex");

  // Missing or empty secret -> MUST return false
  if (_verifyUnityS2SSignature({ ...queryParams, hmac: validHmac }, "") !== false) {
    throw new Error("Empty S2S secret must never be accepted");
  }
  if (_verifyUnityS2SSignature({ ...queryParams, hmac: validHmac }, null) !== false) {
    throw new Error("Null S2S secret must never be accepted");
  }
  // Missing hmac -> MUST return false
  if (_verifyUnityS2SSignature(queryParams, secret) !== false) {
    throw new Error("Unsigned S2S callback must never be accepted");
  }
  // Wrong signature -> MUST return false
  if (_verifyUnityS2SSignature({ ...queryParams, hmac: "0".repeat(32) }, secret) !== false) {
    throw new Error("Forged S2S signature must never be accepted");
  }
  // Valid signature -> MUST return true
  if (_verifyUnityS2SSignature({ ...queryParams, hmac: validHmac }, secret) !== true) {
    throw new Error("Valid Unity S2S HMAC-MD5 signature must be accepted");
  }
});



