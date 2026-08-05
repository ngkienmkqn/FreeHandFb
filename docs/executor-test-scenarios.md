# Kịch bản kiểm thử Executor

## Quy ước

- A/B: hai tài khoản Facebook khác nhau.
- D1/D2: hai thiết bị Android khác nhau.
- Mọi case phải kiểm tra cả trạng thái DB và `fh_job_events` khi event persistence được bật.

## Tạo và claim job

1. Target hợp lệ tạo đúng số job like/comment.
2. Hai mươi request claim đồng thời chỉ một request nhận mỗi job.
3. Job HIGH được claim trước NORMAL/LOW; cùng priority lấy job cũ nhất.
4. Publishing chỉ được người tạo claim.
5. Account/device nằm trong retry exclusions không claim lại được.
6. Không có executor phù hợp: job giữ QUEUED và target hiển thị WAITING_FOR_EXECUTOR.

## Action và partial success

7. Like CONFIRMED, comment FAILED: job cũ FAILED; replacement chỉ comment.
8. Like ALREADY_DONE, comment CONFIRMED: target tăng đủ hai action.
9. Like FAILED trước thao tác: replacement còn like.
10. Comment checkpoint rồi mất mạng: comment UNCERTAIN, không tự retry.
11. Checkpoint bị server từ chối: Android không bấm Gửi/Đăng.
12. Facebook đã hiển thị comment: Android báo CONFIRMED trước khi complete.

## Failure routing

13. A/D1 bị FACEBOOK_ACTION_BLOCK: A BLOCKED/cooldown; replacement giao B.
14. A chưa join: membership A = NOT_JOINED; replacement giao B hoặc account UNKNOWN.
15. D1 OPEN_FACEBOOK_FAILED: loại D1; A có thể chạy trên D2 nếu binding cho phép.
16. CHECKPOINT_REJECTED: lỗi INFRASTRUCTURE, không tăng fail streak group.
17. TARGET_POST_UNAVAILABLE: không tạo replacement, target NEEDS_REVIEW/CLOSED theo policy.
18. Lỗi GROUP liên tiếp đạt ngưỡng: pause đúng Facebook group, không pause tenant khác.

## Lease và dừng

19. Mất heartbeat trước checkpoint: requeue; executor cũ dừng thao tác.
20. Mất heartbeat sau checkpoint: INTERRUPTED/UNCERTAIN, không requeue.
21. Người dùng Stop trước checkpoint: QUEUED.
22. Người dùng Stop sau checkpoint: INTERRUPTED.
23. Server restart khi RUNNING: khôi phục lease và không tạo claim trùng.

## Target lifecycle

24. PAUSED: không claim job mới; checkpoint mới bị từ chối.
25. CLOSED: cancel QUEUED; giữ lịch sử; xử lý late result rõ ràng.
26. Đủ action CONFIRMED: COMPLETED.
27. Quá maxRuntimeHours: EXPIRED/NEEDS_REVIEW theo cấu hình.
28. Job quá expiresAt: EXPIRED và không claim.
29. Resume group không tự chạy target nếu chưa resume target, trừ khi chọn resumeAllTargets.

## Group và membership

30. Không có Facebook group ID: không áp dụng Group Intelligence.
31. userGroup không được dùng thay Facebook group ID.
32. Account JOINED được ưu tiên; UNKNOWN được dùng khi không còn JOINED phù hợp.
33. Account NOT_JOINED/PENDING/BLOCKED không được claim.
34. Thành công của account B không xóa cooldown/block của A.

## Publishing

35. Tải thiếu ảnh: FAILED retryable trên device khác.
36. Bấm Đăng sau checkpoint rồi không xác nhận được: UNCERTAIN, không đăng lại.
37. Bài chờ duyệt được ghi nhận riêng, không coi như link công khai đã xác minh.
38. scheduledAt tương lai không claim sớm; expiresAt quá hạn không đăng.

## Bảo mật và dữ liệu

39. Lease token sai/đã hết hạn trả 409.
40. Account khác không heartbeat/checkpoint/complete job đang giữ.
41. User tenant khác không xem hoặc claim target/job.
42. Server restart và migration không làm mất actionStates, failure, exclusions, membership.
