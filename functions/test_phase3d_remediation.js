const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const { test } = require("node:test");
const {
  _campaignTrackingHandlers,
  _campaignConversionHandlers,
  _campaignAdminHandlers
} = require("./index.js");
const {
  CAMPAIGN_TRACKING_COLLECTIONS,
  CAMPAIGN_VERIFICATION_MODES,
  CAMPAIGN_CONVERSION_STATES,
  ALLOWED_CAMPAIGN_SECRET_KEYS,
  MAX_DESTINATION_URL_LENGTH,
  tfToBalanceCents,
  signedTfToBalanceCents
} = require("./campaign_tracking.js");
const { computeCampaignTrackingAnalytics } = require("./campaign_admin.js");

const { handleCreateCampaignClick } = _campaignTrackingHandlers;
const { handleCampaignPostback, handleReviewCampaignConversion } = _campaignConversionHandlers;
const {
  handleAdminListCampaignNetworks,
  handleAdminUpsertCampaignNetwork,
  handleAdminSetCampaignNetworkStatus,
  handleAdminUpsertCampaignTrackingConfig,
  handleAdminSetCampaignTrackingStatus,
  handleAdminGetCampaignTrackingDashboard
} = _campaignAdminHandlers;

/**
 * Query-capable in-memory Firestore simulator supporting:
 * - where (==, >=, <=, >, <), orderBy (asc/desc), startAfter(docSnap), limit(n)
 * - subcollections, atomic runTransaction with set(merge)/update/delete
 */
function createFullMockFirestore() {
  const store = new Map();
  let autoId = 0;

  function clone(val) {
    if (val === null || val === undefined || typeof val !== "object") return val;
    if (val instanceof Date) return new Date(val.getTime());
    if (Array.isArray(val)) return val.map(clone);
    const out = {};
    for (const [k, v] of Object.entries(val)) out[k] = clone(v);
    return out;
  }

  function resolveValue(val, now) {
    if (val && val.__isServerTimestamp) return new Date(now);
    if (val && typeof val === "object" && !(val instanceof Date) && !Array.isArray(val)) {
      const out = {};
      for (const [k, v] of Object.entries(val)) out[k] = resolveValue(v, now);
      return out;
    }
    return clone(val);
  }

  function toComparable(v) {
    if (v instanceof Date) return v.getTime();
    if (v && typeof v.toMillis === "function") return v.toMillis();
    return v;
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
          ref: makeDocRef(path),
          exists: Boolean(entry),
          data: () => (entry ? clone(entry.data) : undefined)
        };
      }
    };
  }

  function makeQuery(prefix, opts = {}) {
    const filters = opts.filters || [];
    const orders = opts.orders || [];
    const startAfterDoc = opts.startAfterDoc || null;
    const limitVal = opts.limitVal ?? Infinity;

    return {
      path: prefix,
      where(field, op, value) {
        return makeQuery(prefix, { ...opts, filters: [...filters, { field, op, value }] });
      },
      orderBy(field, direction = "asc") {
        return makeQuery(prefix, { ...opts, orders: [...orders, { field, direction }] });
      },
      startAfter(docSnap) {
        return makeQuery(prefix, { ...opts, startAfterDoc: docSnap });
      },
      limit(n) {
        return makeQuery(prefix, { ...opts, limitVal: n });
      },
      async get() {
        let rows = [];
        for (const [k, v] of store.entries()) {
          if (!k.startsWith(`${prefix}/`)) continue;
          const rel = k.slice(prefix.length + 1);
          if (rel.includes("/")) continue;
          const d = clone(v.data);
          const matches = filters.every(({ field, op, value }) => {
            const actual = toComparable(d[field]);
            const target = toComparable(value);
            if (actual === undefined || actual === null) return false;
            if (op === "==") return actual === target;
            if (op === ">=") return actual >= target;
            if (op === "<=") return actual <= target;
            if (op === ">") return actual > target;
            if (op === "<") return actual < target;
            return false;
          });
          if (matches) {
            rows.push({
              id: rel,
              path: k,
              dataObj: d
            });
          }
        }

        if (orders.length > 0) {
          rows.sort((a, b) => {
            for (const { field, direction } of orders) {
              const av = toComparable(a.dataObj[field]);
              const bv = toComparable(b.dataObj[field]);
              if (av !== bv) {
                const cmp = av < bv ? -1 : 1;
                return direction === "desc" ? -cmp : cmp;
              }
            }
            return a.id.localeCompare(b.id);
          });
        } else {
          rows.sort((a, b) => a.id.localeCompare(b.id));
        }

        if (startAfterDoc) {
          const idx = rows.findIndex((r) => r.id === startAfterDoc.id);
          rows = idx >= 0 ? rows.slice(idx + 1) : [];
        }

        if (Number.isFinite(limitVal)) {
          rows = rows.slice(0, limitVal);
        }

        return {
          docs: rows.map((r) => ({
            id: r.id,
            ref: makeDocRef(r.path),
            exists: true,
            data: () => clone(r.dataObj)
          }))
        };
      }
    };
  }

  function makeCollectionRef(path) {
    const baseQuery = makeQuery(path);
    return {
      ...baseQuery,
      doc(docId) {
        return makeDocRef(`${path}/${docId || `auto_${++autoId}`}`);
      }
    };
  }

  async function runTransaction(fn) {
    const writes = [];
    const tx = {
      async get(refOrQuery) {
        return refOrQuery.get();
      },
      set(ref, data, options) {
        writes.push({ op: "set", path: ref.path, data: clone(data), merge: Boolean(options?.merge) });
      },
      update(ref, data) {
        writes.push({ op: "update", path: ref.path, data: clone(data) });
      },
      delete(ref) {
        writes.push({ op: "delete", path: ref.path });
      }
    };
    const result = await fn(tx);
    const now = Date.now();
    for (const w of writes) {
      if (w.op === "delete") {
        store.delete(w.path);
        continue;
      }
      const prev = store.get(w.path);
      const resolved = resolveValue(w.data, now);
      if (w.op === "update") {
        if (!prev) throw new Error(`NOT_FOUND: ${w.path}`);
        store.set(w.path, { data: { ...clone(prev.data), ...resolved } });
      } else if (w.merge && prev) {
        store.set(w.path, { data: { ...clone(prev.data), ...resolved } });
      } else {
        store.set(w.path, { data: resolved });
      }
    }
    return result;
  }

  return {
    collection: (name) => makeCollectionRef(name),
    runTransaction,
    seed(path, data) {
      store.set(path, { data: clone(data) });
    },
    read(path) {
      const e = store.get(path);
      return e ? clone(e.data) : null;
    },
    delete(path) {
      store.delete(path);
    }
  };
}

const ADMIN_AUTH = {
  uid: "admin_001",
  token: { email: "ops-admin@example.com", email_verified: true }
};

function makeDeps(db, nowMs = 1_750_000_000_000) {
  return {
    db,
    now: () => nowMs,
    serverTimestamp: () => ({ __isServerTimestamp: true })
  };
}

