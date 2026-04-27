const fs = require('fs');
const path = require('path');

const postsFile = path.join(__dirname, 'data', 'posts.json');
if (fs.existsSync(postsFile)) {
    let data = fs.readFileSync(postsFile, 'utf8');
    // Regex safely replaces the exact string 123123 with linh
    data = data.replace(/"addedBy":"123123"/g, '"addedBy":"linh"');
    data = data.replace(/123123/g, 'linh'); // Backup generic replace inside completedBy
    fs.writeFileSync(postsFile, data);
    console.log('Migrated posts.json');
}

const usersFile = path.join(__dirname, 'data', 'users.json');
if (fs.existsSync(usersFile)) {
    let data = fs.readFileSync(usersFile, 'utf8');
    data = data.replace(/"username":"123123"/g, '"username":"linh"');
    fs.writeFileSync(usersFile, data);
    console.log('Migrated users.json');
}
