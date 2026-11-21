#!/usr/bin/env bash
# Downloads the latest nodejs-mobile Android build and lays it out as:
#   app/libnode/bin/<abi>/libnode.so
#   app/libnode/include/node/*.h
# The build works without it (the embedded-Node feature is simply compiled out).
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/libnode"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "[node] downloading latest nodejs-mobile Android release..."
if ! gh release download --repo digidem/nodejs-mobile \
      --pattern 'nodejs-mobile-android-[0-9]*.zip' --dir "$TMP"; then
  echo "[node] download failed; building WITHOUT embedded Node."
  exit 0
fi

ZIP="$(ls "$TMP"/nodejs-mobile-android-*.zip 2>/dev/null | head -n1)"
[ -z "$ZIP" ] && { echo "[node] no zip found; skipping."; exit 0; }
echo "[node] using $(basename "$ZIP")"
unzip -q "$ZIP" -d "$TMP/x"

rm -rf "$OUT"; mkdir -p "$OUT/bin" "$OUT/include"

# Copy every libnode.so under its ABI directory name (arm64-v8a, armeabi-v7a, x86_64).
while IFS= read -r so; do
  abi="$(basename "$(dirname "$so")")"
  mkdir -p "$OUT/bin/$abi"
  cp "$so" "$OUT/bin/$abi/libnode.so"
  echo "[node] found $abi"
done < <(find "$TMP/x" -name libnode.so)

# Locate node.h and copy the include directory.
NODE_H="$(find "$TMP/x" -name node.h | head -n1)"
if [ -n "$NODE_H" ]; then
  mkdir -p "$OUT/include/node"
  cp -r "$(dirname "$NODE_H")/." "$OUT/include/node/"
fi

if [ ! -f "$OUT/bin/arm64-v8a/libnode.so" ] || [ ! -f "$OUT/include/node/node.h" ]; then
  echo "[node] unexpected archive layout; building WITHOUT embedded Node."
  rm -rf "$OUT"
fi
exit 0
