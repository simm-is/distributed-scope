#!/bin/bash
# Browser integration tests for distributed-scope
#
# This script:
# 1. Starts the JVM test server on port 47300
# 2. Compiles the ClojureScript browser tests with shadow-cljs
# 3. Runs the tests in ChromeHeadless via Karma
# 4. Cleans up the server on exit

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

SERVER_PID=""

cleanup() {
    echo "[test-browser.sh] Cleaning up..."
    if [ -n "$SERVER_PID" ]; then
        echo "[test-browser.sh] Stopping server (PID: $SERVER_PID)"
        kill $SERVER_PID 2>/dev/null || true
        wait $SERVER_PID 2>/dev/null || true
    fi
    echo "[test-browser.sh] Cleanup complete"
}

trap cleanup EXIT

echo "[test-browser.sh] =========================================="
echo "[test-browser.sh] Starting browser integration tests"
echo "[test-browser.sh] =========================================="

# Start the JVM test server in background
echo "[test-browser.sh] Starting test server on port 47300..."
clojure -M:browser-server &
SERVER_PID=$!

# Wait for server to be ready
echo "[test-browser.sh] Waiting for server to start..."
sleep 5

# Check if server is running
if ! kill -0 $SERVER_PID 2>/dev/null; then
    echo "[test-browser.sh] ERROR: Server failed to start"
    exit 1
fi

echo "[test-browser.sh] Server is running (PID: $SERVER_PID)"

# Compile ClojureScript tests
echo "[test-browser.sh] Compiling ClojureScript tests..."
npx shadow-cljs compile browser-ci

# Run Karma tests
echo "[test-browser.sh] Running Karma tests..."
npx karma start --single-run

echo "[test-browser.sh] =========================================="
echo "[test-browser.sh] Browser integration tests complete!"
echo "[test-browser.sh] =========================================="
