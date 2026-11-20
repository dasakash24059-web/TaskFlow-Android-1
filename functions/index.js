const crypto = require("crypto");
const {
  CAMPAIGN_TRACKING_COLLECTIONS,
  CAMPAIGN_CONVERSION_LOCKS_COLLECTION,
  SECRET_ENV_KEY_REGEX,
  CAMPAIGN_VERIFICATION_MODES,
  CAMPAIGN_CONVERSION_STATES,
  canTransitionConversionState,
  generateSecureClickId,
  buildExternalTxnLockId,
  tfToBalanceCents,
  computeCampaignApprovedWallet,
  computeCampaignReversedWallet,
  verifyCampaignPostbackSignature,
  toMillisValue,
  validateDestinationUrl,
  isValidVerificationMode,
  resolveCountryTrustContext,
  resolveDeviceTrustContext,
  buildSafeTrackingUrl,
  CampaignNetworkAdapterRegistry
} = require("./campaign_tracking");

let onRequest;
let onCall;
let onSchedule;
let HttpsError;
let defineSecret;
let admin;
let db;

const databaseId =
  process.env.FIRESTORE_DATABASE_ID ||
  "ai-studio-android-taskflow-f47fe501-b47e-4e0e-8222-905e3d5397ff";

try {
  ({ onRequest, onCall, HttpsError } = require("firebase-functions/v2/https"));
  try {
    ({ onSchedule } = require("firebase-functions/v2/scheduler"));
  } catch (_) {
    onSchedule = null;
  }
  ({ defineSecret } = require("firebase-functions/params"));
  admin = require("firebase-admin");
  if (!admin.apps.length) {
    admin.initializeApp();
  }
  const { getFirestore } = require("firebase-admin/firestore");
  db =
    databaseId && databaseId !== "(default)" && !process.env.FIRESTORE_EMULATOR_HOST
      ? getFirestore(databaseId)
      : admin.firestore();
} catch (_) {
  HttpsError = class HttpsError extends Error {
    constructor(code, message) {
      super(message);
      this.code = code;
    }
  };
  onRequest = (_opts, handler) => handler || _opts;
  onCall = (_opts, handler) => handler || _opts;
  onSchedule = null;
  defineSecret = (name) => ({
    value: () => process.env[name] || ""
  });
  admin = { apps: [] };
  db = null;
}

// Define secret parameters for Unity S2S HMAC-MD5 verification.
// Production deployments MUST configure UNITY_S2S_SECRET (or UNITY_ADS_SECRET).
const unityS2SSecret = defineSecret("UNITY_S2S_SECRET");
const unityAdsSecret = defineSecret("UNITY_ADS_SECRET");
const telegramBotToken = defineSecret("TELEGRAM_BOT_TOKEN");
const telegramWebhookSecret = defineSecret("TELEGRAM_WEBHOOK_SECRET");
const campaignPostbackSecret = defineSecret("CAMPAIGN_POSTBACK_SECRET");

function resolveTelegramBotToken(deps = {}) {
  if (typeof deps.botToken === "string" && deps.botToken.trim()) {
    return deps.botToken.trim();
  }
  const envToken = (process.env.TELEGRAM_BOT_TOKEN || "").trim();
  if (envToken) return envToken;
  try {
    const val = (telegramBotToken.value() || "").trim();
    if (val) return val;
  } catch (_) {}
  return "";
}

function resolveTelegramWebhookSecret(deps = {}) {
  if (typeof deps.webhookSecret === "string" && deps.webhookSecret.trim()) {
    return deps.webhookSecret.trim();
  }
  const envSecret = (process.env.TELEGRAM_WEBHOOK_SECRET || "").trim();
  if (envSecret) return envSecret;
  try {
    const val = (telegramWebhookSecret.value() || "").trim();
    if (val) return val;
  } catch (_) {}
  return "";
}

const https = require("https");

function callTelegramApi(botToken, method, params = {}) {
  return new Promise((resolve, reject) => {
    const search = new URLSearchParams();
    Object.entries(params || {}).forEach(([k, v]) => {
      if (v !== undefined && v !== null) {
        search.append(k, String(v));
      }
    });
    const qs = search.toString();
    const url = `https://api.telegram.org/bot${encodeURIComponent(botToken)}/${method}${qs ? `?${qs}` : ""}`;
    https
      .get(url, (res) => {
        let data = "";
        res.on("data", (chunk) => {
          data += chunk;
        });
        res.on("end", () => {
          try {
            resolve(JSON.parse(data));
          } catch (e) {
            reject(new Error(`Failed to parse Telegram ${method} API response`));
          }
        });
      })
      .on("error", (err) => {
        reject(err);
      });
  });
}

function callTelegramGetChatMember(botToken, chatId, userId) {
  return callTelegramApi(botToken, "getChatMember", {
    chat_id: chatId,
    user_id: userId
  });
}

function callTelegramGetMe(botToken) {
  return callTelegramApi(botToken, "getMe");
}

function callTelegramGetUpdates(botToken, limit = 100) {
  return callTelegramApi(botToken, "getUpdates", {
    limit,
    allowed_updates: JSON.stringify(["message"])
  });
}

const DAILY_VIDEO_LIMIT = 25;
const DAILY_EARN_LIMIT_COINS = 5000;
const SCRATCH_CARD_DAILY_LIMIT = 50;
const SCRATCH_CARD_ADS_REQUIRED = 2;

const SPIN_DAILY_LIMIT = 50;
const FREE_SPIN_COOLDOWN_MS = 24 * 60 * 60 * 1000;
const EXTRA_SPIN_ADS_REQUIRED = 2;
const SPIN_SEGMENTS_COINS = [10, 20, 30, 40, 50, 100, 125, 150];
const SPIN_SEGMENT_WEIGHTS = [24, 20, 16, 14, 11, 7, 5, 3];

// 30-Day Daily Check-in Coin Schedule (aligned with Android DailyCheckInConstants.REWARDS_COINS)
const DAILY_CHECK_IN_REWARDS_COINS = [
  25, 35, 50, 60, 75, 85, 100, 110, 125, 150,
  160, 175, 185, 200, 210, 225, 250, 260, 275, 285,
  300, 325, 350, 375, 400, 425, 450, 500, 750, 1000
];

function generateWatchEarnRewardCoins(randomFn = Math.random) {
  const roll = typeof randomFn === "function" ? randomFn() : Math.random();
  return Math.floor(roll * (125 - 63 + 1)) + 63;
}

function pickWeightedSpinSegmentIndex(randomFn = Math.random) {
  const totalWeight = SPIN_SEGMENT_WEIGHTS.reduce((sum, w) => sum + w, 0);
  const rawRoll = typeof randomFn === "function" ? randomFn() : Math.random();
  const boundedRoll = Math.max(0, Math.min(0.999999999999, Number(rawRoll) || 0));
  const target = Math.floor(boundedRoll * totalWeight);
  let cumulative = 0;
  for (let i = 0; i < SPIN_SEGMENT_WEIGHTS.length; i++) {
    cumulative += SPIN_SEGMENT_WEIGHTS[i];
    if (target < cumulative) return i;
  }
  return 0;
}

function makeTimestampFromMillis(ms, deps = {}) {
  if (deps && typeof deps.timestampFromMillis === "function") {
    return deps.timestampFromMillis(ms);
  }
  if (admin?.firestore?.Timestamp?.fromMillis) {
    return admin.firestore.Timestamp.fromMillis(ms);
  }
  return new Date(ms);
}

function toMillisSafe(val) {
  if (val === null || val === undefined) return null;
  if (typeof val === "number" && Number.isFinite(val)) return val;
  if (val instanceof Date) return val.getTime();
  if (typeof val.toMillis === "function") return val.toMillis();
  if (typeof val.toDate === "function") return val.toDate().getTime();
  if (typeof val._seconds === "number") {
    return val._seconds * 1000 + Math.floor((val._nanoseconds || 0) / 1e6);
  }
  if (typeof val.seconds === "number") {
    return val.seconds * 1000 + Math.floor((val.nanoseconds || 0) / 1e6);
  }
  return null;
}

/**
 * Computes consistent promotional wallet credit without modifying withdrawable balanceCents or eligibleCoins.
 * Conversion convention: ₹1 (100 paise) = 125 Coins.
 */
function computeUpdatedPromoWallet(userData, rewardCoins) {
  const oldPromo = Number(userData.promotionalCoins || 0);
  const oldBalanceCents = Number(userData.balanceCents || 0);
  const oldCentsCoins = Math.floor((oldBalanceCents * 125) / 100);
  const oldEligible = Math.max(Number(userData.eligibleCoins || 0), oldCentsCoins);
  const oldBalanceCoins = Math.max(
    Number(userData.balanceCoins || 0),
    oldEligible + oldPromo
  );
  const newPromo = oldPromo + rewardCoins;
  const newBalanceCoins = oldBalanceCoins + rewardCoins;
  return {
    oldPromo,
    oldBalanceCents,
    oldEligible,
    oldBalanceCoins,
    newPromo,
    newBalanceCoins
  };
}

function syncLeaderboardProjection(
  transaction,
  firestoreDb,
  userId,
  displayName,
  oldBalanceCents,
  newBalanceCoins,
  completedTasksCount,
  ts,
  approvalStatus = "approved"
) {
  const leaderboardRef = firestoreDb.collection("leaderboard").doc(userId);
  transaction.set(
    leaderboardRef,
    {
      userId,
      displayName,
      balanceCents: oldBalanceCents,
      balanceCoins: newBalanceCoins,
      completedTasksCount,
      approvalStatus: approvalStatus || "approved",
      updatedAt: ts
    },
    { merge: true }
  );
}


/**
 * Resolves the mandatory Unity S2S secret from environment or Secret Manager.
 * Returns empty string if unconfigured.
 */
function resolveUnityS2SSecret() {
  const envSecret =
    (process.env.UNITY_S2S_SECRET || process.env.UNITY_ADS_SECRET || "").trim();
  if (envSecret) {
    return envSecret;
  }
  try {
    const val = (unityS2SSecret.value() || "").trim();
    if (val) return val;
  } catch (_) {
    // Ignore when secret parameter is not bound in current context
  }
  try {
    const fallbackVal = (unityAdsSecret.value() || "").trim();
    if (fallbackVal) return fallbackVal;
  } catch (_) {
    // Ignore when secret parameter is not bound in current context
  }
  return "";
}

/**
 * Standard business date string (YYYY-MM-DD) for India (Asia/Kolkata, UTC+05:30).
 */
function getTodayDateString(now = new Date()) {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "Asia/Kolkata",
    year: "numeric",
    month: "2-digit",
    day: "2-digit"
  }).formatToParts(now);
  const y = parts.find((p) => p.type === "year").value;
  const m = parts.find((p) => p.type === "month").value;
  const d = parts.find((p) => p.type === "day").value;
  return `${y}-${m}-${d}`;
}

/**
 * Standard yesterday date string (YYYY-MM-DD) for India (Asia/Kolkata, UTC+05:30).
 */
function getYesterdayDateString(now = new Date()) {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "Asia/Kolkata",
    year: "numeric",
    month: "numeric",
    day: "numeric"
  }).formatToParts(now);
  const y = parseInt(parts.find((p) => p.type === "year").value, 10);
  const m = parseInt(parts.find((p) => p.type === "month").value, 10) - 1;
  const d = parseInt(parts.find((p) => p.type === "day").value, 10);
  const utcDate = new Date(Date.UTC(y, m, d - 1, 12, 0, 0));
  return getTodayDateString(utcDate);
}

/**
 * Calculates the server-authoritative reward in Coins for a 30-day Daily Check-in streak (1..30).
 */
function getDailyCheckInRewardCoins(streakDay) {
  const day = Number(streakDay);
  if (!Number.isInteger(day) || day < 1 || day > DAILY_CHECK_IN_REWARDS_COINS.length) {
    throw new HttpsError(
      "invalid-argument",
      "Invalid streakDay. Must be an integer between 1 and 30."
    );
  }
  return DAILY_CHECK_IN_REWARDS_COINS[day - 1];
}

// Legacy compatibility alias returning the authoritative Coin amount
function getDailyCheckInRewardCents(streakDay) {
  return getDailyCheckInRewardCoins(streakDay);
}

/**
 * Calculates the server-authoritative Daily Check-in eligibility and next streak day (1..30).
 */
function calculateServerDailyCheckInState(
  lastCheckInDate,
  currentStreak,
  today = getTodayDateString(),
  yesterday = getYesterdayDateString()
) {
  const rawStreak = Number.isInteger(Number(currentStreak))
    ? Number(currentStreak)
    : 0;
  const cleanLastDate = typeof lastCheckInDate === "string" ? lastCheckInDate.trim() : "";

  if (cleanLastDate === today) {
    const activeDay = rawStreak >= 1 && rawStreak <= 30 ? rawStreak : 1;
    const nextDay = activeDay >= 30 ? 1 : activeDay + 1;
    const rewardCoins = getDailyCheckInRewardCoins(nextDay);
    return {
      canCheckInToday: false,
      currentStreak: activeDay,
      expectedStreakDay: nextDay,
      rewardCoins,
      rewardCents: rewardCoins,
      today,
      yesterday
    };
  }

  if (cleanLastDate === yesterday) {
    const nextStreakDay = rawStreak >= 1 && rawStreak < 30 ? rawStreak + 1 : 1;
    const rewardCoins = getDailyCheckInRewardCoins(nextStreakDay);
    return {
      canCheckInToday: true,
      currentStreak: rawStreak,
      expectedStreakDay: nextStreakDay,
      rewardCoins,
      rewardCents: rewardCoins,
      today,
      yesterday
    };
  }

  const rewardCoins = getDailyCheckInRewardCoins(1);
  return {
    canCheckInToday: true,
    currentStreak: 0,
    expectedStreakDay: 1,
    rewardCoins,
    rewardCents: rewardCoins,
    today,
    yesterday
  };
}


/**
 * Official Unity Ads S2S Callback HMAC-MD5 Signature Verification.
 *
 * Security Rules:
 * 1. `secret` is MANDATORY — if missing or blank, always returns false.
 * 2. `query.hmac` is MANDATORY — unsigned requests always return false.
 * 3. Sorts all query parameters except `hmac` alphabetically as `key=value` joined by commas,
 *    computes HMAC-MD5 using `secret`, and compares with `crypto.timingSafeEqual`.
 */
function verifyUnityS2SSignature(query, secret) {
  if (!query || typeof query !== "object") {
    return false;
  }
  if (!secret || typeof secret !== "string" || secret.trim() === "") {
    return false;
  }
  const receivedHmac = query.hmac;
  if (!receivedHmac || typeof receivedHmac !== "string") {
    return false;
  }

  const parameterPairs = Object.keys(query)
    .filter((key) => key !== "hmac" && query[key] !== undefined && query[key] !== null)
    .sort()
    .map((key) => `${key}=${query[key]}`);

  const paramString = parameterPairs.join(",");
  const expectedHmac = crypto
    .createHmac("md5", secret)
    .update(paramString, "utf8")
    .digest("hex");

  const expectedBuf = Buffer.from(expectedHmac.toLowerCase(), "utf8");
  const receivedBuf = Buffer.from(receivedHmac.toLowerCase(), "utf8");

  if (expectedBuf.length !== receivedBuf.length) {
    return false;
  }

  return crypto.timingSafeEqual(expectedBuf, receivedBuf);
}

const ALLOWED_REWARD_SESSION_TYPES = [
  "WATCH_AND_EARN",
  "WATCH_AND_EARN_CLAIM",
  "DAILY_CHECK_IN",
  "SCRATCH_CARD_UNLOCK",
  "EXTRA_SPIN_UNLOCK"
];

/**
 * 1. CREATE REWARD SESSION (Callable Handler)
 * Called by the Android client BEFORE showing a Unity Rewarded Ad.
 * Enforces user approval status, daily limits, and Daily Check-in eligibility on the server.
 */
async function handleCreateRewardSession(request, deps = {}) {
  if (!request.auth || !request.auth.uid) {
    throw new HttpsError("unauthenticated", "User must be logged in.");
  }

  const firestoreDb = deps.db || db;
  const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();
  const userId = String(request.auth.uid).trim();
  const rewardType = request.data?.rewardType || "WATCH_AND_EARN";

  if (!ALLOWED_REWARD_SESSION_TYPES.includes(rewardType)) {
    throw new HttpsError("invalid-argument", "Invalid reward type.");
  }

  const userRef = firestoreDb.collection("users").doc(userId);
  const userSnap = await userRef.get();
  if (!userSnap.exists) {
    throw new HttpsError("not-found", "User profile not found.");
  }

  const userData = userSnap.data() || {};
  if (userData.approvalStatus !== "approved") {
    throw new HttpsError(
      "permission-denied",
      "Your account must be approved by an administrator before earning rewards."
    );
  }

  const now = new Date(nowMillis);
  const today = getTodayDateString(now);
  const yesterday = getYesterdayDateString(now);

  let rewardCoins = 0;
  let validatedStreakDay = null;

  if (rewardType === "WATCH_AND_EARN" || rewardType === "WATCH_AND_EARN_CLAIM") {
    const statId = `${userId}_${today}`;
    const [topStatSnap, subStatSnap] = await Promise.all([
      firestoreDb.collection("watch_stats").doc(statId).get(),
      userRef.collection("watch_stats").doc(today).get()
    ]);

    const currentVideos = Math.max(
      topStatSnap.exists ? Number(topStatSnap.data().videosWatched || 0) : 0,
      subStatSnap.exists ? Number(subStatSnap.data().videosWatched || 0) : 0
    );

    if (currentVideos >= DAILY_VIDEO_LIMIT) {
      throw new HttpsError(
        "resource-exhausted",
        `Daily limit of ${DAILY_VIDEO_LIMIT} watched videos reached.`
      );
    }

    rewardCoins =
      rewardType === "WATCH_AND_EARN"
        ? generateWatchEarnRewardCoins(deps.random)
        : 0;
  } else if (rewardType === "DAILY_CHECK_IN") {
    const checkInSnap = await userRef.collection("check_ins").doc(today).get();
    const checkInState = calculateServerDailyCheckInState(
      userData.lastCheckInDate || "",
      userData.checkInStreak || 0,
      today,
      yesterday
    );

    if (checkInSnap.exists || !checkInState.canCheckInToday) {
      throw new HttpsError(
        "already-exists",
        "You have already claimed today's daily check-in reward."
      );
    }

    if (request.data?.streakDay !== undefined && request.data?.streakDay !== null) {
      const requestedDay = Number(request.data.streakDay);
      if (requestedDay !== checkInState.expectedStreakDay) {
        throw new HttpsError(
          "invalid-argument",
          `Invalid streakDay ${requestedDay}. Expected Day ${checkInState.expectedStreakDay}.`
        );
      }
    }

    validatedStreakDay = checkInState.expectedStreakDay;
    rewardCoins = checkInState.rewardCoins;
  } else if (rewardType === "SCRATCH_CARD_UNLOCK") {
    const scratchStatsDoc = await userRef.collection("scratch_stats").doc(today).get();
    const completedToday = scratchStatsDoc.exists
      ? Number(scratchStatsDoc.data().scratchesCompletedToday || 0)
      : 0;
    if (completedToday >= SCRATCH_CARD_DAILY_LIMIT) {
      throw new HttpsError(
        "resource-exhausted",
        "Daily scratch limit of 50 reached."
      );
    }
    rewardCoins = 0;
  } else if (rewardType === "EXTRA_SPIN_UNLOCK") {
    if (userData.extraSpinAvailable === true) {
      throw new HttpsError(
        "failed-precondition",
        "Extra Spin is already unlocked. Use your Extra Spin before watching more ads."
      );
    }
    const spinStatsDoc = await userRef.collection("spin_stats").doc(today).get();
    const spinsUsed = spinStatsDoc.exists ? Number(spinStatsDoc.data().spinsUsed || 0) : 0;
    if (spinsUsed >= SPIN_DAILY_LIMIT) {
      throw new HttpsError(
        "resource-exhausted",
        `Daily spin limit (${SPIN_DAILY_LIMIT}) reached.`
      );
    }
    rewardCoins = 0;
  }

  const sessionId =
    typeof deps.sessionIdGenerator === "function"
      ? deps.sessionIdGenerator()
      : crypto.randomBytes(16).toString("hex");
  const expiresAt = makeTimestampFromMillis(nowMillis + 10 * 60 * 1000, deps);
  const ts = getDefaultServerTimestamp(deps);

  const sessionPayload = {
    sessionId,
    userId,
    rewardType,
    rewardCoins,
    rewardCents: rewardCoins, // Legacy compatibility alias matching coin reward
    dateKey: today,
    status: "PENDING",
    s2sVerified: rewardType === "DAILY_CHECK_IN", // Daily check-in does not require ad S2S
    used: false,
    claimed: false,
    createdAt: ts,
    expiresAt
  };

  if (validatedStreakDay !== null) {
    sessionPayload.streakDay = validatedStreakDay;
  }

  await firestoreDb.collection("reward_sessions").doc(sessionId).set(sessionPayload);

  return {
    sessionId,
    rewardType,
    rewardCoins,
    rewardCents: rewardCoins,
    streakDay: validatedStreakDay,
    dateKey: today
  };
}

exports.createRewardSession = onCall(async (request) => handleCreateRewardSession(request));

/**
 * 2. UNITY ADS SERVER-TO-SERVER (S2S) CALLBACK (HTTP Handler)
 * Triggered by Unity Ads servers when a user finishes watching a rewarded ad.
 * Strictly requires UNITY_S2S_SECRET and a valid HMAC-MD5 signature.
 * Marks the session as VERIFIED (`s2sVerified = true`) so the user can claim or record it.
 */
async function handleUnityAdsS2SCallback(req, res, deps = {}) {
  try {
    if (req.method !== "GET" && req.method !== "POST") {
      return res.status(405).send("Method Not Allowed");
    }

    const secret =
      typeof deps.secret === "string"
        ? deps.secret.trim()
        : resolveUnityS2SSecret();
    if (!secret) {
      console.error(
        "CRITICAL: UNITY_S2S_SECRET is not configured. Rejecting S2S callback."
      );
      return res
        .status(500)
        .send("Server configuration error: UNITY_S2S_SECRET is mandatory");
    }

    const { oid, sid, hmac } = req.query || {};

    if (!oid || !sid || !hmac) {
      console.warn("Missing required parameters in S2S callback:", req.query);
      return res.status(401).send("Missing required parameters or signature");
    }

    if (!verifyUnityS2SSignature(req.query, secret)) {
      console.error("Invalid HMAC-MD5 signature for Unity S2S callback:", req.query);
      return res.status(401).send("Invalid signature");
    }

    const firestoreDb = deps.db || db;
    const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();
    const ts = getDefaultServerTimestamp(deps);
    const sessionId = String(sid).trim();
    const orderId = String(oid).trim();
    const sessionRef = firestoreDb.collection("reward_sessions").doc(sessionId);
    const callbackRef = firestoreDb.collection("processed_unity_callbacks").doc(orderId);

    await firestoreDb.runTransaction(async (transaction) => {
      const callbackDoc = await transaction.get(callbackRef);
      if (callbackDoc.exists) {
        throw new Error("Duplicate transaction");
      }

      const sessionDoc = await transaction.get(sessionRef);
      if (!sessionDoc.exists) {
        throw new Error("Session not found");
      }

      const session = sessionDoc.data();
      if (
        session.used ||
        session.claimed ||
        session.status === "CLAIMED" ||
        session.status === "CONSUMED"
      ) {
        throw new Error("Session already used");
      }

      const expiresAtMillis = toMillisSafe(session.expiresAt);
      if (expiresAtMillis !== null && expiresAtMillis < nowMillis) {
        transaction.update(sessionRef, { status: "EXPIRED" });
        throw new Error("Session expired");
      }

      transaction.set(callbackRef, {
        oid: orderId,
        sessionId,
        userId: session.userId,
        processedAt: ts
      });

      if (session.status === "PENDING") {
        transaction.update(sessionRef, {
          s2sVerified: true,
          status: "VERIFIED",
          unityOrderId: orderId,
          verifiedAt: ts
        });
      }
    });

    return res.status(200).send("1");
  } catch (error) {
    console.error("Error processing S2S callback:", error.message);
    if (
      error.message === "Duplicate transaction" ||
      error.message === "Session already used"
    ) {
      return res.status(200).send("1");
    }
    return res.status(400).send(error.message);
  }
}

exports.unityAdsS2SCallback = onRequest(
  { secrets: [unityS2SSecret, unityAdsSecret] },
  async (req, res) => handleUnityAdsS2SCallback(req, res)
);

/**
 * Shared server-authoritative transactional Daily Check-in crediting helper.
 * Used by both `claimDailyCheckIn` and `claimRewardSession(DAILY_CHECK_IN)`.
 */
