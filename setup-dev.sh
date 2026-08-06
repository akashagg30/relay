#!/bin/bash
# Setup script for Android Agent development
set -e

echo "=== Android Agent Development Setup ==="
echo ""

# Check if we're in the right directory
if [ ! -f "build.gradle.kts" ] || [ ! -f "app/build.gradle.kts" ]; then
    echo "Error: Run this script from the project root directory"
    exit 1
fi

# Make gradlew executable
chmod +x gradlew

# Make check-syntax.sh executable
chmod +x check-syntax.sh

# Install pre-commit hook
echo "Installing pre-commit hook..."
if [ ! -f ".git/hooks/pre-commit" ]; then
    cp .git/hooks/pre-commit .git/hooks/pre-commit 2>/dev/null || true
fi
chmod +x .git/hooks/pre-commit

echo ""
echo "✅ Development setup complete!"
echo ""
echo "Available commands:"
echo "  ./check-syntax.sh    - Run syntax checks"
echo "  ./gradlew lint       - Run Android Lint (requires Android SDK)"
echo "  git commit           - Pre-commit hook will run automatically"
echo ""
echo "Happy coding!"
