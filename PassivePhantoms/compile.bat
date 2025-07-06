@echo off
setlocal

set JAR_VERSION=1.2.3
set JAR_NAME=PassivePhantoms-%JAR_VERSION%.jar
set TARGET_DIR=target
set MAVEN_PATH=%USERPROFILE%\maven\apache-maven-3.9.6\bin\mvn.cmd

REM Try to use system Maven if available
where mvn >nul 2>nul
if %errorlevel%==0 (
    set MVN_CMD=mvn
) else if exist "%MAVEN_PATH%" (
    set MVN_CMD="%MAVEN_PATH%"
) else (
    echo ERROR: Maven not found in PATH or at %MAVEN_PATH%
    echo Please install Maven or update the script with the correct path.
    pause
    exit /b 1
)

echo Compiling PassivePhantoms with Maven...

REM Clean and compile
%MVN_CMD% clean
%MVN_CMD% package

if exist %TARGET_DIR%\%JAR_NAME% (
    echo.
    echo SUCCESS! Plugin compiled successfully!
    echo Your plugin JAR is at: %TARGET_DIR%\%JAR_NAME%
    echo.
    echo To install: Copy the JAR to your server's plugins folder
    echo.
) else (
    echo.
    echo ERROR: Compilation failed or JAR not found!
    echo.
)

pause
endlocal 
