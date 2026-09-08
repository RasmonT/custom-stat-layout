@echo off
setlocal enabledelayedexpansion
REM ---------------------------------------------------------------------------
REM  Custom Stat Layout - launches RuneLite with this plugin compiled in.
REM
REM  The first run downloads Gradle and the RuneLite client. That is a few
REM  hundred megabytes and takes a few minutes; every run after it starts in
REM  seconds. Your normal RuneLite settings and Plugin Hub plugins are all
REM  still there - this is the same client, just with one extra plugin.
REM
REM  JAGEX ACCOUNT: a client started this way cannot log in through the Jagex
REM  Launcher. RuneLite's official way around that is in README.md under
REM  "Jagex account" - do that once, before the first run.
REM ---------------------------------------------------------------------------
cd /d "%~dp0"

REM Gradle needs a JDK; RuneLite's own bundled runtime is a JRE and cannot compile.
where javac >nul 2>&1 && goto haveJdk
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" goto haveJdk

REM Not on PATH - look where the usual installers put it before giving up.
for /D %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-*") do if exist "%%~D\bin\javac.exe" set "JAVA_HOME=%%~D"
for /D %%D in ("%ProgramFiles%\Microsoft\jdk-*") do if exist "%%~D\bin\javac.exe" set "JAVA_HOME=%%~D"
for /D %%D in ("%ProgramFiles%\Java\jdk-*") do if exist "%%~D\bin\javac.exe" set "JAVA_HOME=%%~D"
for /D %%D in ("%ProgramFiles%\Amazon Corretto\jdk*") do if exist "%%~D\bin\javac.exe" set "JAVA_HOME=%%~D"
if defined JAVA_HOME if exist "!JAVA_HOME!\bin\javac.exe" (
  echo Using JDK at !JAVA_HOME!
  goto haveJdk
)

echo.
echo   No JDK found on this machine.
echo   Gradle needs javac to build the plugin - a JRE is not enough.
echo.
echo   Install one with:
echo.
echo       winget install EclipseAdoptium.Temurin.21.JDK
echo.
echo   then CLOSE this window, open a new one, and run RUN.bat again.
echo   (A new window is needed so it picks up the changed PATH.)
echo.
pause
exit /b 1
:haveJdk

call gradlew.bat run
pause
