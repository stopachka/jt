# journal-together

## Next steps
- Look into enabling logging on google cloud

## Someday-soon
- Consider adding error handlers for chime schedules + http + errwhere :}
- Consider a full-on deploy script
- Consider building a quick site
- Add support for "groups"

## Hacked deploy process for now

```
make prod-build-jar
gsutil cp jt.jar gs://jt-builds/jt-foo.jar # replace with some unique name
make prod-ssh
pkill -f java
rm jt.jar
gsutil cp gs://jt-builds/jt-foo.jar jt.jar
java -Dclojure.server.repl='{:port 50505 :accept clojure.core.server/repl}' -cp jt.jar clojure.main -m jt.core &
```