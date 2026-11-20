const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ADMIN_UID = "admin_001";
const ADMIN_EMAIL = "dasakash24059@gmail.com";
const ALICE_UID = "alice_123";
const ALICE_EMAIL = "alice@example.com";

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
      port: emulatorPort
    }
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

async function seedTrackingFoundationAndAdminData() {
  const ts = new Date(Date.now() - 5000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("admins").doc(ADMIN_UID).set({
      userId: ADMIN_UID,
      email: ADMIN_EMAIL,
      createdAt: ts
    });
    await db.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: ALICE_EMAIL,
      displayName: "Alice",
      role: "user",
      approvalStatus: "approved",
      balanceCents: 0,
      virtualPoints: 0,
      completedTasksCount: 0,
      assignedTaskId: "",
      createdAt: ts,
      updatedAt: ts
    });
    await db.collection("campaign_networks").doc("net_alpha").set({
      networkId: "net_alpha",
      name: "Alpha Affiliate",
      adapterType: "AFFILIATE_POSTBACK",
      isActive: true,
      parameterMapping: { clickIdParam: "click_id" },
      signatureConfig: { algorithm: "HMAC_SHA256", secretEnvKey: "ALPHA_POSTBACK_SECRET" },
      createdAt: ts,
      updatedAt: ts
    });
    await db.collection("campaign_tracking_configs").doc("camp_001").set({
      configId: "camp_001",
      campaignId: "camp_001",
      isActive: true,
      submissionBudget: 100,
      usedSubmissions: 0,
      allowedCountries: ["IN"],
      allowedPlatforms: ["ANDROID"],
      rewardTf: 500,
      verificationMode: "AFFILIATE_POSTBACK",
      networkId: "net_alpha",
      trackingNetworkRef: "campaign_networks/net_alpha",
      postbackSecretHash: "sha256_secret_digest",
      privateSecretRef: "ALPHA_POSTBACK_SECRET",
      createdAt: ts,
      updatedAt: ts
    });
    await db.collection("campaign_clicks").doc("clk_unpredictable_0001").set({
      clickId: "clk_unpredictable_0001",
      userId: ALICE_UID,
      campaignId: "camp_001",
      networkId: "net_alpha",
      trackingNetworkRef: "campaign_networks/net_alpha",
      verificationMode: "AFFILIATE_POSTBACK",
      status: "ISSUED",
      expiresAtMillis: Date.now() + 3600000,
      createdAt: ts
    });
    await db.collection("campaign_conversions").doc("conv_0001").set({
      conversionId: "conv_0001",
      clickId: "clk_unpredictable_0001",
      userId: ALICE_UID,
      campaignId: "camp_001",
      networkId: "net_alpha",
      verificationMode: "AFFILIATE_POSTBACK",
      state: "PENDING",
      rewardTf: 500,
      idempotencyKey: "idem_0001",
      rewardCredited: false,
      createdAt: ts,
      updatedAt: ts
    });
  });
}

test("1. Normal users and unauthenticated users cannot read or write private tracking configs or network configs", async () => {
  await seedTrackingFoundationAndAdminData();

  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv
    .authenticatedContext(ALICE_UID, { email: ALICE_EMAIL, email_verified: true })
    .firestore();

  // Unauthenticated blocked
  await assertFails(unauthDb.collection("campaign_tracking_configs").doc("camp_001").get());
  await assertFails(unauthDb.collection("campaign_tracking_configs").get());
  await assertFails(unauthDb.collection("campaign_networks").doc("net_alpha").get());

  // Normal approved user blocked from reading configs or networks
  await assertFails(aliceDb.collection("campaign_tracking_configs").doc("camp_001").get());
  await assertFails(aliceDb.collection("campaign_tracking_configs").get());
  await assertFails(aliceDb.collection("campaign_networks").doc("net_alpha").get());
  await assertFails(aliceDb.collection("campaign_networks").get());

  // Normal user blocked from creating/modifying tracking configs or networks
  await assertFails(
    aliceDb.collection("campaign_tracking_configs").doc("camp_forged").set({
      configId: "camp_forged",
      campaignId: "camp_forged",
      isActive: true,
      rewardTf: 99999,
      verificationMode: "MANUAL_PROOF"
    })
  );
  await assertFails(
    aliceDb.collection("campaign_tracking_configs").doc("camp_001").update({
      rewardTf: 99999
    })
  );
  await assertFails(
    aliceDb.collection("campaign_networks").doc("net_forged").set({
      networkId: "net_forged",
      name: "Forged Network"
    })
  );
});

