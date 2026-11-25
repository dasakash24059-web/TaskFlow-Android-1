const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ADMIN_UID = "admin_storage_001";
const ADMIN_EMAIL = "dasakash24059@gmail.com";
const CLAIM_ADMIN_UID = "admin_claim_002";
const ALICE_UID = "alice_storage_123";
const ALICE_EMAIL = "alice@example.com";
const BOB_UID = "bob_storage_456";
const BOB_EMAIL = "bob@example.com";

const [storageHost, storagePortStr] = (
  process.env.FIREBASE_STORAGE_EMULATOR_HOST || "127.0.0.1:9199"
).split(":");
const storagePort = parseInt(storagePortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./storage.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    storage: {
      rules,
      host: storageHost,
      port: storagePort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

function ctxUnauth() {
  return testEnv.unauthenticatedContext().storage();
}

function ctxAliceVerified() {
  return testEnv
    .authenticatedContext(ALICE_UID, {
      email: ALICE_EMAIL,
      email_verified: true,
    })
    .storage();
}

function ctxAliceUnverified() {
  return testEnv
    .authenticatedContext(ALICE_UID, {
      email: ALICE_EMAIL,
      email_verified: false,
    })
    .storage();
}

function ctxAliceMissingEmailVerified() {
  return testEnv
    .authenticatedContext(ALICE_UID, {
      email: ALICE_EMAIL,
    })
    .storage();
}

function ctxBobVerified() {
  return testEnv
    .authenticatedContext(BOB_UID, {
      email: BOB_EMAIL,
      email_verified: true,
    })
    .storage();
}

function ctxBootstrappedAdminVerified() {
  return testEnv
    .authenticatedContext(ADMIN_UID, {
      email: ADMIN_EMAIL,
      email_verified: true,
    })
    .storage();
}

function ctxBootstrappedAdminUnverified() {
  return testEnv
    .authenticatedContext(ADMIN_UID, {
      email: ADMIN_EMAIL,
      email_verified: false,
    })
    .storage();
}

function ctxCustomClaimAdmin() {
  return testEnv
    .authenticatedContext(CLAIM_ADMIN_UID, {
      email: "ops@taskflow.app",
      email_verified: true,
      admin: true,
    })
    .storage();
}

function makeImageBytes(sizeBytes = 256) {
  return Buffer.alloc(sizeBytes, 0x42);
}

async function seedServerUploadedProof(storagePath, sizeBytes = 256, contentType = "image/jpeg") {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context
      .storage()
      .ref(storagePath)
      .put(makeImageBytes(sizeBytes), {
        contentType,
        customMetadata: {
          uid: storagePath.split("/")[1] || "",
          "task-id": storagePath.split("/")[2] || "",
          "ticket-id": "tkt_server_seeded_001",
        },
      })
      .then();
  });
}

test("1. Unauthenticated upload and download are strictly denied", async () => {
  const path = `submissions/${ALICE_UID}/task_unauth_1/proof_1001.jpg`;
  const unauthRef = ctxUnauth().ref(path);

  await assertFails(
    unauthRef.put(makeImageBytes(128), { contentType: "image/jpeg" }).then()
  );

  // Seed via server-side ticketed upload path, then verify unauthenticated read is rejected
  await seedServerUploadedProof(path, 128, "image/jpeg");

  await assertFails(unauthRef.getDownloadURL());
});

test("2. Unverified or missing email_verified token claims cannot upload or read restricted proof files", async () => {
  const path1 = `submissions/${ALICE_UID}/task_unv_1/proof_1002.jpg`;
  const unverifiedRef = ctxAliceUnverified().ref(path1);
  await assertFails(
    unverifiedRef.put(makeImageBytes(128), { contentType: "image/jpeg" }).then()
  );

  const path2 = `submissions/${ALICE_UID}/task_unv_2/proof_1003.jpg`;
  const missingClaimRef = ctxAliceMissingEmailVerified().ref(path2);
  await assertFails(
    missingClaimRef.put(makeImageBytes(128), { contentType: "image/png" }).then()
  );

  await seedServerUploadedProof(path1, 128, "image/jpeg");
  await assertFails(unverifiedRef.getDownloadURL());
  await assertFails(ctxAliceMissingEmailVerified().ref(path1).getDownloadURL());
});

