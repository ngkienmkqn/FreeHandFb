# FreeHandFb

FreeHandFb là hệ thống điều phối các thiết bị Android thực hiện tác vụ trên ứng dụng Facebook bằng `AccessibilityService`. Hệ thống gồm một máy chủ Node.js, dashboard web và ứng dụng Android đóng vai trò executor.

> Dự án hiện tại đã thay đổi lớn so với phiên bản Comment Helper ban đầu. App không còn chỉ copy comment thủ công; phiên bản hiện tại có khả năng nhận và tự thực thi job tương tác hoặc đăng bài do server phân phối.

## Kiến trúc

```text
Web Dashboard
      │
      ▼
Node.js Server ── PostgreSQL / Executor queues / Socket.IO
      │
      ▼
Android Executor ── AccessibilityService ── Facebook Katana/Lite
```

### Server

Thư mục `server/` chứa backend Node.js + Express và hai giao diện web:

- `server/index.js`: API, xác thực, phân quyền, queue, planner và lưu trữ dữ liệu.
- `server/public/dashboard.html`: dashboard tạo và theo dõi interaction/publishing job.
- `server/public/admin.html`: giao diện quản trị user, nội dung, group, cấu hình, phiên bản app và log.
- `server/data/`: chỉ chứa log và file asset runtime; dữ liệu nghiệp vụ nằm trong PostgreSQL.

### Android

Thư mục `app/` chứa ứng dụng Kotlin/Jetpack Compose:

- `MainActivity.kt`: đăng nhập, lưu phiên và khởi chạy giao diện executor.
- `ExecutorApp.kt`: giao diện hai luồng Tương tác và Đăng bài.
- `ExecutorForegroundService.kt`: claim job, heartbeat, checkpoint và báo kết quả.
- `FbAutoService.kt`: state machine điều khiển Facebook bằng Accessibility.
- `AutoPublishWorker.kt`: worker đăng bài theo lịch của luồng legacy.
- `AutoPublishReceiver.kt`: lên lịch chạy worker bằng AlarmManager.
- `FbUrlHelper.kt`: chuẩn hóa URL Facebook để mở bằng app native.
- `AppConfig.kt`: cấu hình URL máy chủ Android sử dụng.

## Chức năng chính

### 1. Executor tương tác

Một interaction target có thể định nghĩa:

- Link và nội dung nhận diện bài Facebook.
- Số like cần thực hiện.
- Số comment cần thực hiện.
- Comment pool và quy tắc cho phép lặp.
- Độ ưu tiên và tốc độ xử lý.
- Điều kiện tự đóng theo tiến độ, thời gian hoặc số lỗi.

Server tách target thành các job nhỏ. Mỗi Android executor claim một job phù hợp, tìm đúng bài bằng tác giả/nội dung/text anchor rồi thực hiện like và/hoặc comment.

Target hỗ trợ các trạng thái:

- `RUNNING`: đang phân phối job.
- `PAUSED`: tạm dừng.
- `NEEDS_REVIEW`: cần kiểm tra do lỗi liên tiếp.
- `COMPLETED`: đã đạt mục tiêu.
- `CLOSED`: đã đóng thủ công hoặc tự động.

### 2. Executor đăng bài

Publishing job bao gồm:

- Link group.
- Nội dung hoàn chỉnh.
- Danh sách ảnh URL hoặc base64.
- Thời gian hẹn đăng tùy chọn.

Android tải ảnh vào MediaStore, mở group Facebook, điền nội dung, chọn ảnh và đăng. Publishing job chỉ được claim bởi người dùng đã tạo job đó.

### 3. Job lifecycle

Executor queue có cơ chế lease để tránh nhiều thiết bị xử lý cùng một job:

1. Android claim job bằng tài khoản và device ID.
2. Executor gửi heartbeat định kỳ trong lúc chạy.
3. Trước thao tác không thể hoàn tác như bấm Gửi hoặc Đăng, app gửi checkpoint.
4. App báo `complete`, `fail` hoặc `interrupted` khi kết thúc.
5. Job mất heartbeat trước checkpoint được trả lại queue; job mất kết nối sau checkpoint được giữ ở trạng thái cần kiểm tra để tránh chạy trùng.

Hai executor không chạy đồng thời trên cùng một tài khoản vì cả hai đều sử dụng ứng dụng Facebook.

### 4. Group Intelligence

Server lưu thông tin vận hành riêng theo group:

