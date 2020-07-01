dev-repl:
	clj -A:socket

prod-build-jar:
	clojure -Spom
	clojure -A:uberjar jt.jar

prod-ssh:
	gcloud beta compute ssh --zone us-central1-a jt-1 --project journaltogether
