#!/usr/bin/env sh
# https://gist.github.com/dwwoelfel/57f1fbe3d95d5d9e0e20029fa5798d72
set -ex

BUILD_ID=$(uuidgen)

# make prod-build-jar
STORAGE_PATH="gs://jt-builds/build_${BUILD_ID}/jar.jar"
gsutil cp jt.jar "gs://jt-builds/build_${BUILD_ID}/jar.jar"

echo '#!/usr/bin/env sh
set -ex
ulimit -n 500000

# install java

# download jt binary
gsutil cp '${STORAGE_PATH}' jt.jar
chmod +x jt.jar
env OCAMLRUNPARAM="b,v=0x404" BUILD_NUMBER='${build_id}' GIT_SHA='${build_sha}' ONE_ENV=prod ./onegraph &
' > startup-script.sh
