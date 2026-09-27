@echo off
rem Сборка ПК-версии SoundCut: папка с SoundCut.exe и установщик .msi
chcp 65001 >nul
cd /d "%~dp0"
call gradlew.bat -PdesktopOnly :desktop:createDistributable :desktop:packageMsi
echo.
echo Готово. Результат в папке desktop\build\compose\binaries\main
pause