function executeDailyCheckInCreditInTransaction({
  transaction,
  firestoreDb,
  userRef,
  userId,
  userData,
  checkInDocRef,
  checkInDoc,
  txRef,
  existingTxDoc,
  notifRef,
  today,
  yesterday,
  ts,
  sessionRef = null,
  sessionId = null
}) {
  const checkInState = calculateServerDailyCheckInState(
    userData.lastCheckInDate || "",
    userData.checkInStreak || 0,
    today,
    yesterday
  );

  if (checkInDoc.exists || (existingTxDoc && existingTxDoc.exists) || !checkInState.canCheckInToday) {
    throw new HttpsError(
      "already-exists",
      "You have already claimed today's daily check-in reward."
    );
  }

  const streakDay = checkInState.expectedStreakDay;
  const rewardCoins = getDailyCheckInRewardCoins(streakDay);
  const completedTasksCount = Number(userData.completedTasksCount || 0);
  const displayName = (userData.displayName || "TaskFlow Member").trim() || "TaskFlow Member";

  const { oldBalanceCents, newPromo, newBalanceCoins } = computeUpdatedPromoWallet(
    userData,
    rewardCoins
  );

  if (sessionRef) {
    transaction.update(sessionRef, {
      status: "CLAIMED",
      used: true,
      claimed: true,
      claimedAt: ts
    });
  }

  const checkInRecord = {
    userId,
    dateKey: today,
    streakDay,
    rewardCoins,
    rewardCents: rewardCoins,
    claimedAt: ts
  };
  if (sessionId) {
    checkInRecord.sessionId = sessionId;
  }
  transaction.set(checkInDocRef, checkInRecord);

  // Preserve balanceCents & eligibleCoins; credit promotionalCoins + balanceCoins
  transaction.update(userRef, {
    promotionalCoins: newPromo,
    balanceCoins: newBalanceCoins,
    checkInStreak: streakDay,
    lastCheckInDate: today,
    lastCheckInTimestamp: ts,
    updatedAt: ts
  });

  syncLeaderboardProjection(
    transaction,
    firestoreDb,
    userId,
    displayName,
    oldBalanceCents,
    newBalanceCoins,
    completedTasksCount,
    ts
  );

  transaction.set(txRef, {
    userId,
    userName: displayName,
    type: "DAILY_CHECKIN",
    title: `Daily Check-in Reward (Day ${streakDay}/30)`,
    amountCoins: rewardCoins,
    amountCents: Math.floor((rewardCoins * 100) / 125),
    status: "CREDITED",
    referenceId: today,
    createdAt: ts
  });

  transaction.set(notifRef, {
    userId,
    title: "Daily Check-in Claimed! 🎉",
    body: `You received +${rewardCoins} Coins for Day ${streakDay} streak check-in!`,
    message: `You received +${rewardCoins} Coins for Day ${streakDay} streak check-in!`,
    type: "DAILY_CHECKIN",
    rewardCoins,
    referenceId: today,
    isRead: false,
    createdAt: ts
  });

  return {
    success: true,
    rewardType: "DAILY_CHECK_IN",
    streakDay,
    rewardCoins,
    rewardCents: rewardCoins,
    newPromotionalCoins: newPromo,
    newBalanceCoins,
    newBalanceCents: newBalanceCoins,
    dateKey: today
  };
}

/**
 * 3. CLAIM REWARD SESSION (Callable Handler)
 * Called by the Android client when claiming a session.
 * - For WATCH_AND_EARN: strictly requires TWO distinct, independently S2S-verified
 *   ad sessions (`sessionId` + `secondSessionId`/`claimSessionId`), preventing reuse
 *   of Ad 1's verification for Ad 2.
 * - For DAILY_CHECK_IN: credits 30-day coin schedule to promotionalCoins + balanceCoins.
 */
async function handleClaimRewardSession(request, deps = {}) {
  if (!request.auth || !request.auth.uid) {
    throw new HttpsError("unauthenticated", "User must be logged in.");
  }

  const firestoreDb = deps.db || db;
  const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();
  const userId = String(request.auth.uid).trim();
  const sessionId = String(request.data?.sessionId || "").trim();
  const rawSecondId =
    request.data?.secondSessionId ?? request.data?.claimSessionId ?? "";
  const secondSessionId = typeof rawSecondId === "string" ? rawSecondId.trim() : "";

  if (!sessionId) {
    throw new HttpsError("invalid-argument", "Session ID is required.");
  }

  const sessionRef = firestoreDb.collection("reward_sessions").doc(sessionId);
  const userRef = firestoreDb.collection("users").doc(userId);

  const now = new Date(nowMillis);
  const today = getTodayDateString(now);
  const yesterday = getYesterdayDateString(now);
  const ts = getDefaultServerTimestamp(deps);

  return await firestoreDb.runTransaction(async (transaction) => {
    const sessionDoc = await transaction.get(sessionRef);
    if (!sessionDoc.exists) {
      throw new HttpsError("not-found", "Invalid reward session.");
    }

    const session = sessionDoc.data() || {};
    if (session.userId !== userId) {
      throw new HttpsError("permission-denied", "Session belongs to another user.");
    }

    if (
      session.used ||
      session.claimed ||
      session.status === "CLAIMED" ||
      session.status === "CONSUMED"
    ) {
      throw new HttpsError(
        "already-exists",
        "Reward has already been claimed for this session."
      );
    }

    const expiresAtMillis = toMillisSafe(session.expiresAt);
    if (expiresAtMillis === null || expiresAtMillis < nowMillis) {
      throw new HttpsError("deadline-exceeded", "Reward session has expired.");
    }

    if (!["WATCH_AND_EARN", "DAILY_CHECK_IN"].includes(session.rewardType)) {
      throw new HttpsError(
        "invalid-argument",
        "This reward session cannot be claimed via claimRewardSession."
      );
    }

    if (session.rewardType === "DAILY_CHECK_IN") {
      const checkInDocRef = userRef.collection("check_ins").doc(today);
      const txRef = firestoreDb.collection("transactions").doc(`checkin_${userId}_${today}`);
      const notifRef = userRef.collection("notifications").doc(`notif_checkin_${userId}_${today}`);

      const [userDoc, checkInDoc, existingTxDoc] = await Promise.all([
        transaction.get(userRef),
        transaction.get(checkInDocRef),
        transaction.get(txRef)
      ]);

      if (!userDoc.exists) {
        throw new HttpsError("not-found", "User profile not found.");
      }
      const userData = userDoc.data() || {};
      if (userData.approvalStatus !== "approved") {
        throw new HttpsError(
          "permission-denied",
          "Only approved users can claim rewards."
        );
      }

      return executeDailyCheckInCreditInTransaction({
        transaction,
        firestoreDb,
        userRef,
        userId,
        userData,
        checkInDocRef,
        checkInDoc,
        txRef,
        existingTxDoc,
        notifRef,
        today,
        yesterday,
        ts,
        sessionRef,
        sessionId
      });
    }

    // WATCH_AND_EARN flow: strictly require TWO distinct, independently S2S-verified ad sessions
    if (!secondSessionId) {
      throw new HttpsError(
        "failed-precondition",
        "A second independently S2S-verified rewarded ad session (secondSessionId) is required to claim Watch & Earn rewards."
      );
    }
    if (secondSessionId === sessionId) {
      throw new HttpsError(
        "invalid-argument",
        "Cannot reuse the first ad session verification for the second ad."
      );
    }

    if (session.s2sVerified !== true || session.status !== "VERIFIED") {
      throw new HttpsError(
        "failed-precondition",
        "First reward video completion has not been verified by server yet."
      );
    }

    const secondSessionRef = firestoreDb.collection("reward_sessions").doc(secondSessionId);
    const topStatsRef = firestoreDb.collection("watch_stats").doc(`${userId}_${today}`);
    const subStatsRef = userRef.collection("watch_stats").doc(today);
    const txRef = firestoreDb.collection("transactions").doc(`watch_${sessionId}`);
    const notifRef = userRef.collection("notifications").doc(`notif_watch_${sessionId}`);

    const [userDoc, secondSessionDoc, topStatDoc, subStatDoc, existingTxDoc] =
      await Promise.all([
        transaction.get(userRef),
        transaction.get(secondSessionRef),
        transaction.get(topStatsRef),
        transaction.get(subStatsRef),
        transaction.get(txRef)
      ]);

    if (!userDoc.exists) {
      throw new HttpsError("not-found", "User profile not found.");
    }
    const userData = userDoc.data() || {};
    if (userData.approvalStatus !== "approved") {
      throw new HttpsError(
        "permission-denied",
        "Only approved users can claim rewards."
      );
    }

    if (existingTxDoc.exists) {
      throw new HttpsError(
        "already-exists",
        "Reward has already been claimed for this session."
      );
    }

    if (!secondSessionDoc.exists) {
      throw new HttpsError("not-found", "Second rewarded ad session not found.");
    }
    const secondSession = secondSessionDoc.data() || {};

    if (secondSession.userId !== userId) {
      throw new HttpsError(
        "permission-denied",
        "Second ad session belongs to another user."
      );
    }

    if (!["WATCH_AND_EARN_CLAIM", "WATCH_AND_EARN"].includes(secondSession.rewardType)) {
      throw new HttpsError(
        "invalid-argument",
        "Second session is not a valid Watch & Earn ad session."
      );
    }

    if (
      secondSession.used ||
      secondSession.claimed ||
      secondSession.status === "CLAIMED" ||
      secondSession.status === "CONSUMED"
    ) {
      throw new HttpsError(
        "already-exists",
        "Second ad session has already been used."
      );
    }

    const secondExpiresMs = toMillisSafe(secondSession.expiresAt);
    if (secondExpiresMs === null || secondExpiresMs < nowMillis) {
      throw new HttpsError("deadline-exceeded", "Second rewarded ad session has expired.");
    }

    if (secondSession.s2sVerified !== true || secondSession.status !== "VERIFIED") {
      throw new HttpsError(
        "failed-precondition",
        "Second reward video completion has not been verified by server yet."
      );
    }

    if (
      session.unityOrderId &&
      secondSession.unityOrderId &&
      session.unityOrderId === secondSession.unityOrderId
    ) {
      throw new HttpsError(
        "invalid-argument",
        "Cannot reuse the same Unity S2S verification order for both Watch & Earn ads."
      );
    }

    const topWatched = topStatDoc.exists ? Number(topStatDoc.data().videosWatched || 0) : 0;
    const subWatched = subStatDoc.exists ? Number(subStatDoc.data().videosWatched || 0) : 0;
    const currentVideos = Math.max(topWatched, subWatched);

    const topEarned = topStatDoc.exists
      ? Number(topStatDoc.data().totalEarnedCoins || topStatDoc.data().totalEarnedCents || 0)
      : 0;
    const subEarned = subStatDoc.exists
      ? Number(subStatDoc.data().totalEarnedCoins || subStatDoc.data().totalEarnedCents || 0)
      : 0;
    const currentEarned = Math.max(topEarned, subEarned);

    if (currentVideos >= DAILY_VIDEO_LIMIT) {
      throw new HttpsError(
        "resource-exhausted",
        `Daily limit of ${DAILY_VIDEO_LIMIT} videos reached.`
      );
    }

    // Use strictly server-stored reward from session (never client payload)
    const rawSessionReward = Number(session.rewardCoins ?? session.rewardCents ?? 0);
    if (
      !Number.isInteger(rawSessionReward) ||
      rawSessionReward < 1 ||
      rawSessionReward > 200
    ) {
      throw new HttpsError(
        "failed-precondition",
        "Invalid server reward stored on Watch & Earn session."
      );
    }
    const rewardCoins = rawSessionReward;

    if (currentEarned + rewardCoins > DAILY_EARN_LIMIT_COINS) {
      throw new HttpsError(
        "resource-exhausted",
        "Daily earnings limit exceeded."
      );
    }

    const newVideos = currentVideos + 1;
    const newEarnedCoins = currentEarned + rewardCoins;
    const completedTasksCount = Number(userData.completedTasksCount || 0);
    const displayName = (userData.displayName || "TaskFlow Member").trim() || "TaskFlow Member";

    const { oldBalanceCents, newPromo, newBalanceCoins } = computeUpdatedPromoWallet(
      userData,
      rewardCoins
    );

    // 1. Atomically mark BOTH ad sessions as CLAIMED & single-use
    transaction.update(sessionRef, {
      status: "CLAIMED",
      used: true,
      claimed: true,
      pairedSecondSessionId: secondSessionId,
      claimedAt: ts
    });
    transaction.update(secondSessionRef, {
      status: "CLAIMED",
      used: true,
      claimed: true,
      pairedPrimarySessionId: sessionId,
      claimedAt: ts
    });

    const statsPayload = {
      userId,
      dateKey: today,
      videosWatched: newVideos,
      totalEarnedCoins: newEarnedCoins,
      updatedAt: ts
    };

    // 2. Update both /watch_stats/{uid}_{today} and /users/{uid}/watch_stats/{today}
    transaction.set(topStatsRef, statsPayload, { merge: true });
    transaction.set(subStatsRef, statsPayload, { merge: true });

    // 3. Credit user promotionalCoins + balanceCoins (preserve balanceCents & eligibleCoins)
    transaction.update(userRef, {
      promotionalCoins: newPromo,
      balanceCoins: newBalanceCoins,
      updatedAt: ts
    });

    // 4. Sync public leaderboard projection with canonical schema (preserve balanceCents, no email)
    syncLeaderboardProjection(
      transaction,
      firestoreDb,
      userId,
      displayName,
      oldBalanceCents,
      newBalanceCoins,
      completedTasksCount,
      ts
    );

    // 5. Record transaction history entry
    transaction.set(txRef, {
      userId,
      userName: displayName,
      type: "WATCH_EARN",
      title: `Watch & Earn Video Reward (${newVideos}/${DAILY_VIDEO_LIMIT})`,
      amountCoins: rewardCoins,
      amountCents: Math.floor((rewardCoins * 100) / 125),
      status: "CREDITED",
      referenceId: sessionId,
      secondReferenceId: secondSessionId,
      createdAt: ts
    });

    // 6. Record in-app notification
    transaction.set(notifRef, {
      userId,
      title: "Watch & Earn Reward Credited 🎉",
      body: `You earned +${rewardCoins} Coins for completing Watch & Earn (${newVideos}/${DAILY_VIDEO_LIMIT})!`,
      message: `You earned +${rewardCoins} Coins for completing Watch & Earn (${newVideos}/${DAILY_VIDEO_LIMIT})!`,
      type: "WATCH_EARN_REWARD",
      rewardCoins,
      referenceId: sessionId,
      isRead: false,
      createdAt: ts
    });

    return {
      success: true,
      rewardType: "WATCH_AND_EARN",
      sessionId,
      secondSessionId,
      rewardCoins,
      videosWatched: newVideos,
      totalEarnedCoins: newEarnedCoins,
      newPromotionalCoins: newPromo,
      newBalanceCoins,
      dateKey: today
    };
  });
}

exports.claimRewardSession = onCall(async (request) => handleClaimRewardSession(request));

/**
 * 4. DIRECT DAILY CHECK-IN (Callable Handler)
 * Server-authoritative 30-day Daily Check-in using Asia/Kolkata calendar date.
 * Credits promotionalCoins + balanceCoins while preserving balanceCents & eligibleCoins.
 */
async function handleClaimDailyCheckIn(request, deps = {}) {
  if (!request.auth || !request.auth.uid) {
    throw new HttpsError("unauthenticated", "User must be logged in.");
  }

  const firestoreDb = deps.db || db;
  const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();
  const userId = String(request.auth.uid).trim();
  const userRef = firestoreDb.collection("users").doc(userId);

  const now = new Date(nowMillis);
  const today = getTodayDateString(now);
  const yesterday = getYesterdayDateString(now);
  const ts = getDefaultServerTimestamp(deps);

  const checkInDocRef = userRef.collection("check_ins").doc(today);
  const txRef = firestoreDb.collection("transactions").doc(`checkin_${userId}_${today}`);
  const notifRef = userRef.collection("notifications").doc(`notif_checkin_${userId}_${today}`);

  return await firestoreDb.runTransaction(async (transaction) => {
    const [userDoc, checkInDoc, existingTxDoc] = await Promise.all([
      transaction.get(userRef),
      transaction.get(checkInDocRef),
      transaction.get(txRef)
    ]);

    if (!userDoc.exists) {
      throw new HttpsError("not-found", "User profile not found.");
    }

    const userData = userDoc.data() || {};
    if (userData.approvalStatus !== "approved") {
      throw new HttpsError(
        "permission-denied",
        "Your account must be approved by an administrator before claiming daily check-in rewards."
      );
    }

    return executeDailyCheckInCreditInTransaction({
      transaction,
      firestoreDb,
      userRef,
      userId,
      userData,
      checkInDocRef,
      checkInDoc,
      txRef,
      existingTxDoc,
      notifRef,
      today,
      yesterday,
      ts
    });
  });
}

exports.claimDailyCheckIn = onCall(async (request) => handleClaimDailyCheckIn(request));

/**
 * 5. SPIN & EARN — CREATE SESSION (Callable Handler)
 * - Enforces approved user status.
 * - Enforces daily spin limit (50/day using Asia/Kolkata date key).
 * - Enforces 24-hour free-spin cooldown OR consumes server-verified extraSpinAvailable.
 * - Generates & persists server-authoritative segmentIndex & rewardCoins in
 *   `/users/{uid}/spin_sessions/{sessionId}`.
 */
async function handleCreateSpinSession(request, deps = {}) {
  if (!request.auth || !request.auth.uid) {
    throw new HttpsError("unauthenticated", "User must be logged in.");
  }

  const firestoreDb = deps.db || db;
  const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();
  const userId = String(request.auth.uid).trim();
  const now = new Date(nowMillis);
  const today = getTodayDateString(now);

  const userRef = firestoreDb.collection("users").doc(userId);
  const spinStatsRef = userRef.collection("spin_stats").doc(today);
  const ts = getDefaultServerTimestamp(deps);
  const nowTimestamp = makeTimestampFromMillis(nowMillis, deps);
  const expiresAt = makeTimestampFromMillis(nowMillis + 30 * 60 * 1000, deps);

  return await firestoreDb.runTransaction(async (transaction) => {
    const [userSnap, spinSnap] = await Promise.all([
      transaction.get(userRef),
      transaction.get(spinStatsRef)
    ]);

    if (!userSnap.exists) {
      throw new HttpsError("not-found", "User profile not found.");
    }
    const userData = userSnap.data() || {};
    if (userData.approvalStatus !== "approved") {
      throw new HttpsError(
        "permission-denied",
        "Your account must be approved by an administrator before using Spin & Earn."
      );
    }

    const spinsUsed = spinSnap.exists ? Number(spinSnap.data().spinsUsed || 0) : 0;
    if (spinsUsed >= SPIN_DAILY_LIMIT) {
      throw new HttpsError(
        "resource-exhausted",
        `Daily spin limit (${SPIN_DAILY_LIMIT}) reached.`
      );
    }

    const lastFreeSpinMillis = toMillisSafe(userData.lastFreeSpinAt);
    const freeSpinReady =
      lastFreeSpinMillis === null || nowMillis - lastFreeSpinMillis >= FREE_SPIN_COOLDOWN_MS;
    const extraSpinReady = userData.extraSpinAvailable === true;

    if (!freeSpinReady && !extraSpinReady) {
      throw new HttpsError(
        "failed-precondition",
        "Your Free Spin is on 24-hour cooldown. Watch 2 videos to unlock an Extra Spin!"
      );
    }

    const segmentIndex = pickWeightedSpinSegmentIndex(deps.random);
    const rewardCoins = SPIN_SEGMENTS_COINS[segmentIndex];
    const spinNumber = spinsUsed + 1;
    const sessionId =
      typeof deps.sessionIdGenerator === "function"
        ? deps.sessionIdGenerator()
        : `spin_${crypto.randomBytes(16).toString("hex")}`;
    const spinSessionRef = userRef.collection("spin_sessions").doc(sessionId);

    // Atomically update user cooldown or consume Extra Spin
    if (freeSpinReady) {
      transaction.update(userRef, {
        lastFreeSpinAt: nowTimestamp,
        updatedAt: ts
      });
    } else {
      transaction.update(userRef, {
        extraSpinAvailable: false,
        extraSpinVideosWatched: 0,
        updatedAt: ts
      });
    }

    // Atomically increment daily spin stats
    transaction.set(
      spinStatsRef,
      {
        userId,
        dateKey: today,
        spinsUsed: spinNumber,
        lastSpinAt: nowTimestamp,
        updatedAt: ts
      },
      { merge: true }
    );

    // Persist authoritative spin session with exact pre-determined segment & reward
    transaction.set(spinSessionRef, {
      sessionId,
      userId,
      spinNumber,
      segmentIndex,
      rewardCoins,
      usedFreeSpin: freeSpinReady,
      dateKey: today,
      status: "SPUN",
      used: false,
      claimed: false,
      createdAt: nowTimestamp,
      expiresAt
    });

    return {
      success: true,
      sessionId,
      spinNumber,
      segmentIndex,
      rewardCoins,
      usedFreeSpin: freeSpinReady,
      dateKey: today
    };
  });
}

exports.createSpinSession = onCall(async (request) => handleCreateSpinSession(request));

/**
 * 6. RECORD EXTRA SPIN AD WATCH (Callable Handler)
 * Verifies a Unity S2S-verified `EXTRA_SPIN_UNLOCK` session, consumes it atomically,
 * increments server-managed `extraSpinVideosWatched` (0->1->2), and sets
 * `extraSpinAvailable = true` once 2 independent verified ads are recorded.
 */
async function handleRecordExtraSpinAdWatch(request, deps = {}) {
  if (!request.auth || !request.auth.uid) {
    throw new HttpsError("unauthenticated", "User must be logged in.");
  }

  const firestoreDb = deps.db || db;
  const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();
  const userId = String(request.auth.uid).trim();
  const sessionId = String(request.data?.sessionId || "").trim();

  if (!sessionId) {
    throw new HttpsError("invalid-argument", "Session ID is required.");
  }

  const now = new Date(nowMillis);
  const today = getTodayDateString(now);
  const ts = getDefaultServerTimestamp(deps);

  const userRef = firestoreDb.collection("users").doc(userId);
  const sessionRef = firestoreDb.collection("reward_sessions").doc(sessionId);
  const spinStatsRef = userRef.collection("spin_stats").doc(today);

  return await firestoreDb.runTransaction(async (transaction) => {
    const [userDoc, sessionDoc, spinStatsDoc] = await Promise.all([
      transaction.get(userRef),
      transaction.get(sessionRef),
      transaction.get(spinStatsRef)
    ]);

    if (!userDoc.exists) {
      throw new HttpsError("not-found", "User profile not found.");
    }
    const userData = userDoc.data() || {};
    if (userData.approvalStatus !== "approved") {
      throw new HttpsError(
        "permission-denied",
        "Only approved users can watch ads to unlock Extra Spins."
      );
    }

    if (!sessionDoc.exists) {
      throw new HttpsError("not-found", "Invalid reward session.");
    }
    const session = sessionDoc.data() || {};

    if (session.userId !== userId) {
      throw new HttpsError("permission-denied", "Session belongs to another user.");
    }

    if (session.rewardType !== "EXTRA_SPIN_UNLOCK") {
      throw new HttpsError(
        "invalid-argument",
        "Session is not an Extra Spin unlock session."
      );
    }

    if (
      session.used ||
      session.claimed ||
      session.status === "CONSUMED" ||
      session.status === "CLAIMED"
    ) {
      throw new HttpsError("already-exists", "This ad session has already been used.");
    }

    const expiresAtMillis = toMillisSafe(session.expiresAt);
    if (expiresAtMillis === null || expiresAtMillis < nowMillis) {
      throw new HttpsError("deadline-exceeded", "Ad session has expired.");
    }

    if (session.s2sVerified !== true || session.status !== "VERIFIED") {
      throw new HttpsError(
        "failed-precondition",
        "Reward video completion has not been verified by server yet."
      );
    }

    const spinsUsed = spinStatsDoc.exists ? Number(spinStatsDoc.data().spinsUsed || 0) : 0;
    if (spinsUsed >= SPIN_DAILY_LIMIT) {
      throw new HttpsError(
        "resource-exhausted",
        `Daily spin limit (${SPIN_DAILY_LIMIT}) reached.`
      );
    }

    if (userData.extraSpinAvailable === true) {
      throw new HttpsError(
        "failed-precondition",
        "Extra Spin is already unlocked. Use your Extra Spin before watching more ads."
      );
    }

    const currentWatched = Math.max(
      0,
      Math.min(EXTRA_SPIN_ADS_REQUIRED, Number(userData.extraSpinVideosWatched || 0))
    );
    const nextWatched = Math.min(EXTRA_SPIN_ADS_REQUIRED, currentWatched + 1);
    const newlyUnlocked = nextWatched >= EXTRA_SPIN_ADS_REQUIRED;

    // 1. Mark ad session single-use CONSUMED
    transaction.update(sessionRef, {
      status: "CONSUMED",
      used: true,
      consumedAt: ts
    });

    // 2. Atomically update server-managed Extra Spin unlock progress
    transaction.update(userRef, {
      extraSpinVideosWatched: nextWatched,
      extraSpinAvailable: newlyUnlocked,
      updatedAt: ts
    });

    return {
      success: true,
      sessionId,
      extraSpinVideosWatched: nextWatched,
      extraSpinAvailable: newlyUnlocked,
      newlyUnlocked,
      dateKey: today
    };
  });
}

