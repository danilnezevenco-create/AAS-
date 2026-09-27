package com.asuppression.mod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Клиентская логика подавления (suppression) и контузии от взрыва (flash): хранит текущий
 * уровень обоих эффектов, их затухание по времени и тряску камеры.
 *
 * Сам уровень поднимается извне:
 *  - при прямом попадании -> SuppressionHitPacket (сервер -> клиент), см. ServerSuppressionEvents;
 *  - при близком пролёте пули/снаряда мимо (включая TACZ) -> BulletFlybyTracker;
 *  - при близком взрыве -> ExplosionFlashPacket (сервер -> клиент).
 *
 * Виньетка на экране рисуется в SuppressionOverlay. Ч/б-эффект контузии от взрыва убран -
 * контузия теперь выражается только через затемнение экрана (flash, см. SuppressionOverlay).
 */
public class ClientSuppressionHandler {

    // ---------------------- SUPPRESSION (тряска + виньетка) ----------------------

    private static float suppression = 0f;
    private static final float DECAY_PER_TICK = 0.965f;

    // ---------------------- EXPLOSION FLASH (тусклость + обесцвечивание) ----------------------

    private static float flash = 0f;
    // Контузия угасает медленнее, чем обычное подавление/тряска - взрыв "оглушает" подольше.
    // При 0.9833 угасание с 1.0 до ~0 занимает ~20.5 секунды.
    private static final float FLASH_DECAY_PER_TICK = 0.9833f;

    /**
     * "Дёрганье" камеры при подавлении: при каждом всплеске подавления (прямое попадание или
     * близкий пролёт пули) камера резко уводится в случайную сторону (как будто человек
     * непроизвольно отшатнулся/отвёл взгляд от опасности), а затем плавно, по экспоненте,
     * возвращается обратно к центру - а не бесконечно "гуляет" по случайной траектории, как
     * было раньше. Чем сильнее был всплеск - тем резче дёргает и тем дольше едет обратно.
     */
    private static float flinchYaw = 0f;
    private static float flinchPitch = 0f;
    private static float flinchRoll = 0f;

    // Насколько быстро "дёрганье" едет обратно к нулю каждый тик (экспоненциальное затухание).
    // 0.88 -> с максимума заметный отскок гаснет примерно за 15-20 тиков (~0.8-1 сек).
    private static final float FLINCH_RECOVERY = 0.88f;

    // Максимальный накопленный увод камеры (в градусах) - чтобы частые повторные "спайки"
    // (например, очередь пуль подряд) не утащили камеру совсем в сторону без возврата.
    private static final float MAX_FLINCH_YAW_PITCH = 14.0f;
    private static final float MAX_FLINCH_ROLL = 8.0f;

    private static final java.util.Random FLINCH_RANDOM = new java.util.Random();

    private static void triggerFlinch(float suppressionDelta) {
        if (suppressionDelta <= 0.001f) return;

        // Сила рывка растёт с силой всплеска, но не линейно "в бесконечность" - лёгкий
        // пролёт мимо дёрнет слегка, прямое попадание/близкий крупный калибр - ощутимо.
        float magnitude = (float) Math.pow(Math.min(1.0f, suppressionDelta), 0.7);

        double angle = FLINCH_RANDOM.nextDouble() * Math.PI * 2.0;
        flinchYaw = clamp((float) (flinchYaw + Math.cos(angle) * magnitude * 16.0), -MAX_FLINCH_YAW_PITCH, MAX_FLINCH_YAW_PITCH);
        flinchPitch = clamp((float) (flinchPitch + Math.sin(angle) * magnitude * 10.0), -MAX_FLINCH_YAW_PITCH, MAX_FLINCH_YAW_PITCH);
        flinchRoll = clamp((float) (flinchRoll + (FLINCH_RANDOM.nextFloat() - 0.5f) * magnitude * 10.0), -MAX_FLINCH_ROLL, MAX_FLINCH_ROLL);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    public static float getSuppression() {
        return suppression;
    }

    public static float getFlash() {
        return flash;
    }

    public static void addSuppression(float amount) {
        if (isPlayerRiding()) return;
        float before = suppression;
        suppression = Math.min(1.0f, suppression + amount);
        triggerFlinch(suppression - before);
    }

    /** Жёстко выставить значение, если оно больше текущего (используется при прямом попадании И при близком пролёте) */
    public static void spikeSuppression(float amount) {
        if (isPlayerRiding()) return;
        float clamped = Math.min(1.0f, amount);
        float before = suppression;
        suppression = Math.max(suppression, clamped);
        triggerFlinch(suppression - before);
    }

    /** Жёстко выставить уровень контузии от взрыва, если он больше текущего */
    public static void spikeFlash(float amount) {
        if (isPlayerRiding()) return;
        flash = Math.max(flash, Math.min(1.0f, amount));
    }

    /**
     * true, если игрок сейчас едет/сидит в какой-либо сущности (лодка, конь, минекарт и т.д.).
     * Пока это так, подавление не должно применяться ни в каком виде - ни от близкого пролёта пули,
     * ни от прямого попадания (SuppressionHitPacket), ни от контузии взрыва (ExplosionFlashPacket) -
     * поэтому проверка стоит прямо в точках входа (addSuppression/spikeSuppression/spikeFlash).
     */
    private static boolean isPlayerRiding() {
        Player player = Minecraft.getInstance().player;
        return player != null && player.isPassenger();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // Затухание
        suppression *= DECAY_PER_TICK;
        if (suppression < 0.001f) suppression = 0f;

        flash *= FLASH_DECAY_PER_TICK;
        if (flash < 0.001f) flash = 0f;

        // Плавный возврат "дёрганья" камеры к центру
        flinchYaw *= FLINCH_RECOVERY;
        flinchPitch *= FLINCH_RECOVERY;
        flinchRoll *= FLINCH_RECOVERY;
        if (Math.abs(flinchYaw) < 0.01f) flinchYaw = 0f;
        if (Math.abs(flinchPitch) < 0.01f) flinchPitch = 0f;
        if (Math.abs(flinchRoll) < 0.01f) flinchRoll = 0f;
    }

    /**
     * Применяет текущее "дёрганье" камеры (см. {@link #triggerFlinch}) к углу обзора.
     * Никакого бесконечного шума/дрожи - только резкий увод при всплеске подавления и
     * плавное возвращение к нулю, обрабатываемое затуханием в {@link #onClientTick}.
     */
    @SubscribeEvent
    public void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (flinchYaw == 0f && flinchPitch == 0f && flinchRoll == 0f) return;

        event.setYaw(event.getYaw() + flinchYaw);
        event.setPitch(event.getPitch() + flinchPitch);
        event.setRoll(event.getRoll() + flinchRoll);
    }
}