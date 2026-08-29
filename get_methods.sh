find ~/.gradle/caches/ -name "media3-session-*.aar" -print0 | xargs -0 -n1 unzip -p | grep "DEFAULT_SESSION"
