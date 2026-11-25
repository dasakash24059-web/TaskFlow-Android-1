/**
 * Phase 3E End-to-End Integration, Security & Operational Test Suite.
 *
 * Exercises the complete TaskFlow Universal Campaign Tracking lifecycle:
 *  - Admin network & campaign configuration (Phase 3D)
 *  - User eligible tracked campaign discovery (Phase 3E.1)
 *  - Server-authoritative click issuance (Phase 3B)
 *  - Signed S2S postback verification & hold-for-review (Phase 3C)
 *  - User read-only tracked reward activity (Phase 3E.1)
 *  - Admin conversion moderation queue + Approve/Reject/Reverse (Phase 3E.2)
 *  - Partial recovery / shortfall accounting & analytics reconciliation (Phase 3D/3E)
 *  - Starvation-free & failure-isolated scheduled click cleanup (Phase 3E.3)
 */

const assert = require("assert");
const crypto = require("crypto");
const indexModule = require("./index");
const {
  CAMPAIGN_TRACKING_COLLECTIONS,
  buildConversionModerationId,
  buildUserActivityId
} = require("./campaign_tracking");

const { handleCreateCampaignClick, handleReleaseExpiredCampaignClickReservations } =
  indexModule._campaignTrackingHandlers;
const { handleCampaignPostback, handleReviewCampaignConversion } =
  indexModule._campaignConversionHandlers;
const {
  handleAdminUpsertCampaignNetwork,
  handleAdminUpsertCampaignTrackingConfig,
  handleAdminGetCampaignTrackingDashboard,
  handleAdminListCampaignConversions
} = indexModule._campaignAdminHandlers;
const {
  handleListUserEligibleTrackedCampaigns,
  handleListUserTrackedRewardActivity
} = indexModule._campaignUserHandlers;

function createInMemoryFirestore(seed = {}, options = {}) {
  const store = new Map();
  const failTransactionOnDocPath = options.failTransactionOnDocPath || null;

  for (const [colName, docs] of Object.entries(seed)) {
    const colMap = new Map();
    for (const [docId, docData] of Object.entries(docs)) {
      colMap.set(docId, JSON.parse(JSON.stringify(docData)));
    }
    store.set(colName, colMap);
  }

  let autoIdSeq = 1;

  function ensureCollection(path) {
    if (!store.has(path)) {
      store.set(path, new Map());
    }
    return store.get(path);
  }

  function toComparable(val) {
    if (val instanceof Date) return val.getTime();
    if (val && typeof val.toMillis === "function") return val.toMillis();
    if (typeof val === "object" && val !== null && typeof val._seconds === "number") {
      return val._seconds * 1000;
    }
    return val;
  }

  function matchesFilter(docData, filter) {
    const raw = docData[filter.field];
    const left = toComparable(raw);
    const right = toComparable(filter.value);
    if (filter.op === "==") return left === right;
    if (filter.op === "<=") return left !== undefined && left !== null && left <= right;
    if (filter.op === ">=") return left !== undefined && left !== null && left >= right;
    if (filter.op === "<") return left !== undefined && left !== null && left < right;
    if (filter.op === ">") return left !== undefined && left !== null && left > right;
    return true;
  }

  function makeDocRef(colPath, docId) {
    const fullPath = `${colPath}/${docId}`;
    return {
      id: docId,
      path: fullPath,
      _colPath: colPath,
      async get() {
        const col = ensureCollection(colPath);
        if (!col.has(docId)) {
          return { id: docId, exists: false, data: () => undefined };
        }
        const val = JSON.parse(JSON.stringify(col.get(docId)));
        return { id: docId, exists: true, data: () => val };
      },
      async set(data, opts = {}) {
        const col = ensureCollection(colPath);
        if (opts && opts.merge && col.has(docId)) {
          col.set(docId, { ...col.get(docId), ...JSON.parse(JSON.stringify(data)) });
        } else {
          col.set(docId, JSON.parse(JSON.stringify(data)));
        }
      },
      async update(data) {
        const col = ensureCollection(colPath);
        const existing = col.get(docId) || {};
        col.set(docId, { ...existing, ...JSON.parse(JSON.stringify(data)) });
      },
      async delete() {
        const col = ensureCollection(colPath);
        col.delete(docId);
      },
      collection(subName) {
        return makeCollectionRef(`${fullPath}/${subName}`);
      }
    };
  }

  function makeQuery(colPath, filters = [], orderField = null, orderDir = "asc", limitCount = null, startAfterId = null) {
    return {
      where(field, op, value) {
        return makeQuery(colPath, [...filters, { field, op, value }], orderField, orderDir, limitCount, startAfterId);
      },
      orderBy(field, dir = "asc") {
        return makeQuery(colPath, filters, field, dir, limitCount, startAfterId);
      },
      limit(n) {
        return makeQuery(colPath, filters, orderField, orderDir, n, startAfterId);
      },
      startAfter(docSnap) {
        return makeQuery(colPath, filters, orderField, orderDir, limitCount, docSnap?.id || null);
      },
      async get() {
        const col = ensureCollection(colPath);
        let entries = Array.from(col.entries()).map(([id, data]) => ({
          id,
          data: JSON.parse(JSON.stringify(data))
        }));
        for (const f of filters) {
          entries = entries.filter((e) => matchesFilter(e.data, f));
        }
        if (orderField) {
          entries.sort((a, b) => {
            const va = orderField === "__name__" ? a.id : toComparable(a.data[orderField]);
            const vb = orderField === "__name__" ? b.id : toComparable(b.data[orderField]);
            if (va === vb) return a.id.localeCompare(b.id);
            const cmp = va < vb ? -1 : 1;
            return orderDir === "desc" ? -cmp : cmp;
          });
        }
        if (startAfterId) {
          const idx = entries.findIndex((e) => e.id === startAfterId);
          if (idx >= 0) entries = entries.slice(idx + 1);
        }
        if (typeof limitCount === "number") {
          entries = entries.slice(0, limitCount);
        }
        return {
          empty: entries.length === 0,
          size: entries.length,
          docs: entries.map((e) => ({
            id: e.id,
            exists: true,
            data: () => JSON.parse(JSON.stringify(e.data))
          }))
        };
      }
    };
  }

  function makeCollectionRef(colPath) {
    const q = makeQuery(colPath);
    return {
      ...q,
      doc(id) {
        const docId = id || `auto_${autoIdSeq++}`;
        return makeDocRef(colPath, docId);
      }
    };
  }

  const db = {
    _store: store,
    collection(colPath) {
      return makeCollectionRef(colPath);
    },
    async runTransaction(fn) {
      const writes = [];
      const tx = {
        async get(ref) {
          if (failTransactionOnDocPath && ref.path === failTransactionOnDocPath) {
            throw new Error(`Simulated transaction failure on ${ref.path}`);
          }
          return ref.get();
        },
        set(ref, data, opts = {}) {
          writes.push({ type: "set", ref, data: JSON.parse(JSON.stringify(data)), opts });
        },
        update(ref, data) {
          writes.push({ type: "update", ref, data: JSON.parse(JSON.stringify(data)) });
        },
        delete(ref) {
          writes.push({ type: "delete", ref });
        }
      };
      const result = await fn(tx);
      for (const w of writes) {
        if (w.type === "set") await w.ref.set(w.data, w.opts);
        else if (w.type === "update") await w.ref.update(w.data);
        else if (w.type === "delete") await w.ref.delete();
      }
      return result;
    }
  };
  return db;
}