exports.recordExtraSpinAdWatch = onCall(async (request) =>
  handleRecordExtraSpinAdWatch(request)
);

/**
 * 7. SPIN & EARN — CLAIM REWARD (Callable Handler)
 * - Verifies user approval, session existence, ownership, status, and idempotency.
 * - Uses the exact `segmentIndex` and `rewardCoins` persisted at `createSpinSession` time
 *   (NEVER generates a new random reward during claim).
 * - Atomically marks session CLAIMED, credits `promotionalCoins` + `balanceCoins`
 *   (preserving `balanceCents` & `eligibleCoins`), syncs `/leaderboard/{uid}`, and writes
 *   deterministic `/transactions` + `/notifications`.
 */
async function handleClaimSpinReward(request, deps = {}) {
  if (!request.auth || !request.auth.uid) {
    throw new HttpsError("unauthenticated", "User must be logged in.");
  }

  const firestoreDb = deps.db || db;
  const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();
  const userId = String(request.auth.uid).trim();
  const sessionId = String(request.data?.sessionId || "").trim();

  if (!sessionId) {
    throw new HttpsError("invalid-argument", "Spin session ID is required.");
  }

  const ts = getDefaultServerTimestamp(deps);
  const userRef = firestoreDb.collection("users").doc(userId);
  const spinSessionRef = userRef.collection("spin_sessions").doc(sessionId);
  const txRef = firestoreDb.collection("transactions").doc(`spin_${sessionId}`);
  const notifRef = userRef.collection("notifications").doc(`notif_spin_${sessionId}`);

  return await firestoreDb.runTransaction(async (transaction) => {
    const [userDoc, sessionDoc, existingTxDoc] = await Promise.all([
      transaction.get(userRef),
      transaction.get(spinSessionRef),
      transaction.get(txRef)
    ]);

    if (!userDoc.exists) {
      throw new HttpsError("not-found", "User profile not found.");
    }
    const userData = userDoc.data() || {};
    if (userData.approvalStatus !== "approved") {
      throw new HttpsError(
        "permission-denied",
        "Your account must be approved to claim rewards."
      );
    }

    if (!sessionDoc.exists) {
      throw new HttpsError("not-found", "Spin session not found.");
    }
    const session = sessionDoc.data() || {};

    if (session.userId !== userId) {
      throw new HttpsError("permission-denied", "Spin session belongs to another user.");
    }

    if (
      session.claimed === true ||
      session.used === true ||
      session.status === "CLAIMED" ||
      existingTxDoc.exists
    ) {
      throw new HttpsError(
        "already-exists",
        "This Spin reward has already been claimed."
      );
    }

    const expiresAtMillis = toMillisSafe(session.expiresAt);
    if (expiresAtMillis !== null && expiresAtMillis < nowMillis) {
      throw new HttpsError("deadline-exceeded", "Spin session has expired.");
    }

    const segmentIndex = Number(session.segmentIndex);
    const rewardCoins = Number(session.rewardCoins);
    if (
      !Number.isInteger(segmentIndex) ||
      segmentIndex < 0 ||
      segmentIndex >= SPIN_SEGMENTS_COINS.length ||
      !Number.isInteger(rewardCoins) ||
      rewardCoins !== SPIN_SEGMENTS_COINS[segmentIndex]
    ) {
      throw new HttpsError(
        "failed-precondition",
        "Invalid spin session segment or reward stored on server."
      );
    }

    const displayName = (userData.displayName || "TaskFlow Member").trim() || "TaskFlow Member";
    const completedTasksCount = Number(userData.completedTasksCount || 0);
    const { oldBalanceCents, newPromo, newBalanceCoins } = computeUpdatedPromoWallet(
      userData,
      rewardCoins
    );

    // 1. Atomically mark spin session CLAIMED & single-use
    transaction.update(spinSessionRef, {
      claimed: true,
      used: true,
      status: "CLAIMED",
      claimedAt: ts
    });

    // 2. Credit promotionalCoins & balanceCoins (preserving balanceCents & eligibleCoins)
    transaction.update(userRef, {
      promotionalCoins: newPromo,
      balanceCoins: newBalanceCoins,
      updatedAt: ts
    });

    // 3. Sync public leaderboard projection with canonical schema
    syncLeaderboardProjection(
      transaction,
      firestoreDb,
      userId,
      displayName,
      oldBalanceCents,
      newBalanceCoins,
      completedTasksCount,
      ts
    );

    // 4. Record audit transaction entry
    transaction.set(txRef, {
      userId,
      userName: displayName,
      type: "SPIN_EARN",
      title: `Spin & Earn Reward (+${rewardCoins} Coins)`,
      amountCoins: rewardCoins,
      amountCents: Math.floor((rewardCoins * 100) / 125),
      status: "CREDITED",
      referenceId: sessionId,
      createdAt: ts
    });

    // 5. Record in-app notification
    transaction.set(notifRef, {
      userId,
      title: "Spin & Earn Reward Credited 🎉",
      body: `You won +${rewardCoins} Coins on the Spin Wheel!`,
      message: `You won +${rewardCoins} Coins on the Spin Wheel!`,
      type: "SPIN_EARN_REWARD",
      rewardCoins,
      referenceId: sessionId,
      isRead: false,
      createdAt: ts
    });

    return {
      success: true,
      sessionId,
      segmentIndex,
      rewardCoins,
      newPromotionalCoins: newPromo,
      newBalanceCoins
    };
  });
}

exports.claimSpinReward = onCall(async (request) => handleClaimSpinReward(request));


// =========================================================================
// TELEGRAM JOIN TASK SYSTEM (MULTI-TASK + CRYPTOGRAPHIC BOT LINKING + API VERIFY)
// =========================================================================

const TELEGRAM_MAX_REWARD_COINS = 10_000_000;
const TELEGRAM_LINK_TOKEN_TTL_MS = 15 * 60 * 1000;

function resolveTelegramChannelChatId(channelChatId, channelUrl) {
  const explicit = String(channelChatId || "").trim();
  if (explicit) {
    if (explicit.startsWith("@") || explicit.startsWith("-")) {
      return explicit;
    }
    return `@${explicit}`;
  }
  const cleanUrl = String(channelUrl || "").trim();
  const match = /^https?:\/\/(?:t\.me|telegram\.me)\/([A-Za-z0-9_]{4,64})(?:\/.*)?$/i.exec(cleanUrl);
  const handle = match ? match[1] : "";
  if (!handle || handle.toLowerCase() === "joinchat" || handle.startsWith("+")) {
    throw new HttpsError(
      "invalid-argument",
      "Private invite links require an explicit Channel Chat ID (e.g., -1001234567890)."
    );
  }
  return `@${handle}`;
}

function validateTelegramTaskUrl(rawUrl) {
  const clean = String(rawUrl || "").trim();
  if (!/^https:\/\/(?:t\.me|telegram\.me)\/[^\s]+$/i.test(clean) || clean.length > 500) {
    throw new HttpsError(
      "invalid-argument",
      "Please enter a valid HTTPS Telegram channel link (e.g., https://t.me/yourchannel)."
    );
  }
  return clean;
}

function maskTelegramUsername(username, telegramUserId) {
  const cleanName = String(username || "").trim().replace(/^@+/, "");
  if (cleanName.length >= 2) {
    return `@${cleanName.slice(0, 2)}***`;
  }
  const cleanId = String(telegramUserId || "").trim();
  if (cleanId.length > 4) {
    return `tg_***${cleanId.slice(-4)}`;
  }
  return cleanId ? `tg_***${cleanId}` : "Verified";
}

function parseTelegramChatMemberStatus(tgRes) {
  if (!tgRes || typeof tgRes !== "object") {
    throw new HttpsError(
      "unavailable",
      "Telegram API returned an empty response. Please try again."
    );
  }
  if (!tgRes.ok) {
    const desc = String(tgRes.description || "").toLowerCase();
    if (
      desc.includes("unauthorized") ||
      tgRes.error_code === 401
    ) {
      throw new HttpsError(
        "failed-precondition",
        "Telegram Bot token (TELEGRAM_BOT_TOKEN) is invalid or revoked on server."
      );
    }
    if (
      desc.includes("chat not found") ||
      desc.includes("member list is inaccessible") ||
      desc.includes("bot is not a member") ||
      desc.includes("administrator") ||
      desc.includes("not enough rights")
    ) {
      throw new HttpsError(
        "failed-precondition",
        "Telegram bot setup incomplete: ensure the bot is added as an administrator in the target channel."
      );
    }
    return false;
  }
  const result = tgRes.result || {};
  const status = String(result.status || "").toLowerCase();
  if (["member", "administrator", "creator"].includes(status)) {
    return true;
  }
  if (status === "restricted" && result.is_member === true) {
    return true;
  }
  return false;
}

function extractStartTokenFromText(rawText) {
  const clean = String(rawText || "").trim();
  const match = /^\/start(?:@[A-Za-z0-9_]+)?\s+([A-Za-z0-9_-]{8,128})$/i.exec(clean);
  return match ? match[1] : "";
}

async function applyBotStartTokenUpdate({
  firestoreDb,
  nowMillis,
  deps = {},
  token,
  fromUser
}) {
  const cleanToken = String(token || "").trim();
  const telegramUserId = String(fromUser?.id || "").trim();
  if (!cleanToken || !telegramUserId || !/^\d+$/.test(telegramUserId) || fromUser?.is_bot === true) {
    return { linked: false, reason: "INVALID_PAYLOAD" };
  }
  const telegramUsername = String(fromUser?.username || "").trim().replace(/^@+/, "");
  const masked = maskTelegramUsername(telegramUsername, telegramUserId);
  const ts = getDefaultServerTimestamp(deps);

  const tokenRef = firestoreDb.collection("telegram_link_tokens").doc(cleanToken);
  const bindingRef = firestoreDb.collection("telegram_bindings").doc(telegramUserId);

  return await firestoreDb.runTransaction(async (transaction) => {
    const tokenSnap = await transaction.get(tokenRef);
    if (!tokenSnap.exists) {
      return { linked: false, reason: "TOKEN_NOT_FOUND" };
    }
    const tokenData = tokenSnap.data() || {};
    const uid = String(tokenData.userId || "").trim();
    if (!uid || tokenData.status !== "PENDING") {
      return { linked: false, reason: "TOKEN_ALREADY_USED" };
    }
    const expiresAtMillis = Number(tokenData.expiresAtMillis || 0);
    if (expiresAtMillis > 0 && expiresAtMillis <= nowMillis) {
      transaction.update(tokenRef, { status: "EXPIRED", updatedAt: ts });
      return { linked: false, reason: "TOKEN_EXPIRED" };
    }

    const accountRef = firestoreDb.collection("telegram_accounts").doc(uid);
    const statusRef = firestoreDb
      .collection("users")
      .doc(uid)
      .collection("telegram_account")
      .doc("status");

    const [bindingSnap, accountSnap] = await Promise.all([
      transaction.get(bindingRef),
      transaction.get(accountRef)
    ]);

    if (bindingSnap.exists) {
      const boundUid = String(bindingSnap.data()?.userId || "").trim();
      if (boundUid && boundUid !== uid) {
        transaction.update(tokenRef, {
          status: "CONFLICT",
          conflictReason: "TELEGRAM_ACCOUNT_ALREADY_LINKED_TO_OTHER_USER",
          updatedAt: ts
        });
        return { linked: false, reason: "TELEGRAM_ALREADY_BOUND" };
      }
    }

    if (accountSnap.exists) {
      const boundTgId = String(accountSnap.data()?.telegramUserId || "").trim();
      if (boundTgId && boundTgId !== telegramUserId) {
        transaction.update(tokenRef, {
          status: "CONFLICT",
          conflictReason: "USER_ALREADY_LINKED_DIFFERENT_TELEGRAM",
          updatedAt: ts
        });
        return { linked: false, reason: "USER_ALREADY_BOUND" };
      }
    }

    transaction.set(
      accountRef,
      {
        userId: uid,
        telegramUserId,
        telegramUsername,
        telegramUsernameMasked: masked,
        linkedAt: accountSnap.exists ? accountSnap.data()?.linkedAt || ts : ts,
        updatedAt: ts
      },
      { merge: true }
    );

    transaction.set(
      bindingRef,
      {
        userId: uid,
        telegramUserId,
        telegramUsername,
        telegramUsernameMasked: masked,
        verifiedAt: ts
      },
      { merge: true }
    );

    transaction.set(
      statusRef,
      {
        userId: uid,
        isLinked: true,
        telegramUsernameMasked: masked,
        linkedAt: ts,
        updatedAt: ts
      },
      { merge: true }
    );

    transaction.update(tokenRef, {
      status: "USED",
      telegramUserId,
      usedAt: ts,
      updatedAt: ts
    });

    return {
      linked: true,
      userId: uid,
      telegramUserId,
      telegramUsername,
      telegramUsernameMasked: masked
    };
  });
}

async function handleTelegramBotWebhook(req, res, deps = {}) {
  try {
    if (req.method !== "POST") {
      return res.status(405).send("Method Not Allowed");
    }

    const expectedSecret = resolveTelegramWebhookSecret(deps);
    if (!expectedSecret) {
      console.error(
        "CRITICAL: TELEGRAM_WEBHOOK_SECRET is not configured. Rejecting Telegram webhook request."
      );
      return res.status(500).json({
        ok: false,
        error: "Server configuration error: TELEGRAM_WEBHOOK_SECRET is mandatory"
      });
    }

    const headerSecret = String(
      req.headers?.["x-telegram-bot-api-secret-token"] ||
        (typeof req.get === "function" ? req.get("X-Telegram-Bot-Api-Secret-Token") : "") ||
        ""
    ).trim();
    const expectedBuf = Buffer.from(expectedSecret, "utf8");
    const headerBuf = Buffer.from(headerSecret, "utf8");
    const secretsMatch =
      headerBuf.length === expectedBuf.length &&
      crypto.timingSafeEqual(headerBuf, expectedBuf);
    if (!headerSecret || !secretsMatch) {
      return res.status(401).json({ ok: false, error: "Invalid webhook secret" });
    }

    const firestoreDb = deps.db || db;
    const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();
    const update = req.body || {};
    const message = update.message || update.edited_message || null;

    if (message && typeof message.text === "string" && message.from) {
      const token = extractStartTokenFromText(message.text);
      if (token) {
        await applyBotStartTokenUpdate({
          firestoreDb,
          nowMillis,
          deps,
          token,
          fromUser: message.from
        });
      }
    }

    return res.status(200).json({ ok: true });
  } catch (err) {
    console.error("telegramBotWebhook error:", err);
    return res.status(500).json({ ok: false, error: err.message });
  }
}

exports.telegramBotWebhook = onRequest(
  { secrets: [telegramBotToken, telegramWebhookSecret] },
  async (req, res) => handleTelegramBotWebhook(req, res)
);

async function resolveOrSyncLinkedTelegramAccount({
  firestoreDb,
  botToken,
  userId,
  nowMillis,
  deps = {}
}) {
  const accountSnap = await firestoreDb.collection("telegram_accounts").doc(userId).get();
  if (accountSnap.exists && String(accountSnap.data()?.telegramUserId || "").trim()) {
    return accountSnap.data();
  }

  const pendingSnap = await firestoreDb
    .collection("telegram_link_tokens")
    .where("userId", "==", userId)
    .where("status", "==", "PENDING")
    .get();

  const pendingTokens = new Set();
  const docs = pendingSnap?.docs || [];
  for (const docSnap of docs) {
    const d = docSnap.data() || {};
    if (!d.expiresAtMillis || Number(d.expiresAtMillis) > nowMillis) {
      pendingTokens.add(String(d.token || docSnap.id));
    }
  }

  if (pendingTokens.size === 0) {
    return null;
  }

  const getUpdatesFn =
    typeof deps.getUpdates === "function" ? deps.getUpdates : callTelegramGetUpdates;
  let updatesRes;
  try {
    updatesRes = await getUpdatesFn(botToken, 100);
  } catch (_) {
    updatesRes = null;
  }

  // If webhook is active (HTTP 409 conflict on getUpdates), webhook itself writes telegram_accounts/{uid}.
  if (updatesRes && updatesRes.ok && Array.isArray(updatesRes.result)) {
    const sorted = [...updatesRes.result].sort((a, b) => Number(b.update_id || 0) - Number(a.update_id || 0));
    for (const upd of sorted) {
      const msg = upd?.message || upd?.edited_message;
      const token = extractStartTokenFromText(msg?.text);
      if (token && pendingTokens.has(token) && msg?.from) {
        const res = await applyBotStartTokenUpdate({
          firestoreDb,
          nowMillis,
          deps,
          token,
          fromUser: msg.from
        });
        if (res.linked) {
          return {
            userId,
            telegramUserId: res.telegramUserId,
            telegramUsername: res.telegramUsername,
            telegramUsernameMasked: res.telegramUsernameMasked
          };
        }
        if (res.reason === "TELEGRAM_ALREADY_BOUND") {
          throw new HttpsError(
            "already-exists",
            "This Telegram account is already linked to another TaskFlow user."
          );
        }
      }
    }
  }

  const refreshed = await firestoreDb.collection("telegram_accounts").doc(userId).get();
  if (refreshed.exists && String(refreshed.data()?.telegramUserId || "").trim()) {
    return refreshed.data();
  }
  return null;
}

async function handleCreateTelegramTask(request, deps = {}) {
  const firestoreDb = deps.db || db;
  const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();
  const adminInfo = await verifyAdminAccess(
    request,
    firestoreDb,
    "Only authorized administrators can manage Telegram tasks."
  );

  const data = request.data || {};
  const channelUrl = validateTelegramTaskUrl(data.channelUrl);
  const channelChatId = resolveTelegramChannelChatId(data.channelChatId, channelUrl);

  const rawReward = data.rewardCoins;
  if (
    typeof rawReward !== "number" ||
    !Number.isFinite(rawReward) ||
    !Number.isInteger(rawReward) ||
    rawReward < 1 ||
    rawReward > TELEGRAM_MAX_REWARD_COINS
  ) {
    throw new HttpsError(
      "invalid-argument",
      `rewardCoins must be an integer between 1 and ${TELEGRAM_MAX_REWARD_COINS}.`
    );
  }

  if (data.isActive !== undefined && typeof data.isActive !== "boolean") {
    throw new HttpsError("invalid-argument", "isActive must be a boolean.");
  }
  const isActive = data.isActive === undefined ? true : data.isActive;

  const fallbackTitle = channelChatId.startsWith("@")
    ? `Join ${channelChatId}`
    : "Join Telegram Channel";
  const rawTitle = typeof data.title === "string" ? data.title.trim().slice(0, 100) : "";
  const title = rawTitle || fallbackTitle;

  const taskId =
    typeof deps.taskIdGenerator === "function"
      ? deps.taskIdGenerator()
      : `tg_${crypto.randomBytes(8).toString("hex")}`;
  const ts = getDefaultServerTimestamp(deps);

  const taskRef = firestoreDb.collection("telegram_tasks").doc(taskId);
  const logRef = firestoreDb.collection("admin_logs").doc();

  await firestoreDb.runTransaction(async (transaction) => {
    const existing = await transaction.get(taskRef);
    if (existing.exists) {
      throw new HttpsError("already-exists", "Telegram task ID collision; please retry.");
    }
    transaction.set(taskRef, {
      taskId,
      title,
      channelUrl,
      channelChatId,
      rewardCoins: rawReward,
      isActive,
      claimsCount: 0,
      createdBy: adminInfo.uid,
      createdAt: ts,
      updatedAt: ts
    });
    transaction.set(logRef, {
      actionType: "REWARD",
      title: `Created Telegram Join Task: ${title}`,
      details: `Channel: ${channelUrl} (${channelChatId}) • Reward: ${rawReward} Coins • Active: ${isActive}`,
      targetId: taskId,
      adminId: adminInfo.uid,
      timestampMillis: nowMillis,
      createdAt: ts
    });
  });

  return {
    success: true,
    taskId,
    title,
    channelUrl,
    channelChatId,
    rewardCoins: rawReward,
    isActive,
    claimsCount: 0,
    createdBy: adminInfo.uid
  };
}

async function handleSetTelegramTaskActiveStatus(request, deps = {}) {
  const firestoreDb = deps.db || db;
  const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();
  const adminInfo = await verifyAdminAccess(
    request,
    firestoreDb,
    "Only authorized administrators can update Telegram tasks."
  );

  const data = request.data || {};
  const taskId = String(data.taskId || "").trim();
  if (!taskId || taskId.length > 128) {
    throw new HttpsError("invalid-argument", "Valid taskId is required.");
  }
  if (typeof data.isActive !== "boolean") {
    throw new HttpsError("invalid-argument", "isActive must be a boolean.");
  }
  const targetActive = data.isActive;

  const taskRef = firestoreDb.collection("telegram_tasks").doc(taskId);
  const logRef = firestoreDb.collection("admin_logs").doc();
  const ts = getDefaultServerTimestamp(deps);

  return await firestoreDb.runTransaction(async (transaction) => {
    const snap = await transaction.get(taskRef);
    if (!snap.exists) {
      throw new HttpsError("not-found", "Telegram task not found.");
    }
    const existing = snap.data() || {};
    transaction.update(taskRef, {
      isActive: targetActive,
      updatedBy: adminInfo.uid,
      updatedAt: ts
    });
    transaction.set(logRef, {
      actionType: "REWARD",
      title: `Telegram Task ${targetActive ? "Activated" : "Deactivated"}: ${existing.title || taskId}`,
      details: `Channel: ${existing.channelUrl || ""} • Active: ${targetActive}`,
      targetId: taskId,
      adminId: adminInfo.uid,
      timestampMillis: nowMillis,
      createdAt: ts
    });
    return {
      success: true,
      taskId,
      isActive: targetActive
    };
  });
}

