#!/bin/sh

# Get the root of the repo
REPO_ROOT=$(git rev-parse --show-toplevel)
HOOK_SOURCE="$REPO_ROOT/.githooks/pre-commit"
HOOK_DEST="$REPO_ROOT/.git/hooks/pre-commit"

echo "Installing pre-commit hook..."
echo "Source: $HOOK_SOURCE"
echo "Destination: $HOOK_DEST"

if [ -f "$HOOK_SOURCE" ]; then
  cp "$HOOK_SOURCE" "$HOOK_DEST"
  chmod +x "$HOOK_DEST"
  echo "✅ Git pre-commit hook installed and made executable."
else
  echo "❌ Error: $HOOK_SOURCE not found."
  echo "Make sure your pre-commit hook file exists at .githooks/pre-commit"
  exit 1
fi
