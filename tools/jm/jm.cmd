@echo off
setlocal
if not exist "%~dp0src\Jm.java" goto installed
if not defined TEMP goto no_temp
set "JM_CACHE=%TEMP%\VolunteerHub-jm-%RANDOM%-%RANDOM%"
mkdir "%JM_CACHE%" >nul 2>&1
if errorlevel 1 goto no_cache
javac --release 11 -encoding UTF-8 -d "%JM_CACHE%" "%~dp0src\Jm.java"
if errorlevel 1 goto no_jdk
java -cp "%JM_CACHE%" Jm %*
set "JM_EXIT=%ERRORLEVEL%"
rmdir /s /q "%JM_CACHE%" >nul 2>&1
exit /b %JM_EXIT%

:installed
java -jar "%~dp0jm.jar" %*
exit /b %ERRORLEVEL%

:no_temp
echo jm: TEMP is not set.
exit /b 1

:no_cache
echo jm: Could not create a temporary launcher directory under "%TEMP%".
exit /b 1

:no_jdk
rmdir /s /q "%JM_CACHE%" >nul 2>&1
echo jm: JDK 11 or newer is required; make sure javac is on PATH.
exit /b 1
