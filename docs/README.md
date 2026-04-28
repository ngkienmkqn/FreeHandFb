# 📱 FreeHand (C2 Auto-Interaction Farm) - Master Documentation

> **LƯU Ý DÀNH CHO AI AGENTS VÀ DEVELOPERS:** Tài liệu này được thiết kế theo cấu trúc Context-Rich nhằm cung cấp toàn bộ bối cảnh hệ thống, cấu trúc thư mục, quy trình deploy và khóa SSH bảo mật. Khi đọc tài liệu này, bạn phải tuân thủ nghiêm ngặt các quy tắc kiến trúc đã được định hình.

## 1. Tổng Quan Dự Án (Project Overview)
- **Tên dự án**: FreeHandFb (Trước đây là Comment Helper)
- **Mục tiêu**: Một hệ thống Command and Control (C2) Phone Farm. Hệ thống này điều phối hàng loạt các thiết bị Android vật lý để thực hiện các thao tác tương tác trên Facebook (Like, Comment, Đăng Bài Group) một cách hoàn toàn tự động, chạy ngầm (Headless) dựa trên `Android Accessibility Services`.
- **Cơ chế an toàn**: Hoàn toàn **KHÔNG** sử dụng WebView, **KHÔNG** cắm API Token, **KHÔNG** dùng DOM Scraping bằng JS. Hệ thống mô phỏng thao tác vuốt chạm vật lý trực tiếp trên ứng dụng Facebook gốc (Katana/Lite) nhằm vượt qua 100% thuật toán chống Bot của Meta.
- **Quản lý mã nguồn (VCS)**: GitHub Private Repository tại `https://github.com/ngkienmkqn/FreeHandFb.git`. Branch chính: `main`. Local path (Google Drive Sync): `g:\Other computers\My Computer\antigravity\FreeHandFb`.

---

## 2. Kiến Trúc Hệ Thống & Tech Stack (Architecture)

```
┌─────────────────────────────────────────────────────────────────┐
│                  VPS Cloud (dt.ungthien.com:3000)               │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  server/index.js  (Node.js + Express - Monolith API)     │  │
│  │  ├── REST: /api/login, /api/me, /api/posts, /api/articles│  │
│  │  ├── OTA:  /api/engine/script?version=xxx                │  │
│  │  │         /api/engine/scripts (danh sách phiên bản)     │  │
│  │  ├── Data: server/data/*.json (users, posts, articles)   │  │
│  │  └── Web:  server/public/admin.html (Admin Dashboard)    │  │
│  └───────────────────────────────────────────────────────────┘  │
│         ▲ HTTP REST (Bearer Token Auth)    ▲                    │
└─────────┼──────────────────────────────────┼────────────────────┘
          │                                  │
    ┌─────┴──────┐                    ┌──────┴─────┐
    │ Android #1 │                    │ Android #N │
    │ FbAutoSvc  │        ...        │ FbAutoSvc  │
    │ (Kotlin)   │                    │ (Kotlin)   │
    └────────────┘                    └────────────┘
```

### A. Central Server (VPS / Cloud) — Thư mục `server/`
- **Vai trò**: Trạm Chỉ Huy Trung Tâm (The Brain). Cung cấp REST API cho dàn Android và giao diện Web Dashboard cho Admin.
- **Công nghệ**: `Node.js v20+`, `Express`, `HTML/Vanilla CSS` (Thiết kế PWA Glassmorphism). Không sử dụng Webpack/Vite để giữ sự tối giản và khả năng hot-edit trực tiếp trên server.
- **File chính**: `server/index.js` — tệp backend nguyên khối (monolith) xử lý toàn bộ logic API, phân quyền Admin/User, và I/O dữ liệu JSON.
- **Web Dashboard**: `server/public/admin.html` — giao diện Admin quản lý thành viên, duyệt nhóm gợi ý, duyệt bài mẫu và cấu hình hạn mức.
- **Tính năng Cốt lõi**:
  - **REST Endpoints**: `/api/login`, `/api/me` (GET/PUT sync cấu hình), `/api/posts`, `/api/posts/bulk`, `/api/articles`, `/api/templates`, `/api/suggested-groups`.
  - **Over-The-Air (OTA) Multi-Version Scripting**: 
    - `GET /api/engine/scripts` → trả danh sách tất cả các phiên bản script đã đánh số (VD: `v1.0.1_OTA_VPS`, `v1.1.0_OTA_VPS`) kèm con trỏ `latest`.
    - `GET /api/engine/script?version=xxx` → trả nội dung JSON Anchors của phiên bản được chỉ định. Nếu `version=latest` hoặc bỏ trống, trả phiên bản mới nhất.
    - JSON Anchors bao gồm: `wrong_screen`, `block_dialog`, `group_join`, `dead_link`, `compose_button`, `post_button`, `comment_button`, `send_comment`, `photo_button`.
    - **Khi Facebook đổi giao diện**: Chỉ cần thêm version mới vào object `ENGINE_SCRIPTS.versions` trong `server/index.js`, cập nhật trường `latest`, rồi `pm2 restart C2-Dashboard`. Toàn bộ máy Android sẽ tự động nhận script mới mà không cần build lại APK.
    - **Khi cần Rollback**: Người dùng vào Cài đặt App → OTA Accessibility Engine → Dropdown chọn lại phiên bản cũ → bấm Sync.
  - **Data Persistence**: Lưu trữ dữ liệu bằng file JSON phẳng (`users.json`, `posts.json`, `articles.json`) tại thư mục `server/data/`. Không dùng database.
  - **Universal Cloud Synchronization**: Cấu hình của app điện thoại (`SharedPreferences` như SĐT, Zalo, Lịch hẹn giờ, Bài viết Spintax đã chọn) được đồng bộ hóa toàn diện vào `user.settings` trên VPS qua `PUT /api/me`. Khi cài lại thiết bị, chế độ **Zero-Touch Recovery** tự động pull `GET /api/me` lấy lại cấu hình.

