@echo off
setlocal
cd /d "%~dp0app"
if not exist data mkdir data
set "NODE_ENV=production"
set "DATABASE_URL=file:./data/gtnh-ai-bot.db"
set "APP_DATA_DIR=%LOCALAPPDATA%\GTNH AI Bot"
set "HOST=127.0.0.1"
set "PORT=3000"
set "WEB_ORIGIN=http://127.0.0.1:3000"
start "" http://127.0.0.1:3000
"..\runtime\node.exe" dist\index.mjs
endlocal
