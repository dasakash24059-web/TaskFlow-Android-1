const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const { test } = require("node:test");
const {
  _rewardHandlers,
  _leaderboardHandlers,
  _campaignTrackingHandlers,
  _campaignConversionHandlers
} = require("./index.js");
const {
  CAMPAIGN_TRACKING_COLLECTIONS,
  CAMPAIGN_VERIFICATION_MODES,
  buildUserActivityId
} = require("./campaign_tracking.js");

const {
  handleCreateScratchCardSession,
  handleClaimScratchCardReward
} = _rewardHandlers;
const { handleGetPublicLeaderboard } = _leaderboardHandlers;
const { handleCreateCampaignClick } = _campaignTrackingHandlers;
const { handleCampaignPostback } = _campaignConversionHandlers;

function createMockFirestore() {
  const store = new Map();
  let autoIdCounter = 0;

  function clone(val) {
    if (val === null || val === undefined || typeof val !== "object") return val;
    if (val instanceof Date) return new Date(val.getTime());
    if (Array.isArray(val)) return val.map(clone);
    const out = {};
    for (const [k, v] of Object.entries(val)) {
      out[k] = clone(v);
    }
    return out;
  }

  function applyFieldValues(existing, incoming, merge) {
    const base = merge && existing ? clone(existing) : {};
    for (const [key, val] of Object.entries(incoming || {})) {
      if (val && typeof val === "object" && val.__op === "increment") {
        base[key] = (typeof base[key] === "number" ? base[key] : 0) + val.operand;
      } else if (val && typeof val === "object" && val.__op === "serverTimestamp") {
        base[key] = new Date();
      } else {
        base[key] = clone(val);
      }
    }
    return base;
  }

  function makeDocRef(path) {
    const parts = path.split("/");
    const id = parts[parts.length - 1];
    return {
      id,
      path,
      collection(subName) {
        return makeCollectionRef(`${path}/${subName}`);
      },
      async get() {
        const exists = store.has(path);
        const data = exists ? clone(store.get(path)) : undefined;
        return {
          id,
          ref: this,
          exists,
          data: () => (exists ? clone(data) : undefined)
        };
      },
      async set(data, options = {}) {
        const next = applyFieldValues(store.get(path), data, !!options.merge);
        store.set(path, next);
      },
      async update(data) {
        if (!store.has(path)) throw new Error(`Document ${path} does not exist`);
        const next = applyFieldValues(store.get(path), data, true);
        store.set(path, next);
      }
    };
  }

  function makeQuery(colPath, filters = [], limitCount = null) {
    return {
      where(field, op, value) {
        return makeQuery(colPath, [...filters, { field, op, value }], limitCount);
      },
      limit(n) {
        return makeQuery(colPath, filters, n);
      },
      async get() {
        const prefix = `${colPath}/`;
        const depth = colPath.split("/").length + 1;
        const matched = [];
        for (const [path, data] of store.entries()) {
          if (!path.startsWith(prefix)) continue;
          if (path.split("/").length !== depth) continue;
          let ok = true;
          for (const f of filters) {
            const v = data[f.field];
            if (f.op === "==" && v !== f.value) ok = false;
            if (f.op === "in" && (!Array.isArray(f.value) || !f.value.includes(v))) ok = false;
          }
          if (ok) {
            const id = path.split("/").pop();
            matched.push({
              id,
              ref: makeDocRef(path),
              exists: true,
              data: () => clone(data)
            });
          }
        }
        const docs = limitCount !== null ? matched.slice(0, limitCount) : matched;
        return { docs, empty: docs.length === 0, size: docs.length };
      }
    };
  }

  function makeCollectionRef(colPath) {
    const q = makeQuery(colPath);
    return {
      ...q,
      doc(docId) {
        const id = docId || `auto_${++autoIdCounter}`;
        return makeDocRef(`${colPath}/${id}`);
      }
    };
  }

  return {
    store,
    seed(path, data) {
      store.set(path, clone(data));
    },
    read(path) {
      return store.has(path) ? clone(store.get(path)) : undefined;
    },
    collection(name) {
      return makeCollectionRef(name);
    },
    async runTransaction(fn) {
      const tx = {
        async get(target) {
          return target.get();
        },
        set(ref, data, options = {}) {
          const next = applyFieldValues(store.get(ref.path), data, !!options.merge);
          store.set(ref.path, next);
        },
        update(ref, data) {
          if (!store.has(ref.path)) throw new Error(`Document ${ref.path} does not exist`);
          const next = applyFieldValues(store.get(ref.path), data, true);
          store.set(ref.path, next);
        }
      };
      return fn(tx);
    }
  };
}

