# Repository Guidelines

## Project Structure & Module Organization

Traccar is a Java GPS tracking backend. Production code lives in `src/main/java/org/traccar/`: device protocols in `protocol/`, REST endpoints in `api/resource/`, and configuration keys in `config/Keys.java`. Tests mirror packages under `src/test/java/org/traccar/`. Protobuf definitions and runtime resources are in `src/main/proto/` and `src/main/resources/`. Liquibase migrations live in `schema/`; notification and export assets live in `templates/`. `openapi.yaml` describes the API. The separate web application is linked at `traccar-web/`; deployment files are in `setup/` and `docker/`.

## Build, Test, and Development Commands

Use JDK 17 and the checked-in Gradle wrapper from the repository root.

- `./gradlew build` — compile, test, run Checkstyle, and package; CI runs this with `--no-daemon --stacktrace`.
- `./gradlew assemble` — package without running tests; outputs `target/tracker-server.jar` and dependencies in `target/lib/`.
- `./gradlew test` — run the complete test suite.
- `./gradlew test --tests 'org.traccar.protocol.OsmAndProtocolDecoderTest'` — run one test class.
- `./gradlew checkstyleMain` — check production Java style using `gradle/checkstyle.xml`.
- `java -jar target/tracker-server.jar debug.xml` — start locally after assembling; configuration selects enabled protocols and database settings.

## Coding Style & Naming Conventions

Follow existing Java style: four-space indentation, same-line opening braces, explicit imports, and no tabs or trailing whitespace. Keep production lines within 120 characters and end files with LF newlines. Use `UpperCamelCase` for classes, `lowerCamelCase` for methods and variables, and `UPPER_SNAKE_CASE` for constants. Protocol classes follow `XxxProtocol`, `XxxProtocolDecoder`, and `XxxProtocolEncoder`. Use Guice injection for services and preserve existing license headers.

## Testing Guidelines

Tests use JUnit Jupiter and Mockito. Name classes `*Test` and methods descriptively, following existing `testDecode` examples. Protocol tests extend `ProtocolTest` and exercise representative device messages with helpers such as `verifyPosition` and `verifyNull`. Add regression cases for changed behavior and malformed input where relevant. No numeric coverage threshold is configured; run targeted tests while developing and the full build before submitting.

## Commit & Pull Request Guidelines

Recent commits use short, imperative subjects such as `Support multiple beacons`; Conventional Commit prefixes are not consistently used. Keep commits focused. Describe the problem, resulting behavior, and validation commands in each PR. Link relevant issues, include device-message examples for protocol changes, and attach screenshots when changing visible UI behavior.
