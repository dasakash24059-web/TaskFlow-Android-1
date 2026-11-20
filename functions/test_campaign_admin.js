const assert = require("node:assert/strict");
const { test } = require("node:test");
const { _campaignAdminHandlers } = require("./index.js");
const {
  CAMPAIGN_TRACKING_COLLECTIONS,
  ALLOWED_CAMPAIGN_SECRET_KEYS,
  CAMPAIGN_VERIFICATION_MODES,
  CAMPAIGN_CONVERSION_STATES,
  tfToBalanceCents
} = require("./campaign_tracking.js");

const {
  handleAdminListCampaignNetworks,
  handleAdminUpsertCampaignNetwork,
  handleAdminSetCampaignNetworkStatus,
  handleAdminListCampaignTrackingConfigs,
  handleAdminUpsertCampaignTrackingConfig,
  handleAdminSetCampaignTrackingStatus,
  handleAdminGetCampaignTrackingDashboard
} = _campaignAdminHandlers;

function createMockFirestore() {
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

  function makeDocRef(path) {
    return {
      path,
      id: path.split("/").pop(),
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

  function makeCollectionRef(path) {
    const getDocs = async (limitVal = Infinity) => {
      const docs = [];
      for (const [k, v] of store.entries()) {
        if (!k.startsWith(`${path}/`)) continue;
        const rel = k.slice(path.length + 1);
        if (rel.includes("/")) continue;
        docs.push({
          id: rel,
          ref: makeDocRef(k),
          exists: true,
          data: () => clone(v.data)
        });
        if (docs.length >= limitVal) break;
      }
      return { docs };
    };
    return {
      path,
      doc(docId) {
        return makeDocRef(`${path}/${docId || `auto_${++autoId}`}`);
      },
      limit(n) {
        return { get: () => getDocs(n) };
      },
      get: () => getDocs(Infinity)
    };
  }

  async function runTransaction(fn) {
    const writes = [];
    const tx = {
      async get(ref) {
        return ref.get();
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
    listByPrefix(prefix) {
      const out = [];
      for (const [k, v] of store.entries()) {
        if (k.startsWith(prefix)) out.push({ path: k, data: clone(v.data) });
      }
      return out;
    }
  };
}

function makeDeps(db, nowMs = 1_750_000_000_000) {
  return {
    db,
    now: () => nowMs,
    serverTimestamp: () => ({ __isServerTimestamp: true })
  };
}

function seedAdmin(db, uid = "admin_001") {
  db.seed(`admins/${uid}`, { userId: uid, email: "ops-admin@example.com" });
}

const ADMIN_REQ_AUTH = {
  uid: "admin_001",
  token: { email: "ops-admin@example.com", email_verified: true }
};

const NON_ADMIN_REQ_AUTH = {
  uid: "user_regular",
  token: { email: "user@example.com", email_verified: true }
};

test("1. Rejects unauthenticated and non-admin requests across all 7 Phase 3D endpoints", async () => {
  const db = createMockFirestore();
  seedAdmin(db);
  db.seed("users/user_regular", { userId: "user_regular", role: "admin" }); // spoofed role in /users must NOT grant admin!
  const deps = makeDeps(db);

  const handlers = [
    [handleAdminListCampaignNetworks, {}],
    [handleAdminUpsertCampaignNetwork, { networkId: "net_x", name: "X" }],
    [handleAdminSetCampaignNetworkStatus, { networkId: "net_x", isActive: false }],
    [handleAdminListCampaignTrackingConfigs, {}],
    [handleAdminUpsertCampaignTrackingConfig, { campaignId: "camp_x", networkId: "net_x" }],
    [handleAdminSetCampaignTrackingStatus, { campaignId: "camp_x", isActive: false }],
    [handleAdminGetCampaignTrackingDashboard, { dateRange: "30D" }]
  ];

  for (const [fn, payload] of handlers) {
    await assert.rejects(
      () => fn({ auth: null, data: payload }, deps),
      (err) => err.code === "unauthenticated"
    );
    await assert.rejects(
      () => fn({ auth: NON_ADMIN_REQ_AUTH, data: payload }, deps),
      (err) => err.code === "permission-denied"
    );
  }
});

test("2. Admin network creation, editing, status toggle, audit logging, and secret-free responses", async () => {
  const db = createMockFirestore();
  seedAdmin(db);
  const deps = makeDeps(db);

  // Create valid AFFILIATE_POSTBACK network
  const createRes = await handleAdminUpsertCampaignNetwork(
    {
      auth: ADMIN_REQ_AUTH,
      data: {
        mode: "CREATE",
        networkId: "net_alpha",
        name: "Alpha Affiliate Network",
        adapterType: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
        isActive: true,
        capabilities: {
          supportsIncentivizedTraffic: true,
          supportsPostback: true,
          supportsInstallAttribution: true
        },
        allowedDestinationHosts: ["offer.alpha.example.com"],
        parameterMapping: {
          clickIdParam: "aff_sub",
          externalTxnParam: "txn_id",
          statusParam: "status",
          payoutParam: "payout",
          signatureParam: "sig"
        },
        signatureConfig: {
          algorithm: "HMAC_SHA256",
          signingMode: "SORTED_PARAMS",
          encoding: "hex",
          queryParamName: "sig",
          secretEnvKey: "CAMPAIGN_POSTBACK_SECRET_ALPHA"
        }
      }
    },
    deps
  );

  assert.equal(createRes.success, true);
  assert.equal(createRes.isNew, true);
  assert.equal(createRes.network.networkId, "net_alpha");
  assert.equal(createRes.network.signatureConfig.secretEnvKey, "CAMPAIGN_POSTBACK_SECRET_ALPHA");

  // Duplicate CREATE fails with already-exists
  await assert.rejects(
    () =>
      handleAdminUpsertCampaignNetwork(
        {
          auth: ADMIN_REQ_AUTH,
          data: {
            mode: "CREATE",
            networkId: "net_alpha",
            name: "Dup",
            adapterType: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
            capabilities: { supportsPostback: true },
            signatureConfig: {
              algorithm: "HMAC_SHA256",
              signingMode: "SORTED_PARAMS",
              secretEnvKey: "CAMPAIGN_POSTBACK_SECRET_ALPHA"
            }
          }
        },
        deps
      ),
    (err) => err.code === "already-exists"
  );

  // Edit via UPDATE
  const editRes = await handleAdminUpsertCampaignNetwork(
    {
      auth: ADMIN_REQ_AUTH,
      data: {
        mode: "UPDATE",
        networkId: "net_alpha",
        name: "Alpha Affiliate v2",
        adapterType: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
        isActive: true,
        capabilities: {
          supportsIncentivizedTraffic: true,
          supportsPostback: true,
          supportsInstallAttribution: true
        },
        allowedDestinationHosts: ["offer.alpha.example.com", "go.alpha.example.com"],
        parameterMapping: { clickIdParam: "subid" },
        signatureConfig: {
          algorithm: "HMAC_SHA256",
          signingMode: "CANONICAL_FIELDS",
          signedFields: ["subid", "txn_id", "status"],
          fieldSeparator: "|",
          encoding: "hex",
          secretEnvKey: "CAMPAIGN_POSTBACK_SECRET_ALPHA"
        }
      }
    },
    deps
  );
  assert.equal(editRes.network.name, "Alpha Affiliate v2");
  assert.equal(editRes.network.signatureConfig.signingMode, "CANONICAL_FIELDS");

  // Disable network via status toggle
  const toggleRes = await handleAdminSetCampaignNetworkStatus(
    { auth: ADMIN_REQ_AUTH, data: { networkId: "net_alpha", isActive: false } },
    deps
  );
  assert.equal(toggleRes.network.isActive, false);
  assert.equal(db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/net_alpha`).isActive, false);

  // Audit logs written for create + update + disable
  const logs = db.listByPrefix("admin_logs/");
  assert.equal(logs.length, 3);
  assert.ok(logs.every((l) => l.data.actionType === "CAMPAIGN_NETWORK" && l.data.adminId === "admin_001"));
});

test("3. Enforces secret key allowlist, rejects raw secret fields, and rejects invalid signature configs", async () => {
  const db = createMockFirestore();
  seedAdmin(db);
  const deps = makeDeps(db);

  const basePayload = {
    mode: "CREATE",
    networkId: "net_bad",
    name: "Bad Net",
    adapterType: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
    capabilities: { supportsPostback: true, supportsIncentivizedTraffic: true },
    parameterMapping: { clickIdParam: "click_id" },
    signatureConfig: {
      algorithm: "HMAC_SHA256",
      signingMode: "SORTED_PARAMS",
      secretEnvKey: "CAMPAIGN_POSTBACK_SECRET"
    }
  };

  // Unregistered secretEnvKey (e.g., TELEGRAM_BOT_TOKEN or arbitrary) rejected
  await assert.rejects(
    () =>
      handleAdminUpsertCampaignNetwork(
        {
          auth: ADMIN_REQ_AUTH,
          data: {
            ...basePayload,
            signatureConfig: { ...basePayload.signatureConfig, secretEnvKey: "TELEGRAM_BOT_TOKEN" }
          }
        },
        deps
      ),
    (err) => err.code === "invalid-argument"
  );

  // Raw secret value in payload rejected
  await assert.rejects(
    () =>
      handleAdminUpsertCampaignNetwork(
        {
          auth: ADMIN_REQ_AUTH,
          data: { ...basePayload, postbackSecret: "plaintext-secret-value" }
        },
        deps
      ),
    (err) => err.code === "invalid-argument"
  );

  // NONE algorithm on AFFILIATE_POSTBACK rejected
  await assert.rejects(
    () =>
      handleAdminUpsertCampaignNetwork(
        {
          auth: ADMIN_REQ_AUTH,
          data: {
            ...basePayload,
            signatureConfig: { algorithm: "NONE" }
          }
        },
        deps
      ),
    (err) => err.code === "invalid-argument"
  );

  // Unsupported signature algorithm rejected
  await assert.rejects(
    () =>
      handleAdminUpsertCampaignNetwork(
        {
          auth: ADMIN_REQ_AUTH,
          data: {
            ...basePayload,
            signatureConfig: { algorithm: "RSA_SHA512", secretEnvKey: "CAMPAIGN_POSTBACK_SECRET" }
          }
        },
        deps
      ),
    (err) => err.code === "invalid-argument"
  );

  // Unsupported canonicalization signingMode rejected
  await assert.rejects(
    () =>
      handleAdminUpsertCampaignNetwork(
        {
          auth: ADMIN_REQ_AUTH,
          data: {
            ...basePayload,
            signatureConfig: {
              algorithm: "HMAC_SHA256",
              signingMode: "ARBITRARY_CUSTOM_FORMAT",
              secretEnvKey: "CAMPAIGN_POSTBACK_SECRET"
            }
          }
        },
        deps
      ),
    (err) => err.code === "invalid-argument"
  );

  // CANONICAL_FIELDS without signedFields rejected
  await assert.rejects(
    () =>
      handleAdminUpsertCampaignNetwork(
        {
          auth: ADMIN_REQ_AUTH,
          data: {
            ...basePayload,
            signatureConfig: {
              algorithm: "HMAC_SHA256",
              signingMode: "CANONICAL_FIELDS",
              signedFields: [],
              secretEnvKey: "CAMPAIGN_POSTBACK_SECRET"
            }
          }
        },
        deps
      ),
    (err) => err.code === "invalid-argument"
  );

  // All 4 registered secret keys are accepted
  for (const [idx, keyName] of ALLOWED_CAMPAIGN_SECRET_KEYS.entries()) {
    const res = await handleAdminUpsertCampaignNetwork(
      {
        auth: ADMIN_REQ_AUTH,
        data: {
          ...basePayload,
          networkId: `net_reg_${idx}`,
          name: `Registered ${idx}`,
          signatureConfig: {
            algorithm: "HMAC_SHA256",
            signingMode: "SORTED_PARAMS",
            secretEnvKey: keyName
          }
        }
      },
      deps
    );
    assert.equal(res.network.signatureConfig.secretEnvKey, keyName);
  }
});

test("4. Campaign config creation, update, pause/activate, 125 TF = ₹1 preservation, counter preservation, and audit logs", async () => {
  const db = createMockFirestore();
  seedAdmin(db);
  const nowMs = 1_750_000_000_000;
  const deps = makeDeps(db, nowMs);

  await handleAdminUpsertCampaignNetwork(
    {
      auth: ADMIN_REQ_AUTH,
      data: {
        mode: "CREATE",
        networkId: "net_s2s",
        name: "Partner S2S",
        adapterType: CAMPAIGN_VERIFICATION_MODES.ADVERTISER_S2S_API,
        isActive: true,
        capabilities: { supportsIncentivizedTraffic: true, supportsPostback: true },
        allowedDestinationHosts: ["app.partner.example.com"],
        signatureConfig: {
          algorithm: "SHARED_TOKEN_HEADER",
          headerName: "x-s2s-secret",
          secretEnvKey: "CAMPAIGN_POSTBACK_SECRET_S2S"
        }
      }
    },
    deps
  );

  // Mode mismatch rejected (AFFILIATE_POSTBACK on ADVERTISER_S2S_API network)
  await assert.rejects(
    () =>
      handleAdminUpsertCampaignTrackingConfig(
        {
          auth: ADMIN_REQ_AUTH,
          data: {
            mode: "CREATE",
            campaignId: "camp_mismatch",
            title: "Mismatch",
            networkId: "net_s2s",
            verificationMode: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
            rewardTf: 500,
            trackingBaseUrl: "https://app.partner.example.com/offer"
          }
        },
        deps
      ),
    (err) => err.code === "failed-precondition"
  );

  // Disallowed destination host rejected
  await assert.rejects(
    () =>
      handleAdminUpsertCampaignTrackingConfig(
        {
          auth: ADMIN_REQ_AUTH,
          data: {
            mode: "CREATE",
            campaignId: "camp_evil_host",
            title: "Evil Host",
            networkId: "net_s2s",
            verificationMode: CAMPAIGN_VERIFICATION_MODES.ADVERTISER_S2S_API,
            rewardTf: 500,
            trackingBaseUrl: "https://evil.attacker.example.org/redirect"
          }
        },
        deps
      ),
    (err) => err.code === "invalid-argument"
  );

  // Valid campaign config creation (500 TF = ₹4 = 400 paise)
  const created = await handleAdminUpsertCampaignTrackingConfig(
    {
      auth: ADMIN_REQ_AUTH,
      data: {
        mode: "CREATE",
        campaignId: "camp_fin_01",
        title: "Finance App Install",
        isActive: true,
        startDate: "2026-11-01",
        endDate: "2026-12-31",
        startAtMillis: nowMs - 1000,
        expiresAtMillis: nowMs + 86_400_000,
        submissionBudget: 50,
        rewardBudgetTf: 25_000,
        allowedCountries: ["IN", "AE"],
        allowedPlatforms: ["ANDROID"],
        rewardTf: 500,
        verificationMode: CAMPAIGN_VERIFICATION_MODES.ADVERTISER_S2S_API,
        networkId: "net_s2s",
        trackingBaseUrl: "https://app.partner.example.com/install?src=tf",
        conversionRequirements: {
          requireUniqueClickId: true,
          requireAdminApproval: true,
          attributionWindowHours: 48
        }
      }
    },
    deps
  );

  assert.equal(created.success, true);
  assert.equal(created.config.rewardTf, 500);
  assert.equal(created.config.rewardCents, tfToBalanceCents(500));
  assert.equal(created.config.rewardCents, 400);
  assert.equal(created.config.conversionRequirements.requireAdminApproval, true);

  // Simulate server-side runtime counters having accumulated
  const rawStored = db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_fin_01`);
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_fin_01`, {
    ...rawStored,
    usedSubmissions: 10,
    spentBudgetTf: 5000,
    reservedClickSlots: 2,
    issuedClicksCount: 25,
    postbackSecretHash: "should_never_leak"
  });

  // Update config: attempting to reset usedSubmissions/spentBudgetTf via payload MUST be ignored
  const updated = await handleAdminUpsertCampaignTrackingConfig(
    {
      auth: ADMIN_REQ_AUTH,
      data: {
        mode: "UPDATE",
        campaignId: "camp_fin_01",
        title: "Finance App Install Pro",
        isActive: true,
        submissionBudget: 60,
        rewardBudgetTf: 30_000,
        usedSubmissions: 0,
        spentBudgetTf: 0,
        allowedCountries: ["IN"],
        allowedPlatforms: ["ANDROID"],
        rewardTf: 500,
        verificationMode: CAMPAIGN_VERIFICATION_MODES.ADVERTISER_S2S_API,
        networkId: "net_s2s",
        trackingBaseUrl: "https://app.partner.example.com/install?src=tf"
      }
    },
    deps
  );
  assert.equal(updated.config.usedSubmissions, 10);
  assert.equal(updated.config.spentBudgetTf, 5000);
  assert.equal(updated.config.reservedClickSlots, 2);
  assert.equal(updated.config.issuedClicksCount, 25);
  assert.equal("postbackSecretHash" in updated.config, false);

  // Pause campaign
  const paused = await handleAdminSetCampaignTrackingStatus(
    { auth: ADMIN_REQ_AUTH, data: { campaignId: "camp_fin_01", isActive: false } },
    deps
  );
  assert.equal(paused.config.isActive, false);
  assert.equal(db.read("campaigns/camp_fin_01").status, "PAUSED");

  // Disabling network prevents reactivating campaign
  await handleAdminSetCampaignNetworkStatus(
    { auth: ADMIN_REQ_AUTH, data: { networkId: "net_s2s", isActive: false } },
    deps
  );
  await assert.rejects(
    () =>
      handleAdminSetCampaignTrackingStatus(
        { auth: ADMIN_REQ_AUTH, data: { campaignId: "camp_fin_01", isActive: true } },
        deps
      ),
    (err) => err.code === "failed-precondition"
  );
});

test("5. Pagination works accurately across networks, campaign configs, and analytics performance rows", async () => {
  const db = createMockFirestore();
  seedAdmin(db);
  const nowMs = 1_750_000_000_000;
  const deps = makeDeps(db, nowMs);

  for (let i = 1; i <= 5; i++) {
    await handleAdminUpsertCampaignNetwork(
      {
        auth: ADMIN_REQ_AUTH,
        data: {
          mode: "CREATE",
          networkId: `net_0${i}`,
          name: `Network ${i}`,
          adapterType: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
          isActive: true,
          capabilities: { supportsIncentivizedTraffic: true, supportsPostback: true },
          signatureConfig: {
            algorithm: "HMAC_SHA256",
            signingMode: "SORTED_PARAMS",
            secretEnvKey: "CAMPAIGN_POSTBACK_SECRET"
          }
        }
      },
      deps
    );
    await handleAdminUpsertCampaignTrackingConfig(
      {
        auth: ADMIN_REQ_AUTH,
        data: {
          mode: "CREATE",
          campaignId: `camp_0${i}`,
          title: `Campaign ${i}`,
          isActive: true,
          rewardTf: 250,
          submissionBudget: 20,
          rewardBudgetTf: 5000,
          verificationMode: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
          networkId: `net_0${i}`,
          trackingBaseUrl: "https://offer.example.com/go"
        }
      },
      deps
    );
  }

  // Page through networks 2 at a time
  const nPage1 = await handleAdminListCampaignNetworks(
    { auth: ADMIN_REQ_AUTH, data: { pageSize: 2 } },
    deps
  );
  assert.equal(nPage1.items.length, 2);
  assert.equal(nPage1.hasMore, true);
  assert.equal(nPage1.totalCount, 5);

  const nPage2 = await handleAdminListCampaignNetworks(
    { auth: ADMIN_REQ_AUTH, data: { pageSize: 2, cursor: nPage1.nextCursor } },
    deps
  );
  assert.equal(nPage2.items.length, 2);
  assert.equal(nPage2.hasMore, true);

  const nPage3 = await handleAdminListCampaignNetworks(
    { auth: ADMIN_REQ_AUTH, data: { pageSize: 2, cursor: nPage2.nextCursor } },
    deps
  );
  assert.equal(nPage3.items.length, 1);
  assert.equal(nPage3.hasMore, false);

  const allNetIds = [...nPage1.items, ...nPage2.items, ...nPage3.items].map((x) => x.networkId);
  assert.deepEqual(allNetIds, ["net_01", "net_02", "net_03", "net_04", "net_05"]);

  // Page through configs 3 at a time
  const cPage1 = await handleAdminListCampaignTrackingConfigs(
    { auth: ADMIN_REQ_AUTH, data: { pageSize: 3 } },
    deps
  );
  assert.equal(cPage1.items.length, 3);
  assert.equal(cPage1.hasMore, true);
  const cPage2 = await handleAdminListCampaignTrackingConfigs(
    { auth: ADMIN_REQ_AUTH, data: { pageSize: 3, cursor: cPage1.nextCursor } },
    deps
  );
  assert.equal(cPage2.items.length, 2);
  assert.equal(cPage2.hasMore, false);

  // Dashboard analytics pagination
  const dash1 = await handleAdminGetCampaignTrackingDashboard(
    { auth: ADMIN_REQ_AUTH, data: { dateRange: "ALL", pageSize: 2 } },
    deps
  );
  assert.equal(dash1.campaignPerformance.items.length, 2);
  assert.equal(dash1.campaignPerformance.hasMore, true);
  assert.equal(dash1.networkPerformance.items.length, 2);
  assert.equal(dash1.networkPerformance.hasMore, true);
});

test("6. Analytics computes reversal-aware gross vs net TF rewards, date filters, approval rates, and leaks no attribution/secret metadata", async () => {
  const db = createMockFirestore();
  seedAdmin(db);
  const dayMs = 24 * 60 * 60 * 1000;
  const nowMs = 1_750_000_000_000;
  const deps = makeDeps(db, nowMs);

  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/net_a`, {
    networkId: "net_a",
    name: "Network A",
    adapterType: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
    isActive: true
  });
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_a`, {
    configId: "camp_a",
    campaignId: "camp_a",
    title: "Campaign A",
    isActive: true,
    networkId: "net_a",
    verificationMode: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
    rewardTf: 500,
    submissionBudget: 10,
    usedSubmissions: 2,
    rewardBudgetTf: 5000,
    spentBudgetTf: 1000,
    postbackSecretHash: "secret_hash_must_not_appear"
  });
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_paused`, {
    configId: "camp_paused",
    campaignId: "camp_paused",
    title: "Campaign Paused",
    isActive: false,
    networkId: "net_a",
    verificationMode: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
    rewardTf: 250,
    submissionBudget: 5,
    usedSubmissions: 0,
    rewardBudgetTf: 1250,
    spentBudgetTf: 0
  });

  // 3 clicks within 7D, 1 click 20 days ago
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS}/clk_secret_1`, {
    clickId: "clk_secret_1",
    campaignId: "camp_a",
    networkId: "net_a",
    userId: "u1",
    createdAt: new Date(nowMs - 2 * dayMs)
  });
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS}/clk_secret_2`, {
    clickId: "clk_secret_2",
    campaignId: "camp_a",
    networkId: "net_a",
    userId: "u2",
    createdAt: new Date(nowMs - 3 * dayMs)
  });
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS}/clk_secret_3`, {
    clickId: "clk_secret_3",
    campaignId: "camp_a",
    networkId: "net_a",
    userId: "u3",
    createdAt: new Date(nowMs - 1 * dayMs)
  });
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS}/clk_secret_old`, {
    clickId: "clk_secret_old",
    campaignId: "camp_a",
    networkId: "net_a",
    userId: "u4",
    createdAt: new Date(nowMs - 20 * dayMs)
  });

  // Conversions in 7D:
  // - 2 APPROVED (500 TF each, rewardCredited=true) -> 1000 TF
  // - 1 REVERSED (500 TF, rewardCredited=true) -> previously credited, then reversed
  // - 1 PENDING (500 TF, rewardCredited=false)
  // - 1 REJECTED (500 TF, rewardCredited=false)
  // Conversion 20D ago:
  // - 1 APPROVED (500 TF, rewardCredited=true) -> outside 7D, inside 30D
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS}/conv_1`, {
    conversionId: "conv_1",
    clickId: "clk_secret_1",
    externalTransactionId: "ext_private_001",
    userId: "u1",
    campaignId: "camp_a",
    networkId: "net_a",
    state: CAMPAIGN_CONVERSION_STATES.APPROVED,
    rewardTf: 500,
    rewardCredited: true,
    createdAt: new Date(nowMs - 2 * dayMs)
  });
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS}/conv_2`, {
    conversionId: "conv_2",
    clickId: "clk_secret_2",
    externalTransactionId: "ext_private_002",
    userId: "u2",
    campaignId: "camp_a",
    networkId: "net_a",
    state: CAMPAIGN_CONVERSION_STATES.APPROVED,
    rewardTf: 500,
    rewardCredited: true,
    createdAt: new Date(nowMs - 3 * dayMs)
  });
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS}/conv_3`, {
    conversionId: "conv_3",
    clickId: "clk_secret_3",
    externalTransactionId: "ext_private_003",
    userId: "u3",
    campaignId: "camp_a",
    networkId: "net_a",
    state: CAMPAIGN_CONVERSION_STATES.REVERSED,
    rewardTf: 500,
    rewardCredited: true,
    createdAt: new Date(nowMs - 1 * dayMs)
  });
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS}/conv_4`, {
    conversionId: "conv_4",
    clickId: "clk_secret_4",
    externalTransactionId: "ext_private_004",
    userId: "u5",
    campaignId: "camp_a",
    networkId: "net_a",
    state: CAMPAIGN_CONVERSION_STATES.PENDING,
    rewardTf: 500,
    rewardCredited: false,
    createdAt: new Date(nowMs - 1 * dayMs)
  });
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS}/conv_5`, {
    conversionId: "conv_5",
    clickId: "clk_secret_5",
    externalTransactionId: "ext_private_005",
    userId: "u6",
    campaignId: "camp_a",
    networkId: "net_a",
    state: CAMPAIGN_CONVERSION_STATES.REJECTED,
    rewardTf: 500,
    rewardCredited: false,
    createdAt: new Date(nowMs - 1 * dayMs)
  });
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS}/conv_old`, {
    conversionId: "conv_old",
    clickId: "clk_secret_old",
    externalTransactionId: "ext_private_old",
    userId: "u4",
    campaignId: "camp_a",
    networkId: "net_a",
    state: CAMPAIGN_CONVERSION_STATES.APPROVED,
    rewardTf: 500,
    rewardCredited: true,
    createdAt: new Date(nowMs - 20 * dayMs)
  });

  // 7D window check
  const dash7d = await handleAdminGetCampaignTrackingDashboard(
    { auth: ADMIN_REQ_AUTH, data: { dateRange: "7D" } },
    deps
  );

  assert.equal(dash7d.summary.totalCampaigns, 2);
  assert.equal(dash7d.summary.activeCampaigns, 1);
  assert.equal(dash7d.summary.totalClicks, 3);
  assert.equal(dash7d.summary.pendingConversions, 1);
  assert.equal(dash7d.summary.approvedConversions, 2);
  assert.equal(dash7d.summary.rejectedConversions, 1);
  assert.equal(dash7d.summary.reversedConversions, 1);
  assert.equal(dash7d.summary.totalConversions, 5);
  assert.equal(dash7d.summary.approvalRatePercent, 40); // 2 / 5 = 40.0%
  assert.equal(dash7d.summary.grossCreditedTf, 1500); // 2 approved + 1 reversed that was credited
  assert.equal(dash7d.summary.reversedTf, 500);
  assert.equal(dash7d.summary.netCreditedTf, 1000); // 1500 - 500
  assert.equal(dash7d.summary.grossCreditedCents, 1200); // 1500 TF = ₹12 = 1200 paise
  assert.equal(dash7d.summary.netCreditedCents, 800); // 1000 TF = ₹8 = 800 paise

  const campPausedRow = dash7d.campaignPerformance.items.find((r) => r.campaignId === "camp_paused");
  assert.equal(campPausedRow.approvalRatePercent, null); // no conversions -> null (not misleading 0%)

  // 30D window includes conv_old (+500 TF) and clk_secret_old (+1 click)
  const dash30d = await handleAdminGetCampaignTrackingDashboard(
    { auth: ADMIN_REQ_AUTH, data: { dateRange: "30D" } },
    deps
  );
  assert.equal(dash30d.summary.totalClicks, 4);
  assert.equal(dash30d.summary.grossCreditedTf, 2000);
  assert.equal(dash30d.summary.netCreditedTf, 1500);

  // Privacy: response MUST NOT contain clickId, externalTransactionId, or secret hashes
  const serialized = JSON.stringify(dash30d);
  assert.ok(!serialized.includes("clk_secret_1"));
  assert.ok(!serialized.includes("ext_private_001"));
  assert.ok(!serialized.includes("secret_hash_must_not_appear"));
});
