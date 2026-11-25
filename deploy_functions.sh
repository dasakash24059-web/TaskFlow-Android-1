#!/usr/bin/env bash
set -e

echo "=================================================="
echo "TaskFlow Safe Production Deployment to taskflow-8852d"
echo "=================================================="

# Check if firebase-tools is installed
if ! command -v firebase &> /dev/null; then
    echo "Firebase CLI not found. Installing firebase-tools..."
    npm install -g firebase-tools
fi

echo "Step 1: Logging in to Firebase with your Google Account..."
echo "Please sign in with: dasakash24059@gmail.com"
firebase login

echo "Step 2: Selecting project taskflow-8852d..."
firebase use taskflow-8852d

echo "Step 3: Installing dependencies in functions/..."
cd functions
npm install
cd ..

echo "Step 4: Deploying Firestore Composite Indexes first..."
firebase deploy --only firestore:indexes --project taskflow-8852d

echo "Step 5: Deploying all 36 Cloud Functions (including Phase F.3 proof upload endpoints)..."
firebase deploy --only functions --project taskflow-8852d

echo "Step 6: Deploying Firestore Rules and Storage Rules..."
firebase deploy --only firestore:rules,storage --project taskflow-8852d

echo "=================================================="
echo "Deployment Complete! Indexes, 36 Cloud Functions, Firestore Rules, and Storage Rules are LIVE!"
echo "=================================================="
