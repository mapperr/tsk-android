#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT HUP INT TERM
javac -d "$build" app/src/main/java/io/mapperr/tsk/TaskCodec.java tests/TaskCodecTest.java
java -cp "$build" TaskCodecTest