const mockFieldValue = {
  serverTimestamp: () => ({ __op: "serverTimestamp" }),
  increment: (operand) => ({ __op: "increment", operand })
};

function signSortedParams(params, secret) {
  const canonical = Object.keys(params)
    .filter((k) => k !== "sig" && k !== "signature" && k !== "hmac" && params[k] !== undefined && params[k] !== "")
    .sort()
    .map((k) => `${k}=${params[k]}`)
    .join("&");
  return crypto.createHmac("sha256", secret).update(canonical).digest("hex");
}

function makeMockRes() {
  return {
    statusCode: 200,
    payload: null,
    status(code) {
      this.statusCode = code;
      return this;
    },
    json(data) {
      this.payload = data;
      return this;
    }
  };
}

test("C-02: Scratch card sessions carry serverGenerated + expiresAt and expired sessions are rejected on claim", async () => {
  const db = createMockFirestore();
  let nowMillis = 1_710_000_000_000;
  db.seed("users/user_scratch", {
    userId: "user_scratch",
    displayName: "Scratch Tester",
    approvalStatus: "approved",
    balanceCents: 0,
    balanceCoins: 0,
    eligibleCoins: 0,
    promotionalCoins: 0
  });

  const deps = {
    db,
    now: () => nowMillis,
    random: () => 0.5
  };

  const created = await handleCreateScratchCardSession(
    { auth: { uid: "user_scratch" }, data: {} },
    deps
  );
  assert.ok(typeof created.cardId === "string" && created.cardId.length > 0);
  assert.equal(created.isFree, true);
  assert.ok(created.rewardCoins >= 1 && created.rewardCoins <= 40);

  const sessionDoc = db.read(`users/user_scratch/scratch_sessions/${created.cardId}`);
  assert.equal(sessionDoc.serverGenerated, true);
  assert.ok(sessionDoc.expiresAt instanceof Date);
  assert.equal(sessionDoc.expiresAt.getTime(), nowMillis + 24 * 60 * 60 * 1000);

  // Advance clock past 24h expiration -> claim MUST fail closed
  nowMillis += 25 * 60 * 60 * 1000;
  await assert.rejects(
    () => handleClaimScratchCardReward(
      { auth: { uid: "user_scratch" }, data: { cardId: created.cardId } },
      deps
    ),
    (err) => err.code === "deadline-exceeded" && /expired/i.test(err.message)
  );

  // Balance remains untouched
  assert.equal(db.read("users/user_scratch").balanceCoins, 0);
});

