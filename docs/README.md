# 📱 FreeHand (C2 Auto-Interaction Farm) - Master Documentation

> **LƯU Ý DÀNH CHO AI AGENTS VÀ DEVELOPERS:** Tài liệu này được thiết kế theo cấu trúc Context-Rich nhằm cung cấp toàn bộ bối cảnh hệ thống, cấu trúc thư mục, quy trình deploy và khóa SSH bảo mật. Khi đọc tài liệu này, bạn phải tuân thủ nghiêm ngặt các quy tắc kiến trúc đã được định hình.

## 1. Tổng Quan Dự Án (Project Overview)
- **Tên dự án**: FreeHandFb (Trước đây là Comment Helper)
- **Mục tiêu**: Một hệ thống Command and Control (C2) Phone Farm. Hệ thống này điều phối hàng loạt các thiết bị Android vật lý để thực hiện các thao tác tương tác trên Facebook (Like, Comment, Đăng Bài Group) một cách hoàn toàn tự động, chạy ngầm (Headless) dựa trên `Android Accessibility Services`.
- **Cơ chế an toàn**: Hoàn toàn **KHÔNG** sử dụng WebView, **KHÔNG** cắm API Token, **KHÔNG** dùng DOM Scraping bằng JS. Hệ thống mô phỏng thao tác vuốt chạm vật lý trực tiếp trên ứng dụng Facebook gốc (Katana/Lite) nhằm vượt qua 100% thuật toán chống Bot của Meta.
- **Quản lý mã nguồn (VCS)**: GitHub Private Repository tại `https://github.com/ngkienmkqn/FreeHandFb.git`. Branch chính: `main`. Local path (Google Drive Sync): `g:\Other computers\My Computer\antigravity\FreeHandFb`.
- **Đạt chuẩn Tier 4**: Khả năng Self-Healing bằng Server-Driven Logic thông qua **Rhino JS Engine**.
- **OTA Engine Version**: `Tier 4 (JS Scripts)`

---

## 2. Kiến Trúc Hệ Thống & Tech Stack (Architecture)