- Tài khoản đã tham gia group.
- Lịch sử job và comment của từng tài khoản.
- Comment vừa được dùng trong group.
- Số lỗi liên tiếp và thời gian tạm ngưng.

Planner sử dụng dữ liệu này để hạn chế comment trùng, tránh giao một target nhiều lần cho cùng tài khoản và tạm dừng group khi lỗi liên tục.

### 5. Accessibility Engine

`FbAutoService` hỗ trợ:

- Mở link bằng Facebook Katana hoặc Facebook Lite.
- Cuộn và tìm đúng bài mục tiêu.
- Like, mở ô comment, nhập và gửi comment.
- Tránh comment trùng khi phát hiện bình luận của chính tài khoản.
- Mở composer group, nhập nội dung và đăng bài.
- Tải/chọn nhiều ảnh trong photo picker.
- Dùng clipboard paste khi `ACTION_SET_TEXT` không hoạt động.
- Phát hiện link chết, màn hình sai, yêu cầu tham gia group và action block.
- Retry, timeout, self-healing và ghi X-RAY cây UI khi bị kẹt.
- Dùng WakeLock trong lúc chạy chuỗi tác vụ.

Service chỉ xử lý accessibility event từ:

- `com.facebook.katana`
- `com.facebook.lite`

### 6. OTA Engine

Server cung cấp phiên bản engine qua:

- `GET /api/engine/scripts`
- `GET /api/engine/script?version=latest`
- `POST /api/engine/script` dành cho admin

OTA payload có thể chứa:

- Text anchor nhận diện UI Facebook.
- Cấu hình photo picker và delay chọn ảnh.
- JavaScript chạy bằng Rhino để bổ sung một số logic self-healing.

Android tải OTA engine khi khởi động và lưu trong `SharedPreferences`.

### 7. Nội dung và tài khoản

Hệ thống còn hỗ trợ:

- User, group, role admin/user và khóa tài khoản.
- Ràng buộc tài khoản worker với một thiết bị Android.
- Comment template riêng theo group và template toàn hệ thống.
- Bài viết mẫu cá nhân hoặc công khai có quy trình admin duyệt.
- Upload nhiều ảnh cho bài mẫu.
- Suggested groups và quy trình duyệt group.
- Đồng bộ số điện thoại, Zalo và settings người dùng.
- Notification, điểm thành viên và bảng xếp hạng.
- Cấu hình splash screen và phiên bản APK mới.
- Log Android theo user và dọn log cũ định kỳ.
- Socket.IO broadcast thay đổi theo từng group.

## API chính

### Authentication và profile

| Method | Endpoint | Chức năng |
|---|---|---|
| `POST` | `/api/login` | Đăng nhập và nhận Bearer token |
| `POST` | `/api/logout` | Thu hồi token hiện tại |
| `GET` | `/api/me` | Lấy profile và cloud settings |
| `PUT` | `/api/me` | Cập nhật profile và settings |

### Interaction target và executor

| Method | Endpoint | Chức năng |
|---|---|---|
| `GET` | `/api/interaction-targets` | Danh sách target và tiến độ |
| `POST` | `/api/interaction-targets` | Tạo target và lập kế hoạch job |
| `PATCH` | `/api/interaction-targets/:id` | Pause/resume hoặc đổi priority |
| `POST` | `/api/interaction-targets/:id/plan` | Lập kế hoạch bổ sung |
| `POST` | `/api/interaction-targets/:id/close` | Đóng target và hủy job đang chờ |
| `GET` | `/api/executor/queues` | Thống kê và danh sách queue |
| `POST` | `/api/executor/interaction` | Tạo interaction job trực tiếp |
| `POST` | `/api/executor/publishing` | Tạo publishing job |
| `POST` | `/api/executor/:type/claim` | Executor claim job |
| `POST` | `/api/executor/jobs/:id/heartbeat` | Gia hạn lease |
| `POST` | `/api/executor/jobs/:id/checkpoint` | Đánh dấu thao tác không thể hoàn tác |
| `POST` | `/api/executor/jobs/:id/complete` | Báo thành công |
| `POST` | `/api/executor/jobs/:id/fail` | Báo thất bại |
| `POST` | `/api/executor/jobs/:id/interrupted` | Báo dừng giữa chừng |

### Nội dung

