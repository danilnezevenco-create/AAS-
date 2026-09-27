package com.asuppression.mod;

import com.asuppression.mod.client.BWEffectHandler;
import com.asuppression.mod.client.ClientSuppressionHandler;
import com.asuppression.mod.command.SuppressCommand;
import com.asuppression.mod.event.ServerSuppressionEvents;
import com.asuppression.mod.network.NetworkHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

@Mod(AsSuppressionMod.MODID)
public class AsSuppressionMod {

    public static final String MODID = "assuppression";

    public AsSuppressionMod() {
        NetworkHandler.register();

        // Событие попадания обрабатывается всегда (и на сервере, и в singleplayer, где сервер встроен)
        MinecraftForge.EVENT_BUS.register(new ServerSuppressionEvents());
        MinecraftForge.EVENT_BUS.register(new SuppressCommand());

        // Клиентские хендлеры (рендер виньетки, тряска камеры, детект "пуль" рядом) регистрируем только на клиенте
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> AsSuppressionMod::registerClient);
    }

    private static void registerClient() {
        MinecraftForge.EVENT_BUS.register(new ClientSuppressionHandler());
        MinecraftForge.EVENT_BUS.register(new BWEffectHandler());
    }
}