function seedAdminAndUser(db) {
  db.seed("admins/admin_001", { userId: "admin_001", email: "ops-admin@example.com" });
  db.seed("users/user_001", {
    userId: "user_001",
    email: "member@example.com",
    displayName: "Member One",
    approvalStatus: "approved",
    accountStatus: "approved",
    isActive: true,
    coins: 0,
    balanceCents: 0,
    completedTasksCount: 0
  });
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

function signSortedParams(params, secret, prefix = "") {
  const keys = Object.keys(params).filter((k) => k !== "sig").sort();
  const canonical = keys.map((k) => `${k}=${params[k]}`).join("&");
  const digest = crypto.createHmac("sha256", secret).update(canonical, "utf8").digest("hex");
  return `${prefix}${digest}`;
}

// =========================================================================
// HIGH-1 — PHASE 3D / PHASE 3B / PHASE 3C CONFIGURATION INTEGRITY
// =========================================================================

test("HIGH-1.1: Phase 3D edit preserves Phase 3B/3C campaign & network runtime fields and wires App Check + signaturePrefix", async () => {
  const db = createFullMockFirestore();
  seedAdminAndUser(db);
  const nowMs = 1_750_000_000_000;
  const deps = makeDeps(db, nowMs);

  // Seed pre-existing Phase 3B/3C network with adapterId, signaturePrefix, idempotencyParam, and a rogue secret field that must be stripped
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/net_legacy`, {
    networkId: "net_legacy",
    name: "Legacy Network",
    adapterId: "custom_adapter_v2",
    adapterType: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
    isActive: true,
    capabilities: {
      supportsIncentivizedTraffic: true,
      supportsPostback: true,
      supportsInstallAttribution: true,
      supportsServerToServerPolling: false,
      requiresManualReview: false
    },
    allowedDestinationHosts: ["track.legacy.example"],
    parameterMapping: {
      clickIdParam: "sub_id",
      externalTxnParam: "tx_ref",
      statusParam: "st",
      payoutParam: "pay",
      signatureParam: "sig",
      idempotencyParam: "idem_key"
    },
    postbackStatusMapping: { ok: "APPROVED" },
    signatureConfig: {
      algorithm: "HMAC_SHA256",
      queryParamName: "sig",
      secretEnvKey: "CAMPAIGN_POSTBACK_SECRET_ALPHA",
      signingMode: "SORTED_PARAMS",
      encoding: "hex",
      signaturePrefix: "sha256="
    },
    postbackSecretHash: "must_be_stripped_if_present",
    createdAt: new Date(nowMs - 86_400_000)
  });

  // Seed pre-existing Phase 3B campaign config with runtime-critical fields
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_legacy`, {
    configId: "camp_legacy",
    campaignId: "camp_legacy",
    title: "Legacy Offer",
    isActive: true,
    networkId: "net_legacy",
    trackingNetworkRef: "net_legacy",
    verificationMode: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
    trackingBaseUrl: "https://track.legacy.example/click?offer=9",
    rewardTf: 250,
    rewardCents: 200,
    submissionBudget: 50,
    usedSubmissions: 2,
    reservedClickSlots: 1,
    issuedClicksCount: 5,
    rewardBudgetTf: 12_500,
    spentBudgetTf: 500,
    allowedCountries: ["ALL"],
    allowedPlatforms: ["ANDROID"],
    requireDeviceAttestation: false,
    clickCooldownMs: 45_000,
    maxClicksPerUserPerHour: 7,
    clickOverbookingBuffer: 4,
    customRuntimeFlag: "keep_me_intact",
    conversionRequirements: {
      requireUniqueClickId: true,
      autoApproveVerifiedPostback: true,
      requireAdminApproval: false,
      allowMultipleConversions: false,
      attributionWindowHours: 72,
      requireVerifiedCountry: false,
      requireAppCheck: false
    },
    createdAt: new Date(nowMs - 86_400_000)
  });

  // Edit network via Phase 3D (omitting adapterId, signaturePrefix, idempotencyParam)
  const netEditRes = await handleAdminUpsertCampaignNetwork({
    auth: ADMIN_AUTH,
    data: {
      mode: "UPDATE",
      networkId: "net_legacy",
      name: "Legacy Network Renamed",
      adapterType: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
      isActive: true,
      capabilities: { supportsIncentivizedTraffic: true, supportsPostback: true },
      allowedDestinationHosts: ["track.legacy.example"],
      parameterMapping: {
        clickIdParam: "sub_id",
        externalTxnParam: "tx_ref",
        statusParam: "st",
        payoutParam: "pay",
        signatureParam: "sig"
      },
      signatureConfig: {
        algorithm: "HMAC_SHA256",
        queryParamName: "sig",
        secretEnvKey: "CAMPAIGN_POSTBACK_SECRET_ALPHA",
        signingMode: "SORTED_PARAMS",
        encoding: "hex"
      }
    }
  }, deps);

  const savedNet = db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/net_legacy`);
  assert.equal(savedNet.adapterId, "custom_adapter_v2");
  assert.equal(savedNet.signatureConfig.signaturePrefix, "sha256=");
  assert.equal(savedNet.parameterMapping.idempotencyParam, "idem_key");
  assert.equal(savedNet.postbackSecretHash, undefined, "Secret-like legacy field must be stripped");
  assert.equal(netEditRes.network.signatureConfig.signaturePrefix, "sha256=");

  // Edit campaign via Phase 3D enabling requireAppCheck -> must wire requireDeviceAttestation=true & preserve runtime fields!
  await handleAdminUpsertCampaignTrackingConfig({
    auth: ADMIN_AUTH,
    data: {
      mode: "UPDATE",
      campaignId: "camp_legacy",
      title: "Legacy Offer Updated",
      networkId: "net_legacy",
      verificationMode: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
      isActive: true,
      rewardTf: 250,
      submissionBudget: 50,
      rewardBudgetTf: 12_500,
      allowedCountries: ["ALL"],
      allowedPlatforms: ["ANDROID"],
      trackingBaseUrl: "https://track.legacy.example/click?offer=9",
      clickTtlMinutes: 30,
      conversionRequirements: {
        requireUniqueClickId: true,
        autoApproveVerifiedPostback: true,
        requireAdminApproval: false,
        allowMultipleConversions: false,
        attributionWindowHours: 72,
        requireVerifiedCountry: false,
        requireAppCheck: true
      }
    }
  }, deps);

  const savedCfg = db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_legacy`);
  assert.equal(savedCfg.requireDeviceAttestation, true, "requireAppCheck must wire to Phase 3B requireDeviceAttestation");
  assert.equal(savedCfg.conversionRequirements.requireAppCheck, true);
  assert.equal(savedCfg.clickCooldownMs, 45_000, "clickCooldownMs must be preserved");
  assert.equal(savedCfg.maxClicksPerUserPerHour, 7, "maxClicksPerUserPerHour must be preserved");
  assert.equal(savedCfg.clickOverbookingBuffer, 4, "clickOverbookingBuffer must be preserved");
  assert.equal(savedCfg.customRuntimeFlag, "keep_me_intact", "Non-secret custom runtime fields must be preserved");
  assert.equal(savedCfg.usedSubmissions, 2, "Live server counters must be preserved");

  // Phase 3B runtime must reject click WITHOUT App Check now!
  await assert.rejects(
    () => handleCreateCampaignClick({
      auth: { uid: "user_001", token: {} },
      data: { campaignId: "camp_legacy", platform: "ANDROID" }
    }, deps),
    (err) => err.code === "failed-precondition" && /App Check/i.test(err.message)
  );

  // Phase 3B runtime must accept click WITH App Check and honor clickTtlMinutes=30!
  const clickRes = await handleCreateCampaignClick({
    auth: { uid: "user_001", token: {} },
    app: { appId: "1:123:android:abc", token: { sub: "valid" } },
    data: { campaignId: "camp_legacy", platform: "ANDROID" }
  }, deps);
  assert.equal(clickRes.clickId, undefined);
  assert.equal(clickRes.expiresAtMillis, nowMs + 30 * 60 * 1000, "clickTtlMinutes must govern click expiry");
  const legacyClickId = new URL(clickRes.trackingUrl).searchParams.get("sub_id");
  assert.ok(legacyClickId);

  // Verify Phase 3C postback signature still works with preserved signaturePrefix ("sha256=")
  const secretVal = "unit_test_secret_alpha_prefix";
  const postbackQuery = {
    networkId: "net_legacy",
    sub_id: legacyClickId,
    tx_ref: "txn_prefix_01",
    st: "ok",
    pay: "99.99"
  };
  postbackQuery.sig = signSortedParams(postbackQuery, secretVal, "sha256=");

  const pbResMock = makeMockRes();
  await handleCampaignPostback(
    {
      method: "GET",
      query: postbackQuery,
      headers: {},
      body: {},
      rawBody: ""
    },
    pbResMock,
    {
      ...deps,
      secrets: { CAMPAIGN_POSTBACK_SECRET_ALPHA: secretVal }
    }
  );
  assert.equal(pbResMock.statusCode, 200);
  assert.equal(pbResMock.payload.state, CAMPAIGN_CONVERSION_STATES.APPROVED);
  assert.equal(pbResMock.payload.rewardTf, 250);
});

