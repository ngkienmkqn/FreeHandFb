# 📱 FreeHand (C2 Auto-Interaction Farm) - Master Documentation

> **LƯU Ý DÀNH CHO AI AGENTS VÀ DEVELOPERS:** Tài liệu này được thiết kế theo cấu trúc Context-Rich nhằm cung cấp toàn bộ bối cảnh hệ thống, cấu trúc thư mục, quy trình deploy và khóa SSH bảo mật. Khi đọc tài liệu này, bạn phải tuân thủ nghiêm ngặt các quy tắc kiến trúc đã được định hình.

## 1. Tổng Quan Dự Án (Project Overview)
- **Tên dự án**: FreeHandFb (Trước đây là Comment Helper)
- **Mục tiêu**: Một hệ thống Command and Control (C2) Phone Farm. Hệ thống này điều phối hàng loạt các thiết bị Android vật lý để thực hiện các thao tác tương tác trên Facebook (Like, Comment, Đăng Bài Group) một cách hoàn toàn tự động, chạy ngầm (Headless) dựa trên `Android Accessibility Services`.
- **Cơ chế an toàn**: Hoàn toàn **KHÔNG** sử dụng WebView, **KHÔNG** cắm API Token, **KHÔNG** dùng DOM Scraping bằng JS. Hệ thống mô phỏng thao tác vuốt chạm vật lý trực tiếp trên ứng dụng Facebook gốc (Katana/Lite) nhằm vượt qua 100% thuật toán chống Bot của Meta.
- **Quản lý mã nguồn (VCS)**: GitHub Private Repository tại `https://github.com/ngkienmkqn/FreeHandFb.git`. Branch chính: `main`. Local path (Google Drive Sync): `g:\Other computers\My Computer\antigravity\FreeHandFb`.
- **OTA Engine Version**: `v1.3.0_OTA_VPS` (latest)

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
    - `GET /api/engine/scripts` → trả danh sách tất cả các phiên bản script đã đánh số kèm con trỏ `latest`.
    - `GET /api/engine/script?version=xxx` → trả nội dung JSON Anchors của phiên bản được chỉ định.
    - **Khi Facebook đổi giao diện**: Chỉ cần thêm version mới vào object `ENGINE_SCRIPTS.versions` trong `server/index.js`, cập nhật trường `latest`, rồi `pm2 restart C2-Dashboard`. Toàn bộ máy Android sẽ tự động nhận script mới khi Sync.
    - **Khi cần Rollback**: Người dùng vào Cài đặt App → OTA Accessibility Engine → Dropdown chọn lại phiên bản cũ → bấm Sync.
  - **Data Persistence**: Lưu trữ dữ liệu bằng file JSON phẳng (`users.json`, `posts.json`, `articles.json`) tại thư mục `server/data/`. Không dùng database.
  - **Universal Cloud Synchronization**: Cấu hình của app điện thoại (`SharedPreferences` như SĐT, Zalo, Lịch hẹn giờ, Bài viết Spintax đã chọn) được đồng bộ hóa toàn diện vào `user.settings` trên VPS qua `PUT /api/me`. Khi cài lại thiết bị, chế độ **Zero-Touch Recovery** tự động pull `GET /api/me` lấy lại cấu hình.

### B. Client Node (Native Android App) — Thư mục `app/`
- **Vai trò**: Máy Cày (The Worker). Ứng dụng chạy nền, thu nhận lệnh và thực thi trên điện thoại.
- **Công nghệ**: `Kotlin`, `Jetpack Compose` (UI), `Android AccessibilityService`, `WorkManager`, `AlarmManager`, `Coroutines`.
- **Files chính**:
  - `MainActivity.kt` — Toàn bộ UI Jetpack Compose (Dashboard, Cài đặt, Spintax Composer). Xử lý chu trình `syncWithServer()`, lưu trữ Token, và đồng bộ Cloud.
  - `FbAutoService.kt` — Trái tim của kịch bản Auto. Chứa State Machine phân tích màn hình và singleton `Engine` object nạp Text Anchors + Gallery Config từ OTA Server.
  - `AutoPublishWorker.kt` — Background Worker tự động lấy bài + nhóm random → kích hoạt `FbAutoService` chạy ngầm. Hỗ trợ `FORCE_RUN` flag bypass khung giờ.
  - `AutoPublishReceiver.kt` — BroadcastReceiver lên lịch chu kỳ publish qua `AlarmManager`.
