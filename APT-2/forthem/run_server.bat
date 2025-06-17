@echo off
cd /d D:\APT
mvn exec:java -Dexec.mainClass=com.project.network.CollaborativeEditorServer
pause 