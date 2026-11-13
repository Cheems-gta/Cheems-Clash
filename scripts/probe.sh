#!/system/bin/sh
printf 'GET /health.txt HTTP/1.0\r\nHost: 10.0.2.2\r\n\r\n' | toybox nc -w 10 10.0.2.2 8765
