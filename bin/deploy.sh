#!/usr/bin/env sh

set -x
set -e

BUILD_ID=$(uuidgen | tr 'A-Z' 'a-z')
INSTANCE_GROUP="jt-clj"
PROJECT="journaltogether"
ZONE="us-central1-a"
TEMPLATE_NAME="jt-jar-${BUILD_ID}"
STORAGE_PATH="gs://jt-builds/build_${BUILD_ID}/jar.jar"

# Build Jar

make prod-build-jar

# Upload Jar

gsutil cp jt.jar "gs://jt-builds/build_${BUILD_ID}/jar.jar"

# ---------------
# Startup Script

echo '#!/usr/bin/env sh
set -x
set -e
ulimit -n 500000

# download jar
gsutil cp '${STORAGE_PATH}' jt.jar
chmod +x jt.jar

# kick off jar
java -Dclojure.server.repl="{:port 50505 :accept clojure.core.server/repl}" -Dlogback.configurationFile="logback-production.xml" -cp jt.jar clojure.main -m jt.core &
' > startup-script.sh

# Shutdown Script

echo '#!/usr/bin/env sh
set -x
set -e
PID=$(pgrep -x java)
kill $PID
while (pgrep -x java) do
  sleep 1
done' > shutdown-script.sh

# Setup template

gcloud compute instance-templates create $TEMPLATE_NAME \
  --min-cpu-platform=haswell \
  --image-project $PROJECT \
  --image jt-debian-java \
  --machine-type n1-standard-1 \
  --tags "allow-health-check,https-server" \
  --metadata-from-file startup-script=startup-script.sh,shutdown-script=shutdown-script.sh \
  --project $PROJECT

# Update Instance Group

gcloud compute instance-groups managed wait-until --stable $INSTANCE_GROUP \
  --timeout 600 \
  --zone $ZONE \
  --project $PROJECT

gcloud beta compute instance-groups managed rolling-action start-update $INSTANCE_GROUP \
  --version template=$TEMPLATE_NAME \
  --zone $ZONE \
  --type proactive \
  --max-surge 6 \
  --max-unavailable 0 \
  --project $PROJECT \
  --min-ready 30 # seconds to wait for instance to start

# Cleanup

rm startup-script.sh
rm shutdown-script.sh
rm jt.jar