### B. Client Node (Native Android App) — Thư mục `app/`
- **Vai trò**: Máy Cày (The Worker). Ứng dụng chạy nền, thu nhận lệnh và thực thi trên điện thoại.
- **Công nghệ**: `Kotlin`, `Jetpack Compose` (UI), `Android AccessibilityService`, `AlarmManager`, `Coroutines`.
- **Files chính**:
  - `app/src/main/java/com/example/commenthelper/MainActivity.kt` — Toàn bộ UI Jetpack Compose (Dashboard, Cài đặt, Spintax Composer). Xử lý chu trình `syncWithServer()`, lưu trữ Token, và đồng bộ Cloud.
  - `app/src/main/java/com/example/commenthelper/FbAutoService.kt` — Trái tim của kịch bản Auto. Chứa State Machine phân tích màn hình (`Step.LOOKING_FOR_COMPOSER`, `Step.LOOKING_FOR_LIKE`, v.v..) và singleton `Engine` object nạp Text Anchors từ OTA Server qua `Engine.load()`.
  - `app/src/main/java/com/example/commenthelper/AutoPublishWorker.kt` — Tạo gói dữ liệu `TaskItem` (bài viết, link hình) và kích hoạt `FbAutoService` chạy trong nền.
- **Tính năng Cốt lõi**:
  - **Headless Execution**: `AlarmManager` đánh thức điện thoại định kỳ theo phút (cấu hình được). App tự động bật sáng màn hình (WakeLock) và thực thi.
  - **OTA Version Selector**: Dropdown trong Settings UI cho phép user chọn phiên bản Script OTA cụ thể (mặc định `latest`). Giá trị lưu tại `SharedPreferences["selected_ota_version"]`, được truyền qua query param khi gọi API.
  - **Spintax Engine**: Trình soạn thảo trực quan hỗ trợ biến `{PHONE}`, `{ZALO}` và spin `{A|B|C}` ngay trên thiết bị.
  - **Auto Image Picker**: Tự động lưu hình vào `MediaStore` rồi dùng Accessibility chọc vào Android Gallery.
  - **DOM Interceptor & Safety**: Quét bố cục màn hình để phát hiện cửa sổ chặn (Action Block), Dead Links, hoặc màn hình lạ (Share Sheet/Messenger) để tự động Halt, bảo vệ tài khoản.

---

## 3. Cấu Trúc Thư Mục Chi Tiết (Directory Layout)

```
FreeHandFb/
├── app/                                    # Android App (Kotlin + Jetpack Compose)
│   ├── build.gradle.kts                    # Android dependencies & SDK config
│   └── src/main/
│       ├── AndroidManifest.xml             # Permissions & Accessibility declaration
│       └── java/com/example/commenthelper/
│           ├── MainActivity.kt             # ★ UI + Sync + Cloud Settings (Compose)
│           ├── FbAutoService.kt            # ★ Accessibility Engine + OTA Anchors
│           └── AutoPublishWorker.kt        # ★ Background task scheduler
├── server/                                 # VPS Backend (Node.js)
│   ├── index.js                            # ★ Monolith API (Express + OTA Engine)
│   ├── package.json                        # Node dependencies
│   ├── data/                               # Persistent JSON storage (auto-created)
│   │   ├── users.json
│   │   ├── posts.json
│   │   └── articles.json
│   └── public/                             # Static web files
│       └── admin.html                      # ★ Admin Dashboard (Glassmorphism PWA)
├── docs/
│   └── README.md                           # ← Bạn đang đọc file này
├── .gitignore
└── gradle/                                 # Gradle wrapper
```

---

## 4. Quản Lý Triển Khai & Server (Deployment & SSH)

