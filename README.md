# Comment Helper

App Android giúp bạn **quản lý danh sách bài Facebook của bạn** + cmt nhanh bằng template lưu sẵn. Tay bạn vẫn bấm Send — app không tự gửi.

## Tính năng

**Tab "Bài viết":**
- Thêm bài qua nút `+ Thêm` hoặc Share link từ FB → app tự thêm vào list
- Mỗi bài có trạng thái **CHƯA** / **ĐÃ** tương tác
- Filter: Chưa làm / Đã làm / Tất cả + đếm số lượng
- Mỗi row: nút `Comment` (chọn template + mở FB), `Mở FB`, `✓ Đánh dấu xong`, `Xoá`
- Lưu thời điểm đã tương tác

**Tab "Templates":**
- Lưu các câu cmt hay dùng
- Add / xoá tự do

## Flow

1. Đăng bài FB như bình thường.
2. Trên app FB, bấm **Share** ở bài đó → chọn **Comment Helper** → bài tự được thêm vào tab "Bài viết" với trạng thái **CHƯA**.
3. Khi muốn cmt: vào app, tap nút **Comment** ở row của bài đó.
4. Chọn 1 template → app **copy nội dung vào clipboard** + **mở link bài trong FB**.
5. Trong FB: long-press ô bình luận → **Paste** → bấm **Gửi**.
6. Quay lại app, tap **✓ Đánh dấu xong** ở bài vừa cmt → trạng thái chuyển sang **ĐÃ**.

→ Cuối ngày bạn nhìn vào tab "Bài viết" filter "Chưa làm" là biết còn bài nào chưa cmt.

## Không có ở app này

- Không Accessibility Service driving FB app
- Không WebView injection vào facebook.com
- Không tự bấm Like, không tự gõ, không tự bấm Send
- Không multi-account / phone farm

Comment cuối cùng luôn do tay bạn bấm gửi → là comment thật của bạn.

## Lưu trữ

- `SharedPreferences` ở `comment_helper_prefs`
- `posts_v1`: JSON array các bài (id, url, title, status, addedAt, interactedAt, note)
- `templates`: `Set<String>` các câu mẫu

Dữ liệu local 100%, không gửi đâu hết.

## Build

### Cách 1: Push GitHub → tự build (khuyến khích)

Repo này có sẵn workflow `.github/workflows/android-build.yml`. Mỗi lần push lên `main` (hoặc `master`):

1. GitHub Actions tự setup JDK 17 + Gradle 8.5
2. Tự gen `gradlew` nếu chưa có
3. Build debug APK
4. Upload APK lên tab **Actions** → run gần nhất → mục **Artifacts** → tải `comment-helper-debug-apk`

Push lần đầu:

```bash
cd comment-helper
git init
git add .
git commit -m "init: comment helper"
git branch -M main
git remote add origin https://github.com/<you>/<repo>.git
git push -u origin main
```

Mở GitHub repo → tab **Actions** → đợi run xong (~3-5 phút) → tải APK ở **Artifacts**.

Muốn tạo Release kèm APK: tag commit và push tag:

```bash
git tag v0.1.0
git push --tags
```

Workflow tự tạo GitHub Release đính kèm file APK.

### Cách 2: Build local

Cần Android Studio Hedgehog (2023.1+) hoặc JDK 17 + Gradle.

```bash
cd comment-helper
gradle wrapper          # lần đầu để gen gradlew
./gradlew assembleDebug
# APK ở: app/build/outputs/apk/debug/app-debug.apk
```

Cài APK lên máy bằng `adb install` hoặc copy vào điện thoại rồi mở.

## Cấu trúc

```
comment-helper/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        └── java/com/example/commenthelper/MainActivity.kt
```

Toàn bộ logic nằm trong **một file** `MainActivity.kt` (~150 dòng) — đọc và sửa rất nhanh.

## Có thể mở rộng

Mấy thứ này hợp lệ và mình code thêm được nếu cần:

- Lưu template kèm **tag/nhãn** (vd: "tag bố", "tag mẹ") để chọn nhanh.
- **Random 1 trong N** template mỗi lần để cmt không bị lặp y hệt.
- **Auto-fill cmt vào ô** trên `m.facebook.com` *trong WebView của app* (vẫn bạn bấm Send) — nếu bạn OK với việc đăng nhập FB trong WebView của app này.
- **Lịch sử**: lưu các link đã dùng để mở lại nhanh.

Mấy thứ KHÔNG làm:

- Auto-tap "Send" thay bạn (cần Accessibility Service — chính là kỹ thuật bot).
- Hoạt động trên bài **không phải của bạn** với mục đích đẩy tương tác.
- Multi-account / phone farm.
