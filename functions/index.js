const crypto = require("crypto");

let onRequest, onCall, HttpsError, defineSecret, admin, db, unitySecret;
try {
  ({ onRequest, onCall, HttpsError } = require("firebase-functions/v2/https"));
  ({ defineSecret } = require("firebase-functions/params"));
  admin = require("firebase-admin");
  unitySecret = defineSecret("UNITY_ADS_SECRET");
  if (!admin.apps.length) {
    admin.initializeApp();
  }
  db = admin.firestore();
} catch (_) {
  // Fallback stubs when loaded in local Node test environment without functions/node_modules installed
  onRequest = (_opts, handler) => handler;
  onCall = (_opts, handler) => (typeof _opts === "function" ? _opts : handler);
  HttpsError = class HttpsError extends Error {
    constructor(code, message) {
      super(message);
      this.code = code;
    }
  };
  unitySecret = { value: () => process.env.UNITY_ADS_SECRET || "" };
}

const WATCH_EARN_REWARD_CENTS = 100; // ₹1.00 (100 paise) per verified video
const MAX_DAILY_VIDEOS = 25;
const MAX_DAILY_POINTS = 40;
const SESSION_TTL_MS = 10 * 60 * 1000; // 10 minutes

/**
 * Computes and verifies Unity Ads S2S HMAC-MD5 signature using constant-time comparison.
 * Official Unity S2S format sorts all query parameters (excluding 'hmac') alphabetically
 * and joins them as "key=value" pairs separated by commas.
 */
function verifyUnityS2SSignature(query, secretKey) {
  if (!query || !query.hmac || !secretKey) {
    return false;
  }
  const providedHmac = String(query.hmac).trim().toLowerCase();
  if (!/^[0-9a-f]{32}$/.test(providedHmac)) {
    return false;
  }

  const sortedKeys = Object.keys(query)
    .filter((k) => k !== "hmac")
    .sort();

  // Official Unity S2S key=value comma-separated parameter string
  const keyValueParamString = sortedKeys.map((k) => `${k}=${query[k]}`).join(",");
  const expectedKeyValueHmac = crypto
    .createHmac("md5", secretKey)
    .update(keyValueParamString)
    .digest("hex")
    .toLowerCase();

  const providedBuf = Buffer.from(providedHmac, "hex");
  const expectedKvBuf = Buffer.from(expectedKeyValueHmac, "hex");

  if (
    providedBuf.length === expectedKvBuf.length &&
    crypto.timingSafeEqual(providedBuf, expectedKvBuf)
  ) {
    return true;
  }

  return false;
}

/**
 * 1. Authenticated Callable Function: createRewardSession
 * Binds a unique, unpredictable sessionId to the authenticated Firebase user and rewardType.
 * The returned sessionId is passed to Unity Ads via PlayerMetaData.setServerId(sessionId)
 * so Unity S2S Callback receives sid=<sessionId>.
 */
exports.createRewardSession = onCall(async (request) => {
  if (!request.auth || !request.auth.uid) {
    throw new HttpsError("unauthenticated", "Authentication is required to create a reward session.");
  }

  const userId = request.auth.uid;
  const rewardType = request.data && request.data.rewardType;

  if (rewardType !== "WATCH_AND_EARN" && rewardType !== "DAILY_CHECK_IN") {
    throw new HttpsError("invalid-argument", "Unsupported rewardType.");
  }

  if (rewardType === "WATCH_AND_EARN") {
    const today = new Date().toISOString().split("T")[0];
    const statsSnap = await db.collection("watch_stats").doc(`${userId}_${today}`).get();
    if (statsSnap.exists) {
      const stats = statsSnap.data() || {};
      if ((stats.videosWatched || 0) >= MAX_DAILY_VIDEOS) {
        throw new HttpsError("resource-exhausted", "Daily limit of 25 videos reached.");
      }
    }
  }

  const sessionId = crypto.randomBytes(16).toString("hex");
  const nowMs = Date.now();
  const expiresAt = admin.firestore.Timestamp.fromMillis(nowMs + SESSION_TTL_MS);

  await db.collection("reward_sessions").doc(sessionId).set({
    sessionId,
    userId,
    rewardType,
    rewardCents: WATCH_EARN_REWARD_CENTS,
    status: "PENDING",
    s2sVerified: false,
    used: false,
    claimed: false,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    expiresAt
  });

  return {
    sessionId,
    expiresAtMillis: nowMs + SESSION_TTL_MS
  };
});

