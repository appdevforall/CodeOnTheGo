#!/bin/bash

set -eu

echo There are $# command line parameters. They are:
echo $@

arch=$(uname -m)
install_dir=$HOME
cache_dir="../../cache/apt/archives"
jdk_dir="$SYSROOT/opt/openjdk"
props_dir="$SYSROOT/etc"
props="$props_dir/ide-environment.properties"
supported_abis=(v8 v7 armeabi)

echo HOME is ${install_dir}
echo Cache is ${cache_dir}
echo JAVA_HOME is ${jdk_dir}
echo Properties will be in ${props}
echo Supported abis ${supported_abis}
echo Current arch ${arch}

if [ "$arch" = "armv7l" ]; then
  arch="v7"

elif [ "$arch" = "armv8l" ]; then
  # 64-bit CPU in 32-bit mode
  arch="v8"

elif [ "$arch" = "aarch64" ]; then
  arch="v8"

else
  echo "Unknown architecture. Assuming v7 compatible. Info:" `uname -a`
  arch="v7"
fi

mkdir -p "$install_dir"

# removed to avoid repo signing issues. Most probably AndroidIDE repo sigh has expired.
#apt update
#apt upgrade -y

dpkg -i ${cache_dir}/${arch}/*.deb
for abi in "${supported_abis[@]}"
  do
    if [ "$abi" != "$arch" ]; then
      rm -rf "${cache_dir}/${abi}/"
    fi
  done

# TODO: Can we delete the remaining *.deb files after the dpkg command runs? --DS, 29-Oct-2024
# We can delete files from caches folder inside the app, but not from assets folder.
# Assets folder is a backup folder for the case when we will need to restore IDE functionality.
# Also we should keep files for all architectures is assets, so when app is shared to a different phone, it will still be operational.

# Technically we can comment out line 45 and run rm -rf "${cache_dir}/${abi}/" for all the cached deb folders.
# But during the development process I would like to keep it. To avoid reinstalling app every time idesetup had any issues
# during execution.

# When we will use current idesetup for some time and will be sure it works we should comment out lines 45/47 and let it
# delete all deb files files.
# Or use a pkg clean command, it should also do the thing.

mkdir -p "$props_dir"
printf "JAVA_HOME=%s" "$jdk_dir" >"$props"

#-- this is an interim fix to use our own mirror of packages.androidide.com
#-- a permanent fix will be to change the source of the libs_source/bootstrap/boostrap-<arch>.zip
#-- specifically the etc/apt/sources.list
echo "deb [trusted=yes] https://packages.appdevforall.org/apt/termux-main/ stable main" > $SYSROOT/etc/apt/sources.list
echo "copying documentation database from /data/user/0/com.itsaky.androidide/databases/documentation"
echo "    to /data/user/0/com.itsaky.androidide/databases/documentation"
###cp -f /data/user/0/com.itsaky.androidide/databases/documentation   /data/data/com.itsaky.androidide/databases/documentation
