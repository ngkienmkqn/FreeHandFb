# 📱 C2 Auto-Interaction Farm (Phone Farm)

## Giới Thiệu
Hệ thống Automation "cày cmt/like" theo mô hình **Command and Control (C2)** tiên tiến nhất. Cấu trúc hệ thống bao gồm một Trạm Điều Khiển (Server) quản lý toàn bộ một tập hợp lớn các Thiết Bị Di Động (Worker Nodes) để thực hiện các thao tác trên mạng xã hội Facebook hoàn toàn ẩn danh, chạy ngầm dưới background, bảo vệ 100% tài khoản.

## 🏗️ Kiến Trúc Hệ Thống (Architecture)

### 1. Central Server (VPS / Cloud) - Thư mục \`server/\`
- **Công nghệ:** Node.js, Express, Socket.io
- **Nhiệm vụ:** Hoạt động như trạm chỉ huy. Giao diện Web Dashboard dùng để theo dõi bao nhiêu điện thoại đang cắm Tool, truyền lệnh chứa Link bài viết bài nội dung Comment xuống tất cả các thiết bị.

### 2. Client Node (Android App) - Thư mục \`mobile-client/\`
- **Công nghệ:** React Native (Expo), WebView, Socket.io-client
- **Nhiệm vụ:**
  - Nhúng trình duyệt ẩn (WebView) lưu lại Cookie và Fingerprint thật của điện thoại để bypass AI Facebook.
  - Kết nối vĩnh viễn với Server qua WebSockets.
  - Nhận lệnh -> Tải link -> Bơm Javascript Injection vào trang `mbasic.facebook.com` để tự động click Like và nhập Form Bình Luận một cách trơn tru ở chế độ ngầm.

---

## 🚀 Hướng Dẫn Cài Đặt (Deployment Guide)

### Môi trường Local (Máy chạy Windows)
1. Dựng Server: `cd server && npm install && node index.js`
2. Dựng App Expo: Vào thư mục `mobile-client`, thiết lập `npx create-expo-app` sau đó ném file `App.js` vào để chạy `npx expo start`.

### Môi trường VPS Ubuntu (Cloud Deployment)

**Bước 1: Setup Keys để SSH không cần mật khẩu**
Hệ thống đã gen sẵn key tại thư mục `deploy/`. Anh copy toàn bộ nội dung file `vps_key.pub` rồi lên VPS chạy lệnh:
```bash
mkdir -p ~/.ssh && echo "NỘI DUNG FILE PUB KEY VÀO ĐÂY" >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys
```

**Bước 2: Cài đặt Nodejs & PM2**
```bash
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt-get install -y nodejs
npm install -g pm2
```

**Bước 3: Đẩy Code và Chạy Runtime 24/24**
Đẩy thư mục `server/` từ máy tính lên VPS. Trong thư mục server trên VPS:
```bash
npm install
pm2 start index.js --name "C2-Dashboard"
pm2 save
pm2 startup
```
🚀 Server sẽ luôn chạy ngay cả khi VPS reset! Mở port 3000 để truy cập. Dưới App chỉ cần đổi IP thành IP của cái VPS là hệ thống kết nối thành công.

---

## 📸 3. Mô-đun Quản Lý Homestay (Zalo Caching Crawler)
- **Công cụ:** `group_selector.js` (Web UI Môi giới) & `homestay_extractor.js` (Lõi xử lý JXL).
- **Quy trình:**
  - Bỏ qua API Zalo chính thức (tránh bị khoá tài khoản).
  - Web UI (Port 3005) quét toàn bộ ổ cứng để lôi ra các `_group` bị ẩn và cho người dùng chọn bằng mắt.
  - Lõi Node.js phân tích siêu dữ liệu thời gian (`mtimeMs` < 120s) để bó những bức hình ghim chung với nhau thành các Album độc lập (3-15 ảnh/album).
  - Tích hợp công cụ `djxl` để De-compress tệp JXL ẩn của Zalo Desktop siêu nén 40KB lại thành file Web chuẩn.

---

## 🔑 4. Cấu hình bảo mật Cloud (TrumVPS SSH)
Theo yêu cầu Developer hand-off, dưới đây là Private Key duy nhất dùng để Remote trực tiếp vào hệ thống Cloud `dt.ungthien.com`.

**SSH Key (Ed25519 - TrumVPS / Deploy Key)**
\`\`\`text
-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
QyNTUxOQAAACBZRuU9+SFB0s90uPMGCuBddTGhOq5lwm2v25alICPnOQAAAJA/MDPNPzAz
zQAAAAtzc2gtZWQyNTUxOQAAACBZRuU9+SFB0s90uPMGCuBddTGhOq5lwm2v25alICPnOQ
AAAEDPknHep38u8c8z6QnMD1Vm6s3USldnnPknpp4vYb4HyVlG5T35IUHSz3S48wYK4F11
MaE6rmXCba/blqUgI+c5AAAADHZwcy1jMi1hZG1pbgE=
-----END OPENSSH PRIVATE KEY-----
\`\`\`

> ⚠️ Vui lòng sao chép đoạn Key trên lưu dưới định dạng tệp `id_ed25519` để sử dụng cùng MobaXTerm hoặc Terminal: `ssh -i id_ed25519 root@dt.ungthien.com`
