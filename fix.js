const fs = require('fs');
const file = 'app/src/main/java/com/example/commenthelper/FbAutoService.kt';
let content = fs.readFileSync(file, 'utf8');

content = content.replace(/handler\.postDelayed\(\{ (handle[A-Za-z0-9]+)\(\) \}, (STEP_DELAY|\d+L?)\)/g, 'scheduleNextStep($2) { $1() }');

content = content.replace(
    'private var nextStepTime = 0L',
    'private var nextStepTime = 0L\n    private fun scheduleNextStep(delay: Long, action: () -> Unit) {\n        nextStepTime = System.currentTimeMillis() + delay\n        handler.postDelayed(action, delay)\n    }'
);

fs.writeFileSync(file, content);
console.log("Done");