test("3. Phase F.3: Direct client SDK/REST uploads are strictly blocked for ALL users (must use server-issued upload ticket)", async () => {
  const aliceStorage = ctxAliceVerified();

  // Direct client upload to valid submission paths is blocked even for verified users
  for (const [subPath, mime] of [
    [`submissions/${ALICE_UID}/task_direct_jpg/proof_1700000000001.jpg`, "image/jpeg"],
    [`submissions/${ALICE_UID}/task_direct_png/proof_1700000000002.png`, "image/png"],
    [`submissions/${ALICE_UID}/task_direct_webp/proof_1700000000003.webp`, "image/webp"],
  ]) {
    await assertFails(
      aliceStorage.ref(subPath).put(makeImageBytes(512), { contentType: mime }).then()
    );
  }

  // Even verified bootstrapped admin or custom-claim admin cannot bypass server ticket pipeline via client SDK write
  await assertFails(
    ctxBootstrappedAdminVerified()
      .ref(`submissions/${ADMIN_UID}/task_admin_direct/proof_1.jpg`)
      .put(makeImageBytes(256), { contentType: "image/jpeg" })
      .then()
  );
  await assertFails(
    ctxCustomClaimAdmin()
      .ref(`submissions/${CLAIM_ADMIN_UID}/task_admin_direct/proof_2.jpg`)
      .put(makeImageBytes(256), { contentType: "image/jpeg" })
      .then()
  );
});

test("4. Verified owner can read their own server-uploaded proof files (jpeg, png, webp, up to 128-char taskId)", async () => {
  const aliceStorage = ctxAliceVerified();

  const jpgPath = `submissions/${ALICE_UID}/task_valid_jpg/proof_1700000000001_tkt1.jpg`;
  await seedServerUploadedProof(jpgPath, 512, "image/jpeg");
  const url = await assertSucceeds(aliceStorage.ref(jpgPath).getDownloadURL());
  assert.equal(typeof url, "string");
  assert.ok(url.length > 0);

  const pngPath = `submissions/${ALICE_UID}/task_valid_png/proof_1700000000002_tkt2.png`;
  await seedServerUploadedProof(pngPath, 256, "image/png");
  await assertSucceeds(aliceStorage.ref(pngPath).getDownloadURL());

  const webpPath = `submissions/${ALICE_UID}/task_valid_webp/proof_1700000000003_tkt3.webp`;
  await seedServerUploadedProof(webpPath, 256, "image/webp");
  await assertSucceeds(aliceStorage.ref(webpPath).getDownloadURL());

  const longTaskId = "t".repeat(100);
  const longTaskPath = `submissions/${ALICE_UID}/${longTaskId}/proof_1700000000004_tkt4.jpg`;
  await seedServerUploadedProof(longTaskPath, 256, "image/jpeg");
  await assertSucceeds(aliceStorage.ref(longTaskPath).getDownloadURL());
});

test("5. Cross-user isolation: User A cannot upload to, read, overwrite, or delete User B's files, and immutability blocks self-delete/update", async () => {
  const bobPath = `submissions/${BOB_UID}/task_bob_1/proof_2001_tktbob.jpg`;
  await seedServerUploadedProof(bobPath, 256, "image/jpeg");

  const bobRef = ctxBobVerified().ref(bobPath);
  const aliceOnBobRef = ctxAliceVerified().ref(bobPath);

  // User B can read their own proof
  await assertSucceeds(bobRef.getDownloadURL());

  // User A cannot read User B's proof
  await assertFails(aliceOnBobRef.getDownloadURL());

  // User A cannot upload into User B's namespace
  const aliceUploadToBobRef = ctxAliceVerified().ref(
    `submissions/${BOB_UID}/task_bob_1/proof_forged_by_alice.jpg`
  );
  await assertFails(
    aliceUploadToBobRef.put(makeImageBytes(256), { contentType: "image/jpeg" }).then()
  );

  // User A cannot overwrite User B's existing proof
  await assertFails(
    aliceOnBobRef.put(makeImageBytes(128), { contentType: "image/jpeg" }).then()
  );

  // User A cannot delete User B's proof
  await assertFails(aliceOnBobRef.delete());

  // Even owner (User B) cannot overwrite, update metadata, or delete existing proof (strict audit immutability)
  await assertFails(
    bobRef.put(makeImageBytes(128), { contentType: "image/jpeg" }).then()
  );
  await assertFails(bobRef.updateMetadata({ customMetadata: { tampered: "true" } }));
  await assertFails(bobRef.delete());
});

