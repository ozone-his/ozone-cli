# Source me in fish from the ozone-cli repo to use the Ozone CLI built from
# source, for this session only (for sh/bash/zsh use ozone-local.sh):
#   mvn package
#   source bin/ozone-local.fish
# then:  ozone --compose-root <dir> --distro <dir> --hostname <h> up
set -g _OZONE_REPO (cd (dirname (status filename))/.. && pwd)
function ozone
    java -jar "$_OZONE_REPO"/target/cli-*.jar $argv
end
