@echo off
echo Starting Collaborative Text Editor...
echo.

rem Check if Maven is installed
where mvn >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo Maven not found. Please install Maven and add it to your PATH.
    goto :end
)

rem Check if Java is installed
where java >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo Java not found. Please install JDK 17 or higher and add it to your PATH.
    goto :end
)

rem Build and run the application
echo Building the application...
call mvn clean package

if %ERRORLEVEL% neq 0 (
    echo Failed to build the application.
    goto :end
)

echo.
echo Running the application...
call mvn javafx:run

:end
echo.
pause 