```
┌─────────────────────────────────────────────────────────────────┐
│                  VPS Cloud (free.xommuaban.com)                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  server/index.js  (Node.js + Express - Monolith API)     │  │
│  │  ├── REST: /api/login, /api/me, /api/posts, /api/articles│  │
│  │  ├── OTA:  GET /api/engine/script?version=xxx            │  │
│  │  │         POST /api/engine/script (Admin push)          │  │
│  │  ├── Data: server/data/*.json (users, posts, engine.js)  │  │
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
  - **Over-The-Air (OTA) Multi-Version Scripting (Tier 4)**: 
    - Khác với cơ chế tĩnh cũ, OTA Server cung cấp cả JSON Anchors lẫn **Mã nguồn Javascript**.
    - `GET /api/engine/scripts` → Trả về version mới nhất hiện tại trên server.
    - `GET /api/engine/script?version=xxx` → Trả nội dung JSON (chứa `anchors` và `jsCode` nguyên bản) đọc từ `server/data/engine.js`.
    - `POST /api/engine/script` (Admin API) → Push code JS và cấu hình mới thẳng lên bộ nhớ máy chủ và ghi đè file `data/engine.js`.
    - **Khi Facebook đổi giao diện**: Chỉ cần gọi `POST` API (hoặc sửa thẳng file `data/engine.js`) để viết lại hàm xử lý (VD: `interceptWrongScreen`). Toàn bộ máy Android sẽ tự nhận version mới và chạy code JS mới trong phiên cày tiếp theo. KHÔNG cần `pm2 restart` server.
  - **Data Persistence**: Lưu trữ dữ liệu bằng file JSON phẳng (`users.json`, `posts.json`, `articles.json`) tại thư mục `server/data/`. Không dùng database.
  - **Universal Cloud Synchronization**: Cấu hình của app điện thoại (`SharedPreferences` như SĐT, Zalo, Lịch hẹn giờ, Bài viết Spintax đã chọn) được đồng bộ hóa toàn diện vào `user.settings` trên VPS qua `PUT /api/me`. Khi cài lại thiết bị, chế độ **Zero-Touch Recovery** tự động pull `GET /api/me` lấy lại cấu hình.
  - **User-Isolated Logging System**: API `/api/logs/apk` tự động ghi log được phân mảnh theo tên người dùng (`data/logs/<username>_logs.txt`). Admin có thể truy vấn trực tiếp 500 dòng log gần nhất của một user bất kỳ qua `/api/logs/user-<username>`.
  - **Weekly Logs GC (Dọn rác tự động)**: Hệ thống `runLogsCleanup` chạy hằng ngày (an toàn qua restarts nhờ lưu trạng thái `lastLogsCleanup` trong `settings.json`) sẽ tự động xóa các log của người dùng cũ hơn 7 ngày và dọn trống file monolithic `apk_logs.txt`.

### B. Client Node (Native Android App) — Thư mục `app/`
- **Vai trò**: Máy Cày (The Worker). Ứng dụng chạy nền, thu nhận lệnh và thực thi trên điện thoại.
- **Công nghệ**: `Kotlin`, `Jetpack Compose` (UI), `Android AccessibilityService`, `WorkManager`, `AlarmManager`, `Coroutines`.
- **Files chính**:
  - `MainActivity.kt` — Toàn bộ UI Jetpack Compose (Dashboard, Cài đặt, Spintax Composer). Xử lý chu trình `syncWithServer()`, lưu trữ Token, và đồng bộ Cloud.
  - `FbAutoService.kt` — Trái tim của kịch bản Auto. Chứa State Machine phân tích màn hình và singleton `Engine` object nạp Text Anchors + Gallery Config từ OTA Server.
  - `AutoPublishWorker.kt` — Background Worker tự động lấy bài + nhóm random → kích hoạt `FbAutoService` chạy ngầm. Hỗ trợ `FORCE_RUN` flag bypass khung giờ.
  - `AutoPublishReceiver.kt` — BroadcastReceiver lên lịch chu kỳ publish qua `AlarmManager`.
- **Tính năng Cốt lõi**:
  - **Power Management (WakeLock)**: Sử dụng `PowerManager.WakeLock` mức Service để giữ CPU và màn hình luôn thức trong suốt quá trình chạy chuỗi lệnh dài, chống lại chế độ Doze Mode và Screen Timeout của Android khiến app bị "ngủ gật" (Freezing).
  - **Rhino JS Engine (Server-Driven Logic)**: Trái tim của kịch bản Auto giờ được nhúng engine `Rhino`. Khi tải kịch bản mới, App tự động biên dịch (Compile) chuỗi `jsCode` thành môi trường thực thi (Scriptable scope) ngay trong RAM. 
  - **Auto-Hot Reload**: Trước khi bắt đầu xử lý mỗi bài post (hàm `processNextPost`), App chạy ngầm một ping lấy version. Nếu phát hiện code mới trên Server, App tải và nhúng lại Rhino Context, cho phép **sửa lỗi logic tức thời** mà không cần khởi động lại App.
  - **Self-Healing & X-RAY (Tự chữa lành)**: Khi kẹt ở một bước quá 32 giây, hệ thống tự động:
    - Kích hoạt máy quét X-RAY: Chụp toàn bộ 50 phần tử UI (có chứa chữ) trên màn hình và in vào Log để phân tích từ xa.
    - Giao quyền kiểm soát cho các function JS (`callJsFunction`) để tự đưa ra phác đồ điều trị.
    - Giới hạn 3 lần chữa/bài viết. Khôi phục `healingCount` khi chuyển bài mới (ngăn chặn Bug dồn sát thương).
  - **Log Management**: Tự động dọn dẹp `debug_logs.txt` định kỳ (3 ngày/lần vào lúc 3h sáng) và giới hạn dung lượng 2MB. Đồng thời, bộ lọc `isHighValueLog` sẽ lọc bỏ các log thăm dò lặp lại liên tục, chỉ tải lên server các log sự kiện/lỗi thực tế cùng `username` tương ứng một cách bất đồng bộ để phục vụ việc giám sát trực tiếp.
  - **Crash Logging Integration**: Bắt các sự kiện crash ứng dụng chưa được xử lý tại `MainActivity` để thu thập `stackTrace`, đính kèm `username` và gửi trực tiếp tới máy chủ chỉ huy.
  - **OTA Version Selector**: Dropdown trong Settings UI cho phép user chọn phiên bản Script OTA cụ thể (mặc định `latest`).
  - **Spintax Engine**: Biến `{PHONE}`, `{ZALO}` / `{ZALO_LINK}` và spin `{A|B|C}` ngay trên thiết bị.
  - **Auto Image Picker**: Tải ảnh → lưu `MediaStore` → bấm "Chọn nhiều file" → chọn từng ảnh với delay OTA-configurable → bấm "Tiếp". Debug Toast hiện từng bước.
  - **Clipboard Fallback**: Sử dụng cơ chế `ACTION_PASTE` qua `ClipboardManager` làm phương án dự phòng khi Facebook chặn lệnh gõ phím trực tiếp (`ACTION_SET_TEXT`) ở cả ô Đăng bài Group lẫn ô Bình luận.
  - **Select All / Deselect All**: Nút chọn/bỏ tất cả bài mẫu cho Robot Auto hàng loạt.
  - **FORCE_RUN**: Nút "Chạy Ngay" bypass khung giờ hoạt động và block timeout.
  - **Safety Interceptor**: Quét màn hình liên tục mỗi 800ms để phát hiện Action Block, Dead Links, Share Sheet/Messenger → tự động bấm BACK hoặc Halt bảo vệ tài khoản.
  - **Visible Self-Comment Scan (Chống bình luận trùng)**: Tại bước gõ comment, hệ thống sẽ thực hiện quét toàn bộ phần tử văn bản trên màn hình. Nếu phát hiện bình luận của tài khoản Facebook hiện tại (`fbProfileName` học được ở runtime hoặc `facebookName` khai báo), hệ thống sẽ chủ động bỏ qua bước bình luận và lập tức đánh dấu hoàn tất để chuyển sang bài viết khác.

---

## 3. OTA Engine — Tham Số Cấu Hình & Hot-Patching (Tier 4)

Từ Tier 4, cấu trúc OTA đã được nâng cấp lên Server-Driven Logic, được lưu thành file `server/data/engine.json` và `server/data/engine.js`. **Sửa trên server → App tự động nhận cấu hình mới trước khi cày bài post tiếp theo, KHÔNG cần build APK.**

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

### Code Javascript Thực Thi Ngầm (RhinoJS)

Ngoài khai báo JSON, Admin có thể viết hàm Javascript để override cách xử lý của Android, ví dụ:
```javascript
function interceptWrongScreen(nodes, serviceInstance) {
    for (var i = 0; i < nodes.size(); i++) {
        var txt = nodes.get(i).getText() != null ? nodes.get(i).getText().toString().toLowerCase() : "";
        if (txt.indexOf("bạn đang bị chặn") !== -1) {
            return true; // Trả về true báo App đã chặn thành công
        }
    }
    return false; // Trả về false để App dùng logic mặc định
}
```

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
│   │   ├── engine.json                     # OTA Script Metadata (Version, Anchors)
│   │   ├── engine.js                       # OTA Script Raw Javascript logic
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
| **Domain/IP** | `free.xommuaban.com` |
| **Port** | `3000` (API & WebApp) |
| **Process Manager** | `PM2` (tên: `C2-Dashboard`) |
| **Đường dẫn code trên VPS** | `/root/server/` |
| **GitHub Repo** | `https://github.com/ngkienmkqn/FreeHandFb.git` (Private) |
| **Branch** | `main` |
| **SSH Key (local)** | `C:\Users\admin\.ssh\id_ed25519_dtvps` |

