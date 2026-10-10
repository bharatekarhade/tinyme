# Stream fixtures

`had-a-coffee.json` is a raw event recording from a DevChatRunner session. The other named scenarios are replayable sample event sequences; capture fresh API responses into those paths before treating them as verified agent behavior.

To capture a real run, set `TINYME_DEV_EVENT_CAPTURE` to the fixture path and run the prompt with the `dev` profile. For example, from `server/`:

```sh
TINYME_DEV_EVENT_CAPTURE=src/test/resources/streams/two-tools-one-message.json \
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.arguments="had a coffee and a beer"
```

The capture is a JSON array containing the event objects read by the turn loop, including catch-up events. Review captured content before committing because message text and tool inputs can contain personal data.