test("HIGH-1.2: requireVerifiedCountry enforces trusted server geo even for ALL countries, clickTtlMinutes respects campaign expiration & attribution bounds, and autoApproveVerifiedPostback=false holds conversion in PENDING", async () => {
  const db = createFullMockFirestore();
  seedAdminAndUser(db);
  const nowMs = 1_750_000_000_000;
  const deps = makeDeps(db, nowMs);

  await handleAdminUpsertCampaignNetwork({
    auth: ADMIN_AUTH,
    data: {
      mode: "CREATE",
      networkId: "net_strict",
      name: "Strict Postback Net",
      adapterType: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
      isActive: true,
      capabilities: { supportsIncentivizedTraffic: true, supportsPostback: true },
      allowedDestinationHosts: ["track.strict.example"],
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
        secretEnvKey: "CAMPAIGN_POSTBACK_SECRET_BETA",
        signingMode: "SORTED_PARAMS",
        encoding: "hex"
      }
    }
  }, deps);

  // Campaign with:
  // - allowedCountries = ["ALL"] + requireVerifiedCountry = true
  // - clickTtlMinutes = 240, attributionWindowHours = 2 (120 min), campaign expires in 90 min -> click expiry must be capped at 90 min!
  // - autoApproveVerifiedPostback = false, requireAdminApproval = false -> MUST hold in PENDING!
  const campaignExpiresAt = nowMs + 90 * 60 * 1000;
  await handleAdminUpsertCampaignTrackingConfig({
    auth: ADMIN_AUTH,
    data: {
      mode: "CREATE",
      campaignId: "camp_strict",
      title: "Strict Offer",
      networkId: "net_strict",
      verificationMode: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
      isActive: true,
      expiresAtMillis: campaignExpiresAt,
      rewardTf: 500,
      submissionBudget: 20,
      rewardBudgetTf: 10_000,
      allowedCountries: ["ALL"],
      allowedPlatforms: ["ANDROID"],
      trackingBaseUrl: "https://track.strict.example/go",
      clickTtlMinutes: 240,
      conversionRequirements: {
        requireUniqueClickId: true,
        autoApproveVerifiedPostback: false,
        requireAdminApproval: false,
        allowMultipleConversions: false,
        attributionWindowHours: 2,
        requireVerifiedCountry: true,
        requireAppCheck: false
      }
    }
  }, deps);

  // 1) Missing server geo header with client-spoofed country MUST fail closed!
  await assert.rejects(
    () => handleCreateCampaignClick({
      auth: { uid: "user_001", token: {} },
      data: { campaignId: "camp_strict", platform: "ANDROID", country: "IN" },
      rawRequest: { headers: {} }
    }, deps),
    (err) => err.code === "failed-precondition" && /verified country/i.test(err.message)
  );

  // 2) Trusted server proxy geo header succeeds and click expiry is bounded by campaignExpiresAt (90 min < 120 min < 240 min)
  const clickRes = await handleCreateCampaignClick({
    auth: { uid: "user_001", token: {} },
    data: { campaignId: "camp_strict", platform: "ANDROID" },
    rawRequest: { headers: { "x-appengine-country": "IN" } }
  }, deps);
  assert.equal(clickRes.clickId, undefined);
  assert.equal(clickRes.expiresAtMillis, campaignExpiresAt);
  const strictClickId = new URL(clickRes.trackingUrl).searchParams.get("click_id");
  assert.ok(strictClickId);

  // 3) Verified APPROVED postback MUST be held in PENDING because autoApproveVerifiedPostback=false
  const secretVal = "strict_beta_secret_key";
  const pbQuery = {
    networkId: "net_strict",
    click_id: strictClickId,
    txn_id: "txn_hold_01",
    status: "approved",
    payout: "4.00"
  };
  pbQuery.sig = signSortedParams(pbQuery, secretVal);

  const pbResMock = makeMockRes();
  await handleCampaignPostback(
    {
      method: "GET",
      query: pbQuery,
      headers: {},
      body: {},
      rawBody: ""
    },
    pbResMock,
    {
      ...deps,
      secrets: { CAMPAIGN_POSTBACK_SECRET_BETA: secretVal }
    }
  );

  assert.equal(pbResMock.statusCode, 200);
  assert.equal(pbResMock.payload.state, CAMPAIGN_CONVERSION_STATES.PENDING);
  assert.equal(pbResMock.payload.rewardCredited, false);
  const heldConversionDoc = db.read(
    `${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS}/${pbResMock.payload.conversionId}`
  );
  assert.equal(heldConversionDoc.statusReason, "AUTO_APPROVAL_DISABLED");
  assert.equal(Number(db.read("users/user_001").balanceCoins || 0), 0, "User wallet must NOT be credited while held in PENDING");

  // 4) Admin reviews and approves -> user receives 500 TF (₹4.00)
  const reviewRes = await handleReviewCampaignConversion({
    auth: ADMIN_AUTH,
    data: {
      conversionId: pbResMock.payload.conversionId,
      action: "APPROVE",
      reason: "Verified manually after auto-approve disabled"
    }
  }, makeDeps(db, nowMs + 60_000));
  assert.equal(reviewRes.state, CAMPAIGN_CONVERSION_STATES.APPROVED);
  assert.equal(db.read("users/user_001").balanceCoins, 500);
  assert.equal(db.read("users/user_001").eligibleCoins, 500);
  assert.equal(db.read("users/user_001").balanceCents, 400);

  // 5) User spends 200 TF (balance now 300 TF), then reversal occurs -> recovered=300 TF, shortfall=200 TF
  const userBeforeRev = db.read("users/user_001");
  db.seed("users/user_001", {
    ...userBeforeRev,
    balanceCoins: 300,
    eligibleCoins: 300,
    balanceCents: 240
  });

  const revRes = await handleReviewCampaignConversion({
    auth: ADMIN_AUTH,
    data: {
      conversionId: pbResMock.payload.conversionId,
      action: "REVERSE",
      reason: "Advertiser chargeback"
    }
  }, makeDeps(db, nowMs + 120_000));
  assert.equal(revRes.state, CAMPAIGN_CONVERSION_STATES.REVERSED);
  assert.equal(db.read("users/user_001").balanceCoins, 0);
  assert.equal(db.read("users/user_001").eligibleCoins, 0);

  // 6) Verify Phase 3D Dashboard reflects exact cross-phase accounting (gross=500, reversed=500, recovered=300, shortfall=200, net=0)
  const dash = await handleAdminGetCampaignTrackingDashboard({
    auth: ADMIN_AUTH,
    data: { dateRange: "ALL" }
  }, makeDeps(db, nowMs + 180_000));

  assert.equal(dash.summary.totalClicks, 1);
  assert.equal(dash.summary.reversedConversions, 1);
  assert.equal(dash.summary.grossCreditedTf, 500);
  assert.equal(dash.summary.reversedTf, 500);
  assert.equal(dash.summary.recoveredTf, 300);
  assert.equal(dash.summary.shortfallTf, 200);
  assert.equal(dash.summary.netCreditedTf, 0);
  assert.equal(dash.summary.netWalletImpactTf, 200);
});

