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
const ADMIN_UID = "admin_wd_001";
const ADMIN_EMAIL = "dasakash24059@gmail.com";
const ALICE_UID = "alice_wd_123";
const ALICE_EMAIL = "alice@example.com";
const BOB_UID = "bob_wd_456";
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

function ctxAdmin() {
  return testEnv
    .authenticatedContext(ADMIN_UID, {
      email: ADMIN_EMAIL,
      email_verified: true,
    })
    .firestore();
}

function ctxAlice() {
  return testEnv
    .authenticatedContext(ALICE_UID, {
      email: ALICE_EMAIL,
      email_verified: true,
    })
    .firestore();
}

function ctxBob() {
  return testEnv
    .authenticatedContext(BOB_UID, {
      email: BOB_EMAIL,
      email_verified: true,
    })
    .firestore();
}

async function seedUsers({
  aliceCents = 60000,
  aliceCoins = 75000,
  aliceEligible = 75000,
  alicePromo = 0,
  aliceStatus = "approved",
} = {}) {
  const ts = new Date();
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
      approvalStatus: aliceStatus,
      balanceCents: aliceCents,
      balanceCoins: aliceCoins,
      eligibleCoins: aliceEligible,
      promotionalCoins: alicePromo,
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
}

function buildWithdrawalBatch(
  db,
  {
    reqId,
    uid = ALICE_UID,
    userName = "Alice",
    method = "UPI",
    amountRupees = 200,
    amountCoins = 33000,
    upiId = "alice@upi",
    deductedBalanceCoins = amountCoins,
    deductedEligibleCoins = amountCoins,
    deductedPromotionalCoins = 0,
    deductedBalanceCents = Math.floor((deductedEligibleCoins * 100) / 125),
    newBalanceCoins,
    newEligibleCoins,
    newPromoCoins,
    newBalanceCents,
    idempotencyKey = reqId,
    includeUserUpdate = true,
    includeDebitTx = true,
    includeAdminNotif = true,
  }
) {
  const ts = new Date();
  const batch = db.batch();
  batch.set(db.collection("withdrawal_requests").doc(reqId), {
    requestId: reqId,
    userId: uid,
    userName,
    method,
    amountRupees,
    amountCents: amountRupees * 100,
    amountCoins,
    upiId: method === "UPI" ? upiId : "",
    status: "SUBMITTED",
    statusNote: "",
    adminActionBy: "",
    paymentReference: "",
    giftCode: "",
    giftCodeHash: "",
    coinsDeducted: true,
    coinsRefunded: false,
    deductedBalanceCoins,
    deductedEligibleCoins,
    deductedPromotionalCoins,
    deductedBalanceCents,
    idempotencyKey,
    isDemo: false,
    createdAt: ts,
    updatedAt: ts,
  });

  if (includeUserUpdate) {
    batch.update(db.collection("users").doc(uid), {
      balanceCoins: newBalanceCoins,
      eligibleCoins: newEligibleCoins,
      promotionalCoins: newPromoCoins,
      balanceCents: newBalanceCents,
      lastWithdrawalRequestId: reqId,
      updatedAt: ts,
    });
  }

  if (includeDebitTx) {
    batch.set(db.collection("transactions").doc(`wd_debit_${reqId}`), {
      userId: uid,
      userName,
      type: "WITHDRAWAL_DEBIT",
      title: `${method} Redemption (₹${amountRupees})`,
      amountCents: amountRupees * 100,
      amountCoins,
      status: "DEBITED",
      referenceId: reqId,
      createdAt: ts,
    });
  }

  if (includeAdminNotif) {
    batch.set(db.collection("admin_notifications").doc(`admin_wd_${reqId}`), {
      userId: uid,
      userName,
      title: `New ${method} Request`,
      body: `${userName} requested ₹${amountRupees} (${amountCoins} Coins).`,
      type: "WITHDRAWAL_REQUEST",
      referenceId: reqId,
      isRead: false,
      createdAt: ts,
    });
  }

  return batch;
}

