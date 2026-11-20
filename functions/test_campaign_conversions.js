const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const { test } = require("node:test");
const {
  _campaignTrackingHandlers,
  _campaignConversionHandlers
} = require("./index.js");
const {
  CAMPAIGN_TRACKING_COLLECTIONS,
  CAMPAIGN_CONVERSION_LOCKS_COLLECTION,
  CAMPAIGN_VERIFICATION_MODES,
  CAMPAIGN_CONVERSION_STATES,
  tfToBalanceCents,
  computeCampaignReversedWallet
} = require("./campaign_tracking.js");

const { handleCreateCampaignClick } = _campaignTrackingHandlers;
const {
  handleCampaignPostback,
  handleReviewCampaignConversion,
  resolveCampaignNetworkSecret,
  CAMPAIGN_POSTBACK_BOUND_SECRETS,
  ALLOWED_CAMPAIGN_SECRET_KEYS
} = _campaignConversionHandlers;

/**
 * In-memory Firestore simulator with optimistic-concurrency transactions
 * mirroring Firebase Admin SDK behavior under concurrent conversion callbacks.
 */
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

  function resolveValue(val, serverNow) {
    if (val && val.__isServerTimestamp) {
      return new Date(serverNow);
    }
    if (val && typeof val === "object" && !(val instanceof Date) && !Array.isArray(val)) {
      const out = {};
      for (const [k, v] of Object.entries(val)) {
        out[k] = resolveValue(v, serverNow);
      }
      return out;
    }
    return clone(val);
  }

  function makeDocRef(path) {
    return {
      path,
      id: path.split("/").pop(),
      collection(subName) {
        return makeCollectionRef(`${path}/${subName}`);
      },
      async get() {
        const entry = store.get(path);
        return {
          id: path.split("/").pop(),
          exists: Boolean(entry),
          data: () => (entry ? clone(entry.data) : undefined)
        };
      }
    };
  }

  function makeQueryRef(prefix, filters = [], limitVal = Infinity) {
    return {
      where(field, op, value) {
        return makeQueryRef(prefix, [...filters, { field, op, value }], limitVal);
      },
      limit(n) {
        return makeQueryRef(prefix, filters, n);
      },
      async get() {
        const docs = [];
        for (const [k, v] of store.entries()) {
          if (!k.startsWith(`${prefix}/`)) continue;
          const rel = k.slice(prefix.length + 1);
          if (rel.includes("/")) continue;
          const d = clone(v.data);
          const matches = filters.every(({ field, op, value }) => {
            if (op === "==") return d[field] === value;
            if (op === "<=") return d[field] <= value;
            if (op === "<") return d[field] < value;
            if (op === ">=") return d[field] >= value;
            if (op === ">") return d[field] > value;
            return false;
          });
          if (matches) {
            docs.push({
              id: rel,
              ref: makeDocRef(k),
              exists: true,
              data: () => clone(v.data)
            });
          }
          if (docs.length >= limitVal) break;
        }
        return { docs };
      }
    };
  }

  function makeCollectionRef(path) {
    return {
      path,
      doc(docId) {
        const resolvedId = docId || `auto_${++autoIdCounter}`;
        return makeDocRef(`${path}/${resolvedId}`);
      },
      where(field, op, value) {
        return makeQueryRef(path, [{ field, op, value }], Infinity);
      },
      limit(n) {
        return makeQueryRef(path, [], n);
      }
    };
  }

  async function runTransaction(fn) {
    const MAX_ATTEMPTS = 20;
    for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      const readVersions = new Map();
      const pendingWrites = [];

      const transaction = {
        async get(targetRef) {
          await Promise.resolve();
          if (typeof targetRef.where === "function" || !targetRef.path) {
            const snap = await targetRef.get();
            for (const d of snap.docs) {
              const entry = store.get(d.ref.path);
              readVersions.set(d.ref.path, entry ? entry.version : 0);
            }
            return snap;
          }
          const entry = store.get(targetRef.path);
          readVersions.set(targetRef.path, entry ? entry.version : 0);
          return {
            id: targetRef.id,
            exists: Boolean(entry),
            data: () => (entry ? clone(entry.data) : undefined)
          };
        },
        set(docRef, data, options) {
          pendingWrites.push({
            op: "set",
            path: docRef.path,
            data: clone(data),
            merge: Boolean(options && options.merge)
          });
        },
        update(docRef, data) {
          pendingWrites.push({
            op: "update",
            path: docRef.path,
            data: clone(data)
          });
        },
        delete(docRef) {
          pendingWrites.push({
            op: "delete",
            path: docRef.path
          });
        }
      };

      const result = await fn(transaction);

      let conflict = false;
      for (const [docPath, expectedVersion] of readVersions.entries()) {
        const currentEntry = store.get(docPath);
        const currentVersion = currentEntry ? currentEntry.version : 0;
        if (currentVersion !== expectedVersion) {
          conflict = true;
          break;
        }
      }

      if (conflict) {
        if (attempt === MAX_ATTEMPTS) {
          const err = new Error("ABORTED: Transaction contention max retries exceeded");
          err.code = "aborted";
          throw err;
        }
        continue;
      }

      const serverNow = Date.now();
      for (const w of pendingWrites) {
        if (w.op === "delete") {
          store.delete(w.path);
          continue;
        }
        const prev = store.get(w.path);
        const nextVersion = (prev ? prev.version : 0) + 1;
        const resolved = resolveValue(w.data, serverNow);
        if (w.op === "update") {
          if (!prev) {
            throw new Error(`NOT_FOUND: Cannot update missing doc ${w.path}`);
          }
          store.set(w.path, {
            data: { ...clone(prev.data), ...resolved },
            version: nextVersion
          });
        } else if (w.op === "set" && w.merge && prev) {
          store.set(w.path, {
            data: { ...clone(prev.data), ...resolved },
            version: nextVersion
          });
        } else {
          store.set(w.path, {
            data: resolved,
            version: nextVersion
          });
        }
      }

      return result;
    }
  }

  return {
    _store: store,
    collection(name) {
      return makeCollectionRef(name);
    },
    runTransaction,
    seed(path, data) {
      const prev = store.get(path);
      store.set(path, { data: clone(data), version: (prev ? prev.version : 0) + 1 });
    },
    read(path) {
      const entry = store.get(path);
      return entry ? clone(entry.data) : null;
    },
    listByPrefix(prefix) {
      const out = [];
      for (const [k, v] of store.entries()) {
        if (k.startsWith(prefix)) out.push({ path: k, data: clone(v.data) });
      }
      return out;
    }
  };
}

