@echo off
cd /d "%~dp0"
python -c "import serial, bleak" 2>nul || python -m pip install -r requirements.txt
start "" pythonw obd_app.py --conectar
