@echo off
cd /d "C:\Users\ahmed\Downloads\APT-2"
mvn exec:java -Dexec.mainClass=com.project.network.CollaborativeEditorServer
pause 