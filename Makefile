dev-client:
	cd client && yarn start

dev-repl:
	clj -A:socket

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
	rm jt.pom
	rm -rf resources/public

prod-deploy:
	./bin/deploy.sh
