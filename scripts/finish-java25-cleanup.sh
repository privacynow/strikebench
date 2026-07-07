#!/usr/bin/env bash
# One-time sudo steps to finish Java 25-only cleanup (Azul Zulu 21 + java_home symlink).
set -euo pipefail

ZULU="/Library/Java/JavaVirtualMachines/zulu-21.jdk"
LINK="/Library/Java/JavaVirtualMachines/openjdk-25.jdk"
SRC="/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk"

if [[ -d "$ZULU" ]]; then
  echo "Removing Azul Zulu 21..."
  rm -rf "$ZULU"
else
  echo "Zulu 21 already removed."
fi

echo "Linking Homebrew JDK 25 for /usr/libexec/java_home..."
ln -sfn "$SRC" "$LINK"

echo "Done. Open a new terminal and run: java -version && /usr/libexec/java_home -V"
