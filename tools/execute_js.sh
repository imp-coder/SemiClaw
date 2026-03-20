#!/bin/bash

# Check parameters
if [ -z "${1:-}" ]; then
    echo "Usage: $0 <JS_file_path> [function_name] [parameters_JSON] [env_file_path]"
    echo "Example: $0 example.js"
    echo "Example: $0 example.js main"
    echo "Example: $0 example.js main '{\"name\":\"John\"}'"
    echo "Example: $0 example.js main '{}' .env.local"
    echo "Note: set OPERIT_LOG_WAIT_SECONDS to customize log wait, default is 6 seconds"
    exit 1
fi

# Parameters
FILE_PATH="$1"
if [ -z "${2:-}" ]; then
    FUNCTION_NAME="main"
    PARAMS="{}"
    ENV_FILE_PATH=""
else
    FUNCTION_NAME="$2"
    if [ -z "${3:-}" ]; then
        PARAMS="{}"
        ENV_FILE_PATH="${4:-}"
    else
        PARAMS="$3"
        ENV_FILE_PATH="${4:-}"
    fi
fi

if [ -z "${OPERIT_LOG_WAIT_SECONDS:-}" ]; then
    LOG_WAIT_SECONDS=6
else
    LOG_WAIT_SECONDS="$OPERIT_LOG_WAIT_SECONDS"
fi

# Check if file exists
if [ ! -f "$FILE_PATH" ]; then
    echo "Error: File does not exist - $FILE_PATH"
    exit 1
fi

# Check ADB availability
if ! command -v adb >/dev/null 2>&1; then
    echo "Error: ADB command not found."
    echo "Make sure Android SDK is installed and adb is in PATH"
    exit 1
fi

# Device detection
echo "Checking connected devices..."
mapfile -t DEVICE_LIST < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
DEVICE_COUNT=${#DEVICE_LIST[@]}

if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "Error: No authorized devices found"
    exit 1
fi

# Device selection
if [ "$DEVICE_COUNT" -eq 1 ]; then
    DEVICE_SERIAL="${DEVICE_LIST[0]}"
    echo "Using the only connected device: $DEVICE_SERIAL"
else
    while true; do
        echo "Multiple devices detected:"
        for ((i = 0; i < DEVICE_COUNT; i += 1)); do
            echo "  $((i + 1)). ${DEVICE_LIST[$i]}"
        done
        read -r -p "Select device (1-$DEVICE_COUNT): " CHOICE

        if ! [[ "$CHOICE" =~ ^[0-9]+$ ]]; then
            echo "Invalid input. Numbers only."
            continue
        fi
        if [ "$CHOICE" -lt 1 ]; then
            echo "Number too small"
            continue
        fi
        if [ "$CHOICE" -gt "$DEVICE_COUNT" ]; then
            echo "Number too large"
            continue
        fi

        DEVICE_SERIAL="${DEVICE_LIST[$((CHOICE - 1))]}"
        echo "Selected device: $DEVICE_SERIAL"
        break
    done
fi

# File operations
echo "Creating directory structure..."
TARGET_DIR="/sdcard/Android/data/com.ai.assistance.operit/js_temp"
adb -s "$DEVICE_SERIAL" shell mkdir -p "$TARGET_DIR"
TARGET_FILE="$TARGET_DIR/$(basename "$FILE_PATH")"

echo "Pushing [$FILE_PATH] to device..."
adb -s "$DEVICE_SERIAL" push "$FILE_PATH" "$TARGET_FILE"
if [ $? -ne 0 ]; then
    echo "Error: Failed to push file"
    exit 1
fi

# Resolve env file path
if [ -z "$ENV_FILE_PATH" ]; then
    SCRIPT_DIR="$(dirname "$FILE_PATH")"
    if [ -f "$SCRIPT_DIR/.env.local" ]; then
        ENV_FILE_PATH="$SCRIPT_DIR/.env.local"
    fi
fi

TARGET_ENV_FILE=""
HAS_ENV_FILE=false
if [ -n "$ENV_FILE_PATH" ] && [ -f "$ENV_FILE_PATH" ]; then
    TARGET_ENV_FILE="$TARGET_DIR/$(basename "$ENV_FILE_PATH")"
    echo "Pushing env file [$ENV_FILE_PATH] to device..."
    adb -s "$DEVICE_SERIAL" push "$ENV_FILE_PATH" "$TARGET_ENV_FILE"
    if [ $? -ne 0 ]; then
        echo "Error: Failed to push env file"
        exit 1
    fi
    HAS_ENV_FILE=true
fi

# Escape JSON quotes
ESCAPED_PARAMS="${PARAMS//\"/\\\"}"

# Reset logcat buffer to avoid old logs
adb -s "$DEVICE_SERIAL" logcat -c

# Execute JS function
echo "Executing [$FUNCTION_NAME] with params: $ESCAPED_PARAMS"
if [ "$HAS_ENV_FILE" = "true" ]; then
    adb -s "$DEVICE_SERIAL" shell "am broadcast -a com.ai.assistance.operit.EXECUTE_JS -n com.ai.assistance.operit/.core.tools.javascript.ScriptExecutionReceiver --include-stopped-packages --es file_path '$TARGET_FILE' --es function_name '$FUNCTION_NAME' --es params '$ESCAPED_PARAMS' --es env_file_path '$TARGET_ENV_FILE' --ez temp_file true --ez temp_env_file true"
else
    adb -s "$DEVICE_SERIAL" shell "am broadcast -a com.ai.assistance.operit.EXECUTE_JS -n com.ai.assistance.operit/.core.tools.javascript.ScriptExecutionReceiver --include-stopped-packages --es file_path '$TARGET_FILE' --es function_name '$FUNCTION_NAME' --es params '$ESCAPED_PARAMS' --ez temp_file true"
fi

echo "Waiting ${LOG_WAIT_SECONDS}s for execution and logs..."
sleep "$LOG_WAIT_SECONDS"

echo "Capturing logcat output and exiting..."
adb -s "$DEVICE_SERIAL" logcat -d -s ScriptExecutionReceiver:* JsEngine:*
