#!/bin/sh
# Source this file to put the Ozone CLI first on your PATH:
#   . ./bin/ozone-path.sh
# then use:  ozone --compose-root <dir> --distro <dir> --hostname <h> run
# (Running it directly has no effect on your shell - it must be sourced.
#  Equivalent one-liner: export PATH="$PWD/bin:$PATH")
# If this bin dir is already on PATH (e.g. another distro was sourced earlier),
# it is moved to the front so this distro's 'ozone' wins.
set -f
_ozone_bin="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
_old="$PATH"
PATH=""
IFS=:
for _dir in $_old; do
  if [ -n "$_dir" ] && [ "$_dir" != "$_ozone_bin" ]; then
    PATH="${PATH:+$PATH:}$_dir"
  fi
done
unset IFS
set +f
export PATH="$_ozone_bin:$PATH"
unset _ozone_bin _old _dir
