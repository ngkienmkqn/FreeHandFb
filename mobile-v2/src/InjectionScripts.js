export const GENERATE_FB_SCRIPT = (targetUrl, commentText) => {
    return `
        (function() {
            // Helper để gửi thông báo xuyên Webview ra Native React Code
            const log = (msg) => window.ReactNativeWebView.postMessage(JSON.stringify({ type: 'log', message: msg }));
            const finish = (msg) => window.ReactNativeWebView.postMessage(JSON.stringify({ type: 'success', message: msg }));
            const fail = (msg) => window.ReactNativeWebView.postMessage(JSON.stringify({ type: 'error', error: msg }));

            try {
                log('DOM Injected. Checking available buttons...');
                
                // Đợi 1.5s cho chắc chắn DOM tải hết
                setTimeout(() => {
                    // == 1. TRIỂN KHAI LIKE ==
                    // Mbasic fb lưu action Like trong các thẻ a chứa /a/like.php
                    const likeBtns = Array.from(document.querySelectorAll('a[href*="/a/like.php"]'));
                    if (likeBtns.length > 0) {
                        const likeUrl = likeBtns[0].href;
                        // Gọi Ajax ngầm thay vì click để tránh load lại trang rườm rà.
                        fetch(likeUrl)
                            .then(() => log('-> Like command executed over internal Fetch.'))
                            .catch(e => log('Warning Fetching Like: ' + e));
                    } else {
                        log('-> No Like button found. Proceeding...');
                    }

                    // == 2. TRIỂN KHAI BÌNH LUẬN ==
                    // Đợi thêm tí xử lý cho nuột
                    setTimeout(() => {
                        const form = document.querySelector('form[action*="/a/comment.php"]');
                        if (form) {
                            const input = form.querySelector('textarea, input[type="text"]');
                            if (input && '${commentText}' !== '') { // Bỏ qua nếu không gõ Comment
                                input.value = '${commentText}';
                                log('-> Form localized & populated with content.');
                                
                                // Submit Form
                                const formData = new FormData(form);
                                fetch(form.action, { method: 'POST', body: formData })
                                    .then(() => finish('Mission Accomplished (Like + Comment) !'))
                                    .catch(e => fail('Network Error during Comment post: ' + e));
                            } else {
                                finish('Mission Accomplished (Like Only).');
                            }
                        } else {
                            fail('Could not identify Comment Form.');
                        }
                    }, 1000);

                }, 1500);
            } catch(e) {
                fail('Injection Logic Crashed: ' + e.message);
            }
        })();
    `;
};
