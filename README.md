# Đồng Hồ Nổi — by Minh Thái

Đồng hồ nổi trên mọi ứng dụng, giao diện **soft glass** (kính mờ), tối ưu cho **Redmi Turbo 4 Pro / HyperOS**.

## Tính năng

- Đồng hồ nổi dạng viên kính mờ, **kéo để di chuyển**, nhớ vị trí.
- **Nhấn giữ vào đồng hồ** → bảng chỉnh nhanh: cỡ chữ, độ đậm, màu chữ, **Tắt đồng hồ**, mở Cài đặt (có chữ *by Minh Thái*).
- **Báo thức nhấp nháy** kèm ghi chú nhắc việc; chạm 1 lần vào đồng hồ để tắt nhấp nháy.
- Màu chữ (10 màu + tự nhập mã `#RRGGBB`), cỡ chữ 10–60 sp, độ đậm 20–100 %, hiện giây.
- Làm mờ nền phía sau (Android 12+, cần bật "hiệu ứng làm mờ" của hệ thống).
- Ô bật/tắt nhanh trong thanh **Cài đặt nhanh** (Quick Settings).
- Tự chạy lại sau khi khởi động máy / sau khi cập nhật / khi bị hệ thống dọn.
- **Cập nhật OTA qua GitHub Releases**.
- Nút mở nhanh các mục tối ưu HyperOS: Pin không hạn chế, Tự khởi động, Pop-up khi chạy nền, Khóa đa nhiệm.

## 1. Đưa lên GitHub

1. Tạo repo mới trên GitHub (ví dụ `FloatingClock`), đẩy toàn bộ thư mục này lên.
2. Không cần sửa code: workflow tự truyền tên chủ repo + tên repo vào app (`GITHUB_OWNER`, `GITHUB_REPO`).
   - Nếu tự build bằng Android Studio, hãy sửa 2 giá trị mặc định ở đầu `app/build.gradle.kts`
     (`ghOwner`, `ghRepo`) hoặc chạy `gradle assembleRelease -PghOwner=ten_ban -PghRepo=FloatingClock`.
3. Repo nên để **Public** để app đọc được Release không cần đăng nhập.

## 2. Tạo khóa ký (làm 1 lần)

APK mới chỉ cài đè được lên bản cũ nếu **cùng một khóa ký**. Vì vậy cần 1 khóa cố định:

```bash
bash tools/make-keystore.sh
```

Script in ra 4 giá trị. Vào GitHub → **Settings → Secrets and variables → Actions** và thêm:
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

Giữ kỹ file `release.jks` (không commit lên repo, `.gitignore` đã chặn).

## 3. Phát hành bản đầu tiên

```bash
git tag v1.0.0
git push origin v1.0.0
```

GitHub Actions sẽ build và tạo Release có file `FloatingClock-1.0.0.apk`. Tải về cài lên máy.

## 4. Cập nhật OTA về sau

Sửa code → đẩy lên → gắn tag mới cao hơn:

```bash
git tag v1.0.1
git push origin v1.0.1
```

Trên điện thoại mở app → mục **Cập nhật (GitHub) → Kiểm tra cập nhật** (app cũng tự kiểm tra mỗi 6 giờ khi mở).
Lần đầu cần cấp quyền "Cài ứng dụng không rõ nguồn" cho chính app.

> Số tag (`v1.0.1`) trở thành `versionName`, số lần chạy workflow trở thành `versionCode`, nên luôn tăng dần.

## 5. Thiết lập trên Redmi Turbo 4 Pro (HyperOS)

Trong app có sẵn các nút mở thẳng trang cài đặt, làm đủ 4 bước:

1. **Pin: Không hạn chế**.
2. **Tự khởi động**: bật.
3. **Cửa sổ pop-up khi chạy nền**: cho phép (nếu thiếu, nhấn giữ → "Cài đặt" có thể không mở được app).
4. **Khóa app trong đa nhiệm** (biểu tượng ổ khóa).

Ngoài ra cấp quyền *Hiển thị trên các ứng dụng khác* và *Thông báo* khi app hỏi.

## Build thủ công

Mở thư mục bằng **Android Studio** (Ladybug trở lên, JDK 17), để nó đồng bộ Gradle rồi chạy.
Hoặc: `gradle assembleRelease` (Gradle 8.9).

## Cấu trúc

| File | Vai trò |
|---|---|
| `ClockService.kt` | Đồng hồ nổi, kéo thả, nhấn giữ, bảng chỉnh nhanh, báo thức nhấp nháy |
| `MainActivity.kt` | Màn hình cài đặt kính mờ, tối ưu HyperOS, OTA |
| `Updater.kt` | Kiểm tra / tải / cài APK từ GitHub Releases |
| `ClockReceiver.kt` | Tự chạy lại sau boot, cập nhật, báo thức |
| `ClockTileService.kt` | Ô bật/tắt nhanh |
| `.github/workflows/release.yml` | Build APK và tạo Release khi đẩy tag `v*` |