| Method | Endpoint | Chức năng |
|---|---|---|
| `GET/POST` | `/api/posts` | Lấy hoặc thêm bài viết |
| `POST` | `/api/posts/bulk` | Thêm nhiều link |
| `GET/POST/DELETE` | `/api/templates` | Quản lý comment template |
| `GET/POST` | `/api/articles` | Lấy hoặc đóng góp bài mẫu |
| `PUT/DELETE` | `/api/articles/:id` | Duyệt, sửa hoặc xóa bài mẫu |
| `GET/POST` | `/api/suggested-groups` | Lấy hoặc đề xuất group |

## Cài đặt và chạy server

Yêu cầu Node.js 20+.

```bash
cd server
npm install
npm start
```

Mặc định server lắng nghe tại:

```text
http://0.0.0.0:3030
```

Dashboard chính:

```text
http://localhost:3030/
```

Giao diện admin cũ:

```text
http://localhost:3030/admin.html
```

## Cấu hình Android

Trước khi build APK, cập nhật `SERVER_URL` trong:

```text
app/src/main/java/com/example/commenthelper/AppConfig.kt
```

Ví dụ khi dùng VPS có HTTPS:

```kotlin
const val SERVER_URL = "https://example.com"
```

`127.0.0.1` trên điện thoại trỏ về chính điện thoại, không phải máy tính hoặc VPS. Chỉ dùng địa chỉ này khi server thực sự chạy trên thiết bị hoặc đang cấu hình `adb reverse`.

Build debug APK:

```bash
./gradlew assembleDebug
```

APK được tạo tại:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Yêu cầu build:

- JDK 17.
- Android SDK 34.
- Android 7.0 trở lên (`minSdk 24`).

Sau khi cài APK, người dùng phải bật Accessibility Service cho ứng dụng trước khi khởi chạy executor.

## Lưu trữ dữ liệu

Server sử dụng PostgreSQL cho user, token, bài viết, template, article, settings, target, queue, group intelligence và OTA engine. Không dùng file JSON làm runtime storage.

Cách lưu này phù hợp môi trường nhỏ hoặc thử nghiệm. Khi vận hành nhiều executor đồng thời nên chuyển sang database có transaction và cơ chế migration rõ ràng.

Android lưu token và cấu hình cục bộ trong `SharedPreferences` tên `comment_helper_prefs`.

## Cấu trúc thư mục

```text
FreeHandFb/
├── app/                         # Android Kotlin/Compose executor
│   └── src/main/java/com/example/commenthelper/
│       ├── AppConfig.kt
│       ├── MainActivity.kt
│       ├── ExecutorApp.kt
│       ├── ExecutorForegroundService.kt
│       ├── FbAutoService.kt
│       ├── AutoPublishWorker.kt
│       ├── AutoPublishReceiver.kt
│       └── FbUrlHelper.kt
├── server/
│   ├── index.js                 # Express API + queues + planner
│   ├── public/
│   │   ├── dashboard.html
│   │   ├── admin.html
│   │   └── worker.js
│   └── data/                    # Runtime data, không commit secret
├── docs/
│   ├── README.md
│   └── product-group-interaction-requirements.md
├── scripts/                     # Script test/report tự động
├── build.gradle.kts
└── settings.gradle.kts
```

## Bảo mật và vận hành

- Không commit mật khẩu, Bearer token, private key hoặc file dữ liệu production.
- Không hard-code tài khoản admin mặc định trong mã nguồn production.
- Dùng HTTPS cho toàn bộ kết nối giữa Android và server.
- Thay SHA-256 trực tiếp bằng thuật toán password hashing như Argon2 hoặc bcrypt.
- Yêu cầu xác thực cho API nhận và đọc log.
- Giới hạn kích thước request/upload phù hợp với nhu cầu thực tế.
- Backup PostgreSQL và các file asset/log cần giữ trước khi deploy hoặc migration.
- Kiểm thử trên đúng phiên bản Facebook Katana/Lite đang sử dụng vì accessibility tree có thể thay đổi.

## Trạng thái tài liệu

README này mô tả luồng executor đang được gọi sau màn hình đăng nhập. Một số màn hình và scheduler legacy vẫn còn trong `MainActivity.kt` và `AutoPublishWorker.kt`, nhưng không phải toàn bộ đều được expose trong giao diện executor hiện tại.

Tài liệu chi tiết hơn nằm trong `docs/`, tuy nhiên khi tài liệu và code khác nhau thì mã nguồn hiện tại là nguồn tham chiếu chính.
