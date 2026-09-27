package com.asuppression.mod.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.WeakHashMap;

/**
 * Клиентская логика подавления (suppression) и контузии от взрыва (flash).
 *
 * Подавление растёт от:
 *  - "близкого пролёта пули" (любая сущность, которая летит рядом достаточно быстро - стрела,
 *    снежок, трезубец, огненный шар и т.д.) -> сила зависит от дистанции по кривой ниже
 *  - прямого попадания -> максимальный резкий скачок (см. SuppressionHitPacket, тоже 1.0)
 *
 * Контузия (flash) растёт от близкого взрыва (см. ExplosionFlashPacket) и тускнит/обесцвечивает экран.
 */
public class ClientSuppressionHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ---------------------- SUPPRESSION (тряска + виньетка) ----------------------

    private static float suppression = 0f;
    private static final float DECAY_PER_TICK = 0.965f;

    // Радиус поиска "пуль" вокруг игрока. Раньше был всего 2 блока - из-за этого быстрые снаряды
    // (стрела летит ~2-3 блока/тик) часто вообще проскакивали мимо проверки: сущность просто не
    // оказывалась внутри маленькой зоны в момент сэмплирования позиции (конец тика), особенно если
    // пролетала мимо по касательной. Расширили зону до 10 блоков - при такой скорости снаряд
    // гарантированно пробудет внутри зоны хотя бы 1-2 тика, а по кривой затухания ниже на 10 блоках
    // эффект и так уже около нуля, так что большой радиус не даёт подавлению расти "от всего вокруг".
    private static final double NEAR_MISS_RADIUS = 10.0;

    // Кривая интенсивности от дистанции: f(d) = exp(-K * d^P).
    // Подобрана под 3 контрольные точки:
    //  f(0)   = 1.0  (прямое попадание - совпадает с DIRECT_HIT_INTENSITY на сервере)
    //  f(0.5) = 0.9  (пролёт в полблока от игрока)
    //  f(3.0) = 0.4  (пролёт в 3 блока от игрока)
    // К 10 блокам f(d) уже < 0.02, дальше эффект практически не ощущается.
    private static final float DIST_CURVE_K = 0.2433f;
    private static final float DIST_CURVE_P = 1.2074f;

    // Минимальная скорость сущности (блоков/тик), чтобы считать её "пулей", а не просто идущим мобом.
    // Стрела в полёте ~1.5-3.0, снежок/трезубец ~0.8-1.5, бегущий игрок/лошадь ~0.2-0.4.
    private static final double FAST_ENTITY_SPEED_THRESHOLD = 0.5;

    // Короткий кулдаун на сущность, чтобы не пересчитывать одно и то же каждый тик без остановки -
    // на результат не влияет (spikeSuppression всё равно берёт максимум), только экономит CPU.
    private static final int RECHECK_COOLDOWN_TICKS = 2;
    private static final WeakHashMap<Entity, Integer> recentlyCounted = new WeakHashMap<>();

    // ---------------------- EXPLOSION FLASH (тусклость + обесцвечивание) ----------------------

    private static float flash = 0f;
    // Контузия угасает медленнее, чем обычное подавление/тряска - взрыв "оглушает" подольше.
    // При 0.9833 угасание с 1.0 до ~0 занимает ~20.5 секунды (в 1.5 раза дольше, чем раньше было
    // с 0.975, что давало ~14 секунд).
    private static final float FLASH_DECAY_PER_TICK = 0.9833f;

    public static float getSuppression() {
        return suppression;
    }

    public static float getFlash() {
        return flash;
    }

    public static void addSuppression(float amount) {
        if (isPlayerRiding()) return;
        suppression = Math.min(1.0f, suppression + amount);
    }

    /** Жёстко выставить значение, если оно больше текущего (используется при прямом попадании И при близком пролёте) */
    public static void spikeSuppression(float amount) {
        if (isPlayerRiding()) return;
        suppression = Math.max(suppression, Math.min(1.0f, amount));
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
     * поэтому проверка стоит прямо в точках входа (addSuppression/spikeSuppression/spikeFlash),
     * а не только в цикле поиска ближних сущностей.
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

        Player player = mc.player;
        if (player.isPassenger()) return; // пока едет/сидит в сущности - подавление не применяется вообще

        // Ищем ЛЮБЫЕ быстро летящие сущности рядом с игроком - это и есть "пули" в этом моде
        AABB searchArea = player.getBoundingBox().inflate(NEAR_MISS_RADIUS);
        Entity playerVehicle = player.getVehicle();

        // --- ВРЕМЕННЫЙ DEBUG: показывает вообще ВСЕ сущности рядом (без фильтра по скорости),
        // чтобы понять - видит ли детект пулю оружейного мода вообще и какая у неё скорость.
        // Удали этот блок после диагностики.
        for (Entity dbg : mc.level.getEntities(player, searchArea, e -> e != player)) {
            LOGGER.info("[AsSuppression DEBUG] {} speed={}", dbg.getClass().getSimpleName(), dbg.getDeltaMovement().length());
        }
        // --- конец debug-блока ---

        // Исключены:
        //  - сущности-игроки (см. предыдущую правку);
        //  - снаряды, выпущенные самим игроком (проверяем владельца Projectile - иначе свои же
        //    пули/стрелы триггерили бы подавление в момент выстрела, пока летят рядом с тобой);
        //  - сущность, в которой игрок сейчас едет/сидит (лодка, конь, минекарт и т.д.) - иначе
        //    само движение "транспорта" считалось бы близким пролётом пули.
        List<Entity> nearby = mc.level.getEntities(player, searchArea,
                e -> e != player && !(e instanceof Player) && e.isAlive()
                        && e.getDeltaMovement().length() >= FAST_ENTITY_SPEED_THRESHOLD
                        && !(e instanceof Projectile proj && proj.getOwner() == player)
                        && e != playerVehicle);

        recentlyCounted.entrySet().removeIf(e -> !e.getKey().isAlive());

        for (Entity ent : nearby) {
            Integer cooldown = recentlyCounted.get(ent);
            if (cooldown != null && cooldown > 0) {
                recentlyCounted.put(ent, cooldown - 1);
                continue;
            }

            Vec3 entPos = ent.position();
            double dist = entPos.distanceTo(player.position());
            if (dist <= NEAR_MISS_RADIUS) {
                float intensity = (float) Math.exp(-DIST_CURVE_K * Math.pow(dist, DIST_CURVE_P));
                spikeSuppression(intensity);
                recentlyCounted.put(ent, RECHECK_COOLDOWN_TICKS);
            }
        }
    }

    /**
     * Тряска (увод) камеры. Сглаженный псевдослучайный шум (hash-based value noise +
     * smoothstep-интерполяция): движение плавное (без рывков и попапинга), но нерегулярное,
     * без ощущения качелей.
     *
     * Амплитуды подняты: "крупная" гармоника каждой оси x1.2, "мелкая" x2.5 - так мелкая дрожь
     * стала заметнее, а не терялась на фоне крупной.
     */
    @SubscribeEvent
    public void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        float supp = getSuppression();
        float fl = getFlash();
        if (supp <= 0.001f && fl <= 0.001f) return;

        long t = System.currentTimeMillis();

        // Разные периоды и "сиды" на каждую ось/гармонику - чтобы движение не выглядело
        // синхронным и предсказуемым (как было бы с одинаковыми по фазе синусоидами)
        double yawShake = smoothNoise(t, 90, 1) * 6.0 + smoothNoise(t, 47, 2) * 5.5;   // было 5.0 и 2.2
        double pitchShake = smoothNoise(t, 110, 3) * 3.84 + smoothNoise(t, 61, 4) * 3.5; // было 3.2 и 1.4
        double rollShake = smoothNoise(t, 130, 5) * 1.92; // было 1.6

        float suppIntensity = supp * supp; // квадратичная кривая - слабое подавление почти не трясёт, сильное - ощутимо
        double flashSway = smoothNoise(t, 400, 6) * 2.0 * fl; // от контузии - медленный "плывущий" крен

        event.setYaw((float) (event.getYaw() + yawShake * suppIntensity));
        event.setPitch((float) (event.getPitch() + pitchShake * suppIntensity));
        event.setRoll((float) (event.getRoll() + rollShake * suppIntensity + flashSway));
    }

    /** Хэш-шум: детерминированное псевдослучайное число в [-1, 1] для целого индекса. */
    private static float hash(long index, long seed) {
        long x = index * 0x9E3779B97F4A7C15L + seed * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 33)) * 0xFF51AFD7ED558CCDL;
        x = (x ^ (x >>> 33)) * 0xC4CEB9FE1A85EC53L;
        x = x ^ (x >>> 33);
        return ((x & 0xFFFFFF) / (float) 0xFFFFFF) * 2f - 1f;
    }

    /**
     * Плавный псевдослучайный шум во времени: значение меняется каждые {@code periodMs} мс,
     * а между "опорными точками" интерполируется smoothstep'ом (без ощущения синусоиды/пружины).
     */
    private static float smoothNoise(long timeMs, long periodMs, long seed) {
        long index = timeMs / periodMs;
        float frac = (timeMs % periodMs) / (float) periodMs;
        float a = hash(index, seed);
        float b = hash(index + 1, seed);
        float t = frac * frac * (3f - 2f * frac); // smoothstep
        return a + (b - a) * t;
    }
}