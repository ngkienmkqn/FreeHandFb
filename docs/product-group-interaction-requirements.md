# Yêu cầu sản phẩm: Tương tác bài viết trong Group

> Trạng thái: tài liệu yêu cầu sản phẩm / phương án thiết kế.
> Cập nhật 2026-08-08 — sau Four-Wave Product Hardening (MVP mỏng, Wave 4).

### Shipped (MVP mỏng)

| Item | Ghi chú |
|------|---------|
| `activeHours` | Khung giờ claim theo target (`activeStart`/`activeEnd`); ngoài cửa sổ → không claim |
| `maxRuntimeHours` | `autoClose.maxRuntimeHours` → target `RUNNING` quá hạn chuyển `NEEDS_REVIEW` |
| Prefer joined | Claim ưu tiên tài khoản đã join group (intel); cold start / chưa có joined → cho claim |
| `onlineOnly` | Đã gỡ khỏi UI; server ignore (không build device presence) |
| Ops resolve | Dashboard resolve job `INTERRUPTED`; resume target `NEEDS_REVIEW` → `RUNNING` |
| Speed pacing | Planner gán `scheduledAt` theo SLOW/NORMAL/FAST (`executor-schedule`) |

### Deferred (ngoài MVP mỏng)

| Item | Ghi chú |
|------|---------|
| Campaign | Chiến dịch đa target — explicit non-goal |
| Worker-pool | Nhóm worker / phân bổ pool — explicit non-goal |
| `DRAFT` lifecycle | Trạng thái nháp đầy đủ chưa ship |
| Full online presence | Presence thiết bị thật (thay cho `onlineOnly`) — deferred |

Phần còn lại của tài liệu vẫn là yêu cầu/thiết kế dài hạn; không đồng nghĩa mọi mục đã có code.

## 1. Mục tiêu

Hệ thống cần hỗ trợ tạo nhu cầu tương tác cho từng bài viết trong group Facebook.

Một bài viết không chỉ là một task đơn lẻ, mà là một target có mục tiêu tương tác cụ thể, ví dụ:

- Cần bao nhiêu like.
- Cần bao nhiêu comment.
- Comment phải khác nhau.
- Server tự phân bổ comment cho thiết bị/tài khoản phù hợp.
- App Android chỉ nhận payload cuối cùng và thực thi, không tự chọn nội dung.

## 2. Vai trò các thành phần

### Web

Web là nơi tạo và quản lý yêu cầu tương tác:

- Chọn bài viết cần tương tác.
- Chọn group liên quan.
- Nhập số lượng tương tác cần có.
- Nhập hoặc chọn nhiều comment mẫu.
- Theo dõi tiến độ.
- Tạm ngưng hoặc đóng bài viết khi không cần tương tác nữa.

### Server

Server là nơi lập kế hoạch và phân bổ:

- Tính số lượng job cần tạo.
- Chọn comment khác nhau từ comment pool.
- Chọn thiết bị/tài khoản phù hợp.
- Tránh trùng comment trong cùng bài/group.
- Theo dõi job thành công/thất bại.
- Thu hồi hoặc phân lại job khi cần.

### Android App

App chỉ là executor:

- Nhận một job cụ thể.
- Mở đúng bài viết.
- Thực hiện đúng action được server giao.
- Dùng đúng comment đã được server chọn.
- Báo kết quả về server.
- Không tự quyết định comment, group, quota hoặc số lượng tương tác.

## 3. Mô hình bài viết cần tương tác

Mỗi bài viết target nên có trạng thái và mục tiêu riêng.

Ví dụ:

```text
target_posts
- id: post_001
- post_url: https://facebook.com/...
- group_id: group_001
- status: RUNNING
- like_enabled: true
- like_quantity: 20
- comment_enabled: true
- comment_quantity: 8

comment_pool_items
- target_post_id: post_001
- content: Quan tâm ạ
- content: Mình xin thông tin nhé
- content: Inbox mình với
- content: Bài viết hữu ích quá
```

## 3.1. Quyết định storage

Sản phẩm sẽ dùng PostgreSQL làm nguồn dữ liệu chính.

