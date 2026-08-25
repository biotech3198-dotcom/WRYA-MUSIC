with open("/app/applet/app/src/main/java/com/example/ui/components/FullPlayerSheet.kt", "r") as f:
    text = f.read()

text = text.replace('package com.example.ui.components', 'package com.example.ui.components\n')
text = text.replace('import androidx', '\nimport androidx')
text = text.replace('import com.example', '\nimport com.example')
text = text.replace('import java.', '\nimport java.')
text = text.replace('import coil.', '\nimport coil.')

with open("/app/applet/app/src/main/java/com/example/ui/components/FullPlayerSheet.kt", "w") as f:
    f.write(text)
