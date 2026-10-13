@echo off
setlocal
if not exist "%~dp0src\Jm.java" goto installed
if not defined LOCALAPPDATA goto no_local_app_data
set "JM_CACHE=%LOCALAPPDATA%\VolunteerHub\jm"
if not exist "%JM_CACHE%" mkdir "%JM_CACHE%"
javac --release 11 -encoding UTF-8 -d "%JM_CACHE%" "%~dp0src\Jm.java"
if errorlevel 1 goto no_jdk
java -cp "%JM_CACHE%" Jm %*
exit /b %ERRORLEVEL%

:installed
java -jar "%~dp0jm.jar" %*
exit /b %ERRORLEVEL%

:no_local_app_data
echo jm: LOCALAPPDATA is not set.
exit /b 1

:no_jdk
echo jm: JDK 11 or newer is required; make sure javac is on PATH.
exit /b 1
