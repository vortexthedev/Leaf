package ru.minecomplex.mac.packet;

import ru.minecomplex.mac.replay.Recording;

/** The native/model packet, its recorded bytes and the connection registry snapshot used to decode it. */
public record PacketInput(Recording.Packet wire, Object value, net.minecraft.core.RegistryAccess registries) { }
