#!/bin/bash

set -euo pipefail

NEWVERNAME=${1:?Canary version name is required}

echo "*$NEWVERNAME* released in canary channel  " > telegram.msg
echo "  " >> telegram.msg
echo "*Changelog:*  " >> telegram.msg
cat changeLog.md >> telegram.msg
echo 'TMessage<<EOF' >> "$GITHUB_ENV"
cat telegram.msg >> "$GITHUB_ENV"
echo 'EOF' >> "$GITHUB_ENV"
