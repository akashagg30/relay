#!/bin/bash
# Local syntax checker - runs basic checks without Android SDK
set -e

echo "=== Android Agent Local Syntax Check ==="
echo ""

# Check for common Kotlin syntax issues
echo "Checking Kotlin files for syntax issues..."

ERRORS=0

# Check for unmatched braces
for file in $(find app/src -name "*.kt" 2>/dev/null); do
    OPEN=$(grep -o '{' "$file" | wc -l)
    CLOSE=$(grep -o '}' "$file" | wc -l)
    if [ "$OPEN" -ne "$CLOSE" ]; then
        echo "  ERROR: Unmatched braces in $file (open: $OPEN, close: $CLOSE)"
        ERRORS=$((ERRORS + 1))
    fi
done

# Check for missing imports
echo "Checking for common import issues..."
for file in $(find app/src -name "*.kt" 2>/dev/null); do
    if grep -q "ComponentActivity" "$file" && ! grep -q "import.*ComponentActivity" "$file"; then
        echo "  WARNING: ComponentActivity used without import in $file"
    fi
    if grep -q "@Composable" "$file" && ! grep -q "import.*Composable" "$file"; then
        echo "  WARNING: @Composable used without import in $file"
    fi
done

# Check XML files
echo "Checking XML files..."
for file in $(find app/src -name "*.xml" 2>/dev/null); do
    if command -v xmllint >/dev/null 2>&1; then
        if ! xmllint --noout "$file" 2>/dev/null; then
            echo "  ERROR: Invalid XML in $file"
            ERRORS=$((ERRORS + 1))
        fi
    else
        # Basic XML check - ensure it starts with <?xml and has matching tags
        if ! head -1 "$file" | grep -q "<?xml"; then
            echo "  WARNING: $file may not be valid XML (missing XML declaration)"
        fi
    fi
done

# Check Gradle files
echo "Checking Gradle files..."
if [ -f "build.gradle.kts" ]; then
    echo "  build.gradle.kts exists"
fi
if [ -f "app/build.gradle.kts" ]; then
    echo "  app/build.gradle.kts exists"
fi
if [ -f "settings.gradle.kts" ]; then
    echo "  settings.gradle.kts exists"
fi

echo ""
if [ $ERRORS -gt 0 ]; then
    echo "Found $ERRORS error(s)"
    exit 1
else
    echo "All checks passed!"
fi