test("2. Normal users cannot forge, modify, or read raw click records", async () => {
  await seedTrackingFoundationAndAdminData();

  const aliceDb = testEnv
    .authenticatedContext(ALICE_UID, { email: ALICE_EMAIL, email_verified: true })
    .firestore();

  await assertFails(aliceDb.collection("campaign_clicks").doc("clk_unpredictable_0001").get());
  await assertFails(
    aliceDb.collection("campaign_clicks").where("userId", "==", ALICE_UID).get()
  );
  await assertFails(
    aliceDb.collection("campaign_clicks").doc("clk_forged_1234567890").set({
      clickId: "clk_forged_1234567890",
      userId: ALICE_UID,
      campaignId: "camp_001",
      networkId: "net_alpha",
      status: "ISSUED"
    })
  );
  await assertFails(
    aliceDb.collection("campaign_clicks").doc("clk_unpredictable_0001").update({
      status: "CONVERTED"
    })
  );
  await assertFails(
    aliceDb.collection("campaign_clicks").doc("clk_unpredictable_0001").delete()
  );
});

test("3. Normal users cannot create, read, or modify conversion records", async () => {
  await seedTrackingFoundationAndAdminData();

  const aliceDb = testEnv
    .authenticatedContext(ALICE_UID, { email: ALICE_EMAIL, email_verified: true })
    .firestore();

  await assertFails(aliceDb.collection("campaign_conversions").doc("conv_0001").get());
  await assertFails(
    aliceDb.collection("campaign_conversions").where("userId", "==", ALICE_UID).get()
  );
  await assertFails(
    aliceDb.collection("campaign_conversions").doc("conv_forged").set({
      conversionId: "conv_forged",
      clickId: "clk_unpredictable_0001",
      userId: ALICE_UID,
      campaignId: "camp_001",
      state: "APPROVED",
      rewardTf: 50000
    })
  );
  await assertFails(
    aliceDb.collection("campaign_conversions").doc("conv_0001").update({
      state: "APPROVED",
      rewardCredited: true
    })
  );
  await assertFails(
    aliceDb.collection("campaign_conversions").doc("conv_0001").delete()
  );
});

test("4. Ordinary Admin UI client queries cannot read private tracking configs, postback secrets, clicks, or conversions", async () => {
  await seedTrackingFoundationAndAdminData();

  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, { email: ADMIN_EMAIL, email_verified: true })
    .firestore();

  // Strictly server-only: even admin client queries cannot read private configs or secrets directly
  await assertFails(adminDb.collection("campaign_tracking_configs").doc("camp_001").get());
  await assertFails(adminDb.collection("campaign_tracking_configs").get());
  await assertFails(adminDb.collection("campaign_networks").doc("net_alpha").get());
  await assertFails(adminDb.collection("campaign_networks").get());
  await assertFails(adminDb.collection("campaign_clicks").doc("clk_unpredictable_0001").get());
  await assertFails(adminDb.collection("campaign_conversions").doc("conv_0001").get());

  // Nested private subcollections are also strictly denied
  await assertFails(
    adminDb.collection("campaign_networks").doc("net_alpha").collection("secrets").doc("hmac").get()
  );
});

test("5. Public campaigns collection blocks secret fields while preserving normal admin campaign management", async () => {
  await seedTrackingFoundationAndAdminData();
  const ts = new Date(Date.now() - 1000);

  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, { email: ADMIN_EMAIL, email_verified: true })
    .firestore();
  const aliceDb = testEnv
    .authenticatedContext(ALICE_UID, { email: ALICE_EMAIL, email_verified: true })
    .firestore();

  // Normal campaign creation still works as before
  await assertSucceeds(
    adminDb.collection("campaigns").doc("camp_normal").set({
      title: "Partner App Campaign",
      clientName: "Partner Co",
      description: "Install and verify",
      budgetCents: 50000,
      spentCents: 0,
      targetSubmissions: 100,
      completedSubmissions: 0,
      status: "ACTIVE",
      category: "App Install",
      rewardPerTaskCents: 500,
      createdAt: ts,
      updatedAt: ts
    })
  );

  // Authenticated user can still read public campaigns metadata
  await assertSucceeds(aliceDb.collection("campaigns").doc("camp_normal").get());

  // Writing private postback secrets onto public /campaigns document is rejected
  await assertFails(
    adminDb.collection("campaigns").doc("camp_secret_leak").set({
      title: "Leaky Campaign",
      clientName: "Partner Co",
      postbackSecret: "leaked-hmac-secret",
      status: "ACTIVE"
    })
  );
  await assertFails(
    adminDb.collection("campaigns").doc("camp_normal").update({
      hmacSecret: "leaked-hmac-secret"
    })
  );
});

