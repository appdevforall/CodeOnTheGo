#!/bin/bash

# Find the project root directory (where gradlew is located)
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$SCRIPT_DIR/.."

# Change to the project root directory
cd "$PROJECT_ROOT" || exit

# Fast test runner for Android IDE tests

# Select specific test classes to run
./gradlew connectedAndroidTest -PandroidTest.useTestOrchestrator=true \
  -PandroidTest.failOnDeviceUnavailable=false \
  -Pandroid.testInstrumentationRunnerArguments.class=com.itsaky.androidide.OrderedTestSuite \
  --max-workers=4 \
  "$@"

# Uncomment below to run just a single test class (much faster)
# ./gradlew connectedAndroidTest -PandroidTest.useTestOrchestrator=true \
#   -PandroidTest.failOnDeviceUnavailable=false \
#   -Pandroid.testInstrumentationRunnerArguments.class=com.itsaky.androidide.WelcomeScreenTest \
#   --max-workers=4 \
#   "$@"

echo "Tests complete!"