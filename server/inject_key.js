const { Client } = require('ssh2');

const conn = new Client();
conn.on('ready', () => {
  console.log('Client :: ready');
  const pubKey = 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILTYgTHvKBJmmRnqlRzzavinJacT8pd8Axs04LZiWvef thehuman@DESKTOP-UC5804U';
  conn.exec(`mkdir -p ~/.ssh && chmod 700 ~/.ssh && echo "${pubKey}" >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys`, (err, stream) => {
    if (err) throw err;
    stream.on('close', (code, signal) => {
      console.log('Stream :: close :: code: ' + code + ', signal: ' + signal);
      conn.end();
    }).on('data', (data) => {
      console.log('STDOUT: ' + data);
    }).stderr.on('data', (data) => {
      console.log('STDERR: ' + data);
    });
  });
}).connect({
  host: '103.38.237.40',
  port: 22,
  username: 'root',
  password: 'SVpII9VA',
  readyTimeout: 10000
});
