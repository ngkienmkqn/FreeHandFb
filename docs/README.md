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
