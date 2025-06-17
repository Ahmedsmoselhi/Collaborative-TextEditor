@echo off
echo Starting Collaborative Editor Application...
echo.

echo Starting the server...
start "Collaborative Editor Server" cmd /c "mvn exec:java -Dexec.mainClass=com.project.network.CollaborativeEditorServer & pause"

echo Waiting for server to initialize...
timeout /t 3 /nobreak >nul

echo Starting client 1...
start "Collaborative Editor Client 1" cmd /c "mvn javafx:run & pause"

echo Starting client 2...
start "Collaborative Editor Client 2" cmd /c "mvn javafx:run & pause"

echo.
echo All components started!
echo - Server window: Displays server logs and status
echo - Client windows: Allow multiple users to edit documents collaboratively
echo.
echo Press any key to exit this window (the other windows will remain open)
pause > nul 