/**
 * 2. Unity Ads Official S2S Redeem Callback (HTTP GET)
 * Unity sends: ?oid=<order_id>&sid=<server_id>&...&hmac=<hmac_md5>
 * Because the Android client sets PlayerMetaData.setServerId(sessionId),
 * 'sid' contains the server-generated sessionId.
 */
exports.unityAdsS2SCallback = onRequest({ secrets: [unitySecret] }, async (req, res) => {
  if (req.method !== "GET") {
    return res.status(405).send("Method Not Allowed");
  }

  const { hmac, oid, sid } = req.query;
  const sessionId = req.query.sessionId || sid;
  const secret = unitySecret.value();

  if (!hmac || !oid || !sid || !sessionId) {
    return res.status(400).send("Missing required callback parameters");
  }

  if (!verifyUnityS2SSignature(req.query, secret)) {
    return res.status(401).send("Invalid HMAC-MD5 signature");
  }

  try {
    await db.runTransaction(async (transaction) => {
      const txRef = db.collection("processed_transactions").doc(String(oid));
      const existingTx = await transaction.get(txRef);
      if (existingTx.exists) {
        throw new Error("Duplicate transaction");
      }

      const sessionRef = db.collection("reward_sessions").doc(String(sessionId));
      const sessionDoc = await transaction.get(sessionRef);
      if (!sessionDoc.exists) {
        throw new Error("Invalid or expired session");
      }

      const sessionData = sessionDoc.data();
      if (
        sessionData.used ||
        sessionData.claimed ||
        !sessionData.expiresAt ||
        sessionData.expiresAt.toMillis() < Date.now()
      ) {
        throw new Error("Invalid or expired session");
      }

      // Mark session as S2S-verified so the user's Claim button can atomically claim the reward
      transaction.update(sessionRef, {
        s2sVerified: true,
        status: "VERIFIED",
        oid: String(oid),
        verifiedAt: admin.firestore.FieldValue.serverTimestamp()
      });

      transaction.set(txRef, {
        transactionId: String(oid),
        sessionId: String(sessionId),
        userId: sessionData.userId,
        rewardType: sessionData.rewardType,
        timestamp: admin.firestore.FieldValue.serverTimestamp()
      });
    });

    return res.status(200).send("1");
  } catch (error) {
    if (
      ["Duplicate transaction", "Invalid or expired session", "Daily limit exceeded"].includes(
        error.message
      )
    ) {
      return res.status(409).send(error.message);
    }
    return res.status(500).send("Internal Server Error");
  }
});

/**
 * 3. Authenticated Callable Function: claimRewardSession
 * Called when the user taps the "Claim" button after completing a rewarded video.
 * Atomically verifies the session belongs to request.auth.uid, is verified, unused, and unexpired,
 * enforces the 25-video / 40-point daily cap, credits wallet balance, and records the transaction.
 */
