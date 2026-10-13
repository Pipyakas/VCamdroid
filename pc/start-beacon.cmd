@echo off
rem VCamdroid LAN beacon: lets the phone find this PC without a QR code or cable.
start "VCamdroid beacon" /min pythonw "%~dp0beacon.py"