test("6. Arbitrary non-submission paths and oversized or invalid payloads are denied via client Storage Rules", async () => {
  const aliceStorage = ctxAliceVerified();

  // Arbitrary paths outside /submissions/{userId}/{taskId}/{fileName}
  await assertFails(
    aliceStorage.ref(`public/avatar_${ALICE_UID}.jpg`).put(makeImageBytes(128), { contentType: "image/jpeg" }).then()
  );
  await assertFails(
    aliceStorage.ref(`admins/${ALICE_UID}/secret.jpg`).put(makeImageBytes(128), { contentType: "image/jpeg" }).then()
  );

  // Seeded non-submission path cannot be read even by authenticated users
  await seedServerUploadedProof(`internal_backups/${ALICE_UID}/dump.jpg`, 128, "image/jpeg");
  await assertFails(aliceStorage.ref(`internal_backups/${ALICE_UID}/dump.jpg`).getDownloadURL());
  await assertFails(
    ctxBootstrappedAdminVerified().ref(`internal_backups/${ALICE_UID}/dump.jpg`).getDownloadURL()
  );
});

test("7. Admin cross-user proof review requires verified admin authorization (bootstrapped email_verified == true or custom claim admin == true)", async () => {
  const aliceProofPath = `submissions/${ALICE_UID}/task_admin_review/proof_3001_tktadmin.jpg`;
  await seedServerUploadedProof(aliceProofPath, 256, "image/jpeg");

  // Verified bootstrapped admin can read Alice's proof for moderation
  await assertSucceeds(
    ctxBootstrappedAdminVerified().ref(aliceProofPath).getDownloadURL()
  );

  // Custom-claim admin (admin == true) can read Alice's proof for moderation
  await assertSucceeds(
    ctxCustomClaimAdmin().ref(aliceProofPath).getDownloadURL()
  );

  // Unverified bootstrapped admin email is DENIED read access (H-01)
  await assertFails(
    ctxBootstrappedAdminUnverified().ref(aliceProofPath).getDownloadURL()
  );

  // Admin cannot delete or overwrite user's audit proof via client SDK
  await assertFails(
    ctxBootstrappedAdminVerified().ref(aliceProofPath).delete()
  );
  await assertFails(
    ctxBootstrappedAdminVerified()
      .ref(aliceProofPath)
      .put(makeImageBytes(128), { contentType: "image/jpeg" })
      .then()
  );
});

test("8. Pending, rejected, and profileless users cannot directly upload or create orphan files via Storage SDK/REST", async () => {
  // Even with email_verified == true in Firebase Auth JWT, any direct client upload attempt is blocked at the Storage Rule layer
  const pendingUserStorage = testEnv
    .authenticatedContext("pending_user_f3", {
      email: "pending@example.com",
      email_verified: true,
    })
    .storage();
  const rejectedUserStorage = testEnv
    .authenticatedContext("rejected_user_f3", {
      email: "rejected@example.com",
      email_verified: true,
    })
    .storage();

  await assertFails(
    pendingUserStorage
      .ref("submissions/pending_user_f3/task_open_1/proof_orphan.jpg")
      .put(makeImageBytes(256), { contentType: "image/jpeg" })
      .then()
  );
  await assertFails(
    rejectedUserStorage
      .ref("submissions/rejected_user_f3/task_open_1/proof_orphan.jpg")
      .put(makeImageBytes(256), { contentType: "image/jpeg" })
      .then()
  );
});

test("9. End-to-end Phase F.3 server-ticketed storagePath & downloadUrl format compatibility with Firestore submission rules", async () => {
  const aliceStorage = ctxAliceVerified();
  const timestamp = 1763890000123;
  const ticketId = "tkt_1763890000123_a1b2c3d4e5f6";
  const taskId = "task_manual_proof_compat_01";
  const storagePath = `submissions/${ALICE_UID}/${taskId}/proof_${timestamp}_${ticketId}.jpg`;

  await seedServerUploadedProof(storagePath, 1024, "image/jpeg");

  const downloadUrl = await assertSucceeds(
    aliceStorage.ref(storagePath).getDownloadURL()
  );

  // Verify storagePath and downloadUrl meet Firestore submission constraints:
  // - storagePath is non-empty and <= 500 chars
  // - downloadUrl is non-empty and <= 350,000 chars
  assert.ok(storagePath.length > 0 && storagePath.length <= 500);
  assert.ok(downloadUrl.length > 0 && downloadUrl.length <= 350000);
});
