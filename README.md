# AR Drawing V5 — Hướng dẫn build APK (không cần Android Studio!)

## Cách lấy file APK trong 5 bước ⬇️

### Bước 1 — Tạo tài khoản GitHub (miễn phí)
Vào https://github.com → **Sign up** → điền email, mật khẩu, tên → xác nhận email.

### Bước 2 — Tạo repository mới
1. Nhấn nút **"+"** góc trên phải → **New repository**
2. Đặt tên: `ar-drawing`
3. Chọn **Public**
4. Nhấn **Create repository**

### Bước 3 — Upload toàn bộ thư mục này
**Cách A (kéo thả — dễ nhất):**
1. Mở trang repository vừa tạo
2. Nhấn **"uploading an existing file"** (link nhỏ ở giữa trang)
3. Mở thư mục `ardrawing_v5` trên máy, **chọn tất cả** (Ctrl+A)
4. **Kéo thả** vào trình duyệt
5. Cuộn xuống → **Commit changes**

**Cách B (dùng Git):**
```bash
cd ardrawing_v5
git init && git add . && git commit -m "AR Drawing V5"
git remote add origin https://github.com/TÊN_BẠN/ar-drawing.git
git push -u origin main
```

### Bước 4 — Chờ build tự động (~5 phút)
1. Vào tab **Actions** trên GitHub
2. Thấy workflow **"🏗️ Build AR Drawing APK"** đang chạy (icon ⚙️ vàng)
3. Chờ icon chuyển sang ✅ xanh

> ⚠️ Nếu chưa thấy workflow chạy: nhấn **Actions** → **"Build AR Drawing APK"** → **"Run workflow"**

### Bước 5 — Tải APK về điện thoại
1. Nhấn vào workflow run vừa chạy xong ✅
2. Cuộn xuống mục **Artifacts**
3. Nhấn **ARDrawing-V5-debug-apk** → file ZIP sẽ tải về
4. Giải nén → có file `app-debug.apk`

---

## Cài APK lên điện thoại Android

1. **Android 8+:** Vào **Cài đặt → Ứng dụng → Cài ứng dụng không rõ nguồn gốc**
   → Cho phép trình duyệt/Files của bạn
2. Mở file `app-debug.apk` → Nhấn **Cài đặt**
3. Nếu bị chặn: **Cài đặt → Bảo mật → Cài từ nguồn không rõ** → Bật

---

## Tính năng app

- 📷 Xem camera live real-time
- 🖼️ Chọn ảnh từ thư viện → tự động xử lý thành Line Art (đường nét)
- 👆 Pinch to zoom, xoay, kéo để căn chỉnh vị trí ảnh
- 🔒 Nút khóa: cố định ảnh để vẽ không bị xê dịch
- 💡 Bật đèn flash để sáng hơn khi vẽ
- 🔄 Đặt lại vị trí ảnh về trung tâm

---

*AR Drawing V5 — Production-Ready Android App*
