package com.asuppression.mod.network;

import com.asuppression.mod.AsSuppressionMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class NetworkHandler {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(AsSuppressionMod.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, SuppressionHitPacket.class,
                SuppressionHitPacket::encode,
                SuppressionHitPacket::decode,
                SuppressionHitPacket::handle);

        CHANNEL.registerMessage(id++, ExplosionFlashPacket.class,
                ExplosionFlashPacket::encode,
                ExplosionFlashPacket::decode,
                ExplosionFlashPacket::handle);
    }
}
