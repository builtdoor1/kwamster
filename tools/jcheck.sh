#!/usr/bin/env bash
# Fast compile check without Gradle: javac the given .java files, pulling any
# referenced project classes from the source roots. Needs build/client-classpath.txt
# (./gradlew writeClientClasspath). Output goes to a throwaway directory.
#   tools/jcheck.sh src/client/java/kwamster/hud/*.java
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d)"
ARGS="$OUT/args.txt"
CP="$(tr '\\' '/' < "$ROOT/build/client-classpath.txt")"
WROOT="$(cygpath -m "$ROOT" 2>/dev/null || echo "$ROOT")"
{
	echo "-cp"
	echo "\"$CP\""
	echo "-sourcepath"
	echo "\"$WROOT/src/client/java;$WROOT/src/main/java\""
} > "$ARGS"
javac --release 21 -proc:none -implicit:class -Xlint:none -nowarn -encoding UTF-8 -d "$OUT/classes" @"$ARGS" "$@"
echo "jcheck OK ($# files)"
rm -rf "$OUT"