test("W1. Fixed denomination validation rejects unapproved amounts, invalid coin prices, and missing UPI handles", async () => {
  await seedUsers({ aliceCents: 80000, aliceCoins: 100000, aliceEligible: 100000 });
  const aliceDb = ctxAlice();

  // Unapproved amount ₹100 for UPI
  await assertFails(
    buildWithdrawalBatch(aliceDb, {
      reqId: "w1_bad_upi_100",
      method: "UPI",
      amountRupees: 100,
      amountCoins: 16500,
      newBalanceCoins: 83500,
      newEligibleCoins: 83500,
      newPromoCoins: 0,
      newBalanceCents: 66800,
    }).commit()
  );

  // Valid ₹200 UPI tier with tampered 25,000 coins instead of 33,000
  await assertFails(
    buildWithdrawalBatch(aliceDb, {
      reqId: "w1_bad_price_200",
      method: "UPI",
      amountRupees: 200,
      amountCoins: 25000,
      newBalanceCoins: 75000,
      newEligibleCoins: 75000,
      newPromoCoins: 0,
      newBalanceCents: 60000,
    }).commit()
  );

  // Invalid empty UPI handle on UPI withdrawal
  await assertFails(
    buildWithdrawalBatch(aliceDb, {
      reqId: "w1_missing_upi",
      method: "UPI",
      amountRupees: 200,
      amountCoins: 33000,
      upiId: "invalid-no-at",
      newBalanceCoins: 67000,
      newEligibleCoins: 67000,
      newPromoCoins: 0,
      newBalanceCents: 53600,
    }).commit()
  );

  // Valid ₹200 UPI (33,000 Coins) succeeds
  await assertSucceeds(
    buildWithdrawalBatch(aliceDb, {
      reqId: "w1_valid_upi_200",
      method: "UPI",
      amountRupees: 200,
      amountCoins: 33000,
      upiId: "alice@okaxis",
      newBalanceCoins: 67000,
      newEligibleCoins: 67000,
      newPromoCoins: 0,
      newBalanceCents: 53600,
    }).commit()
  );
});

test("W2. Exact eligibleCoins and promotionalCoins mixed-bucket deductions and exact refund parity", async () => {
  // Mixed wallet: 20,000 eligibleCoins (16,000 paise) + 20,000 promotionalCoins = 40,000 balanceCoins
  await seedUsers({
    aliceCents: 16000,
    aliceCoins: 40000,
    aliceEligible: 20000,
    alicePromo: 20000,
  });
  const aliceDb = ctxAlice();
  const adminDb = ctxAdmin();

  // Submitting Amazon Gift Card ₹150 (33,000 Coins) must deduct 20,000 eligible + 13,000 promo + 16,000 paise
  const reqId = "w2_mixed_gc_150";
  await assertSucceeds(
    buildWithdrawalBatch(aliceDb, {
      reqId,
      method: "AMAZON_GIFT_CARD",
      amountRupees: 150,
      amountCoins: 33000,
      deductedBalanceCoins: 33000,
      deductedEligibleCoins: 20000,
      deductedPromotionalCoins: 13000,
      deductedBalanceCents: 16000,
      newBalanceCoins: 7000,
      newEligibleCoins: 0,
      newPromoCoins: 7000,
      newBalanceCents: 0,
    }).commit()
  );

  // Rejecting refunds exact 20,000 eligible + 13,000 promo + 16,000 paise
  const refundTs = new Date();
  const rejectBatch = adminDb.batch();
  rejectBatch.update(adminDb.collection("withdrawal_requests").doc(reqId), {
    status: "REJECTED",
    statusNote: "Stock out of ₹150 Amazon card",
    adminActionBy: ADMIN_UID,
    coinsRefunded: true,
    rejectedAt: refundTs,
    updatedAt: refundTs,
  });
  rejectBatch.update(adminDb.collection("users").doc(ALICE_UID), {
    balanceCoins: 40000,
    eligibleCoins: 20000,
    promotionalCoins: 20000,
    balanceCents: 16000,
    lastRefundedWithdrawalId: reqId,
    updatedAt: refundTs,
  });
  rejectBatch.set(adminDb.collection("transactions").doc(`wd_refund_${reqId}`), {
    userId: ALICE_UID,
    userName: "Alice",
    type: "WITHDRAWAL_REFUND",
    title: "Amazon Gift Card Refund (₹150)",
    amountCents: 15000,
    amountCoins: 33000,
    status: "REFUNDED",
    referenceId: reqId,
    createdAt: refundTs,
  });
  await assertSucceeds(rejectBatch.commit());
});

