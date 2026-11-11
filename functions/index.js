const crypto = require("crypto");

let onRequest;
let onCall;
let HttpsError;
let defineSecret;
let admin;
let db;

const databaseId =
  process.env.FIRESTORE_DATABASE_ID ||
  "ai-studio-android-taskflow-f47fe501-b47e-4e0e-8222-905e3d5397ff";

try {
  ({ onRequest, onCall, HttpsError } = require("firebase-functions/v2/https"));
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

const DAILY_VIDEO_LIMIT = 25;
const DAILY_EARN_LIMIT_CENTS = 4000;
const WATCH_EARN_REWARD_CENTS = Math.floor(Math.random() * (100 - 50 + 1)) + 50;

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
 * Calculates the server-authoritative reward in paise for a 7-day Daily Check-in streak:
 * - Days 1 to 6: 100 paise (₹1.00)
 * - Day 7: 500 paise (₹5.00)
 */
function getDailyCheckInRewardCents(streakDay) {
  const day = Number(streakDay);
  if (!Number.isInteger(day) || day < 1 || day > 7) {
    throw new HttpsError(
      "invalid-argument",
      "Invalid streakDay. Must be an integer between 1 and 7."
    );
  }
  return day === 7 ? 500 : 100;
}

/**
 * Calculates the server-authoritative Daily Check-in eligibility and next streak day (1..7).
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
    const activeDay = rawStreak >= 1 && rawStreak <= 7 ? rawStreak : 1;
    const nextDay = activeDay >= 7 ? 1 : activeDay + 1;
    return {
      canCheckInToday: false,
      currentStreak: activeDay,
      expectedStreakDay: nextDay,
      rewardCents: getDailyCheckInRewardCents(nextDay),
      today,
      yesterday
    };
  }

  if (cleanLastDate === yesterday) {
    const nextStreakDay = rawStreak >= 1 && rawStreak < 7 ? rawStreak + 1 : 1;
    return {
      canCheckInToday: true,
      currentStreak: rawStreak,
      expectedStreakDay: nextStreakDay,
      rewardCents: getDailyCheckInRewardCents(nextStreakDay),
      today,
      yesterday
    };
  }

  return {
    canCheckInToday: true,
    currentStreak: 0,
    expectedStreakDay: 1,
    rewardCents: getDailyCheckInRewardCents(1),
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

/**
 * 1. CREATE REWARD SESSION (Callable Function)
 * Called by the Android client BEFORE showing a Unity Rewarded Ad.
 * Enforces user approval status, daily limits, and Daily Check-in eligibility on the server.
 */
exports.createRewardSession = onCall(async (request) => {
  if (!request.auth || !request.auth.uid) {
    throw new HttpsError("unauthenticated", "User must be logged in.");
  }

  const userId = request.auth.uid;
  const rewardType = request.data?.rewardType || "WATCH_AND_EARN";

  if (!["WATCH_AND_EARN", "DAILY_CHECK_IN"].includes(rewardType)) {
    throw new HttpsError("invalid-argument", "Invalid reward type.");
  }

  const userRef = db.collection("users").doc(userId);
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

  const now = new Date();
  const today = getTodayDateString(now);
  const yesterday = getYesterdayDateString(now);

  let rewardCents = WATCH_EARN_REWARD_CENTS;
  let validatedStreakDay = null;

  if (rewardType === "WATCH_AND_EARN") {
    const statId = `${userId}_${today}`;
    const [topStatSnap, subStatSnap] = await Promise.all([
      db.collection("watch_stats").doc(statId).get(),
      userRef.collection("watch_stats").doc(today).get()
    ]);

    const currentVideos = Math.max(
      topStatSnap.exists ? topStatSnap.data().videosWatched || 0 : 0,
      subStatSnap.exists ? subStatSnap.data().videosWatched || 0 : 0
    );

    if (currentVideos >= DAILY_VIDEO_LIMIT) {
      throw new HttpsError(
        "resource-exhausted",
        "Daily limit of 25 watched videos reached."
      );
    }
    rewardCents = WATCH_EARN_REWARD_CENTS;
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
    rewardCents = checkInState.rewardCents; // Days 1-6 = 100 paise, Day 7 = 500 paise
  }

  const sessionId = crypto.randomBytes(16).toString("hex");
  const nowMillis = Date.now();
  const expiresAt = admin.firestore.Timestamp.fromMillis(nowMillis + 10 * 60 * 1000);

  const sessionPayload = {
    sessionId,
    userId,
    rewardType,
    rewardCents,
    dateKey: today,
    status: "PENDING",
    s2sVerified: rewardType === "DAILY_CHECK_IN", // Daily check-in does not require ad S2S
    used: false,
    claimed: false,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    expiresAt
  };

  if (validatedStreakDay !== null) {
    sessionPayload.streakDay = validatedStreakDay;
  }

  await db.collection("reward_sessions").doc(sessionId).set(sessionPayload);

  return {
    sessionId,
    rewardType,
    rewardCents,
    streakDay: validatedStreakDay,
    dateKey: today
  };
});

/**
 * 2. UNITY ADS SERVER-TO-SERVER (S2S) CALLBACK (HTTP Endpoint)
 * Triggered by Unity Ads servers when a user finishes watching a rewarded ad.
 * Strictly requires UNITY_S2S_SECRET and a valid HMAC-MD5 signature.
 * Marks the session as VERIFIED (`s2sVerified = true`) so the user can claim it.
 */
exports.unityAdsS2SCallback = onRequest(
  { secrets: [unityS2SSecret, unityAdsSecret] },
  async (req, res) => {
    try {
      if (req.method !== "GET" && req.method !== "POST") {
        return res.status(405).send("Method Not Allowed");
      }

      const secret = resolveUnityS2SSecret();
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

      const sessionId = String(sid).trim();
      const orderId = String(oid).trim();
      const sessionRef = db.collection("reward_sessions").doc(sessionId);
      const callbackRef = db.collection("processed_unity_callbacks").doc(orderId);

      await db.runTransaction(async (transaction) => {
        const callbackDoc = await transaction.get(callbackRef);
        if (callbackDoc.exists) {
          throw new Error("Duplicate transaction");
        }

        const sessionDoc = await transaction.get(sessionRef);
        if (!sessionDoc.exists) {
          throw new Error("Session not found");
        }

        const session = sessionDoc.data();
        if (session.used || session.claimed || session.status === "CLAIMED") {
          throw new Error("Session already used");
        }

        if (session.expiresAt && session.expiresAt.toMillis() < Date.now()) {
          transaction.update(sessionRef, { status: "EXPIRED" });
          throw new Error("Session expired");
        }

        transaction.set(callbackRef, {
          oid: orderId,
          sessionId,
          userId: session.userId,
          processedAt: admin.firestore.FieldValue.serverTimestamp()
        });

        if (session.status === "PENDING") {
          transaction.update(sessionRef, {
            s2sVerified: true,
            status: "VERIFIED",
            unityOrderId: orderId,
            verifiedAt: admin.firestore.FieldValue.serverTimestamp()
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
);

/**
 * 3. CLAIM REWARD SESSION (Callable Function)
 * Called by the Android client when claiming a session.
 * For WATCH_AND_EARN: strictly requires session.s2sVerified === true && session.status === "VERIFIED".
 * For DAILY_CHECK_IN: allows direct claiming without an advertising S2S callback.
 */
exports.claimRewardSession = onCall(async (request) => {
  if (!request.auth || !request.auth.uid) {
    throw new HttpsError("unauthenticated", "User must be logged in.");
  }

  const userId = request.auth.uid;
  const sessionId = (request.data?.sessionId || "").trim();

  if (!sessionId) {
    throw new HttpsError("invalid-argument", "Session ID is required.");
  }

  const sessionRef = db.collection("reward_sessions").doc(sessionId);
  const userRef = db.collection("users").doc(userId);
  const leaderboardRef = db.collection("leaderboard").doc(userId);

  const now = new Date();
  const today = getTodayDateString(now);
  const yesterday = getYesterdayDateString(now);

  return await db.runTransaction(async (transaction) => {
    const sessionDoc = await transaction.get(sessionRef);
    if (!sessionDoc.exists) {
      throw new HttpsError("not-found", "Invalid reward session.");
    }

    const session = sessionDoc.data();
    if (session.userId !== userId) {
      throw new HttpsError("permission-denied", "Session belongs to another user.");
    }

    if (session.used || session.claimed || session.status === "CLAIMED") {
      throw new HttpsError(
        "already-exists",
        "Reward has already been claimed for this session."
      );
    }

    if (!session.expiresAt || session.expiresAt.toMillis() < Date.now()) {
      throw new HttpsError("deadline-exceeded", "Reward session has expired.");
    }

    // WATCH_AND_EARN strictly requires genuine Unity S2S verification
    if (
      session.rewardType === "WATCH_AND_EARN" &&
      (session.s2sVerified !== true || session.status !== "VERIFIED")
    ) {
      throw new HttpsError(
        "failed-precondition",
        "Reward video completion has not been verified by server yet."
      );
    }

    const userDoc = await transaction.get(userRef);
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

    const currentBalanceCents = Number(userData.balanceCents || 0);
    const completedTasksCount = Number(userData.completedTasksCount || 0);
    const displayName = (userData.displayName || "TaskFlow Member").trim() || "TaskFlow Member";

    if (session.rewardType === "DAILY_CHECK_IN") {
      const checkInDocRef = userRef.collection("check_ins").doc(today);
      const checkInDoc = await transaction.get(checkInDocRef);

      const checkInState = calculateServerDailyCheckInState(
        userData.lastCheckInDate || "",
        userData.checkInStreak || 0,
        today,
        yesterday
      );

      if (checkInDoc.exists || !checkInState.canCheckInToday) {
        throw new HttpsError(
          "already-exists",
          "You have already claimed today's daily check-in reward."
        );
      }

      const streakDay = checkInState.expectedStreakDay;
      const rewardCents = getDailyCheckInRewardCents(streakDay); // Days 1-6 = 100, Day 7 = 500
      const newBalanceCents = currentBalanceCents + rewardCents;

      // 1. Mark session as CLAIMED & single-use
      transaction.update(sessionRef, {
        status: "CLAIMED",
        used: true,
        claimed: true,
        claimedAt: admin.firestore.FieldValue.serverTimestamp()
      });

      // 2. Create daily check-in record in /users/{userId}/check_ins/{today}
      transaction.set(checkInDocRef, {
        userId,
        dateKey: today,
        streakDay,
        rewardCents,
        sessionId,
        claimedAt: admin.firestore.FieldValue.serverTimestamp()
      });

      // 3. Atomically update user balance and streak
      transaction.update(userRef, {
        balanceCents: admin.firestore.FieldValue.increment(rewardCents),
        checkInStreak: streakDay,
        lastCheckInDate: today,
        lastCheckInTimestamp: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      });

      // 4. Atomically sync public leaderboard projection
      transaction.set(leaderboardRef, {
        userId,
        displayName,
        balanceCents: newBalanceCents,
        completedTasksCount,
        approvalStatus: "approved",
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      });

      // 5. Record transaction history entry
      const txRef = db.collection("transactions").doc();
      transaction.set(txRef, {
        userId,
        userName: displayName,
        type: "DAILY_CHECKIN",
        title: `Daily Check-in Reward (Day ${streakDay}/7)`,
        amountCents: rewardCents,
        status: "CREDITED",
        referenceId: today,
        createdAt: admin.firestore.FieldValue.serverTimestamp()
      });

      return {
        success: true,
        rewardType: "DAILY_CHECK_IN",
        rewardCents,
        streakDay,
        newBalanceCents,
        dateKey: today
      };
    }

    // WATCH AND EARN flow
    const topStatsRef = db.collection("watch_stats").doc(`${userId}_${today}`);
    const subStatsRef = userRef.collection("watch_stats").doc(today);

    const [topStatDoc, subStatDoc] = await Promise.all([
      transaction.get(topStatsRef),
      transaction.get(subStatsRef)
    ]);

    const topWatched = topStatDoc.exists ? topStatDoc.data().videosWatched || 0 : 0;
    const subWatched = subStatDoc.exists ? subStatDoc.data().videosWatched || 0 : 0;
    const currentVideos = Math.max(topWatched, subWatched);

    const topEarned = topStatDoc.exists ? topStatDoc.data().totalEarnedCents || 0 : 0;
    const subEarned = subStatDoc.exists ? subStatDoc.data().totalEarnedCents || 0 : 0;
    const currentEarned = Math.max(topEarned, subEarned);

    if (currentVideos >= DAILY_VIDEO_LIMIT) {
      throw new HttpsError(
        "resource-exhausted",
        `Daily limit of ${DAILY_VIDEO_LIMIT} videos reached.`
      );
    }

    const rewardCents = Number(session.rewardCents || WATCH_EARN_REWARD_CENTS);
    if (currentEarned + rewardCents > DAILY_EARN_LIMIT_CENTS) {
      throw new HttpsError(
        "resource-exhausted",
        "Daily earnings limit exceeded."
      );
    }

    const newVideos = currentVideos + 1;
    const newEarnedCents = currentEarned + rewardCents;
    const newBalanceCents = currentBalanceCents + rewardCents;

    // 1. Mark session as CLAIMED & single-use
    transaction.update(sessionRef, {
      status: "CLAIMED",
      used: true,
      claimed: true,
      claimedAt: admin.firestore.FieldValue.serverTimestamp()
    });

    const statsPayload = {
      userId,
      dateKey: today,
      videosWatched: newVideos,
      totalEarnedCents: newEarnedCents,
      totalEarnedPoints: newVideos,
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    };

    // 2. Update both /watch_stats/{uid}_{today} and /users/{uid}/watch_stats/{today}
    transaction.set(topStatsRef, statsPayload, { merge: true });
    transaction.set(subStatsRef, statsPayload, { merge: true });

    // 3. Credit user wallet balance
    transaction.update(userRef, {
      balanceCents: admin.firestore.FieldValue.increment(rewardCents),
      virtualPoints: admin.firestore.FieldValue.increment(1),
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });

    // 4. Sync public leaderboard projection (strictly no email)
    transaction.set(leaderboardRef, {
      userId,
      displayName,
      balanceCents: newBalanceCents,
      completedTasksCount,
      approvalStatus: "approved",
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });

    // 5. Record transaction history entry
    const txRef = db.collection("transactions").doc();
    transaction.set(txRef, {
      userId,
      userName: displayName,
      type: "WATCH_EARN",
      title: `Watch & Earn Video Reward (${newVideos}/${DAILY_VIDEO_LIMIT})`,
      amountCents: rewardCents,
      status: "CREDITED",
      referenceId: sessionId,
      createdAt: admin.firestore.FieldValue.serverTimestamp()
    });

    return {
      success: true,
      rewardType: "WATCH_AND_EARN",
      rewardCents,
      videosWatched: newVideos,
      totalEarnedCents: newEarnedCents,
      newBalanceCents,
      dateKey: today
    };
  });
});

/**
 * 4. DIRECT DAILY CHECK-IN (Callable Function)
 * Secure server-side daily check-in that does NOT require an advertising S2S callback.
 * Validates once per local calendar day (Asia/Kolkata), verifies approved status,
 * computes streak progression (Days 1-6 = 100 paise, Day 7 = 500 paise), and atomically
 * updates checkInStreak, lastCheckInDate, wallet balance, leaderboard, and transaction ledger.
 */
exports.claimDailyCheckIn = onCall(async (request) => {
  if (!request.auth || !request.auth.uid) {
    throw new HttpsError("unauthenticated", "User must be logged in.");
  }

  const userId = request.auth.uid;
  const userRef = db.collection("users").doc(userId);
  const leaderboardRef = db.collection("leaderboard").doc(userId);

  const now = new Date();
  const today = getTodayDateString(now);
  const yesterday = getYesterdayDateString(now);

  return await db.runTransaction(async (transaction) => {
    const userDoc = await transaction.get(userRef);
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

    const checkInDocRef = userRef.collection("check_ins").doc(today);
    const checkInDoc = await transaction.get(checkInDocRef);

    const checkInState = calculateServerDailyCheckInState(
      userData.lastCheckInDate || "",
      userData.checkInStreak || 0,
      today,
      yesterday
    );

    if (checkInDoc.exists || !checkInState.canCheckInToday) {
      throw new HttpsError(
        "already-exists",
        "You have already claimed today's daily check-in reward."
      );
    }

    const streakDay = checkInState.expectedStreakDay;
    const rewardCents = getDailyCheckInRewardCents(streakDay);
    const currentBalanceCents = Number(userData.balanceCents || 0);
    const completedTasksCount = Number(userData.completedTasksCount || 0);
    const displayName = (userData.displayName || "TaskFlow Member").trim() || "TaskFlow Member";
    const newBalanceCents = currentBalanceCents + rewardCents;

    // 1. Record daily check-in in /users/{uid}/check_ins/{today}
    transaction.set(checkInDocRef, {
      userId,
      dateKey: today,
      streakDay,
      rewardCents,
      claimedAt: admin.firestore.FieldValue.serverTimestamp()
    });

    // 2. Atomically update user profile
    transaction.update(userRef, {
      balanceCents: admin.firestore.FieldValue.increment(rewardCents),
      checkInStreak: streakDay,
      lastCheckInDate: today,
      lastCheckInTimestamp: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });

    // 3. Atomically sync public leaderboard projection
    transaction.set(leaderboardRef, {
      userId,
      displayName,
      balanceCents: newBalanceCents,
      completedTasksCount,
      approvalStatus: "approved",
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });

    // 4. Record transaction history entry
    const txRef = db.collection("transactions").doc();
    transaction.set(txRef, {
      userId,
      userName: displayName,
      type: "DAILY_CHECKIN",
      title: `Daily Check-in Reward (Day ${streakDay}/7)`,
      amountCents: rewardCents,
      status: "CREDITED",
      referenceId: today,
      createdAt: admin.firestore.FieldValue.serverTimestamp()
    });

    // 5. In-app notification
    const notifRef = userRef.collection("notifications").doc();
    transaction.set(notifRef, {
      userId,
      title: "Daily Check-in Claimed! 🎉",
      message: `You received ₹${(rewardCents / 100).toFixed(2)} for Day ${streakDay} streak check-in!`,
      type: "DAILY_CHECKIN",
      referenceId: today,
      isRead: false,
      createdAt: admin.firestore.FieldValue.serverTimestamp()
    });

    return {
      success: true,
      streakDay,
      rewardCents,
      newBalanceCents,
      dateKey: today
    };
  });
});

exports._verifyUnityS2SSignature = verifyUnityS2SSignature;
exports._getDailyCheckInRewardCents = getDailyCheckInRewardCents;
exports._calculateServerDailyCheckInState = calculateServerDailyCheckInState;
exports._resolveUnityS2SSecret = resolveUnityS2SSecret;
exports._getTodayDateString = getTodayDateString;
exports._getYesterdayDateString = getYesterdayDateString;