async function handleVerifyTelegramTaskMembership(request, deps = {}) {
  if (!request.auth || !request.auth.uid) {
    throw new HttpsError("unauthenticated", "User must be logged in.");
  }
  const firestoreDb = deps.db || db;
  const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();
  const userId = String(request.auth.uid).trim();
  const taskId = String(request.data?.taskId || "").trim();

  if (!taskId || taskId.length > 128) {
    throw new HttpsError("invalid-argument", "Valid taskId is required.");
  }

  const botToken = resolveTelegramBotToken(deps);
  if (!botToken) {
    throw new HttpsError(
      "failed-precondition",
      "Telegram Bot Token (TELEGRAM_BOT_TOKEN) is not configured on the server."
    );
  }

  const userRef = firestoreDb.collection("users").doc(userId);
  const taskRef = firestoreDb.collection("telegram_tasks").doc(taskId);
  const claimRef = firestoreDb.collection("telegram_claims").doc(`${taskId}_${userId}`);
  const stateRef = userRef.collection("telegram_task_states").doc(taskId);

  const [userSnap, taskSnap, claimSnap, stateSnap] = await Promise.all([
    userRef.get(),
    taskRef.get(),
    claimRef.get(),
    stateRef.get()
  ]);

  if (!userSnap.exists) {
    throw new HttpsError("not-found", "User profile not found.");
  }
  if (!taskSnap.exists) {
    throw new HttpsError("not-found", "Telegram task not found.");
  }
  const task = taskSnap.data() || {};
  if (task.isActive !== true) {
    throw new HttpsError("failed-precondition", "This Telegram task is currently inactive.");
  }
  const channelChatId = resolveTelegramChannelChatId(task.channelChatId, task.channelUrl);

  if (claimSnap.exists || stateSnap.data()?.isClaimed === true) {
    throw new HttpsError("already-exists", "You have already claimed this Telegram task.");
  }

  const linkedAccount = await resolveOrSyncLinkedTelegramAccount({
    firestoreDb,
    botToken,
    userId,
    nowMillis,
    deps
  });

  if (!linkedAccount) {
    const getMeFn = typeof deps.getMe === "function" ? deps.getMe : callTelegramGetMe;
    let meRes;
    try {
      meRes = await getMeFn(botToken);
    } catch (err) {
      throw new HttpsError(
        "unavailable",
        `Failed to query Telegram Bot identity: ${err.message}`
      );
    }
    const botUsername = String(meRes?.result?.username || "").trim().replace(/^@+/, "");
    if (!meRes?.ok || !botUsername) {
      throw new HttpsError(
        "failed-precondition",
        "Telegram Bot setup incomplete: could not resolve bot username from TELEGRAM_BOT_TOKEN."
      );
    }

    const token =
      typeof deps.linkTokenGenerator === "function"
        ? deps.linkTokenGenerator()
        : crypto.randomBytes(18).toString("base64url");
    const ts = getDefaultServerTimestamp(deps);
    await firestoreDb.collection("telegram_link_tokens").doc(token).set({
      token,
      userId,
      taskId,
      status: "PENDING",
      expiresAtMillis: nowMillis + TELEGRAM_LINK_TOKEN_TTL_MS,
      createdAt: ts,
      updatedAt: ts
    });

    const botLinkUrl = `https://t.me/${botUsername}?start=${encodeURIComponent(token)}`;
    return {
      success: true,
      verified: false,
      needsAccountLink: true,
      taskId,
      botUsername,
      botLinkUrl,
      message: `Tap Start in @${botUsername} on Telegram, then tap VERIFY again.`
    };
  }

  const telegramUserId = String(linkedAccount.telegramUserId || "").trim();
  const crossUserClaimSnap = await firestoreDb
    .collection("telegram_task_user_claims")
    .doc(`${taskId}_${telegramUserId}`)
    .get();
  if (crossUserClaimSnap.exists) {
    throw new HttpsError(
      "already-exists",
      "This Telegram account has already claimed this Telegram task."
    );
  }

  const getChatMemberFn =
    typeof deps.getChatMember === "function" ? deps.getChatMember : callTelegramGetChatMember;
  let tgRes;
  try {
    tgRes = await getChatMemberFn(botToken, channelChatId, telegramUserId);
  } catch (err) {
    throw new HttpsError("unavailable", `Failed to communicate with Telegram API: ${err.message}`);
  }

  const isMember = parseTelegramChatMemberStatus(tgRes);
  const ts = getDefaultServerTimestamp(deps);
  if (!isMember) {
    await stateRef.set(
      {
        taskId,
        userId,
        isMembershipVerified: false,
        updatedAt: ts
      },
      { merge: true }
    );
    throw new HttpsError(
      "permission-denied",
      "You are not a member of this Telegram channel yet. Tap JOIN first, then VERIFY."
    );
  }

  await stateRef.set(
    {
      taskId,
      userId,
      isMembershipVerified: true,
      isClaimed: false,
      verifiedAt: ts,
      updatedAt: ts
    },
    { merge: true }
  );

  return {
    success: true,
    verified: true,
    needsAccountLink: false,
    taskId,
    telegramUsernameMasked: String(linkedAccount.telegramUsernameMasked || ""),
    message: "Membership verified! Tap CLAIM to collect your reward."
  };
}

async function handleClaimTelegramTaskReward(request, deps = {}) {
  if (!request.auth || !request.auth.uid) {
    throw new HttpsError("unauthenticated", "User must be logged in.");
  }
  const firestoreDb = deps.db || db;
  const userId = String(request.auth.uid).trim();
  const taskId = String(request.data?.taskId || "").trim();

  if (!taskId || taskId.length > 128) {
    throw new HttpsError("invalid-argument", "Valid taskId is required.");
  }

  const botToken = resolveTelegramBotToken(deps);
  if (!botToken) {
    throw new HttpsError(
      "failed-precondition",
      "Telegram Bot Token (TELEGRAM_BOT_TOKEN) is not configured on the server."
    );
  }

  const userRef = firestoreDb.collection("users").doc(userId);
  const taskRef = firestoreDb.collection("telegram_tasks").doc(taskId);
  const accountRef = firestoreDb.collection("telegram_accounts").doc(userId);
  const stateRef = userRef.collection("telegram_task_states").doc(taskId);
  const claimRef = firestoreDb.collection("telegram_claims").doc(`${taskId}_${userId}`);

  const [taskPreSnap, accountPreSnap, statePreSnap] = await Promise.all([
    taskRef.get(),
    accountRef.get(),
    stateRef.get()
  ]);

  if (!taskPreSnap.exists) {
    throw new HttpsError("not-found", "Telegram task not found.");
  }
  const preTask = taskPreSnap.data() || {};
  if (preTask.isActive !== true) {
    throw new HttpsError("failed-precondition", "This Telegram task is no longer active.");
  }
  if (
    !accountPreSnap.exists ||
    !String(accountPreSnap.data()?.telegramUserId || "").trim() ||
    statePreSnap.data()?.isMembershipVerified !== true
  ) {
    throw new HttpsError(
      "failed-precondition",
      "Please join the Telegram channel and complete VERIFY before claiming."
    );
  }

  const telegramUserId = String(accountPreSnap.data().telegramUserId).trim();
  const telegramUsernameMasked =
    String(accountPreSnap.data()?.telegramUsernameMasked || "").trim() ||
    maskTelegramUsername("", telegramUserId);
  const channelChatId = resolveTelegramChannelChatId(preTask.channelChatId, preTask.channelUrl);

  // Live re-verification of membership right before crediting so users cannot leave right after verify.
  const getChatMemberFn =
    typeof deps.getChatMember === "function" ? deps.getChatMember : callTelegramGetChatMember;
  let tgRes;
  try {
    tgRes = await getChatMemberFn(botToken, channelChatId, telegramUserId);
  } catch (err) {
    throw new HttpsError("unavailable", `Failed to verify Telegram membership: ${err.message}`);
  }
  const ts = getDefaultServerTimestamp(deps);
  if (!parseTelegramChatMemberStatus(tgRes)) {
    // Safely reset isMembershipVerified = false in an isolated transaction only if the task
    // has not already been claimed, ensuring concurrent or stale requests never corrupt claim integrity.
    await firestoreDb.runTransaction(async (resetTx) => {
      const [latestStateSnap, latestClaimSnap] = await Promise.all([
        resetTx.get(stateRef),
        resetTx.get(claimRef)
      ]);
      const alreadyClaimed =
        latestClaimSnap.exists || latestStateSnap.data()?.isClaimed === true;
      if (!alreadyClaimed) {
        resetTx.set(
          stateRef,
          {
            taskId,
            userId,
            isMembershipVerified: false,
            updatedAt: ts
          },
          { merge: true }
        );
      }
    });
    throw new HttpsError(
      "permission-denied",
      "Channel membership check failed. Please remain in the channel and tap VERIFY again."
    );
  }

  const tgUserClaimRef = firestoreDb
    .collection("telegram_task_user_claims")
    .doc(`${taskId}_${telegramUserId}`);
  const txId = `tg_task_${taskId}_${userId}`;
  const txRef = firestoreDb.collection("transactions").doc(txId);
  const notifRef = userRef.collection("notifications").doc(`notif_${txId}`);

  return await firestoreDb.runTransaction(async (transaction) => {
    const [taskSnap, userSnap, stateSnap, claimSnap, tgUserClaimSnap, txSnap] = await Promise.all([
      transaction.get(taskRef),
      transaction.get(userRef),
      transaction.get(stateRef),
      transaction.get(claimRef),
      transaction.get(tgUserClaimRef),
      transaction.get(txRef)
    ]);

    if (!taskSnap.exists) {
      throw new HttpsError("not-found", "Telegram task not found.");
    }
    const task = taskSnap.data() || {};
    if (task.isActive !== true) {
      throw new HttpsError("failed-precondition", "This Telegram task is currently inactive.");
    }
    const rewardCoins = Number(task.rewardCoins || 0);
    if (!Number.isInteger(rewardCoins) || rewardCoins <= 0 || rewardCoins > TELEGRAM_MAX_REWARD_COINS) {
      throw new HttpsError("failed-precondition", "Invalid Telegram task reward configuration.");
    }

    if (!userSnap.exists) {
      throw new HttpsError("not-found", "User profile not found.");
    }
    const userData = userSnap.data() || {};
    if (userData.approvalStatus !== "approved") {
      throw new HttpsError("permission-denied", "Account must be approved before claiming rewards.");
    }

    const stateData = stateSnap.exists ? stateSnap.data() || {} : {};
    if (claimSnap.exists || stateData.isClaimed === true || txSnap.exists) {
      throw new HttpsError("already-exists", "You have already claimed this Telegram task.");
    }
    if (tgUserClaimSnap.exists) {
      throw new HttpsError("already-exists", "This Telegram account has already claimed this task.");
    }
    if (stateData.isMembershipVerified !== true) {
      throw new HttpsError(
        "failed-precondition",
        "Membership verification required before claiming."
      );
    }

    const wallet = computeUpdatedPromoWallet(userData, rewardCoins);
    const displayName =
      String(userData.displayName || "TaskFlow Member").trim() || "TaskFlow Member";
    const completedTasksCount = Number(userData.completedTasksCount || 0);
    const taskTitle = String(task.title || "Join Telegram Channel").trim();
    const channelUrl = String(task.channelUrl || "").trim();

    transaction.update(userRef, {
      promotionalCoins: wallet.newPromo,
      balanceCoins: wallet.newBalanceCoins,
      updatedAt: ts
    });

    syncLeaderboardProjection(
      transaction,
      firestoreDb,
      userId,
      displayName,
      wallet.oldBalanceCents,
      wallet.newBalanceCoins,
      completedTasksCount,
      ts,
      userData.approvalStatus || "approved"
    );

    transaction.set(
      stateRef,
      {
        taskId,
        userId,
        isMembershipVerified: true,
        isClaimed: true,
        rewardCoinsClaimed: rewardCoins,
        claimedAt: ts,
        updatedAt: ts
      },
      { merge: true }
    );

    transaction.set(claimRef, {
      claimId: `${taskId}_${userId}`,
      taskId,
      taskTitle,
      channelUrl,
      userId,
      userName: displayName,
      telegramUsernameMasked,
      rewardCoins,
      claimedAt: ts
    });

    transaction.set(tgUserClaimRef, {
      taskId,
      telegramUserId,
      userId,
      rewardCoins,
      claimedAt: ts
    });

    transaction.update(taskRef, {
      claimsCount: Number(task.claimsCount || 0) + 1,
      updatedAt: ts
    });

    transaction.set(txRef, {
      userId,
      userName: displayName,
      type: "TELEGRAM_JOIN",
      title: `${taskTitle} (+${rewardCoins} Coins)`,
      amountCoins: rewardCoins,
      amountCents: Math.floor((rewardCoins * 100) / 125),
      status: "CREDITED",
      referenceId: taskId,
      createdAt: ts
    });

    transaction.set(notifRef, {
      userId,
      title: "Telegram Join Reward Credited 🎉",
      body: `You earned +${rewardCoins} Coins for ${taskTitle}!`,
      message: `You earned +${rewardCoins} Coins for ${taskTitle}!`,
      type: "TELEGRAM_REWARD",
      rewardCoins,
      referenceId: taskId,
      isRead: false,
      createdAt: ts
    });

    return {
      success: true,
      taskId,
      rewardCoins,
      newPromotionalCoins: wallet.newPromo,
      newBalanceCoins: wallet.newBalanceCoins
    };
  });
}

exports.createTelegramTask = onCall(async (request) => handleCreateTelegramTask(request));
exports.setTelegramTaskActiveStatus = onCall(async (request) =>
  handleSetTelegramTaskActiveStatus(request)
);
exports.verifyTelegramTaskMembership = onCall(
  { secrets: [telegramBotToken] },
  async (request) => handleVerifyTelegramTaskMembership(request)
);
exports.claimTelegramTaskReward = onCall(
  { secrets: [telegramBotToken] },
  async (request) => handleClaimTelegramTaskReward(request)
);

exports._telegramHandlers = {
  resolveTelegramChannelChatId,
  parseTelegramChatMemberStatus,
  extractStartTokenFromText,
  applyBotStartTokenUpdate,
  handleTelegramBotWebhook,
  handleCreateTelegramTask,
  handleSetTelegramTaskActiveStatus,
  handleVerifyTelegramTaskMembership,
  handleClaimTelegramTaskReward
};

/**
 * Generates server-side Scratch Card reward coins with configurable probability distribution:
 * - 90% probability: 1–40 coins
 * - 10% probability: 41–100 coins
 * Considers completedTasksCount as an optional minor weighting boost to the minimum floor without making rewards predictable.
 */
function generateScratchRewardCoins(completedTasksCount = 0, randomFn = Math.random) {
  const isHighTier = randomFn() < 0.10;
  if (isHighTier) {
    // 10% probability: 41–100 coins
    return Math.floor(randomFn() * (100 - 41 + 1)) + 41;
  }
  // 90% probability: 1–40 coins (task boost can increase base minimum up to 10)
  const taskBoost = Math.min(10, Math.floor((Number(completedTasksCount) || 0) / 2));
  const minCoins = Math.min(20, 1 + taskBoost);
  const maxCoins = 40;
  return Math.floor(randomFn() * (maxCoins - minCoins + 1)) + minCoins;
}

/**
 * RECORD SCRATCH AD WATCH (Callable Function)
 * Called after an ad view for Scratch Card unlock completes.
 * Verifies the ad session is S2S-verified, marks it consumed, and updates the user's daily scratch unlock progress.
 * Exactly two verified ads unlock one scratch card.
 */
async function handleRecordScratchAdWatch(request, deps = {}) {
  const firestoreDb = deps.db || db;
  const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();

  if (!request.auth || !request.auth.uid) {
    throw new HttpsError("unauthenticated", "User must be logged in.");
  }

  const userId = request.auth.uid;
  const sessionId = String(request.data?.sessionId || "").trim();
  if (!sessionId) {
    throw new HttpsError("invalid-argument", "Session ID is required.");
  }

  const userRef = firestoreDb.collection("users").doc(userId);
  const sessionRef = firestoreDb.collection("reward_sessions").doc(sessionId);

  const today = getTodayDateString(new Date(nowMillis));
  const statsRef = userRef.collection("scratch_stats").doc(today);

  return await firestoreDb.runTransaction(async (transaction) => {
    const [userDoc, sessionDoc, statsDoc] = await Promise.all([
      transaction.get(userRef),
      transaction.get(sessionRef),
      transaction.get(statsRef)
    ]);

    if (!userDoc.exists) {
      throw new HttpsError("not-found", "User profile not found.");
    }
    const userData = userDoc.data() || {};
    if (userData.approvalStatus !== "approved") {
      throw new HttpsError(
        "permission-denied",
        "Only approved users can watch ads to unlock scratch cards."
      );
    }

    if (!sessionDoc.exists) {
      throw new HttpsError("not-found", "Invalid reward session.");
    }
    const session = sessionDoc.data() || {};

    if (session.userId !== userId) {
      throw new HttpsError("permission-denied", "Session belongs to another user.");
    }

    if (session.rewardType !== "SCRATCH_CARD_UNLOCK") {
      throw new HttpsError(
        "invalid-argument",
        "Session is not a Scratch Card unlock session."
      );
    }

    if (session.used || session.claimed || session.status === "CONSUMED" || session.status === "CLAIMED") {
      throw new HttpsError(
        "already-exists",
        "This ad session has already been used."
      );
    }

    const expiresAtMillis = toMillisSafe(session.expiresAt);
    if (expiresAtMillis === null || expiresAtMillis < nowMillis) {
      throw new HttpsError("deadline-exceeded", "Ad session has expired.");
    }

    if (session.s2sVerified !== true || session.status !== "VERIFIED") {
      throw new HttpsError(
        "failed-precondition",
        "Reward video completion has not been verified by server yet."
      );
    }

    const statsData = statsDoc.exists ? statsDoc.data() || {} : {};
    const completedToday = Number(statsData.scratchesCompletedToday || 0);

    if (completedToday >= SCRATCH_CARD_DAILY_LIMIT) {
      throw new HttpsError(
        "resource-exhausted",
        "Daily scratch limit of 50 reached."
      );
    }

    const currentWatched = Number(statsData.adsWatchedForNextScratch || 0);
    const currentUnlocked = Number(statsData.unlockedScratchesCount || 0);

    const nextWatched = currentWatched + 1;
    let finalWatched = nextWatched;
    let finalUnlocked = currentUnlocked;
    let newlyUnlocked = false;

    if (nextWatched >= SCRATCH_CARD_ADS_REQUIRED) {
      finalWatched = 0;
      finalUnlocked = currentUnlocked + 1;
      newlyUnlocked = true;
    }

    const ts = getDefaultServerTimestamp(deps);

    // 1. Mark ad session as consumed (atomic single-use)
    transaction.update(sessionRef, {
      status: "CONSUMED",
      used: true,
      consumedAt: ts
    });

    // 2. Atomically update daily scratch stats
    transaction.set(
      statsRef,
      {
        userId,
        dateKey: today,
        adsWatchedForNextScratch: finalWatched,
        unlockedScratchesCount: finalUnlocked,
        scratchesCompletedToday: completedToday,
        updatedAt: ts
      },
      { merge: true }
    );

    return {
      success: true,
      sessionId,
      adsWatchedForNextScratch: finalWatched,
      unlockedScratchesCount: finalUnlocked,
      newlyUnlocked,
      dateKey: today
    };
  });
}

exports.recordScratchAdWatch = onCall(async (request) => handleRecordScratchAdWatch(request));

/**
 * CREATE SCRATCH CARD SESSION (Callable Function)
 * Called when opening/requesting a scratch card.
 * - Enforces approved user status.
 * - First scratch each day is FREE.
 * - Subsequent scratches require 1 server-recorded unlock (`unlockedScratchesCount >= 1`).
 * - Enforces daily limit of 50 scratches per day (Asia/Kolkata date).
 * - Generates reward server-side: 90% probability of 1–40 coins, 10% probability of 41–100 coins.
 * - Persists session in `/users/{userId}/scratch_sessions/{cardId}`.
 * - Reopening returns the same active card without generating a new reward or consuming another unlock.
 */
async function handleCreateScratchCardSession(request, deps = {}) {
  const firestoreDb = deps.db || db;
  const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();
  const randomFn = typeof deps.random === "function" ? deps.random : Math.random;

  if (!request.auth || !request.auth.uid) {
    throw new HttpsError("unauthenticated", "User must be logged in.");
  }

  const userId = request.auth.uid;
  const userRef = firestoreDb.collection("users").doc(userId);

  const today = getTodayDateString(new Date(nowMillis));
  const statsRef = userRef.collection("scratch_stats").doc(today);

  return await firestoreDb.runTransaction(async (transaction) => {
    const [userDoc, statsDoc] = await Promise.all([
      transaction.get(userRef),
      transaction.get(statsRef)
    ]);

    if (!userDoc.exists) {
      throw new HttpsError("not-found", "User profile not found.");
    }
    const userData = userDoc.data() || {};
    if (userData.approvalStatus !== "approved") {
      throw new HttpsError(
        "permission-denied",
        "Your account must be approved before using Scratch Cards."
      );
    }

    const stats = statsDoc.exists ? statsDoc.data() || {} : {};
    const completedToday = Number(stats.scratchesCompletedToday || 0);

    if (completedToday >= SCRATCH_CARD_DAILY_LIMIT) {
      throw new HttpsError(
        "resource-exhausted",
        "Daily scratch limit (50) reached. Check back tomorrow!"
      );
    }

    // Check if user already has an active, unclaimed scratch card
    const activeCardId = String(stats.activeCardId || "").trim();
    if (activeCardId) {
      const activeSessionRef = userRef.collection("scratch_sessions").doc(activeCardId);
      const activeSessionDoc = await transaction.get(activeSessionRef);
      if (activeSessionDoc.exists) {
        const activeSession = activeSessionDoc.data() || {};
        if (!activeSession.claimed && activeSession.status !== "CLAIMED") {
          const existingReward = Number(
            activeSession.rewardCoins !== undefined && activeSession.rewardCoins !== null
              ? activeSession.rewardCoins
              : stats.activeCardRewardCoins
          );
          if (!Number.isInteger(existingReward) || existingReward < 1 || existingReward > 100) {
            throw new HttpsError(
              "failed-precondition",
              "Invalid active scratch card reward stored on server."
            );
          }
          return {
            cardId: activeCardId,
            rewardCoins: existingReward,
            scratchNumber: Number(activeSession.scratchNumber || completedToday + 1),
            isFree: Boolean(activeSession.isFree),
            dateKey: today
          };
        }
      }
    }

    // Eligibility check:
    const hasUsedFree = stats.hasUsedFreeScratchToday === true || completedToday > 0;
    let unlockedScratchesCount = Number(stats.unlockedScratchesCount || 0);

    if (hasUsedFree) {
      if (unlockedScratchesCount < 1) {
        throw new HttpsError(
          "failed-precondition",
          "You must watch 2 rewarded videos to unlock another Scratch Card."
        );
      }
      unlockedScratchesCount -= 1;
    }

    const rewardCoins = generateScratchRewardCoins(userData.completedTasksCount || 0, randomFn);
    const cardId =
      typeof deps.cardIdGenerator === "function"
        ? deps.cardIdGenerator()
        : `scratch_${crypto.randomBytes(16).toString("hex")}`;
    const scratchNumber = completedToday + 1;
    const sessionRef = userRef.collection("scratch_sessions").doc(cardId);
    const ts = getDefaultServerTimestamp(deps);

    const sessionPayload = {
      cardId,
      userId,
      scratchNumber,
      rewardCoins,
      dateKey: today,
      isFree: !hasUsedFree,
      claimed: false,
      status: "ACTIVE",
      createdAt: ts
    };

    transaction.set(sessionRef, sessionPayload);

    transaction.set(
      statsRef,
      {
        userId,
        dateKey: today,
        scratchesCompletedToday: completedToday,
        hasUsedFreeScratchToday: true,
        unlockedScratchesCount,
        activeCardId: cardId,
        activeCardRewardCoins: rewardCoins,
        activeCardIsScratched: false,
        activeCardIsClaimed: false,
        updatedAt: ts
      },
      { merge: true }
    );

    return {
      cardId,
      rewardCoins,
      scratchNumber,
      isFree: !hasUsedFree,
      dateKey: today
    };
  });
}

exports.createScratchCardSession = onCall(async (request) => handleCreateScratchCardSession(request));

/**
 * CLAIM SCRATCH CARD REWARD (Callable Function)
 * Called when claiming a completed scratch card.
 * - Atomically marks the card session as CLAIMED.
 * - Increments `scratchesCompletedToday` on server.
 * - Atomically credits rewardCoins to promotionalCoins and balanceCoins (using computeUpdatedPromoWallet).
 * - Preserves balanceCents unchanged.
 * - Records deterministic audit transaction in `/transactions/scratch_{cardId}` with type `SCRATCH_CARD`.
 * - Records deterministic in-app notification in `/users/{userId}/notifications/notif_scratch_{cardId}` with both `body` and `message`.
 * - Updates public leaderboard projection with both `balanceCents` and `balanceCoins`.
 */