Không dùng JSON file làm runtime storage cho các dữ liệu nghiệp vụ:

- users;
- devices/workers;
- groups;
- accounts;
- target posts;
- campaigns;
- interaction queue;
- publishing queue;
- comment pools;
- settings nghiệp vụ;
- job history.

JSON cũ, nếu còn tồn tại trong source hiện tại, chỉ được xem là dữ liệu/dev legacy để bỏ hoặc import một lần. Khi triển khai hướng mới, server phải đọc/ghi từ PostgreSQL, không ghi tiếp vào JSON file.

## 4. Số lượng tương tác cần có

Khi tạo yêu cầu cho một bài viết, người dùng phải xác định rõ:

- Bài này cần bao nhiêu like.
- Bài này cần bao nhiêu comment.
- Có cần chạy cả like và comment không.
- Có deadline hoặc khung giờ chạy không.
- Những thiết bị/tài khoản nào được phép tham gia.

Ví dụ:

```text
Bài A trong Group X:
- Like: 20
- Comment: 8
- Chạy trong khung giờ: 09:00 - 22:00
- Dùng nhóm thiết bị: worker_group_01
```

## 5. Comment pool và yêu cầu comment khác nhau

Khi bật tương tác comment, web phải cho chọn hoặc nhập nhiều comment mẫu.

Server sẽ tự phân chia comment cho từng job nhỏ.

Ví dụ:

```text
Comment pool:
1. Quan tâm ạ
2. Mình xin giá nhé
3. Inbox mình thông tin với
4. Bài viết hữu ích quá
5. Đúng thứ mình đang cần
```

Nếu bài cần 8 comment, server sẽ chọn 8 comment phù hợp từ pool và gán cho các thiết bị/tài khoản khác nhau.

Rule đề xuất:

- Không dùng trùng comment trong cùng một bài, nếu đủ comment pool.
- Không dùng cùng một tài khoản comment hai lần vào cùng một bài.
- Không dùng cùng một comment quá gần nhau trong cùng một group.
- Nếu số lượng comment cần chạy lớn hơn số comment mẫu, web phải cảnh báo.

Các chế độ khi thiếu comment mẫu:

```text
1. Không cho tạo nếu thiếu comment.
2. Cho phép lặp comment nhưng phải có xác nhận của admin.
3. Dùng Spintax để sinh thêm biến thể.
```

Khuyến nghị MVP:

```text
Mặc định không cho tạo nếu thiếu comment.
Admin có thể bật "cho phép lặp" nếu thật sự muốn.
```

## 6. Server phân bổ job

Một target post sẽ được server tách thành nhiều job nhỏ trong `interaction_queue`.

Ví dụ:

```text
Bài A cần 20 like và 8 comment
        ↓
Server planner tạo nhiều executor jobs
        ↓
Job 1: account01/device01 like + comment "Quan tâm ạ"
Job 2: account02/device02 like + comment "Mình xin giá nhé"
Job 3: account03/device03 chỉ like
...
```

Payload xuống app phải là payload cuối cùng, không để app tự quyết định:

```text
executor job payload
- type: interaction
- target_post_id: post_001
- group_id: group_001
- post_url: https://facebook.com/...
- action_like: true
- action_comment: true
- comment: Mình xin giá nhé
- assigned_account_id: acc_02
- assigned_device_id: device_02
```

## 7. Mapping theo group

Server cần biết lịch sử trong từng group để phân bổ an toàn hơn:

- Tài khoản nào đã join group.
- Tài khoản nào từng đăng/tương tác trong group.
- Tài khoản nào bị lỗi trong group.
- Comment nào đã dùng trong group.
- Bài nào đã chạy trong group.
- Thiết bị nào đang online và sẵn sàng.

Rule đề xuất:

- Ưu tiên tài khoản đã join group.
- Không giao job group cho tài khoản chưa join, nếu action yêu cầu quyền thành viên.
- Không để một tài khoản comment nhiều lần liên tiếp trong cùng group.
- Không dùng lại comment vừa dùng gần đây trong cùng group.
- Tạm ngưng group nếu fail liên tục.

## 8. Trạng thái bài viết target

