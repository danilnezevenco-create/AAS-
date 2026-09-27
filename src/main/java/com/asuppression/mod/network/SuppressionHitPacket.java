package com.asuppression.mod.network;

import com.asuppression.mod.client.ClientSuppressionHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Пакет сервер -> клиент: "тебя задело/задело рядом, подними подавление до intensity"
 */
public class SuppressionHitPacket {

    private final float intensity;

    public SuppressionHitPacket(float intensity) {
        this.intensity = intensity;
    }

    public static void encode(SuppressionHitPacket msg, FriendlyByteBuf buf) {
        buf.writeFloat(msg.intensity);
    }

    public static SuppressionHitPacket decode(FriendlyByteBuf buf) {
        return new SuppressionHitPacket(buf.readFloat());
    }

    public static void handle(SuppressionHitPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                        ClientSuppressionHandler.spikeSuppression(msg.intensity))
        );
        ctx.setPacketHandled(true);
    }
}