test("W3. Insufficient balance blocks withdrawal for zero-balance and underfunded users", async () => {
  await seedUsers({ aliceCents: 33600, aliceCoins: 42000, aliceEligible: 42000 });
  const aliceDb = ctxAlice();
  const bobDb = ctxBob();

  // Bob with 0 coins tries ₹200 UPI
  await assertFails(
    buildWithdrawalBatch(bobDb, {
      reqId: "w3_bob_zero",
      uid: BOB_UID,
      userName: "Bob",
      method: "UPI",
      amountRupees: 200,
      amountCoins: 33000,
      upiId: "bob@upi",
      deductedBalanceCoins: 33000,
      deductedEligibleCoins: 0,
      deductedPromotionalCoins: 0,
      deductedBalanceCents: 0,
      newBalanceCoins: -33000,
      newEligibleCoins: 0,
      newPromoCoins: 0,
      newBalanceCents: 0,
    }).commit()
  );

  // Alice with 42,000 coins tries ₹500 UPI (82,500 coins)
  await assertFails(
    buildWithdrawalBatch(aliceDb, {
      reqId: "w3_alice_short",
      method: "UPI",
      amountRupees: 500,
      amountCoins: 82500,
      deductedBalanceCoins: 82500,
      deductedEligibleCoins: 42000,
      deductedPromotionalCoins: 0,
      deductedBalanceCents: 33600,
      newBalanceCoins: -40500,
      newEligibleCoins: 0,
      newPromoCoins: 0,
      newBalanceCents: 0,
    }).commit()
  );
});

test("W4. Bucket manipulation, user under-deduction, and direct balanceCoins inflation always fail", async () => {
  await seedUsers({
    aliceCents: 33600,
    aliceCoins: 52000,
    aliceEligible: 42000,
    alicePromo: 10000,
  });
  const aliceDb = ctxAlice();

  // 1. Direct balanceCoins inflation without matching promoDelta
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      balanceCoins: 999999,
      updatedAt: new Date(),
    })
  );

  // 2. Skipping eligibleCoins (deducting 0 eligibleCoins when Alice has 42,000 eligibleCoins)
  await assertFails(
    buildWithdrawalBatch(aliceDb, {
      reqId: "w4_bucket_skip",
      method: "UPI",
      amountRupees: 200,
      amountCoins: 33000,
      deductedBalanceCoins: 33000,
      deductedEligibleCoins: 0,
      deductedPromotionalCoins: 10000,
      deductedBalanceCents: 0,
      newBalanceCoins: 19000,
      newEligibleCoins: 42000,
      newPromoCoins: 0,
      newBalanceCents: 33600,
    }).commit()
  );

  // 3. Under-deducting users/{uid}.balanceCoins (subtracting 1,000 instead of 33,000)
  await assertFails(
    buildWithdrawalBatch(aliceDb, {
      reqId: "w4_under_deduct",
      method: "UPI",
      amountRupees: 200,
      amountCoins: 33000,
      deductedBalanceCoins: 33000,
      deductedEligibleCoins: 33000,
      deductedPromotionalCoins: 0,
      deductedBalanceCents: 26400,
      newBalanceCoins: 51000,
      newEligibleCoins: 51000,
      newPromoCoins: 10000,
      newBalanceCents: 32800,
    }).commit()
  );

  // 4. Request created without deducting user or creating wd_debit transaction
  await assertFails(
    buildWithdrawalBatch(aliceDb, {
      reqId: "w4_no_user_update",
      method: "UPI",
      amountRupees: 200,
      amountCoins: 33000,
      includeUserUpdate: false,
      includeDebitTx: false,
    }).commit()
  );
});

