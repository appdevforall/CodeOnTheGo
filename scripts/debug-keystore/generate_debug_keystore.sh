#!/bin/bash

my_pass=$( uv run make_password.py )
echo "keystore pw: $my_pass"

keystore_file=adfa-keystore.jks
rm -f $keystore_file ${keystore_file}.base64

my_dname="CN=Hal Eisen, OU=Engineering, O=App Dev For All, L=San Francisco, ST=CA, C=US"

keytool -genkeypair -v \
 -keystore $keystore_file \
 -alias cogo-debug-key \
 -keyalg RSA \
 -keysize 2048 \
 -validity 10000 \
 -dname "$my_dname" \
 -storepass $my_pass \
 -keypass $my_pass

openssl base64 -in $keystore_file -out ${keystore_file}.base64

ls -l $keystore_file ${keystore_file}.base64

