verify: test
	./gradlew build -x test

test:
	./gradlew test

demo:
	ZO_CLIENT_IDENTITY_TOKEN="$${ZO_CLIENT_IDENTITY_TOKEN:?set token}" ./gradlew run --quiet

clean:
	./gradlew clean
.PHONY: verify test demo clean