test("W5. Disabled withdrawal method and denomination availability enforced server-side in Firestore rules", async () => {
  await seedUsers({ aliceCents: 80000, aliceCoins: 100000, aliceEligible: 100000 });
  const adminDb = ctxAdmin();
  const aliceDb = ctxAlice();

  await assertSucceeds(
    adminDb.collection("app_config").doc("withdrawal_availability").set({
      upiEnabled: true,
      amazonEnabled: false,
      googlePlayEnabled: true,
      flipkartEnabled: true,
      upiDenominationsEnabled: { "200": false, "500": true },
      updatedAt: new Date(),
    })
  );

  // Disabled ₹200 UPI denomination -> MUST FAIL
  await assertFails(
    buildWithdrawalBatch(aliceDb, {
      reqId: "w5_disabled_upi_200",
      method: "UPI",
      amountRupees: 200,
      amountCoins: 33000,
      newBalanceCoins: 67000,
      newEligibleCoins: 67000,
      newPromoCoins: 0,
      newBalanceCents: 53600,
    }).commit()
  );

  // Disabled Amazon Gift Card method -> MUST FAIL
  await assertFails(
    buildWithdrawalBatch(aliceDb, {
      reqId: "w5_disabled_amazon",
      method: "AMAZON_GIFT_CARD",
      amountRupees: 150,
      amountCoins: 33000,
      newBalanceCoins: 67000,
      newEligibleCoins: 67000,
      newPromoCoins: 0,
      newBalanceCents: 53600,
    }).commit()
  );

  // Enabled ₹500 UPI denomination -> MUST SUCCEED
  await assertSucceeds(
    buildWithdrawalBatch(aliceDb, {
      reqId: "w5_enabled_upi_500",
      method: "UPI",
      amountRupees: 500,
      amountCoins: 82500,
      newBalanceCoins: 17500,
      newEligibleCoins: 17500,
      newPromoCoins: 0,
      newBalanceCents: 14000,
    }).commit()
  );
});

test("W6. Duplicate request IDs and mismatched idempotency keys are rejected", async () => {
  await seedUsers({ aliceCents: 80000, aliceCoins: 100000, aliceEligible: 100000 });
  const aliceDb = ctxAlice();

  // Mismatched idempotencyKey != requestId -> MUST FAIL
  await assertFails(
    buildWithdrawalBatch(aliceDb, {
      reqId: "w6_req_1",
      method: "UPI",
      amountRupees: 200,
      amountCoins: 33000,
      idempotencyKey: "different_idem_key",
      newBalanceCoins: 67000,
      newEligibleCoins: 67000,
      newPromoCoins: 0,
      newBalanceCents: 53600,
    }).commit()
  );

  // Valid request succeeds once
  await assertSucceeds(
    buildWithdrawalBatch(aliceDb, {
      reqId: "w6_req_1",
      method: "UPI",
      amountRupees: 200,
      amountCoins: 33000,
      newBalanceCoins: 67000,
      newEligibleCoins: 67000,
      newPromoCoins: 0,
      newBalanceCents: 53600,
    }).commit()
  );

  // Re-submitting duplicate requestId -> MUST FAIL
  await assertFails(
    buildWithdrawalBatch(aliceDb, {
      reqId: "w6_req_1",
      method: "UPI",
      amountRupees: 200,
      amountCoins: 33000,
      newBalanceCoins: 34000,
      newEligibleCoins: 34000,
      newPromoCoins: 0,
      newBalanceCents: 27200,
    }).commit()
  );
});