// =========================================================================
// HIGH-2 — FINANCIAL ANALYTICS & TIME-WINDOW CORRECTION
// =========================================================================

test("HIGH-2.1: Credits attributed to verifiedAt/creditedAt, reversals to reversedAt, negative net preserved, IST midnight enforced, partial recovery & duplicate reversal handled", async () => {
  const db = createFullMockFirestore();
  seedAdminAndUser(db);

  // Pick exact IST boundary: 2026-06-15 00:10:00 IST = 2026-06-14 18:40:00 UTC
  const nowMs = Date.UTC(2026, 5, 14, 18, 40, 0);
  const beforeIstMidnightUtc = Date.UTC(2026, 5, 14, 18, 29, 59); // 23:59:59 IST yesterday!
  const afterIstMidnightUtc = Date.UTC(2026, 5, 14, 18, 30, 1);   // 00:00:01 IST today!
  const fortyFiveDaysAgo = nowMs - 45 * 86_400_000;

  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/net_fin`, {
    networkId: "net_fin",
    name: "Finance Net",
    adapterType: "AFFILIATE_POSTBACK",
    isActive: true
  });
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_fin`, {
    configId: "camp_fin",
    campaignId: "camp_fin",
    title: "Finance Offer",
    networkId: "net_fin",
    verificationMode: "AFFILIATE_POSTBACK",
    isActive: true,
    rewardTf: 500
  });

  // Conv A: Credited 45 days ago (outside TODAY and 30D), reversed TODAY (after IST midnight) with partial wallet recovery (300 recovered, 200 shortfall)
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS}/conv_old_rev_today`, {
    conversionId: "conv_old_rev_today",
    campaignId: "camp_fin",
    networkId: "net_fin",
    state: "REVERSED",
    rewardCredited: true,
    rewardTf: 500,
    reversedAmountTf: 500,
    recoveredCoins: 300,
    shortfallCoins: 200,
    createdAt: new Date(fortyFiveDaysAgo),
    verifiedAt: new Date(fortyFiveDaysAgo),
    reversedAt: new Date(afterIstMidnightUtc)
  });

  // Conv B: Credited 1 second BEFORE IST midnight -> MUST NOT be counted in TODAY (IST), MUST be counted in 7D
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS}/conv_yesterday_ist`, {
    conversionId: "conv_yesterday_ist",
    campaignId: "camp_fin",
    networkId: "net_fin",
    state: "APPROVED",
    rewardCredited: true,
    rewardTf: 250,
    createdAt: new Date(beforeIstMidnightUtc),
    verifiedAt: new Date(beforeIstMidnightUtc)
  });

  // Duplicate reversal record sharing the same conversionId -> must deduplicate, never double-subtract!
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS}/conv_old_rev_today_dup`, {
    conversionId: "conv_old_rev_today",
    campaignId: "camp_fin",
    networkId: "net_fin",
    state: "REVERSED",
    rewardCredited: true,
    rewardTf: 500,
    reversedAmountTf: 500,
    recoveredCoins: 300,
    shortfallCoins: 200,
    createdAt: new Date(fortyFiveDaysAgo),
    verifiedAt: new Date(fortyFiveDaysAgo),
    reversedAt: new Date(afterIstMidnightUtc)
  });

  // TODAY (IST): Gross=0, Reversed=500, Recovered=300, Shortfall=200, Net=-500 TF (-₹4.00 = -400 cents)
  const todayDash = await handleAdminGetCampaignTrackingDashboard({
    auth: ADMIN_AUTH,
    data: { dateRange: "TODAY" }
  }, makeDeps(db, nowMs));

  assert.equal(todayDash.timezone, "Asia/Kolkata");
  assert.equal(todayDash.summary.grossCreditedTf, 0);
  assert.equal(todayDash.summary.reversedTf, 500);
  assert.equal(todayDash.summary.recoveredTf, 300);
  assert.equal(todayDash.summary.shortfallTf, 200);
  assert.equal(todayDash.summary.netCreditedTf, -500, "Negative net TF must NOT be clamped to 0");
  assert.equal(todayDash.summary.netCreditedCents, -400);
  assert.equal(signedTfToBalanceCents(-500), -400);
  assert.equal(todayDash.summary.reversedCents, tfToBalanceCents(500));
  assert.equal(todayDash.summary.recoveredCents, tfToBalanceCents(300));
  assert.equal(todayDash.summary.shortfallCents, tfToBalanceCents(200));
  assert.equal(todayDash.summary.netWalletImpactTf, -300);

  // 7D: includes Conv B (+250 gross) and Conv A reversal (-500) -> Net = -250 TF (-200 cents)
  const sevenDayDash = await handleAdminGetCampaignTrackingDashboard({
    auth: ADMIN_AUTH,
    data: { dateRange: "7D" }
  }, makeDeps(db, nowMs));
  assert.equal(sevenDayDash.summary.grossCreditedTf, 250);
  assert.equal(sevenDayDash.summary.reversedTf, 500);
  assert.equal(sevenDayDash.summary.netCreditedTf, -250);
  assert.equal(sevenDayDash.summary.netCreditedCents, -200);

  // ALL: includes Conv A gross (+500), Conv B gross (+250), and Conv A reversal (-500) -> Net = +250 TF (+200 cents)
  const allDash = await handleAdminGetCampaignTrackingDashboard({
    auth: ADMIN_AUTH,
    data: { dateRange: "ALL" }
  }, makeDeps(db, nowMs));
  assert.equal(allDash.summary.grossCreditedTf, 750);
  assert.equal(allDash.summary.reversedTf, 500);
  assert.equal(allDash.summary.netCreditedTf, 250);
  assert.equal(allDash.summary.netCreditedCents, 200);
});

test("HIGH-2.2: Reconciles orphaned campaign/network clicks & conversions 100% with summary totals", async () => {
  const db = createFullMockFirestore();
  seedAdminAndUser(db);
  const nowMs = 1_750_000_000_000;

  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/net_known`, {
    networkId: "net_known",
    name: "Known Net",
    adapterType: "AFFILIATE_POSTBACK",
    isActive: true
  });
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_known`, {
    configId: "camp_known",
    campaignId: "camp_known",
    title: "Known Campaign",
    networkId: "net_known",
    verificationMode: "AFFILIATE_POSTBACK",
    isActive: true,
    rewardTf: 125
  });

  // Known click & conversion
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS}/clk_1`, {
    clickId: "clk_1",
    campaignId: "camp_known",
    networkId: "net_known",
    createdAt: new Date(nowMs - 1000)
  });
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS}/conv_1`, {
    conversionId: "conv_1",
    campaignId: "camp_known",
    networkId: "net_known",
    state: "APPROVED",
    rewardCredited: true,
    rewardTf: 125,
    createdAt: new Date(nowMs - 900),
    verifiedAt: new Date(nowMs - 800)
  });

  // Orphaned click & conversion referencing deleted campaign & deleted network
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS}/clk_orphan`, {
    clickId: "clk_orphan",
    campaignId: "camp_deleted_99",
    networkId: "net_deleted_99",
    createdAt: new Date(nowMs - 700)
  });
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS}/conv_orphan`, {
    conversionId: "conv_orphan",
    campaignId: "camp_deleted_99",
    networkId: "net_deleted_99",
    state: "APPROVED",
    rewardCredited: true,
    rewardTf: 250,
    createdAt: new Date(nowMs - 600),
    verifiedAt: new Date(nowMs - 500)
  });

  const dash = await handleAdminGetCampaignTrackingDashboard({
    auth: ADMIN_AUTH,
    data: { dateRange: "ALL", pageSize: 50 }
  }, makeDeps(db, nowMs));

  assert.equal(dash.summary.orphanedClicksCount, 1);
  assert.equal(dash.summary.orphanedConversionsCount, 1);
  assert.equal(dash.summary.orphanedGrossCreditedTf, 250);
  assert.equal(dash.summary.orphanedCampaignCount, 1);
  assert.equal(dash.summary.orphanedNetworkCount, 1);

  const campRows = dash.campaignPerformance.items;
  const netRows = dash.networkPerformance.items;

  // Verify exact reconciliation between summary and breakdowns!
  assert.equal(campRows.reduce((s, r) => s + r.clicksCount, 0), dash.summary.totalClicks);
  assert.equal(campRows.reduce((s, r) => s + r.grossCreditedTf, 0), dash.summary.grossCreditedTf);
  assert.equal(campRows.reduce((s, r) => s + r.netCreditedTf, 0), dash.summary.netCreditedTf);

  assert.equal(netRows.reduce((s, r) => s + r.clicksCount, 0), dash.summary.totalClicks);
  assert.equal(netRows.reduce((s, r) => s + r.grossCreditedTf, 0), dash.summary.grossCreditedTf);
  assert.equal(netRows.reduce((s, r) => s + r.netCreditedTf, 0), dash.summary.netCreditedTf);

  const orphanCampRow = campRows.find((r) => r.campaignId === "camp_deleted_99");
  assert.ok(orphanCampRow);
  assert.equal(orphanCampRow.isOrphaned, true);

  const orphanNetRow = netRows.find((r) => r.networkId === "net_deleted_99");
  assert.ok(orphanNetRow);
  assert.equal(orphanNetRow.isOrphaned, true);
});

test("HIGH-2.3: Indexed range query + cursor pagination handles >5,000 records cleanly and emits explicit scanLimits truncation metadata", async () => {
  const db = createFullMockFirestore();
  seedAdminAndUser(db);
  const nowMs = 1_750_000_000_000;

  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/net_vol`, {
    networkId: "net_vol",
    name: "High Volume Net",
    adapterType: "AFFILIATE_POSTBACK",
    isActive: true
  });
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_vol`, {
    configId: "camp_vol",
    campaignId: "camp_vol",
    title: "High Volume Campaign",
    networkId: "net_vol",
    verificationMode: "AFFILIATE_POSTBACK",
    isActive: true,
    rewardTf: 125
  });

  // Seed 5,005 recent clicks inside 7D window (plus 20 old clicks outside 7D that indexed query must skip!)
  for (let i = 0; i < 5005; i++) {
    db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS}/clk_${String(i).padStart(5, "0")}`, {
      clickId: `clk_${i}`,
      campaignId: "camp_vol",
      networkId: "net_vol",
      createdAt: new Date(nowMs - (i + 1) * 1000)
    });
  }
  for (let j = 0; j < 20; j++) {
    db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS}/clk_old_${j}`, {
      clickId: `clk_old_${j}`,
      campaignId: "camp_vol",
      networkId: "net_vol",
      createdAt: new Date(nowMs - 40 * 86_400_000 - j * 1000)
    });
  }

  const dash = await handleAdminGetCampaignTrackingDashboard({
    auth: ADMIN_AUTH,
    data: { dateRange: "7D" }
  }, makeDeps(db, nowMs));

  assert.equal(dash.scanLimits.queryStrategy, "INDEXED_TIMESTAMP_RANGE");
  assert.equal(dash.scanLimits.clicksScanLimit, 5000);
  assert.equal(dash.scanLimits.clicksScanned, 5000);
  assert.equal(dash.scanLimits.clicksTruncated, true);
  assert.equal(dash.scanLimits.isTruncated, true);
  assert.equal(dash.summary.totalClicks, 5000);
});

// =========================================================================
// MEDIUM-1 — CAMPAIGN ACTIVATION & NETWORK MUTATION INTEGRITY
// =========================================================================

test("MEDIUM-1.1: Blocks campaign reactivation for disabled/incompatible network, expired schedule, and exhausted budgets", async () => {
  const db = createFullMockFirestore();
  seedAdminAndUser(db);
  const nowMs = 1_750_000_000_000;
  const deps = makeDeps(db, nowMs);

  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/net_m1`, {
    networkId: "net_m1",
    name: "M1 Network",
    adapterType: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
    isActive: true,
    capabilities: { supportsIncentivizedTraffic: true, supportsPostback: true },
    allowedDestinationHosts: ["track.m1.example"],
    signatureConfig: {
      algorithm: "HMAC_SHA256",
      queryParamName: "sig",
      secretEnvKey: "CAMPAIGN_POSTBACK_SECRET",
      signingMode: "SORTED_PARAMS",
      encoding: "hex"
    }
  });

  const baseCampaign = {
    configId: "camp_m1",
    campaignId: "camp_m1",
    title: "M1 Campaign",
    isActive: false,
    networkId: "net_m1",
    verificationMode: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
    trackingBaseUrl: "https://track.m1.example/offer",
    rewardTf: 250,
    submissionBudget: 10,
    usedSubmissions: 0,
    rewardBudgetTf: 2500,
    spentBudgetTf: 0,
    allowedCountries: ["IN"],
    allowedPlatforms: ["ANDROID"]
  };

  // Case A: Expired campaign cannot be reactivated
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_m1`, {
    ...baseCampaign,
    expiresAtMillis: nowMs - 10_000
  });
  await assert.rejects(
    () => handleAdminSetCampaignTrackingStatus({ auth: ADMIN_AUTH, data: { campaignId: "camp_m1", isActive: true } }, deps),
    (err) => err.code === "failed-precondition" && /expired/i.test(err.message)
  );

  // Case B: Exhausted submission budget cannot be reactivated
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_m1`, {
    ...baseCampaign,
    usedSubmissions: 10
  });
  await assert.rejects(
    () => handleAdminSetCampaignTrackingStatus({ auth: ADMIN_AUTH, data: { campaignId: "camp_m1", isActive: true } }, deps),
    (err) => err.code === "failed-precondition" && /submission budget/i.test(err.message)
  );

  // Case C: Exhausted reward budget cannot be reactivated
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_m1`, {
    ...baseCampaign,
    spentBudgetTf: 2400 // remaining 100 < rewardTf 250
  });
  await assert.rejects(
    () => handleAdminSetCampaignTrackingStatus({ auth: ADMIN_AUTH, data: { campaignId: "camp_m1", isActive: true } }, deps),
    (err) => err.code === "failed-precondition" && /reward budget/i.test(err.message)
  );

  // Case D: Network with supportsIncentivizedTraffic=false blocks activation
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_m1`, { ...baseCampaign });
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/net_m1`, {
    ...db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/net_m1`),
    capabilities: { supportsIncentivizedTraffic: false, supportsPostback: true }
  });
  await assert.rejects(
    () => handleAdminSetCampaignTrackingStatus({ auth: ADMIN_AUTH, data: { campaignId: "camp_m1", isActive: true } }, deps),
    (err) => err.code === "failed-precondition" && /incentivized traffic/i.test(err.message)
  );

  // Case E: Network adapter mismatch blocks activation
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/net_m1`, {
    ...db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/net_m1`),
    adapterType: CAMPAIGN_VERIFICATION_MODES.TELEGRAM_BOT_API,
    capabilities: { supportsIncentivizedTraffic: true, supportsPostback: false }
  });
  await assert.rejects(
    () => handleAdminSetCampaignTrackingStatus({ auth: ADMIN_AUTH, data: { campaignId: "camp_m1", isActive: true } }, deps),
    (err) => err.code === "failed-precondition" && /incompatible/i.test(err.message)
  );
});

