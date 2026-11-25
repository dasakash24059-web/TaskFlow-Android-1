@echo off
echo ==================================================
echo TaskFlow Safe Production Deployment to taskflow-8852d
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

echo Step 4: Deploying Firestore Composite Indexes first...
call firebase deploy --only firestore:indexes --project taskflow-8852d

echo Step 5: Deploying all 36 Cloud Functions (including Phase F.3 proof upload endpoints)...
call firebase deploy --only functions --project taskflow-8852d

echo Step 6: Deploying Firestore Rules and Storage Rules...
call firebase deploy --only firestore:rules,storage --project taskflow-8852d

echo ==================================================
echo Deployment Complete! Indexes, 36 Cloud Functions, Firestore Rules, and Storage Rules are LIVE!
echo ==================================================
pause
