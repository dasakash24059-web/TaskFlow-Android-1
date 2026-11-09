const { onRequest } = require("firebase-functions/v2/https");
const { onCall } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const admin = require("firebase-admin");
const crypto = require("crypto");

const unitySecret = defineSecret("UNITY_ADS_SECRET");

admin.initializeApp();
const db = admin.firestore();

exports.createRewardSession = onCall({ secrets: [unitySecret] }, async (request) => {
  if (!request.auth) throw new Error("Unauthenticated");
  const userId = request.auth.uid;
  const { rewardType } = request.data;
  
  if (rewardType !== "WATCH_AND_EARN" && rewardType !== "DAILY_CHECK_IN") {
    throw new Error("Invalid reward type");
  }

  const sessionId = crypto.randomBytes(16).toString("hex");
  const expiresAt = admin.firestore.Timestamp.fromMillis(Date.now() + 10 * 60 * 1000); // 10 mins

  await db.collection("reward_sessions").document(sessionId).set({
    userId,
    rewardType,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    expiresAt,
    used: false
  });

  return { sessionId };
});

exports.unityAdsS2SCallback = onRequest({ secrets: [unitySecret] }, async (req, res) => {
  if (req.method !== "GET") return res.status(405).send("Method Not Allowed");

  const { hmac, oid, sid: userId, rewardPoints, sessionId } = req.query;
  const secret = unitySecret.value();

  if (!hmac || !oid || !userId || !rewardPoints || !sessionId) {
    return res.status(400).send("Missing parameters");
  }

  // HMAC-MD5 Verification
  const keys = Object.keys(req.query).filter(k => k !== 'hmac').sort();
  const paramString = keys.map(k => req.query[k]).join(',');
  const hmacObj = crypto.createHmac("md5", secret);
  hmacObj.update(paramString);
  const calculated = hmacObj.digest("hex");
  
  if (!crypto.timingSafeEqual(Buffer.from(hmac, 'hex'), Buffer.from(calculated, 'hex'))) {
    return res.status(401).send("Invalid signature");
  }

  try {
    await db.runTransaction(async (transaction) => {
      const txRef = db.collection("processed_transactions").document(oid);
      if ((await transaction.get(txRef)).exists) throw new Error("Duplicate transaction");

      const sessionRef = db.collection("reward_sessions").document(sessionId);
      const sessionDoc = await transaction.get(sessionRef);
      if (!sessionDoc.exists || sessionDoc.data().userId !== userId || sessionDoc.data().used || sessionDoc.data().expiresAt.toMillis() < Date.now()) {
        throw new Error("Invalid or expired session");
      }

      if (sessionDoc.data().rewardType === "WATCH_AND_EARN") {
        const today = new Date().toISOString().split("T")[0];
        const statsRef = db.collection("watch_stats").document(`${userId}_${today}`);
        const statsDoc = await transaction.get(statsRef);
        const data = statsDoc.exists ? statsDoc.data() : { videosWatched: 0, totalEarnedPoints: 0 };
        
        if (data.videosWatched >= 25 || data.totalEarnedPoints + parseInt(rewardPoints) > 40) {
          throw new Error("Daily limit exceeded");
        }
        transaction.set(statsRef, {
            totalEarnedPoints: data.totalEarnedPoints + parseInt(rewardPoints),
            videosWatched: admin.firestore.FieldValue.increment(1)
        }, { merge: true });
      }

      transaction.update(db.collection("users").document(userId), { virtualPoints: admin.firestore.FieldValue.increment(parseInt(rewardPoints)) });
      transaction.update(sessionRef, { used: true });
      transaction.set(txRef, { userId, transactionId: oid, rewardPoints: parseInt(rewardPoints), timestamp: admin.firestore.FieldValue.serverTimestamp() });
    });
    return res.status(200).send("OK");
  } catch (error) {
    if (["Duplicate transaction", "Invalid or expired session", "Daily limit exceeded"].includes(error.message)) return res.status(409).send(error.message);
    return res.status(500).send("Internal Server Error");
  }
});