test("MEDIUM-1.2: Prevents network edits from silently invalidating active dependent campaigns & validates date ordering and clearing", async () => {
  const db = createFullMockFirestore();
  seedAdminAndUser(db);
  const nowMs = 1_750_000_000_000;
  const deps = makeDeps(db, nowMs);

  await handleAdminUpsertCampaignNetwork({
    auth: ADMIN_AUTH,
    data: {
      mode: "CREATE",
      networkId: "net_dep",
      name: "Dependent Net",
      adapterType: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
      isActive: true,
      capabilities: { supportsIncentivizedTraffic: true, supportsPostback: true },
      allowedDestinationHosts: ["go.dep.example"],
      signatureConfig: {
        algorithm: "HMAC_SHA256",
        queryParamName: "sig",
        secretEnvKey: "CAMPAIGN_POSTBACK_SECRET",
        signingMode: "SORTED_PARAMS",
        encoding: "hex"
      }
    }
  }, deps);

  // Reject inverted startDate > endDate
  await assert.rejects(
    () => handleAdminUpsertCampaignTrackingConfig({
      auth: ADMIN_AUTH,
      data: {
        mode: "CREATE",
        campaignId: "camp_bad_dates",
        title: "Bad Dates",
        networkId: "net_dep",
        verificationMode: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
        isActive: false,
        startDate: "2026-12-31",
        endDate: "2026-01-01",
        rewardTf: 125,
        trackingBaseUrl: "https://go.dep.example/o/1"
      }
    }, deps),
    (err) => err.code === "invalid-argument" && /endDate/i.test(err.message)
  );

  // Create valid active campaign with an endDate
  await handleAdminUpsertCampaignTrackingConfig({
    auth: ADMIN_AUTH,
    data: {
      mode: "CREATE",
      campaignId: "camp_dep",
      title: "Dependent Active Campaign",
      networkId: "net_dep",
      verificationMode: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
      isActive: true,
      startDate: "2025-01-01",
      endDate: "2027-12-31",
      rewardTf: 125,
      trackingBaseUrl: "https://go.dep.example/o/1"
    }
  }, deps);
  const createdCfg = db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_dep`);
  assert.ok(createdCfg.expiresAtMillis > nowMs, "endDate string must populate expiresAtMillis");

  // Clearing endDate to "" + expiresAtMillis to 0 MUST clear expiration completely (never leave stale expiresAtMillis)
  await handleAdminUpsertCampaignTrackingConfig({
    auth: ADMIN_AUTH,
    data: {
      mode: "UPDATE",
      campaignId: "camp_dep",
      title: "Dependent Active Campaign",
      networkId: "net_dep",
      verificationMode: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
      isActive: true,
      startDate: "2025-01-01",
      endDate: "",
      expiresAtMillis: 0,
      rewardTf: 125,
      trackingBaseUrl: "https://go.dep.example/o/1"
    }
  }, deps);
  const clearedCfg = db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_dep`);
  assert.equal(clearedCfg.endDate, "");
  assert.equal(clearedCfg.expiresAtMillis, 0);
  assert.equal(clearedCfg.expiresAt ?? null, null);

  // Editing net_dep to remove "go.dep.example" while camp_dep is active MUST fail!
  await assert.rejects(
    () => handleAdminUpsertCampaignNetwork({
      auth: ADMIN_AUTH,
      data: {
        mode: "UPDATE",
        networkId: "net_dep",
        name: "Dependent Net",
        adapterType: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
        isActive: true,
        capabilities: { supportsIncentivizedTraffic: true, supportsPostback: true },
        allowedDestinationHosts: ["other.dep.example"],
        signatureConfig: {
          algorithm: "HMAC_SHA256",
          queryParamName: "sig",
          secretEnvKey: "CAMPAIGN_POSTBACK_SECRET",
          signingMode: "SORTED_PARAMS",
          encoding: "hex"
        }
      }
    }, deps),
    (err) => err.code === "failed-precondition" && /camp_dep/.test(err.message)
  );

  // Editing net_dep to disable supportsIncentivizedTraffic while camp_dep is active MUST fail!
  await assert.rejects(
    () => handleAdminUpsertCampaignNetwork({
      auth: ADMIN_AUTH,
      data: {
        mode: "UPDATE",
        networkId: "net_dep",
        name: "Dependent Net",
        adapterType: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
        isActive: true,
        capabilities: { supportsIncentivizedTraffic: false, supportsPostback: true },
        allowedDestinationHosts: ["go.dep.example"],
        signatureConfig: {
          algorithm: "HMAC_SHA256",
          queryParamName: "sig",
          secretEnvKey: "CAMPAIGN_POSTBACK_SECRET",
          signingMode: "SORTED_PARAMS",
          encoding: "hex"
        }
      }
    }, deps),
    (err) => err.code === "failed-precondition" && /camp_dep/.test(err.message)
  );

  // Pause camp_dep -> now network edit succeeds!
  await handleAdminSetCampaignTrackingStatus({
    auth: ADMIN_AUTH,
    data: { campaignId: "camp_dep", isActive: false }
  }, deps);
  const updatedNet = await handleAdminUpsertCampaignNetwork({
    auth: ADMIN_AUTH,
    data: {
      mode: "UPDATE",
      networkId: "net_dep",
      name: "Dependent Net",
      adapterType: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
      isActive: true,
      capabilities: { supportsIncentivizedTraffic: false, supportsPostback: true },
      allowedDestinationHosts: ["other.dep.example"],
      signatureConfig: {
        algorithm: "HMAC_SHA256",
        queryParamName: "sig",
        secretEnvKey: "CAMPAIGN_POSTBACK_SECRET",
        signingMode: "SORTED_PARAMS",
        encoding: "hex"
      }
    }
  }, deps);
  assert.equal(updatedNet.network.capabilities.supportsIncentivizedTraffic, false);
});

