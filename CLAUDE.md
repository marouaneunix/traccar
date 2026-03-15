# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Traccar is an open-source GPS tracking system backend (Java). It supports 200+ GPS protocols and 2000+ GPS tracking device models, with a REST API and support for multiple SQL databases.

## Build & Development Commands

```bash
# Build
./gradlew build

# Run tests
./gradlew test

# Run a single test class
./gradlew test --tests "org.traccar.protocol.TeltonikaProtocolDecoderTest"

# Run a single test method
./gradlew test --tests "org.traccar.protocol.TeltonikaProtocolDecoderTest.testDecode"

# Lint (Checkstyle)
./gradlew checkstyle

# Build without tests
./gradlew assemble
```

Build output: `target/tracker-server.jar` with dependencies in `target/lib/`.

## Architecture

### Entry Point & Startup

`Main.java` → creates a Guice injector with `MainModule`, `DatabaseModule`, `WebModule` → starts lifecycle services in order: `ScheduleManager`, `ServerManager`, `WebServer`, `BroadcastService`.

Config file passed as last CLI argument (defaults to `./debug.xml`).

### Protocol Handling

Each GPS protocol lives in `src/main/java/org/traccar/protocol/` and follows this pattern:
- `XxxProtocol` extends `BaseProtocol` — registers decoders/encoders and server ports
- `XxxProtocolDecoder` extends `BaseProtocolDecoder` — parses raw device data into `Position` objects
- `XxxProtocolEncoder` extends `BaseProtocolEncoder` — serializes commands to device format
- `XxxFrameDecoder` extends `LengthFieldBasedFrameDecoder` (or similar) — splits byte stream into frames

`ServerManager` uses `ClassScanner` to dynamically discover all `Protocol` subclasses in the package. A protocol only starts if its port is configured.

### Position Processing Pipeline

Incoming positions flow through `ProcessingHandler`, which chains `BasePositionHandler` implementations:
`FilterHandler` → `ComputedAttributesHandler` → `DistanceHandler` → `MotionHandler` → `GeocoderHandler` → `SpeedLimitHandler` → `DatabaseHandler` → event handlers (Alarm, Behavior, Fuel, Geofence, etc.)

### REST API

Jersey 4 + Jetty 12. Resources in `src/main/java/org/traccar/api/resource/`. Security handled via `SecurityRequestFilter` and `PermissionsService`. All config keys are centralized in `org.traccar.config.Keys`.

### Key Patterns

- **Dependency injection**: Google Guice throughout; avoid `new` for services
- **Network I/O**: Netty 4 for TCP/UDP device connections; Jetty for HTTP
- **Database**: Liquibase migrations, JDBC abstraction via `DatabaseStorage`; `MemoryStorage` used in tests
- **Forwarding**: Positions/events can be forwarded to AMQP, Kafka, MQTT, Redis (see `forward/` package)
- **Broadcasting**: Multicast or Redis-backed `BroadcastService` for multi-instance deployments

### Database Schema

The database schema is defined as Liquibase changelog XML files in `schema/`. Reference these files whenever you need to understand the structure of data stored in the database. The master file is `schema/changelog-master.xml`.

### Testing

Tests extend `ProtocolTest` (which extends `BaseTest`). Protocol decoder tests call `verifyPosition(decoder.decode(...))` or `verifyPositions(...)` with raw hex/binary frames. `BaseTest` sets up a Guice injector backed by `MemoryStorage` so no real database is needed.
