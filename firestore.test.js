const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

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

  // 4c. Valid Atomic Task Submission by second user account (Alice) with BOTH written proofText AND uploaded photo -> MUST SUCCEED
  const submitBatch = aliceDb.batch();
  submitBatch.update(aliceDb.collection("tasks").doc(taskId), {
    assignedUserId: ALICE_UID,
    assignedUserName: "Alice",
    assignedUserEmail: ALICE_EMAIL,
    status: "submitted",
    proofText: "Completed QA check across Android screens.",
    proofImageUrl: sampleImageUrl,
    proofStoragePath: sampleStoragePath,
    updatedAt: ts,
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
    createdAt: ts,
    updatedAt: ts,
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

test("7. Safe Demo Withdrawal Requests (/withdrawal_requests): enforces >= ₹200 minimum, <= balanceCents, rejects banking/UPI PII keys, and allows Admin visibility", async () => {
  const ts = nowTimestamp();

  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: ALICE_EMAIL,
      displayName: "Alice",
      role: "user",
      approvalStatus: "approved",
      balanceCents: 35000, // ₹350.00 available balance
      completedTasksCount: 3,
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

  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, {
      email: ADMIN_EMAIL,
      email_verified: true,
    })
    .firestore();

  // 1. Below ₹200 minimum (15000 paise = ₹150.00 < 20000) -> MUST FAIL
  await assertFails(
    aliceDb.collection("withdrawal_requests").doc("req_below_min").set({
      requestId: "req_below_min",
      userId: ALICE_UID,
      userName: "Alice",
      amountCents: 15000,
      method: "Bank Account",
      status: "pending_demo",
      isDemo: true,
      createdAt: ts,
    })
  );

  // 2. Exceeding available wallet balance (50000 paise = ₹500.00 > ₹350.00) -> MUST FAIL
  await assertFails(
    aliceDb.collection("withdrawal_requests").doc("req_exceeds_bal").set({
      requestId: "req_exceeds_bal",
      userId: ALICE_UID,
      userName: "Alice",
      amountCents: 50000,
      method: "UPI ID",
      status: "pending_demo",
      isDemo: true,
      createdAt: ts,
    })
  );

  // 3. Attempting to store real banking or UPI details (accountNumber, ifscCode, upiId) -> MUST FAIL
  await assertFails(
    aliceDb.collection("withdrawal_requests").doc("req_with_pii").set({
      requestId: "req_with_pii",
      userId: ALICE_UID,
      userName: "Alice",
      amountCents: 20000,
      method: "Bank Account",
      accountNumber: "123456789012",
      ifscCode: "SBIN0001234",
      status: "pending_demo",
      isDemo: true,
      createdAt: ts,
    })
  );

  // 4. Valid Demo Withdrawal Request (₹250.00 = 25000 paise via Bank Account, zero PII) -> MUST SUCCEED
  await assertSucceeds(
    aliceDb.collection("withdrawal_requests").doc("req_valid_bank").set({
      requestId: "req_valid_bank",
      userId: ALICE_UID,
      userName: "Alice",
      amountCents: 25000,
      method: "Bank Account",
      status: "pending_demo",
      isDemo: true,
      createdAt: ts,
    })
  );

  // 5. Valid Demo Withdrawal Request (₹200.00 = 20000 paise via UPI ID, zero PII) -> MUST SUCCEED
  await assertSucceeds(
    aliceDb.collection("withdrawal_requests").doc("req_valid_upi").set({
      requestId: "req_valid_upi",
      userId: ALICE_UID,
      userName: "Alice",
      amountCents: 20000,
      method: "UPI ID",
      status: "pending_demo",
      isDemo: true,
      createdAt: ts,
    })
  );

  // 6. Admin can read and list all demo withdrawal requests -> MUST SUCCEED
  await assertSucceeds(adminDb.collection("withdrawal_requests").get());
  await assertSucceeds(
    adminDb.collection("withdrawal_requests").doc("req_valid_bank").get()
  );
});

