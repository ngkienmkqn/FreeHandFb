import requests
from bs4 import BeautifulSoup
import time

# 1. ĐIỀN LINK BÀI VIẾT (Ví dụ: https://www.facebook.com/123/posts/456)
TARGET_URL = "https://www.facebook.com/Zuck/posts/12345"

# 2. LẤY COOKIE FACEBOOK CỦA NICK ANH DÁN VÀO ĐÂY
# (Dùng Kiwi Browser hoặc Lemur trên điện thoại -> Vào mbasic.facebook.com -> Copy toàn bộ Cookie)
COOKIE_STRING = "c_user=...; xs=...; fr=...; datr=..."

# 3. NỘI DUNG COMMENT
COMMENT_TEXT = "Test tự động bằng Termux/Pydroid!"

def run_bot():
    print(f"[*] Đang tải bài viết: {TARGET_URL}")
    
    # Ép link về mbasic để xử lý HTML thuần (Vừa lách được bot chặn, vừa siêu nhẹ cho điện thoại)
    url = TARGET_URL.replace("www.facebook.com", "mbasic.facebook.com").replace("m.facebook.com", "mbasic.facebook.com")
    
    headers = {
        "User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36",
        "Cookie": COOKIE_STRING,
        "Accept-Language": "vi-VN,vi;q=0.9"
    }
    
    session = requests.Session()
    session.headers.update(headers)

    try:
        res = session.get(url)
        soup = BeautifulSoup(res.text, "html.parser")
        
        # --- BƯỚC 1: TÌM VÀ BẤM LIKE ---
        like_link = None
        # Quét tìm thẻ link dẫn tới hành động /a/like.php
        for a in soup.find_all('a', href=True):
            if '/a/like.php' in a['href']:
                like_link = a['href']
                break
                
        if like_link:
            print("[+] Tìm thấy nút Like, đang nhấn...")
            # FB trả về link tương đối (có thể chứa /)
            full_like_url = "https://mbasic.facebook.com" + like_link if str(like_link).startswith('/') else like_link
            session.get(full_like_url)
            print("[+] Đã Like xong!")
            time.sleep(2)
        else:
            print("[-] Không thấy nút Like (Anh đã like rồi hoặc link lỗi).")

        # --- BƯỚC 2: TÌM FORM VÀ SUBMIT COMMENT ---
        # Tìm form ẩn chứa biến action /a/comment.php
        form = soup.find('form', action=lambda x: x and '/a/comment.php' in x)
        if form:
            print("[+] Đang ghép dữ liệu để gửi comment...")
            action_url = "https://mbasic.facebook.com" + form['action']
            
            # Cào toàn bộ token bảo mật ẩn (bắt buộc) của Facebook: fb_dtsg, jazoest...
            payload = {}
            for inp in form.find_all('input', type='hidden'):
                payload[inp.get('name')] = inp.get('value', '')
                
            # Điền text vào ô input comment
            comment_box = form.find('textarea') or form.find('input', type='text')
            if comment_box and comment_box.get('name'):
                payload[comment_box.get('name')] = COMMENT_TEXT
                
            # Submit y như trình duyệt
            session.post(action_url, data=payload)
            print(f"[SUCCESS] Đã gửi comment: '{COMMENT_TEXT}'")
        else:
            print("[-] Không tìm thấy chỗ để viết comment!")

    except Exception as e:
        print("[!] Lỗi:", str(e))

if __name__ == "__main__":
    run_bot()
