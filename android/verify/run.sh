#!/bin/sh
# Exercises LoadStore — the counting/persistence logic the app, the widget and
# the geofence receiver all share — on a plain JVM. Needs only a JDK and network
# for the first run (jars are cached in .cache/), NOT the Android SDK.
set -e
cd "$(dirname "$0")"

CACHE=.cache
M2=https://repo1.maven.org/maven2
mkdir -p "$CACHE"

fetch() { # url, filename
  [ -f "$CACHE/$2" ] || { echo "fetching $2"; curl -sSLo "$CACHE/$2" "$1"; }
}

fetch "$M2/org/jetbrains/kotlin/kotlin-compiler-embeddable/1.9.24/kotlin-compiler-embeddable-1.9.24.jar" kotlin-compiler-embeddable.jar
fetch "$M2/org/jetbrains/kotlin/kotlin-stdlib/1.9.24/kotlin-stdlib-1.9.24.jar" kotlin-stdlib.jar
fetch "$M2/org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar" trove4j.jar
fetch "$M2/org/jetbrains/annotations/24.1.0/annotations-24.1.0.jar" annotations.jar
fetch "$M2/org/json/json/20240303/json-20240303.jar" json.jar

rm -rf "$CACHE/out" "$CACHE/stubs"
mkdir -p "$CACHE/out" "$CACHE/stubs"

# in-memory stand-ins for the two Android classes LoadStore touches
javac -nowarn -d "$CACHE/stubs" $(find stubs -name '*.java')

KOTLINC="$CACHE/kotlin-compiler-embeddable.jar:$CACHE/kotlin-stdlib.jar:$CACHE/trove4j.jar:$CACHE/annotations.jar"
CP="$CACHE/kotlin-stdlib.jar:$CACHE/json.jar:$CACHE/stubs"

java -cp "$KOTLINC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -nowarn -cp "$CP" -d "$CACHE/out" \
  ../app/src/main/java/com/farmerboy/silageloads/LoadStore.kt StoreTest.kt

java -cp "$CP:$CACHE/out" StoreTest
