@echo off
echo Starting collaborative editor instances...

echo Make sure the WebSocket server is running (use run_server.bat in another console)
timeout /t 3

echo Starting client instances...
start cmd /k "mvn javafx:run -Djavafx.args=\"user1\""
start cmd /k "mvn javafx:run -Djavafx.args=\"user2\""

echo Started two instances. You can now test collaboration between them. 