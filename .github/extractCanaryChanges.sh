#!/bin/bash

set -euo pipefail

VERSION_NAME=${1:?Canary version name is required}

awk -v heading="**$VERSION_NAME**" '
  $0 == heading {
    found = 1
    next
  }
  found && /^\*\*canary-[0-9]+\*\*[[:space:]]*$/ {
    exit
  }
  found && /^- / {
    print
    entries++
  }
  END {
    if (!found || !entries) exit 1
  }
' CanaryChangelog.md > changeLog.md
