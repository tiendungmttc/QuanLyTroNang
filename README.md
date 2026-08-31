# Quản lý Trợ năng v1.3

Ứng dụng Android dành cho việc quản lý các Accessibility Service trong nhóm **Ứng dụng đã tải xuống**.

## Chức năng

1. **TẮT TẤT CẢ** – tắt toàn bộ dịch vụ Trợ năng thuộc nhóm ứng dụng đã tải xuống, nhưng giữ nguyên các dịch vụ hệ thống khác.
2. **BẬT ỨNG DỤNG ĐÃ CHỌN** – bật các ứng dụng đã tích chọn. Danh sách lựa chọn được lưu lại.

Không còn chức năng “Tắt → bật lại đã chọn”.

## Quyền ADB một lần

Sau khi cài APK, chạy:

```bash
adb shell pm grant com.dung.accessibilitymanager android.permission.WRITE_SECURE_SETTINGS
```

Sau khi cấp quyền có thể rút USB; app không cần Shizuku, Wi‑Fi hay root.

## Build bằng GitHub Actions

Vào **Actions → Build APK → Run workflow**. APK nằm trong Artifacts với tên `QuanLyTroNang-v1.3`.


## v1.3
- Sửa lỗi Android/HyperOS không thấy app bên thứ ba do package visibility.
- Dò Accessibility Service từ cả AccessibilityManager và PackageManager.
- Lọc gần với nhóm “Ứng dụng đã tải xuống” trên HyperOS.