test("W7. Cross-user withdrawal read and list access denied while owner and Admin can read", async () => {
  await seedUsers({ aliceCents: 60000, aliceCoins: 75000, aliceEligible: 75000 });
  const aliceDb = ctxAlice();
  const bobDb = ctxBob();
  const adminDb = ctxAdmin();

  const reqId = "w7_alice_req";
  await assertSucceeds(
    buildWithdrawalBatch(aliceDb, {
      reqId,
      method: "GOOGLE_PLAY_GIFT_CARD",
      amountRupees: 150,
      amountCoins: 33000,
      newBalanceCoins: 42000,
      newEligibleCoins: 42000,
      newPromoCoins: 0,
      newBalanceCents: 33600,
    }).commit()
  );

  await assertSucceeds(aliceDb.collection("withdrawal_requests").doc(reqId).get());
  await assertSucceeds(
    aliceDb.collection("withdrawal_requests").where("userId", "==", ALICE_UID).get()
  );
  await assertSucceeds(adminDb.collection("withdrawal_requests").get());

  // Bob cannot get Alice's doc, query Alice's docs, or list all withdrawal_requests
  await assertFails(bobDb.collection("withdrawal_requests").doc(reqId).get());
  await assertFails(
    bobDb.collection("withdrawal_requests").where("userId", "==", ALICE_UID).get()
  );
  await assertFails(bobDb.collection("withdrawal_requests").get());
});

test("W8. Gift code privacy (owner/Admin only) and duplicate gift-code hash prevention", async () => {
  await seedUsers({ aliceCents: 80000, aliceCoins: 100000, aliceEligible: 100000 });
  const aliceDb = ctxAlice();
  const bobDb = ctxBob();
  const adminDb = ctxAdmin();

  const req1 = "w8_gc_1";
  const req2 = "w8_gc_2";
  await assertSucceeds(
    buildWithdrawalBatch(aliceDb, {
      reqId: req1,
      method: "FLIPKART_GIFT_CARD",
      amountRupees: 150,
      amountCoins: 33000,
      newBalanceCoins: 67000,
      newEligibleCoins: 67000,
      newPromoCoins: 0,
      newBalanceCents: 53600,
    }).commit()
  );
  await assertSucceeds(
    buildWithdrawalBatch(aliceDb, {
      reqId: req2,
      method: "FLIPKART_GIFT_CARD",
      amountRupees: 150,
      amountCoins: 33000,
      newBalanceCoins: 34000,
      newEligibleCoins: 34000,
      newPromoCoins: 0,
      newBalanceCents: 27200,
    }).commit()
  );

  const giftCode = "FLPK-UNIQUE-2026-9911";
  const codeHash = crypto.createHash("sha256").update(giftCode).digest("hex");

  const fulfill1 = adminDb.batch();
  fulfill1.set(adminDb.collection("used_gift_codes").doc(codeHash), {
    codeHash,
    requestId: req1,
    method: "FLIPKART_GIFT_CARD",
    amountRupees: 150,
    assignedBy: ADMIN_UID,
    assignedAt: new Date(),
  });
  fulfill1.update(adminDb.collection("withdrawal_requests").doc(req1), {
    status: "COMPLETED",
    statusNote: "Delivered",
    adminActionBy: ADMIN_UID,
    giftCode,
    giftCodeHash: codeHash,
    giftCodeAssignedAt: new Date(),
    completedAt: new Date(),
    updatedAt: new Date(),
  });
  await assertSucceeds(fulfill1.commit());

  // Owner can read giftCode; Bob cannot; neither Alice nor Bob can read /used_gift_codes
  const ownerSnap = await assertSucceeds(aliceDb.collection("withdrawal_requests").doc(req1).get());
  if (ownerSnap.data().giftCode !== giftCode) {
    throw new Error("Expected owner to view delivered gift code");
  }
  await assertFails(bobDb.collection("withdrawal_requests").doc(req1).get());
  await assertFails(aliceDb.collection("used_gift_codes").doc(codeHash).get());
  await assertFails(bobDb.collection("used_gift_codes").get());

  // Reusing the exact same giftCodeHash for req2 -> MUST FAIL
  const fulfill2Duplicate = adminDb.batch();
  fulfill2Duplicate.set(adminDb.collection("used_gift_codes").doc(codeHash), {
    codeHash,
    requestId: req2,
    method: "FLIPKART_GIFT_CARD",
    amountRupees: 150,
    assignedBy: ADMIN_UID,
    assignedAt: new Date(),
  });
  fulfill2Duplicate.update(adminDb.collection("withdrawal_requests").doc(req2), {
    status: "COMPLETED",
    statusNote: "Duplicate attempt",
    adminActionBy: ADMIN_UID,
    giftCode,
    giftCodeHash: codeHash,
    giftCodeAssignedAt: new Date(),
    completedAt: new Date(),
    updatedAt: new Date(),
  });
  await assertFails(fulfill2Duplicate.commit());
});

