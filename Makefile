JAR_NAME="jt.jar"
CORE_CLASS="jt.core"

dev-nrepl:
	clj -R:nREPL -m nrepl.cmdline -p 3434 -i

prod-build-jar:
	clojure -Spom && clojure -A:uberjar $(JAR_NAME) -C -m $(CORE_CLASS)
