const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ADMIN_UID = "promo_admin_001";
const ADMIN_EMAIL = "dasakash24059@gmail.com";
const ALICE_UID = "promo_alice_123";
const ALICE_EMAIL = "alice_promo@example.com";
const BOB_UID = "promo_bob_456";
const BOB_EMAIL = "bob_promo@example.com";

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

async function seedPromoFixture() {
  const ts = new Date();
  const futureDate = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000);
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
      displayName: "Alice Approved",
      role: "user",
      approvalStatus: "approved",
      balanceCents: 1000,
      balanceCoins: 1450,
      eligibleCoins: 1250,
      promotionalCoins: 200,
      completedTasksCount: 1,
      assignedTaskId: "",
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("users").doc(BOB_UID).set({
      userId: BOB_UID,
      email: BOB_EMAIL,
      displayName: "Bob Approved",
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

    await db.collection("promo_codes").doc("WELCOME100").set({
      code: "WELCOME100",
      rewardCoins: 100,
      maxSlots: 50,
      usedSlots: 1,
      expiresAt: futureDate,
      expiresAtMillis: futureDate.getTime(),
      expiresAtLabel: "2026-12-31 23:59",
      isActive: true,
      status: "ACTIVE",
      createdBy: ADMIN_UID,
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("promo_codes").doc("PRIVATE_VIP_500").set({
      code: "PRIVATE_VIP_500",
      rewardCoins: 500,
      maxSlots: 5,
      usedSlots: 0,
      expiresAt: futureDate,
      expiresAtMillis: futureDate.getTime(),
      expiresAtLabel: "2026-12-31 23:59",
      isActive: false,
      status: "INACTIVE",
      createdBy: ADMIN_UID,
      createdAt: ts,
      updatedAt: ts,
    });

    await db
      .collection("promo_codes")
      .doc("WELCOME100")
      .collection("redemptions")
      .doc(ALICE_UID)
      .set({
        promoId: "WELCOME100",
        code: "WELCOME100",
        userId: ALICE_UID,
        userName: "Alice Approved",
        rewardCoins: 100,
        redeemedAt: ts,
      });

    await db.collection("promo_redemptions").doc(`WELCOME100_${ALICE_UID}`).set({
      id: `WELCOME100_${ALICE_UID}`,
      promoId: "WELCOME100",
      code: "WELCOME100",
      userId: ALICE_UID,
      userName: "Alice Approved",
      rewardCoins: 100,
      redeemedAt: ts,
    });
  });
}

test("P1. Unauthenticated reads and writes on promo_codes, redemptions, and promo_redemptions are denied", async () => {
  await seedPromoFixture();
  const unauthDb = testEnv.unauthenticatedContext().firestore();

  await assertFails(unauthDb.collection("promo_codes").get());
  await assertFails(unauthDb.collection("promo_codes").doc("WELCOME100").get());
  await assertFails(
    unauthDb
      .collection("promo_codes")
      .doc("WELCOME100")
      .collection("redemptions")
      .doc(ALICE_UID)
      .get()
  );
  await assertFails(unauthDb.collection("promo_redemptions").get());
  await assertFails(
    unauthDb.collection("promo_redemptions").doc(`WELCOME100_${ALICE_UID}`).get()
  );

  await assertFails(
    unauthDb.collection("promo_codes").doc("HACK100").set({ code: "HACK100", rewardCoins: 999 })
  );
  await assertFails(
    unauthDb
      .collection("promo_redemptions")
      .doc("HACK100_anon")
      .set({ promoId: "HACK100", userId: "anon", rewardCoins: 999 })
  );
});

test("P2. Ordinary users cannot read or list active, inactive, or private promo_codes; only Admins can read/list", async () => {
  await seedPromoFixture();
  const aliceDb = ctxAlice();
  const adminDb = ctxAdmin();

  // Ordinary approved user cannot list or get any promo code
  await assertFails(aliceDb.collection("promo_codes").get());
  await assertFails(aliceDb.collection("promo_codes").where("isActive", "==", true).get());
  await assertFails(aliceDb.collection("promo_codes").doc("WELCOME100").get());
  await assertFails(aliceDb.collection("promo_codes").doc("PRIVATE_VIP_500").get());
  await assertFails(aliceDb.collection("promo_codes").doc("NON_EXISTENT_CODE").get());

  // Authorized admin can read and list promo_codes
  const adminList = await assertSucceeds(adminDb.collection("promo_codes").get());
  if (adminList.docs.length !== 2) {
    throw new Error(`Expected 2 promo_codes for admin, got ${adminList.docs.length}`);
  }
  await assertSucceeds(adminDb.collection("promo_codes").doc("PRIVATE_VIP_500").get());
});

test("P3. All client create/update/delete writes on promo_codes, redemptions, and promo_redemptions are denied (even for Admins)", async () => {
  await seedPromoFixture();
  const aliceDb = ctxAlice();
  const adminDb = ctxAdmin();
  const ts = new Date();

  for (const db of [aliceDb, adminDb]) {
    await assertFails(
      db.collection("promo_codes").doc("NEWCODE").set({
        code: "NEWCODE",
        rewardCoins: 500,
        maxSlots: 10,
        usedSlots: 0,
        isActive: true,
        status: "ACTIVE",
        createdAt: ts,
        updatedAt: ts,
      })
    );
    await assertFails(
      db.collection("promo_codes").doc("WELCOME100").update({
        rewardCoins: 999999,
        usedSlots: 2,
        updatedAt: ts,
      })
    );
    await assertFails(db.collection("promo_codes").doc("WELCOME100").delete());

    await assertFails(
      db
        .collection("promo_codes")
        .doc("WELCOME100")
        .collection("redemptions")
        .doc(BOB_UID)
        .set({
          promoId: "WELCOME100",
          userId: BOB_UID,
          rewardCoins: 100,
          redeemedAt: ts,
        })
    );
    await assertFails(
      db
        .collection("promo_codes")
        .doc("WELCOME100")
        .collection("redemptions")
        .doc(ALICE_UID)
        .delete()
    );

    await assertFails(
      db.collection("promo_redemptions").doc(`WELCOME100_${BOB_UID}`).set({
        promoId: "WELCOME100",
        userId: BOB_UID,
        rewardCoins: 100,
        redeemedAt: ts,
      })
    );
    await assertFails(
      db.collection("promo_redemptions").doc(`WELCOME100_${ALICE_UID}`).update({
        rewardCoins: 9999,
      })
    );
    await assertFails(
      db.collection("promo_redemptions").doc(`WELCOME100_${ALICE_UID}`).delete()
    );
  }
});

test("P4. Cross-user redemption reads are denied while owner and Admin reads are preserved", async () => {
  await seedPromoFixture();
  const aliceDb = ctxAlice();
  const bobDb = ctxBob();
  const adminDb = ctxAdmin();

  // Owner Alice can read her own redemption records
  await assertSucceeds(
    aliceDb
      .collection("promo_codes")
      .doc("WELCOME100")
      .collection("redemptions")
      .doc(ALICE_UID)
      .get()
  );
  await assertSucceeds(
    aliceDb
      .collection("promo_codes")
      .doc("WELCOME100")
      .collection("redemptions")
      .where("userId", "==", ALICE_UID)
      .get()
  );
  await assertSucceeds(
    aliceDb.collection("promo_redemptions").doc(`WELCOME100_${ALICE_UID}`).get()
  );
  await assertSucceeds(
    aliceDb.collection("promo_redemptions").where("userId", "==", ALICE_UID).get()
  );

  // Bob cannot read or list Alice's redemption records or probe non-existent IDs
  await assertFails(
    bobDb
      .collection("promo_codes")
      .doc("WELCOME100")
      .collection("redemptions")
      .doc(ALICE_UID)
      .get()
  );
  await assertFails(
    bobDb.collection("promo_codes").doc("WELCOME100").collection("redemptions").get()
  );
  await assertFails(
    bobDb.collection("promo_redemptions").doc(`WELCOME100_${ALICE_UID}`).get()
  );
  await assertFails(
    bobDb.collection("promo_redemptions").doc("WELCOME100_NONEXISTENT").get()
  );
  await assertFails(bobDb.collection("promo_redemptions").get());

  // Admin can read and list all redemption records
  await assertSucceeds(
    adminDb.collection("promo_codes").doc("WELCOME100").collection("redemptions").get()
  );
  await assertSucceeds(adminDb.collection("promo_redemptions").get());
});

test("P5. Wallet protection: approved users cannot directly increase promotionalCoins, balanceCoins, balanceCents, or eligibleCoins, nor forge promo transactions/notifications", async () => {
  await seedPromoFixture();
  const aliceDb = ctxAlice();
  const ts = new Date();

  // 1. Old promo loophole (+50000 coins) -> MUST FAIL
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      promotionalCoins: 50200,
      balanceCoins: 51450,
      updatedAt: ts,
    })
  );

  // 2. Old mini-reward loophole (+100 / +500 / +1000 coins) -> MUST FAIL
  for (const delta of [1, 100, 500, 1000]) {
    await assertFails(
      aliceDb.collection("users").doc(ALICE_UID).update({
        promotionalCoins: 200 + delta,
        balanceCoins: 1450 + delta,
        updatedAt: ts,
      })
    );
  }

  // 3. Direct balanceCoins / eligibleCoins / balanceCents modification -> MUST FAIL
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      balanceCoins: 99999,
      updatedAt: ts,
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      eligibleCoins: 99999,
      updatedAt: ts,
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      balanceCents: 50000,
      updatedAt: ts,
    })
  );

  // 4. Attempting to forge a PROMO_CODE or PROMO-like transaction -> MUST FAIL
  await assertFails(
    aliceDb.collection("transactions").doc(`promo_WELCOME100_${ALICE_UID}`).set({
      userId: ALICE_UID,
      userName: "Alice Approved",
      type: "PROMO_CODE",
      title: "Promo Code Redeemed",
      amountCents: 100,
      amountCoins: 100,
      status: "CREDITED",
      referenceId: "WELCOME100",
      createdAt: ts,
    })
  );
  await assertFails(
    aliceDb.collection("transactions").doc("forged_scratch_tx").set({
      userId: ALICE_UID,
      userName: "Alice Approved",
      type: "SCRATCH_CARD",
      title: "Scratch Reward",
      amountCents: 50,
      amountCoins: 50,
      status: "CREDITED",
      referenceId: "card_1",
      createdAt: ts,
    })
  );

  // 5. Attempting to spoof PROMO_CODE_REWARD notification -> MUST FAIL
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("notifications")
      .doc("spoof_promo_notif")
      .set({
        userId: ALICE_UID,
        title: "Promo Redeemed",
        body: "+5000 Coins",
        type: "PROMO_CODE_REWARD",
        referenceId: "WELCOME100",
        isRead: false,
        createdAt: ts,
      })
  );

  // 6. Legitimate profile update (displayName) and WITHDRAWAL notification still succeed
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      displayName: "Alice Verified Name",
      updatedAt: ts,
    })
  );
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("notifications")
      .doc("valid_wd_notif")
      .set({
        userId: ALICE_UID,
        title: "Withdrawal Submitted",
        body: "Request received",
        type: "WITHDRAWAL",
        referenceId: "wd_123",
        isRead: false,
        createdAt: ts,
      })
  );

  // 7. Existing Scratch Card security rules remain locked down
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("scratch_sessions")
      .doc("fake_card")
      .set({ rewardCoins: 999, status: "ACTIVE" })
  );
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("scratch_stats")
      .doc("2026-11-14")
      .set({ unlockedScratchesCount: 10 })
  );
});