test("W9. Forged user notifications and forged admin notifications are blocked", async () => {
  await seedUsers({ aliceCents: 60000, aliceCoins: 75000, aliceEligible: 75000 });
  const aliceDb = ctxAlice();
  const bobDb = ctxBob();
  const adminDb = ctxAdmin();

  const aliceNotifRef = aliceDb.collection("users").doc(ALICE_UID).collection("notifications").doc("n1");
  await assertSucceeds(
    aliceNotifRef.set({
      userId: ALICE_UID,
      title: "Withdrawal Submitted",
      body: "Valid own notification",
      type: "WITHDRAWAL",
      referenceId: "r1",
      isRead: false,
      createdAt: new Date(),
    })
  );

  // Alice can mark isRead, CANNOT tamper with title
  await assertFails(aliceNotifRef.update({ title: "Tampered Title" }));
  await assertSucceeds(aliceNotifRef.update({ isRead: true }));

  // Bob CANNOT spoof notification inside Alice's subcollection
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("notifications").doc("spoof").set({
      userId: ALICE_UID,
      title: "Spoof",
      body: "Fake gift card ready",
      type: "GIFT_CARD_READY",
      referenceId: "r1",
      isRead: false,
      createdAt: new Date(),
    })
  );

  // Forged admin notification without matching atomic withdrawal request -> MUST FAIL
  await assertFails(
    bobDb.collection("admin_notifications").doc("admin_wd_fake_req").set({
      userId: BOB_UID,
      userName: "Bob",
      title: "Fake Alert",
      body: "No withdrawal request exists",
      type: "WITHDRAWAL_REQUEST",
      referenceId: "fake_req",
      isRead: false,
      createdAt: new Date(),
    })
  );

  // Bob CANNOT read admin_notifications; Admin CAN
  await assertFails(bobDb.collection("admin_notifications").get());
  await assertSucceeds(adminDb.collection("admin_notifications").get());
});

test("W10. Unauthorized status changes and backward Admin PROCESSING -> UNDER_REVIEW transitions fail", async () => {
  await seedUsers({ aliceCents: 60000, aliceCoins: 75000, aliceEligible: 75000 });
  const aliceDb = ctxAlice();
  const bobDb = ctxBob();
  const adminDb = ctxAdmin();

  const reqId = "w10_status_req";
  await assertSucceeds(
    buildWithdrawalBatch(aliceDb, {
      reqId,
      method: "UPI",
      amountRupees: 200,
      amountCoins: 33000,
      newBalanceCoins: 42000,
      newEligibleCoins: 42000,
      newPromoCoins: 0,
      newBalanceCents: 33600,
    }).commit()
  );

  // Ordinary users cannot change status
  await assertFails(
    aliceDb.collection("withdrawal_requests").doc(reqId).update({
      status: "UNDER_REVIEW",
      updatedAt: new Date(),
    })
  );
  await assertFails(
    bobDb.collection("withdrawal_requests").doc(reqId).update({
      status: "PROCESSING",
      updatedAt: new Date(),
    })
  );

  // Admin can advance SUBMITTED -> UNDER_REVIEW -> PROCESSING
  await assertSucceeds(
    adminDb.collection("withdrawal_requests").doc(reqId).update({
      status: "UNDER_REVIEW",
      statusNote: "Reviewed",
      adminActionBy: ADMIN_UID,
      reviewedAt: new Date(),
      updatedAt: new Date(),
    })
  );
  await assertSucceeds(
    adminDb.collection("withdrawal_requests").doc(reqId).update({
      status: "PROCESSING",
      statusNote: "Processing payout",
      adminActionBy: ADMIN_UID,
      reviewedAt: new Date(),
      updatedAt: new Date(),
    })
  );

  // Admin cannot move PROCESSING backward to UNDER_REVIEW or perform no-op status rewrite
  await assertFails(
    adminDb.collection("withdrawal_requests").doc(reqId).update({
      status: "UNDER_REVIEW",
      statusNote: "Backward",
      adminActionBy: ADMIN_UID,
      reviewedAt: new Date(),
      updatedAt: new Date(),
    })
  );
  await assertFails(
    adminDb.collection("withdrawal_requests").doc(reqId).update({
      status: "PROCESSING",
      statusNote: "No-op duplicate",
      adminActionBy: ADMIN_UID,
      reviewedAt: new Date(),
      updatedAt: new Date(),
    })
  );
});

