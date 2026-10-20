default:
    @just --list

test:
    ./tests/run.sh

apk:
    ./build-apk.sh

clean:
    ./gradlew clean
