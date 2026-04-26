import React, { useRef, useEffect, useState, useMemo } from 'react';
import { StyleSheet, Text, View, SafeAreaView } from 'react-native';
import { WebView } from 'react-native-webview';
import io from 'socket.io-client';

// Kết nối về C2 Master Server trên Máy Tính thông qua loopback của Giả Lập BlueStacks (10.0.2.2 là mã Cửa hậu vào thẳng Ổ Máy Sếp)
const SERVER_URL = 'http://10.0.2.2:3000';

export default function App() {
  const webviewRef = useRef(null);
  const [deviceId] = useState('NODE_' + Math.floor(Math.random() * 1000));
  const [socket, setSocket] = useState(null);
  const [isConnected, setIsConnected] = useState(false);

  const sourceUri = useMemo(() => ({ uri: 'https://mbasic.facebook.com' }), []);

  useEffect(() => {
    // Ép tạo WebSocket thay vì dùng Polling cho tốc độ chớp nhoáng
    const newSocket = io(SERVER_URL, { transports: ['websocket'] });
    setSocket(newSocket);

    newSocket.on('connect', () => {
      setIsConnected(true);
      newSocket.emit('register_device', { deviceId, deviceName: 'Expo-BlueStacks' });
    });

    newSocket.on('disconnect', () => setIsConnected(false));

    newSocket.on('execute_task', (task) => {
      if (task.url && webviewRef.current) {
        // Lệnh 1: Bẻ Lái Sang Link Mục Tiêu
        webviewRef.current.injectJavaScript(`window.location.href = "${task.url}"; true;`);
        
        // Lệnh 2: Bắn Payload Sau Khi Chuyển Trang (Tránh Lỗi Trắng DOM)
        setTimeout(() => {
          if (task.payload_js) {
              let wrappedScript = `(function() { try { ${task.payload_js} } catch (e) { window.ReactNativeWebView.postMessage(JSON.stringify({ type: 'task_result', status: 'error', error: e.toString() })); } })(); true;`;
              webviewRef.current.injectJavaScript(wrappedScript);
          }
        }, 5000); 
      }
    });

    return () => newSocket.close();
  }, [deviceId]);

  const onMessage = (event) => {
    try {
        const data = JSON.parse(event.nativeEvent.data);
        if (data.type === 'task_result' && socket) {
            if (data.status === 'log') socket.emit('task_log', { message: data.message });
            else if (data.status === 'success') socket.emit('task_complete', { message: data.message });
            else if (data.status === 'error') socket.emit('task_error', { error: data.error });
        }
    } catch (e) {}
  };

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.headerText}>FreeHandFb C2: {deviceId}</Text>
        <Text style={styles.headerSub}>{isConnected ? '✅ BĂNG THÔNG ĐÃ MỞ RỘNG' : '❌ Đang Nối Cáp Máy Chủ C2...'}</Text>
      </View>
      <WebView
        ref={webviewRef}
        source={sourceUri}
        style={styles.webview}
        onMessage={onMessage}
        originWhitelist={['*']}
        // UA Cực Cứng Ép Facebook Phải Nhận Diện Đây Là Điện Thoại Thật 100% Khắc Phục Lỗi Drop Session Đăng Nhập Cũ!
        userAgent="Mozilla/5.0 (Linux; Android 13; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36"
        sharedCookiesEnabled={true}
        thirdPartyCookiesEnabled={true}
        javaScriptEnabled={true}
        domStorageEnabled={true}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#111' },
  header: { padding: 12, backgroundColor: '#0a0a0a', alignItems: 'center', borderBottomWidth: 1, borderBottomColor: '#222' },
  headerText: { color: '#00ffcc', fontWeight: 'bold', fontSize: 18, fontFamily: 'sans-serif-medium' },
  headerSub: { color: '#888', fontSize: 13, marginTop: 4, fontStyle: 'italic' },
  webview: { flex: 1, backgroundColor: '#fff' }
});
