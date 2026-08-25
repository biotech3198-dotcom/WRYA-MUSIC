with open("/app/applet/app/src/main/java/com/example/ui/components/FullPlayerSheet.kt", "r") as f:
    text = f.read()

# Replace all 'import' with '\nimport' ONLY if it's not already preceded by a newline
import re
text = re.sub(r'([^\n])import ', r'\1\nimport ', text)
text = re.sub(r'([^\n])@Composable', r'\1\n@Composable', text)
text = re.sub(r'([^\n])fun ', r'\1\nfun ', text)
text = re.sub(r'([^\n])val ', r'\1\nval ', text)

with open("/app/applet/app/src/main/java/com/example/ui/components/FullPlayerSheet.kt", "w") as f:
    f.write(text)
