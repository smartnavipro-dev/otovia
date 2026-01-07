@echo off
REM v1.1.7 Testing Script for Kindle TTS Reader
REM Tests bitmap leak fixes and memory monitoring

echo ============================================
echo Kindle TTS Reader v1.1.7 Test Script
echo ============================================
echo.

REM Check if device is connected
echo [1/7] Checking device connection...
adb devices | findstr /C:"device" >nul 2>&1
if errorlevel 1 (
    echo ERROR: No Android device connected
    echo Please connect your device via USB and enable USB debugging
    pause
    exit /b 1
)
echo ✓ Device connected
echo.

REM Clear logcat
echo [2/7] Clearing logcat...
adb logcat -c
echo ✓ Logcat cleared
echo.

REM Build and install APK
echo [3/7] Building release APK...
call gradlew.bat assembleRelease
if errorlevel 1 (
    echo ERROR: Build failed
    pause
    exit /b 1
)
echo ✓ Build successful
echo.

echo [4/7] Installing APK...
adb install -r app\build\outputs\apk\release\app-release.apk
if errorlevel 1 (
    echo ERROR: Installation failed
    pause
    exit /b 1
)
echo ✓ APK installed
echo.

REM Start the app
echo [5/7] Launching Kindle TTS Reader...
adb shell am start -n com.kindletts.reader/.MainActivity
timeout /t 3 >nul
echo ✓ App launched
echo.

REM Start logcat monitoring
echo [6/7] Starting logcat monitoring...
echo.
echo ============================================
echo MONITORING LOGS (Press Ctrl+C to stop)
echo ============================================
echo.
echo Key indicators to watch for:
echo  - [v1.1.7] Bitmap recycled
echo  - [v1.1.7] Memory
echo  - [v1.1.7] Similarity calculation
echo  - ⚠️ High memory usage
echo.

REM Create log file with timestamp
set timestamp=%date:~-4%%date:~4,2%%date:~7,2%_%time:~0,2%%time:~3,2%%time:~6,2%
set timestamp=%timestamp: =0%
set logfile=test_logs\v1.1.7_test_%timestamp%.txt

REM Create log directory if it doesn't exist
if not exist "test_logs" mkdir test_logs

echo [7/7] Logging to: %logfile%
echo.

REM Monitor logcat with filtering
adb logcat -s "KindleTTS_OverlayService:D" "KindleTTS_MainActivity:D" "KindleTTS_Service:D" | tee %logfile%