async function handleClaimScratchCardReward(request, deps = {}) {
  const firestoreDb = deps.db || db;
  const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();

  if (!request.auth || !request.auth.uid) {
    throw new HttpsError("unauthenticated", "User must be logged in.");
  }

  const userId = request.auth.uid;
  const cardId = String(request.data?.cardId || "").trim();
  if (!cardId) {
    throw new HttpsError("invalid-argument", "Card ID is required.");
  }

  const userRef = firestoreDb.collection("users").doc(userId);
  const sessionRef = userRef.collection("scratch_sessions").doc(cardId);
  const txRef = firestoreDb.collection("transactions").doc(`scratch_${cardId}`);
  const notifRef = userRef.collection("notifications").doc(`notif_scratch_${cardId}`);

  const today = getTodayDateString(new Date(nowMillis));
  const statsRef = userRef.collection("scratch_stats").doc(today);

  return await firestoreDb.runTransaction(async (transaction) => {
    const [userDoc, sessionDoc, statsDoc, existingTxDoc] = await Promise.all([
      transaction.get(userRef),
      transaction.get(sessionRef),
      transaction.get(statsRef),
      transaction.get(txRef)
    ]);

    if (!userDoc.exists) {
      throw new HttpsError("not-found", "User profile not found.");
    }
    const userData = userDoc.data() || {};
    if (userData.approvalStatus !== "approved") {
      throw new HttpsError(
        "permission-denied",
        "Your account must be approved to claim rewards."
      );
    }

    if (!sessionDoc.exists) {
      throw new HttpsError("not-found", "Scratch card session not found.");
    }
    const session = sessionDoc.data() || {};

    if (session.userId !== userId) {
      throw new HttpsError("permission-denied", "Scratch card belongs to another user.");
    }

    if (session.claimed === true || session.status === "CLAIMED" || existingTxDoc.exists) {
      throw new HttpsError(
        "already-exists",
        "This Scratch Card reward has already been claimed."
      );
    }

    const rawReward = Number(session.rewardCoins);
    if (!Number.isInteger(rawReward) || rawReward < 1 || rawReward > 100) {
      throw new HttpsError(
        "failed-precondition",
        "Invalid scratch card reward stored on server."
      );
    }
    const rewardCoins = rawReward;
    const displayName =
      String(userData.displayName || "TaskFlow Member").trim() || "TaskFlow Member";
    const completedTasksCount = Number(userData.completedTasksCount || 0);

    const { oldBalanceCents, newPromo, newBalanceCoins } = computeUpdatedPromoWallet(
      userData,
      rewardCoins
    );

    const statsData = statsDoc.exists ? statsDoc.data() || {} : {};
    const completedToday = Number(statsData.scratchesCompletedToday || 0);

    if (completedToday >= SCRATCH_CARD_DAILY_LIMIT) {
      throw new HttpsError(
        "resource-exhausted",
        "Daily scratch limit (50) reached."
      );
    }

    const nextCompleted = completedToday + 1;
    const ts = getDefaultServerTimestamp(deps);

    // 1. Mark session as CLAIMED & single-use
    transaction.update(sessionRef, {
      claimed: true,
      status: "CLAIMED",
      claimedAt: ts
    });

    // 2. Update daily stats
    transaction.set(
      statsRef,
      {
        userId,
        dateKey: today,
        scratchesCompletedToday: nextCompleted,
        activeCardId: "",
        activeCardRewardCoins: 0,
        activeCardIsScratched: false,
        activeCardIsClaimed: true,
        updatedAt: ts
      },
      { merge: true }
    );

    // 3. Atomically update wallet balances (preserving balanceCents)
    transaction.update(userRef, {
      promotionalCoins: newPromo,
      balanceCoins: newBalanceCoins,
      updatedAt: ts
    });

    // 4. Update leaderboard projection (preserving balanceCents and updating balanceCoins)
    syncLeaderboardProjection(
      transaction,
      firestoreDb,
      userId,
      displayName,
      oldBalanceCents,
      newBalanceCoins,
      completedTasksCount,
      ts
    );

    // 5. Record deterministic transaction history entry
    transaction.set(txRef, {
      userId,
      userName: displayName,
      type: "SCRATCH_CARD",
      title: `Scratch Card Reward (+${rewardCoins} Coins)`,
      amountCoins: rewardCoins,
      amountCents: Math.floor((rewardCoins * 100) / 125),
      status: "CREDITED",
      referenceId: cardId,
      createdAt: ts
    });

    // 6. Record deterministic notification with both body and message
    transaction.set(notifRef, {
      userId,
      title: "Scratch Card Reward Credited 🎉",
      body: `You won +${rewardCoins} Coins from Scratch Card #${nextCompleted}!`,
      message: `You won +${rewardCoins} Coins from Scratch Card #${nextCompleted}!`,
      type: "SCRATCH_CARD_REWARD",
      rewardCoins,
      referenceId: cardId,
      isRead: false,
      createdAt: ts
    });

    return {
      success: true,
      cardId,
      rewardCoins,
      scratchesCompletedToday: nextCompleted,
      newBalanceCoins
    };
  });
}

exports.claimScratchCardReward = onCall(async (request) => handleClaimScratchCardReward(request));

exports._generateScratchRewardCoins = generateScratchRewardCoins;
exports._verifyUnityS2SSignature = verifyUnityS2SSignature;
exports._getDailyCheckInRewardCoins = getDailyCheckInRewardCoins;
exports._getDailyCheckInRewardCents = getDailyCheckInRewardCents;
exports._calculateServerDailyCheckInState = calculateServerDailyCheckInState;
exports._resolveUnityS2SSecret = resolveUnityS2SSecret;
exports._getTodayDateString = getTodayDateString;
exports._getYesterdayDateString = getYesterdayDateString;
exports._DAILY_CHECK_IN_REWARDS_COINS = DAILY_CHECK_IN_REWARDS_COINS;
exports._SPIN_SEGMENTS_COINS = SPIN_SEGMENTS_COINS;
exports._rewardHandlers = {
  handleCreateRewardSession,
  handleUnityAdsS2SCallback,
  handleClaimRewardSession,
  handleClaimDailyCheckIn,
  handleCreateSpinSession,
  handleRecordExtraSpinAdWatch,
  handleClaimSpinReward,
  handleRecordScratchAdWatch,
  handleCreateScratchCardSession,
  handleClaimScratchCardReward,
  computeUpdatedPromoWallet,
  pickWeightedSpinSegmentIndex,
  generateWatchEarnRewardCoins,
  generateScratchRewardCoins
};

// =========================================================================
// PROMO CODE SYSTEM (SERVER-AUTHORITATIVE CLOUD FUNCTIONS)
// =========================================================================

const PROMO_BOOTSTRAPPED_ADMIN_EMAIL = "dasakash24059@gmail.com";
const PROMO_CODE_REGEX = /^[A-Z0-9_-]{3,32}$/;
const PROMO_MAX_REWARD_COINS = 10_000_000;
const PROMO_MAX_SLOTS = 1_000_000;

function normalizePromoCode(rawCode) {
  if (typeof rawCode !== "string") return "";
  return rawCode.trim().toUpperCase();
}

function getDefaultServerTimestamp(deps) {
  if (deps && typeof deps.serverTimestamp === "function") {
    return deps.serverTimestamp();
  }
  return admin?.firestore?.FieldValue?.serverTimestamp
    ? admin.firestore.FieldValue.serverTimestamp()
    : new Date();
}

/**
 * Verifies Admin access strictly using Firebase Auth custom claims, verified bootstrap admin email,
 * or `/admins/{uid}` document. Never trusts `/users/{uid}.role`.
 */
async function verifyAdminAccess(
  request,
  firestoreDb,
  deniedMessage = "Only authorized administrators can perform this action."
) {
  if (!request.auth || !request.auth.uid) {
    throw new HttpsError("unauthenticated", "Admin authentication required.");
  }
  const uid = String(request.auth.uid).trim();
  if (!uid) {
    throw new HttpsError("unauthenticated", "Admin authentication required.");
  }

  const token = request.auth.token || {};
  const email = typeof token.email === "string" ? token.email.trim().toLowerCase() : "";

  if (token.admin === true) {
    return { uid, email };
  }

  if (email === PROMO_BOOTSTRAPPED_ADMIN_EMAIL && token.email_verified !== false) {
    return { uid, email };
  }

  const adminSnap = await firestoreDb.collection("admins").doc(uid).get();
  if (adminSnap.exists) {
    return { uid, email };
  }

  throw new HttpsError("permission-denied", deniedMessage);
}

async function verifyPromoAdminAccess(request, firestoreDb) {
  return verifyAdminAccess(
    request,
    firestoreDb,
    "Only authorized administrators can manage promo codes."
  );
}

/**
 * Strictly parses and validates promo code expiry timestamp in server UTC millis.
 * Rejects invalid formats, rolled-over calendar dates (e.g. Feb 31), non-integers,
 * missing dates, and past timestamps instead of silently falling back.
 */
function parseStrictPromoExpiryMillis(data, nowMillis = Date.now()) {
  if (!data || typeof data !== "object") {
    throw new HttpsError("invalid-argument", "Invalid promo code payload.");
  }

  const hasDateString =
    data.expiresAtDateTime !== undefined && data.expiresAtDateTime !== null;

  let resolvedMillis = null;

  if (hasDateString) {
    if (typeof data.expiresAtDateTime !== "string" || !data.expiresAtDateTime.trim()) {
      throw new HttpsError(
        "invalid-argument",
        "Expiry date & time must be a valid non-empty string."
      );
    }
    const rawStr = data.expiresAtDateTime.trim();
    const ymdHmMatch = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?$/.exec(
      rawStr
    );
    if (ymdHmMatch) {
      const year = parseInt(ymdHmMatch[1], 10);
      const month = parseInt(ymdHmMatch[2], 10);
      const day = parseInt(ymdHmMatch[3], 10);
      const hour = parseInt(ymdHmMatch[4], 10);
      const minute = parseInt(ymdHmMatch[5], 10);
      const second = ymdHmMatch[6] ? parseInt(ymdHmMatch[6], 10) : 0;

      if (
        month < 1 ||
        month > 12 ||
        day < 1 ||
        day > 31 ||
        hour < 0 ||
        hour > 23 ||
        minute < 0 ||
        minute > 59 ||
        second < 0 ||
        second > 59
      ) {
        throw new HttpsError(
          "invalid-argument",
          "Invalid expiry date & time values. Use YYYY-MM-DD HH:mm."
        );
      }

      const checkUtc = new Date(Date.UTC(year, month - 1, day, 12, 0, 0));
      if (
        checkUtc.getUTCFullYear() !== year ||
        checkUtc.getUTCMonth() !== month - 1 ||
        checkUtc.getUTCDate() !== day
      ) {
        throw new HttpsError(
          "invalid-argument",
          "Invalid calendar date for expiry. Please check month and day."
        );
      }

      // Interpret admin input YYYY-MM-DD HH:mm in Asia/Kolkata (UTC+05:30)
      const IST_OFFSET_MS = (5 * 60 + 30) * 60 * 1000;
      resolvedMillis =
        Date.UTC(year, month - 1, day, hour, minute, second) - IST_OFFSET_MS;
    } else if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,3})?)?(Z|[+-]\d{2}:\d{2})$/.test(rawStr)) {
      const parsed = Date.parse(rawStr);
      if (!Number.isFinite(parsed)) {
        throw new HttpsError("invalid-argument", "Invalid ISO expiry timestamp.");
      }
      resolvedMillis = parsed;
    } else {
      throw new HttpsError(
        "invalid-argument",
        "Invalid expiry date format. Use YYYY-MM-DD HH:mm."
      );
    }
  } else {
    const rawMillis = data.expiresAtMillis;
    if (
      rawMillis === undefined ||
      rawMillis === null ||
      typeof rawMillis === "boolean" ||
      typeof rawMillis === "string" ||
      !Number.isFinite(rawMillis) ||
      !Number.isInteger(rawMillis)
    ) {
      throw new HttpsError(
        "invalid-argument",
        "A valid integer expiry timestamp (expiresAtMillis) is required."
      );
    }
    resolvedMillis = rawMillis;
  }

  if (!Number.isFinite(resolvedMillis) || resolvedMillis <= 0) {
    throw new HttpsError(
      "invalid-argument",
      "Expiry timestamp must be a valid positive future time."
    );
  }

  if (resolvedMillis <= nowMillis) {
    throw new HttpsError(
      "invalid-argument",
      "Expiry date and time must be in the future."
    );
  }

  return resolvedMillis;
}

async function handleCreatePromoCode(request, deps = {}) {
  const firestoreDb = deps.db || db;
  const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();
  const adminInfo = await verifyPromoAdminAccess(request, firestoreDb);

  const data = request.data || {};
  const rawCode = data.code ?? data.rawCode;
  if (typeof rawCode !== "string") {
    throw new HttpsError("invalid-argument", "Promo code must be a string.");
  }
  const normalized = normalizePromoCode(rawCode);
  if (!PROMO_CODE_REGEX.test(normalized)) {
    throw new HttpsError(
      "invalid-argument",
      "Promo code must be 3–32 characters (letters, numbers, '-' or '_')."
    );
  }

  const rawReward = data.rewardCoins;
  if (
    typeof rawReward !== "number" ||
    !Number.isFinite(rawReward) ||
    !Number.isInteger(rawReward) ||
    rawReward < 1 ||
    rawReward > PROMO_MAX_REWARD_COINS
  ) {
    throw new HttpsError(
      "invalid-argument",
      `rewardCoins must be an integer between 1 and ${PROMO_MAX_REWARD_COINS}.`
    );
  }

  const rawSlots = data.maxSlots;
  if (
    typeof rawSlots !== "number" ||
    !Number.isFinite(rawSlots) ||
    !Number.isInteger(rawSlots) ||
    rawSlots < 1 ||
    rawSlots > PROMO_MAX_SLOTS
  ) {
    throw new HttpsError(
      "invalid-argument",
      `maxSlots must be an integer between 1 and ${PROMO_MAX_SLOTS}.`
    );
  }

  if (data.isActive !== undefined && typeof data.isActive !== "boolean") {
    throw new HttpsError("invalid-argument", "isActive must be a boolean.");
  }
  const isActive = data.isActive === undefined ? true : data.isActive;

  const expiresAtMillis = parseStrictPromoExpiryMillis(data, nowMillis);
  const expiresAtLabel =
    typeof data.expiresAtLabel === "string"
      ? data.expiresAtLabel.trim().slice(0, 64)
      : typeof data.expiresAtDateTime === "string"
        ? data.expiresAtDateTime.trim().slice(0, 64)
        : "";

  const promoRef = firestoreDb.collection("promo_codes").doc(normalized);
  const logRef = firestoreDb.collection("admin_logs").doc();
  const initialStatus = isActive ? "ACTIVE" : "INACTIVE";

  await firestoreDb.runTransaction(async (transaction) => {
    const existing = await transaction.get(promoRef);
    if (existing.exists) {
      throw new HttpsError(
        "already-exists",
        `Promo code '${normalized}' already exists. Please choose a unique code.`
      );
    }

    const ts = getDefaultServerTimestamp(deps);
    transaction.set(promoRef, {
      code: normalized,
      rewardCoins: rawReward,
      maxSlots: rawSlots,
      usedSlots: 0,
      expiresAtMillis,
      expiresAtLabel,
      isActive,
      status: initialStatus,
      createdBy: adminInfo.uid,
      createdAt: ts,
      updatedAt: ts
    });

    transaction.set(logRef, {
      actionType: "REWARD",
      title: `Created Promo Code: ${normalized}`,
      details: `Reward: ${rawReward} Coins • Max Slots: ${rawSlots} • Expires: ${
        expiresAtLabel || String(expiresAtMillis)
      }`,
      targetId: normalized,
      adminId: adminInfo.uid,
      timestampMillis: nowMillis,
      createdAt: ts
    });
  });

  return {
    success: true,
    id: normalized,
    code: normalized,
    rewardCoins: rawReward,
    maxSlots: rawSlots,
    usedSlots: 0,
    expiresAtMillis,
    expiresAtLabel,
    isActive,
    status: initialStatus,
    createdBy: adminInfo.uid
  };
}

async function handleSetPromoCodeActiveStatus(request, deps = {}) {
  const firestoreDb = deps.db || db;
  const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();
  const adminInfo = await verifyPromoAdminAccess(request, firestoreDb);

  const data = request.data || {};
  const rawId = data.promoId ?? data.code ?? "";
  if (typeof rawId !== "string") {
    throw new HttpsError("invalid-argument", "promoId must be a string.");
  }
  const cleanId = normalizePromoCode(rawId);
  if (!PROMO_CODE_REGEX.test(cleanId)) {
    throw new HttpsError("invalid-argument", "Invalid promo code ID.");
  }

  if (typeof data.isActive !== "boolean") {
    throw new HttpsError("invalid-argument", "isActive must be a boolean.");
  }
  const targetActive = data.isActive;

  const promoRef = firestoreDb.collection("promo_codes").doc(cleanId);
  const logRef = firestoreDb.collection("admin_logs").doc();

  return await firestoreDb.runTransaction(async (transaction) => {
    const snap = await transaction.get(promoRef);
    if (!snap.exists) {
      throw new HttpsError("not-found", `Promo code '${cleanId}' not found.`);
    }

    const promo = snap.data() || {};
    const expiresAtMillis = Number(promo.expiresAtMillis || 0);
    const maxSlots = Number(promo.maxSlots || 0);
    const usedSlots = Number(promo.usedSlots || 0);
    const currentStatus = String(promo.status || "").toUpperCase();

    if (
      (expiresAtMillis > 0 && expiresAtMillis <= nowMillis) ||
      currentStatus === "EXPIRED"
    ) {
      throw new HttpsError(
        "failed-precondition",
        "Expired promo codes cannot be activated or modified."
      );
    }

    if (
      !Number.isInteger(maxSlots) ||
      maxSlots <= 0 ||
      usedSlots >= maxSlots ||
      currentStatus === "EXHAUSTED"
    ) {
      throw new HttpsError(
        "failed-precondition",
        "Exhausted promo codes cannot be activated or modified."
      );
    }

    const nextStatus = targetActive ? "ACTIVE" : "INACTIVE";
    const ts = getDefaultServerTimestamp(deps);

    transaction.update(promoRef, {
      isActive: targetActive,
      status: nextStatus,
      updatedBy: adminInfo.uid,
      updatedAt: ts
    });

    transaction.set(logRef, {
      actionType: "REWARD",
      title: `Promo Code ${targetActive ? "Activated" : "Deactivated"}: ${cleanId}`,
      details: `Status set to ${nextStatus}`,
      targetId: cleanId,
      adminId: adminInfo.uid,
      timestampMillis: nowMillis,
      createdAt: ts
    });

    return {
      success: true,
      promoId: cleanId,
      isActive: targetActive,
      status: nextStatus
    };
  });
}

async function handleRedeemPromoCode(request, deps = {}) {
  if (!request.auth || !request.auth.uid) {
    throw new HttpsError("unauthenticated", "User must be logged in.");
  }

  const firestoreDb = deps.db || db;
  const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();
  const userId = String(request.auth.uid).trim();
  if (!userId) {
    throw new HttpsError("unauthenticated", "User must be logged in.");
  }

  // Strictly read ONLY the promo code string from client payload; ignore any tampered reward/balance fields.
  const rawInput = request.data?.code ?? request.data?.rawCode ?? "";
  if (typeof rawInput !== "string" || !rawInput.trim()) {
    throw new HttpsError("invalid-argument", "Please enter a promo code.");
  }
  const normalized = normalizePromoCode(rawInput);
  if (!PROMO_CODE_REGEX.test(normalized)) {
    throw new HttpsError(
      "not-found",
      "Invalid promo code. Please check the code and try again."
    );
  }

  const redemptionId = `${normalized}_${userId}`;
  const txId = `promo_${normalized}_${userId}`;
  const notifId = `notif_${txId}`;

  const userRef = firestoreDb.collection("users").doc(userId);
  const promoRef = firestoreDb.collection("promo_codes").doc(normalized);
  const redemptionRef = firestoreDb.collection("promo_redemptions").doc(redemptionId);
  const subRedemptionRef = promoRef.collection("redemptions").doc(userId);
  const txRef = firestoreDb.collection("transactions").doc(txId);
  const notifRef = userRef.collection("notifications").doc(notifId);
  const leaderboardRef = firestoreDb.collection("leaderboard").doc(userId);

  return await firestoreDb.runTransaction(async (transaction) => {
    // All reads first (Firestore transaction requirement)
    const userSnap = await transaction.get(userRef);
    const promoSnap = await transaction.get(promoRef);
    const redemptionSnap = await transaction.get(redemptionRef);
    const subRedemptionSnap = await transaction.get(subRedemptionRef);
    const txSnap = await transaction.get(txRef);

    if (!userSnap.exists) {
      throw new HttpsError("not-found", "User profile not found.");
    }
    const userData = userSnap.data() || {};
    if (userData.approvalStatus !== "approved") {
      throw new HttpsError(
        "permission-denied",
        "Your account must be approved before redeeming promo codes."
      );
    }

    if (!promoSnap.exists) {
      throw new HttpsError(
        "not-found",
        "Invalid promo code. Please check the code and try again."
      );
    }
    const promo = promoSnap.data() || {};

    if (redemptionSnap.exists || subRedemptionSnap.exists || txSnap.exists) {
      throw new HttpsError(
        "already-exists",
        `You have already redeemed promo code '${normalized}'.`
      );
    }

    const promoStatus = String(promo.status || "").toUpperCase();
    if (promo.isActive !== true || promoStatus === "INACTIVE") {
      throw new HttpsError(
        "failed-precondition",
        "This promo code is currently inactive."
      );
    }

    const expiresAtMillis = Number(promo.expiresAtMillis || 0);
    if (
      (expiresAtMillis > 0 && expiresAtMillis <= nowMillis) ||
      promoStatus === "EXPIRED"
    ) {
      throw new HttpsError("failed-precondition", "This promo code has expired.");
    }

    const maxSlots = Number(promo.maxSlots || 0);
    const usedSlots = Number(promo.usedSlots || 0);
    if (
      !Number.isInteger(maxSlots) ||
      maxSlots <= 0 ||
      usedSlots >= maxSlots ||
      promoStatus === "EXHAUSTED"
    ) {
      throw new HttpsError(
        "resource-exhausted",
        "This promo code has reached its maximum redemption limit."
      );
    }

    const rewardCoins = Number(promo.rewardCoins || 0);
    if (
      !Number.isInteger(rewardCoins) ||
      rewardCoins <= 0 ||
      rewardCoins > PROMO_MAX_REWARD_COINS
    ) {
      throw new HttpsError("failed-precondition", "Invalid promo code reward amount.");
    }

    const newUsedSlots = usedSlots + 1;
    const newPromoStatus = newUsedSlots >= maxSlots ? "EXHAUSTED" : "ACTIVE";

    const oldPromo = Number(userData.promotionalCoins || 0);
    const oldBalanceCents = Number(userData.balanceCents || 0);
    const oldCentsCoins = Math.floor((oldBalanceCents * 125) / 100);
    const oldEligible = Math.max(Number(userData.eligibleCoins || 0), oldCentsCoins);
    const oldBalanceCoins = Math.max(
      Number(userData.balanceCoins || 0),
      oldEligible + oldPromo
    );

    const newPromo = oldPromo + rewardCoins;
    const newBalanceCoins = oldBalanceCoins + rewardCoins;
    const displayName =
      String(userData.displayName || "TaskFlow Member").trim() || "TaskFlow Member";
    const completedTasksCount = Number(userData.completedTasksCount || 0);
    const ts = getDefaultServerTimestamp(deps);

    // 1. Atomically increment usedSlots & update status
    transaction.update(promoRef, {
      usedSlots: newUsedSlots,
      status: newPromoStatus,
      updatedAt: ts
    });

    // 2. Record deterministic per-user redemption documents
    const redemptionPayload = {
      promoId: normalized,
      code: normalized,
      userId,
      userName: displayName,
      rewardCoins,
      redeemedAt: ts
    };
    transaction.set(redemptionRef, redemptionPayload);
    transaction.set(subRedemptionRef, redemptionPayload);

    // 3. Atomically credit promotionalCoins and balanceCoins (preserving balanceCents)
    transaction.update(userRef, {
      promotionalCoins: newPromo,
      balanceCoins: newBalanceCoins,
      updatedAt: ts
    });

    // 4. Atomically sync public leaderboard projection
    transaction.set(
      leaderboardRef,
      {
        userId,
        displayName,
        balanceCents: oldBalanceCents,
        balanceCoins: newBalanceCoins,
        completedTasksCount,
        approvalStatus: "approved",
        updatedAt: ts
      },
      { merge: true }
    );

    // 5. Record audit transaction ledger entry
    transaction.set(txRef, {
      userId,
      userName: displayName,
      type: "PROMO_CODE",
      title: `Promo Code Redeemed (${normalized})`,
      amountCoins: rewardCoins,
      amountCents: Math.floor((rewardCoins * 100) / 125),
      status: "CREDITED",
      referenceId: normalized,
      redemptionId,
      createdAt: ts
    });

    // 6. Record in-app notification
    transaction.set(notifRef, {
      userId,
      title: "Promo Code Reward Credited",
      body: `Promo code '${normalized}' redeemed! +${rewardCoins} Coins added to your wallet.`,
      message: `Promo code '${normalized}' redeemed! +${rewardCoins} Coins added to your wallet.`,
      type: "PROMO_CODE_REWARD",
      rewardCoins,
      referenceId: normalized,
      redemptionId,
      isRead: false,
      createdAt: ts
    });

    return {
      success: true,
      id: redemptionId,
      promoId: normalized,
      code: normalized,
      userId,
      userName: displayName,
      rewardCoins,
      newPromotionalCoins: newPromo,
      newBalanceCoins
    };
  });
}

exports.createPromoCode = onCall(async (request) => handleCreatePromoCode(request));
exports.setPromoCodeActiveStatus = onCall(async (request) =>
  handleSetPromoCodeActiveStatus(request)
);
exports.redeemPromoCode = onCall(async (request) => handleRedeemPromoCode(request));

exports._promoHandlers = {
  normalizePromoCode,
  parseStrictPromoExpiryMillis,
  verifyPromoAdminAccess,
  handleCreatePromoCode,
  handleSetPromoCodeActiveStatus,
  handleRedeemPromoCode
};

