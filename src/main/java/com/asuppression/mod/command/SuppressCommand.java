package com.asuppression.mod.command;

import com.asuppression.mod.network.ExplosionFlashPacket;
import com.asuppression.mod.network.NetworkHandler;
import com.asuppression.mod.network.SuppressionHitPacket;
import com.mojang.brigadier.arguments.FloatArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

/**
 * Команда для тестирования эффекта без стрельбы:
 *   /suppress <0.0 - 1.0>
 * Например: /suppress 1.0  -> максимальный эффект попадания
 *           /suppress 0.3  -> лёгкое подавление, как от близкого пролёта
 */
public class SuppressCommand {

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("suppress")
                        .requires(source -> source.hasPermission(0))
                        .then(Commands.argument("intensity", FloatArgumentType.floatArg(0.0f, 1.0f))
                                .executes(ctx -> {
                                    float intensity = FloatArgumentType.getFloat(ctx, "intensity");
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();

                                    NetworkHandler.CHANNEL.send(
                                            PacketDistributor.PLAYER.with(() -> player),
                                            new SuppressionHitPacket(intensity)
                                    );

                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("Suppression applied: " + intensity), false);
                                    return 1;
                                })
                        )
        );

        // Тест эффекта контузии от взрыва без реального взрыва: /suppressflash <0..1>
        event.getDispatcher().register(
                Commands.literal("suppressflash")
                        .requires(source -> source.hasPermission(0))
                        .then(Commands.argument("intensity", FloatArgumentType.floatArg(0.0f, 1.0f))
                                .executes(ctx -> {
                                    float intensity = FloatArgumentType.getFloat(ctx, "intensity");
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();

                                    NetworkHandler.CHANNEL.send(
                                            PacketDistributor.PLAYER.with(() -> player),
                                            new ExplosionFlashPacket(intensity, intensity * 0.7f)
                                    );

                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("Explosion flash applied: " + intensity), false);
                                    return 1;
                                })
                        )
        );
    }
}