### Quy trình Deploy lên VPS
```bash
# 1. SSH vào server
ssh -i ~/.ssh/id_ed25519_dtvps root@free.xommuaban.com

# 2. Copy file server từ local
scp -i ~/.ssh/id_ed25519_dtvps server/index.js root@free.xommuaban.com:/root/server/index.js

# 3. Restart service
pm2 restart C2-Dashboard

# 4. Xem logs nếu cần debug
pm2 logs C2-Dashboard --lines 50
```

### Thông tin chứng thực SSH (Dành cho Cập Nhật tự động)
Để AI Agents hoặc Developer truy cập và deploy code lên máy chủ Cloud `free.xommuaban.com`, sử dụng **Ed25519 Private Key** (quyền root) dưới đây:

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
3. `ssh -i ~/.ssh/id_ed25519_dtvps root@free.xommuaban.com`

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

**⚠️ Lưu ý:** Hiện tại để đảm bảo an toàn vượt bão quét của Facebook, mọi từ khoá liên quan đến `{ZALO_LINK}`, `ZALO_LINK` và `{ZALO}` đều tự động bị **XÓA SẠCH** khỏi bài đăng lúc Auto. Tuyệt đối không dùng Link trong bài đăng, Facebook rất gắt gao khoản này.

---

## 8. Sổ tay Quy trình (Developer Workflows)

### Workflow 1: Facebook đổi tên nút bấm hoặc thay luồng → OTA (KHÔNG BUILD APK)
1. Có thể sử dụng API `POST /api/engine/script` hoặc cập nhật thẳng 2 file `server/data/engine.json` và `server/data/engine.js`.
2. Thay đổi giá trị Anchor hoặc viết lại hàm JS (VD: `interceptWrongScreen`).
3. Tăng trường `version` lên (VD: `v2.0.0`). Server tự động hot-reload cấu hình.
4. App Android sẽ lấy version mới ngay lập tức trước khi tương tác với post tiếp theo và tự động nạp đoạn JS vào RhinoContext. Không cần khởi động lại máy!

### Workflow 2: Cập nhật Gallery Config (KHÔNG BUILD APK)
1. Thêm keyword vào `gallery_exclude` trong file `engine.json`.
2. Tăng version lên, lưu lại. Xong!

### Workflow 4: Thay đổi Logic Kotlin → PHẢI BUILD APK
1. Chỉnh sửa `.kt` files trong Android Studio.
2. Build → Generate Signed APK.
3. Gửi file APK cho user cài lại.