- **Tính năng Cốt lõi**:
  - **Headless Execution**: `AlarmManager` đánh thức điện thoại định kỳ. App tự bật sáng màn hình (WakeLock) và thực thi.
  - **OTA Version Selector**: Dropdown trong Settings UI cho phép user chọn phiên bản Script OTA cụ thể (mặc định `latest`).
  - **Spintax Engine**: Biến `{PHONE}`, `{ZALO}` / `{ZALO_LINK}` và spin `{A|B|C}` ngay trên thiết bị.
  - **Auto Image Picker**: Tải ảnh → lưu `MediaStore` → bấm "Chọn nhiều file" → chọn từng ảnh với delay OTA-configurable → bấm "Tiếp". Debug Toast hiện từng bước trên màn hình.
  - **Select All / Deselect All**: Nút chọn/bỏ tất cả bài mẫu cho Robot Auto hàng loạt (thay vì tích từng bài một).
  - **FORCE_RUN**: Nút "Chạy Ngay" bypass khung giờ hoạt động và block timeout.
  - **Safety Interceptor**: Quét bố cục màn hình để phát hiện Action Block, Dead Links, Share Sheet/Messenger → tự động Halt bảo vệ tài khoản.

---

## 3. OTA Engine — Tham Số Cấu Hình (v1.3.0)

Tất cả các tham số dưới đây được lưu trên server trong `ENGINE_SCRIPTS` và tải về app qua `/api/engine/script`. **Sửa trên server → Sync từ Server → app nhận cấu hình mới, KHÔNG cần build APK.**

### Anchors (Text Detection)

| OTA Key | Mô tả | Ví dụ |
|---------|-------|-------|
| `wrong_screen` | Phát hiện màn hình lạ (Messenger, Story) | "gửi bằng messenger", "share to story" |
| `block_dialog` | Phát hiện Facebook chặn | "bạn đang tạm thời bị chặn" |
| `group_join` | Nút tham gia nhóm | "tham gia nhóm", "join group" |
| `questionnaire_submit` | Nút gửi bảng câu hỏi nhóm | "gửi", "submit" |
| `dead_link` | Link không khả dụng | "không khả dụng", "content not found" |
| `compose_button` | Ô soạn bài | "bạn viết gì đi", "create post" |
| `post_button` | Nút đăng bài | "đăng", "post" |
| `comment_button` | Ô bình luận | "bình luận", "comment" |
| `send_comment` | Nút gửi comment | "gửi", "send" |
| `photo_button` | Nút thêm ảnh | "ảnh/video", "photo/video" |

### Gallery Config (Photo Picker — OTA v1.3.0+)

| OTA Key | Mô tả | Mặc định |
|---------|-------|----------|
| `gallery_exclude` | Danh sách keyword loại trừ khi tìm ảnh trong gallery. Nếu `contentDescription` chứa bất kỳ keyword nào → bỏ qua node đó. | `["take", "chụp", "camera", "thu gọn", "chọn nhiều", "thêm vào", "collapse", "select multiple", "thư viện", "library", "pictures", "album", "video", "quay lại", "back", "navigate", "bài viết mới", "new post"]` |
| `multi_select_button` | Text nút chuyển sang chế độ chọn nhiều ảnh | `["chọn nhiều file", "chọn nhiều", "select multiple"]` |
| `gallery_next_button` | Text nút "Tiếp" sau khi chọn ảnh | `["next", "tiếp", "done", "xong", "tiếp tục", "hoàn tất"]` |
| `gallery_click_delay` | Delay (ms) giữa mỗi lần chọn ảnh | `800` |

**⚠️ Quan trọng:** Khi bot bấm nhầm nút trong gallery → chỉ cần thêm keyword của nút đó vào `gallery_exclude` trên server → `pm2 restart` → Sync → xong. **KHÔNG cần build APK.**

---

## 4. Cấu Trúc Thư Mục Chi Tiết (Directory Layout)

```
FreeHandFb/
├── app/                                    # Android App (Kotlin + Jetpack Compose)
│   ├── build.gradle.kts                    # Android dependencies & SDK config
│   └── src/main/
│       ├── AndroidManifest.xml             # Permissions & Accessibility declaration
│       └── java/com/example/commenthelper/
│           ├── MainActivity.kt             # ★ UI + Sync + Cloud Settings (Compose)
│           ├── FbAutoService.kt            # ★ Accessibility Engine + OTA Anchors
│           ├── AutoPublishWorker.kt        # ★ Background WorkManager task
│           └── AutoPublishReceiver.kt      # AlarmManager scheduler
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

## 5. Quản Lý Triển Khai & Server (Deployment & SSH)

| Thông tin | Giá trị |
|-----------|---------|
| **Domain/IP** | `dt.ungthien.com` |
| **Port** | `3000` (API & WebApp) |
| **Process Manager** | `PM2` (tên: `C2-Dashboard`) |
| **Đường dẫn code trên VPS** | `/root/server/` |
| **GitHub Repo** | `https://github.com/ngkienmkqn/FreeHandFb.git` (Private) |
| **Branch** | `main` |
| **SSH Key (local)** | `C:\Users\admin\.ssh\id_ed25519_dtvps` |

