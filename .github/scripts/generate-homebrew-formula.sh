#!/bin/bash
set -euo pipefail

# Secure Homebrew formula generation script
# Validates inputs and safely substitutes template placeholders

TEMPLATE_FILE=".github/templates/obsidize.rb.template"
OUTPUT_FILE="Formula/obsidize.rb"

# Validate required environment variables
required_vars=(VERSION REPO ARM64_URL AMD64_URL ARM64_SHA AMD64_SHA)
for var in "${required_vars[@]}"; do
    if [[ -z "${!var:-}" ]]; then
        echo "❌ Required environment variable $var is not set"
        exit 1
    fi
done

# Validate inputs for security
echo "🔒 Validating inputs for security..."

# Version validation
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.-]+)?$ ]]; then
    echo "❌ Invalid version format: $VERSION"
    exit 1
fi

# Repository validation  
if [[ ! "$REPO" =~ ^[a-zA-Z0-9._-]+/[a-zA-Z0-9._-]+$ ]]; then
    echo "❌ Invalid repository format: $REPO"
    exit 1
fi

# URL validation
url_pattern="^https://github\.com/[a-zA-Z0-9._-]+/[a-zA-Z0-9._-]+/releases/download/v[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.-]+)?/[a-zA-Z0-9._-]+\.tar\.gz$"
for url_var in ARM64_URL AMD64_URL; do
    if [[ ! "${!url_var}" =~ $url_pattern ]]; then
        echo "❌ Invalid URL format for $url_var: ${!url_var}"
        exit 1
    fi
done

# SHA validation
sha_pattern="^[a-f0-9]{64}$"
for sha_var in ARM64_SHA AMD64_SHA; do
    if [[ ! "${!sha_var}" =~ $sha_pattern ]]; then
        echo "❌ Invalid SHA256 format for $sha_var: ${!sha_var}"
        exit 1
    fi
done

echo "✅ All inputs validated"

# Check if template exists
if [[ ! -f "$TEMPLATE_FILE" ]]; then
    echo "❌ Template file not found: $TEMPLATE_FILE"
    exit 1
fi

echo "🔧 Generating Homebrew formula from template..."

# Create output directory
mkdir -p "$(dirname "$OUTPUT_FILE")"

# Copy template and perform secure substitutions
cp "$TEMPLATE_FILE" "$OUTPUT_FILE"

# Use sed with delimiter that won't appear in our values
sed -i.bak \
    -e "s|{{REPO}}|${REPO}|g" \
    -e "s|{{VERSION}}|${VERSION}|g" \
    -e "s|{{ARM64_URL}}|${ARM64_URL}|g" \
    -e "s|{{AMD64_URL}}|${AMD64_URL}|g" \
    -e "s|{{ARM64_SHA}}|${ARM64_SHA}|g" \
    -e "s|{{AMD64_SHA}}|${AMD64_SHA}|g" \
    "$OUTPUT_FILE"

# Handle optional Linux section
if [[ -n "${LINUX_URL:-}" && -n "${LINUX_SHA:-}" ]]; then
    echo "📦 Including Linux support"
    
    # Validate Linux URL and SHA
    if [[ ! "$LINUX_URL" =~ $url_pattern ]]; then
        echo "❌ Invalid Linux URL format: $LINUX_URL"
        exit 1
    fi
    if [[ ! "$LINUX_SHA" =~ $sha_pattern ]]; then
        echo "❌ Invalid Linux SHA256 format: $LINUX_SHA"
        exit 1
    fi
    
    # Replace Linux placeholders and enable section
    sed -i.bak2 \
        -e "s|{{LINUX_URL}}|${LINUX_URL}|g" \
        -e "s|{{LINUX_SHA}}|${LINUX_SHA}|g" \
        -e "s|{{#LINUX_ENABLED}}||g" \
        -e "s|{{/LINUX_ENABLED}}||g" \
        "$OUTPUT_FILE"
    rm -f "$OUTPUT_FILE.bak2"
else
    echo "📦 Removing Linux support (not provided)"
    # Remove Linux section entirely
    awk '
        /{{#LINUX_ENABLED}}/ {skip=1; next}
        /{{\/LINUX_ENABLED}}/ {skip=0; next}
        !skip
    ' "$OUTPUT_FILE" > "$OUTPUT_FILE.tmp" && mv "$OUTPUT_FILE.tmp" "$OUTPUT_FILE"
fi

# Clean up backup file
rm -f "$OUTPUT_FILE.bak"

# Validate generated formula
if ! ruby -c "$OUTPUT_FILE" > /dev/null 2>&1; then
    echo "❌ Generated formula has syntax errors"
    exit 1
fi

echo "✅ Homebrew formula generated successfully: $OUTPUT_FILE"

# Show a preview
echo "📄 Formula preview:"
head -20 "$OUTPUT_FILE"
echo "..."