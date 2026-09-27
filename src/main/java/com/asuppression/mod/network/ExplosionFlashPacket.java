package com.asuppression.mod.network;

import com.asuppression.mod.client.ClientSuppressionHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Пакет сервер -> клиент: "рядом взорвалось, подними контузию (flash) до intensity"
 * Контузия тускнит и обесцвечивает экран, отдельно от обычного подавления пулями.
 */
public class ExplosionFlashPacket {

    private final float intensity;
    private final float suppressionKick;

    public ExplosionFlashPacket(float intensity, float suppressionKick) {
        this.intensity = intensity;
        this.suppressionKick = suppressionKick;
    }

    public static void encode(ExplosionFlashPacket msg, FriendlyByteBuf buf) {
        buf.writeFloat(msg.intensity);
        buf.writeFloat(msg.suppressionKick);
    }

    public static ExplosionFlashPacket decode(FriendlyByteBuf buf) {
        return new ExplosionFlashPacket(buf.readFloat(), buf.readFloat());
    }

    public static void handle(ExplosionFlashPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                    ClientSuppressionHandler.spikeFlash(msg.intensity);
                    // взрыв рядом также резко трясёт камеру, как и попадание пулей
                    ClientSuppressionHandler.spikeSuppression(msg.suppressionKick);
                })
        );
        ctx.setPacketHandled(true);
    }
}
