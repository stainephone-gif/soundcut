@echo off
rem Запуск ПК-версии SoundCut без Android Studio
chcp 65001 >nul
cd /d "%~dp0"
call gradlew.bat -PdesktopOnly :desktop:run
pause
