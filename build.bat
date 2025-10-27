@echo off
chcp 65001 >nul
title StopNet Build Tool
:: 保存原始目录
set "ORIGINAL_DIR=%CD%"

:menu
cls
echo ===================================
echo      StopNet Build Tool
echo ===================================
echo  1) gradlew.bat cleanSafe
echo  2) gradlew.bat build
echo  3) gradlew.bat test
echo  4) gradlew.bat installDebugAndLogcat
echo  5) gradlew.bat assembleDebug
echo  6) gradlew.bat installRelease
echo  7) gradlew.bat assembleRelease
echo  8) adb stop app
echo  9) adb -e emu kill
echo  0) exit
echo ===================================
:: 输入改为 set /p，仿照 ref.bat；任务完成后回到菜单
choice /c 0123456789 /n /m "输入数字并回车（0 退出）： "

if errorlevel 10 goto do_killEmulator
if errorlevel 9 goto do_stopApp
if errorlevel 8 goto do_assembleRelease
if errorlevel 7 goto do_installRelease
if errorlevel 6 goto do_assembleDebug
if errorlevel 5 goto do_installDebugAndLogcat
if errorlevel 4 goto do_test
if errorlevel 3 goto do_build
if errorlevel 2 goto do_clean
if errorlevel 1 goto end

goto menu

:do_clean
call .\gradlew.bat cleanSafe
goto done

:do_build
call .\gradlew.bat cleanSafe
call .\gradlew.bat build
goto done

:do_test
call .\gradlew.bat cleanSafe
call .\gradlew.bat build
call .\gradlew.bat test
goto done

:do_installDebugAndLogcat
call .\gradlew.bat cleanSafe
call .\gradlew.bat build
call .\gradlew.bat installDebugAndLogcat
goto done

:do_assembleDebug
call .\gradlew.bat cleanSafe
call .\gradlew.bat build
call .\gradlew.bat assembleDebug
goto done

:do_installRelease
call .\gradlew.bat cleanSafe
call .\gradlew.bat build
call .\gradlew.bat installRelease
goto done

:do_assembleRelease
call .\gradlew.bat cleanSafe
call .\gradlew.bat build
call .\gradlew.bat assembleRelease
goto done

:do_stopApp
adb shell am force-stop com.example.stopnet
goto done

:do_killEmulator
adb -e emu kill
goto done

:done
pause >nul
:: 回到菜单，清屏重画
goto menu

:end
endlocal & exit /b %ERRORLEVEL%