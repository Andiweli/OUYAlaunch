#!/bin/sh
# Host-only checks: no APK, emulator, SDK or real Android lifecycle is exercised.
set -eu
cd "$(dirname "$0")/.."
sh tools/run-loading-checks.sh
out="build/host-loading-checks"
javac --release 8 -Xlint:-options -cp "$out" -d "$out" \
  app/src/main/java/com/ast/ouyalaunch/HeaderGeometry.java \
  app/src/main/java/com/ast/ouyalaunch/TabPageMotion.java \
  tools/tests/com/ast/ouyalaunch/GridRowGeometryChecks.java \
  tools/tests/com/ast/ouyalaunch/HeaderGeometryChecks.java \
  tools/tests/com/ast/ouyalaunch/TabPageMotionChecks.java
java -cp "$out" com.ast.ouyalaunch.GridRowGeometryChecks
java -cp "$out" com.ast.ouyalaunch.HeaderGeometryChecks
java -cp "$out" com.ast.ouyalaunch.TabPageMotionChecks
javac -d "$out" tools/checks/JavaSyntaxCheck.java
# shellcheck disable=SC2046
java -cp "$out" JavaSyntaxCheck $(find app/src tools -name '*.java' -print)
