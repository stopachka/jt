# journal-together

## Next steps

- Make sure we only handle journal entries, and we do not overwrite
- Include "group" in the email
- Have separate emails for reminders & summaries 
- Update the summary email to include messages from previous day 

## Someday-soon
- Consider adding error handlers for chime schedules + http + errwhere :} 
- Consider a full-on deploy script
- Organize code and tighten up some of the indireciton (i.e the send-ack etc code seems a bit too much indirection)
- Make sure I am using futures properly

## Hacked deploy process for now

```
make prod-build-jar
gsutil cp jt.jar gs://jt-builds/jt-foo.jar # replace with some unique name
make prod-ssh
ps aux | grep java 
kill -9 pid # uh oh this will kill the server, and we only have...one instance xD
rm jt.jar
gsutil cp gs://jt-builds/jt-foo.jar jt.jar 
java -cp jt.jar clojure.main -m jt.core & 
```