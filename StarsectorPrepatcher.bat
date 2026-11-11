@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0"

for /f "usebackq delims=" %%v in (`powershell.exe -NoProfile -Command "(Get-Content 'mod_info.json' | ConvertFrom-Json).version"`) do set "VERSION=%%v"
if not defined VERSION set "VERSION=unknown"

if not "%~1"=="" goto dispatch

:menu
cls
echo ================================================================
echo                    StarsectorPrepatcher %VERSION%
echo ================================================================
echo.
echo  1. Install javaagent
echo     Adds the Prepatcher agent to a chosen launch path, after any
echo     existing javaagents, with a timestamped backup.
echo.
echo  2. Remove javaagent
echo     Removes only the Prepatcher entry; other launch options are
echo     preserved. A backup is created before modifying the file.
echo.
echo  3. Show detailed help
echo  0. Exit
echo.
choice /C 1230 /N /M "Select an action [1-3, 0]: "
if errorlevel 4 exit /b 0
if errorlevel 3 goto help_interactive
if errorlevel 2 goto uninstall_interactive
if errorlevel 1 goto install_interactive
goto menu

:install_interactive
call :select_target "INSTALL"
if errorlevel 1 goto menu
call :run_action "Install javaagent: %TARGETS%" "install-agent.ps1" "%TARGETS%"
goto menu

:uninstall_interactive
call :select_target "REMOVE"
if errorlevel 1 goto menu
call :run_action "Remove javaagent: %TARGETS%" "uninstall-agent.ps1" "%TARGETS%"
goto menu

:help_interactive
call :show_help
echo.
pause
goto menu

:select_target
cls
echo ================================================================
echo  %~1 JAVAAGENT
echo ================================================================
echo.
echo  Select launch paths to update:
echo.
set "TARGETS="

choice /C YN /N /M "  Vanilla (Starsector\vmparams)? [Y/N]: "
if errorlevel 2 goto :select_fr
set "TARGETS=Vanilla"

:select_fr
choice /C YN /N /M "  Separate Faster Rendering launcher (starsector-core\fr.vmparams)? [Y/N]: "
if errorlevel 2 goto :select_miko
if defined TARGETS (set "TARGETS=%TARGETS%,FasterRendering") else (set "TARGETS=FasterRendering")

:select_miko
choice /C YN /N /M "  Mikohime (original or Gaius; detected automatically)? [Y/N]: "
if errorlevel 2 goto :select_done
if defined TARGETS (set "TARGETS=%TARGETS%,Mikohime") else (set "TARGETS=Mikohime")

:select_done
if not defined TARGETS (
    echo.
    echo  No target selected.
    timeout /t 2 >nul
    exit /b 1
)
if /I "%TARGETS%"=="Vanilla,FasterRendering,Mikohime" set "TARGETS=All"
exit /b 0

:run_action
cls
echo ================================================================
echo  %~1
echo ================================================================
echo.
if "%~3"=="" (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0%~2"
) else (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0%~2" -Target "%~3"
)
set "RESULT=%ERRORLEVEL%"
echo.
if "%RESULT%"=="0" (
    echo Selected launch paths processed. Follow any instructions above.
) else (
    echo ERROR: action failed with exit code %RESULT%.
)
echo.
pause
exit /b %RESULT%

:dispatch
if /I "%~1"=="help" goto help_cli
if /I "%~1"=="install" goto install_cli
if /I "%~1"=="uninstall" goto uninstall_cli
echo Unknown command: %~1
echo.
call :show_usage
exit /b 2

:install_cli
call :normalize_target "%~2"
if errorlevel 1 exit /b 2
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-agent.ps1" -Target "%TARGET%"
exit /b %ERRORLEVEL%

:uninstall_cli
call :normalize_target "%~2"
if errorlevel 1 exit /b 2
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0uninstall-agent.ps1" -Target "%TARGET%"
exit /b %ERRORLEVEL%

:normalize_target
set "TARGET=%~1"
if not defined TARGET goto :show_target_usage
if /I "%TARGET%"=="All" exit /b 0
set "BAD="
for %%t in (%TARGET%) do (
    if /I not "%%t"=="Vanilla" if /I not "%%t"=="FasterRendering" if /I not "%%t"=="Mikohime" set "BAD=%%t"
)
if defined BAD (
    echo Unknown target: %BAD%
    goto :show_target_usage
)
exit /b 0
:show_target_usage
echo Specify targets: Vanilla, FasterRendering, Mikohime (comma-separated), or All.
echo.
call :show_usage
exit /b 1

:help_cli
call :show_help
exit /b 0

:show_help
echo StarsectorPrepatcher.bat - unified Windows launcher.
echo.
echo IMPORTANT: fully close Starsector before installing or removing the
echo javaagent. If the game is running, it may overwrite the launch files
echo and undo the change.
echo.
echo Install (menu item 1 / 'install' command):
echo   Adds -javaagent:../mods/StarsectorPrepatcher/agent/StarsectorPrepatcherAgent.jar
echo   after any existing javaagents in the chosen launch path. Every target
echo   file is validated before any of them is written, and a timestamped
echo   backup is created. The agent exports its ASM modules at startup, so
echo   no --add-exports flags are needed.
echo   Targets: Vanilla, FasterRendering, Mikohime (comma-separated), or All.
echo   Original Mikohime: this installer updates its launch files.
echo   Gaius Mikohime with built-in Prepatcher support: enable or disable it
echo   in Configure_Me.cmd. This installer only displays instructions for it.
echo   FasterRendering selects a separate fr.vmparams launch path; it is not
echo   needed just because Faster Rendering is enabled inside Mikohime.
echo.
echo Remove (menu item 2 / 'uninstall' command):
echo   Removes only the Prepatcher entry; other javaagents and launch
echo   options are left intact. A timestamped backup is created first.
echo   Same targets as install.
echo.
echo Run without arguments to open the interactive menu.
echo.
call :show_usage
exit /b 0

:show_usage
echo Command-line usage:
echo   StarsectorPrepatcher.bat
echo   StarsectorPrepatcher.bat install Vanilla
echo   StarsectorPrepatcher.bat install FasterRendering
echo   StarsectorPrepatcher.bat install Mikohime
echo   StarsectorPrepatcher.bat install Vanilla,Mikohime
echo   StarsectorPrepatcher.bat install All
echo   StarsectorPrepatcher.bat uninstall Vanilla
echo   StarsectorPrepatcher.bat uninstall FasterRendering
echo   StarsectorPrepatcher.bat uninstall Mikohime
echo   StarsectorPrepatcher.bat uninstall Vanilla,Mikohime
echo   StarsectorPrepatcher.bat uninstall All
echo   StarsectorPrepatcher.bat help
exit /b 0
