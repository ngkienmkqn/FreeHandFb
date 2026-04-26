const io = require('socket.io-client');
const { exec } = require('child_process');

const SERVER_URL = 'http://157.66.24.227:3000';
const DEVICE_ID = 'TERMUX_NODE_' + Math.floor(Math.random() * 1000);

// [CẤU HÌNH TỌA ĐỘ VẬT LÝ] - Sếp đo 1 lần trên điện thoại sếp lúc rảnh rồi điền đây!
const LIKE_X = 150;
const LIKE_Y = 800; // Thay bằng Tọa độ thực trên máy Sếp
const COMMENT_BOX_X = 300;
const COMMENT_BOX_Y = 800;
const SEND_BTN_X = 950;
const SEND_BTN_Y = 500;

console.log(`[*] Khởi động Bản Tối Thượng: Termux C2 Worker...`);
const socket = io(SERVER_URL, { transports: ['websocket'] });

// Hàm luồn quyền gõ lệnh xuống thẳng Lõi Hệ Điều Hành (ADB Local)
function shell(cmd) {
    return new Promise((resolve) => {
        exec(`${cmd}`, (error, stdout, stderr) => {
            if (error) console.log(`[Lỗi Hệ Thống]: ${error.message}`);
            resolve(stdout);
        });
    });
}

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

socket.on('connect', () => {
    console.log(`[+] ĐÃ THÔNG CÁP CẦU NỐI VPS! Máy [${DEVICE_ID}] Rình Lệnh...`);
    socket.emit('register_device', { deviceId: DEVICE_ID, deviceName: 'Termux Native Phone' });
});

socket.on('execute_task', async (task) => {
    console.log(`\n[!] TING TING: Lệnh Auto Like & Cmt Mới! URL: ${task.url}`);
    
    // BƯỚC 1: Gọi Hồn Bật Giao Diện App FACEBOOK THẬT lật tới thẳng Bài Viết
    console.log(`[1] Ép Kích Hoạt App FB Xịn...`);
    await shell(`adb shell am start -a android.intent.action.VIEW -d "${task.url}"`);
    
    // Đếm 4 giây cho FB App Xịn Load Mạng xong 100%
    console.log(`[?] Rình 4 giây cho Bài load xong...`);
    await sleep(4000);

    // BƯỚC 2: Chọt Thả Tim Đỏ
    console.log(`[2] Phập Ngón Tay Ảo Vô Nút Like (${LIKE_X}, ${LIKE_Y})`);
    await shell(`adb shell input tap ${LIKE_X} ${LIKE_Y}`);
    await sleep(1500); // Tránh Thao tác quá nhanh FB ban

    // BƯỚC 3: Bình Luận Thật
    const commentData = "Like tich cuc tu Termux ne Xep"; // Mình lấy cmt tiếng Việt ko dấu để test gọn
    console.log(`[3] Gọi Khung Bình Luận...`);
    await shell(`adb shell input tap ${COMMENT_BOX_X} ${COMMENT_BOX_Y}`);
    await sleep(1000);
    
    console.log(`[4] Điền Chữ: ${commentData}`);
    await shell(`adb shell input text "${commentData.replace(/ /g, '%s')}"`); 
    await sleep(1000);
    
    console.log(`[5] Bấm Nút Gửi Cmt Xong Việc!`);
    await shell(`adb shell input tap ${SEND_BTN_X} ${SEND_BTN_Y}`);

    console.log(`[SUCCESS] Hoàn Thành Lệnh. Rửa Tay Báo Cáo KQ Về Nguồn!`);
    socket.emit('task_complete', { message: 'Đã Nuốt Nát Bài Viết Bằng Native Thành Công 100%!' });
});
