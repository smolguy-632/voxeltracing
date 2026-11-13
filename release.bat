@echo off
setlocal enabledelayedexpansion
rem ============================================================
rem  release.bat - bump version, commit ONLY the version bump,
rem  push, wait for CI, tag, wait for the release run, and
rem  download the jar to the Desktop.
rem
rem  Usage:
rem    double-click            -> auto-increments the version
rem    release.bat 1.3.0       -> releases exactly that version
rem
rem  Works for both repos (auto-detects the nested project).
rem ============================================================

cd /d "%~dp0"
set "PATH=%PATH%;C:\Program Files\GitHub CLI"

set "PROPS=gradle.properties"

rem ---- repo slug from the git remote ----
set "REPO="
for /f "delims=" %%u in ('git remote get-url origin') do set "REPO=%%u"
set "REPO=!REPO:https://github.com/=!"
set "REPO=!REPO:.git=!"
if "!REPO!"=="" (
    echo [X] No git remote found in this folder.
    pause & exit /b 1
)
echo Repo: !REPO!

rem ---- read current version ----
set "CUR="
for /f "usebackq delims=" %%l in ("%PROPS%") do (
    set "LINE=%%l"
    if "!LINE:~0,8!"=="version=" set "CUR=!LINE:~8!"
)
if "!CUR!"=="" (
    echo [X] Could not find a version= line in %PROPS%.
    pause & exit /b 1
)

rem ---- next version: from argument, or auto-increment patch ----
if not "%~1"=="" (
    set "NEXT=%~1"
) else (
    for /f "tokens=1-3 delims=." %%a in ("!CUR!") do set /a MAJ=%%a, MIN=%%b, PAT=%%c+1
    set "NEXT=!MAJ!.!MIN!.!PAT!"
)
echo Version: !CUR! -^> !NEXT!

rem ---- rewrite version= in gradle.properties ----
(for /f "usebackq delims=" %%l in ("%PROPS%") do (
    set "LINE=%%l"
    if "!LINE:~0,8!"=="version=" (echo version=!NEXT!) else (echo !LINE!)
)) > "%TEMP%\props.tmp"
move /y "%TEMP%\props.tmp" "%PROPS%" >nul

rem ---- commit + push (includes all changes made to the mod) ----
git add -A
git commit -m "Release v!NEXT!" >nul 2>&1 || echo Nothing new to commit besides version bump.
git push origin main
if errorlevel 1 ( echo [X] Push failed. & pause & exit /b 1 )

rem ---- watch the build run ----
echo Waiting for CI build...
timeout /t 8 /nobreak >nul
for /f "delims=" %%r in ('gh run list --repo !REPO! --limit 1 --json databaseId --jq ".[0].databaseId"') do set "RUN=%%r"
gh run watch !RUN! --repo !REPO! --exit-status --compact
if errorlevel 1 (
    echo [X] BUILD FAILED - fix the code, push again, then run this again.
    pause & exit /b 1
)

rem ---- tag + push ----
git tag "v!NEXT!" 2>nul
git push origin "v!NEXT!"
if errorlevel 1 ( echo [X] Tag push failed. & pause & exit /b 1 )

rem ---- watch the release run ----
echo Waiting for release run...
timeout /t 10 /nobreak >nul
for /f "delims=" %%r in ('gh run list --repo !REPO! --limit 1 --json databaseId --jq ".[0].databaseId"') do set "RUN=%%r"
gh run watch !RUN! --repo !REPO! --exit-status --compact
if errorlevel 1 (
    echo [X] RELEASE FAILED.
    pause & exit /b 1
)

rem ---- download the jar and zip it to the Desktop ----
timeout /t 3 /nobreak >nul
set "ASSET="
for /f "usebackq delims=" %%a in (`gh release view "v!NEXT!" --repo !REPO! --json assets --jq ".assets[0].name"`) do set "ASSET=%%a"
if "!ASSET!"=="" ( echo [X] No asset on the release. & pause & exit /b 1 )
curl -sL -o "%USERPROFILE%\Desktop\!ASSET!" "https://github.com/!REPO!/releases/download/v!NEXT!/!ASSET!"

echo.
echo ==============================================
echo  Released v!NEXT!
echo  Jar:  %USERPROFILE%\Desktop\!ASSET!
echo ==============================================
pause
