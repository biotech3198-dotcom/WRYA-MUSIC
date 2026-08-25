with open("/app/applet/app/src/main/java/com/example/ui/components/FullPlayerSheet.kt", "r") as f:
    text = f.read()

# Let's count the number of { and }
open_count = text.count('{')
close_count = text.count('}')

diff = open_count - close_count
print(f"Open: {open_count}, Close: {close_count}, Diff: {diff}")