// =========================================================================
// MANUAL BONUS SYSTEM (SERVER-AUTHORITATIVE CLOUD FUNCTION)
// =========================================================================

const MANUAL_BONUS_MAX_COINS = 10_000_000;
const MANUAL_BONUS_KEY_REGEX = /^[A-Za-z0-9_-]{6,128}$/;

async function handleCreditManualBonus(request, deps = {}) {
  const firestoreDb = deps.db || db;
  const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();
  const adminInfo = await verifyAdminAccess(
    request,
    firestoreDb,
    "Only authorized administrators can credit manual bonuses."
  );

  const data = request.data || {};
  const targetUserId = String(data.targetUserId || "").trim();
  if (!targetUserId || targetUserId.length > 128) {
    throw new HttpsError("invalid-argument", "Please select a valid target user.");
  }

  const rawAmount = data.amountCoins;
  if (
    typeof rawAmount !== "number" ||
    !Number.isFinite(rawAmount) ||
    !Number.isInteger(rawAmount) ||
    rawAmount < 1 ||
    rawAmount > MANUAL_BONUS_MAX_COINS
  ) {
    throw new HttpsError(
      "invalid-argument",
      `Manual bonus amountCoins must be an integer between 1 and ${MANUAL_BONUS_MAX_COINS}.`
    );
  }

  const idempotencyKey = String(data.idempotencyKey || "").trim();
  if (!MANUAL_BONUS_KEY_REGEX.test(idempotencyKey)) {
    throw new HttpsError(
      "invalid-argument",
      "A valid idempotencyKey (6-128 alphanumeric/'-'/'_' characters) is required."
    );
  }

  const rawReason = typeof data.reason === "string" ? data.reason.trim().slice(0, 500) : "";
  const displayReason = rawReason || "Special Admin Bonus";

  const userRef = firestoreDb.collection("users").doc(targetUserId);
  const bonusRef = firestoreDb.collection("manual_bonuses").doc(idempotencyKey);
  const txId = `bonus_${idempotencyKey}`;
  const txRef = firestoreDb.collection("transactions").doc(txId);
  const notifRef = userRef.collection("notifications").doc(`notif_${txId}`);
  const logRef = firestoreDb.collection("admin_logs").doc();
  const ts = getDefaultServerTimestamp(deps);

  return await firestoreDb.runTransaction(async (transaction) => {
    const [bonusSnap, txSnap, userSnap] = await Promise.all([
      transaction.get(bonusRef),
      transaction.get(txRef),
      transaction.get(userRef)
    ]);

    if (bonusSnap.exists || txSnap.exists) {
      throw new HttpsError(
        "already-exists",
        "This manual bonus has already been credited (duplicate request prevented)."
      );
    }

    if (!userSnap.exists) {
      throw new HttpsError("not-found", "Selected user profile not found.");
    }

    const userData = userSnap.data() || {};
    const wallet = computeUpdatedPromoWallet(userData, rawAmount);
    const targetName =
      String(userData.displayName || "TaskFlow Member").trim() || "TaskFlow Member";
    const targetEmail = String(userData.email || "").trim();
    const completedTasksCount = Number(userData.completedTasksCount || 0);
    const approvalStatus = String(userData.approvalStatus || "approved");

    // 1. Atomically credit promotionalCoins & balanceCoins (preserving balanceCents & eligibleCoins)
    transaction.update(userRef, {
      promotionalCoins: wallet.newPromo,
      balanceCoins: wallet.newBalanceCoins,
      updatedAt: ts
    });

    // 2. Sync leaderboard projection
    syncLeaderboardProjection(
      transaction,
      firestoreDb,
      targetUserId,
      targetName,
      wallet.oldBalanceCents,
      wallet.newBalanceCoins,
      completedTasksCount,
      ts,
      approvalStatus
    );

    // 3. Record canonical /manual_bonuses audit document (`targetUserId` + server `adminId`)
    transaction.set(bonusRef, {
      targetUserId,
      targetUserName: targetName,
      targetUserEmail: targetEmail,
      adminId: adminInfo.uid,
      adminEmail: adminInfo.email,
      amountCoins: rawAmount,
      reason: displayReason,
      idempotencyKey,
      createdAt: ts
    });

    // 4. Record transaction ledger entry
    transaction.set(txRef, {
      userId: targetUserId,
      userName: targetName,
      type: "ADMIN_MANUAL_BONUS",
      title: `Admin Bonus: ${displayReason.slice(0, 80)}`,
      amountCents: Math.floor((rawAmount * 100) / 125),
      amountCoins: rawAmount,
      status: "CREDITED",
      referenceId: idempotencyKey,
      createdAt: ts
    });

    // 5. Deliver Notification Bell entry (`body` and `message`)
    const notifBody = `You received +${rawAmount} Coins from Admin! Reason: ${displayReason}`;
    transaction.set(notifRef, {
      userId: targetUserId,
      title: "Admin Bonus Credited",
      body: notifBody,
      message: notifBody,
      type: "ADMIN_BONUS",
      rewardCoins: rawAmount,
      referenceId: idempotencyKey,
      isRead: false,
      createdAt: ts
    });

    // 6. Write Admin activity audit log
    transaction.set(logRef, {
      actionType: "REWARD",
      title: `Credited Manual Bonus (+${rawAmount} Coins)`,
      details: `User: ${targetName} (${targetUserId}) • Reason: ${displayReason}`,
      targetId: idempotencyKey,
      adminId: adminInfo.uid,
      timestampMillis: nowMillis,
      createdAt: ts
    });

    return {
      success: true,
      id: idempotencyKey,
      targetUserId,
      targetUserName: targetName,
      targetUserEmail: targetEmail,
      adminId: adminInfo.uid,
      adminEmail: adminInfo.email,
      amountCoins: rawAmount,
      reason: displayReason,
      idempotencyKey,
      newPromotionalCoins: wallet.newPromo,
      newBalanceCoins: wallet.newBalanceCoins
    };
  });
}

exports.creditManualBonus = onCall(async (request) => handleCreditManualBonus(request));
exports._manualBonusHandlers = {
  handleCreditManualBonus,
  verifyAdminAccess
};

// =========================================================================
// UNIVERSAL CAMPAIGN TRACKING ENGINE — PHASE 3B: SECURE CLICK ISSUANCE
// =========================================================================

const CAMPAIGN_ID_REGEX = /^[a-zA-Z0-9_-]{1,128}$/;
const CLICK_IDEMPOTENCY_REGEX = /^[a-zA-Z0-9_-]{6,128}$/;
const DEFAULT_CLICK_COOLDOWN_MS = 30 * 1000;
const CLICK_HOURLY_WINDOW_MS = 60 * 60 * 1000;
const DEFAULT_MAX_CLICKS_PER_USER_PER_HOUR = 5;
const GLOBAL_USER_RATE_WINDOW_MS = 60 * 1000;
const DEFAULT_MAX_USER_CLICKS_PER_MINUTE = 20;
const DEFAULT_ATTRIBUTION_WINDOW_HOURS = 72;

async function handleCreateCampaignClick(request, deps = {}) {
  if (!request.auth || !request.auth.uid) {
    throw new HttpsError("unauthenticated", "User must be signed in to start a campaign.");
  }

  const firestoreDb = deps.db || db;
  const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();
  const userId = String(request.auth.uid).trim();
  const data = request.data || {};

  // Strictly derive userId from Firebase Auth; reject spoofed userId fields.
  if (
    data.userId !== undefined &&
    data.userId !== null &&
    String(data.userId).trim() !== "" &&
    String(data.userId).trim() !== userId
  ) {
    throw new HttpsError(
      "permission-denied",
      "Authenticated user identity mismatch."
    );
  }

  const campaignId = String(data.campaignId || "").trim();
  if (!CAMPAIGN_ID_REGEX.test(campaignId)) {
    throw new HttpsError("invalid-argument", "A valid campaignId is required.");
  }

  const taskId = String(data.taskId || "").trim();
  if (taskId && !CAMPAIGN_ID_REGEX.test(taskId)) {
    throw new HttpsError("invalid-argument", "Invalid taskId format.");
  }

  const idempotencyKey = String(data.idempotencyKey || "").trim();
  if (idempotencyKey && !CLICK_IDEMPOTENCY_REGEX.test(idempotencyKey)) {
    throw new HttpsError("invalid-argument", "Invalid idempotencyKey format.");
  }

  const rawHeaders = request.rawRequest?.headers || deps.headers || {};
  const countryContext = resolveCountryTrustContext(rawHeaders, {
    trustCloudflareHeaders:
      deps.trustCloudflareHeaders ?? process.env.TRUST_CLOUDFLARE_HEADERS === "true"
  });
  const appCheckToken = request.app || deps.appCheck || null;
  const deviceContext = resolveDeviceTrustContext(data, appCheckToken);
  if (!deviceContext.platform) {
    throw new HttpsError(
      "invalid-argument",
      "Unsupported devicePlatform. Allowed values: ANDROID, IOS, WEB."
    );
  }
  const devicePlatform = deviceContext.platform;

  const ts = getDefaultServerTimestamp(deps);
  const registry = deps.adapterRegistry || new CampaignNetworkAdapterRegistry();

  const userRef = firestoreDb.collection("users").doc(userId);
  const configRef = firestoreDb
    .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS)
    .doc(campaignId);
  const campaignRef = firestoreDb.collection("campaigns").doc(campaignId);
  const guardRef = firestoreDb
    .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICK_GUARDS)
    .doc(`${userId}__${campaignId}`);
  const userRateRef = firestoreDb
    .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICK_GUARDS)
    .doc(`rate_${userId}`);
  const ownReservationRef =
    typeof configRef.collection === "function"
      ? configRef.collection("active_reservations").doc(userId)
      : null;

  return await firestoreDb.runTransaction(async (transaction) => {
    // All primary reads BEFORE any writes
    const primaryReads = [
      transaction.get(userRef),
      transaction.get(configRef),
      transaction.get(campaignRef),
      transaction.get(guardRef),
      transaction.get(userRateRef)
    ];
    if (ownReservationRef) {
      primaryReads.push(transaction.get(ownReservationRef));
    }
    const [
      userSnap,
      configSnap,
      campaignSnap,
      guardSnap,
      userRateSnap,
      ownReservationSnap = null
    ] = await Promise.all(primaryReads);

    if (!userSnap.exists) {
      throw new HttpsError("not-found", "User profile not found.");
    }
    const userData = userSnap.data() || {};
    if (userData.approvalStatus !== "approved") {
      throw new HttpsError(
        "permission-denied",
        "Your account must be approved by an administrator before participating in campaigns."
      );
    }

    if (!configSnap.exists) {
      throw new HttpsError(
        "not-found",
        "Campaign tracking configuration not found."
      );
    }
    const configData = configSnap.data() || {};

    if (configData.isActive !== true) {
      throw new HttpsError(
        "failed-precondition",
        "This campaign is currently inactive."
      );
    }

    if (campaignSnap.exists) {
      const campStatus = String(campaignSnap.data()?.status || "ACTIVE")
        .trim()
        .toUpperCase();
      if (campStatus !== "ACTIVE") {
        throw new HttpsError(
          "failed-precondition",
          `This campaign is currently ${campStatus.toLowerCase()}.`
        );
      }
    }

    // Time window enforcement using authoritative server clock
    const startAtMillis = Math.max(
      Number(configData.startAtMillis || 0),
      toMillisValue(configData.startAt)
    );
    const expiresAtMillis = Math.max(
      Number(configData.expiresAtMillis || 0),
      toMillisValue(configData.expiresAt)
    );
    if (startAtMillis > 0 && nowMillis < startAtMillis) {
      throw new HttpsError(
        "failed-precondition",
        "This campaign has not started yet."
      );
    }
    if (expiresAtMillis > 0 && nowMillis > expiresAtMillis) {
      throw new HttpsError("failed-precondition", "This campaign has expired.");
    }

    const verificationMode = String(configData.verificationMode || "").trim();
    if (!isValidVerificationMode(verificationMode)) {
      throw new HttpsError(
        "failed-precondition",
        "Campaign has an invalid verification mode."
      );
    }

    const networkId = String(configData.networkId || "").trim();
    if (!CAMPAIGN_ID_REGEX.test(networkId)) {
      throw new HttpsError(
        "failed-precondition",
        "Campaign network reference is invalid."
      );
    }

    const networkRef = firestoreDb
      .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS)
      .doc(networkId);
    const networkSnap = await transaction.get(networkRef);
    if (!networkSnap.exists) {
      throw new HttpsError(
        "failed-precondition",
        "Configured tracking network was not found."
      );
    }
    const networkData = networkSnap.data() || {};
    if (networkData.isActive !== true) {
      throw new HttpsError(
        "failed-precondition",
        "Configured tracking network is currently inactive."
      );
    }
    if (networkData.capabilities?.supportsIncentivizedTraffic !== true) {
      throw new HttpsError(
        "permission-denied",
        "Configured tracking network does not permit incentivized traffic."
      );
    }

    // Strict Country & Device Trust Enforcement (Fail-closed when strict verification cannot be verified)
    const allowedCountries = Array.isArray(configData.allowedCountries)
      ? configData.allowedCountries.map((c) => String(c || "").trim().toUpperCase())
      : [];
    const allowsAllCountries = allowedCountries.length === 0 || allowedCountries.includes("ALL");
    if (!allowsAllCountries) {
      if (!countryContext.countryCode) {
        throw new HttpsError(
          "failed-precondition",
          "Verified country signal is unavailable for this geo-restricted campaign."
        );
      }
      if (!allowedCountries.includes(countryContext.countryCode)) {
        throw new HttpsError(
          "permission-denied",
          `This campaign is not available in your country (${countryContext.countryCode}).`
        );
      }
    }
    const resolvedCountryCode = countryContext.countryCode || "UNVERIFIED";

    if (
      configData.requireDeviceAttestation === true &&
      deviceContext.trustLevel !== "APP_CHECK_VERIFIED"
    ) {
      throw new HttpsError(
        "failed-precondition",
        "Device attestation (App Check) is required for this campaign."
      );
    }

    const allowedPlatforms = Array.isArray(configData.allowedPlatforms)
      ? configData.allowedPlatforms.map((p) => String(p || "").trim().toUpperCase())
      : [];
    if (
      allowedPlatforms.length > 0 &&
      !allowedPlatforms.includes("ALL") &&
      !allowedPlatforms.includes(devicePlatform)
    ) {
      throw new HttpsError(
        "permission-denied",
        `This campaign is not supported on ${devicePlatform}.`
      );
    }

    // Read guard + existing active click (still strictly before any writes)
    const guardData = guardSnap.exists ? guardSnap.data() || {} : {};
    const allowMultiple =
      configData.conversionRequirements?.allowMultipleConversions === true;
    if (guardData.hasCompletedConversion === true && !allowMultiple) {
      throw new HttpsError(
        "already-exists",
        "You have already completed this campaign."
      );
    }

    const cooldownMs = Math.max(
      0,
      Number(configData.clickCooldownMs ?? DEFAULT_CLICK_COOLDOWN_MS)
    );
    const activeClickId = String(guardData.activeClickId || "").trim();
    const activeClickExpiresAtMillis = Number(guardData.activeClickExpiresAtMillis || 0);
    const isUnexpiredActiveClick =
      Boolean(activeClickId) && activeClickExpiresAtMillis > nowMillis;
    const lastIssuedAtMillis = Number(guardData.lastIssuedAtMillis || 0);
    const isWithinCooldown =
      lastIssuedAtMillis > 0 && nowMillis - lastIssuedAtMillis < cooldownMs;
    const isSameIdempotencyKey =
      Boolean(idempotencyKey) && guardData.lastIdempotencyKey === idempotencyKey;

    if (isUnexpiredActiveClick && (isWithinCooldown || isSameIdempotencyKey)) {
      const existingClickRef = firestoreDb
        .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS)
        .doc(activeClickId);
      const existingClickSnap = await transaction.get(existingClickRef);
      if (existingClickSnap.exists) {
        const existingClick = existingClickSnap.data() || {};
        if (
          existingClick.status === "ISSUED" &&
          Number(existingClick.expiresAtMillis || 0) > nowMillis &&
          typeof existingClick.trackingUrl === "string" &&
          validateDestinationUrl(existingClick.trackingUrl).valid
        ) {
          return {
            success: true,
            clickId: existingClick.clickId,
            campaignId: existingClick.campaignId,
            networkId: existingClick.networkId,
            verificationMode: existingClick.verificationMode,
            trackingUrl: existingClick.trackingUrl,
            expiresAtMillis: existingClick.expiresAtMillis,
            deduplicated: true
          };
        }
      }
    }

    // Per-user-per-campaign hourly rate limit & global burst abuse guard
    const maxClicksPerHour = Math.max(
      1,
      Number(configData.maxClicksPerUserPerHour || DEFAULT_MAX_CLICKS_PER_USER_PER_HOUR)
    );
    const windowStart = Number(guardData.windowStartedAtMillis || 0);
    const inCampaignWindow =
      windowStart > 0 && nowMillis - windowStart < CLICK_HOURLY_WINDOW_MS;
    const campaignWindowClicks = inCampaignWindow
      ? Number(guardData.windowClickCount || 0)
      : 0;
    if (campaignWindowClicks >= maxClicksPerHour) {
      throw new HttpsError(
        "resource-exhausted",
        "Too many click attempts on this campaign. Please try again later."
      );
    }

    const rateData = userRateSnap.exists ? userRateSnap.data() || {} : {};
    const userRateWindowStart = Number(rateData.windowStartedAtMillis || 0);
    const inUserRateWindow =
      userRateWindowStart > 0 && nowMillis - userRateWindowStart < GLOBAL_USER_RATE_WINDOW_MS;
    const userMinuteClicks = inUserRateWindow
      ? Number(rateData.windowClickCount || 0)
      : 0;
    const maxPerMinute = Math.max(
      1,
      Number(deps.maxUserClicksPerMinute || DEFAULT_MAX_USER_CLICKS_PER_MINUTE)
    );
    if (userMinuteClicks >= maxPerMinute) {
      throw new HttpsError(
        "resource-exhausted",
        "Click rate limit exceeded. Please wait a moment and try again."
      );
    }

    // Lazy expiration read phase (strictly BEFORE any writes) to release expired reservedClickSlots
    const reservedClickSlots = Math.max(0, Number(configData.reservedClickSlots || 0));
    const activeReservationsCol =
      typeof configRef.collection === "function"
        ? configRef.collection("active_reservations")
        : null;
    const expiredReservationsByClickId = new Map();

    if (
      ownReservationSnap &&
      ownReservationSnap.exists &&
      Number(ownReservationSnap.data()?.expiresAtMillis || 0) <= nowMillis
    ) {
      const ownResData = ownReservationSnap.data() || {};
      const ownClickId = String(ownResData.clickId || activeClickId || "").trim();
      if (ownClickId) {
        expiredReservationsByClickId.set(ownClickId, {
          userId,
          clickId: ownClickId,
          resRef: ownReservationRef
        });
      }
    } else if (
      guardData.hasActiveReservation === true &&
      activeClickId &&
      activeClickExpiresAtMillis <= nowMillis
    ) {
      expiredReservationsByClickId.set(activeClickId, {
        userId,
        clickId: activeClickId,
        resRef: ownReservationRef
      });
    }

    if (
      reservedClickSlots > 0 &&
      activeReservationsCol &&
      typeof activeReservationsCol.where === "function"
    ) {
      const expiredQuery = activeReservationsCol
        .where("expiresAtMillis", "<=", nowMillis)
        .limit(25);
      const expiredQuerySnap = await transaction.get(expiredQuery);
      const docs = expiredQuerySnap?.docs || [];
      for (const docSnap of docs) {
        const resData = typeof docSnap.data === "function" ? docSnap.data() || {} : {};
        const expClickId = String(resData.clickId || "").trim();
        const expUserId = String(resData.userId || docSnap.id || "").trim();
        if (expClickId && !expiredReservationsByClickId.has(expClickId)) {
          expiredReservationsByClickId.set(expClickId, {
            userId: expUserId,
            clickId: expClickId,
            resRef: activeReservationsCol.doc(expUserId)
          });
        }
      }
    }

    const expiredReleaseCandidates = [];
    if (expiredReservationsByClickId.size > 0) {
      const entries = Array.from(expiredReservationsByClickId.values());
      const clickSnaps = await Promise.all(
        entries.map((entry) =>
          transaction.get(
            firestoreDb
              .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS)
              .doc(entry.clickId)
          )
        )
      );
      for (let i = 0; i < entries.length; i++) {
        const entry = entries[i];
        const clickSnap = clickSnaps[i];
        const clickData = clickSnap.exists ? clickSnap.data() || {} : null;
        const clickExpires = Number(clickData?.expiresAtMillis || 0);
        const isExpiredIssuedClick =
          clickData && clickData.status === "ISSUED" && clickExpires <= nowMillis;
        expiredReleaseCandidates.push({
          ...entry,
          clickRef: firestoreDb
            .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS)
            .doc(entry.clickId),
          isExpiredIssuedClick: Boolean(isExpiredIssuedClick),
          shouldDeleteStaleRes: Boolean(entry.resRef)
        });
      }
    }

    const reclaimedSlotsCount = expiredReleaseCandidates.filter(
      (item) => item.isExpiredIssuedClick
    ).length;
    const effectiveReservedSlots = Math.max(0, reservedClickSlots - reclaimedSlotsCount);

    // Atomic budget & slot checks (always using server-side rewardTf and post-reclamation reservedClickSlots)
    const rewardTf = Number(configData.rewardTf || 0);
    if (!Number.isInteger(rewardTf) || rewardTf <= 0) {
      throw new HttpsError(
        "failed-precondition",
        "Campaign tracking configuration has an invalid TF reward."
      );
    }
    const submissionBudget = Number(configData.submissionBudget || 0);
    const usedSubmissions = Number(configData.usedSubmissions || 0);
    const clickOverbookingBuffer = Math.max(0, Number(configData.clickOverbookingBuffer || 0));
    const rewardBudgetTf = Number(configData.rewardBudgetTf || 0);
    const spentBudgetTf = Number(configData.spentBudgetTf || 0);

    if (submissionBudget > 0 && usedSubmissions >= submissionBudget) {
      throw new HttpsError(
        "resource-exhausted",
        "This campaign has reached its maximum submission budget."
      );
    }
    if (rewardBudgetTf > 0 && spentBudgetTf + rewardTf > rewardBudgetTf) {
      throw new HttpsError(
        "resource-exhausted",
        "This campaign's TF reward budget has been exhausted."
      );
    }

    const userAlreadyReservedSlot =
      guardData.hasActiveReservation === true && activeClickExpiresAtMillis > nowMillis;
    if (
      submissionBudget > 0 &&
      !userAlreadyReservedSlot &&
      usedSubmissions + effectiveReservedSlots >= submissionBudget + clickOverbookingBuffer
    ) {
      throw new HttpsError(
        "resource-exhausted",
        "This campaign has reached its active participation limit."
      );
    }
    if (
      rewardBudgetTf > 0 &&
      !userAlreadyReservedSlot &&
      spentBudgetTf + (effectiveReservedSlots * rewardTf) + rewardTf >
        rewardBudgetTf + (clickOverbookingBuffer * rewardTf)
    ) {
      throw new HttpsError(
        "resource-exhausted",
        "This campaign's active TF reward budget has reached capacity."
      );
    }

    // Build & validate destination URL strictly from server config + network adapter
    const rawBaseUrl = String(
      configData.trackingBaseUrl ||
        (campaignSnap.exists ? campaignSnap.data()?.taskLink : "") ||
        ""
    ).trim();
    if (!rawBaseUrl) {
      throw new HttpsError(
        "failed-precondition",
        "Campaign destination tracking URL is missing."
      );
    }

    const clickId =
      typeof deps.clickIdGenerator === "function"
        ? String(deps.clickIdGenerator()).trim()
        : generateSecureClickId();
    if (!CAMPAIGN_ID_REGEX.test(clickId) || clickId.length < 16) {
      throw new HttpsError("internal", "Generated invalid click identifier.");
    }

    let builtTracking;
    try {
      builtTracking = buildSafeTrackingUrl(
        rawBaseUrl,
        { ...configData, verificationMode },
        { ...networkData, networkId },
        {
          clickId,
          userId,
          campaignId,
          countryCode: resolvedCountryCode,
          devicePlatform
        },
        registry
      );
    } catch (urlErr) {
      throw new HttpsError(
        "failed-precondition",
        urlErr?.message || "Invalid campaign tracking URL."
      );
    }

    const attributionWindowHours = Math.min(
      2160,
      Math.max(
        1,
        Number(
          configData.conversionRequirements?.attributionWindowHours ||
            DEFAULT_ATTRIBUTION_WINDOW_HOURS
        )
      )
    );
    const clickExpiresAtMillis =
      expiresAtMillis > 0
        ? Math.min(expiresAtMillis, nowMillis + attributionWindowHours * 60 * 60 * 1000)
        : nowMillis + attributionWindowHours * 60 * 60 * 1000;
    const clickExpiresAt = makeTimestampFromMillis(clickExpiresAtMillis, deps);
    const trackingNetworkRef = String(
      configData.trackingNetworkRef ||
        `${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/${networkId}`
    );

    const clickRef = firestoreDb
      .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS)
      .doc(clickId);

    // Atomic writes: release expired reservations, store click, update user guard/rate limits, update config click reservation
    for (const candidate of expiredReleaseCandidates) {
      if (candidate.isExpiredIssuedClick) {
        transaction.update(candidate.clickRef, {
          status: "EXPIRED",
          expiredAt: ts,
          expiredReason: "RESERVATION_TIMEOUT"
        });
      }
      if (candidate.shouldDeleteStaleRes && candidate.userId !== userId && typeof transaction.delete === "function") {
        transaction.delete(candidate.resRef);
      }
      if (candidate.userId && candidate.userId !== userId) {
        const otherGuardRef = firestoreDb
          .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICK_GUARDS)
          .doc(`${candidate.userId}__${campaignId}`);
        transaction.set(
          otherGuardRef,
          {
            hasActiveReservation: false,
            updatedAt: ts
          },
          { merge: true }
        );
      }
    }

    transaction.set(clickRef, {
      clickId,
      userId,
      campaignId,
      ...(taskId ? { taskId } : {}),
      networkId,
      trackingNetworkRef,
      verificationMode,
      countryCode: resolvedCountryCode,
      countryTrustLevel: countryContext.trustLevel,
      countrySource: countryContext.source,
      devicePlatform,
      deviceTrustLevel: deviceContext.trustLevel,
      resolvedParameters: builtTracking.resolvedParameters,
      trackingUrl: builtTracking.trackingUrl,
      status: "ISSUED",
      expiresAtMillis: clickExpiresAtMillis,
      expiresAt: clickExpiresAt,
      createdAt: ts
    });

    if (ownReservationRef) {
      transaction.set(ownReservationRef, {
        userId,
        campaignId,
        clickId,
        expiresAtMillis: clickExpiresAtMillis,
        createdAt: ts,
        updatedAt: ts
      });
    }

    transaction.set(
      guardRef,
      {
        userId,
        campaignId,
        activeClickId: clickId,
        activeClickExpiresAtMillis: clickExpiresAtMillis,
        hasActiveReservation: true,
        hasCompletedConversion: Boolean(guardData.hasCompletedConversion),
        lastIssuedAtMillis: nowMillis,
        lastIdempotencyKey: idempotencyKey || "",
        windowStartedAtMillis: inCampaignWindow ? windowStart : nowMillis,
        windowClickCount: campaignWindowClicks + 1,
        updatedAt: ts
      },
      { merge: true }
    );

    transaction.set(
      userRateRef,
      {
        userId,
        windowStartedAtMillis: inUserRateWindow ? userRateWindowStart : nowMillis,
        windowClickCount: userMinuteClicks + 1,
        updatedAt: ts
      },
      { merge: true }
    );

    const nextReservedSlots =
      effectiveReservedSlots + (userAlreadyReservedSlot ? 0 : 1);
    transaction.update(configRef, {
      issuedClicksCount: Number(configData.issuedClicksCount || 0) + 1,
      reservedClickSlots: nextReservedSlots,
      lastClickIssuedAt: ts,
      updatedAt: ts
    });

    return {
      success: true,
      clickId,
      campaignId,
      networkId,
      verificationMode,
      trackingUrl: builtTracking.trackingUrl,
      expiresAtMillis: clickExpiresAtMillis,
      deduplicated: false
    };
  });
}

