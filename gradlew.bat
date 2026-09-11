@echo off
setlocal enabledelayedexpansion
set "PROJECT_DIR=%~dp0"
set "GRADLE_VERSION=8.9"
set "BOOT=%USERPROFILE%\.gradle\sc2-tmg-bootstrap"
set "GRADLE_HOME=%BOOT%\gradle-%GRADLE_VERSION%"
set "GRADLE_EXE=%GRADLE_HOME%\bin\gradle.bat"

if not exist "%GRADLE_EXE%" (
  echo [SC2 TMG] First Gradle run - fetching Gradle %GRADLE_VERSION%...
  if not exist "%BOOT%" mkdir "%BOOT%"
  set "ZIP=%BOOT%\gradle-%GRADLE_VERSION%-bin.zip"
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -UseBasicParsing 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' -OutFile '%BOOT%\gradle-8.9-bin.zip'; Expand-Archive -Path '%BOOT%\gradle-8.9-bin.zip' -DestinationPath '%BOOT%' -Force"
  if errorlevel 1 exit /b 1
)

call "%GRADLE_EXE%" %*
exit /b %ERRORLEVEL%
