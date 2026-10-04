#!/usr/bin/env bash
# Teste la logique pure (secousse, touches de volume, sons, vibrations) SANS Android : il suffit de Node + Java.
#   bash android/core-test/run.sh
set -e
cd "$(dirname "$0")"
WORK="${TMPDIR:-/tmp}/sentia-kc"
mkdir -p "$WORK" && cd "$WORK"
[ -d node_modules/kotlin-compiler ] || npm install --silent kotlin-compiler
L="$WORK/node_modules/kotlin-compiler/lib"
CP="$L/kotlin-compiler.jar:$L/kotlin-stdlib.jar:$L/kotlin-script-runtime.jar:$L/kotlin-reflect.jar:$L/trove4j.jar:$L/annotations-13.0.jar:$L/kotlin-daemon.jar"
SRC="$(cd "$OLDPWD" && pwd)"
rm -rf out
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$L/kotlin-stdlib.jar" "$SRC"/../app/src/main/java/dj/sentia/core/*.kt "$SRC"/CoreTest.kt -d out 2>&1 | grep -v "Picked up" || true
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "out:$L/kotlin-stdlib.jar" CoreTestKt