test("6. Phase 3B campaign_click_guards collection is strictly server-only (denied to unauth, users, and client admins)", async () => {
  await seedTrackingFoundationAndAdminData();
  const ts = new Date(Date.now() - 1000);

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("campaign_click_guards").doc(`${ALICE_UID}__camp_001`).set({
      userId: ALICE_UID,
      campaignId: "camp_001",
      activeClickId: "clk_unpredictable_0001",
      windowClickCount: 1,
      updatedAt: ts
    });
  });

  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv
    .authenticatedContext(ALICE_UID, { email: ALICE_EMAIL, email_verified: true })
    .firestore();
  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, { email: ADMIN_EMAIL, email_verified: true })
    .firestore();

  await assertFails(
    unauthDb.collection("campaign_click_guards").doc(`${ALICE_UID}__camp_001`).get()
  );
  await assertFails(
    aliceDb.collection("campaign_click_guards").doc(`${ALICE_UID}__camp_001`).get()
  );
  await assertFails(
    aliceDb.collection("campaign_click_guards").doc(`${ALICE_UID}__camp_001`).set({
      userId: ALICE_UID,
      campaignId: "camp_001",
      windowClickCount: 0
    })
  );
  await assertFails(
    adminDb.collection("campaign_click_guards").doc(`${ALICE_UID}__camp_001`).get()
  );
  await assertFails(
    adminDb.collection("campaign_click_guards").doc(`${ALICE_UID}__camp_001`).delete()
  );

  // Active reservations subcollection under campaign_tracking_configs is also strictly server-only
  await assertFails(
    unauthDb
      .collection("campaign_tracking_configs")
      .doc("camp_001")
      .collection("active_reservations")
      .doc(ALICE_UID)
      .get()
  );
  await assertFails(
    aliceDb
      .collection("campaign_tracking_configs")
      .doc("camp_001")
      .collection("active_reservations")
      .doc(ALICE_UID)
      .set({ userId: ALICE_UID, clickId: "clk_forged", expiresAtMillis: Date.now() + 10000 })
  );
  await assertFails(
    adminDb
      .collection("campaign_tracking_configs")
      .doc("camp_001")
      .collection("active_reservations")
      .doc(ALICE_UID)
      .get()
  );
});

test("7. Phase 3C campaign_conversion_locks collection is strictly server-only (denied to unauth, users, and client admins)", async () => {
  await seedTrackingFoundationAndAdminData();
  const ts = new Date(Date.now() - 1000);
  const lockId = "net_alpha__deadbeef1234";

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("campaign_conversion_locks").doc(lockId).set({
      externalTransactionId: "ext_txn_001",
      networkId: "net_alpha",
      conversionId: "conv_clk_unpredictable_0001",
      clickId: "clk_unpredictable_0001",
      campaignId: "camp_001",
      userId: ALICE_UID,
      createdAt: ts
    });
  });

  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv
    .authenticatedContext(ALICE_UID, { email: ALICE_EMAIL, email_verified: true })
    .firestore();
  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, { email: ADMIN_EMAIL, email_verified: true })
    .firestore();

  await assertFails(unauthDb.collection("campaign_conversion_locks").doc(lockId).get());
  await assertFails(unauthDb.collection("campaign_conversion_locks").get());

  await assertFails(aliceDb.collection("campaign_conversion_locks").doc(lockId).get());
  await assertFails(aliceDb.collection("campaign_conversion_locks").get());
  await assertFails(
    aliceDb.collection("campaign_conversion_locks").doc("forged_lock").set({
      externalTransactionId: "ext_forged",
      networkId: "net_alpha"
    })
  );

  await assertFails(adminDb.collection("campaign_conversion_locks").doc(lockId).get());
  await assertFails(adminDb.collection("campaign_conversion_locks").get());
  await assertFails(adminDb.collection("campaign_conversion_locks").doc(lockId).delete());
  await assertFails(
    adminDb.collection("campaign_conversion_locks").doc(lockId).collection("meta").doc("x").get()
  );
});