### Workflow 5: Push code lên GitHub (CI/CD Auto-Deploy)
```bash
git add -A
git commit -m "feat: mô tả thay đổi"
git push origin main
```
> **Lưu ý:** Repository đã được tích hợp GitHub Actions. Khi push lên nhánh `main`, server Node.js trên VPS (C2-Dashboard) sẽ tự động được kéo code về và khởi động lại qua PM2 (yêu cầu cấu hình đủ 3 biến Secret VPS_HOST, VPS_USERNAME, VPS_SSH_KEY trên GitHub).

---

## 9. Lịch Sử Phiên Bản OTA

| Version | Ngày | Thay đổi |
|---------|------|----------|
| `v1.0.1_OTA_VPS` | 27/04 | Bản gốc — text anchors cơ bản |
| `v1.1.0_OTA_VPS` | 27/04 | Thêm "gia nhập nhóm" |
| `v1.2.0_OTA_VPS` | 28/04 | Thêm "bạn viết gì đi", "viết bình luận", "write a comment", mở rộng send_comment |
| `v1.3.0_OTA_VPS` | 28/04 | **Gallery OTA**: thêm `gallery_exclude`, `multi_select_button`, `gallery_next_button`, `gallery_click_delay`. Từ giờ fix gallery chỉ cần update server. |
| `Tier 4 (JS OTA)` | 20/05/2026 | **Rhino JS Engine**: Thay vì JSON tĩnh, Server nay cung cấp cả mã nguồn JS. App tự compile trong phiên chạy qua `Engine.load()` giúp xử lý các logic phức tạp và vượt mọi đợt thay đổi của Facebook. |

---

## 10. Lịch Sử Cập Nhật Hệ Thống Cơ Bản

| Ngày | Component | Thay đổi |
|------|-----------|----------|
| **21/05/2026** | Server/App | **Log Phân Mảnh Theo User & Cơ Chế GC Hằng Tuần:** Ghi log theo từng user riêng biệt; tự động dọn dẹp các log cũ hơn 7 ngày hằng tuần; bổ sung API xem trực tiếp log của từng user cho Admin trên web. Android client tự lọc log nhiễu và đính kèm username khi báo crash. |
| **21/05/2026** | App/Server | **Ngăn Chặn Comment Trùng Lặp (Dual-Layer):** Sửa lỗi kiểm tra trạng thái tương tác từ server (chuyển đổi từ `completedBy` sang `interactedBy`); bổ sung kịch bản quét màn hình phát hiện comment của bản thân trước khi tương tác để tự động skip. |
| **02/05/2026** | App/Server | **Chuyển đổi HTTP Polling sang Socket.io Real-time Push:** Đồng bộ trạng thái bài viết ngay lập tức giữa các máy trong cùng nhóm khi có thao tác thêm/xóa/tương tác bài. |
| **02/05/2026** | App | **Sửa lỗi lọc Tab "Cần Giúp":** Bổ sung điều kiện kiểm tra `interactedBy` giúp bài viết lập tức bị ẩn khỏi Tab Cần Giúp với người vừa tương tác, dù trạng thái Global vẫn là PENDING. |
| **02/05/2026** | Server | **Tích hợp CI/CD:** Thêm GitHub Actions (`deploy-server.yml`) cho phép Auto-Deploy lên VPS qua SSH Key khi push code. |

---

## 11. ⚠️ Quy tắc kiến trúc bắt buộc
- **KHÔNG** được dùng `ACTION_SEND` intent vì sẽ kẹt ở màn hình chọn nhóm.
- Cốt lõi công nghệ: nhảy thẳng vào FB App (Katana) rồi cho Accessibility chọc trực tiếp vào `AccessibilityNodeInfo.ACTION_CLICK`. **Bảo tồn cấu trúc này.**
- Mọi text anchor (nút bấm, dialog chặn, v.v.) phải được quản lý qua OTA `Engine` object, **KHÔNG hardcode** trong Kotlin.
- Gallery exclusion list, click delay, multi-select button, next button — tất cả phải qua OTA `Engine`. **KHÔNG hardcode**.
- `AutoPublishWorker` hỗ trợ `FORCE_RUN` input data flag — khi `true`, bypass khung giờ hoạt động và block timeout.
- Hệ thống **Self-Healing** phải có điều kiện fallback (`GLOBAL_ACTION_BACK`) ở nhánh màn hình `UNKNOWN` để đảm bảo luôn có thể xử lý các popup quảng cáo bất ngờ ngoài dự kiến.
- Hệ thống đồng bộ thời gian thực hiện dùng **Socket.io**. Phải bảo toàn `socket.join("group:X")` và `io.to("group:X")` để đảm bảo scope tin nhắn Push chuẩn xác, tránh rò rỉ dữ liệu chéo nhóm.

