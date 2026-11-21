#!/usr/bin/env bash
# Tạo khóa ký APK (chỉ làm 1 lần, GIỮ KỸ file release.jks - mất là không cập nhật OTA được nữa).
# Cần có JDK (lệnh keytool). Chạy trên máy tính hoặc Termux.
set -e

read -r -s -p "Đặt mật khẩu cho khóa (>= 6 ký tự): " PASS
echo
ALIAS="floatclock"

keytool -genkeypair -v \
  -keystore release.jks \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 -validity 36500 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=Minh Thai, O=FloatingClock, C=VN"

echo
echo "===== Thêm 4 secret này vào GitHub: Settings > Secrets and variables > Actions ====="
echo "KEYSTORE_PASSWORD = (mật khẩu bạn vừa đặt)"
echo "KEY_PASSWORD      = (cùng mật khẩu đó)"
echo "KEY_ALIAS         = $ALIAS"
echo "KEYSTORE_BASE64   = (chuỗi dài bên dưới)"
echo
base64 -w0 release.jks 2>/dev/null || base64 release.jks | tr -d '\n'
echo