### Quy trình Deploy lên VPS
```bash
# 1. SSH vào server
ssh -i ~/.ssh/id_ed25519_dtvps root@dt.ungthien.com

# 2. Copy file server từ local
scp -i ~/.ssh/id_ed25519_dtvps server/index.js root@dt.ungthien.com:/root/server/index.js

# 3. Restart service
pm2 restart C2-Dashboard

# 4. Xem logs nếu cần debug
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
1. Lưu nội dung trên vào tệp `~/.ssh/id_ed25519_dtvps`.
2. `chmod 600 ~/.ssh/id_ed25519_dtvps`
3. `ssh -i ~/.ssh/id_ed25519_dtvps root@dt.ungthien.com`

---

## 6. API Reference

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
| GET | `/api/settings` | Cấu hình global (maxGroupPostsPerDay...) |
| GET | `/api/engine/scripts` | Danh sách tất cả phiên bản OTA Script |
| GET | `/api/engine/script?version=xxx` | Tải nội dung Anchors theo phiên bản |

---

## 7. Biến Spintax Trong Bài Viết

| Biến | Thay bằng | Nguồn |
|------|-----------|-------|
| `{PHONE}` | Số điện thoại | Cài đặt → "SĐT của bạn" |
| `{ZALO}` | Link Zalo | Cài đặt → "Link Zalo của bạn" |
| `{ZALO_LINK}` | Giống `{ZALO}` | Alias |
| `{A\|B\|C}` | Random 1 trong A, B, C | Tự động spin mỗi lần đăng |

**⚠️ Phải có dấu `{}` bao quanh!** Ví dụ: `{ZALO_LINK}` ✅, `ZALO_LINK` ❌

---

## 8. Sổ tay Quy trình (Developer Workflows)

### Workflow 1: Facebook đổi tên nút bấm → OTA (KHÔNG BUILD APK)
1. Mở `server/index.js`, tìm object `ENGINE_SCRIPTS`.
2. Tạo phiên bản mới trong `versions` (VD: `"v1.4.0_OTA_VPS": {...}`).
3. Cập nhật trường `latest` trỏ đến version mới.
4. `scp` file lên server → `pm2 restart C2-Dashboard`.
5. Toàn bộ điện thoại nhận script mới khi Sync!

### Workflow 2: Bot bấm nhầm nút trong Gallery → OTA (KHÔNG BUILD APK)
1. Xác định tên nút bị bấm nhầm (từ log hoặc debug toast).
2. Thêm keyword vào `gallery_exclude` trong version mới.
3. Deploy server → `pm2 restart` → Sync → xong!

### Workflow 3: Thay đổi delay chọn ảnh → OTA (KHÔNG BUILD APK)
1. Sửa `gallery_click_delay` trong version mới (ms).
2. Deploy server → `pm2 restart` → Sync.

### Workflow 4: Thay đổi Logic Kotlin → PHẢI BUILD APK
1. Chỉnh sửa `.kt` files trong Android Studio.
2. Build → Generate Signed APK.
3. Gửi file APK cho user cài lại.

### Workflow 5: Push code lên GitHub
```bash
git add -A
git commit -m "feat: mô tả thay đổi"
git push origin main
```

---

## 9. Lịch Sử Phiên Bản OTA

| Version | Ngày | Thay đổi |
|---------|------|----------|
| `v1.0.1_OTA_VPS` | 27/04 | Bản gốc — text anchors cơ bản |
| `v1.1.0_OTA_VPS` | 27/04 | Thêm "gia nhập nhóm" |
| `v1.2.0_OTA_VPS` | 28/04 | Thêm "bạn viết gì đi", "viết bình luận", "write a comment", mở rộng send_comment |
| `v1.3.0_OTA_VPS` | 28/04 | **Gallery OTA**: thêm `gallery_exclude`, `multi_select_button`, `gallery_next_button`, `gallery_click_delay`. Từ giờ fix gallery chỉ cần update server. |

---

## 10. ⚠️ Quy tắc kiến trúc bắt buộc
- **KHÔNG** được dùng `ACTION_SEND` intent vì sẽ kẹt ở màn hình chọn nhóm.
- Cốt lõi công nghệ: nhảy thẳng vào FB App (Katana) rồi cho Accessibility chọc trực tiếp vào `AccessibilityNodeInfo.ACTION_CLICK`. **Bảo tồn cấu trúc này.**
- Mọi text anchor (nút bấm, dialog chặn, v.v.) phải được quản lý qua OTA `Engine` object, **KHÔNG hardcode** trong Kotlin.
- Gallery exclusion list, click delay, multi-select button, next button — tất cả phải qua OTA `Engine`. **KHÔNG hardcode**.
- `AutoPublishWorker` hỗ trợ `FORCE_RUN` input data flag — khi `true`, bypass khung giờ hoạt động và block timeout.
