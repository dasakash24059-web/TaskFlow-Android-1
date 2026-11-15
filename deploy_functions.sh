#!/usr/bin/env bash
set -e

echo "=================================================="
echo "TaskFlow Cloud Functions Deployment to taskflow-8852d"
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

echo "Step 4: Deploying Cloud Functions and Firestore Rules..."
firebase deploy --only functions,firestore:rules --project taskflow-8852d

echo "=================================================="
echo "Deployment Complete! All 19 Cloud Functions are LIVE!"
echo "=================================================="