function makeMockRes() {
  const res = {
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
  return res;
}

function makeDeps(db, nowRef, secrets = {}) {
  return {
    db,
    now: () => nowRef.value,
    serverTimestamp: () => ({ __isServerTimestamp: true }),
    timestampFromMillis: (ms) => new Date(ms),
    secrets
  };
}

function signSortedParams(params, secret, algorithm = "sha256") {
  const canonical = Object.keys(params)
    .sort()
    .map((k) => `${k}=${params[k]}`)
    .join("&");
  return crypto.createHmac(algorithm, secret).update(canonical, "utf8").digest("hex");
}

function seedCampaignEnvironment(db, nowMs, {
  campaignId = "camp_offer_01",
  networkId = "net_postback_alpha",
  rewardTf = 500,
  submissionBudget = 5,
  rewardBudgetTf = 2500,
  requireAdminApproval = false,
  allowMultipleConversions = false,
  verificationMode = CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
  signatureConfig = {
    algorithm: "HMAC_SHA256",
    queryParamName: "sig",
    signingMode: "SORTED_PARAMS",
    secretEnvKey: "CAMPAIGN_POSTBACK_SECRET"
  },
  parameterMapping = {
    clickIdParam: "click_id",
    externalTxnParam: "txn_id",
    statusParam: "status",
    payoutParam: "payout",
    signatureParam: "sig"
  },
  userId = "user_alice",
  adminUid = "admin_001",
  adminEmail = "dasakash24059@gmail.com"
} = {}) {
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/${networkId}`, {
    networkId,
    name: "Alpha Affiliate S2S",
    adapterType: verificationMode,
    isActive: true,
    capabilities: {
      supportsIncentivizedTraffic: true,
      supportsPostback: true,
      supportsInstallAttribution: true,
      supportsServerToServerPolling: false,
      requiresManualReview: false
    },
    allowedDestinationHosts: ["offer.example.com"],
    parameterMapping,
    signatureConfig
  });

  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/${campaignId}`, {
    configId: campaignId,
    campaignId,
    title: "Install Example Finance",
    isActive: true,
    startAtMillis: nowMs - 60000,
    expiresAtMillis: nowMs + 86400000,
    submissionBudget,
    usedSubmissions: 0,
    reservedClickSlots: 0,
    rewardBudgetTf,
    spentBudgetTf: 0,
    allowedCountries: ["ALL"],
    allowedPlatforms: ["ANDROID"],
    rewardTf,
    verificationMode,
    networkId,
    trackingNetworkRef: `${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/${networkId}`,
    trackingBaseUrl: "https://offer.example.com/install",
    conversionRequirements: {
      requireUniqueClickId: true,
      requireAdminApproval,
      allowMultipleConversions,
      attributionWindowHours: 72
    }
  });

  db.seed(`campaigns/${campaignId}`, {
    campaignId,
    title: "Install Example Finance",
    status: "ACTIVE",
    budgetCents: tfToBalanceCents(rewardBudgetTf),
    spentCents: 0,
    targetSubmissions: submissionBudget,
    completedSubmissions: 0,
    rewardPerTaskCents: tfToBalanceCents(rewardTf)
  });

  db.seed(`users/${userId}`, {
    userId,
    displayName: "Alice",
    email: "alice@example.com",
    role: "user",
    approvalStatus: "approved",
    balanceCents: 0,
    balanceCoins: 0,
    eligibleCoins: 0,
    promotionalCoins: 100,
    completedTasksCount: 0
  });

  db.seed(`admins/${adminUid}`, {
    userId: adminUid,
    email: adminEmail
  });
}