Mỗi bài viết target nên có trạng thái riêng:

```text
DRAFT      Nháp, chưa chạy
RUNNING    Đang chạy
PAUSED     Tạm ngưng, có thể chạy tiếp
CLOSED     Đã đóng, không cần tương tác nữa
COMPLETED  Đã đạt đủ mục tiêu
```

## 9. Chế độ đóng bài viết

Cần có tính năng đóng một bài viết khi không cần tương tác nữa.

Đây là thao tác ở cấp target post, không phải tắt toàn bộ chiến dịch.

Khi bài viết được đóng:

- Server không tạo job mới cho bài đó.
- Job đang chờ của bài đó bị hủy hoặc chuyển sang `CANCELED`.
- Job đang chạy xử lý theo checkpoint an toàn.
- Dữ liệu cũ vẫn được giữ để xem báo cáo.
- Không xóa lịch sử tương tác.

Nút trên web:

```text
[ Tạm ngưng ] [ Đóng bài viết ]
```

Confirm khi đóng:

```text
Đóng bài viết này?

Sau khi đóng, hệ thống sẽ không tiếp tục tạo tương tác cho bài này.
Các job đang chờ sẽ bị hủy.
Job đang chạy sẽ dừng tại checkpoint an toàn hoặc hoàn tất nếu đã bấm gửi.

[Hủy] [Đóng bài viết]
```

Thông tin lưu lại:

```text
target_posts
- id: post_001
- status: CLOSED
- closed_at: thời điểm đóng
- closed_by: admin_001
- close_reason: Đã đủ tương tác
```

Lý do đóng đề xuất:

- Đã đủ tương tác.
- Bài không còn phù hợp.
- Bài lỗi hoặc không tìm thấy.
- Group không hiệu quả.
- Đóng thủ công.
- Khác.

## 10. Khác nhau giữa Tạm ngưng và Đóng

```text
Tạm ngưng:
- Không tạo job mới trong thời gian tạm ngưng.
- Có thể giữ job chờ hoặc tạm khóa.
- Có thể bật lại để chạy tiếp.
- Phù hợp khi muốn nghỉ tạm thời.

Đóng:
- Không tạo job mới nữa.
- Hủy job đang chờ.
- Không tự chạy lại.
- Giữ lịch sử để báo cáo.
- Phù hợp khi bài đã đủ tương tác hoặc không cần chạy nữa.
```

## 11. Tiến độ cấp bài viết

Web cần hiển thị tiến độ theo từng bài:

```text
Bài A
Trạng thái: Đang chạy
Like: 12 / 20
Comment: 4 / 8
Đang chạy: 2 job
Đang chờ: 10 job
Thất bại: 1 job
```

Khi bài đóng:

```text
Bài A
Trạng thái: Đã đóng
Like: 18 / 20
Comment: 7 / 8
Lý do đóng: Đã đủ tương tác
Đóng bởi: admin
```

## 12. Xử lý job fail hoặc bị dừng

Nếu job fail, server cần phân biệt:

```text
Chưa bấm Gửi / chưa bấm Like:
- Có thể trả job về queue.
- Có thể giao lại cho thiết bị khác.

Đã bấm Gửi / đã bấm Like:
- Phải xác minh trước.
- Không được tạo job trùng ngay.
- Tránh comment hoặc like lặp.
```

Nếu target post đã bị đóng trong lúc job đang chạy:

- Nếu chưa tới checkpoint cuối, app dừng an toàn và báo `interrupted`.
- Nếu đã thực hiện thao tác cuối, app xác minh và báo kết quả.

## 13. UI tạo yêu cầu tương tác đề xuất

