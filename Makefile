JAR_NAME=jt.jar
CORE_CLASS=jt.core
GC_PROJECT_NAME="journaltogether"
GC_BUCKET="jt-builds"
GC_VM_NAME="jt-1"
GC_VM_ZONE="us-central1-a"

dev-repl:
	clj -A:socket

prod-build-jar:
	clojure -Spom && clojure -A:uberjar $(JAR_NAME) -C -m $(CORE_CLASS)

prod-ssh:
	gcloud beta compute ssh --zone $(GC_VM_ZONE) $(GC_VM_NAME) --projet $(GC_PROJECT_NAME)