/**
 * Scheduled/sweep handler to release expired ISSUED campaign clicks and restore
 * `reservedClickSlots` atomically per campaign without requiring composite indexes.
 */
async function handleReleaseExpiredCampaignClickReservations(deps = {}) {
  const firestoreDb = deps.db || db;
  const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();
  const batchLimit = Math.min(500, Math.max(1, Number(deps.batchLimit || 100)));
  const ts = getDefaultServerTimestamp(deps);

  const clicksCol = firestoreDb.collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS);
  const querySnap = await clicksCol
    .where("expiresAtMillis", "<=", nowMillis)
    .limit(batchLimit)
    .get();

  const expiredByCampaign = new Map();
  for (const docSnap of querySnap?.docs || []) {
    const clickData = typeof docSnap.data === "function" ? docSnap.data() || {} : {};
    if (clickData.status !== "ISSUED") continue;
    const cId = String(clickData.campaignId || "").trim();
    const clkId = String(clickData.clickId || docSnap.id || "").trim();
    const uId = String(clickData.userId || "").trim();
    if (!cId || !clkId) continue;
    if (!expiredByCampaign.has(cId)) {
      expiredByCampaign.set(cId, []);
    }
    expiredByCampaign.get(cId).push({ clickId: clkId, userId: uId });
  }

  let totalReleased = 0;
  for (const [campaignId, items] of expiredByCampaign.entries()) {
    const configRef = firestoreDb
      .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS)
      .doc(campaignId);

    const releasedForCampaign = await firestoreDb.runTransaction(async (transaction) => {
      const configSnap = await transaction.get(configRef);
      if (!configSnap.exists) return 0;
      const configData = configSnap.data() || {};

      const itemReads = await Promise.all(
        items.map(async (item) => {
          const clickRef = clicksCol.doc(item.clickId);
          const guardRef = item.userId
            ? firestoreDb
                .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICK_GUARDS)
                .doc(`${item.userId}__${campaignId}`)
            : null;
          const resRef =
            item.userId && typeof configRef.collection === "function"
              ? configRef.collection("active_reservations").doc(item.userId)
              : null;

          const [clickSnap, guardSnap, resSnap] = await Promise.all([
            transaction.get(clickRef),
            guardRef ? transaction.get(guardRef) : Promise.resolve(null),
            resRef ? transaction.get(resRef) : Promise.resolve(null)
          ]);
          return { item, clickRef, guardRef, resRef, clickSnap, guardSnap, resSnap };
        })
      );

      let transitioned = 0;
      for (const rec of itemReads) {
        if (!rec.clickSnap.exists) continue;
        const clickData = rec.clickSnap.data() || {};
        if (clickData.status !== "ISSUED" || Number(clickData.expiresAtMillis || 0) > nowMillis) {
          continue;
        }
        transitioned += 1;
        transaction.update(rec.clickRef, {
          status: "EXPIRED",
          expiredAt: ts,
          expiredReason: "SCHEDULED_EXPIRATION_SWEEP"
        });
        if (
          rec.resRef &&
          rec.resSnap &&
          rec.resSnap.exists &&
          rec.resSnap.data()?.clickId === rec.item.clickId &&
          typeof transaction.delete === "function"
        ) {
          transaction.delete(rec.resRef);
        }
        if (
          rec.guardRef &&
          rec.guardSnap &&
          rec.guardSnap.exists &&
          rec.guardSnap.data()?.activeClickId === rec.item.clickId
        ) {
          transaction.set(
            rec.guardRef,
            { hasActiveReservation: false, updatedAt: ts },
            { merge: true }
          );
        }
      }

      if (transitioned > 0) {
        const currentReserved = Math.max(0, Number(configData.reservedClickSlots || 0));
        transaction.update(configRef, {
          reservedClickSlots: Math.max(0, currentReserved - transitioned),
          updatedAt: ts
        });
      }
      return transitioned;
    });

    totalReleased += releasedForCampaign;
  }

  return { success: true, releasedClicks: totalReleased };
}

// =========================================================================
// UNIVERSAL CAMPAIGN TRACKING ENGINE — PHASE 3C: CONVERSION VERIFICATION & TF REWARDS
// =========================================================================

const EXTERNAL_TXN_ID_REGEX = /^[a-zA-Z0-9_.:-]{1,128}$/;
const ALLOWED_ADMIN_CONVERSION_ACTIONS = new Set(["APPROVE", "REJECT", "REVERSE"]);

class CampaignPostbackHttpError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

/**
 * Resolves a network verification secret strictly from server-side Secret Manager / env / deps.
 * Never reads secret values from Firestore client-accessible or tracking documents.
 */
function resolveCampaignNetworkSecret(networkData = {}, deps = {}) {
  const sigConfig = networkData.signatureConfig || {};
  const secretEnvKey = String(sigConfig.secretEnvKey || "CAMPAIGN_POSTBACK_SECRET").trim();
  const networkId = String(networkData.networkId || "").trim();

  if (typeof deps.resolveSecret === "function") {
    const val = String(deps.resolveSecret(secretEnvKey, networkId) || "").trim();
    if (val) return val;
  }

  if (deps.secrets && typeof deps.secrets === "object" && typeof deps.secrets[secretEnvKey] === "string") {
    const val = deps.secrets[secretEnvKey].trim();
    if (val) return val;
  }

  if (!SECRET_ENV_KEY_REGEX.test(secretEnvKey)) {
    return "";
  }

  const envVal = String(process.env[secretEnvKey] || "").trim();
  if (envVal) return envVal;

  if (secretEnvKey === "CAMPAIGN_POSTBACK_SECRET") {
    try {
      const boundVal = String(campaignPostbackSecret.value() || "").trim();
      if (boundVal) return boundVal;
    } catch (_) {
      // Secret parameter unavailable outside bound cloud runtime
    }
  }

  return "";
}

function extractNetworkIdFromPostbackRequest(req = {}) {
  const queryNet = req.query?.networkId ?? req.query?.network_id;
  if (typeof queryNet === "string" && queryNet.trim()) {
    return queryNet.trim();
  }
  const bodyObj = req.body && typeof req.body === "object" && !Array.isArray(req.body) ? req.body : {};
  const bodyNet = bodyObj.networkId ?? bodyObj.network_id;
  if (typeof bodyNet === "string" && bodyNet.trim()) {
    return bodyNet.trim();
  }
  const paramNet = req.params?.networkId;
  if (typeof paramNet === "string" && paramNet.trim()) {
    return paramNet.trim();
  }
  const rawPath = String(req.path || req.url || "").split("?")[0].replace(/^\/+|\/+$/g, "");
  if (rawPath) {
    const segments = rawPath.split("/").filter(Boolean);
    const candidate = segments[segments.length - 1] || "";
    if (candidate && candidate !== "campaignPostback") {
      return candidate;
    }
  }
  return "";
}

/**
 * Core atomic transaction processor for Phase 3C conversion transitions (`PENDING`, `APPROVED`, `REJECTED`, `REVERSED`).
 * Shared between external S2S postback callbacks and Admin-authorized manual review actions.
 */
