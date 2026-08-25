with open("/app/applet/app/src/main/java/com/example/ui/components/FullPlayerSheet.kt", "r") as f:
    text = f.read()

text = text.replace("private fun formatTimeMs", "}\n}\nprivate fun formatTimeMs")

with open("/app/applet/app/src/main/java/com/example/ui/components/FullPlayerSheet.kt", "w") as f:
    f.write(text)
