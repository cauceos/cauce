// cauce-orchestration-events — the invocation lifecycle event contract.
//
// Deliberately a leaf module with ZERO dependencies (not even cauce-core): the
// payloads are plain JDK types, so future consumers (cauce-observability,
// cauce-governance) can listen to orchestration events without depending on
// cauce-orchestration's internals — and no dependency cycle is possible by
// construction. No Spring either: the sealed records are published as plain
// objects through ApplicationEventPublisher by the emitting module.
// Build config inherited from the root project (see ../build.gradle.kts).
plugins {
    java
}
