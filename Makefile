dev-client:
	cd client && yarn start

dev-repl:
	clj -R:nREPL -m nrepl.cmdline

dev-tmux:
	tmux new-session -d -s jt 'make dev-repl'
	tmux split-window
	tmux send 'make dev-client' ENTER
	tmux split-window
	tmux a

prod-build-jar:
	# build client
	cd client && yarn build

	# move to resources
	rm -rf resources/public
	mv client/build resources/public

	# build jar
	clojure -Spom
	clojure -A:uberjar jt.jar

	# cleanup
	rm pom.xml
	rm -rf resources/public

prod-deploy:
	./bin/deploy.sh
