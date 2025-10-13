@echo off
setlocal

REM 支持传入数字参数直接执行（如：build.bat 5）
if not "%~1"=="" (
    set "OPT=%~1"
    goto handle
)

:menu
echo 请选择要执行的命令：
echo  1) gradlew.bat clean
echo  2) gradlew.bat build
echo  3) gradlew.bat test
echo  4) gradlew.bat installDebugAndLogcat
echo  5) gradlew.bat assembleDebug
echo  6) gradlew.bat installRelease
echo  7) gradlew.bat assembleRelease
CHOICE /C 1234567 /N /M "输入数字并回车： "
set "OPT=%ERRORLEVEL%"

:handle
if "%OPT%"=="1" goto do_clean
if "%OPT%"=="2" goto do_build
if "%OPT%"=="3" goto do_test
if "%OPT%"=="4" goto installDebugAndLogcat
if "%OPT%"=="5" goto do_assembleDebug
if "%OPT%"=="6" goto do_installRelease
if "%OPT%"=="7" goto do_assembleRelease

echo 无效输入：%OPT%
set "EXIT_CODE=1"
goto end

:do_clean
.\gradlew.bat clean
goto end

:do_build
.\gradlew.bat build
goto end

:do_test
.\gradlew.bat test
goto end

:do_installDebug
.\gradlew.bat installDebug
goto end

:do_assembleDebug
.\gradlew.bat assembleDebug
goto end

:do_installRelease
.\gradlew.bat installRelease
goto end

:do_assembleRelease
.\gradlew.bat assembleRelease
goto end

:end
REM 透传最后一条命令的退出码
set "EXIT_CODE=%ERRORLEVEL%"
endlocal & exit /b %EXIT_CODE%