```text
Tạo tương tác cho bài

URL bài viết:
[........................................]

Group:
[ Chọn group hoặc tự nhận diện ]

Số lượng cần chạy:
[✓] Like       Số lượng: [20]
[✓] Comment    Số lượng: [8]

Tốc độ chạy:
( ) Chậm    Rải trong 12 giờ
(●) Vừa     Rải trong 4 giờ
( ) Nhanh   Chạy trong 30 phút

Ưu tiên:
( ) Cao
(●) Thường
( ) Thấp

Thiết bị:
[✓] Chỉ chia cho thiết bị đang online
[ ] Cho phép giữ job chờ thiết bị offline quay lại

Tự đóng:
[✓] Tự đóng khi đủ mục tiêu
[✓] Tự chuyển cần xem lại nếu fail quá nhiều

Comment pool:
[ textarea nhiều dòng ]
Quan tâm ạ
Mình xin giá nhé
Inbox mình thông tin với
Bài viết hữu ích quá

Rule:
[✓] Không lặp comment trong cùng bài
[✓] Không dùng cùng tài khoản comment 2 lần cùng bài
[✓] Ưu tiên tài khoản đã join group
[✓] Chia đều cho thiết bị đang online

[ Tạo yêu cầu ]
```

## 14. Tốc độ chạy

Mỗi bài target nên có chế độ tốc độ chạy để người dùng không phải tự hiểu các tham số kỹ thuật như delay, khoảng nghỉ hoặc số job mỗi giờ.

Các mức đề xuất:

```text
Chậm:
- Rải tương tác trong khoảng 12 giờ.
- Phù hợp bài cần chạy tự nhiên, ít rủi ro.

Vừa:
- Rải tương tác trong khoảng 4 giờ.
- Phù hợp nhu cầu phổ thông.

Nhanh:
- Chạy trong khoảng 30 phút.
- Phù hợp bài cần đẩy nhanh, nhưng phải bị quota kiểm soát chặt hơn.
```

Server sẽ chuyển tốc độ này thành kế hoạch phân bổ job:

- khoảng nghỉ giữa các job;
- số job tối đa mỗi khung giờ;
- số thiết bị/tài khoản được dùng đồng thời;
- mức ưu tiên khi queue đang có nhiều bài.

Ví dụ dữ liệu:

```text
target_posts
- speed: NORMAL
- estimated_duration_minutes: 240
```

## 15. Ưu tiên bài viết

Mỗi bài target nên có mức ưu tiên:

```text
HIGH     Cao
NORMAL   Thường
LOW      Thấp
```

Khi nhiều bài cùng chờ tương tác, server planner dùng priority để quyết định bài nào được phân job trước.

Rule đề xuất:

- Bài ưu tiên cao được claim job trước bài thường/thấp.
- Priority không được vượt qua các giới hạn an toàn như quota tài khoản, quota group, hoặc bài đã đóng.
- Admin có thể đổi priority khi bài đang chạy.
- Việc đổi priority chỉ ảnh hưởng các job mới/chưa claim, không ép app dừng job đang chạy.

Ví dụ:

```text
target_posts
- priority: HIGH
```

## 16. Ngưỡng tự đóng bài viết

Bài target có thể tự đóng hoặc tự hoàn thành khi đạt điều kiện đã cấu hình.

Các điều kiện đề xuất:

- Đủ số like mục tiêu.
- Đủ số comment mục tiêu.
- Chạy quá thời gian cho phép.
- Fail quá số lần cho phép.
- Không còn thiết bị/tài khoản phù hợp để chạy tiếp.

Phân biệt trạng thái:

```text
COMPLETED:
- Bài đã đạt đủ mục tiêu.
- Hệ thống tự kết thúc thành công.

CLOSED:
- Bài bị đóng thủ công hoặc đóng theo rule.
- Có thể chưa đạt đủ mục tiêu.

NEEDS_REVIEW:
- Bài gặp lỗi nhiều lần.
- Cần admin xem lại trước khi chạy tiếp.
```

Ví dụ dữ liệu:

```text
target_posts
- auto_close_enabled: true
- auto_close_when_requirements_met: true
- max_runtime_hours: 24
- max_failed_jobs: 5
```

## 17. Chế độ chỉ dùng thiết bị online hiện tại

Khi tạo yêu cầu tương tác, web nên có lựa chọn:

```text
[✓] Chỉ chia cho thiết bị đang online
[ ] Cho phép giữ job chờ thiết bị offline quay lại
```

Ý nghĩa:

