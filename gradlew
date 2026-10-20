#!/bin/sh
# Tiny Unix Gradle launcher. For reproducibility uses Gradle 8.10.2.
# No binary Gradle wrapper JAR needs to be committed.
set -eu
cd "$(dirname "$0")"
if command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
fi
version=8.10.2
cache=${XDG_CACHE_HOME:-$HOME/.cache}/tsk-android
mkdir -p "$cache"
dist="$cache/gradle-$version"
if [ ! -x "$dist/bin/gradle" ]; then
    for cmd in curl unzip; do
        command -v "$cmd" >/dev/null 2>&1 || {
            printf 'Missing %s; install Gradle %s manually.\n' "$cmd" "$version" >&2
            exit 1
        }
    done
    archive="$cache/gradle-$version-bin.zip"
    if [ ! -f "$archive" ]; then
        echo "Downloading Gradle $version..." >&2
        curl --fail --location --show-error --silent --retry 2 \
            "https://services.gradle.org/distributions/gradle-$version-bin.zip" -o "$archive"
    fi
    unzip -tqq "$archive" || { rm -f "$archive"; echo 'Bad Gradle ZIP' >&2; exit 1; }
    unzip -qn "$archive" -d "$cache"
fi
exec "$dist/bin/gradle" "$@"
