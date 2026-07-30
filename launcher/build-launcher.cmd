@echo off
setlocal
set "LAUNCHER_DIR=%~dp0"
set "CSC64=%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
set "CSC32=%WINDIR%\Microsoft.NET\Framework\v4.0.30319\csc.exe"
set "OUTPUT=%LAUNCHER_DIR%PsychCounselorLauncher.exe"

if exist "%CSC64%" goto compiler_ready
if exist "%CSC32%" goto compiler_32
echo [ERROR] .NET Framework csc.exe was not found.
exit /b 1

:compiler_32
set "CSC=%CSC32%"
goto compile

:compiler_ready
set "CSC=%CSC64%"

:compile
pushd "%LAUNCHER_DIR%"
echo [1/2] Compiling launcher...
"%CSC%" /nologo /target:winexe /optimize+ /codepage:65001 /utf8output ^
    /reference:System.dll ^
    /reference:System.Core.dll ^
    /reference:System.Drawing.dll ^
    /reference:System.Windows.Forms.dll ^
    /out:"%OUTPUT%" ^
    "src\PsychCounselorLauncher.cs"

if errorlevel 1 (
    echo [ERROR] Launcher compilation failed.
    popd
    exit /b 1
)

echo [2/2] Running static self-test...
start "" /wait "%OUTPUT%" --self-test
set "SELF_TEST_CODE=%ERRORLEVEL%"
if not "%SELF_TEST_CODE%"=="0" (
    echo [ERROR] Self-test failed. Exit code: %SELF_TEST_CODE%
    echo 10=root 11=compose 12=docker-cli 13=docker-desktop 14=browser 15=no-fallback-port 16=port-check 20=other
    popd
    exit /b %SELF_TEST_CODE%
)

echo [DONE] %OUTPUT%
popd
exit /b 0
