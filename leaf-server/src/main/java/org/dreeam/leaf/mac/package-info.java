/**
 * Built-in MAC for Leaf 26.3, native protocol 777.
 *
 * <p>{@link org.dreeam.leaf.mac.MacRuntime} starts before connections are accepted,
 * queues markers after ServerTickEndEvent, and finalizes captures before network shutdown.
 * Packet interception remains in LeafPackets; no Plugin instance or plugin scheduler is used.
 * Simulation, configuration snapshots, packet decoding and marker allocation remain confined to
 * the connection's Netty event loop. Command/debug delivery uses MinecraftServer.execute.
 *
 * <p>Settings: config/mac.yml. Recordings: mac/recordings. Diagnostics: mac/diagnostics.
 * The standalone MAC plugin must be removed before startup to avoid duplicate checks/markers.
 * The core lives in ru.minecomplex.mac; the historical bukkit package name now denotes the
 * server adapter only. Five bundled native datasets retain their original resource paths.
 *
 * <p>The migration preserves the original simulation operations and oracle fixtures. Thirty
 * Lombok UtilityClass declarations use explicit static members/private constructors instead;
 * Leaf requires no new build dependencies. MAC tests have a class-scoped runtime extension;
 * no global JUnit autodetection configuration is installed into Leaf.
 *
 * <p>Owner verification is required: compilation, MAC oracle/transport tests, a protocol-777
 * login including configuration registries/tags, bundle cancellation/replacement and failed
 * writes, reconfiguration, tick marker ordering, reconnect recording/replay, diagnostics and
 * clean shutdown with active recordings. Existing MAC math/local-ticks provenance gaps remain
 * documented in the source repository's docs/research/status.md; assertions were not relaxed.
 * This migration has not been compiled, tested or started by the agent, per Leaf AGENTS.md.
 *
 * <p>MinecraftServer is an applied source file ignored by the outer Leaf Git repository.
 * Its lifecycle hooks are persisted in minecraft-patches/features/0322-Built-in-MAC.patch.
 * Update that patch through the normal workflow when changing the applied lifecycle hooks.
 */
package org.dreeam.leaf.mac;
