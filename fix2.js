const fs = require('fs');
const file = 'app/src/main/java/com/example/commenthelper/FbAutoService.kt';
let content = fs.readFileSync(file, 'utf8');

content = content.replace(/private fun scheduleNextStep\(delay: Long, action: \(\) \-\> Unit\) \{\n\s+nextStepTime = System\.currentTimeMillis\(\) \+ delay\n\s+handler\.postDelayed\(action, delay\)\n\s+\}/, 'private fun setNextStepDelay(delay: Long) {\n        nextStepTime = System.currentTimeMillis() + delay\n    }');

content = content.replace(/scheduleNextStep\((\w+)\) \{ .*? \}/g, 'setNextStepDelay($1)');
content = content.replace('Step.WAITING_FOR_COMMENT_SENT -> handleWaitingForCommentSent()', 'Step.WAITING_FOR_COMMENT_SENT -> findAndClickSend()');
content = content.replace(/private fun handleWaitingForCommentSent\(\) \{\n\s+\/\/ Handled by findAndClickSend callback\n\s+\}/, '');

fs.writeFileSync(file, content);
console.log("Done");