async function issueClickForUser(db, deps, { uid = "user_alice", campaignId = "camp_offer_01" } = {}) {
  return await handleCreateCampaignClick(
    {
      auth: { uid },
      app: { appId: "1:123:android:abc" },
      data: { campaignId, devicePlatform: "ANDROID" },
      rawRequest: { headers: { "x-appengine-country": "IN" } }
    },
    deps
  );
}

test("1. Rejects missing, tampered, or insecure NONE signatures and missing server secrets", async () => {
  const nowRef = { value: 1_710_000_000_000 };
  const db = createMockFirestore();
  const secret = "super_secret_hmac_key_2025";
  const deps = makeDeps(db, nowRef, { CAMPAIGN_POSTBACK_SECRET: secret });

  seedCampaignEnvironment(db, nowRef.value);
  const click = await issueClickForUser(db, deps);

  // Missing signature
  {
    const res = makeMockRes();
    await handleCampaignPostback(
      {
        method: "GET",
        query: {
          networkId: "net_postback_alpha",
          click_id: click.clickId,
          txn_id: "ext_001",
          status: "approved"
        },
        headers: {}
      },
      res,
      deps
    );
    assert.equal(res.statusCode, 401);
    assert.equal(res.payload.success, false);
  }

  // Tampered HMAC signature
  {
    const res = makeMockRes();
    await handleCampaignPostback(
      {
        method: "GET",
        query: {
          networkId: "net_postback_alpha",
          click_id: click.clickId,
          txn_id: "ext_001",
          status: "approved",
          sig: "deadbeef00000000000000000000000000000000000000000000000000000000"
        },
        headers: {}
      },
      res,
      deps
    );
    assert.equal(res.statusCode, 401);
    assert.equal(res.payload.success, false);
  }

  // Network configured with insecure algorithm=NONE must fail closed
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/net_insecure`, {
    networkId: "net_insecure",
    name: "Insecure Network",
    adapterType: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
    isActive: true,
    capabilities: { supportsPostback: true, supportsIncentivizedTraffic: true },
    parameterMapping: { clickIdParam: "click_id", externalTxnParam: "txn_id", statusParam: "status" },
    signatureConfig: { algorithm: "NONE" }
  });
  {
    const res = makeMockRes();
    await handleCampaignPostback(
      {
        method: "GET",
        query: { networkId: "net_insecure", click_id: click.clickId, txn_id: "ext_001", status: "approved" },
        headers: {}
      },
      res,
      deps
    );
    assert.equal(res.statusCode, 401);
    assert.equal(res.payload.success, false);
  }

  // Missing secret env must fail closed with 500 without leaking secrets
  {
    const noSecretDeps = makeDeps(db, nowRef, {});
    const res = makeMockRes();
    await handleCampaignPostback(
      {
        method: "GET",
        query: {
          networkId: "net_postback_alpha",
          click_id: click.clickId,
          txn_id: "ext_001",
          status: "approved",
          sig: "abc"
        },
        headers: {}
      },
      res,
      noSecretDeps
    );
    assert.equal(res.statusCode, 500);
    assert.equal(res.payload.success, false);
    assert.ok(!JSON.stringify(res.payload).includes(secret));
  }

  // Wallet must remain untouched
  assert.equal(db.read("users/user_alice").eligibleCoins, 0);
  assert.equal(db.listByPrefix(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS}/`).length, 0);
});

