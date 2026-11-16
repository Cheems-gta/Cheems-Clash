#!/system/bin/sh
count="$(printf 'GET /large HTTP/1.0\r\nHost: 198.18.0.42\r\n\r\n' | toybox nc -w 20 198.18.0.42 8766 | toybox wc -c)"
case "$count" in
  ''|*[!0-9]*) echo "LARGE_TUN_FAIL: bad count $count"; exit 1 ;;
esac
if [ "$count" -ge 262180 ]; then
  echo "CHEEMS_CORE_LARGE_OK $count"
else
  echo "LARGE_TUN_FAIL: premature EOF at $count bytes"
  exit 1
fi