test("C-05: getPublicLeaderboard aggregates allTime/thisMonth/thisWeek server-side, excludes admin & suspended users, subtracts reversals once, and returns zero private fields", async () => {
  const db = createMockFirestore();
  // Fixed IST timestamp: Wednesday 2026-05-13 12:00:00 IST (UTC+5:30 = 06:30 UTC)
  const nowMillis = Date.UTC(2026, 4, 13, 6, 30, 0);
  const insideWeek = new Date(Date.UTC(2026, 4, 12, 8, 0, 0)); // Tuesday May 12
  const insideMonthBeforeWeek = new Date(Date.UTC(2026, 4, 3, 8, 0, 0)); // May 3
  const previousMonth = new Date(Date.UTC(2026, 3, 20, 8, 0, 0)); // April 20

  db.seed("leaderboard/u_alice", {
    userId: "u_alice",
    displayName: "Alice",
    balanceCoins: 1200,
    balanceCents: 800,
    completedTasksCount: 3,
    approvalStatus: "approved"
  });
  db.seed("leaderboard/u_bob", {
    userId: "u_bob",
    displayName: "Bob",
    balanceCoins: 700,
    balanceCents: 500,
    completedTasksCount: 2,
    approvalStatus: "approved"
  });
  db.seed("leaderboard/u_suspended", {
    userId: "u_suspended",
    displayName: "Suspended User",
    balanceCoins: 9999,
    balanceCents: 7000,
    completedTasksCount: 10,
    approvalStatus: "suspended"
  });
  db.seed("leaderboard/u_admin", {
    userId: "u_admin",
    displayName: "Admin",
    email: "dasakash24059@gmail.com",
    role: "admin",
    balanceCoins: 50000,
    completedTasksCount: 99,
    approvalStatus: "approved"
  });

  // Alice earned 500 TF this week, 400 TF earlier this month, 300 TF last month
  db.seed("transactions/tx_alice_week", {
    userId: "u_alice",
    userName: "Alice",
    type: "CAMPAIGN_REWARD",
    amountCoins: 500,
    amountCents: 400,
    status: "CREDITED",
    referenceId: "act_alice_week",
    createdAt: insideWeek
  });
  db.seed("transactions/tx_alice_month", {
    userId: "u_alice",
    userName: "Alice",
    type: "TASK_REWARD",
    amountCoins: 400,
    amountCents: 320,
    status: "CREDITED",
    referenceId: "sub_alice_1",
    createdAt: insideMonthBeforeWeek
  });
  db.seed("transactions/tx_alice_old", {
    userId: "u_alice",
    userName: "Alice",
    type: "PROMO_CODE",
    amountCoins: 300,
    amountCents: 0,
    status: "CREDITED",
    referenceId: "PROMO1",
    createdAt: previousMonth
  });

  // Bob earned 600 TF this week via campaign, then had 200 TF reversed (with duplicate reversal record to test dedupe!)
  db.seed("transactions/conv_credit_act_bob_1", {
    userId: "u_bob",
    userName: "Bob",
    type: "CAMPAIGN_REWARD",
    amountCoins: 600,
    amountCents: 480,
    status: "CREDITED",
    referenceId: "act_bob_1",
    createdAt: insideWeek
  });
  db.seed("transactions/conv_rev_act_bob_1", {
    userId: "u_bob",
    userName: "Bob",
    type: "CAMPAIGN_REVERSAL",
    amountCoins: 200,
    amountCents: 160,
    status: "REVERSED",
    referenceId: "act_bob_1",
    originalTransactionId: "conv_credit_act_bob_1",
    createdAt: insideWeek
  });

  // Unauthenticated call MUST be rejected
  await assert.rejects(
    () => handleGetPublicLeaderboard({ auth: null, data: {} }, { db, now: () => nowMillis }),
    (err) => err.code === "unauthenticated"
  );

  const res = await handleGetPublicLeaderboard(
    { auth: { uid: "u_bob" }, data: {} },
    { db, now: () => nowMillis }
  );

  assert.equal(res.success, true);
  assert.equal(res.generatedAtMillis, nowMillis);

  // Admin & suspended users are strictly excluded from all 3 slices
  for (const slice of [res.allTime, res.thisMonth, res.thisWeek]) {
    assert.ok(!slice.some((e) => e.userId === "u_admin" || e.userId === "u_suspended"));
    for (const row of slice) {
      assert.deepEqual(
        Object.keys(row).sort(),
        ["completedTasksCount", "displayName", "earnedCoins", "rank", "userId"]
      );
    }
  }

  // All Time: Alice 1200 (#1), Bob 700 (leaderboard balance max with net tx 400 -> 700, #2)
  assert.equal(res.allTime[0].userId, "u_alice");
  assert.equal(res.allTime[0].earnedCoins, 1200);
  assert.equal(res.allTime[1].userId, "u_bob");
  assert.equal(res.allTime[1].earnedCoins, 700);

  // This Month: Alice 500 + 400 = 900 (#1), Bob 600 - 200 = 400 (#2)
  assert.equal(res.thisMonth[0].userId, "u_alice");
  assert.equal(res.thisMonth[0].earnedCoins, 900);
  assert.equal(res.thisMonth[1].userId, "u_bob");
  assert.equal(res.thisMonth[1].earnedCoins, 400);

  // This Week: Alice 500 (#1), Bob 600 - 200 = 400 (#2)
  assert.equal(res.thisWeek[0].userId, "u_alice");
  assert.equal(res.thisWeek[0].earnedCoins, 500);
  assert.equal(res.thisWeek[1].userId, "u_bob");
  assert.equal(res.thisWeek[1].earnedCoins, 400);
});

