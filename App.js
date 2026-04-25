import React, { useRef, useEffect, useState } from 'react';
import { StyleSheet, Text, View, SafeAreaView, KeyboardAvoidingView } from 'react-native';
import { WebView } from 'react-native-webview';
import io from 'socket.io-client';

const SERVER_URL = 'http://157.66.24.227:3000';

export default function App() {
  const webviewRef = useRef(null);
  const [deviceId] = useState('NODE_' + Math.floor(Math.random() * 10000));
  const [socket, setSocket] = useState(null);
  const [isConnected, setIsConnected] = useState(false);
  const [currentUrl, setCurrentUrl] = useState('https://mbasic.facebook.com');

  useEffect(() => {
    const newSocket = io(SERVER_URL, { transports: ['websocket'] });
    setSocket(newSocket);

    newSocket.on('connect', () => {
      setIsConnected(true);
      newSocket.emit('register_device', { deviceId, deviceName: deviceId });
    });

    newSocket.on('disconnect', () => setIsConnected(false));

    newSocket.on('execute_task', (task) => {
      if (task.url && webviewRef.current) {
        setCurrentUrl(task.url);
        setTimeout(() => {
          if (task.payload_js) {
              let wrappedScript = `(function() { try { ${task.payload_js} } catch (e) { window.ReactNativeWebView.postMessage(JSON.stringify({ type: 'task_result', status: 'error', error: e.toString() })); } })(); true;`;
              webviewRef.current.injectJavaScript(wrappedScript);
          }
        }, 5000); 
      }
    });

    return () => newSocket.close();
  }, []);

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

  const onNavigationStateChange = (navState) => {
    if(navState.url && navState.url !== currentUrl && navState.url.startsWith('http')) {
        setCurrentUrl(navState.url);
    }
  };

  // GIẢI PHÁP TỐI THƯỢNG: Phải Giữ Nguyên Xác Trình Duyệt để Ôm Trọn Cookie (Tránh Loop Login), 
  // Chặn đứng chặn link lạ ở Cửa Vào!
  const onShouldStartLoadWithRequest = (request) => {
    if (!request.url.startsWith('http://') && !request.url.startsWith('https://')) {
        console.log('Blocked dangerous schema drop: ', request.url);
        // Bẻ Lái Âm Thầm trong JavaScript để không làm Rút Phân Cứng Webview, Giữ Mạng sống cho Phiên Đăng Nhập
        if(webviewRef.current) {
            webviewRef.current.injectJavaScript("window.location.href='https://mbasic.facebook.com/home.php'");
        }
        return false;
    }
    return true;
  };

  return (
    <SafeAreaView style={styles.container}>
      <KeyboardAvoidingView behavior="padding" style={{ flex: 1 }}>
        <View style={styles.header}>
          <Text style={styles.headerText}>C2 Farm Node: {deviceId}</Text>
          <Text style={styles.headerSub}>Status: {isConnected ? '✅ Online (Live C2)' : '❌ Offline'}</Text>
        </View>
        <WebView
          ref={webviewRef}
          source={{ uri: currentUrl }}
          style={styles.webview}
          onMessage={onMessage}
          onNavigationStateChange={onNavigationStateChange} 
          onShouldStartLoadWithRequest={onShouldStartLoadWithRequest}
          originWhitelist={['*']}
          userAgent="Mozilla/5.0 (Linux; Android 13; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"
          sharedCookiesEnabled={true}
          thirdPartyCookiesEnabled={true}
          javaScriptEnabled={true}
          domStorageEnabled={true}
          cacheEnabled={true}
          setSupportMultipleWindows={false}
        />
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#121212' },
  header: { padding: 10, backgroundColor: '#001a11', alignItems: 'center' },
  headerText: { color: '#00ff66', fontWeight: 'bold', fontSize: 16, fontFamily: 'monospace' },
  headerSub: { color: '#a0a0a0', fontSize: 12, marginTop: 2, fontFamily: 'monospace' },
  webview: { flex: 1 }
});