test("W11. UPI and Gift Card completion forgery by ordinary users is blocked; valid Admin UPI completion succeeds", async () => {
  await seedUsers({ aliceCents: 80000, aliceCoins: 100000, aliceEligible: 100000 });
  const aliceDb = ctxAlice();
  const adminDb = ctxAdmin();

  const upiReq = "w11_upi_req";
  const gcReq = "w11_gc_req";
  await assertSucceeds(
    buildWithdrawalBatch(aliceDb, {
      reqId: upiReq,
      method: "UPI",
      amountRupees: 200,
      amountCoins: 33000,
      newBalanceCoins: 67000,
      newEligibleCoins: 67000,
      newPromoCoins: 0,
      newBalanceCents: 53600,
    }).commit()
  );
  await assertSucceeds(
    buildWithdrawalBatch(aliceDb, {
      reqId: gcReq,
      method: "AMAZON_GIFT_CARD",
      amountRupees: 150,
      amountCoins: 33000,
      newBalanceCoins: 34000,
      newEligibleCoins: 34000,
      newPromoCoins: 0,
      newBalanceCents: 27200,
    }).commit()
  );

  // Owner cannot forge UPI completion or UTR
  await assertFails(
    aliceDb.collection("withdrawal_requests").doc(upiReq).update({
      status: "COMPLETED",
      paymentReference: "UTR-FORGED-9999",
      completedAt: new Date(),
      updatedAt: new Date(),
    })
  );

  // Owner cannot forge Gift Card completion or giftCode
  await assertFails(
    aliceDb.collection("withdrawal_requests").doc(gcReq).update({
      status: "COMPLETED",
      giftCode: "AMZN-FORGED-1111",
      completedAt: new Date(),
      updatedAt: new Date(),
    })
  );

  // Admin cannot complete a Gift Card request via UPI completion fields (missing giftCodeHash/used_gift_codes)
  await assertFails(
    adminDb.collection("withdrawal_requests").doc(gcReq).update({
      status: "COMPLETED",
      statusNote: "Wrong method path",
      adminActionBy: ADMIN_UID,
      paymentReference: "UTR-WRONG",
      completedAt: new Date(),
      updatedAt: new Date(),
    })
  );

  // Valid Admin UPI payout completion succeeds
  await assertSucceeds(
    adminDb.collection("withdrawal_requests").doc(upiReq).update({
      status: "COMPLETED",
      statusNote: "Manual UPI transfer confirmed",
      adminActionBy: ADMIN_UID,
      paymentReference: "UTR61234567890",
      completedAt: new Date(),
      updatedAt: new Date(),
    })
  );
});

