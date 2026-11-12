# Source this file in fish to put the Ozone CLI first on your PATH:
#   source ./bin/ozone-path.fish
# then use:  ozone --compose-root <dir> --distro <dir> --hostname <h> run
# (For sh/bash/zsh use ozone-path.sh instead.)
# If this bin dir is already on PATH (e.g. another distro was sourced earlier),
# it is moved to the front so this distro's 'ozone' wins.
set -l _ozone_bin (path resolve (dirname (status filename)))
set -l _rest
for _p in $PATH
    if test (path resolve -- $_p) != "$_ozone_bin"
        set -a _rest $_p
    end
end
set -gx PATH $_ozone_bin $_rest
set -e _ozone_bin _rest _p
