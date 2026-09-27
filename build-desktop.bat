@echo off
rem Сборка ПК-версии SoundCut: папка с SoundCut.exe и установщик .msi
chcp 65001 >nul
cd /d "%~dp0"
set "JAVA_EXE=java"
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java"
set "JMAJ="
for /f "tokens=3" %%v in ('"%JAVA_EXE%" -version 2^>^&1 ^| findstr /i "version"') do set "JV=%%~v"
for /f "delims=." %%m in ("%JV%") do set "JMAJ=%%m"
if not defined JMAJ (
  echo Java не найдена. Установите JDK 21: https://adoptium.net/temurin/releases/?version=21
  echo При установке включите пункт "Set JAVA_HOME variable".
  pause
  exit /b 1
)
if %JMAJ% GTR 23 (
  echo Установлена Java %JV%, а для сборки нужна Java 17-23.
  echo Установите JDK 21: https://adoptium.net/temurin/releases/?version=21
  echo При установке включите пункт "Set JAVA_HOME variable", затем откройте новое окно командной строки.
  pause
  exit /b 1
)
if %JMAJ% LSS 17 (
  echo Установлена Java %JV%, а нужна Java 17-23. Установите JDK 21: https://adoptium.net/temurin/releases/?version=21
  pause
  exit /b 1
)
call gradlew.bat -PdesktopOnly :desktop:createDistributable :desktop:packageMsi
echo.
echo Готово. Результат в папке desktop\build\compose\binaries\main
pause