// =========================================================================
// MEDIUM-2 — EXACT NETWORK LOOKUP & 2000-CHAR URL PARITY
// =========================================================================

test("MEDIUM-2: Exact networkId lookup bypasses pagination page 1 & tracking URL accepts 2000 chars and rejects 2001 chars", async () => {
  const db = createFullMockFirestore();
  seedAdminAndUser(db);
  const nowMs = 1_750_000_000_000;
  const deps = makeDeps(db, nowMs);

  // Seed 25 networks so net_24 is strictly on page 2 (pageSize=20)
  for (let i = 0; i < 25; i++) {
    const id = `net_${String(i).padStart(2, "0")}`;
    db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/${id}`, {
      networkId: id,
      name: `Network ${i}`,
      adapterType: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
      isActive: true,
      capabilities: { supportsIncentivizedTraffic: true, supportsPostback: true },
      allowedDestinationHosts: ["track.longurl.example"],
      signatureConfig: {
        algorithm: "HMAC_SHA256",
        queryParamName: "sig",
        secretEnvKey: ALLOWED_CAMPAIGN_SECRET_KEYS[0],
        signingMode: "SORTED_PARAMS",
        encoding: "hex"
      },
      updatedAt: new Date(nowMs - i * 1000)
    });
  }

  // Page 1 only has 20 networks (does NOT contain net_24)
  const page1 = await handleAdminListCampaignNetworks({
    auth: ADMIN_AUTH,
    data: { pageSize: 20 }
  }, deps);
  assert.equal(page1.items.length, 20);
  assert.equal(page1.hasMore, true);
  assert.equal(page1.items.some((n) => n.networkId === "net_24"), false);

  // Exact lookup retrieves net_24 directly in O(1)
  const exactRes = await handleAdminListCampaignNetworks({
    auth: ADMIN_AUTH,
    data: { pageSize: 1, networkId: "net_24" }
  }, deps);
  assert.equal(exactRes.items.length, 1);
  assert.equal(exactRes.items[0].networkId, "net_24");

  // 2000-char URL accepted; 2001-char URL rejected
  const prefix = "https://track.longurl.example/click?p=";
  const url2000 = prefix + "a".repeat(MAX_DESTINATION_URL_LENGTH - prefix.length);
  const url2001 = prefix + "a".repeat(MAX_DESTINATION_URL_LENGTH - prefix.length + 1);
  assert.equal(url2000.length, 2000);
  assert.equal(url2001.length, 2001);

  const okRes = await handleAdminUpsertCampaignTrackingConfig({
    auth: ADMIN_AUTH,
    data: {
      mode: "CREATE",
      campaignId: "camp_url_2000",
      title: "Max Length URL Offer",
      networkId: "net_24",
      verificationMode: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
      isActive: true,
      rewardTf: 125,
      trackingBaseUrl: url2000
    }
  }, deps);
  assert.equal(okRes.config.trackingBaseUrl.length, 2000);

  await assert.rejects(
    () => handleAdminUpsertCampaignTrackingConfig({
      auth: ADMIN_AUTH,
      data: {
        mode: "CREATE",
        campaignId: "camp_url_2001",
        title: "Too Long URL Offer",
        networkId: "net_24",
        verificationMode: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
        isActive: true,
        rewardTf: 125,
        trackingBaseUrl: url2001
      }
    }, deps),
    (err) => err.code === "invalid-argument"
  );
});

// =========================================================================
// LOW-2 — REJECTION EVENT-TIME ANALYTICS & LOW-1 ACTIVE DEPENDENT COUNT
// =========================================================================

test("LOW-2.1: Cross-period rejection attributed strictly to rejectedAt without duplicate counting while preserving verifiedAt/reversedAt financial accounting", async () => {
  const db = createFullMockFirestore();
  seedAdminAndUser(db);

  // Reference "now": 2026-06-15 12:00:00 IST (06:30:00 UTC)
  const nowMs = Date.UTC(2026, 5, 15, 6, 30, 0);
  const periodACreatedMs = Date.UTC(2026, 5, 5, 8, 0, 0); // 10 days ago (Period A)
  const periodBRejectedMs = Date.UTC(2026, 5, 15, 4, 0, 0); // Today in IST (Period B)
  const sameDayCreditMs = Date.UTC(2026, 5, 15, 5, 0, 0); // Today in IST
  const deps = makeDeps(db, nowMs);

  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/net_rej`, {
    networkId: "net_rej",
    name: "Rejection Audit Network",
    adapterType: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
    isActive: true,
    capabilities: { supportsIncentivizedTraffic: true, supportsPostback: true }
  });
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_rej`, {
    configId: "camp_rej",
    campaignId: "camp_rej",
    title: "Rejection Event-Time Campaign",
    networkId: "net_rej",
    verificationMode: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
    isActive: true,
    rewardTf: 250
  });

  // Conversion 1: Created in Period A (June 5), REJECTED in Period B (June 15)
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS}/conv_cross_reject`, {
    conversionId: "conv_cross_reject",
    campaignId: "camp_rej",
    networkId: "net_rej",
    state: CAMPAIGN_CONVERSION_STATES.REJECTED,
    rewardTf: 250,
    rewardCredited: false,
    createdAt: new Date(periodACreatedMs),
    rejectedAt: new Date(periodBRejectedMs),
    updatedAt: new Date(periodBRejectedMs)
  });

  // Conversion 2: Created and rejected in Period B (both createdAt and rejectedAt match TODAY range — MUST NOT double-count!)
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS}/conv_same_day_reject`, {
    conversionId: "conv_same_day_reject",
    campaignId: "camp_rej",
    networkId: "net_rej",
    state: CAMPAIGN_CONVERSION_STATES.REJECTED,
    rewardTf: 250,
    rewardCredited: false,
    createdAt: new Date(periodBRejectedMs - 30 * 60 * 1000),
    rejectedAt: new Date(periodBRejectedMs),
    updatedAt: new Date(periodBRejectedMs)
  });

  // Conversion 3: Created in Period A, APPROVED/verified in Period B (financial credit event-time check)
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS}/conv_cross_credit`, {
    conversionId: "conv_cross_credit",
    campaignId: "camp_rej",
    networkId: "net_rej",
    state: CAMPAIGN_CONVERSION_STATES.APPROVED,
    rewardTf: 250,
    rewardCredited: true,
    createdAt: new Date(periodACreatedMs),
    verifiedAt: new Date(sameDayCreditMs),
    updatedAt: new Date(sameDayCreditMs)
  });

  // Conversion 4: Credited in Period A, REVERSED in Period B (cross-period reversal preserved)
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS}/conv_cross_rev`, {
    conversionId: "conv_cross_rev",
    campaignId: "camp_rej",
    networkId: "net_rej",
    state: CAMPAIGN_CONVERSION_STATES.REVERSED,
    rewardTf: 500,
    rewardCredited: true,
    recoveredCoins: 300,
    shortfallCoins: 200,
    createdAt: new Date(periodACreatedMs),
    verifiedAt: new Date(periodACreatedMs),
    reversedAt: new Date(sameDayCreditMs),
    updatedAt: new Date(sameDayCreditMs)
  });

  // 1) TODAY (Period B): must discover conv_cross_reject via rejectedAt range query, count 2 rejections total (no dup!),
  //    plus 1 approved credit (250 TF) and 1 reversal (500 TF reversed, 300 recovered, 200 shortfall -> net -250 TF, wallet impact -50 TF)
  const todayDash = await handleAdminGetCampaignTrackingDashboard({
    auth: ADMIN_AUTH,
    data: { dateRange: "TODAY" }
  }, deps);
  assert.equal(todayDash.scanLimits.queryStrategy, "INDEXED_TIMESTAMP_RANGE");
  assert.equal(todayDash.summary.rejectedConversions, 2);
  assert.equal(todayDash.summary.approvedConversions, 1);
  assert.equal(todayDash.summary.reversedConversions, 1);
  assert.equal(todayDash.summary.grossCreditedTf, 250);
  assert.equal(todayDash.summary.reversedTf, 500);
  assert.equal(todayDash.summary.recoveredTf, 300);
  assert.equal(todayDash.summary.shortfallTf, 200);
  assert.equal(todayDash.summary.netCreditedTf, -250);
  assert.equal(todayDash.summary.netWalletImpactTf, -50);
  assert.equal(todayDash.campaignPerformance.items[0].rejectedConversions, 2);
  assert.equal(todayDash.networkPerformance.items[0].rejectedConversions, 2);

  // 2) Period A custom window (June 5): conv_cross_reject was created on June 5 but rejected on June 15 -> 0 rejections in Period A!
  const periodADash = await handleAdminGetCampaignTrackingDashboard({
    auth: ADMIN_AUTH,
    data: {
      dateRange: "CUSTOM",
      startAtMillis: Date.UTC(2026, 5, 5, 0, 0, 0),
      endAtMillis: Date.UTC(2026, 5, 5, 23, 59, 59, 999)
    }
  }, deps);
  assert.equal(periodADash.summary.rejectedConversions, 0, "Rejection must NOT be attributed to createdAt in Period A");
  assert.equal(periodADash.summary.grossCreditedTf, 500, "Period A credit from conv_cross_rev remains attributed to verifiedAt");
  assert.equal(periodADash.summary.reversedTf, 0, "Reversal occurred in Period B, not Period A");

  // 3) LAST_30_DAYS (covers both Period A and Period B): strictly 2 rejections total (zero double-counting across queries)
  const thirtyDayDash = await handleAdminGetCampaignTrackingDashboard({
    auth: ADMIN_AUTH,
    data: { dateRange: "30D" }
  }, deps);
  assert.equal(thirtyDayDash.summary.rejectedConversions, 2);
  assert.equal(thirtyDayDash.summary.grossCreditedTf, 750);
  assert.equal(thirtyDayDash.summary.reversedTf, 500);
  assert.equal(thirtyDayDash.summary.netCreditedTf, 250);
  assert.equal(thirtyDayDash.summary.netWalletImpactTf, 450);
});

test("LOW-2.2 & LOW-1: Missing rejectedAt falls back to updatedAt then createdAt; network list exposes activeCampaignsCount while kill-switch preserves dependent campaigns", async () => {
  const db = createFullMockFirestore();
  seedAdminAndUser(db);

  const nowMs = Date.UTC(2026, 5, 15, 6, 30, 0);
  const periodAMs = Date.UTC(2026, 5, 5, 8, 0, 0);
  const periodBMs = Date.UTC(2026, 5, 15, 4, 0, 0);
  const deps = makeDeps(db, nowMs);

  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/net_ks`, {
    networkId: "net_ks",
    name: "Kill-Switch Network",
    adapterType: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
    isActive: true,
    capabilities: { supportsIncentivizedTraffic: true, supportsPostback: true }
  });
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_ks_1`, {
    configId: "camp_ks_1",
    campaignId: "camp_ks_1",
    title: "Active Dependent 1",
    networkId: "net_ks",
    verificationMode: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
    isActive: true,
    rewardTf: 125
  });
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_ks_2`, {
    configId: "camp_ks_2",
    campaignId: "camp_ks_2",
    title: "Active Dependent 2",
    networkId: "net_ks",
    verificationMode: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
    isActive: true,
    rewardTf: 125
  });
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_ks_paused`, {
    configId: "camp_ks_paused",
    campaignId: "camp_ks_paused",
    title: "Paused Dependent",
    networkId: "net_ks",
    verificationMode: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
    isActive: false,
    rewardTf: 125
  });

  // Direct aggregator check for missing rejectedAt fallbacks:
  // 1) Missing rejectedAt -> falls back to updatedAt (Period B)
  // 2) Missing rejectedAt & updatedAt -> falls back to createdAt (Period A)
  const conversions = [
    {
      conversionId: "conv_fallback_updated",
      campaignId: "camp_ks_1",
      networkId: "net_ks",
      state: CAMPAIGN_CONVERSION_STATES.REJECTED,
      rewardTf: 125,
      rewardCredited: false,
      createdAt: new Date(periodAMs),
      updatedAt: new Date(periodBMs)
    },
    {
      conversionId: "conv_fallback_created",
      campaignId: "camp_ks_1",
      networkId: "net_ks",
      state: CAMPAIGN_CONVERSION_STATES.REJECTED,
      rewardTf: 125,
      rewardCredited: false,
      createdAt: new Date(periodAMs)
    }
  ];

  const todayWindow = {
    dateRange: "TODAY",
    startAtMillis: Date.UTC(2026, 5, 14, 18, 30, 0),
    endAtMillis: nowMs,
    timezone: "Asia/Kolkata"
  };
  const periodAWindow = {
    dateRange: "CUSTOM",
    startAtMillis: Date.UTC(2026, 5, 5, 0, 0, 0),
    endAtMillis: Date.UTC(2026, 5, 5, 23, 59, 59, 999),
    timezone: "Asia/Kolkata"
  };

  const todayAgg = computeCampaignTrackingAnalytics({
    campaigns: [db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_ks_1`)],
    networks: [db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/net_ks`)],
    clicks: [],
    conversions,
    window: todayWindow
  });
  assert.equal(todayAgg.summary.rejectedConversions, 1, "Missing rejectedAt falls back to updatedAt in Period B");
  assert.equal(todayAgg.summary.grossCreditedTf, 0);
  assert.equal(todayAgg.summary.reversedTf, 0);

  const periodAAgg = computeCampaignTrackingAnalytics({
    campaigns: [db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_ks_1`)],
    networks: [db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/net_ks`)],
    clicks: [],
    conversions,
    window: periodAWindow
  });
  assert.equal(periodAAgg.summary.rejectedConversions, 1, "Missing rejectedAt & updatedAt falls back to createdAt in Period A");

  // LOW-1: adminListCampaignNetworks reports activeCampaignsCount = 2 (ignores paused campaign)
  const listed = await handleAdminListCampaignNetworks({ auth: ADMIN_AUTH, data: {} }, deps);
  assert.equal(listed.items[0].activeCampaignsCount, 2);
  const exact = await handleAdminListCampaignNetworks({ auth: ADMIN_AUTH, data: { networkId: "net_ks" } }, deps);
  assert.equal(exact.items[0].activeCampaignsCount, 2);

  // Admin can immediately disable network (kill-switch preserved) without silently mutating dependent campaigns
  const toggleRes = await handleAdminSetCampaignNetworkStatus({
    auth: ADMIN_AUTH,
    data: { networkId: "net_ks", isActive: false }
  }, deps);
  assert.equal(toggleRes.network.isActive, false);
  assert.equal(
    db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_ks_1`).isActive,
    true,
    "Dependent campaigns must NOT be silently deactivated when network kill-switch is toggled"
  );
  assert.equal(
    db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_ks_2`).isActive,
    true
  );
});

