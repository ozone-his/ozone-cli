#!/bin/sh
# Source me from the ozone-cli repo to use the Ozone CLI built from source, for
# this shell session only (sh/bash/zsh; in fish use ozone-local.fish):
#   mvn package
#   . bin/ozone-local.sh
# then:  ozone --compose-root <dir> --distro <dir> --hostname <h> up
_OZONE_REPO="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
ozone() { java -jar "$_OZONE_REPO"/target/cli-*.jar "$@"; }