test("2. Attribution validation rejects unknown click, wrong network, mismatched user/campaign, and expired clicks", async () => {
  const nowRef = { value: 1_710_000_000_000 };
  const db = createMockFirestore();
  const secret = "attr_secret_key_999";
  const deps = makeDeps(db, nowRef, { CAMPAIGN_POSTBACK_SECRET: secret });

  seedCampaignEnvironment(db, nowRef.value);
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/net_other`, {
    networkId: "net_other",
    name: "Other Network",
    adapterType: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
    isActive: true,
    capabilities: { supportsIncentivizedTraffic: true, supportsPostback: true },
    parameterMapping: { clickIdParam: "click_id", externalTxnParam: "txn_id", statusParam: "status", signatureParam: "sig" },
    signatureConfig: { algorithm: "HMAC_SHA256", queryParamName: "sig", signingMode: "SORTED_PARAMS", secretEnvKey: "CAMPAIGN_POSTBACK_SECRET" }
  });

  const click = await issueClickForUser(db, deps);

  // Unknown clickId
  {
    const q = { networkId: "net_postback_alpha", click_id: "clk_nonexistent_1234567890", txn_id: "t1", status: "approved" };
    q.sig = signSortedParams(q, secret);
    const res = makeMockRes();
    await handleCampaignPostback({ method: "GET", query: q, headers: {} }, res, deps);
    assert.equal(res.statusCode, 404);
  }

  // Wrong networkId for the click
  {
    const q = { networkId: "net_other", click_id: click.clickId, txn_id: "t2", status: "approved" };
    q.sig = signSortedParams(q, secret);
    const res = makeMockRes();
    await handleCampaignPostback({ method: "GET", query: q, headers: {} }, res, deps);
    assert.equal(res.statusCode, 403);
  }

  // Forged userId / campaignId mismatch in callback payload
  {
    const q = {
      networkId: "net_postback_alpha",
      click_id: click.clickId,
      txn_id: "t3",
      status: "approved",
      userId: "user_attacker"
    };
    q.sig = signSortedParams(q, secret);
    const res = makeMockRes();
    await handleCampaignPostback({ method: "GET", query: q, headers: {} }, res, deps);
    assert.equal(res.statusCode, 403);
  }

  // Expired click window
  nowRef.value = click.expiresAtMillis + 5000;
  {
    const q = { networkId: "net_postback_alpha", click_id: click.clickId, txn_id: "t4", status: "approved" };
    q.sig = signSortedParams(q, secret);
    const res = makeMockRes();
    await handleCampaignPostback({ method: "GET", query: q, headers: {} }, res, deps);
    assert.equal(res.statusCode, 410);
  }
  assert.equal(db.read("users/user_alice").eligibleCoins, 0);
});

test("3. Verified S2S postback credits authoritative TF reward (125 TF = ₹1), ignores spoofed payout, syncs ledger/leaderboard/notification, and handles network-specific parameter mappings", async () => {
  const nowRef = { value: 1_710_000_000_000 };
  const db = createMockFirestore();
  const netSecret = "beta_network_shared_token_xyz";
  const deps = makeDeps(db, nowRef, { CAMPAIGN_POSTBACK_SECRET_BETA: netSecret });

  // Advertiser network using custom aff_sub / txid / conv_state and shared header token
  seedCampaignEnvironment(db, nowRef.value, {
    campaignId: "camp_beta",
    networkId: "net_beta",
    rewardTf: 500, // 500 TF = ₹4 = 400 paise
    verificationMode: CAMPAIGN_VERIFICATION_MODES.ADVERTISER_S2S_API,
    parameterMapping: {
      clickIdParam: "aff_sub",
      externalTxnParam: "txid",
      statusParam: "conv_state",
      payoutParam: "commission_tf"
    },
    signatureConfig: {
      algorithm: "SHARED_TOKEN_HEADER",
      headerName: "x-beta-secret",
      secretEnvKey: "CAMPAIGN_POSTBACK_SECRET_BETA"
    }
  });

  const click = await issueClickForUser(db, deps, { campaignId: "camp_beta" });

  // Callback spoofs commission_tf=999999 -> MUST credit authoritative 500 TF (400 paise) only
  const res = makeMockRes();
  await handleCampaignPostback(
    {
      method: "POST",
      params: { networkId: "net_beta" },
      query: {},
      body: {
        aff_sub: click.clickId,
        txid: "beta_txn_1001",
        conv_state: "confirmed",
        commission_tf: "999999"
      },
      headers: { "X-Beta-Secret": netSecret }
    },
    res,
    deps
  );

  assert.equal(res.statusCode, 200);
  assert.equal(res.payload.success, true);
  assert.equal(res.payload.state, CAMPAIGN_CONVERSION_STATES.APPROVED);
  assert.equal(res.payload.rewardTf, 500);
  assert.equal(res.payload.rewardCents, 400);
  assert.equal(res.payload.rewardCredited, true);

  // User wallet updated atomically (withdrawable earnings; existing 100 promo preserved)
  const user = db.read("users/user_alice");
  assert.equal(user.eligibleCoins, 500);
  assert.equal(user.balanceCents, 400);
  assert.equal(user.promotionalCoins, 100);
  assert.equal(user.balanceCoins, 600);
  assert.equal(user.completedTasksCount, 1);

  // Leaderboard synced
  const lb = db.read("leaderboard/user_alice");
  assert.equal(lb.balanceCoins, 600);
  assert.equal(lb.balanceCents, 400);

  // Immutable credit ledger doc written without leaking private attribution identifiers
  const creditTx = db.read(`transactions/conv_credit_${res.payload.conversionId}`);
  assert.equal(creditTx.type, "CAMPAIGN_REWARD");
  assert.equal(creditTx.status, "CREDITED");
  assert.equal(creditTx.amountCoins, 500);
  assert.equal(creditTx.amountCents, 400);
  assert.equal(creditTx.referenceId, res.payload.conversionId);
  assert.equal("clickId" in creditTx, false);
  assert.equal("externalTransactionId" in creditTx, false);

  // Server-only campaign_conversions record retains full attribution metadata
  const convRecord = db.read(
    `${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS}/${res.payload.conversionId}`
  );
  assert.equal(convRecord.clickId, click.clickId);
  assert.equal(convRecord.externalTransactionId, "beta_txn_1001");

  // Notification created
  const notif = db.read(`users/user_alice/notifications/notif_conv_credit_${res.payload.conversionId}`);
  assert.equal(notif.type, "CAMPAIGN_REWARD");
  assert.equal(notif.rewardCoins, 500);

  // Reservation slot released, submission used incremented
  const cfg = db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_beta`);
  assert.equal(cfg.usedSubmissions, 1);
  assert.equal(cfg.spentBudgetTf, 500);
  assert.equal(cfg.reservedClickSlots, 0);
  assert.equal(db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS}/${click.clickId}`).status, "CONVERTED");
  assert.ok(!JSON.stringify(res.payload).includes(netSecret));
});

test("4. Concurrent identical APPROVED callbacks credit strictly ONCE and reject duplicate externalTransactionId across different clicks", async () => {
  const nowRef = { value: 1_710_000_000_000 };
  const db = createMockFirestore();
  const secret = "race_secret_hmac_123";
  const deps = makeDeps(db, nowRef, { CAMPAIGN_POSTBACK_SECRET: secret });

  seedCampaignEnvironment(db, nowRef.value, {
    allowMultipleConversions: true
  });

  const click1 = await issueClickForUser(db, deps);
  const q1 = {
    networkId: "net_postback_alpha",
    click_id: click1.clickId,
    txn_id: "shared_ext_txn_777",
    status: "approved"
  };
  q1.sig = signSortedParams(q1, secret);

  // Launch 5 concurrent postbacks for the exact same click + externalTxnId
  const responses = await Promise.all(
    Array.from({ length: 5 }, async () => {
      const res = makeMockRes();
      await handleCampaignPostback({ method: "GET", query: { ...q1 }, headers: {} }, res, deps);
      return res;
    })
  );

  for (const r of responses) {
    assert.equal(r.statusCode, 200);
    assert.equal(r.payload.success, true);
    assert.equal(r.payload.state, CAMPAIGN_CONVERSION_STATES.APPROVED);
  }
  const freshCredits = responses.filter((r) => r.payload.duplicate === false);
  const duplicateReplays = responses.filter((r) => r.payload.duplicate === true);
  assert.equal(freshCredits.length, 1);
  assert.equal(duplicateReplays.length, 4);

  // Wallet credited strictly once (500 TF)
  const user = db.read("users/user_alice");
  assert.equal(user.eligibleCoins, 500);
  assert.equal(user.balanceCoins, 600);
  assert.equal(user.completedTasksCount, 1);
  assert.equal(db.listByPrefix("transactions/").length, 1);

  // Advance past expiry of click1 or use a second user click, reuse SAME external txn id -> blocked 409
  db.seed("users/user_bob", {
    userId: "user_bob",
    displayName: "Bob",
    email: "bob@example.com",
    role: "user",
    approvalStatus: "approved",
    balanceCents: 0,
    balanceCoins: 0,
    eligibleCoins: 0,
    promotionalCoins: 0,
    completedTasksCount: 0
  });
  const clickBob = await issueClickForUser(db, deps, { uid: "user_bob" });
  const qBob = {
    networkId: "net_postback_alpha",
    click_id: clickBob.clickId,
    txn_id: "shared_ext_txn_777",
    status: "approved"
  };
  qBob.sig = signSortedParams(qBob, secret);
  const bobRes = makeMockRes();
  await handleCampaignPostback({ method: "GET", query: qBob, headers: {} }, bobRes, deps);
  assert.equal(bobRes.statusCode, 409);
  assert.equal(bobRes.payload.code, "already-exists");
  assert.equal(db.read("users/user_bob").eligibleCoins, 0);
});

test("5. PENDING conversion hold + non-admin rejection + Admin approval flow credits TF atomically", async () => {
  const nowRef = { value: 1_710_000_000_000 };
  const db = createMockFirestore();
  const secret = "manual_hold_secret_abc";
  const deps = makeDeps(db, nowRef, { CAMPAIGN_POSTBACK_SECRET: secret });

  seedCampaignEnvironment(db, nowRef.value, {
    requireAdminApproval: true,
    rewardTf: 250 // 250 TF = ₹2 = 200 paise
  });
  const click = await issueClickForUser(db, deps);

  // Verified S2S callback claims approved -> must be held in PENDING without crediting TF
  const q = {
    networkId: "net_postback_alpha",
    click_id: click.clickId,
    txn_id: "hold_txn_01",
    status: "approved"
  };
  q.sig = signSortedParams(q, secret);
  const postRes = makeMockRes();
  await handleCampaignPostback({ method: "GET", query: q, headers: {} }, postRes, deps);
  assert.equal(postRes.statusCode, 200);
  assert.equal(postRes.payload.state, CAMPAIGN_CONVERSION_STATES.PENDING);
  assert.equal(postRes.payload.rewardCredited, false);
  assert.equal(db.read("users/user_alice").eligibleCoins, 0);
  assert.equal(
    db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS}/${click.clickId}`).status,
    "PENDING_VERIFICATION"
  );

  // Normal user cannot call reviewCampaignConversion
  await assert.rejects(
    () =>
      handleReviewCampaignConversion(
        {
          auth: { uid: "user_alice", token: { email: "alice@example.com", email_verified: true } },
          data: { conversionId: postRes.payload.conversionId, action: "APPROVE" }
        },
        deps
      ),
    (err) => err.code === "permission-denied"
  );

  // Replay S2S approved callback still stays PENDING (cannot bypass admin approval)
  const replayRes = makeMockRes();
  await handleCampaignPostback({ method: "GET", query: q, headers: {} }, replayRes, deps);
  assert.equal(replayRes.statusCode, 200);
  assert.equal(replayRes.payload.state, CAMPAIGN_CONVERSION_STATES.PENDING);
  assert.equal(replayRes.payload.heldForReview, true);

  // Admin approves conversion (even after click window has elapsed!)
  nowRef.value = click.expiresAtMillis + 3600000;
  const adminApproval = await handleReviewCampaignConversion(
    {
      auth: { uid: "admin_001", token: { email: "dasakash24059@gmail.com", email_verified: true } },
      data: {
        conversionId: postRes.payload.conversionId,
        action: "APPROVE",
        reason: "Verified install screenshot & advertiser invoice"
      }
    },
    deps
  );

  assert.equal(adminApproval.state, CAMPAIGN_CONVERSION_STATES.APPROVED);
  assert.equal(adminApproval.rewardCredited, true);
  assert.equal(adminApproval.rewardTf, 250);
  assert.equal(adminApproval.rewardCents, 200);
  assert.equal(db.read("users/user_alice").eligibleCoins, 250);
  assert.equal(db.read("users/user_alice").balanceCents, 200);
  assert.equal(db.read("users/user_alice").balanceCoins, 350);
});

