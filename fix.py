import re

with open("/app/applet/app/src/main/java/com/example/ui/components/FullPlayerSheet.kt", "r") as f:
    text = f.read()
    
# Fix missing newlines after imports
text = re.sub(r'package com.example.ui.components.*?(?=import)', 'package com.example.ui.components\n\n', text, flags=re.DOTALL)
text = text.replace('import', '\nimport')

with open("/app/applet/app/src/main/java/com/example/ui/components/FullPlayerSheet.kt", "w") as f:
    f.write(text)
