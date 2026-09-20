#!/bin/sh
# Host algorithm tests only. Android doubles are isolated from app/src and never packaged.
set -eu
cd "$(dirname "$0")/.."
out="build/host-loading-checks"
mkdir -p "$out"
# shellcheck disable=SC2046
javac --release 8 -d "$out" \
  $(find tools/host-stubs -name '*.java' -print) \
  app/src/main/java/com/ast/ouyalaunch/AppEntry.java \
  app/src/main/java/com/ast/ouyalaunch/GridRowGeometry.java \
  app/src/main/java/com/ast/ouyalaunch/PageLoadWindow.java \
  app/src/main/java/com/ast/ouyalaunch/PreviewPngHeader.java \
  app/src/main/java/com/ast/ouyalaunch/BitmapTools.java \
  app/src/main/java/com/ast/ouyalaunch/IconLoader.java \
  tools/tests/com/ast/ouyalaunch/PageLoadingChecks.java \
  tools/tests/com/ast/ouyalaunch/IconLoadingChecks.java \
  tools/tests/com/ast/ouyalaunch/MemoryReleaseChecks.java
java -Djava.awt.headless=true -cp "$out" com.ast.ouyalaunch.PageLoadingChecks
java -Djava.awt.headless=true -cp "$out" com.ast.ouyalaunch.IconLoadingChecks
java -Djava.awt.headless=true -cp "$out" com.ast.ouyalaunch.MemoryReleaseChecks
