const { chromium } = require('playwright');

// 1. DÁN LINK BÀI VIẾT CỦA ANH VÀO ĐÂY
const TARGET_URL = "https://www.facebook.com/123/posts/456"; 

// 2. NỘI DUNG COMMENT
const COMMENT_TEXT = "Bình luận auto test!";

(async () => {
    // Dùng launchPersistentContext để trình duyệt lưu lại lịch sử đăng nhập.
    // Lần đầu chạy anh sẽ đăng nhập thủ công, các lần sau nó tự nhớ, không cần code quản lý cookie.
    const browser = await chromium.launchPersistentContext('./fb_session_data', {
        headless: false, // Mở tab lên cho anh nhìn nó chạy
        viewport: { width: 400, height: 800 },
        userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 14_7_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/14.1.2 Mobile/15E148 Safari/604.1'
    });
    
    const page = await browser.newPage();

    try {
        console.log(`[+] Đang mở trang: ${TARGET_URL}`);
        
        // Tự động ép link sang giao diện m.facebook.com cho dễ thao tác và ít lỗi
        let finalUrl = TARGET_URL.replace('www.facebook.com', 'm.facebook.com');
        await page.goto(finalUrl, { waitUntil: 'load' });

        // --- BƯỚC 1: CLICK LIKE ---
        const likeButton = page.locator('div[aria-label="Thích"], div[aria-label="Like"], a:text("Thích")').first();
        if (await likeButton.isVisible()) {
            await likeButton.click();
            console.log('[+] Đã nhấn Like!');
            await page.waitForTimeout(2000); // Chờ 2s
        } else {
            console.log('[-] Không thấy nút Like (Có thể anh đã Like sẵn hoặc mạng load chậm)');
        }

        // --- BƯỚC 2: CLICK COMMENT ---
        const commentBox = page.locator('textarea, input[placeholder*="bình luận"], div[aria-label*="bình luận"]').first();
        if (await commentBox.isVisible()) {
            await commentBox.click();
            await page.waitForTimeout(1000);
            
            // Gõ text
            await page.keyboard.type(COMMENT_TEXT, { delay: 100 });
            
            // Ấn Post / Gửi
            const sendBtn = page.locator('div[aria-label="Gửi bình luận"], form button[type="submit"]').first();
            if (await sendBtn.isVisible()) {
                await sendBtn.click();
            } else {
                await page.keyboard.press('Enter');
            }
            console.log(`[+] Đã comment thành công: "${COMMENT_TEXT}"`);
        } else {
            console.log('[-] Không tìm thấy ô để cmt.');
        }

    } catch (err) {
        console.log('[!] Có lỗi xuất hiện:', err.message);
    } finally {
        console.log('[SUCCESS] Đã chạy xong quy trình!');
        // await browser.close(); // Tạm tắt để anh xem lại trình duyệt lúc chạy xong
    }
})();