test("C-05: Campaign postback reversal on legacy conversion sanitizes originalTransactionId and never writes raw clickId to transactions or notifications", async () => {
  const db = createMockFirestore();
  let nowMillis = 1_710_000_000_000;
  const secret = "legacy_rev_secret_987";

  db.seed("users/user_legacy", {
    userId: "user_legacy",
    displayName: "Legacy User",
    approvalStatus: "approved",
    balanceCents: 400,
    eligibleCoins: 500,
    promotionalCoins: 0,
    balanceCoins: 500,
    completedTasksCount: 1
  });
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/net_legacy`, {
    networkId: "net_legacy",
    name: "Legacy Network",
    adapterType: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
    isActive: true,
    capabilities: {
      supportsIncentivizedTraffic: true,
      supportsPostback: true,
      supportsInstallAttribution: true,
      supportsServerToServerPolling: false,
      requiresManualReview: false
    },
    allowedDestinationHosts: ["partner.example.com"],
    parameterMapping: {
      clickIdParam: "click_id",
      externalTxnParam: "txn_id",
      statusParam: "status",
      payoutParam: "payout",
      signatureParam: "sig"
    },
    signatureConfig: {
      algorithm: "HMAC_SHA256",
      queryParamName: "sig",
      signingMode: "SORTED_PARAMS",
      secretEnvKey: "CAMPAIGN_POSTBACK_SECRET"
    }
  });
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_legacy`, {
    configId: "camp_legacy",
    campaignId: "camp_legacy",
    networkId: "net_legacy",
    title: "Legacy Offer",
    isActive: true,
    startAtMillis: nowMillis - 60_000,
    expiresAtMillis: nowMillis + 86_400_000,
    verificationMode: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
    trackingUrlTemplate: "https://partner.example.com/go?cid={click_id}",
    rewardTf: 500,
    rewardCents: 400,
    rewardBudgetTf: 5000,
    spentBudgetTf: 500,
    submissionBudget: 10,
    usedSubmissions: 1,
    reservedClickSlots: 0
  });

  const deps = {
    db,
    now: () => nowMillis,
    serverTimestamp: () => ({ __isServerTimestamp: true }),
    timestampFromMillis: (ms) => new Date(ms),
    resolveSecret: () => secret
  };

  const rawClickId = "clk_secret_1234567890abcdef";
  const conversionId = `conv_${rawClickId}`;
  const expectedActivityId = buildUserActivityId(conversionId);

  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS}/${rawClickId}`, {
    clickId: rawClickId,
    campaignId: "camp_legacy",
    networkId: "net_legacy",
    userId: "user_legacy",
    status: "CONVERTED",
    expiresAtMillis: nowMillis + 86_400_000
  });

  // Pre-fix historical conversion whose creditedTransactionId contained raw clickId
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS}/${conversionId}`, {
    conversionId,
    clickId: rawClickId,
    campaignId: "camp_legacy",
    networkId: "net_legacy",
    userId: "user_legacy",
    externalTransactionId: "legacy_txn_001",
    state: "APPROVED",
    rewardTf: 500,
    rewardCents: 400,
    rewardCredited: true,
    creditedTransactionId: `conv_credit_${conversionId}`,
    reversalTransactionId: ""
  });

  const qRev = {
    networkId: "net_legacy",
    click_id: rawClickId,
    txn_id: "legacy_txn_001",
    status: "chargeback"
  };
  qRev.sig = signSortedParams(qRev, secret);
  const res = makeMockRes();
  await handleCampaignPostback({ method: "GET", query: qRev, headers: {} }, res, deps);

  assert.equal(res.statusCode, 200);
  assert.equal(res.payload.state, "REVERSED");

  // New reversal transaction & notification MUST use opaque activityId and contain ZERO raw clickId
  const revTx = db.read(`transactions/conv_rev_${expectedActivityId}`);
  assert.ok(revTx, "Reversal transaction should use opaque activityId");
  assert.equal(revTx.referenceId, expectedActivityId);
  assert.equal(revTx.originalTransactionId, `conv_credit_${expectedActivityId}`);
  assert.ok(!JSON.stringify(revTx).includes(rawClickId));

  const revNotif = db.read(`users/user_legacy/notifications/notif_conv_rev_${expectedActivityId}`);
  assert.ok(revNotif, "Reversal notification should use opaque activityId");
  assert.equal(revNotif.referenceId, expectedActivityId);
  assert.ok(!JSON.stringify(revNotif).includes(rawClickId));

  // Verify no new user-facing transaction or notification doc IDs contain rawClickId
  for (const key of db.store.keys()) {
    if (key.startsWith("transactions/") || key.includes("/notifications/")) {
      assert.ok(!key.includes(rawClickId), `Leaked raw clickId in user-facing doc path: ${key}`);
    }
  }
});
