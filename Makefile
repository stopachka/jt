dev-repl:
	clj -A:socket

prod-build-jar:
	clojure -Spom
	clojure -A:uberjar jt.jar

prod-deploy:
	./bin/deploy.sh
