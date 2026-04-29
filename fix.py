import re

file_path = 'app/src/main/java/com/example/commenthelper/FbAutoService.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

content = re.sub(
    r'private fun scheduleNextStep\(delay: Long, action: \(\) \-> Unit\) \{\n\s+nextStepTime = System\.currentTimeMillis\(\) \+ delay\n\s+handler\.postDelayed\(action, delay\)\n\s+\}',
    'private fun setNextStepDelay(delay: Long) {\n        nextStepTime = System.currentTimeMillis() + delay\n    }',
    content
)

content = re.sub(r'scheduleNextStep\((\w+)\) \{ .*? \}', r'setNextStepDelay(\1)', content)
content = content.replace('Step.WAITING_FOR_COMMENT_SENT -> handleWaitingForCommentSent()', 'Step.WAITING_FOR_COMMENT_SENT -> findAndClickSend()')
content = re.sub(r'private fun handleWaitingForCommentSent\(\) \{\n\s+// Handled by findAndClickSend callback\n\s+\}', '', content)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Done")