test("W12. Duplicate refunds and terminal COMPLETED/REJECTED state mutations are strictly rejected", async () => {
  await seedUsers({ aliceCents: 80000, aliceCoins: 100000, aliceEligible: 100000 });
  const aliceDb = ctxAlice();
  const adminDb = ctxAdmin();

  const upiReq = "w12_reject_once";
  const completedReq = "w12_completed_lock";
  await assertSucceeds(
    buildWithdrawalBatch(aliceDb, {
      reqId: upiReq,
      method: "UPI",
      amountRupees: 200,
      amountCoins: 33000,
      newBalanceCoins: 67000,
      newEligibleCoins: 67000,
      newPromoCoins: 0,
      newBalanceCents: 53600,
    }).commit()
  );
  await assertSucceeds(
    buildWithdrawalBatch(aliceDb, {
      reqId: completedReq,
      method: "UPI",
      amountRupees: 200,
      amountCoins: 33000,
      newBalanceCoins: 34000,
      newEligibleCoins: 34000,
      newPromoCoins: 0,
      newBalanceCents: 27200,
    }).commit()
  );

  // Mark completedReq as COMPLETED
  await assertSucceeds(
    adminDb.collection("withdrawal_requests").doc(completedReq).update({
      status: "COMPLETED",
      statusNote: "Paid",
      adminActionBy: ADMIN_UID,
      paymentReference: "UTR99887766",
      completedAt: new Date(),
      updatedAt: new Date(),
    })
  );

  // Attempting to reject & refund an already-COMPLETED request -> MUST FAIL
  const rejectCompleted = adminDb.batch();
  rejectCompleted.update(adminDb.collection("withdrawal_requests").doc(completedReq), {
    status: "REJECTED",
    statusNote: "Illegal post-completion refund",
    adminActionBy: ADMIN_UID,
    coinsRefunded: true,
    rejectedAt: new Date(),
    updatedAt: new Date(),
  });
  rejectCompleted.update(adminDb.collection("users").doc(ALICE_UID), {
    balanceCoins: 67000,
    eligibleCoins: 67000,
    promotionalCoins: 0,
    balanceCents: 53600,
    lastRefundedWithdrawalId: completedReq,
    updatedAt: new Date(),
  });
  rejectCompleted.set(adminDb.collection("transactions").doc(`wd_refund_${completedReq}`), {
    userId: ALICE_UID,
    userName: "Alice",
    type: "WITHDRAWAL_REFUND",
    title: "Withdrawal Refund (₹200)",
    amountCents: 20000,
    amountCoins: 33000,
    status: "REFUNDED",
    referenceId: completedReq,
    createdAt: new Date(),
  });
  await assertFails(rejectCompleted.commit());

  // First reject & refund of upiReq -> MUST SUCCEED
  const rTs = new Date();
  const rejectOnce = adminDb.batch();
  rejectOnce.update(adminDb.collection("withdrawal_requests").doc(upiReq), {
    status: "REJECTED",
    statusNote: "Invalid UPI ID",
    adminActionBy: ADMIN_UID,
    coinsRefunded: true,
    rejectedAt: rTs,
    updatedAt: rTs,
  });
  rejectOnce.update(adminDb.collection("users").doc(ALICE_UID), {
    balanceCoins: 67000,
    eligibleCoins: 67000,
    promotionalCoins: 0,
    balanceCents: 53600,
    lastRefundedWithdrawalId: upiReq,
    updatedAt: rTs,
  });
  rejectOnce.set(adminDb.collection("transactions").doc(`wd_refund_${upiReq}`), {
    userId: ALICE_UID,
    userName: "Alice",
    type: "WITHDRAWAL_REFUND",
    title: "Withdrawal Refund (₹200)",
    amountCents: 20000,
    amountCoins: 33000,
    status: "REFUNDED",
    referenceId: upiReq,
    createdAt: rTs,
  });
  await assertSucceeds(rejectOnce.commit());

  // Second refund on already-REJECTED upiReq -> MUST FAIL
  const rejectTwice = adminDb.batch();
  rejectTwice.update(adminDb.collection("withdrawal_requests").doc(upiReq), {
    status: "REJECTED",
    statusNote: "Second refund attempt",
    adminActionBy: ADMIN_UID,
    coinsRefunded: true,
    rejectedAt: new Date(),
    updatedAt: new Date(),
  });
  rejectTwice.update(adminDb.collection("users").doc(ALICE_UID), {
    balanceCoins: 100000,
    eligibleCoins: 100000,
    promotionalCoins: 0,
    balanceCents: 80000,
    lastRefundedWithdrawalId: upiReq,
    updatedAt: new Date(),
  });
  await assertFails(rejectTwice.commit());
});
