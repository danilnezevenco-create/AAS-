package com.asuppression.mod.event;

import com.asuppression.mod.network.ExplosionFlashPacket;
import com.asuppression.mod.network.NetworkHandler;
import com.asuppression.mod.network.SuppressionHitPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Серверная (в том числе встроенный сервер в одиночной игре) логика:
 *  - при попадании быстрого снаряда/сущности ИЛИ дальнего hitscan-выстрела
 *    (TACZ и подобные оружейные моды) в игрока шлём клиенту "спайк" подавления;
 *  - при взрыве шлём всем игрокам поблизости "контузию" (flash) с затуханием по дистанции.
 */
public class ServerSuppressionEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Насколько сильно подавление подскакивает при прямом попадании (0..1)
    private static final float DIRECT_HIT_INTENSITY = 1.0f;

    // Радиус, в котором взрыв ощущается игроком (за пределами - эффекта нет)
    private static final double EXPLOSION_RADIUS = 20.0;

    // Дистанция, начиная с которой попадание без отдельной сущности-снаряда
    // (типичный случай hitscan-оружия вроде TACZ) считается "выстрелом",
    // а не рукопашной атакой в упор
    private static final double MELEE_RANGE = 4.0;
    private static final double MELEE_RANGE_SQR = MELEE_RANGE * MELEE_RANGE;

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        DamageSource source = event.getSource();

        // Взрывы уже обрабатываются отдельно в onExplosionDetonate - не задваиваем эффект
        if (source.is(DamageTypeTags.IS_EXPLOSION)) return;

        Entity causer = source.getEntity();       // тот, кто нанёс урон (игрок/моб/стрелок)
        Entity direct = source.getDirectEntity();  // реальная сущность-снаряд, ЕСЛИ она есть;
        // если её нет - ваниль откатывается на causer

        // ВРЕМЕННЫЙ ДИАГНОСТИЧЕСКИЙ ЛОГ - удалить/закомментировать после отладки TACZ.
        // Смотрите консоль сервера / logs/latest.log сразу после выстрела по игроку.
        LOGGER.info("[AsSuppression DEBUG] hurt player={} amount={} msgId={} typeKey={} " +
                        "causer={} causerClass={} direct={} directClass={} isIndirect={} distToCauser={}",
                player.getName().getString(),
                event.getAmount(),
                source.getMsgId(),
                source.type().msgId(),
                causer,
                causer == null ? "null" : causer.getClass().getName(),
                direct,
                direct == null ? "null" : direct.getClass().getName(),
                source.isIndirect(),
                causer == null ? "n/a" : Math.sqrt(causer.distanceToSqr(player)));

        // Урон самому себе (голод, огонь, падение и т.п.) - не считается попаданием
        if (causer == null && direct == null) return;
        if (causer == player && direct == player) return;

        boolean hasRealProjectile = direct != null && direct != causer;

        boolean triggered;
        if (hasRealProjectile) {
            // Есть отдельная сущность-снаряд (стрела, снежок, трезубец, кастомный
            // снаряд из любого другого мода). Считаем это попаданием пули вне
            // зависимости от скорости на момент события: многие модовые снаряды
            // к моменту LivingHurtEvent уже удалены/погашены, и их
            // getDeltaMovement() может быть нулевым, даже если летели быстро.
            triggered = direct != player;
        } else if (causer != null && causer != player) {
            // Отдельной сущности-снаряда нет - типичный случай для hitscan-оружия
            // (TACZ и подобные), которое наносит урон "мгновенным лучом" от
            // стрелка без спавна физической пули. Отличаем выстрел от рукопашной
            // атаки по дистанции между атакующим и игроком.
            double distSqr = causer.distanceToSqr(player);
            triggered = distSqr > MELEE_RANGE_SQR;
        } else {
            triggered = false;
        }

        if (!triggered) return;

        NetworkHandler.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new SuppressionHitPacket(DIRECT_HIT_INTENSITY)
        );
    }

    @SubscribeEvent
    public void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        Vec3 center = event.getExplosion().getPosition();

        for (ServerPlayer player : level.players()) {
            double dist = player.position().distanceTo(center);
            if (dist > EXPLOSION_RADIUS) continue;

            // Чем ближе к эпицентру, тем сильнее контузия. У самого эпицентра - почти максимум.
            float falloff = (float) (1.0 - (dist / EXPLOSION_RADIUS));
            falloff = falloff * falloff; // квадратичное затухание - реалистичнее, чем линейное

            float flashIntensity = Math.min(1.0f, falloff * 1.2f);
            float suppressionKick = Math.min(1.0f, falloff * 0.9f);

            if (flashIntensity <= 0.02f) continue;

            NetworkHandler.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new ExplosionFlashPacket(flashIntensity, suppressionKick)
            );
        }
    }
}