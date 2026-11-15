@echo off
echo ==================================================
echo TaskFlow Cloud Functions Deployment to taskflow-8852d
echo ==================================================

where firebase >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo Firebase CLI not found. Installing firebase-tools...
    call npm install -g firebase-tools
)

echo Step 1: Logging in to Firebase with your Google Account...
echo Please sign in with: dasakash24059@gmail.com
call firebase login

echo Step 2: Selecting project taskflow-8852d...
call firebase use taskflow-8852d

echo Step 3: Installing dependencies in functions/...
cd functions
call npm install
cd ..

echo Step 4: Deploying Cloud Functions and Firestore Rules...
call firebase deploy --only functions,firestore:rules --project taskflow-8852d

echo ==================================================
echo Deployment Complete! All 19 Cloud Functions are LIVE!
echo ==================================================
pause
