#!/bin/sh
# Host-only editor sizing checks. An Android SDK / Robolectric is needed to test actual layout.
set -eu
cd "$(dirname "$0")/.."
out="build/host-dialog-checks"
mkdir -p "$out"
javac --release 8 -d "$out" \
  app/src/main/java/com/ast/ouyalaunch/CategoryDialogSizing.java \
  tools/tests/com/ast/ouyalaunch/CategoryDialogSizingChecks.java
java -cp "$out" com.ast.ouyalaunch.CategoryDialogSizingChecks