test("6. Approved conversion reversal creates compensating ledger without deleting history, reconciles budgets safely without negative balances, and blocks double reversals", async () => {
  const nowRef = { value: 1_710_000_000_000 };
  const db = createMockFirestore();
  const secret = "reversal_hmac_secret_789";
  const deps = makeDeps(db, nowRef, { CAMPAIGN_POSTBACK_SECRET: secret });

  seedCampaignEnvironment(db, nowRef.value, {
    rewardTf: 500,
    submissionBudget: 2,
    rewardBudgetTf: 1000
  });
  const click = await issueClickForUser(db, deps);

  // Approve conversion first
  const qApprove = {
    networkId: "net_postback_alpha",
    click_id: click.clickId,
    txn_id: "rev_txn_01",
    status: "approved"
  };
  qApprove.sig = signSortedParams(qApprove, secret);
  const approveRes = makeMockRes();
  await handleCampaignPostback({ method: "GET", query: qApprove, headers: {} }, approveRes, deps);
  assert.equal(approveRes.payload.state, "APPROVED");

  const conversionId = approveRes.payload.conversionId;
  const creditTxPath = `transactions/conv_credit_${conversionId}`;
  assert.ok(db.read(creditTxPath));

  // Reverse via S2S chargeback callback
  const qReverse = {
    networkId: "net_postback_alpha",
    click_id: click.clickId,
    txn_id: "rev_txn_01",
    status: "chargeback"
  };
  qReverse.sig = signSortedParams(qReverse, secret);
  const revRes = makeMockRes();
  await handleCampaignPostback({ method: "GET", query: qReverse, headers: {} }, revRes, deps);

  assert.equal(revRes.statusCode, 200);
  assert.equal(revRes.payload.state, CAMPAIGN_CONVERSION_STATES.REVERSED);
  assert.equal(revRes.payload.recoveredCoins, 500);
  assert.equal(revRes.payload.shortfallCoins, 0);

  // Wallet & budget reconciled
  const userAfterRev = db.read("users/user_alice");
  assert.equal(userAfterRev.eligibleCoins, 0);
  assert.equal(userAfterRev.balanceCents, 0);
  assert.equal(userAfterRev.promotionalCoins, 100);
  assert.equal(userAfterRev.balanceCoins, 100);
  assert.equal(userAfterRev.completedTasksCount, 0);

  const cfgAfterRev = db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_offer_01`);
  assert.equal(cfgAfterRev.usedSubmissions, 0);
  assert.equal(cfgAfterRev.spentBudgetTf, 0);

  // Historical credit tx is NOT deleted, compensating tx created alongside it without clickId leakage
  assert.ok(db.read(creditTxPath));
  const revTx = db.read(`transactions/conv_rev_${conversionId}`);
  assert.equal(revTx.type, "CAMPAIGN_REVERSAL");
  assert.equal(revTx.status, "REVERSED");
  assert.equal(revTx.amountCoins, 500);
  assert.equal(revTx.reversedCoins, -500);
  assert.equal(revTx.originalTransactionId, `conv_credit_${conversionId}`);
  assert.equal("clickId" in revTx, false);
  assert.equal("externalTransactionId" in revTx, false);

  // Replay reversal is idempotent without double debit; invalid backwards transition (REVERSED -> APPROVED) blocked 409
  const replayRevRes = makeMockRes();
  await handleCampaignPostback({ method: "GET", query: qReverse, headers: {} }, replayRevRes, deps);
  assert.equal(replayRevRes.statusCode, 200);
  assert.equal(replayRevRes.payload.duplicate, true);
  assert.equal(db.read("users/user_alice").balanceCoins, 100);

  const invalidBackRes = makeMockRes();
  await handleCampaignPostback({ method: "GET", query: qApprove, headers: {} }, invalidBackRes, deps);
  assert.equal(invalidBackRes.statusCode, 409);

  // Partial balance reversal helper check (user withdrew most funds -> never negative, shortfall tracked)
  const partialRev = computeCampaignReversedWallet(
    { balanceCents: 100, eligibleCoins: 125, promotionalCoins: 25, balanceCoins: 150 },
    500
  );
  assert.equal(partialRev.recoveredCoins, 150);
  assert.equal(partialRev.shortfallCoins, 350);
  assert.equal(partialRev.newBalanceCoins, 0);
  assert.equal(partialRev.newEligibleCoins, 0);
  assert.equal(partialRev.newBalanceCents, 0);
});

test("7. Budget cap prevents overspending across concurrent approvals from different users", async () => {
  const nowRef = { value: 1_710_000_000_000 };
  const db = createMockFirestore();
  const secret = "budget_cap_hmac_secret";
  const deps = makeDeps(db, nowRef, { CAMPAIGN_POSTBACK_SECRET: secret });

  // submissionBudget = 2 slots
  seedCampaignEnvironment(db, nowRef.value, {
    submissionBudget: 2,
    rewardBudgetTf: 1000,
    rewardTf: 500
  });
  for (const uid of ["user_u2", "user_u3"]) {
    db.seed(`users/${uid}`, {
      userId: uid,
      displayName: uid,
      email: `${uid}@example.com`,
      role: "user",
      approvalStatus: "approved",
      balanceCents: 0,
      balanceCoins: 0,
      eligibleCoins: 0,
      promotionalCoins: 0,
      completedTasksCount: 0
    });
  }

  const c1 = await issueClickForUser(db, deps, { uid: "user_alice" });
  const c2 = await issueClickForUser(db, deps, { uid: "user_u2" });
  // Exhaust 1 reservation or simulate a 3rd issued click trying to convert simultaneously
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS}/clk_race_third_00000001`, {
    clickId: "clk_race_third_00000001",
    userId: "user_u3",
    campaignId: "camp_offer_01",
    networkId: "net_postback_alpha",
    trackingNetworkRef: `${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/net_postback_alpha`,
    verificationMode: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
    status: "ISSUED",
    expiresAtMillis: nowRef.value + 3600000
  });

  const requests = [
    { clickId: c1.clickId, txnId: "cap_t1" },
    { clickId: c2.clickId, txnId: "cap_t2" },
    { clickId: "clk_race_third_00000001", txnId: "cap_t3" }
  ].map(async ({ clickId, txnId }) => {
    const q = { networkId: "net_postback_alpha", click_id: clickId, txn_id: txnId, status: "approved" };
    q.sig = signSortedParams(q, secret);
    const res = makeMockRes();
    await handleCampaignPostback({ method: "GET", query: q, headers: {} }, res, deps);
    return res;
  });

  const results = await Promise.all(requests);
  const succeeded = results.filter((r) => r.statusCode === 200);
  const exhausted = results.filter((r) => r.statusCode === 409 && r.payload.code === "resource-exhausted");

  assert.equal(succeeded.length, 2);
  assert.equal(exhausted.length, 1);

  const cfg = db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_offer_01`);
  assert.equal(cfg.usedSubmissions, 2);
  assert.equal(cfg.spentBudgetTf, 1000);
});

test("8. Rejection callback releases reserved click slot cleanly and credits 0 TF", async () => {
  const nowRef = { value: 1_710_000_000_000 };
  const db = createMockFirestore();
  const secret = "reject_secret_hmac";
  const deps = makeDeps(db, nowRef, { CAMPAIGN_POSTBACK_SECRET: secret });

  seedCampaignEnvironment(db, nowRef.value, {
    submissionBudget: 1,
    rewardBudgetTf: 500
  });

  const click = await issueClickForUser(db, deps);
  assert.equal(
    db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_offer_01`).reservedClickSlots,
    1
  );

  const qReject = {
    networkId: "net_postback_alpha",
    click_id: click.clickId,
    txn_id: "rej_txn_01",
    status: "fraud"
  };
  qReject.sig = signSortedParams(qReject, secret);

  const res = makeMockRes();
  await handleCampaignPostback({ method: "GET", query: qReject, headers: {} }, res, deps);

  assert.equal(res.statusCode, 200);
  assert.equal(res.payload.state, CAMPAIGN_CONVERSION_STATES.REJECTED);
  assert.equal(res.payload.rewardCredited, false);
  assert.equal(db.read("users/user_alice").eligibleCoins, 0);
  assert.equal(db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS}/${click.clickId}`).status, "CANCELLED");

  const cfg = db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_offer_01`);
  assert.equal(cfg.reservedClickSlots, 0);
  assert.equal(cfg.usedSubmissions, 0);
  assert.equal(cfg.spentBudgetTf, 0);
});

