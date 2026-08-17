@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0"

if not "%~1"=="" goto dispatch

:menu
cls
echo ================================================================
echo                    StarsectorPrepatcher 0.18.3
echo ================================================================
echo.
echo  1. Build release JARs
echo     Compiles the agent/bootstrap and updates SHA256SUMS.txt.
echo.
echo  2. Install javaagent
echo     Adds Prepatcher to the selected launch configuration with a backup.
echo.
echo  3. Remove javaagent
echo     Removes only the managed entry and backs up the vmparams file.
echo.
echo  4. Run full verification
echo     Rebuilds the project and runs documentation, structural,
echo     runtime, startup, hyperspace, and Faster Rendering tests.
echo.
echo  5. Show detailed help
echo  0. Exit
echo.
choice /C 123450 /N /M "Select an action [1-5, 0]: "
if errorlevel 6 exit /b 0
if errorlevel 5 goto help_interactive
if errorlevel 4 goto verify_interactive
if errorlevel 3 goto uninstall_interactive
if errorlevel 2 goto install_interactive
if errorlevel 1 goto build_interactive
goto menu

:build_interactive
call :run_action "Build release JARs and SHA-256 inventory" "build-agent.ps1"
goto menu

:install_interactive
call :select_target "INSTALL"
if errorlevel 1 goto menu
call :run_action "Install javaagent: %TARGET%" "install-agent.ps1" "%TARGET%"
goto menu

:uninstall_interactive
call :select_target "REMOVE"
if errorlevel 1 goto menu
call :run_action "Remove javaagent: %TARGET%" "uninstall-agent.ps1" "%TARGET%"
goto menu

:verify_interactive
call :run_action "Full release verification" "verify-structural.ps1"
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
echo  1. Vanilla
echo     Updates the main Starsector\vmparams file.
echo.
echo  2. Faster Rendering
echo     Updates Starsector\starsector-core\fr.vmparams.
echo.
echo  3. Both launch paths
echo     Validates both files before updating either one.
echo.
echo  0. Back without changes
echo.
choice /C 1230 /N /M "Select a target [1-3, 0]: "
if errorlevel 4 exit /b 1
if errorlevel 3 (
    set "TARGET=Both"
    exit /b 0
)
if errorlevel 2 (
    set "TARGET=FasterRendering"
    exit /b 0
)
if errorlevel 1 (
    set "TARGET=Vanilla"
    exit /b 0
)
exit /b 1

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
    echo Action completed successfully.
) else (
    echo ERROR: action failed with exit code %RESULT%.
)
echo.
pause
exit /b %RESULT%

:dispatch
if /I "%~1"=="help" goto help_cli
if /I "%~1"=="build" goto build_cli
if /I "%~1"=="verify" goto verify_cli
if /I "%~1"=="install" goto install_cli
if /I "%~1"=="uninstall" goto uninstall_cli
echo Unknown command: %~1
echo.
call :show_usage
exit /b 2

:build_cli
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-agent.ps1"
exit /b %ERRORLEVEL%

:verify_cli
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0verify-structural.ps1"
exit /b %ERRORLEVEL%

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
set "TARGET="
if /I "%~1"=="Vanilla" set "TARGET=Vanilla"
if /I "%~1"=="FasterRendering" set "TARGET=FasterRendering"
if /I "%~1"=="Both" set "TARGET=Both"
if defined TARGET exit /b 0
echo Specify an install/uninstall target: Vanilla, FasterRendering, or Both.
echo.
call :show_usage
exit /b 1

:help_cli
call :show_help
exit /b 0

:show_help
echo StarsectorPrepatcher.bat - unified Windows launcher.
echo.
echo Build:
echo   Creates agent\StarsectorPrepatcherAgent.jar and the bootstrap JAR,
echo   validates the runtime payload, and recalculates SHA256SUMS.txt.
echo.
echo Install:
echo   Adds -javaagent after other javaagents. It validates vmparams first
echo   and creates a timestamped backup before writing any changes.
echo.
echo Remove:
echo   Removes only the StarsectorPrepatcher entry. Other launch options
echo   are preserved, and a backup is created before modifying the file.
echo.
echo Verify:
echo   Runs the complete release gate. Reports are written to
echo   .build\reports; .build is excluded from Git and the release.
echo.
echo Fully close Starsector before installing or removing the javaagent.
echo Run without arguments to open the interactive menu.
echo.
call :show_usage
exit /b 0

:show_usage
echo Command-line usage:
echo   StarsectorPrepatcher.bat
echo   StarsectorPrepatcher.bat build
echo   StarsectorPrepatcher.bat verify
echo   StarsectorPrepatcher.bat install Vanilla
echo   StarsectorPrepatcher.bat install FasterRendering
echo   StarsectorPrepatcher.bat install Both
echo   StarsectorPrepatcher.bat uninstall Vanilla
echo   StarsectorPrepatcher.bat uninstall FasterRendering
echo   StarsectorPrepatcher.bat uninstall Both
echo   StarsectorPrepatcher.bat help
exit /b 0