| Thông tin | Giá trị |
|-----------|---------|
| **Domain/IP** | `dt.ungthien.com` |
| **Port** | `3000` (API & WebApp) |
| **Process Manager** | `PM2` (tên: `C2-Dashboard`) |
| **Đường dẫn code trên VPS** | `/root/server/` |
| **GitHub Repo** | `https://github.com/ngkienmkqn/FreeHandFb.git` (Private) |
| **Branch** | `main` |

### Quy trình Deploy lên VPS
```bash
# 1. SSH vào server
ssh -i ~/.ssh/id_ed25519 root@dt.ungthien.com

# 2. Pull code mới từ GitHub (nếu đã clone)
cd /root/server && git pull origin main

# 3. Hoặc copy thủ công từ local
scp -i ~/.ssh/id_ed25519 -r ./server/* root@dt.ungthien.com:/root/server/

# 4. Restart service
pm2 restart C2-Dashboard

# 5. Xem logs nếu cần debug
pm2 logs C2-Dashboard --lines 50
```

### Thông tin chứng thực SSH (Dành cho Cập Nhật tự động)
Để AI Agents hoặc Developer truy cập và deploy code lên máy chủ Cloud `dt.ungthien.com`, sử dụng **Ed25519 Private Key** (quyền root) dưới đây:

```text
-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
QyNTUxOQAAACBZRuU9+SFB0s90uPMGCuBddTGhOq5lwm2v25alICPnOQAAAJA/MDPNPzAz
zQAAAAtzc2gtZWQyNTUxOQAAACBZRuU9+SFB0s90uPMGCuBddTGhOq5lwm2v25alICPnOQ
AAAEDPknHep38u8c8z6QnMD1Vm6s3USldnnPknpp4vYb4HyVlG5T35IUHSz3S48wYK4F11
MaE6rmXCba/blqUgI+c5AAAADHZwcy1jMi1hZG1pbgE=
-----END OPENSSH PRIVATE KEY-----
```

**Cách sử dụng:**
1. Lưu nội dung trên vào tệp `~/.ssh/id_ed25519`.
2. `chmod 600 ~/.ssh/id_ed25519`
3. `ssh -i ~/.ssh/id_ed25519 root@dt.ungthien.com`

---

## 5. API Reference (Tóm tắt)

| Method | Endpoint | Mô tả |
|--------|----------|-------|
| POST | `/api/login` | Đăng nhập (username/password) → trả Bearer token |
| GET | `/api/me` | Lấy profile + settings người dùng |
| PUT | `/api/me` | Cập nhật settings (SĐT, Zalo, lịch hẹn giờ...) |
| GET | `/api/posts` | Danh sách bài cần tương tác |
| POST | `/api/posts/bulk` | Thêm nhiều bài viết cùng lúc |
| POST | `/api/posts/:id/done` | Đánh dấu bài viết đã xong |
| GET | `/api/templates` | Danh sách comment template |
| GET | `/api/articles` | Danh sách bài mẫu Spintax |
| POST | `/api/articles` | Gửi bài mẫu mới (user đóng góp) |
| GET | `/api/suggested-groups` | Danh sách nhóm gợi ý (đã duyệt) |
| GET | `/api/engine/scripts` | Danh sách tất cả phiên bản OTA Script |
| GET | `/api/engine/script?version=xxx` | Tải nội dung Anchors theo phiên bản |

---

## 6. Sổ tay Quy trình (Developer Workflows)

### Workflow 1: Facebook đổi tên nút bấm (VD: "Đăng" → "Chia sẻ")
1. **KHÔNG CẦN BUILD LẠI APK**.
2. Mở `server/index.js`, tìm object `ENGINE_SCRIPTS`.
3. Tạo phiên bản mới trong `versions` (VD: `"v1.2.0_OTA_VPS": {...}`).
4. Cập nhật trường `latest` trỏ đến version mới.
5. `pm2 restart C2-Dashboard` → Toàn bộ điện thoại nhận script mới khi Sync!

### Workflow 2: Thay đổi Logic UI trên Android
1. Chỉnh sửa `MainActivity.kt` hoặc `FbAutoService.kt` trong Android Studio.
2. Build → Generate Signed APK.
3. Gửi file APK cho user cài lại.

### Workflow 3: Push code lên GitHub
```bash
git add -A
git commit -m "feat: mô tả thay đổi"
git push origin main
```

### ⚠️ Quy tắc kiến trúc bắt buộc
- **KHÔNG** được dùng `ACTION_SEND` intent vì sẽ kẹt ở màn hình chọn nhóm.
- Cốt lõi công nghệ: nhảy thẳng vào FB App (Katana) rồi cho Accessibility chọc trực tiếp vào `AccessibilityNodeInfo.ACTION_CLICK`. **Bảo tồn cấu trúc này.**
- Mọi text anchor (nút bấm, dialog chặn, v.v.) phải được quản lý qua OTA `Engine` object, **KHÔNG hardcode** trong Kotlin.