test("9. Phase 3C remediation: explicit Gen 2 secret registry binds all supported keys and rejects unregistered env keys", async () => {
  assert.deepEqual(
    [...ALLOWED_CAMPAIGN_SECRET_KEYS],
    [
      "CAMPAIGN_POSTBACK_SECRET",
      "CAMPAIGN_POSTBACK_SECRET_ALPHA",
      "CAMPAIGN_POSTBACK_SECRET_BETA",
      "CAMPAIGN_POSTBACK_SECRET_S2S"
    ]
  );
  assert.equal(CAMPAIGN_POSTBACK_BOUND_SECRETS.length, ALLOWED_CAMPAIGN_SECRET_KEYS.length);

  // Unregistered keys MUST fail closed even if present in process.env or deps.secrets (e.g. TELEGRAM_BOT_TOKEN)
  const prevTg = process.env.TELEGRAM_BOT_TOKEN;
  const prevArb = process.env.UNREGISTERED_ARBITRARY_SECRET;
  process.env.TELEGRAM_BOT_TOKEN = "tg_secret_should_never_be_readable";
  process.env.UNREGISTERED_ARBITRARY_SECRET = "arbitrary_env_secret";
  try {
    assert.equal(
      resolveCampaignNetworkSecret(
        { networkId: "net_forged", signatureConfig: { secretEnvKey: "TELEGRAM_BOT_TOKEN" } },
        { secrets: { TELEGRAM_BOT_TOKEN: "tg_secret_should_never_be_readable" } }
      ),
      ""
    );
    assert.equal(
      resolveCampaignNetworkSecret(
        { networkId: "net_forged", signatureConfig: { secretEnvKey: "UNREGISTERED_ARBITRARY_SECRET" } },
        { secrets: { UNREGISTERED_ARBITRARY_SECRET: "arbitrary_env_secret" } }
      ),
      ""
    );
    assert.equal(
      resolveCampaignNetworkSecret(
        { networkId: "net_alpha", signatureConfig: { secretEnvKey: "CAMPAIGN_POSTBACK_SECRET_ALPHA" } },
        { secrets: { CAMPAIGN_POSTBACK_SECRET_ALPHA: "alpha_registered_secret_ok" } }
      ),
      "alpha_registered_secret_ok"
    );
  } finally {
    if (prevTg === undefined) delete process.env.TELEGRAM_BOT_TOKEN;
    else process.env.TELEGRAM_BOT_TOKEN = prevTg;
    if (prevArb === undefined) delete process.env.UNREGISTERED_ARBITRARY_SECRET;
    else process.env.UNREGISTERED_ARBITRARY_SECRET = prevArb;
  }

  // End-to-end postback call referencing unregistered key must fail closed with HTTP 500 without leaking secrets
  const nowRef = { value: 1_710_000_000_000 };
  const db = createMockFirestore();
  const deps = makeDeps(db, nowRef, { UNREGISTERED_ENV_KEY: "do_not_leak_secret" });

  seedCampaignEnvironment(db, nowRef.value, {
    campaignId: "camp_unreg",
    networkId: "net_unreg",
    signatureConfig: {
      algorithm: "SHARED_TOKEN_HEADER",
      headerName: "x-secret",
      secretEnvKey: "UNREGISTERED_ENV_KEY"
    }
  });
  const click = await issueClickForUser(db, deps, { campaignId: "camp_unreg" });

  const res = makeMockRes();
  await handleCampaignPostback(
    {
      method: "POST",
      params: { networkId: "net_unreg" },
      query: {},
      body: { click_id: click.clickId, txn_id: "t_unreg_1", status: "approved" },
      headers: { "x-secret": "do_not_leak_secret" }
    },
    res,
    deps
  );

  assert.equal(res.statusCode, 500);
  assert.equal(res.payload.success, false);
  assert.equal(res.payload.error, "Server verification secret is not configured.");
  assert.ok(!JSON.stringify(res.payload).includes("do_not_leak_secret"));
  assert.equal(db.read("users/user_alice").eligibleCoins, 0);
});

