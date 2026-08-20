#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CACHE_DIR="$SCRIPT_DIR/cache"
OUTPUT_DIR="$SCRIPT_DIR/prebuilt/arm64-v8a"
AAR_FILE="$CACHE_DIR/sherpa-onnx-static-link-onnxruntime-1.13.3.aar"
TARGET_FILE="$OUTPUT_DIR/libsherpa-onnx-jni.so"
MARKER_FILE="$OUTPUT_DIR/.source-sha256"
DEFAULT_URL="https://huggingface.co/csukuangfj2/sherpa-onnx-libs/resolve/86cc7834ba16b3d1cdbc4fe69e362ccdae10a48a/android/aar/1.13.3/sherpa-onnx-static-link-onnxruntime-1.13.3.aar?download=true"
DEFAULT_SHA256="9f065fe6f2cab09fd48eaa580097293e077637ad53a5e89c5c58a36509386ac7"
DEFAULT_SIZE="38398784"
AAR_URL=${DROIDBRIDGE_SHERPA_AAR_URL:-$DEFAULT_URL}
AAR_SHA256=${DROIDBRIDGE_SHERPA_AAR_SHA256:-$DEFAULT_SHA256}
AAR_SIZE=${DROIDBRIDGE_SHERPA_AAR_SIZE:-$DEFAULT_SIZE}

mkdir -p "$CACHE_DIR" "$OUTPUT_DIR"

sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        echo "DroidBridge Verity Daniel: sha256sum or shasum is required." >&2
        exit 1
    fi
}

validate_native() {
    python3 - "$1" <<'PY'
import os, struct, sys
path = sys.argv[1]
try:
    with open(path, "rb") as f:
        h = f.read(20)
    valid = (
        len(h) >= 20
        and h[:4] == b"\x7fELF"
        and h[4] == 2
        and h[5] == 1
        and struct.unpack_from("<H", h, 18)[0] == 183
        and os.path.getsize(path) > 1024 * 1024
    )
except OSError:
    valid = False
sys.exit(0 if valid else 1)
PY
}

if [ -f "$TARGET_FILE" ] && [ -f "$MARKER_FILE" ] \
        && [ "$(cat "$MARKER_FILE" 2>/dev/null || true)" = "$AAR_SHA256" ] \
        && validate_native "$TARGET_FILE"; then
    echo "DroidBridge Verity Daniel: Android ARM64 Sherpa JNI already prepared."
    exit 0
fi

need_download=1
if [ -f "$AAR_FILE" ]; then
    actual_hash=$(sha256_file "$AAR_FILE")
    actual_size=$(wc -c < "$AAR_FILE" | tr -d ' ')
    if [ "$actual_hash" = "$AAR_SHA256" ] && [ "$actual_size" = "$AAR_SIZE" ]; then
        need_download=0
    else
        rm -f "$AAR_FILE"
    fi
fi

if [ "$need_download" -eq 1 ]; then
    tmp="$AAR_FILE.download"
    rm -f "$tmp"
    echo "DroidBridge Verity Daniel: downloading official Sherpa-ONNX 1.13.3 Android AAR..."
    if command -v curl >/dev/null 2>&1; then
        curl --fail --location --silent --show-error --retry 3 --retry-delay 2 --connect-timeout 20 \
            --output "$tmp" "$AAR_URL"
    elif command -v wget >/dev/null 2>&1; then
        wget --tries=3 --timeout=30 --output-document="$tmp" "$AAR_URL"
    else
        echo "DroidBridge Verity Daniel: curl or wget is required for the first build." >&2
        exit 1
    fi

    actual_hash=$(sha256_file "$tmp")
    actual_size=$(wc -c < "$tmp" | tr -d ' ')
    if [ "$actual_hash" != "$AAR_SHA256" ]; then
        rm -f "$tmp"
        echo "DroidBridge Verity Daniel: AAR SHA-256 mismatch: $actual_hash" >&2
        exit 1
    fi
    if [ "$actual_size" != "$AAR_SIZE" ]; then
        rm -f "$tmp"
        echo "DroidBridge Verity Daniel: AAR size mismatch: $actual_size" >&2
        exit 1
    fi
    mv -f "$tmp" "$AAR_FILE"
fi

native_tmp="$TARGET_FILE.download"
rm -f "$native_tmp"
python3 - "$AAR_FILE" "$native_tmp" <<'PY'
import os, sys, zipfile
archive, target = sys.argv[1:3]
with zipfile.ZipFile(archive, "r") as z:
    matches = [
        n for n in z.namelist()
        if n.replace("\\", "/").lower().endswith(
            "/arm64-v8a/libsherpa-onnx-jni.so"
        )
    ]
    if len(matches) != 1:
        raise SystemExit(
            "Expected exactly one arm64-v8a/libsherpa-onnx-jni.so entry; found %d" % len(matches)
        )
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with z.open(matches[0], "r") as src, open(target, "wb") as dst:
        while True:
            chunk = src.read(1024 * 1024)
            if not chunk:
                break
            dst.write(chunk)
PY

if ! validate_native "$native_tmp"; then
    rm -f "$native_tmp"
    echo "DroidBridge Verity Daniel: extracted JNI library is not Android ARM64 ELF." >&2
    exit 1
fi

mv -f "$native_tmp" "$TARGET_FILE"
chmod 0644 "$TARGET_FILE" 2>/dev/null || true
printf '%s' "$AAR_SHA256" > "$MARKER_FILE"
echo "DroidBridge Verity Daniel: official Android ARM64 Sherpa JNI prepared."