```text
Chỉ thiết bị online:
- Server chỉ phân job cho thiết bị/tài khoản đang sẵn sàng.
- Nếu không đủ thiết bị online, web cảnh báo hoặc chỉ tạo số job có thể chạy.
- Phù hợp khi muốn chạy ngay.

Cho phép thiết bị offline:
- Server có thể giữ job chờ.
- Khi thiết bị quay lại online, job mới được claim.
- Phù hợp chiến dịch dài hoặc không cần chạy ngay.
```

Server cần hiển thị rõ nếu không đủ năng lực chạy:

```text
Cần 8 comment nhưng hiện chỉ có 5 thiết bị/tài khoản phù hợp đang online.
Bạn muốn tạo 5 job trước hay giữ 3 job còn lại chờ thiết bị online?
```

## 18. Đóng bài khác với xóa bài

Sản phẩm phải phân biệt rõ:

```text
Đóng bài:
- Ngừng chạy tương tác cho bài đó.
- Không tạo job mới.
- Hủy hoặc khóa job đang chờ.
- Giữ toàn bộ lịch sử và báo cáo.
- Là thao tác vận hành thường dùng.

Xóa bài:
- Xóa bài khỏi danh sách quản lý.
- Có thể làm mất ngữ cảnh báo cáo nếu xóa cứng.
- Chỉ admin tổng nên được phép dùng.
- Nên hạn chế dùng trong vận hành hằng ngày.
```

Khuyến nghị:

- UI mặc định hiển thị `Đóng bài viết`.
- `Xóa bài` đặt trong khu vực nguy hiểm hoặc chỉ có ở admin tổng.
- Nếu có xóa, ưu tiên soft delete để còn khả năng phục hồi.

## 19. Bài lỗi cần xem lại

Nếu một bài nhiều lần không chạy được, server nên chuyển sang trạng thái:

```text
NEEDS_REVIEW
```

Các trường hợp đưa vào cần xem lại:

- App nhiều lần không tìm thấy bài viết.
- URL lỗi hoặc bài không còn tồn tại.
- Không tìm thấy nút comment/gửi.
- Group không cho tương tác.
- Tài khoản không có quyền xem bài.
- Fail vượt ngưỡng cấu hình.

Khi ở trạng thái `NEEDS_REVIEW`:

- Server không tạo thêm job mới cho bài đó.
- Job đang chờ nên bị khóa lại.
- Web hiển thị lý do cần xem lại.
- Admin có thể sửa URL, đổi group, chạy lại, tạm ngưng hoặc đóng bài.

Ví dụ:

```text
target_posts
- status: NEEDS_REVIEW
- review_reason: TARGET_POST_NOT_FOUND
- failed_count: 6
- last_error: Không tìm thấy bài mục tiêu sau 15 lần cuộn
```

## 20. Tiêu chí đạt

- Một bài viết có thể khai báo số lượng like/comment cần có.
- Comment có thể chọn nhiều mẫu.
- Server tự phân bổ comment cho từng thiết bị/tài khoản.
- App không tự chọn comment.
- Có trạng thái bài viết target.
- Có thể tạm ngưng bài viết.
- Có thể đóng bài viết để không cần tương tác nữa.
- Có thể chọn tốc độ chạy cho từng bài.
- Có thể đặt ưu tiên cho từng bài.
- Có thể tự đóng/tự hoàn thành khi đạt mục tiêu.
- Có thể chỉ dùng thiết bị đang online.
- Phân biệt rõ đóng bài và xóa bài.
- Có trạng thái bài lỗi cần xem lại.
- Bài đã đóng không phát sinh job mới.
- Job chờ của bài đã đóng không tiếp tục chạy.
- Dữ liệu bài đã đóng vẫn còn để báo cáo.

## 21. Nguyên tắc kiến trúc

- Web tạo yêu cầu.
- Server lập kế hoạch và chia job.
- Android chỉ thực thi.
- Không đưa logic chọn comment, chọn quota, chọn group xuống app.
- PostgreSQL là storage chính cho dữ liệu nghiệp vụ.
- Không dùng JSON file làm runtime storage cho queue, task, group, campaign hoặc user.
- Không xóa dữ liệu lịch sử khi đóng bài.
- Không để bài đã đóng tự phát sinh job mới.
