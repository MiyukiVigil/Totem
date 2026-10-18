#!/usr/bin/env sh
# Downloads sample Aegis and 2FAS export files from the Aegis project's test suite (GPL-3.0, so
# they're kept out of this repo) for android/core's ForeignImportTest. Without them those tests
# only run against the small built-in samples. Password for the encrypted ones: "test".
set -e
dir="$(dirname "$0")/../target/foreign-fixtures"
mkdir -p "$dir"
base=https://raw.githubusercontent.com/beemdevelopment/Aegis/master/app/src/test/resources/com/beemdevelopment/aegis/importers
for f in aegis_plain.json aegis_encrypted.json \
         2fas_authenticator_plain.2fas 2fas_authenticator_encrypted.2fas \
         2fas_authenticator_plain_v4.2fas 2fas_authenticator_encrypted_v4.2fas; do
  curl -sfL -o "$dir/$f" "$base/$f" && echo "got $f"
done
