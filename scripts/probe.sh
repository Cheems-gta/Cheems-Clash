#!/system/bin/sh
printf 'GET /health.txt HTTP/1.0\r\nHost: 198.18.0.42\r\n\r\n' | toybox nc -w 10 198.18.0.42 8765
