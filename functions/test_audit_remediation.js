"use strict";

const crypto = require("node:crypto");
const test = require("node:test");
const assert = require("node:assert/strict");

const {
  _manualBonusHandlers,
  _promoHandlers,
  _telegramHandlers,
  _campaignTrackingHandlers,
  _campaignConversionHandlers
} = require("./index.js");

const { verifyAdminAccess } = _manualBonusHandlers;
const { verifyPromoAdminAccess } = _promoHandlers;
const { handleVerifyTelegramTaskMembership, handleClaimTelegramTaskReward } = _telegramHandlers;
const { handleCreateCampaignClick } = _campaignTrackingHandlers;
const { handleReviewCampaignConversion, handleCampaignPostback } = _campaignConversionHandlers;
const BOOTSTRAPPED_ADMIN_EMAIL = "dasakash24059@gmail.com";

const {
  CAMPAIGN_TRACKING_COLLECTIONS,
  CAMPAIGN_VERIFICATION_MODES,
  CAMPAIGN_CONVERSION_STATES
} = require("./campaign_tracking.js");

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
      },
      async set(data, options) {
        const serverNow = Date.now();
        const prev = store.get(path);
        const nextVersion = (prev ? prev.version : 0) + 1;
        const resolved = resolveValue(data, serverNow);
        if (options && options.merge && prev) {
          store.set(path, { data: { ...clone(prev.data), ...resolved }, version: nextVersion });
        } else {
          store.set(path, { data: resolved, version: nextVersion });
        }
      },
      async update(data) {
        const serverNow = Date.now();
        const prev = store.get(path);
        if (!prev) throw new Error(`NOT_FOUND: ${path}`);
        const resolved = resolveValue(data, serverNow);
        store.set(path, { data: { ...clone(prev.data), ...resolved }, version: prev.version + 1 });
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

test("H-01: verifyAdminAccess requires email_verified === true for bootstrapped admin email", async () => {
  const db = createMockFirestore();

  await assert.rejects(
    () =>
      verifyAdminAccess(
        { auth: { uid: "boot_unverified", token: { email: BOOTSTRAPPED_ADMIN_EMAIL, email_verified: false } } },
        db
      ),
    (err) => err.code === "permission-denied"
  );

  await assert.rejects(
    () =>
      verifyPromoAdminAccess(
        { auth: { uid: "boot_unverified", token: { email: BOOTSTRAPPED_ADMIN_EMAIL, email_verified: false } } },
        db
      ),
    (err) => err.code === "permission-denied"
  );

  await assert.rejects(
    () =>
      verifyAdminAccess(
        { auth: { uid: "boot_missing_claim", token: { email: BOOTSTRAPPED_ADMIN_EMAIL } } },
        db
      ),
    (err) => err.code === "permission-denied"
  );

  await assert.rejects(
    () =>
      verifyAdminAccess(
        { auth: { uid: "non_admin", token: { email: "intruder@example.com", email_verified: true } } },
        db
      ),
    (err) => err.code === "permission-denied"
  );

  const verified = await verifyAdminAccess(
    { auth: { uid: "boot_ok", token: { email: BOOTSTRAPPED_ADMIN_EMAIL.toUpperCase(), email_verified: true } } },
    db
  );
  assert.equal(verified.uid, "boot_ok");
  assert.equal(verified.email, BOOTSTRAPPED_ADMIN_EMAIL);

  db.seed("admins/delegated_admin", { userId: "delegated_admin", email: "ops@example.com" });
  const delegated = await verifyAdminAccess(
    { auth: { uid: "delegated_admin", token: { email: "ops@example.com", email_verified: true } } },
    db
  );
  assert.equal(delegated.uid, "delegated_admin");
});

test("M-03: Telegram verification & claim enforce approved account, membership, and single claim", async () => {
  const db = createMockFirestore();
  db.seed("telegram_tasks/tg_task_1", {
    taskId: "tg_task_1",
    title: "Join Official Channel",
    channelUrl: "https://t.me/taskflow_official",
    channelChatId: "@taskflow_official",
    rewardCoins: 100,
    isActive: true
  });
  db.seed("users/pending_user", {
    userId: "pending_user",
    approvalStatus: "pending",
    balanceCoins: 0
  });
  db.seed("users/rejected_user", {
    userId: "rejected_user",
    approvalStatus: "rejected",
    balanceCoins: 0
  });
  db.seed("users/suspended_user", {
    userId: "suspended_user",
    approvalStatus: "suspended",
    balanceCoins: 0
  });
  db.seed("users/approved_member", {
    userId: "approved_member",
    approvalStatus: "approved",
    balanceCoins: 0,
    balanceCents: 0,
    eligibleCoins: 0,
    promotionalCoins: 0
  });
  db.seed("telegram_accounts/approved_member", {
    userId: "approved_member",
    telegramUserId: "900112233",
    username: "member_ok"
  });
  db.seed("users/approved_non_member", {
    userId: "approved_non_member",
    approvalStatus: "approved",
    balanceCoins: 0,
    balanceCents: 0,
    eligibleCoins: 0,
    promotionalCoins: 0
  });
  db.seed("telegram_accounts/approved_non_member", {
    userId: "approved_non_member",
    telegramUserId: "900999888",
    username: "non_member"
  });

  for (const blockedUid of ["pending_user", "rejected_user", "suspended_user"]) {
    await assert.rejects(
      () =>
        handleVerifyTelegramTaskMembership(
          { auth: { uid: blockedUid }, data: { taskId: "tg_task_1" } },
          { db, botToken: "123456:TEST_BOT_TOKEN" }
        ),
      (err) => err.code === "permission-denied"
    );
  }
  assert.equal(db.listByPrefix("telegram_link_tokens/").length, 0);

  const nonMemberChat = async () => ({ ok: true, result: { status: "left" } });
  await assert.rejects(
    () =>
      handleVerifyTelegramTaskMembership(
        { auth: { uid: "approved_non_member" }, data: { taskId: "tg_task_1" } },
        { db, botToken: "123456:TEST_BOT_TOKEN", getChatMember: nonMemberChat }
      ),
    (err) => err.code === "permission-denied"
  );
  await assert.rejects(
    () =>
      handleClaimTelegramTaskReward(
        { auth: { uid: "approved_non_member" }, data: { taskId: "tg_task_1" } },
        { db, botToken: "123456:TEST_BOT_TOKEN", getChatMember: nonMemberChat }
      ),
    (err) => err.code === "failed-precondition" || err.code === "permission-denied"
  );

  const memberChat = async () => ({ ok: true, result: { status: "member" } });
  const memberVerify = await handleVerifyTelegramTaskMembership(
    { auth: { uid: "approved_member" }, data: { taskId: "tg_task_1" } },
    { db, botToken: "123456:TEST_BOT_TOKEN", getChatMember: memberChat }
  );
  assert.equal(memberVerify.verified, true);

  const memberClaim = await handleClaimTelegramTaskReward(
    { auth: { uid: "approved_member" }, data: { taskId: "tg_task_1" } },
    { db, botToken: "123456:TEST_BOT_TOKEN", getChatMember: memberChat }
  );
  assert.equal(memberClaim.rewardCoins, 100);
  const memberProfile = db.read("users/approved_member");
  assert.equal(memberProfile.balanceCoins, 100);

  await assert.rejects(
    () =>
      handleClaimTelegramTaskReward(
        { auth: { uid: "approved_member" }, data: { taskId: "tg_task_1" } },
        { db, botToken: "123456:TEST_BOT_TOKEN", getChatMember: memberChat }
      ),
    (err) => err.code === "already-exists"
  );
});

test("M-02: createCampaignClick, reviewCampaignConversion, and admin_logs never expose raw clickId or conv_<clickId>", async () => {
  const nowRef = { value: 1_765_000_000_000 };
  const db = createMockFirestore();
  const secret = "m02_hmac_secret";
  const deps = makeDeps(db, nowRef, { CAMPAIGN_POSTBACK_SECRET: secret });

  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/net_alpha`, {
    networkId: "net_alpha",
    name: "Alpha",
    adapterType: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
    isActive: true,
    capabilities: { supportsIncentivizedTraffic: true, supportsPostback: true },
    allowedDestinationHosts: ["offer.example.com"],
    parameterMapping: {
      clickIdParam: "click_id",
      externalTxnParam: "txn_id",
      statusParam: "status",
      signatureParam: "sig"
    },
    signatureConfig: {
      algorithm: "HMAC_SHA256",
      queryParamName: "sig",
      secretEnvKey: "CAMPAIGN_POSTBACK_SECRET",
      signingMode: "SORTED_PARAMS"
    }
  });
  db.seed(`${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS}/camp_m02`, {
    campaignId: "camp_m02",
    networkId: "net_alpha",
    title: "Offer M02",
    isActive: true,
    verificationMode: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
    rewardTf: 250,
    submissionBudget: 10,
    usedSubmissions: 0,
    rewardBudgetTf: 5000,
    spentBudgetTf: 0,
    allowedCountries: ["ALL"],
    allowedPlatforms: ["ANDROID"],
    trackingBaseUrl: "https://offer.example.com/landing",
    conversionRequirements: { requireAdminApproval: true }
  });
  db.seed("campaigns/camp_m02", { id: "camp_m02", title: "Offer M02", status: "ACTIVE" });
  db.seed("users/user_m02", {
    userId: "user_m02",
    displayName: "M02 User",
    email: "m02@example.com",
    role: "user",
    approvalStatus: "approved",
    balanceCents: 0,
    balanceCoins: 0,
    eligibleCoins: 0,
    promotionalCoins: 0,
    completedTasksCount: 0
  });
  db.seed("admins/admin_m02", {
    userId: "admin_m02",
    email: BOOTSTRAPPED_ADMIN_EMAIL
  });

  const clickRes = await handleCreateCampaignClick(
    {
      auth: { uid: "user_m02" },
      app: { appId: "1:123:android:abc" },
      data: { campaignId: "camp_m02", devicePlatform: "ANDROID" },
      rawRequest: { headers: { "x-appengine-country": "IN" } }
    },
    deps
  );
  assert.equal(clickRes.clickId, undefined);
  const storedClickId = (/clk_[a-f0-9]{32}/.exec(clickRes.trackingUrl) || [])[0] || "";
  assert.match(storedClickId, /^clk_/);

  const q = {
    networkId: "net_alpha",
    click_id: storedClickId,
    txn_id: "m02_txn_1",
    status: "approved"
  };
  q.sig = signSortedParams(q, secret);
  const pbRes = makeMockRes();
  await handleCampaignPostback({ method: "GET", query: q, headers: {} }, pbRes, deps);
  assert.equal(pbRes.statusCode, 200);
  assert.equal(pbRes.payload.state, CAMPAIGN_CONVERSION_STATES.PENDING);

  const reviewRes = await handleReviewCampaignConversion(
    {
      auth: { uid: "admin_m02", token: { email: BOOTSTRAPPED_ADMIN_EMAIL, email_verified: true } },
      data: {
        conversionId: pbRes.payload.conversionId,
        action: "APPROVE",
        reason: "Verified conversion"
      }
    },
    deps
  );
  assert.equal(reviewRes.clickId, undefined);
  assert.equal(reviewRes.conversionId, undefined);
  assert.equal(reviewRes.creditedTransactionId, undefined);
  assert.ok(reviewRes.moderationId && reviewRes.moderationId.startsWith("mod_"));
  assert.equal(JSON.stringify(reviewRes).includes(storedClickId), false);

  const logs = db.listByPrefix("admin_logs/");
  assert.ok(logs.length >= 1);
  for (const log of logs) {
    assert.equal(JSON.stringify(log.data).includes(storedClickId), false);
    assert.ok(String(log.data.targetId || "").startsWith("mod_"));
  }
});
