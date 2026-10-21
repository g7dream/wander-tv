#!/usr/bin/env bash
# Runs the Gradle wrapper with this machine's JDK + Android SDK. Usage: tools/gw.sh :app:assembleDebug
# Override JAVA_HOME / ANDROID_HOME in your environment if yours live elsewhere.
export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/openjdk/jdk-21.0.8}"
export ANDROID_HOME="${ANDROID_HOME:-D:/UnityHub/Editor/6000.4.7f1/Editor/Data/PlaybackEngines/AndroidPlayer/SDK}"
cd "$(dirname "$0")/.." && exec ./gradlew "$@"