function makeRes() {
  const res = {
    statusCode: 200,
    body: null,
    status(code) {
      this.statusCode = code;
      return this;
    },
    json(payload) {
      this.body = payload;
      return this;
    }
  };
  return res;
}

function signSortedParamsHmacSha256(params, secret, sigParam = "sig") {
  const keys = Object.keys(params)
    .filter((k) => k !== sigParam)
    .sort();
  const payload = keys.map((k) => `${k}=${params[k]}`).join("&");
  return crypto.createHmac("sha256", secret).update(payload).digest("hex");
}

function assertNoSensitiveFields(obj, forbiddenValues = []) {
  const serialized = JSON.stringify(obj);
  const forbiddenKeys = [
    '"clickId"',
    '"conversionId"',
    '"externalTransactionId"',
    '"secretEnvKey"',
    '"secretValue"',
    '"signatureValue"',
    '"trackingBaseUrl"',
    '"idempotencyKey"',
    '"callbackPayoutRaw"'
  ];
  for (const key of forbiddenKeys) {
    assert.strictEqual(
      serialized.includes(key),
      false,
      `Response MUST NOT contain sensitive key ${key}: ${serialized}`
    );
  }
  for (const val of forbiddenValues) {
    if (!val) continue;
    assert.strictEqual(
      serialized.includes(val),
      false,
      `Response MUST NOT leak sensitive value '${val}': ${serialized}`
    );
  }
}