async function executeConversionTransitionInTransaction({
  firestoreDb,
  nowMillis,
  ts,
  clickId,
  networkIdFromRequest = "",
  externalTransactionId = "",
  targetState,
  sourceCallbackType = "S2S_POSTBACK",
  idempotencyKey = "",
  reportedCampaignId = "",
  reportedUserId = "",
  callbackPayoutRaw = "",
  statusReason = "",
  actorAdminInfo = null,
  isAdminOverride = false
}) {
  const clickRef = firestoreDb
    .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS)
    .doc(clickId);
  const conversionId = `conv_${clickId}`;
  const conversionRef = firestoreDb
    .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS)
    .doc(conversionId);

  return await firestoreDb.runTransaction(async (transaction) => {
    // Phase 1 Reads: click + existing conversion (+ external txn lock when networkIdFromRequest known)
    const preliminaryReads = [
      transaction.get(clickRef),
      transaction.get(conversionRef)
    ];
    let preLockRef = null;
    if (networkIdFromRequest && externalTransactionId) {
      preLockRef = firestoreDb
        .collection(CAMPAIGN_CONVERSION_LOCKS_COLLECTION)
        .doc(buildExternalTxnLockId(networkIdFromRequest, externalTransactionId));
      preliminaryReads.push(transaction.get(preLockRef));
    }

    const [clickSnap, conversionSnap, preLockSnap = null] = await Promise.all(preliminaryReads);

    if (!clickSnap.exists) {
      throw new CampaignPostbackHttpError(404, "not-found", "Unknown campaign click ID.");
    }
    const clickData = clickSnap.data() || {};
    const resolvedClickId = String(clickData.clickId || clickId).trim();
    if (resolvedClickId !== clickId) {
      throw new CampaignPostbackHttpError(400, "invalid-argument", "Click identifier attribution mismatch.");
    }

    const clickNetworkId = String(clickData.networkId || "").trim();
    const campaignId = String(clickData.campaignId || "").trim();
    const userId = String(clickData.userId || "").trim();

    if (!clickNetworkId || !campaignId || !userId) {
      throw new CampaignPostbackHttpError(400, "failed-precondition", "Stored click attribution record is incomplete.");
    }

    if (networkIdFromRequest && clickNetworkId !== networkIdFromRequest) {
      throw new CampaignPostbackHttpError(403, "permission-denied", "Click does not belong to the specified tracking network.");
    }
    if (reportedCampaignId && reportedCampaignId !== campaignId) {
      throw new CampaignPostbackHttpError(400, "invalid-argument", "Callback campaign ID does not match attributed click.");
    }
    if (reportedUserId && reportedUserId !== userId) {
      throw new CampaignPostbackHttpError(403, "permission-denied", "Callback user ID does not match attributed click.");
    }

    const existingConversion = conversionSnap.exists ? conversionSnap.data() || {} : null;
    const creditTxId = `conv_credit_${conversionId}`;
    const revTxId = `conv_rev_${conversionId}`;

    // Phase 2 Reads (still strictly BEFORE any writes): config, network, campaign, user, guard, reservation, ledger docs, and lock
    const configRef = firestoreDb
      .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS)
      .doc(campaignId);
    const networkRef = firestoreDb
      .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS)
      .doc(clickNetworkId);
    const campaignRef = firestoreDb.collection("campaigns").doc(campaignId);
    const userRef = firestoreDb.collection("users").doc(userId);
    const guardRef = firestoreDb
      .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICK_GUARDS)
      .doc(`${userId}__${campaignId}`);
    const resRef =
      typeof configRef.collection === "function"
        ? configRef.collection("active_reservations").doc(userId)
        : null;
    const creditTxRef = firestoreDb.collection("transactions").doc(creditTxId);
    const revTxRef = firestoreDb.collection("transactions").doc(revTxId);

    let lockRef = preLockRef;
    let needSecondLockRead = false;
    if (!lockRef && externalTransactionId) {
      lockRef = firestoreDb
        .collection(CAMPAIGN_CONVERSION_LOCKS_COLLECTION)
        .doc(buildExternalTxnLockId(clickNetworkId, externalTransactionId));
      needSecondLockRead = true;
    }

    const secondaryReads = [
      transaction.get(configRef),
      transaction.get(networkRef),
      transaction.get(campaignRef),
      transaction.get(userRef),
      transaction.get(guardRef),
      transaction.get(creditTxRef),
      transaction.get(revTxRef),
      resRef ? transaction.get(resRef) : Promise.resolve(null),
      needSecondLockRead ? transaction.get(lockRef) : Promise.resolve(preLockSnap)
    ];

    const [
      configSnap,
      networkSnap,
      campaignSnap,
      userSnap,
      guardSnap,
      creditTxSnap,
      revTxSnap,
      resSnap,
      lockSnap
    ] = await Promise.all(secondaryReads);

    if (lockSnap && lockSnap.exists) {
      const lockData = lockSnap.data() || {};
      if (
        lockData.conversionId !== conversionId ||
        lockData.clickId !== clickId ||
        lockData.campaignId !== campaignId
      ) {
        throw new CampaignPostbackHttpError(
          409,
          "already-exists",
          "Duplicate external transaction ID is already bound to another conversion."
        );
      }
    }

    if (!configSnap.exists) {
      throw new CampaignPostbackHttpError(404, "not-found", "Campaign tracking configuration not found.");
    }
    const configData = configSnap.data() || {};

    if (String(configData.networkId || "").trim() !== clickNetworkId) {
      throw new CampaignPostbackHttpError(403, "permission-denied", "Click network does not match campaign configuration.");
    }

    const networkData = networkSnap.exists ? networkSnap.data() || {} : null;
    const verificationMode = String(
      clickData.verificationMode || configData.verificationMode || ""
    ).trim();

    if (!isAdminOverride) {
      if (
        verificationMode !== CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK &&
        verificationMode !== CAMPAIGN_VERIFICATION_MODES.ADVERTISER_S2S_API &&
        verificationMode !== CAMPAIGN_VERIFICATION_MODES.CUSTOM_APPROVED
      ) {
        throw new CampaignPostbackHttpError(
          403,
          "permission-denied",
          `Campaign verification mode '${verificationMode}' does not accept S2S postbacks.`
        );
      }
      if (networkData && networkData.capabilities?.supportsPostback === false && verificationMode !== CAMPAIGN_VERIFICATION_MODES.CUSTOM_APPROVED) {
        throw new CampaignPostbackHttpError(
          403,
          "permission-denied",
          "Tracking network does not allow postback callbacks."
        );
      }
    }

    // Hold verified postbacks in PENDING when campaign/network requires manual Admin review
    let effectiveTargetState = targetState;
    let effectiveStatusReason = statusReason;
    if (
      !isAdminOverride &&
      targetState === CAMPAIGN_CONVERSION_STATES.APPROVED &&
      (configData.conversionRequirements?.requireAdminApproval === true ||
        networkData?.capabilities?.requiresManualReview === true) &&
      (!existingConversion || existingConversion.state === CAMPAIGN_CONVERSION_STATES.PENDING)
    ) {
      if (!existingConversion) {
        effectiveTargetState = CAMPAIGN_CONVERSION_STATES.PENDING;
        effectiveStatusReason = statusReason || "HELD_FOR_ADMIN_REVIEW";
      } else {
        return {
          success: true,
          duplicate: true,
          heldForReview: true,
          conversionId,
          clickId,
          campaignId,
          networkId: clickNetworkId,
          userId,
          state: existingConversion.state,
          rewardTf: Number(existingConversion.rewardTf || configData.rewardTf || 0),
          rewardCredited: Boolean(existingConversion.rewardCredited),
          idempotencyKey: existingConversion.idempotencyKey || idempotencyKey || conversionId
        };
      }
    }

    // Idempotent duplicate replay guard (state matches or terminal duplicate replay)
    if (existingConversion) {
      const currentState = String(existingConversion.state || "").trim();
      if (currentState === effectiveTargetState) {
        return {
          success: true,
          duplicate: true,
          conversionId,
          clickId,
          campaignId,
          networkId: clickNetworkId,
          userId,
          state: currentState,
          rewardTf: Number(existingConversion.rewardTf || 0),
          rewardCredited: Boolean(existingConversion.rewardCredited),
          idempotencyKey: existingConversion.idempotencyKey || idempotencyKey || conversionId
        };
      }
      if (!canTransitionConversionState(currentState, effectiveTargetState)) {
        throw new CampaignPostbackHttpError(
          409,
          "failed-precondition",
          `Invalid conversion state transition from ${currentState} to ${effectiveTargetState}.`
        );
      }
    } else {
      // Brand new conversion creation rules
      if (effectiveTargetState === CAMPAIGN_CONVERSION_STATES.REVERSED) {
        throw new CampaignPostbackHttpError(
          409,
          "failed-precondition",
          "Cannot reverse a conversion that has not been approved."
        );
      }

      const clickStatus = String(clickData.status || "").trim();
      if (clickStatus !== "ISSUED") {
        throw new CampaignPostbackHttpError(
          409,
          "failed-precondition",
          `Click '${clickId}' cannot be converted in status '${clickStatus}'.`
        );
      }

      const clickExpiresAtMillis = Number(clickData.expiresAtMillis || 0);
      if (!Number.isFinite(clickExpiresAtMillis) || clickExpiresAtMillis <= nowMillis) {
        throw new CampaignPostbackHttpError(
          410,
          "deadline-exceeded",
          "Campaign click attribution window has expired."
        );
      }
    }

    // Campaign, network & user eligibility validation for active approvals or holding in PENDING
    if (
      effectiveTargetState !== CAMPAIGN_CONVERSION_STATES.REVERSED &&
      effectiveTargetState !== CAMPAIGN_CONVERSION_STATES.REJECTED
    ) {
      if (configData.isActive !== true) {
        throw new CampaignPostbackHttpError(400, "failed-precondition", "Campaign tracking configuration is inactive.");
      }
      if (campaignSnap.exists) {
        const campStatus = String(campaignSnap.data()?.status || "ACTIVE").trim().toUpperCase();
        if (campStatus !== "ACTIVE") {
          throw new CampaignPostbackHttpError(400, "failed-precondition", `Campaign is currently ${campStatus.toLowerCase()}.`);
        }
      }
      if (!networkSnap.exists || networkData?.isActive !== true) {
        throw new CampaignPostbackHttpError(403, "failed-precondition", "Tracking network is inactive or missing.");
      }

      if (!existingConversion) {
        const startAtMillis = Math.max(Number(configData.startAtMillis || 0), toMillisValue(configData.startAt));
        const expiresAtMillis = Math.max(Number(configData.expiresAtMillis || 0), toMillisValue(configData.expiresAt));
        if (startAtMillis > 0 && nowMillis < startAtMillis) {
          throw new CampaignPostbackHttpError(400, "failed-precondition", "Campaign has not started yet.");
        }
        if (expiresAtMillis > 0 && nowMillis > expiresAtMillis) {
          throw new CampaignPostbackHttpError(400, "failed-precondition", "Campaign has expired.");
        }
      }
    }

    if (!userSnap.exists) {
      throw new CampaignPostbackHttpError(404, "not-found", "Attributed user profile not found.");
    }
    const userData = userSnap.data() || {};
    if (
      effectiveTargetState === CAMPAIGN_CONVERSION_STATES.APPROVED &&
      userData.approvalStatus !== "approved"
    ) {
      throw new CampaignPostbackHttpError(
        403,
        "permission-denied",
        "Attributed user account is not approved to receive TF rewards."
      );
    }

    const guardData = guardSnap.exists ? guardSnap.data() || {} : {};
    const allowMultiple = configData.conversionRequirements?.allowMultipleConversions === true;
    if (!existingConversion && guardData.hasCompletedConversion === true && !allowMultiple) {
      throw new CampaignPostbackHttpError(
        409,
        "already-exists",
        "User has already completed an approved conversion for this campaign."
      );
    }

    // Never trust payout supplied in callback; always resolve server-configured rewardTf
    const authoritativeRewardTf = existingConversion
      ? Number(existingConversion.rewardTf || configData.rewardTf || 0)
      : Number(configData.rewardTf || 0);
    if (!Number.isInteger(authoritativeRewardTf) || authoritativeRewardTf <= 0) {
      throw new CampaignPostbackHttpError(
        400,
        "failed-precondition",
        "Campaign tracking configuration has an invalid TF reward."
      );
    }

    const submissionBudget = Number(configData.submissionBudget || 0);
    const usedSubmissions = Number(configData.usedSubmissions || 0);
    const rewardBudgetTf = Number(configData.rewardBudgetTf || 0);
    const spentBudgetTf = Number(configData.spentBudgetTf || 0);
    const currentReservedSlots = Math.max(0, Number(configData.reservedClickSlots || 0));
    const hasActiveResForClick = Boolean(
      resSnap && resSnap.exists && resSnap.data()?.clickId === clickId
    );
    const nextReservedAfterRelease = hasActiveResForClick
      ? Math.max(0, currentReservedSlots - 1)
      : currentReservedSlots;

    const displayName =
      String(userData.displayName || "TaskFlow Member").trim() || "TaskFlow Member";
    const completedTasksCount = Math.max(0, Number(userData.completedTasksCount || 0));
    const campaignTitle = String(
      (campaignSnap.exists ? campaignSnap.data()?.title : "") || configData.title || campaignId
    ).trim();
    const resolvedIdempotencyKey = String(
      idempotencyKey ||
      existingConversion?.idempotencyKey ||
      (externalTransactionId ? `${clickNetworkId}:${externalTransactionId}` : conversionId)
    ).trim();

    if (effectiveTargetState === CAMPAIGN_CONVERSION_STATES.APPROVED) {
      if (creditTxSnap.exists || existingConversion?.rewardCredited === true) {
        throw new CampaignPostbackHttpError(
          409,
          "already-exists",
          "Reward transaction has already been credited for this conversion."
        );
      }
      if (submissionBudget > 0 && usedSubmissions + 1 > submissionBudget) {
        throw new CampaignPostbackHttpError(
          409,
          "resource-exhausted",
          "Campaign submission budget has been exhausted."
        );
      }
      if (rewardBudgetTf > 0 && spentBudgetTf + authoritativeRewardTf > rewardBudgetTf) {
        throw new CampaignPostbackHttpError(
          409,
          "resource-exhausted",
          "Campaign TF reward budget has been exhausted."
        );
      }
    }

    if (effectiveTargetState === CAMPAIGN_CONVERSION_STATES.REVERSED) {
      if (!existingConversion || existingConversion.rewardCredited !== true || existingConversion.reversedAt || revTxSnap.exists) {
        throw new CampaignPostbackHttpError(
          409,
          "failed-precondition",
          "Conversion is not eligible for reversal or has already been reversed."
        );
      }
    }

    // Phase 3: Atomic Writes
    if (lockRef && !lockSnap?.exists && externalTransactionId) {
      transaction.set(lockRef, {
        externalTransactionId,
        networkId: clickNetworkId,
        conversionId,
        clickId,
        campaignId,
        userId,
        createdAt: ts
      });
    }

    const baseConversionFields = {
      conversionId,
      clickId,
      userId,
      campaignId,
      networkId: clickNetworkId,
      trackingNetworkRef: String(
        clickData.trackingNetworkRef || `${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/${clickNetworkId}`
      ),
      verificationMode,
      rewardTf: authoritativeRewardTf,
      externalTransactionId: String(externalTransactionId || existingConversion?.externalTransactionId || ""),
      idempotencyKey: resolvedIdempotencyKey,
      sourceCallbackType,
      ...(callbackPayoutRaw ? { callbackPayoutRaw } : {}),
      updatedAt: ts
    };

    const logRef = firestoreDb.collection("admin_logs").doc();

    if (effectiveTargetState === CAMPAIGN_CONVERSION_STATES.PENDING) {
      transaction.set(
        conversionRef,
        {
          ...baseConversionFields,
          state: CAMPAIGN_CONVERSION_STATES.PENDING,
          rewardCredited: false,
          statusReason: effectiveStatusReason || "AWAITING_VERIFICATION",
          createdAt: existingConversion?.createdAt || ts
        },
        { merge: true }
      );

      if (clickData.status === "ISSUED") {
        transaction.update(clickRef, {
          status: "PENDING_VERIFICATION",
          pendingConversionAt: ts
        });
      }
      if (hasActiveResForClick && resRef && typeof transaction.delete === "function") {
        transaction.delete(resRef);
      }
      if (hasActiveResForClick) {
        transaction.update(configRef, {
          reservedClickSlots: nextReservedAfterRelease,
          updatedAt: ts
        });
      }
      transaction.set(
        guardRef,
        {
          userId,
          campaignId,
          hasActiveReservation: false,
          hasPendingConversion: true,
          lastConversionId: conversionId,
          updatedAt: ts
        },
        { merge: true }
      );
      transaction.set(logRef, {
        actionType: "CAMPAIGN",
        title: `Conversion Pending: ${campaignTitle.slice(0, 60)}`,
        details: `Conversion ${conversionId} (${authoritativeRewardTf} TF) held in PENDING (${effectiveStatusReason || "awaiting approval"})`,
        targetId: conversionId,
        adminId: actorAdminInfo?.uid || "SYSTEM_POSTBACK",
        timestampMillis: nowMillis,
        createdAt: ts
      });

      return {
        success: true,
        duplicate: false,
        conversionId,
        clickId,
        campaignId,
        networkId: clickNetworkId,
        userId,
        state: CAMPAIGN_CONVERSION_STATES.PENDING,
        rewardTf: authoritativeRewardTf,
        rewardCredited: false,
        idempotencyKey: resolvedIdempotencyKey
      };
    }

    if (effectiveTargetState === CAMPAIGN_CONVERSION_STATES.APPROVED) {
      const wallet = computeCampaignApprovedWallet(userData, authoritativeRewardTf);
      const nextCompletedTasks = completedTasksCount + 1;

      // 1. Credit user wallet atomically (withdrawable earnings: eligibleCoins + balanceCents + balanceCoins)
      transaction.update(userRef, {
        balanceCents: wallet.newBalanceCents,
        eligibleCoins: wallet.newEligibleCoins,
        balanceCoins: wallet.newBalanceCoins,
        completedTasksCount: nextCompletedTasks,
        updatedAt: ts
      });

      // 2. Sync public leaderboard
      syncLeaderboardProjection(
        transaction,
        firestoreDb,
        userId,
        displayName,
        wallet.newBalanceCents,
        wallet.newBalanceCoins,
        nextCompletedTasks,
        ts,
        userData.approvalStatus || "approved"
      );

      // 3. Create immutable wallet ledger credit entry
      transaction.set(creditTxRef, {
        userId,
        userName: displayName,
        type: "CAMPAIGN_REWARD",
        title: `Campaign Reward: ${campaignTitle.slice(0, 60)}`,
        amountCoins: authoritativeRewardTf,
        amountCents: wallet.rewardCents,
        status: "CREDITED",
        referenceId: conversionId,
        clickId,
        campaignId,
        networkId: clickNetworkId,
        externalTransactionId: String(externalTransactionId || existingConversion?.externalTransactionId || ""),
        createdAt: ts
      });

      // 4. Create user notification
      const notifRef = userRef.collection("notifications").doc(`notif_${creditTxId}`);
      transaction.set(notifRef, {
        userId,
        title: "Campaign Reward Credited",
        body: `Your conversion for '${campaignTitle.slice(0, 60)}' was verified! +${authoritativeRewardTf} TF credited to your wallet.`,
        message: `Your conversion for '${campaignTitle.slice(0, 60)}' was verified! +${authoritativeRewardTf} TF credited to your wallet.`,
        type: "CAMPAIGN_REWARD",
        rewardCoins: authoritativeRewardTf,
        referenceId: conversionId,
        isRead: false,
        createdAt: ts
      });

      // 5. Mark click CONVERTED & release any reservation
      transaction.update(clickRef, {
        status: "CONVERTED",
        convertedAt: ts
      });
      if (hasActiveResForClick && resRef && typeof transaction.delete === "function") {
        transaction.delete(resRef);
      }

      // 6. Update config budgets & submission counters
      transaction.update(configRef, {
        usedSubmissions: usedSubmissions + 1,
        spentBudgetTf: spentBudgetTf + authoritativeRewardTf,
        reservedClickSlots: nextReservedAfterRelease,
        updatedAt: ts
      });

      if (campaignSnap.exists) {
        const campData = campaignSnap.data() || {};
        transaction.update(campaignRef, {
          completedSubmissions: Math.max(0, Number(campData.completedSubmissions || 0)) + 1,
          spentCents: Math.max(0, Number(campData.spentCents || 0)) + wallet.rewardCents,
          updatedAt: ts
        });
      }

      // 7. Update user campaign guard
      transaction.set(
        guardRef,
        {
          userId,
          campaignId,
          hasActiveReservation: false,
          hasPendingConversion: false,
          hasCompletedConversion: true,
          completedConversionsCount: Number(guardData.completedConversionsCount || 0) + 1,
          lastConversionId: conversionId,
          updatedAt: ts
        },
        { merge: true }
      );

      // 8. Save conversion record as APPROVED
      transaction.set(
        conversionRef,
        {
          ...baseConversionFields,
          state: CAMPAIGN_CONVERSION_STATES.APPROVED,
          rewardCents: wallet.rewardCents,
          rewardCredited: true,
          creditedTransactionId: creditTxId,
          statusReason: effectiveStatusReason || "VERIFIED",
          ...(actorAdminInfo ? { approvedByAdminId: actorAdminInfo.uid } : {}),
          verifiedAt: ts,
          createdAt: existingConversion?.createdAt || ts
        },
        { merge: true }
      );

      // 9. Write admin audit log
      transaction.set(logRef, {
        actionType: "CAMPAIGN",
        title: `Conversion Approved (+${authoritativeRewardTf} TF)`,
        details: `Campaign: ${campaignId} • User: ${userId} • Click: ${clickId} • Mode: ${verificationMode}`,
        targetId: conversionId,
        adminId: actorAdminInfo?.uid || "SYSTEM_POSTBACK",
        timestampMillis: nowMillis,
        createdAt: ts
      });

      return {
        success: true,
        duplicate: false,
        conversionId,
        clickId,
        campaignId,
        networkId: clickNetworkId,
        userId,
        state: CAMPAIGN_CONVERSION_STATES.APPROVED,
        rewardTf: authoritativeRewardTf,
        rewardCents: wallet.rewardCents,
        rewardCredited: true,
        creditedTransactionId: creditTxId,
        newBalanceCoins: wallet.newBalanceCoins,
        newEligibleCoins: wallet.newEligibleCoins,
        newBalanceCents: wallet.newBalanceCents,
        idempotencyKey: resolvedIdempotencyKey
      };
    }

    if (effectiveTargetState === CAMPAIGN_CONVERSION_STATES.REJECTED) {
      if (hasActiveResForClick && resRef && typeof transaction.delete === "function") {
        transaction.delete(resRef);
      }
      if (hasActiveResForClick) {
        transaction.update(configRef, {
          reservedClickSlots: nextReservedAfterRelease,
          updatedAt: ts
        });
      }
      if (clickData.status === "ISSUED" || clickData.status === "PENDING_VERIFICATION") {
        transaction.update(clickRef, {
          status: "CANCELLED",
          cancelledAt: ts
        });
      }
      transaction.set(
        guardRef,
        {
          userId,
          campaignId,
          hasActiveReservation: false,
          hasPendingConversion: false,
          lastConversionId: conversionId,
          updatedAt: ts
        },
        { merge: true }
      );
      transaction.set(
        conversionRef,
        {
          ...baseConversionFields,
          state: CAMPAIGN_CONVERSION_STATES.REJECTED,
          rewardCredited: false,
          statusReason: effectiveStatusReason || "REJECTED",
          ...(actorAdminInfo ? { rejectedByAdminId: actorAdminInfo.uid } : {}),
          rejectedAt: ts,
          createdAt: existingConversion?.createdAt || ts
        },
        { merge: true }
      );
      transaction.set(logRef, {
        actionType: "CAMPAIGN",
        title: `Conversion Rejected: ${campaignTitle.slice(0, 60)}`,
        details: `Campaign: ${campaignId} • User: ${userId} • Reason: ${effectiveStatusReason || "Rejected"}`,
        targetId: conversionId,
        adminId: actorAdminInfo?.uid || "SYSTEM_POSTBACK",
        timestampMillis: nowMillis,
        createdAt: ts
      });

      return {
        success: true,
        duplicate: false,
        conversionId,
        clickId,
        campaignId,
        networkId: clickNetworkId,
        userId,
        state: CAMPAIGN_CONVERSION_STATES.REJECTED,
        rewardTf: authoritativeRewardTf,
        rewardCredited: false,
        idempotencyKey: resolvedIdempotencyKey
      };
    }

    // APPROVED -> REVERSED (Compensating ledger entry + wallet + budget reconciliation; never deletes history)
    const revWallet = computeCampaignReversedWallet(userData, authoritativeRewardTf);
    const nextCompletedAfterReversal = Math.max(0, completedTasksCount - 1);
    const reversalCents = tfToBalanceCents(authoritativeRewardTf);

    // 1. Reverse user wallet safely without negative balances
    transaction.update(userRef, {
      balanceCents: revWallet.newBalanceCents,
      eligibleCoins: revWallet.newEligibleCoins,
      promotionalCoins: revWallet.newPromoCoins,
      balanceCoins: revWallet.newBalanceCoins,
      completedTasksCount: nextCompletedAfterReversal,
      updatedAt: ts
    });

    // 2. Sync leaderboard projection
    syncLeaderboardProjection(
      transaction,
      firestoreDb,
      userId,
      displayName,
      revWallet.newBalanceCents,
      revWallet.newBalanceCoins,
      nextCompletedAfterReversal,
      ts,
      userData.approvalStatus || "approved"
    );

    // 3. Create auditable compensating ledger entry (original credit tx stays untouched!)
    transaction.set(revTxRef, {
      userId,
      userName: displayName,
      type: "CAMPAIGN_REVERSAL",
      title: `Conversion Reversed: ${campaignTitle.slice(0, 60)}`,
      amountCoins: authoritativeRewardTf,
      amountCents: reversalCents,
      reversedCoins: -authoritativeRewardTf,
      recoveredCoins: revWallet.recoveredCoins,
      shortfallCoins: revWallet.shortfallCoins,
      status: "REVERSED",
      referenceId: conversionId,
      originalTransactionId: existingConversion.creditedTransactionId || creditTxId,
      clickId,
      campaignId,
      networkId: clickNetworkId,
      reason: effectiveStatusReason || "ADVERTISER_REVERSAL",
      createdAt: ts
    });

    // 4. Notify user
    const revNotifRef = userRef.collection("notifications").doc(`notif_${revTxId}`);
    transaction.set(revNotifRef, {
      userId,
      title: "Campaign Conversion Reversed",
      body: `Reward of ${authoritativeRewardTf} TF for '${campaignTitle.slice(0, 60)}' was reversed (${effectiveStatusReason || "advertiser reversal"}).`,
      message: `Reward of ${authoritativeRewardTf} TF for '${campaignTitle.slice(0, 60)}' was reversed (${effectiveStatusReason || "advertiser reversal"}).`,
      type: "CAMPAIGN_REVERSAL",
      rewardCoins: authoritativeRewardTf,
      referenceId: conversionId,
      isRead: false,
      createdAt: ts
    });

    // 5. Reconcile campaign budget
    transaction.update(configRef, {
      usedSubmissions: Math.max(0, usedSubmissions - 1),
      spentBudgetTf: Math.max(0, spentBudgetTf - authoritativeRewardTf),
      updatedAt: ts
    });

    if (campaignSnap.exists) {
      const campData = campaignSnap.data() || {};
      transaction.update(campaignRef, {
        completedSubmissions: Math.max(0, Number(campData.completedSubmissions || 0) - 1),
        spentCents: Math.max(0, Number(campData.spentCents || 0) - reversalCents),
        updatedAt: ts
      });
    }

    // 6. Update guard + conversion record
    const prevCompletedConversions = Math.max(0, Number(guardData.completedConversionsCount || 0));
    const remainingCompletedConversions = Math.max(0, prevCompletedConversions - 1);
    transaction.set(
      guardRef,
      {
        userId,
        campaignId,
        hasCompletedConversion: remainingCompletedConversions > 0,
        completedConversionsCount: remainingCompletedConversions,
        hasReversedConversion: true,
        reversedConversionsCount: Number(guardData.reversedConversionsCount || 0) + 1,
        lastReversalAt: ts,
        updatedAt: ts
      },
      { merge: true }
    );

    transaction.set(
      conversionRef,
      {
        ...baseConversionFields,
        state: CAMPAIGN_CONVERSION_STATES.REVERSED,
        rewardCredited: true,
        creditedTransactionId: existingConversion.creditedTransactionId || creditTxId,
        reversalTransactionId: revTxId,
        recoveredCoins: revWallet.recoveredCoins,
        shortfallCoins: revWallet.shortfallCoins,
        statusReason: effectiveStatusReason || "ADVERTISER_REVERSAL",
        ...(actorAdminInfo ? { reversedByAdminId: actorAdminInfo.uid } : {}),
        reversedAt: ts,
        createdAt: existingConversion.createdAt || ts
      },
      { merge: true }
    );

    transaction.set(logRef, {
      actionType: "CAMPAIGN",
      title: `Conversion Reversed (-${authoritativeRewardTf} TF)`,
      details: `Campaign: ${campaignId} • User: ${userId} • Recovered: ${revWallet.recoveredCoins} TF • Shortfall: ${revWallet.shortfallCoins} TF • Reason: ${effectiveStatusReason || "Reversal"}`,
      targetId: conversionId,
      adminId: actorAdminInfo?.uid || "SYSTEM_POSTBACK",
      timestampMillis: nowMillis,
      createdAt: ts
    });

    return {
      success: true,
      duplicate: false,
      conversionId,
      clickId,
      campaignId,
      networkId: clickNetworkId,
      userId,
      state: CAMPAIGN_CONVERSION_STATES.REVERSED,
      rewardTf: authoritativeRewardTf,
      recoveredCoins: revWallet.recoveredCoins,
      shortfallCoins: revWallet.shortfallCoins,
      reversalTransactionId: revTxId,
      newBalanceCoins: revWallet.newBalanceCoins,
      newEligibleCoins: revWallet.newEligibleCoins,
      newBalanceCents: revWallet.newBalanceCents,
      idempotencyKey: resolvedIdempotencyKey
    };
  });
}

/**
 * SECURE SERVER-TO-SERVER POSTBACK ENDPOINT (HTTP Handler)
 * Receives conversion callbacks from external affiliate networks or advertisers.
 * Strictly validates network activation, server-side signature/secret, click attribution,
 * idempotency locks, state machine transitions, and atomic TF reward crediting/reversal.
 */
async function handleCampaignPostback(req, res, deps = {}) {
  try {
    if (req.method !== "GET" && req.method !== "POST") {
      return res.status(405).json({ success: false, error: "Method Not Allowed" });
    }

    const networkId = extractNetworkIdFromPostbackRequest(req);
    if (!CAMPAIGN_ID_REGEX.test(networkId)) {
      return res.status(400).json({ success: false, error: "Missing or invalid networkId." });
    }

    const firestoreDb = deps.db || db;
    const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();
    const ts = getDefaultServerTimestamp(deps);
    const registry = deps.adapterRegistry || new CampaignNetworkAdapterRegistry();

    const networkSnap = await firestoreDb
      .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS)
      .doc(networkId)
      .get();

    if (!networkSnap.exists) {
      return res.status(404).json({ success: false, error: "Tracking network not found." });
    }
    const networkData = { ...(networkSnap.data() || {}), networkId };
    if (networkData.isActive !== true) {
      return res.status(403).json({ success: false, error: "Tracking network is inactive." });
    }

    const secret = resolveCampaignNetworkSecret(networkData, deps);
    if (!secret) {
      console.error(`CRITICAL: Server secret not configured for network '${networkId}'. Rejecting postback.`);
      return res.status(500).json({
        success: false,
        error: "Server verification secret is not configured."
      });
    }

    let adapter;
    try {
      adapter = registry.resolve(
        networkData.adapterType || CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
        networkData.adapterId || networkId
      );
    } catch (_) {
      return res.status(400).json({ success: false, error: "Unsupported network adapter configuration." });
    }

    const normalized = adapter.normalizeConversionPayload(
      { query: req.query || {}, body: req.body || {}, headers: req.headers || {} },
      networkData
    );

    // Cryptographically authenticate postback BEFORE mutating any state or exposing click status
    const sigCheck = verifyCampaignPostbackSignature({
      req,
      network: networkData,
      normalizedPayload: normalized,
      secret
    });
    if (!sigCheck.valid) {
      const statusCode = sigCheck.code === "missing-server-secret" ? 500 : 401;
      return res.status(statusCode).json({
        success: false,
        error: sigCheck.reason || "Invalid postback signature."
      });
    }

    // Validate callback payload fields strictly
    if (!normalized.clickId || !CAMPAIGN_ID_REGEX.test(normalized.clickId)) {
      return res.status(400).json({ success: false, error: "Missing or malformed clickId." });
    }
    if (
      !normalized.externalTransactionId ||
      !EXTERNAL_TXN_ID_REGEX.test(normalized.externalTransactionId)
    ) {
      return res.status(400).json({
        success: false,
        error: "Missing or malformed externalTransactionId."
      });
    }
    if (normalized.reportedCampaignId && !CAMPAIGN_ID_REGEX.test(normalized.reportedCampaignId)) {
      return res.status(400).json({ success: false, error: "Malformed campaignId in callback." });
    }

    const result = await executeConversionTransitionInTransaction({
      firestoreDb,
      nowMillis,
      ts,
      clickId: normalized.clickId,
      networkIdFromRequest: networkId,
      externalTransactionId: normalized.externalTransactionId,
      targetState: normalized.normalizedState,
      sourceCallbackType: "S2S_POSTBACK",
      idempotencyKey: normalized.idempotencyKey,
      reportedCampaignId: normalized.reportedCampaignId,
      reportedUserId: normalized.reportedUserId,
      callbackPayoutRaw: normalized.payoutRaw,
      statusReason: normalized.rawStatus ? `POSTBACK:${normalized.rawStatus}` : "POSTBACK",
      isAdminOverride: false
    });

    return res.status(200).json(result);
  } catch (err) {
    if (err instanceof CampaignPostbackHttpError) {
      return res.status(err.status).json({
        success: false,
        code: err.code,
        error: err.message
      });
    }
    console.error("Error processing campaign postback:", err);
    return res.status(500).json({
      success: false,
      error: "Internal error processing conversion postback."
    });
  }
}

/**
 * ADMIN-AUTHORIZED CONVERSION REVIEW (Callable Handler)
 * Allows verified administrators to manually approve PENDING conversions (e.g. MANUAL_PROOF,
 * CUSTOM_APPROVED, or held postbacks), reject PENDING conversions, or reverse APPROVED conversions
 * using the exact same atomic ledger, wallet, and budget reconciliation engine.
 */
async function handleReviewCampaignConversion(request, deps = {}) {
  const firestoreDb = deps.db || db;
  const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();
  const ts = getDefaultServerTimestamp(deps);

  const adminInfo = await verifyAdminAccess(
    request,
    firestoreDb,
    "Only authorized administrators can review campaign conversions."
  );

  const data = request.data || {};
  const action = String(data.action || "").trim().toUpperCase();
  if (!ALLOWED_ADMIN_CONVERSION_ACTIONS.has(action)) {
    throw new HttpsError(
      "invalid-argument",
      "action must be one of APPROVE, REJECT, REVERSE."
    );
  }

  let clickId = String(data.clickId || "").trim();
  const conversionId = String(data.conversionId || "").trim();
  if (!clickId && conversionId) {
    if (!CAMPAIGN_ID_REGEX.test(conversionId)) {
      throw new HttpsError("invalid-argument", "Invalid conversionId format.");
    }
    if (conversionId.startsWith("conv_")) {
      clickId = conversionId.slice("conv_".length);
    } else {
      const convSnap = await firestoreDb
        .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS)
        .doc(conversionId)
        .get();
      if (!convSnap.exists) {
        throw new HttpsError("not-found", "Conversion record not found.");
      }
      clickId = String(convSnap.data()?.clickId || "").trim();
    }
  }

  if (!clickId || !CAMPAIGN_ID_REGEX.test(clickId)) {
    throw new HttpsError("invalid-argument", "A valid conversionId or clickId is required.");
  }

  const reason = typeof data.reason === "string" ? data.reason.trim().slice(0, 500) : "";
  if ((action === "REJECT" || action === "REVERSE") && !reason) {
    throw new HttpsError(
      "invalid-argument",
      `A review reason is required when performing ${action}.`
    );
  }

  const targetState =
    action === "APPROVE"
      ? CAMPAIGN_CONVERSION_STATES.APPROVED
      : action === "REJECT"
        ? CAMPAIGN_CONVERSION_STATES.REJECTED
        : CAMPAIGN_CONVERSION_STATES.REVERSED;

  try {
    return await executeConversionTransitionInTransaction({
      firestoreDb,
      nowMillis,
      ts,
      clickId,
      networkIdFromRequest: "",
      externalTransactionId: String(data.externalTransactionId || "").trim(),
      targetState,
      sourceCallbackType: "ADMIN_REVIEW",
      idempotencyKey: String(data.idempotencyKey || "").trim(),
      statusReason: reason || `ADMIN_${action}`,
      actorAdminInfo: adminInfo,
      isAdminOverride: true
    });
  } catch (err) {
    if (err instanceof CampaignPostbackHttpError) {
      throw new HttpsError(err.code || "failed-precondition", err.message);
    }
    throw err;
  }
}

exports.createCampaignClick = onCall(async (request) =>
  handleCreateCampaignClick(request)
);
exports.campaignPostback = onRequest(
  { secrets: [campaignPostbackSecret] },
  async (req, res) => handleCampaignPostback(req, res)
);
exports.reviewCampaignConversion = onCall(async (request) =>
  handleReviewCampaignConversion(request)
);
if (typeof onSchedule === "function") {
  exports.releaseExpiredCampaignClickReservations = onSchedule(
    "every 15 minutes",
    async () => handleReleaseExpiredCampaignClickReservations()
  );
}
exports._campaignTrackingHandlers = {
  handleCreateCampaignClick,
  handleReleaseExpiredCampaignClickReservations
};
exports._campaignConversionHandlers = {
  handleCampaignPostback,
  handleReviewCampaignConversion,
  resolveCampaignNetworkSecret,
  extractNetworkIdFromPostbackRequest
};



