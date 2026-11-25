const assert = require("node:assert/strict");
const { test } = require("node:test");
const { _campaignTrackingHandlers } = require("./index.js");
const {
  CAMPAIGN_TRACKING_COLLECTIONS,
  CAMPAIGN_VERIFICATION_MODES
} = require("./campaign_tracking.js");

const {
  handleCreateCampaignClick,
  handleReleaseExpiredCampaignClickReservations
} = _campaignTrackingHandlers;

/**
 * In-memory Firestore simulator with optimistic-concurrency transactions
 * mirroring Firebase Admin SDK behavior under concurrent click contention.
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
          if (rel.includes("/")) continue; // direct children only
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
    const MAX_ATTEMPTS = 12;
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

function makeDeps(db, nowRef) {
  return {
    db,
    now: () => nowRef.value,
    serverTimestamp: () => ({ __isServerTimestamp: true }),
    timestampFromMillis: (ms) => new Date(ms)
  };
}

function seedBaselineCampaign({
  db,
  nowMs,
  campaignId = "camp_offer_01",
  networkId = "net_incent_a",
  supportsIncentivizedTraffic = true,
  networkIsActive = true,
  configOverrides = {},
  networkOverrides = {},
  campaignOverrides = {},
  userIds = ["user_1"]
}) {
  for (const uid of userIds) {
    db.seed(`users/${uid}`, {
      userId: uid,
      email: `${uid}@example.com`,
      approvalStatus: "approved",
      balanceCoins: 1000,
      balanceCents: 1000
    });
  }

  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/${networkId}`, {
    networkId,
    name: "Incent Network A",
    adapterType: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
    isActive: networkIsActive,
    postbackSecret: "SUPER_PRIVATE_HMAC_SECRET_999",
    apiKey: "PRIVATE_NETWORK_API_KEY",
    capabilities: {
      supportsIncentivizedTraffic,
      supportsPostback: true,
      supportsInstallAttribution: true
    },
    parameterMapping: {
      clickIdParam: "aff_sub",
      userIdParam: "sub_user",
      campaignIdParam: "offer_sub",
      extraStaticParams: { source: "taskflow_app" }
    },
    allowedDestinationHosts: ["partner.example.com", "trk.partner.example.com"],
    ...networkOverrides
  });

  db.seed(`campaigns/${campaignId}`, {
    campaignId,
    title: "Partner Finance App",
    status: "ACTIVE",
    taskLink: "https://partner.example.com/offer?lp=1",
    ...campaignOverrides
  });

  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/${campaignId}`, {
    configId: campaignId,
    campaignId,
    isActive: true,
    startAtMillis: nowMs - 60_000,
    expiresAtMillis: nowMs + 86_400_000,
    submissionBudget: 50,
    usedSubmissions: 0,
    reservedClickSlots: 0,
    rewardBudgetTf: 25_000,
    spentBudgetTf: 0,
    rewardTf: 500,
    allowedCountries: ["IN"],
    allowedPlatforms: ["ANDROID"],
    verificationMode: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
    networkId,
    trackingNetworkRef: `campaign_networks/${networkId}`,
    trackingBaseUrl: "https://partner.example.com/install?pub=tf&cb={click_id}",
    postbackSecretHash: "sha256_hash_value",
    privateSecretRef: "ENV_SECRET_REF",
    ...configOverrides
  });
}

async function expectHttpsError(promise, expectedCode, substring = "") {
  let caught = null;
  try {
    await promise;
  } catch (err) {
    caught = err;
  }
  assert.ok(caught, `Expected HttpsError(${expectedCode}) but call succeeded`);
  assert.equal(caught.code, expectedCode, `Expected ${expectedCode}, got ${caught.code}: ${caught.message}`);
  if (substring) {
    assert.ok(
      String(caught.message || "").toLowerCase().includes(substring.toLowerCase()),
      `Expected error message '${caught.message}' to include '${substring}'`
    );
  }
}

test("1. Rejects unauthenticated, spoofed userId, and non-approved user accounts", async () => {
  const nowRef = { value: 1_764_000_000_000 };
  const db = createMockFirestore();
  seedBaselineCampaign({ db, nowMs: nowRef.value, userIds: ["user_1"] });
  db.seed("users/user_pending", {
    userId: "user_pending",
    approvalStatus: "pending",
    balanceCoins: 0
  });

  await expectHttpsError(
    handleCreateCampaignClick({ auth: null, data: { campaignId: "camp_offer_01" } }, makeDeps(db, nowRef)),
    "unauthenticated"
  );

  await expectHttpsError(
    handleCreateCampaignClick(
      {
        auth: { uid: "user_1" },
        data: { campaignId: "camp_offer_01", userId: "user_attacker_spoofed" }
      },
      makeDeps(db, nowRef)
    ),
    "permission-denied",
    "identity mismatch"
  );

  await expectHttpsError(
    handleCreateCampaignClick(
      { auth: { uid: "user_pending" }, data: { campaignId: "camp_offer_01" } },
      makeDeps(db, nowRef)
    ),
    "permission-denied",
    "approved"
  );
});

test("2. Generates unique unpredictable clickIds, persists click safely, strips secrets, and never credits TF", async () => {
  const nowRef = { value: 1_764_000_000_000 };
  const db = createMockFirestore();
  seedBaselineCampaign({ db, nowMs: nowRef.value, userIds: ["user_1", "user_2"] });

  const res1 = await handleCreateCampaignClick(
    {
      auth: { uid: "user_1" },
      data: { campaignId: "camp_offer_01", taskId: "task_1", devicePlatform: "ANDROID", rewardTf: 999999 }
    },
    { ...makeDeps(db, nowRef), headers: { "x-appengine-country": "IN" } }
  );

  nowRef.value += 35_000; // advance past default cooldown
  const res2 = await handleCreateCampaignClick(
    {
      auth: { uid: "user_1" },
      data: { campaignId: "camp_offer_01", taskId: "task_1", devicePlatform: "ANDROID" }
    },
    { ...makeDeps(db, nowRef), headers: { "x-appengine-country": "IN" } }
  );

  assert.equal(res1.success, true);
  assert.equal(res2.success, true);
  assert.equal(res1.clickId, undefined);
  assert.equal(res2.clickId, undefined);
  const res1ClickId = (/clk_[a-f0-9]{32}/.exec(res1.trackingUrl) || [])[0] || "";
  const res2ClickId = (/clk_[a-f0-9]{32}/.exec(res2.trackingUrl) || [])[0] || "";
  assert.match(res1ClickId, /^clk_[a-f0-9]{32}$/);
  assert.match(res2ClickId, /^clk_[a-f0-9]{32}$/);
  assert.notEqual(res1ClickId, res2ClickId);

  // Verify response exposes only safe tracking fields — no secrets, no rewardTf override, no raw clickId property
  const serialized = JSON.stringify(res1);
  assert.equal(serialized.includes("SUPER_PRIVATE_HMAC_SECRET_999"), false);
  assert.equal(serialized.includes("PRIVATE_NETWORK_API_KEY"), false);
  assert.equal(serialized.includes("sha256_hash_value"), false);
  assert.equal(res1.rewardTf, undefined);

  // Verify click record stored securely and user TF balance untouched (still 1000)
  const storedClick = db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS}/${res1ClickId}`);
  assert.ok(storedClick);
  assert.equal(storedClick.userId, "user_1");
  assert.equal(storedClick.campaignId, "camp_offer_01");
  assert.equal(storedClick.taskId, "task_1");
  assert.equal(storedClick.networkId, "net_incent_a");
  assert.equal(storedClick.status, "ISSUED");
  assert.equal(storedClick.verificationMode, CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK);

  const userAfter = db.read("users/user_1");
  assert.equal(userAfter.balanceCoins, 1000);
  assert.equal(db.listByPrefix("transactions/").length, 0);
  assert.equal(db.listByPrefix("campaign_conversions/").length, 0);
});

test("3. Enforces campaign active status, schedule window, expiration, and network incentivized-traffic policy", async () => {
  const nowRef = { value: 1_764_000_000_000 };
  const db = createMockFirestore();

  // Inactive config
  seedBaselineCampaign({
    db,
    nowMs: nowRef.value,
    campaignId: "camp_inactive",
    configOverrides: { isActive: false }
  });
  await expectHttpsError(
    handleCreateCampaignClick({ auth: { uid: "user_1" }, data: { campaignId: "camp_inactive" } }, makeDeps(db, nowRef)),
    "failed-precondition",
    "inactive"
  );

  // Paused parent campaign
  seedBaselineCampaign({
    db,
    nowMs: nowRef.value,
    campaignId: "camp_paused",
    campaignOverrides: { status: "PAUSED" }
  });
  await expectHttpsError(
    handleCreateCampaignClick({ auth: { uid: "user_1" }, data: { campaignId: "camp_paused" } }, makeDeps(db, nowRef)),
    "failed-precondition",
    "paused"
  );

  // Not yet started
  seedBaselineCampaign({
    db,
    nowMs: nowRef.value,
    campaignId: "camp_future",
    configOverrides: { startAtMillis: nowRef.value + 600_000 }
  });
  await expectHttpsError(
    handleCreateCampaignClick({ auth: { uid: "user_1" }, data: { campaignId: "camp_future" } }, makeDeps(db, nowRef)),
    "failed-precondition",
    "not started"
  );

  // Expired
  seedBaselineCampaign({
    db,
    nowMs: nowRef.value,
    campaignId: "camp_expired",
    configOverrides: { expiresAtMillis: nowRef.value - 1 }
  });
  await expectHttpsError(
    handleCreateCampaignClick({ auth: { uid: "user_1" }, data: { campaignId: "camp_expired" } }, makeDeps(db, nowRef)),
    "failed-precondition",
    "expired"
  );

  // Inactive network
  seedBaselineCampaign({
    db,
    nowMs: nowRef.value,
    campaignId: "camp_net_off",
    networkId: "net_off",
    networkIsActive: false
  });
  await expectHttpsError(
    handleCreateCampaignClick({ auth: { uid: "user_1" }, data: { campaignId: "camp_net_off" } }, makeDeps(db, nowRef)),
    "failed-precondition",
    "network is currently inactive"
  );

  // Network does NOT permit incentivized traffic
  seedBaselineCampaign({
    db,
    nowMs: nowRef.value,
    campaignId: "camp_no_incent",
    networkId: "net_non_incent",
    supportsIncentivizedTraffic: false
  });
  await expectHttpsError(
    handleCreateCampaignClick({ auth: { uid: "user_1" }, data: { campaignId: "camp_no_incent" } }, makeDeps(db, nowRef)),
    "permission-denied",
    "does not permit incentivized traffic"
  );
});

test("4. Fails closed when geo headers are missing or spoofed and enforces App Check strict attestation when required", async () => {
  const nowRef = { value: 1_764_000_000_000 };
  const db = createMockFirestore();
  seedBaselineCampaign({
    db,
    nowMs: nowRef.value,
    campaignId: "camp_geo_dev",
    configOverrides: {
      allowedCountries: ["IN", "AE"],
      allowedPlatforms: ["ANDROID"]
    }
  });

  // Missing server geo headers + client trying to spoof data.countryCode / x-country-code MUST fail closed
  await expectHttpsError(
    handleCreateCampaignClick(
      {
        auth: { uid: "user_1" },
        data: { campaignId: "camp_geo_dev", countryCode: "IN", devicePlatform: "ANDROID" }
      },
      { ...makeDeps(db, nowRef), headers: { "x-country-code": "IN" } }
    ),
    "failed-precondition",
    "country signal is unavailable"
  );

  // cf-ipcountry is ignored unless trustCloudflareHeaders is explicitly enabled
  await expectHttpsError(
    handleCreateCampaignClick(
      { auth: { uid: "user_1" }, data: { campaignId: "camp_geo_dev", devicePlatform: "ANDROID" } },
      { ...makeDeps(db, nowRef), headers: { "cf-ipcountry": "IN" } }
    ),
    "failed-precondition",
    "country signal is unavailable"
  );

  await expectHttpsError(
    handleCreateCampaignClick(
      { auth: { uid: "user_1" }, data: { campaignId: "camp_geo_dev", devicePlatform: "ANDROID" } },
      { ...makeDeps(db, nowRef), headers: { "x-appengine-country": "US" } }
    ),
    "permission-denied",
    "country (US)"
  );

  await expectHttpsError(
    handleCreateCampaignClick(
      { auth: { uid: "user_1" }, data: { campaignId: "camp_geo_dev", devicePlatform: "IOS" } },
      { ...makeDeps(db, nowRef), headers: { "x-appengine-country": "IN" } }
    ),
    "permission-denied",
    "not supported on IOS"
  );

  const ok = await handleCreateCampaignClick(
    { auth: { uid: "user_1" }, data: { campaignId: "camp_geo_dev", devicePlatform: "ANDROID" } },
    {
      ...makeDeps(db, nowRef),
      headers: { "cf-ipcountry": "AE" },
      trustCloudflareHeaders: true
    }
  );
  assert.equal(ok.success, true);
  assert.equal(ok.clickId, undefined);
  const okClickId = (/clk_[a-f0-9]{32}/.exec(ok.trackingUrl) || [])[0] || "";
  const stored = db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS}/${okClickId}`);
  assert.equal(stored.deviceTrustLevel, "SELF_DECLARED_UNVERIFIED");
  assert.equal(stored.countryTrustLevel, "CLOUDFLARE_PROXY_HEADER");

  // Strict device attestation fails closed when App Check token is missing
  seedBaselineCampaign({
    db,
    nowMs: nowRef.value,
    campaignId: "camp_strict_attest",
    userIds: ["user_1"],
    configOverrides: {
      allowedCountries: ["ALL"],
      allowedPlatforms: ["ANDROID"],
      requireDeviceAttestation: true
    }
  });
  await expectHttpsError(
    handleCreateCampaignClick(
      { auth: { uid: "user_1" }, data: { campaignId: "camp_strict_attest", devicePlatform: "ANDROID" } },
      makeDeps(db, nowRef)
    ),
    "failed-precondition",
    "device attestation"
  );
  const attested = await handleCreateCampaignClick(
    { auth: { uid: "user_1" }, data: { campaignId: "camp_strict_attest", devicePlatform: "ANDROID" } },
    { ...makeDeps(db, nowRef), appCheck: { appId: "1:123:android:abc" } }
  );
  assert.equal(attested.success, true);
  assert.equal(attested.clickId, undefined);
  const attestedClickId = (/clk_[a-f0-9]{32}/.exec(attested.trackingUrl) || [])[0] || "";
  assert.equal(
    db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS}/${attestedClickId}`).deviceTrustLevel,
    "APP_CHECK_VERIFIED"
  );
});

test("5. Maps network-specific URL parameters without hardcoding a universal format and blocks malicious URLs", async () => {
  const nowRef = { value: 1_764_000_000_000 };
  const db = createMockFirestore();

  // Network A uses aff_sub + cb={click_id} macro
  seedBaselineCampaign({
    db,
    nowMs: nowRef.value,
    campaignId: "camp_net_a",
    networkId: "net_a",
    networkOverrides: {
      parameterMapping: {
        clickIdParam: "aff_sub",
        userIdParam: "aff_sub2",
        campaignIdParam: "offer_id"
      },
      allowedDestinationHosts: ["track.alpha.example"]
    },
    configOverrides: {
      trackingBaseUrl: "https://track.alpha.example/click?offer=42&cb={click_id}"
    }
  });

  // Network B uses subid
  seedBaselineCampaign({
    db,
    nowMs: nowRef.value,
    campaignId: "camp_net_b",
    networkId: "net_b",
    networkOverrides: {
      parameterMapping: {
        clickIdParam: "subid",
        extraStaticParams: { partner: "taskflow" }
      },
      allowedDestinationHosts: ["go.beta.example"]
    },
    configOverrides: {
      trackingBaseUrl: "https://go.beta.example/c/99",
      verificationMode: CAMPAIGN_VERIFICATION_MODES.ADVERTISER_S2S_API
    }
  });

  const outA = await handleCreateCampaignClick(
    { auth: { uid: "user_1" }, data: { campaignId: "camp_net_a", devicePlatform: "ANDROID" } },
    { ...makeDeps(db, nowRef), headers: { "x-appengine-country": "IN" } }
  );
  assert.equal(outA.clickId, undefined);
  const urlA = new URL(outA.trackingUrl);
  assert.equal(urlA.hostname, "track.alpha.example");
  const clickIdA = urlA.searchParams.get("aff_sub");
  assert.match(clickIdA, /^clk_[a-f0-9]{32}$/);
  assert.equal(urlA.searchParams.get("cb"), clickIdA);
  assert.equal(urlA.searchParams.get("aff_sub2"), "user_1");
  assert.equal(urlA.searchParams.get("offer_id"), "camp_net_a");

  const outB = await handleCreateCampaignClick(
    { auth: { uid: "user_1" }, data: { campaignId: "camp_net_b", devicePlatform: "ANDROID" } },
    { ...makeDeps(db, nowRef), headers: { "x-appengine-country": "IN" } }
  );
  assert.equal(outB.clickId, undefined);
  const urlB = new URL(outB.trackingUrl);
  assert.equal(urlB.hostname, "go.beta.example");
  assert.match(urlB.searchParams.get("subid"), /^clk_[a-f0-9]{32}$/);
  assert.equal(urlB.searchParams.get("partner"), "taskflow");

  // Malicious URLs & trailing-dot localhost
  const badUrls = [
    "http://partner.example.com/offer",
    "javascript:alert(1)",
    "https://user:pass@partner.example.com/offer",
    "https://127.0.0.1/admin",
    "https://localhost./admin",
    "https://evil-open-redirect.example/phish"
  ];
  for (let i = 0; i < badUrls.length; i++) {
    const campId = `camp_bad_url_${i}`;
    seedBaselineCampaign({
      db,
      nowMs: nowRef.value,
      campaignId: campId,
      networkId: "net_bad",
      networkOverrides: { allowedDestinationHosts: ["partner.example.com"] },
      configOverrides: { trackingBaseUrl: badUrls[i] }
    });
    await expectHttpsError(
      handleCreateCampaignClick(
        { auth: { uid: "user_1" }, data: { campaignId: campId } },
        { ...makeDeps(db, nowRef), headers: { "x-appengine-country": "IN" } }
      ),
      "failed-precondition"
    );
  }

  // parameterOverrides cannot overwrite server clickIdParam
  seedBaselineCampaign({
    db,
    nowMs: nowRef.value,
    campaignId: "camp_override_guard",
    networkId: "net_a",
    networkOverrides: {
      parameterMapping: { clickIdParam: "aff_sub" },
      allowedDestinationHosts: ["track.alpha.example"]
    },
    configOverrides: {
      trackingBaseUrl: "https://track.alpha.example/click",
      parameterOverrides: { aff_sub: "spoofed_override", campaign_tag: "q4" }
    }
  });
  const overrideOut = await handleCreateCampaignClick(
    { auth: { uid: "user_1" }, data: { campaignId: "camp_override_guard" } },
    { ...makeDeps(db, nowRef), headers: { "x-appengine-country": "IN" } }
  );
  const overrideUrl = new URL(overrideOut.trackingUrl);
  assert.match(overrideUrl.searchParams.get("aff_sub"), /^clk_[a-f0-9]{32}$/);
  assert.notEqual(overrideUrl.searchParams.get("aff_sub"), "spoofed_override");
  assert.equal(overrideUrl.searchParams.get("campaign_tag"), "q4");
});

test("6. Deduplicates rapid duplicate clicks via cooldown/idempotency while allowing legitimate repeat clicks and enforcing rate limits", async () => {
  const nowRef = { value: 1_764_000_000_000 };
  const db = createMockFirestore();
  seedBaselineCampaign({
    db,
    nowMs: nowRef.value,
    campaignId: "camp_dedup",
    configOverrides: {
      clickCooldownMs: 30_000,
      maxClicksPerUserPerHour: 2,
      submissionBudget: 5
    }
  });

  const first = await handleCreateCampaignClick(
    {
      auth: { uid: "user_1" },
      data: { campaignId: "camp_dedup", idempotencyKey: "attempt_key_1" }
    },
    { ...makeDeps(db, nowRef), headers: { "x-appengine-country": "IN" } }
  );
  assert.equal(first.deduplicated, false);
  assert.equal(first.clickId, undefined);
  const firstClickId = (/clk_[a-f0-9]{32}/.exec(first.trackingUrl) || [])[0] || "";

  // Rapid duplicate tap within cooldown -> returns existing unexpired click without burning slot
  nowRef.value += 5_000;
  const dup = await handleCreateCampaignClick(
    {
      auth: { uid: "user_1" },
      data: { campaignId: "camp_dedup", idempotencyKey: "attempt_key_2" }
    },
    { ...makeDeps(db, nowRef), headers: { "x-appengine-country": "IN" } }
  );
  assert.equal(dup.deduplicated, true);
  assert.equal((/clk_[a-f0-9]{32}/.exec(dup.trackingUrl) || [])[0] || "", firstClickId);

  // Repeat tap after cooldown -> allowed (2nd hourly click)
  nowRef.value += 31_000;
  const second = await handleCreateCampaignClick(
    {
      auth: { uid: "user_1" },
      data: { campaignId: "camp_dedup", idempotencyKey: "attempt_key_3" }
    },
    { ...makeDeps(db, nowRef), headers: { "x-appengine-country": "IN" } }
  );
  assert.equal(second.deduplicated, false);
  assert.notEqual((/clk_[a-f0-9]{32}/.exec(second.trackingUrl) || [])[0] || "", firstClickId);

  // Third click within same hour -> blocked by per-campaign hourly limit (maxClicksPerUserPerHour = 2)
  nowRef.value += 31_000;
  await expectHttpsError(
    handleCreateCampaignClick(
      {
        auth: { uid: "user_1" },
        data: { campaignId: "camp_dedup", idempotencyKey: "attempt_key_4" }
      },
      { ...makeDeps(db, nowRef), headers: { "x-appengine-country": "IN" } }
    ),
    "resource-exhausted",
    "too many click attempts"
  );
});

test("7. Enforces submissionBudget and rewardBudgetTf atomically under concurrent requests", async () => {
  const nowRef = { value: 1_764_000_000_000 };
  const db = createMockFirestore();
  const userIds = ["u1", "u2", "u3", "u4", "u5", "u6"];

  seedBaselineCampaign({
    db,
    nowMs: nowRef.value,
    campaignId: "camp_concurrent_cap",
    userIds,
    configOverrides: {
      submissionBudget: 2,
      usedSubmissions: 0,
      reservedClickSlots: 0,
      clickOverbookingBuffer: 0,
      rewardBudgetTf: 5000,
      spentBudgetTf: 0,
      rewardTf: 500
    }
  });

  const results = await Promise.allSettled(
    userIds.map((uid) =>
      handleCreateCampaignClick(
        {
          auth: { uid },
          data: { campaignId: "camp_concurrent_cap", devicePlatform: "ANDROID" }
        },
        { ...makeDeps(db, nowRef), headers: { "x-appengine-country": "IN" } }
      )
    )
  );

  const succeeded = results.filter((r) => r.status === "fulfilled");
  const rejected = results.filter((r) => r.status === "rejected");

  assert.equal(succeeded.length, 2, `Expected exactly 2 succeeded clicks, got ${succeeded.length}`);
  assert.equal(rejected.length, 4, `Expected 4 rejected clicks, got ${rejected.length}`);
  for (const r of rejected) {
    assert.equal(r.reason.code, "resource-exhausted");
  }

  const finalConfig = db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_concurrent_cap`);
  assert.equal(finalConfig.reservedClickSlots, 2);
  assert.equal(finalConfig.issuedClicksCount, 2);

  // Also verify exhausted rewardBudgetTf blocks clicks
  seedBaselineCampaign({
    db,
    nowMs: nowRef.value,
    campaignId: "camp_tf_exhausted",
    userIds: ["u1"],
    configOverrides: {
      submissionBudget: 100,
      rewardBudgetTf: 1000,
      spentBudgetTf: 750,
      rewardTf: 500
    }
  });
  await expectHttpsError(
    handleCreateCampaignClick(
      { auth: { uid: "u1" }, data: { campaignId: "camp_tf_exhausted" } },
      { ...makeDeps(db, nowRef), headers: { "x-appengine-country": "IN" } }
    ),
    "resource-exhausted",
    "reward budget"
  );
});

test("8. Releases expired reservations lazily and via scheduled sweep without permanent budget locking or concurrent overspend", async () => {
  const nowRef = { value: 1_764_000_000_000 };
  const db = createMockFirestore();
  const userIds = ["u1", "u2", "u3", "u4", "u5"];

  seedBaselineCampaign({
    db,
    nowMs: nowRef.value,
    campaignId: "camp_expiry_release",
    userIds,
    configOverrides: {
      submissionBudget: 2,
      usedSubmissions: 0,
      reservedClickSlots: 0,
      clickOverbookingBuffer: 0,
      rewardBudgetTf: 5000,
      spentBudgetTf: 0,
      rewardTf: 500,
      conversionRequirements: { attributionWindowHours: 1 }
    }
  });

  // u1 and u2 reserve both slots (budget = 2)
  const clickU1 = await handleCreateCampaignClick(
    { auth: { uid: "u1" }, data: { campaignId: "camp_expiry_release" } },
    { ...makeDeps(db, nowRef), headers: { "x-appengine-country": "IN" } }
  );
  const clickU2 = await handleCreateCampaignClick(
    { auth: { uid: "u2" }, data: { campaignId: "camp_expiry_release" } },
    { ...makeDeps(db, nowRef), headers: { "x-appengine-country": "IN" } }
  );

  // u3 is blocked while u1 and u2 hold unexpired reservations
  await expectHttpsError(
    handleCreateCampaignClick(
      { auth: { uid: "u3" }, data: { campaignId: "camp_expiry_release" } },
      { ...makeDeps(db, nowRef), headers: { "x-appengine-country": "IN" } }
    ),
    "resource-exhausted",
    "active participation limit"
  );

  // Advance clock past attribution window (1h + 1s); u1 and u2 never converted
  nowRef.value += 61 * 60 * 1000;

  // u3, u4, u5 race concurrently to claim reclaimed slots -> strictly 2 succeed, 1 blocked
  const raceResults = await Promise.allSettled(
    ["u3", "u4", "u5"].map((uid) =>
      handleCreateCampaignClick(
        { auth: { uid }, data: { campaignId: "camp_expiry_release" } },
        { ...makeDeps(db, nowRef), headers: { "x-appengine-country": "IN" } }
      )
    )
  );
  const raceWon = raceResults.filter((r) => r.status === "fulfilled");
  const raceBlocked = raceResults.filter((r) => r.status === "rejected");
  assert.equal(raceWon.length, 2, `Expected strictly 2 reclaimed slots, got ${raceWon.length}`);
  assert.equal(raceBlocked.length, 1);
  assert.equal(raceBlocked[0].reason.code, "resource-exhausted");

  // Previous clicks from u1 & u2 are marked EXPIRED and reservedClickSlots equals 2 (no leak)
  const clickU1Id = (/clk_[a-f0-9]{32}/.exec(clickU1.trackingUrl) || [])[0] || "";
  const clickU2Id = (/clk_[a-f0-9]{32}/.exec(clickU2.trackingUrl) || [])[0] || "";
  assert.equal(
    db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS}/${clickU1Id}`).status,
    "EXPIRED"
  );
  assert.equal(
    db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS}/${clickU2Id}`).status,
    "EXPIRED"
  );
  assert.equal(
    db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_expiry_release`).reservedClickSlots,
    2
  );

  // Advance clock past new clicks and run scheduled cleanup sweep
  nowRef.value += 61 * 60 * 1000;
  const sweep1 = await handleReleaseExpiredCampaignClickReservations(makeDeps(db, nowRef));
  assert.equal(sweep1.releasedClicks, 2);
  assert.equal(
    db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_expiry_release`).reservedClickSlots,
    0
  );

  // Sweep idempotency: running again releases 0 and keeps reservedClickSlots at 0
  const sweep2 = await handleReleaseExpiredCampaignClickReservations(makeDeps(db, nowRef));
  assert.equal(sweep2.releasedClicks, 0);
  assert.equal(
    db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_expiry_release`).reservedClickSlots,
    0
  );

  // Same user re-clicking after expiration on submissionBudget=1 reclaims own slot cleanly
  seedBaselineCampaign({
    db,
    nowMs: nowRef.value,
    campaignId: "camp_single_user_reclick",
    userIds: ["u1"],
    configOverrides: {
      submissionBudget: 1,
      conversionRequirements: { attributionWindowHours: 1 }
    }
  });
  const firstClick = await handleCreateCampaignClick(
    { auth: { uid: "u1" }, data: { campaignId: "camp_single_user_reclick" } },
    { ...makeDeps(db, nowRef), headers: { "x-appengine-country": "IN" } }
  );
  nowRef.value += 61 * 60 * 1000;
  const reclick = await handleCreateCampaignClick(
    { auth: { uid: "u1" }, data: { campaignId: "camp_single_user_reclick" } },
    { ...makeDeps(db, nowRef), headers: { "x-appengine-country": "IN" } }
  );
  const firstReclickId = (/clk_[a-f0-9]{32}/.exec(firstClick.trackingUrl) || [])[0] || "";
  const secondReclickId = (/clk_[a-f0-9]{32}/.exec(reclick.trackingUrl) || [])[0] || "";
  assert.notEqual(secondReclickId, firstReclickId);
  assert.equal(
    db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS}/${firstReclickId}`).status,
    "EXPIRED"
  );
  assert.equal(
    db.read(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_single_user_reclick`).reservedClickSlots,
    1
  );

  // TF-budget-only campaign (submissionBudget = 0, rewardBudgetTf = 1000, rewardTf = 500) caps concurrent clicks at 2
  seedBaselineCampaign({
    db,
    nowMs: nowRef.value,
    campaignId: "camp_tf_cap_only",
    userIds: ["u1", "u2", "u3", "u4"],
    configOverrides: {
      submissionBudget: 0,
      rewardBudgetTf: 1000,
      spentBudgetTf: 0,
      rewardTf: 500
    }
  });
  const tfRace = await Promise.allSettled(
    ["u1", "u2", "u3", "u4"].map((uid) =>
      handleCreateCampaignClick(
        { auth: { uid }, data: { campaignId: "camp_tf_cap_only" } },
        { ...makeDeps(db, nowRef), headers: { "x-appengine-country": "IN" } }
      )
    )
  );
  assert.equal(tfRace.filter((r) => r.status === "fulfilled").length, 2);
  assert.equal(tfRace.filter((r) => r.status === "rejected").length, 2);
});