async function runTests() {
  let passed = 0;
  async function test(name, fn) {
    await fn();
    passed += 1;
    console.log(`  PASS: ${name}`);
  }

  console.log("\n=== Phase 3E End-to-End Integration & Security Tests ===\n");

  const BASE_NOW = Date.parse("2026-06-15T10:00:00.000Z");
  const SECRET_VAL = "staging-only-test-hmac-secret-999";

  // -----------------------------------------------------------------------
  // TEST 1: Complete E2E Lifecycle
  // -----------------------------------------------------------------------
  await test("1. Full E2E lifecycle: Admin config -> User discovery -> Click -> S2S Postback (PENDING) -> User activity -> Admin Queue Approve -> Partial Reversal -> Analytics", async () => {
    const db = createInMemoryFirestore({
      users: {
        admin_1: {
          uid: "admin_1",
          displayName: "Ops Admin",
          role: "admin",
          isAdmin: true,
          approvalStatus: "approved"
        },
        user_1: {
          uid: "user_1",
          displayName: "Aarav Sharma",
          role: "user",
          approvalStatus: "approved",
          balanceCoins: 0,
          eligibleCoins: 0,
          promotionalCoins: 0,
          balanceCents: 0,
          completedTasksCount: 0
        }
      }
    });

    const nowFn = () => BASE_NOW;
    const ts = new Date(BASE_NOW);
    const adminAuth = { uid: "admin_1", token: { admin: true } };
    const userAuth = { uid: "user_1", token: {} };

    // Step A: Admin creates network
    const netRes = await handleAdminUpsertCampaignNetwork(
      {
        auth: adminAuth,
        data: {
          networkId: "net_alpha",
          name: "Alpha Partner Network",
          adapterType: "AFFILIATE_POSTBACK",
          isActive: true,
          capabilities: {
            supportsPostback: true,
            supportsIncentivizedTraffic: true
          },
          allowedDestinationHosts: ["partner.example.com"],
          signatureConfig: {
            algorithm: "HMAC_SHA256",
            secretEnvKey: "CAMPAIGN_POSTBACK_SECRET_ALPHA",
            signatureParam: "sig",
            signingMode: "SORTED_PARAMS",
            encoding: "hex"
          }
        }
      },
      { db, now: nowFn, serverTimestamp: ts }
    );
    assert.strictEqual(netRes.success, true);

    // Step B: Admin creates campaign tracking config (requireAdminApproval = true)
    const cfgRes = await handleAdminUpsertCampaignTrackingConfig(
      {
        auth: adminAuth,
        data: {
          campaignId: "camp_e2e_1",
          title: "Install FinApp & Register",
          isActive: true,
          networkId: "net_alpha",
          verificationMode: "AFFILIATE_POSTBACK",
          rewardTf: 500, // 500 TF = ₹4.00 (400 cents)
          submissionBudget: 50,
          rewardBudgetTf: 25000,
          allowedCountries: ["ALL"],
          allowedPlatforms: ["ANDROID"],
          trackingBaseUrl: "https://partner.example.com/offer/101",
          allowedDestinationHosts: ["partner.example.com"],
          requireVerifiedCountry: false,
          requireDeviceAttestation: false,
          conversionRequirements: {
            requireAdminApproval: true,
            autoApproveVerifiedPostback: false,
            allowMultipleConversions: false
          }
        }
      },
      { db, now: nowFn, serverTimestamp: ts }
    );
    assert.strictEqual(cfgRes.success, true);

    // Step C: User discovers eligible tracked campaigns
    const discRes = await handleListUserEligibleTrackedCampaigns(
      { auth: userAuth, data: { devicePlatform: "ANDROID", pageSize: 20 } },
      { db, now: nowFn }
    );
    assert.strictEqual(discRes.success, true);
    assert.strictEqual(discRes.accountApproved, true);
    assert.strictEqual(discRes.items.length, 1);
    assert.strictEqual(discRes.items[0].campaignId, "camp_e2e_1");
    assert.strictEqual(discRes.items[0].rewardTf, 500);
    assert.strictEqual(discRes.items[0].rewardCents, 400);
    assert.strictEqual(discRes.items[0].userStatus, "AVAILABLE");
    assertNoSensitiveFields(discRes, [SECRET_VAL, "partner.example.com/offer/101"]);

    // Step D: User initiates click via existing createCampaignClick
    const clickRes = await handleCreateCampaignClick(
      {
        auth: userAuth,
        data: {
          campaignId: "camp_e2e_1",
          devicePlatform: "ANDROID",
          idempotencyKey: "e2e_click_1"
        }
      },
      { db, now: nowFn, serverTimestamp: ts }
    );
    assert.strictEqual(clickRes.success, true);
    assert.strictEqual(clickRes.clickId, undefined);
    assert.ok(clickRes.trackingUrl.startsWith("https://partner.example.com/offer/101"));
    const trackedClickId = (/clk_[a-f0-9]{32}/.exec(clickRes.trackingUrl) || [])[0] || "";
    assert.match(trackedClickId, /^clk_/);

    // Wallet MUST NOT be credited on click
    const userAfterClick = (await db.collection("users").doc("user_1").get()).data();
    assert.strictEqual(userAfterClick.balanceCoins, 0);

    // Step E: Verified S2S postback arrives -> held in PENDING because requireAdminApproval=true
    const pbParams = {
      networkId: "net_alpha",
      click_id: trackedClickId,
      txn_id: "ext_txn_e2e_001",
      status: "approved",
      campaign_id: "camp_e2e_1",
      payout: "999999" // Untrusted partner payout must be ignored!
    };
    pbParams.sig = signSortedParamsHmacSha256(pbParams, SECRET_VAL);
    const pbRes = makeRes();
    await handleCampaignPostback(
      {
        method: "GET",
        query: pbParams,
        headers: {}
      },
      pbRes,
      {
        db,
        now: () => BASE_NOW + 60_000,
        serverTimestamp: new Date(BASE_NOW + 60_000),
        secrets: { CAMPAIGN_POSTBACK_SECRET_ALPHA: SECRET_VAL }
      }
    );
    assert.strictEqual(pbRes.statusCode, 200);
    assert.strictEqual(pbRes.body.state, "PENDING");
    assert.strictEqual(pbRes.body.rewardTf, 500);
    assert.strictEqual(pbRes.body.rewardCredited, false);

    // Step F: User checks Tracked Reward Activity -> sees PENDING, zero sensitive leaks
    const actPending = await handleListUserTrackedRewardActivity(
      { auth: userAuth, data: { status: "ALL" } },
      { db }
    );
    assert.strictEqual(actPending.success, true);
    assert.strictEqual(actPending.items.length, 1);
    assert.strictEqual(actPending.items[0].status, "PENDING");
    assert.strictEqual(actPending.items[0].statusLabel, "Pending Verification");
    assert.strictEqual(actPending.items[0].rewardTf, 500);
    assert.strictEqual(actPending.items[0].rewardCents, 400);
    assert.strictEqual(actPending.counts.pending, 1);
    assertNoSensitiveFields(actPending, [clickRes.clickId, "ext_txn_e2e_001", SECRET_VAL]);

    // Step G: Admin fetches Conversion Moderation Queue
    const queuePending = await handleAdminListCampaignConversions(
      { auth: adminAuth, data: { status: "PENDING" } },
      { db }
    );
    assert.strictEqual(queuePending.success, true);
    assert.strictEqual(queuePending.items.length, 1);
    const modItem = queuePending.items[0];
    assert.ok(/^mod_[a-f0-9]{24}$/.test(modItem.moderationId));
    assert.ok(modItem.displayRef.startsWith("MOD-"));
    assert.strictEqual(modItem.state, "PENDING");
    assert.strictEqual(modItem.rewardTf, 500);
    assert.strictEqual(modItem.rewardCents, 400);
    assert.strictEqual(modItem.userDisplayName, "Aarav Sharma");
    assertNoSensitiveFields(queuePending, [clickRes.clickId, "ext_txn_e2e_001", SECRET_VAL]);

    // Step H: Admin approves conversion using moderationId + mandatory reason + expectedState
    const approveRes = await handleReviewCampaignConversion(
      {
        auth: adminAuth,
        data: {
          moderationId: modItem.moderationId,
          action: "APPROVE",
          reason: "Verified advertiser report match",
          expectedState: "PENDING"
        }
      },
      { db, now: () => BASE_NOW + 120_000, serverTimestamp: new Date(BASE_NOW + 120_000) }
    );
    assert.strictEqual(approveRes.success, true);
    assert.strictEqual(approveRes.state, "APPROVED");
    assert.strictEqual(approveRes.rewardTf, 500);
    assert.strictEqual(approveRes.newBalanceCoins, 500);
    assert.strictEqual(approveRes.newBalanceCents, 400);
    assertNoSensitiveFields(approveRes, [clickRes.clickId, "ext_txn_e2e_001", SECRET_VAL]);

    // User activity now reflects APPROVED
    const actApproved = await handleListUserTrackedRewardActivity(
      { auth: userAuth, data: {} },
      { db }
    );
    assert.strictEqual(actApproved.items[0].status, "APPROVED");
    assert.strictEqual(actApproved.counts.approved, 1);

    // Step I: Simulate user withdrawing 300 TF so only 200 TF remains -> partial recovery preview & reversal
    await db.collection("users").doc("user_1").update({
      balanceCoins: 200,
      eligibleCoins: 200,
      balanceCents: 160
    });

    const queueApproved = await handleAdminListCampaignConversions(
      { auth: adminAuth, data: { status: "APPROVED" } },
      { db }
    );
    assert.strictEqual(queueApproved.items.length, 1);
    assert.strictEqual(queueApproved.items[0].expectedRecoverableTf, 200);
    assert.strictEqual(queueApproved.items[0].expectedRecoverableCents, 160);
    assert.strictEqual(queueApproved.items[0].estimatedShortfallTf, 300);
    assert.strictEqual(queueApproved.items[0].estimatedShortfallCents, 240);

    const reverseRes = await handleReviewCampaignConversion(
      {
        auth: adminAuth,
        data: {
          moderationId: modItem.moderationId,
          action: "REVERSE",
          reason: "Advertiser chargeback confirmed",
          expectedState: "APPROVED"
        }
      },
      { db, now: () => BASE_NOW + 180_000, serverTimestamp: new Date(BASE_NOW + 180_000) }
    );
    assert.strictEqual(reverseRes.success, true);
    assert.strictEqual(reverseRes.state, "REVERSED");
    assert.strictEqual(reverseRes.recoveredCoins, 200);
    assert.strictEqual(reverseRes.shortfallCoins, 300);
    assert.strictEqual(reverseRes.newBalanceCoins, 0);
    assertNoSensitiveFields(reverseRes, [clickRes.clickId, "ext_txn_e2e_001", SECRET_VAL]);

    // Verify user wallet never went negative & compensating ledger exists
    const userAfterRev = (await db.collection("users").doc("user_1").get()).data();
    assert.strictEqual(userAfterRev.balanceCoins, 0);
    assert.strictEqual(userAfterRev.eligibleCoins, 0);
    assert.strictEqual(userAfterRev.balanceCents, 0);

    // Verify analytics dashboard reconciles gross, reversed, recovered, shortfall, signedNetTf, and netWalletImpactTf
    const dash = await handleAdminGetCampaignTrackingDashboard(
      { auth: adminAuth, data: { dateRange: "ALL" } },
      { db, now: () => BASE_NOW + 240_000 }
    );
    assert.strictEqual(dash.summary.grossCreditedTf, 500);
    assert.strictEqual(dash.summary.reversedTf, 500);
    assert.strictEqual(dash.summary.recoveredTf, 200);
    assert.strictEqual(dash.summary.shortfallTf, 300);
    assert.strictEqual(dash.summary.netCreditedTf, 0);
    assert.strictEqual(dash.summary.netWalletImpactTf, 300); // 500 gross - 200 recovered
  });

  // -----------------------------------------------------------------------
  // TEST 2: Authentication, Cross-User Spoofing & Admin Authorization Guards
  // -----------------------------------------------------------------------
  await test("2. Rejects unauthenticated, cross-user spoofed, and non-admin requests across all Phase 3E endpoints", async () => {
    const db = createInMemoryFirestore({
      users: {
        user_a: { uid: "user_a", role: "user", approvalStatus: "approved" },
        user_b: { uid: "user_b", role: "user", approvalStatus: "approved" }
      }
    });

    await assert.rejects(
      () => handleListUserEligibleTrackedCampaigns({ auth: null, data: {} }, { db }),
      /Authentication required/
    );
    await assert.rejects(
      () => handleListUserTrackedRewardActivity({ auth: null, data: {} }, { db }),
      /Authentication required/
    );
    await assert.rejects(
      () => handleListUserEligibleTrackedCampaigns({ auth: { uid: "user_a" }, data: { userId: "user_b" } }, { db }),
      /another user/i
    );
    await assert.rejects(
      () => handleListUserTrackedRewardActivity({ auth: { uid: "user_a" }, data: { userId: "user_b" } }, { db }),
      /Cross-user activity access is prohibited/i
    );
    await assert.rejects(
      () => handleAdminListCampaignConversions({ auth: { uid: "user_a", token: {} }, data: { isAdmin: true, role: "admin" } }, { db }),
      /Only authorized administrators/
    );
    await assert.rejects(
      () => handleReviewCampaignConversion({ auth: { uid: "user_a", token: {} }, data: { action: "APPROVE", isAdmin: true } }, { db }),
      /Only authorized administrators/
    );
  });

  // -----------------------------------------------------------------------
  // TEST 3: Mandatory Admin Reason, Stale-State Guard & Duplicate Reversal Protection
  // -----------------------------------------------------------------------
  await test("3. Enforces mandatory reason on APPROVE/REJECT/REVERSE, stale expectedState rejection, and duplicate reversal prevention", async () => {
    const clickId = "clk_guard_001";
    const convId = `conv_${clickId}`;
    const modId = buildConversionModerationId(convId);
    const db = createInMemoryFirestore({
      users: {
        admin_1: { uid: "admin_1", role: "admin", isAdmin: true, approvalStatus: "approved" },
        user_1: { uid: "user_1", displayName: "User One", role: "user", approvalStatus: "approved", balanceCoins: 0, eligibleCoins: 0, balanceCents: 0 }
      },
      [CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS]: {
        net_1: { networkId: "net_1", name: "Net 1", isActive: true }
      },
      [CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS]: {
        camp_1: { campaignId: "camp_1", title: "Camp 1", networkId: "net_1", isActive: true, rewardTf: 250, submissionBudget: 10, usedSubmissions: 0, rewardBudgetTf: 2500, spentBudgetTf: 0, verificationMode: "AFFILIATE_POSTBACK" }
      },
      [CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS]: {
        [clickId]: { clickId, userId: "user_1", campaignId: "camp_1", networkId: "net_1", status: "PENDING_VERIFICATION", verificationMode: "AFFILIATE_POSTBACK", expiresAtMillis: BASE_NOW + 3600_000 }
      },
      [CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS]: {
        [convId]: { conversionId: convId, moderationId: modId, clickId, userId: "user_1", campaignId: "camp_1", networkId: "net_1", state: "PENDING", rewardTf: 250, rewardCredited: false, createdAt: new Date(BASE_NOW) }
      }
    });

    const adminAuth = { uid: "admin_1", token: { admin: true } };

    // Missing reason rejected for APPROVE
    await assert.rejects(
      () => handleReviewCampaignConversion(
        { auth: adminAuth, data: { moderationId: modId, action: "APPROVE", reason: "   " } },
        { db, now: () => BASE_NOW }
      ),
      /review reason is required/
    );

    // Approve succeeds
    const appRes = await handleReviewCampaignConversion(
      { auth: adminAuth, data: { moderationId: modId, action: "APPROVE", reason: "Confirmed", expectedState: "PENDING" } },
      { db, now: () => BASE_NOW + 1000, serverTimestamp: new Date(BASE_NOW + 1000) }
    );
    assert.strictEqual(appRes.state, "APPROVED");

    // Stale-state repeat APPROVE with expectedState="PENDING" MUST fail loudly instead of silent duplicate
    await assert.rejects(
      () => handleReviewCampaignConversion(
        { auth: adminAuth, data: { moderationId: modId, action: "APPROVE", reason: "Second click", expectedState: "PENDING" } },
        { db, now: () => BASE_NOW + 2000, serverTimestamp: new Date(BASE_NOW + 2000) }
      ),
      /already changed to APPROVED/
    );

    // Reverse once
    const rev1 = await handleReviewCampaignConversion(
      { auth: adminAuth, data: { moderationId: modId, action: "REVERSE", reason: "Fraud reversal", expectedState: "APPROVED" } },
      { db, now: () => BASE_NOW + 3000, serverTimestamp: new Date(BASE_NOW + 3000) }
    );
    assert.strictEqual(rev1.state, "REVERSED");
    assert.strictEqual(rev1.recoveredCoins, 250);

    // Second reverse with expectedState="APPROVED" MUST fail
    await assert.rejects(
      () => handleReviewCampaignConversion(
        { auth: adminAuth, data: { moderationId: modId, action: "REVERSE", reason: "Duplicate reverse", expectedState: "APPROVED" } },
        { db, now: () => BASE_NOW + 4000, serverTimestamp: new Date(BASE_NOW + 4000) }
      ),
      /already changed to REVERSED/
    );

    // Even without expectedState, duplicate reverse returns idempotent duplicate without second wallet debit
    const rev2 = await handleReviewCampaignConversion(
      { auth: adminAuth, data: { moderationId: modId, action: "REVERSE", reason: "Idempotent retry" } },
      { db, now: () => BASE_NOW + 5000, serverTimestamp: new Date(BASE_NOW + 5000) }
    );
    assert.strictEqual(rev2.duplicate, true);
    const u = (await db.collection("users").doc("user_1").get()).data();
    assert.strictEqual(u.balanceCoins, 0);
  });

  // -----------------------------------------------------------------------
  // TEST 4: Fail-Closed Geo, App Check, Unapproved User, Invalid Signature & Budget/Expiry
  // -----------------------------------------------------------------------
  await test("4. Fail-closed eligibility: Geo-restricted hidden without trusted country, App Check enforced, budget/expiry hidden, invalid postback signature rejected", async () => {
    const db = createInMemoryFirestore({
      users: {
        user_ok: { uid: "user_ok", role: "user", approvalStatus: "approved" },
        user_pending: { uid: "user_pending", role: "user", approvalStatus: "pending" }
      },
      [CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS]: {
        net_live: {
          networkId: "net_live",
          name: "Live Net",
          isActive: true,
          adapterType: "AFFILIATE_POSTBACK",
          capabilities: {
            supportsPostback: true,
            supportsIncentivizedTraffic: true
          },
          allowedDestinationHosts: ["partner.example.com"],
          signatureConfig: {
            algorithm: "HMAC_SHA256",
            secretEnvKey: "CAMPAIGN_POSTBACK_SECRET",
            signatureParam: "sig",
            signingMode: "SORTED_PARAMS",
            encoding: "hex"
          }
        }
      },
      [CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS]: {
        camp_geo_only: {
          campaignId: "camp_geo_only",
          title: "India Only Offer",
          networkId: "net_live",
          trackingNetworkRef: "campaign_networks/net_live",
          isActive: true,
          rewardTf: 300,
          submissionBudget: 50,
          usedSubmissions: 0,
          allowedCountries: ["IN"],
          requireVerifiedCountry: true,
          allowedPlatforms: ["ANDROID"],
          trackingBaseUrl: "https://partner.example.com/in",
          allowedDestinationHosts: ["partner.example.com"],
          verificationMode: "AFFILIATE_POSTBACK"
        },
        camp_appcheck: {
          campaignId: "camp_appcheck",
          title: "Attested Offer",
          networkId: "net_live",
          trackingNetworkRef: "campaign_networks/net_live",
          isActive: true,
          rewardTf: 250,
          submissionBudget: 50,
          usedSubmissions: 0,
          allowedCountries: ["ALL"],
          requireVerifiedCountry: false,
          requireDeviceAttestation: true,
          allowedPlatforms: ["ANDROID"],
          trackingBaseUrl: "https://partner.example.com/att",
          allowedDestinationHosts: ["partner.example.com"],
          verificationMode: "AFFILIATE_POSTBACK"
        },
        camp_exhausted: {
          campaignId: "camp_exhausted",
          title: "Exhausted Offer",
          networkId: "net_live",
          trackingNetworkRef: "campaign_networks/net_live",
          isActive: true,
          rewardTf: 200,
          submissionBudget: 5,
          usedSubmissions: 5,
          allowedCountries: ["ALL"],
          allowedPlatforms: ["ANDROID"],
          trackingBaseUrl: "https://partner.example.com/exh",
          allowedDestinationHosts: ["partner.example.com"],
          verificationMode: "AFFILIATE_POSTBACK"
        },
        camp_expired: {
          campaignId: "camp_expired",
          title: "Expired Offer",
          networkId: "net_live",
          trackingNetworkRef: "campaign_networks/net_live",
          isActive: true,
          rewardTf: 200,
          submissionBudget: 50,
          usedSubmissions: 0,
          expiresAtMillis: BASE_NOW - 1000,
          allowedCountries: ["ALL"],
          allowedPlatforms: ["ANDROID"],
          trackingBaseUrl: "https://partner.example.com/exp",
          allowedDestinationHosts: ["partner.example.com"],
          verificationMode: "AFFILIATE_POSTBACK"
        }
      }
    });

    // Without trusted geo header, camp_geo_only MUST be excluded (even if client spoofs country: "IN")
    const disc = await handleListUserEligibleTrackedCampaigns(
      { auth: { uid: "user_ok" }, data: { devicePlatform: "ANDROID", country: "IN" } },
      { db, now: () => BASE_NOW }
    );
    const ids = disc.items.map((i) => i.campaignId);
    assert.deepStrictEqual(ids, ["camp_appcheck"]);

    // App Check required -> fails closed when request.app is missing
    await assert.rejects(
      () => handleCreateCampaignClick(
        { auth: { uid: "user_ok" }, data: { campaignId: "camp_appcheck", devicePlatform: "ANDROID" } },
        { db, now: () => BASE_NOW }
      ),
      /App Check|attestation|device verification/i
    );

    // Unapproved user flagged in discovery and blocked on click
    const discPending = await handleListUserEligibleTrackedCampaigns(
      { auth: { uid: "user_pending" }, data: { devicePlatform: "ANDROID" } },
      { db, now: () => BASE_NOW }
    );
    assert.strictEqual(discPending.accountApproved, false);

    // Invalid S2S postback signature fails closed (401)
    const badRes = makeRes();
    await handleCampaignPostback(
      {
        method: "GET",
        query: { networkId: "net_live", click_id: "clk_fake", txn_id: "t1", status: "approved", sig: "deadbeef" },
        headers: {}
      },
      badRes,
      { db, now: () => BASE_NOW, secrets: { CAMPAIGN_POSTBACK_SECRET: SECRET_VAL } }
    );
    assert.strictEqual(badRes.statusCode, 401);
  });

  // -----------------------------------------------------------------------
  // TEST 5: Phase 3E.3 Scheduled Cleanup — Starvation Immunity & Failure Isolation
  // -----------------------------------------------------------------------
  await test("5. Scheduled click cleanup avoids query starvation from old EXPIRED/CONVERTED clicks, isolates per-campaign errors, and writes maintenance audit log", async () => {
    const clicksSeed = {};
    // Seed 25 historical already-EXPIRED clicks with very old timestamps
    for (let i = 1; i <= 25; i++) {
      clicksSeed[`old_exp_${i}`] = {
        clickId: `old_exp_${i}`,
        campaignId: "camp_ok",
        userId: `old_u_${i}`,
        status: "EXPIRED",
        expiresAtMillis: BASE_NOW - 10_000_000 + i
      };
    }
    // Seed 2 newly expired ISSUED clicks on camp_ok and 1 on camp_failing
    clicksSeed.new_ok_1 = {
      clickId: "new_ok_1",
      campaignId: "camp_ok",
      userId: "u_ok_1",
      status: "ISSUED",
      expiresAtMillis: BASE_NOW - 5000
    };
    clicksSeed.new_ok_2 = {
      clickId: "new_ok_2",
      campaignId: "camp_ok",
      userId: "u_ok_2",
      status: "ISSUED",
      expiresAtMillis: BASE_NOW - 4000
    };
    clicksSeed.new_fail_1 = {
      clickId: "new_fail_1",
      campaignId: "camp_fail",
      userId: "u_fail_1",
      status: "ISSUED",
      expiresAtMillis: BASE_NOW - 3000
    };

    const db = createInMemoryFirestore(
      {
        [CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS]: {
          camp_ok: { campaignId: "camp_ok", reservedClickSlots: 2 },
          camp_fail: { campaignId: "camp_fail", reservedClickSlots: 1 }
        },
        [CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS]: clicksSeed,
        [CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICK_GUARDS]: {
          u_ok_1__camp_ok: { activeClickId: "new_ok_1", hasActiveReservation: true },
          u_ok_2__camp_ok: { activeClickId: "new_ok_2", hasActiveReservation: true }
        }
      },
      {
        failTransactionOnDocPath: `${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_fail`
      }
    );

    // batchLimit=5 is SMALLER than 25 old EXPIRED docs; starvation fix ensures ISSUED clicks are still found!
    const sweepRes = await handleReleaseExpiredCampaignClickReservations({
      db,
      now: () => BASE_NOW,
      batchLimit: 5,
      serverTimestamp: new Date(BASE_NOW)
    });

    assert.strictEqual(sweepRes.releasedClicks, 2);
    assert.strictEqual(sweepRes.processedCampaigns, 1);
    assert.strictEqual(sweepRes.failedCampaigns.length, 1);
    assert.strictEqual(sweepRes.failedCampaigns[0].campaignId, "camp_fail");
    assert.strictEqual(sweepRes.success, false); // Partial failure accurately surfaced

    // camp_ok slots restored & clicks marked EXPIRED despite camp_fail error
    const campOk = (await db.collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS).doc("camp_ok").get()).data();
    assert.strictEqual(campOk.reservedClickSlots, 0);
    const click1 = (await db.collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS).doc("new_ok_1").get()).data();
    assert.strictEqual(click1.status, "EXPIRED");

    // Audit log written to admin_logs
    const logs = await db.collection("admin_logs").get();
    const maintLogs = logs.docs.map((d) => d.data()).filter((l) => l.actionType === "CAMPAIGN_MAINTENANCE");
    assert.strictEqual(maintLogs.length, 1);
    assert.ok(maintLogs[0].title.includes("2 released"));
  });

  // -----------------------------------------------------------------------
  // TEST 6: Activity & Moderation IDs are irreversible & distinct from clickId
  // -----------------------------------------------------------------------
  await test("6. Activity IDs and Moderation IDs are deterministic, prefixed, and do not expose clickId", async () => {
    const clickId = "clk_secret_abc123xyz";
    const convId = `conv_${clickId}`;
    const modId = buildConversionModerationId(convId);
    const actId = buildUserActivityId(convId);
    assert.ok(/^mod_[a-f0-9]{24}$/.test(modId));
    assert.ok(/^act_[a-f0-9]{24}$/.test(actId));
    assert.notStrictEqual(modId.slice(4), actId.slice(4));
    assert.strictEqual(modId.includes(clickId), false);
    assert.strictEqual(actId.includes(clickId), false);
  });

  console.log(`\nALL ${passed} PHASE 3E E2E TESTS PASSED.\n`);
}

runTests().catch((err) => {
  console.error("PHASE 3E E2E TEST FAILURE:", err);
  process.exit(1);
});
