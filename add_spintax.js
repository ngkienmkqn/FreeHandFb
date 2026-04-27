const fs = require('fs');
const path = require('path');

const articlesFile = path.join(__dirname, 'data', 'articles.json');
let articles = [];
if (fs.existsSync(articlesFile)) {
    articles = JSON.parse(fs.readFileSync(articlesFile, 'utf8'));
}

const templates = [
  {
    "id": "eco-promo-1",
    "category": "Homestay Ecopark",
    "title": "Sale Căn hộ Ecopark Full Tiện Ích",
    "content": "{Góc pass|Góc nhượng|Cho thuê} {căn 2PN|homestay 2N|căn hộ studio} khu đô thị Ecopark {chỉ từ 600k/đêm|cực xịn giá siêu hời|view hồ thiên nga siêu hot}.\n\n🏡 Tên căn: {Eco Haven|Sol Forest View|Sky Oasis Premium}\n💰 Giá tham khảo: {Chỉ từ 500k/đêm|Giá niêm yết từ 600k|Từ 700k/ngày đêm}\n✨ {Tiện ích bao gồm bể bơi cực xịn, nhà bếp nấu nướng thoải mái.|Không gian check-in sang chảnh, bếp lẩu nướng đầy đủ.|Self check-in bảo mật tuyệt đối, đầy đủ tiện nghi wifi mạnh.}\n\nNhanh tay {chốt đơn|gọi liền|inbox nhé}:\n📞 SĐT: {PHONE} (Zalo: {ZALO})\n\n#{HomestayEcopark|Ecopark|ThueNhaEcopark} #{Homestay|CanHo} #{DuLichHanoi|NghiDuongCuoiTuan}",
    "images": [
      "https://ecopark-city.com.vn/wp-content/uploads/2021/05/homestay-ecopark-1.jpg"
    ]
  },
  {
    "id": "eco-promo-2",
    "category": "Homestay Ecopark",
    "title": "Tìm Khách Thuê Homestay Ecopark",
    "content": "{🔥🔥 Đi trốn khói bụi thành phố thôi|🌿 Bỏ phố về đại công viên xanh|✨ Cuối tuần tụ tập nhóm bạn cực chill}\nBạn đang cần tìm {homestay|nơi lưu trú|căn hộ} tại Ecopark - Văn Giang?\n\n📍 Vị trí: {Toà West Bay view ngút ngàn|Toà Sky Oasis gần phố đi bộ|Khu Aquabay trung tâm tiện ích}\n💰 Giá siêu hạt dẻ chỉ từ {600k|750k|800k} một đêm!\n🛁 {Tặng kèm vé tắm Onsen cho khách lưu trú|Có bể bơi miễn phí, smart tivi, Netflix Netflix max chill|Ban công lộng gió, bếp BBQ đủ dụng cụ siêu đỉnh}.\n\n{Booking ngay hôm nay|Nhắn tin em hỗ trợ 24/7|Chốt lịch liền tay nha}:\n📞 Alo: {PHONE}\n💬 Zalo chốt phòng: {ZALO}\n\n#HomestayEcopark #canhodichvu #ecopark_apartment #dulich",
    "images": [
      "https://toplist.vn/images/800px/ecopark-homestay-256942.jpg"
    ]
  },
  {
    "id": "eco-promo-3",
    "category": "Homestay Ecopark",
    "title": "Homestay Ecopark Nhiều Ưu Đãi",
    "content": "{Tìm khách yomost|Tuyển khách thuê|Góc booking} {HOMESTAY ECOPARK|Căn hộ VIP Ecopark|Homestay view xanh mát}\n{Sạch sẽ - Riêng tư - Thoải mái|An ninh đỉnh cao - Cực kỳ riêng tư - Checkin tự động}!\n- ⏰ Cho thuê đa dạng: {Theo Giờ - Qua Đêm - Ngày - Tuần|Theo Ngày, Qua đêm hoặc theo giờ}\n- 🍽 {Làm BBQ thoải mái tại khu vực ban công rộng rãi|Có bếp xinh xắn thoải mái chuẩn bị mâm cơm gia đình|Trang bị bếp lẩu, nướng sẵn trong phòng}\n- 💵 Giá sinh viên: Niêm yết từ {650.000 VNĐ|550.000đ|700.000 VNĐ} tuỳ thời điểm.\n\n{Liên hệ ngay em nhé|Ib ngay giữ phòng|Nhắn em trước để check phòng trống}:\n📞 Hotline: {PHONE} (ZALO: {ZALO})\n\n#homestayEcopark #thuehomestay #canhotheongay #ecopark",
    "images": [
      "https://dulichkhampha24.com/wp-content/uploads/2020/09/homestay-ecopark-thu-vi.jpg"
    ]
  }
];

templates.forEach(t => {
   t.createdAt = Date.now() + Math.floor(Math.random() * 1000);
   articles.push(t);
});

fs.writeFileSync(articlesFile, JSON.stringify(articles, null, 2));
console.log("Added Spintax Articles!");