exports.claimRewardSession = onCall(async (request) => {
  if (!request.auth || !request.auth.uid) {
    throw new HttpsError("unauthenticated", "Authentication is required to claim a reward.");
  }

  const userId = request.auth.uid;
  const sessionId = request.data && request.data.sessionId;
  if (!sessionId || typeof sessionId !== "string") {
    throw new HttpsError("invalid-argument", "A valid sessionId is required.");
  }

  const today = new Date().toISOString().split("T")[0];

  const result = await db.runTransaction(async (transaction) => {
    const sessionRef = db.collection("reward_sessions").doc(sessionId);
    const sessionDoc = await transaction.get(sessionRef);

    if (!sessionDoc.exists) {
      throw new HttpsError("not-found", "Reward session not found.");
    }

    const session = sessionDoc.data();
    if (session.userId !== userId) {
      throw new HttpsError("permission-denied", "Session does not belong to the authenticated user.");
    }
    if (session.used || session.claimed || session.status === "CLAIMED") {
      throw new HttpsError("already-exists", "Reward has already been claimed for this session.");
    }
    if (!session.expiresAt || session.expiresAt.toMillis() < Date.now()) {
      throw new HttpsError("deadline-exceeded", "Reward session has expired.");
    }
    if (session.status !== "VERIFIED" && !session.s2sVerified) {
      throw new HttpsError("failed-precondition", "Reward video completion has not been verified yet.");
    }

    const userRef = db.collection("users").doc(userId);
    const userDoc = await transaction.get(userRef);
    if (!userDoc.exists) {
      throw new HttpsError("not-found", "User profile not found.");
    }

    const userData = userDoc.data() || {};
    const currentBalanceCents = Number(userData.balanceCents || 0);
    const rewardCents = Number(session.rewardCents || WATCH_EARN_REWARD_CENTS);
    const rewardPoints = Math.round(rewardCents / 100);

    let newVideosWatched = 0;
    let newTotalEarnedCents = 0;

    if (session.rewardType === "WATCH_AND_EARN") {
      const statsRef = db.collection("watch_stats").doc(`${userId}_${today}`);
      const statsDoc = await transaction.get(statsRef);
      const statsData = statsDoc.exists
        ? statsDoc.data()
        : { videosWatched: 0, totalEarnedCents: 0, totalEarnedPoints: 0 };

      const currentVideos = Number(statsData.videosWatched || 0);
      const currentPoints = Number(
        statsData.totalEarnedPoints !== undefined
          ? statsData.totalEarnedPoints
          : Math.round(Number(statsData.totalEarnedCents || 0) / 100)
      );

      if (currentVideos >= MAX_DAILY_VIDEOS || currentPoints + rewardPoints > MAX_DAILY_POINTS) {
        throw new HttpsError("resource-exhausted", "Daily Watch & Earn limit reached (25/25).");
      }

      newVideosWatched = currentVideos + 1;
      newTotalEarnedCents = Number(statsData.totalEarnedCents || 0) + rewardCents;

      transaction.set(
        statsRef,
        {
          userId,
          dateKey: today,
          videosWatched: newVideosWatched,
          totalEarnedCents: newTotalEarnedCents,
          totalEarnedPoints: currentPoints + rewardPoints,
          updatedAt: admin.firestore.FieldValue.serverTimestamp()
        },
        { merge: true }
      );
    }

    const newBalanceCents = currentBalanceCents + rewardCents;

    transaction.update(sessionRef, {
      used: true,
      claimed: true,
      status: "CLAIMED",
      claimedAt: admin.firestore.FieldValue.serverTimestamp()
    });

    transaction.update(userRef, {
      balanceCents: newBalanceCents,
      virtualPoints: admin.firestore.FieldValue.increment(rewardPoints),
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });

    const txRecordRef = db.collection("transactions").doc();
    transaction.set(txRecordRef, {
      userId,
      userName: userData.displayName || "Verified Member",
      type: "WATCH_EARN",
      title: `Watch & Earn Video Reward (${newVideosWatched}/${MAX_DAILY_VIDEOS})`,
      amountCents: rewardCents,
      status: "CREDITED",
      referenceId: sessionId,
      createdAt: admin.firestore.FieldValue.serverTimestamp()
    });

    return {
      claimed: true,
      rewardCents,
      newBalanceCents,
      videosWatched: newVideosWatched
    };
  });

  return result;
});

exports._verifyUnityS2SSignature = verifyUnityS2SSignature;
