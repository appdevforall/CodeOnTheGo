#!/bin/bash

# Fast test runner for Android IDE tests

# Find the project root directory (where gradlew is located)
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$SCRIPT_DIR/.."

# Change to the project root directory
cd "$PROJECT_ROOT" || exit

# Determine the appropriate Gradle wrapper to use based on the OS
if [[ "$OSTYPE" == "msys" || "$OSTYPE" == "win32" || "$OSTYPE" == "cygwin" ]]; then
    GRADLE_WRAPPER="./gradlew.bat"
else
    GRADLE_WRAPPER="./gradlew"
fi

# Select specific test classes to run
$GRADLE_WRAPPER connectedAndroidTest -PandroidTest.useTestOrchestrator=true \
  -PandroidTest.failOnDeviceUnavailable=false \
  -Pandroid.testInstrumentationRunnerArguments.class=com.itsaky.androidide.OrderedTestSuite \
  --max-workers=4 \
  "$@"

# Uncomment below to run just a single test class (much faster)
# $GRADLE_WRAPPER connectedAndroidTest -PandroidTest.useTestOrchestrator=true \
#   -PandroidTest.failOnDeviceUnavailable=false \
#   -Pandroid.testInstrumentationRunnerArguments.class=com.itsaky.androidide.WelcomeScreenTest \
#   --max-workers=4 \
#   "$@"

echo "Tests complete!"