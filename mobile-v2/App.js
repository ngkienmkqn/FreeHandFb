import React, { useState, useRef } from 'react';
import { StyleSheet, Text, View, TextInput, TouchableOpacity, SafeAreaView, Platform } from 'react-native';
import { WebView } from 'react-native-webview';
import io from 'socket.io-client';
import { GENERATE_FB_SCRIPT } from './src/InjectionScripts';

export default function App() {
  // Thay thế bằng IP VPS / WiFi Local của bạn chạy NodeJS Server
  const [ip, setIp] = useState('http://192.168.1.100:3000'); 
  const [connected, setConnected] = useState(false);
  const [socket, setSocket] = useState(null);
  const [url, setUrl] = useState('https://mbasic.facebook.com');
  const [task, setTask] = useState(null);
  
  const webViewRef = useRef(null);

  const connect = () => {
    if (socket) socket.disconnect();
    const s = io(ip);
    
    s.on('connect', () => { 
        setConnected(true); 
        s.emit('register_device', { deviceName: Platform.OS + ' Worker Node' }); 
    });
    
    s.on('disconnect', () => setConnected(false));
    
    // Nhận Lệnh Từ Server !!!
    s.on('execute_task', (data) => {
      setTask(data); // Lưu Task vào State
      // Chuyển WebView nhảy trang sang bài viết chỉ định
      let safeUrl = data.url.replace('www.facebook.com', 'mbasic.facebook.com').replace('m.facebook.com', 'mbasic.facebook.com');
      setUrl(safeUrl);
    });
    
    setSocket(s);
  };

  // Lắng nghe Message báo cáo từ WebView
  const handleMessage = (e) => {
    let msg = JSON.parse(e.nativeEvent.data);
    
    if (msg.type === 'success' && socket) { 
        socket.emit('task_complete', msg); 
        setTask(null); 
        // Xong việc tự quay đầu về trang chủ FB tránh theo dõi
        setUrl('https://mbasic.facebook.com'); 
    }
    if (msg.type === 'error' && socket) { 
        socket.emit('task_error', msg); 
        setTask(null); 
    }
    if (msg.type === 'log' && socket) { 
        socket.emit('task_log', msg); 
    }
  };

  // Kích hoạt tiêm Code ngay khi Webview Vừa loading xong 1 Url bất kỳ
  const handleLoadEnd = (e) => {
    if (task) {
        // Chỉ Inject khi nó đang đứng ở trang bài viết (Chứa /story.php /, /posts/, fk=* ... v.v)
        if (e.nativeEvent.url.includes('/posts/') || e.nativeEvent.url.includes('story_fbid=') || e.nativeEvent.url.includes('/photo.php') || e.nativeEvent.url.includes('fbid=')) {
            // Chích cái Code Logic vào trình duyệt đang mở
            webViewRef.current.injectJavaScript(GENERATE_FB_SCRIPT(task.url, task.comment));
        }
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <TextInput 
            style={styles.input} 
            value={ip} 
            onChangeText={setIp} 
            placeholder="C2 Server IP (http://192...)" 
            autoCapitalize="none"
        />
        <TouchableOpacity style={[styles.btn, connected && styles.btnActive]} onPress={connect}>
          <Text style={{color: 'white', fontWeight: 'bold'}}>
            {connected ? '🟢 ONLINE' : '🔴 CONNECT SERVER'}
          </Text>
        </TouchableOpacity>
      </View>
      <View style={styles.statusBanner}>
          <Text style={{color: 'white', fontSize: 13}}>
              Task Status: {task ? `EXECUTING (${task.url.substring(0,25)}...)` : 'IDLE - WAITING FOR ORDERS'}
          </Text>
      </View>

      <WebView
        ref={webViewRef}
        source={{ uri: url }}
        onMessage={handleMessage}
        onLoadEnd={handleLoadEnd}
        userAgent="Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
        incognito={false} // Lưu ý: React Native WebView tự động xài Storage của App, nên anh login 1 lần nó nhớ mãi
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0f172a', paddingTop: Platform.OS === 'android' ? 30 : 0 },
  header: { flexDirection: 'row', padding: 10, alignItems: 'center' },
  input: { flex: 1, backgroundColor: '#1e293b', color: 'white', padding: 10, borderRadius: 5, marginRight: 10, fontSize: 13 },
  btn: { backgroundColor: '#ef4444', padding: 12, borderRadius: 5 },
  btnActive: { backgroundColor: '#10b981' },
  statusBanner: { paddingLeft: 10, paddingBottom: 10, borderBottomWidth: 1, borderBottomColor: '#334155' }
});
