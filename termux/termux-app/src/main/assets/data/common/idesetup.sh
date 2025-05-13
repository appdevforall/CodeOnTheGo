#!/bin/bash

set -eu

echo There are $# command line parameters. They are:
echo "$@"

# determine apk architecture
apk_arch=$(dpkg --print-architecture)

# determine device architecture
device_arch=$(uname -m)

install_dir=$HOME
cache_dir="../../cache/apt/archives"
jdk_dir="$SYSROOT/opt/openjdk"
props_dir="$SYSROOT/etc"
props="$props_dir/ide-environment.properties"


echo HOME is ${install_dir}
echo Cache is ${cache_dir}
echo JAVA_HOME is ${jdk_dir}
echo Properties will be in ${props}
echo Device arch is ${device_arch}
echo APK arch is ${apk_arch}


mkdir -p "$install_dir"

# removed to avoid repo signing issues. Most probably AndroidIDE repo sigh has expired.
#apt update
#apt upgrade -y

# Normalize to "v8" if it's armv8l, aarch64, or arm64
if [[ "$device_arch" == "armv8l" || "$device_arch" == "aarch64" || "$device_arch" == "arm64" ]]; then
    device_arch="v8"
else
    device_arch="v7"
fi

if [[ "$apk_arch" == "armv8l" || "$apk_arch" == "aarch64" || "$apk_arch" == "arm64" ]]; then
    apk_arch="v8"
else
    apk_arch="v7"
fi

# Compare architectures
if [ "$apk_arch" != "$device_arch" ]; then
    echo "WARNING: Your device architecture ($device_arch) does NOT match apk architecture ($apk_arch)."
    read -p "Do you want to continue installation? (y/n): " choice

    if [[ ! "$choice" =~ ^[Yy]$ ]]; then
        echo "Installation aborted."
        exit 1
    fi
fi

# Install all .deb packages
echo "Installing .deb packages..."
dpkg -i ${cache_dir}/packages/*.deb
echo "Installation complete!"

#rm -rf "${cache_dir}/packages/"

# TODO: Can we delete the remaining *.deb files after the dpkg command runs? --DS, 29-Oct-2024
# We can delete files from caches folder inside the app, but not from assets folder.
# Assets folder is a backup folder for the case when we will need to restore IDE functionality.
# Also we should keep files for all architectures is assets, so when app is shared to a different phone, it will still be operational.

# Technically we can comment out line 66 and run rm -rf "${cache_dir}/packages/".
# But during the development process I would like to keep it. To avoid reinstalling app every time idesetup had any issues
# during execution.

# When we will use current idesetup for some time and will be sure it works we should comment out lines 45/47 and let it
# delete all deb files files.
# Or use a pkg clean command, it should also do the thing.

mkdir -p "$props_dir"
printf "JAVA_HOME=%s" "$jdk_dir" >"$props"
