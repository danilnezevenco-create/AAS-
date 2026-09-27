package com.asuppression.mod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Отдельный трекер "близкого пролёта" снарядов - в первую очередь пуль TACZ, а заодно
 * и любых других сущностей-снарядов (стрелы, снежки, трезубцы и т.д.).
 *
 * ПОЧЕМУ ЭТО ОТДЕЛЬНЫЙ КЛАСС, А НЕ ЧАСТЬ СТАРОГО AABB-ПОИСКА В ClientSuppressionHandler:
 *
 * Пуля TACZ (EntityKineticBullet) - обычная сущность-снаряд, но она:
 *  1) двигается на сервере/клиенте вручную (собственный тик мода вызывает setPos/setDeltaMovement
 *     по своей баллистике), из-за чего её "видимая" скорость перемещения не всегда совпадает
 *     с тем, что отдаёт ванильный Entity#getDeltaMovement() - фильтр по этому полю мог просто
 *     не пропускать пулю дальше;
 *  2) на дальней дистанции выстрела пролетает МНОГО блоков за один тик. При старом подходе
 *     (искать сущности в AABB вокруг игрока раз в тик) очень быстрый снаряд мог ни разу не
 *     попасть в выборку: в момент тика N он ещё не долетел до зоны поиска, а к тику N+1 уже
 *     улетел far за неё - сама зона "проскакивалась" между двумя семплами.
 *
 * Решение: не искать снаряды рядом с игроком каждый тик, а один раз подписаться на вход/выход
 * сущности из уровня (EntityJoinLevelEvent/EntityLeaveLevelEvent) и вести список ВСЕХ снарядов
 * на клиенте целиком, независимо от того, где они сейчас находятся. Каждый тик:
 *  - скорость снаряда считаем сами - по разнице его текущей и прошлой позиции, а не по
 *    getDeltaMovement() (работает одинаково надёжно и для ванильных стрел, и для TACZ);
 *  - "пролетело рядом или нет" проверяем не по расстоянию до точки (тик), а по расстоянию
 *    от глаза игрока до ОТРЕЗКА между прошлой и текущей позицией снаряда - это как раз и
 *    ловит случай, когда сама пуля сэмплируется редко (раз в тик), но путь между двумя
 *    соседними позициями всё равно проходит вплотную к игроку.
 *
 * Синергия с TACZ не требует прямой зависимости от мода: снаряды TACZ определяются по
 * namespace регистрационного имени сущности ("tacz"), поэтому TACZ не нужен ни в classpath,
 * ни как обязательная зависимость - если мода нет, просто никогда не найдётся сущность
 * с таким namespace, и трекер тихо ничего не делает для него.
 *
 * СИЛА ПОДАВЛЕНИЯ ОТ УРОНА ПУЛИ: чем "больнее" бьёт пролетевший снаряд, тем сильнее
 * подавление - как и должно быть (снайперская пуля мимо головы пугает сильнее, чем
 * стрела). Урон снаряда читается через рефлексию (см. {@link #resolveDamage}) - тем же
 * способом, что и определение TACZ-namespace: без компиляционной зависимости от TACZ,
 * простым перебором стандартных имён геттера/поля урона (у TACZ, как и у большинства
 * оружейных модов и ванильных снарядов, где-то в иерархии класса лежит числовое поле
 * вроде damage/baseDamage). Если найти его не удалось (другая версия мода/незнакомый
 * снаряд) - используем как раньше грубую оценку по скорости, чтобы эффект не пропадал.
 */
public class BulletFlybyTracker {

    // Максимальная дистанция (в блоках) от отрезка "прошлая -> текущая позиция снаряда"
    // до глаза игрока, при которой пролёт всё ещё считается "близким" и даёт подавление.
    private static final double NEAR_MISS_RADIUS = 3.5;

    // Снаряды дальше этого расстояния от игрока не проверяем на пролёт - нет смысла считать
    // отрезки для пуль, летящих в другом конце карты.
    private static final double MAX_TRACK_RANGE = 48.0;
    private static final double MAX_TRACK_RANGE_SQR = MAX_TRACK_RANGE * MAX_TRACK_RANGE;

    // Минимальное перемещение снаряда за тик (в блоках), чтобы вообще считать его "пулей",
    // а не случайно попавшей в трекер медленной/зависшей сущностью.
    private static final double MIN_MOVE_PER_TICK = 0.05;

    // Короткий "иммунитет" у одного и того же снаряда после срабатывания, чтобы одна и та же
    // пролетевшая пуля не засчитывалась повторно несколько тиков подряд, пока ещё видна рядом.
    private static final int TRIGGER_COOLDOWN_TICKS = 6;

    // --- Нормализация урона в множитель силы подавления ---
    // damageFactor = clamp(DAMAGE_FACTOR_BASE + damage / DAMAGE_FACTOR_SCALE, MIN, MAX).
    // На вменяемых цифрах: стрела (~2 урона) -> ~0.53; пистолетная пуля (~6-8) -> ~0.7-0.83;
    // винтовочная (~15-20) -> ~1.0-1.13; тяжёлая/снайперская (35+) -> упирается в потолок 1.6.
    private static final double DAMAGE_FACTOR_BASE = 0.4;
    private static final double DAMAGE_FACTOR_SCALE = 15.0;
    private static final float DAMAGE_FACTOR_MIN = 0.4f;
    private static final float DAMAGE_FACTOR_MAX = 1.6f;

    // Имена геттеров/полей урона, которые стоит попробовать через рефлексию (в порядке
    // приоритета) - без компиляционной зависимости ни от TACZ, ни от других оружейных модов.
    private static final String[] DAMAGE_METHOD_NAMES = {
            "getDamage", "getBulletDamage", "getKineticDamage", "getBaseDamage", "getAttackDamage"
    };
    private static final String[] DAMAGE_FIELD_NAMES = {
            "damage", "bulletDamage", "kineticDamage", "baseDamage", "attackDamage"
    };

    // Кэш найденного способа читать урон по классу снаряда - чтобы не перебирать рефлексией
    // одно и то же на каждый тик/каждый экземпляр одного и того же типа пули.
    private static final Map<Class<?>, Object> DAMAGE_ACCESSOR_CACHE = new HashMap<>();
    private static final Object NO_ACCESSOR = new Object();

    private final Map<UUID, Tracked> tracked = new HashMap<>();

    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide()) return;

        Entity entity = event.getEntity();
        if (!isRelevantProjectile(entity)) return;

        tracked.put(entity.getUUID(), new Tracked(entity));
    }

    @SubscribeEvent
    public void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) return;
        tracked.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (tracked.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        if (player.isPassenger()) return; // как и остальное подавление - пока едет/сидит, не считаем

        Vec3 eye = player.getEyePosition(1.0f);
        UUID localId = player.getUUID();

        Iterator<Map.Entry<UUID, Tracked>> it = tracked.entrySet().iterator();
        while (it.hasNext()) {
            Tracked t = it.next().getValue();
            Entity entity = t.entity;

            if (entity == null || !entity.isAlive() || entity.level() != mc.level) {
                it.remove();
                continue;
            }

            Vec3 curr = entity.position();

            // Собственная пуля игрока не должна пугать его самого - но позицию всё равно
            // обновляем, чтобы после условного рикошета/смены владельца отрезок считался верно.
            if (entity instanceof Projectile proj && proj.getOwner() != null
                    && proj.getOwner().getUUID().equals(localId)) {
                t.prev = curr;
                continue;
            }

            Vec3 prev = t.prev;
            t.prev = curr;

            if (curr.distanceToSqr(eye) > MAX_TRACK_RANGE_SQR) continue;

            double moved = prev.distanceTo(curr);
            if (moved < MIN_MOVE_PER_TICK) continue;

            if (t.cooldown > 0) {
                t.cooldown--;
                continue;
            }

            double dist = distancePointToSegment(eye, prev, curr);
            if (dist > NEAR_MISS_RADIUS) continue;

            // Чем ближе пролетела пуля и чем сильнее её урон - тем сильнее "спайк" подавления.
            // Вплотную (dist ~ 0) - почти максимум, на границе радиуса - едва заметный эффект.
            float distanceFactor = (float) Math.max(0.0, 1.0 - dist / NEAR_MISS_RADIUS);
            float damageFactor = resolveDamageFactor(entity, moved);
            float intensity = Math.min(1.0f, distanceFactor * damageFactor);

            ClientSuppressionHandler.spikeSuppression(intensity);
            t.cooldown = TRIGGER_COOLDOWN_TICKS;
        }
    }

    /**
     * Множитель силы подавления от урона пролетевшего снаряда. Если урон прочитать не
     * удалось (незнакомый снаряд/версия мода) - грубо оцениваем по скорости, как раньше,
     * чтобы эффект в любом случае не пропадал полностью.
     */
    private static float resolveDamageFactor(Entity entity, double movedThisTick) {
        double damage = resolveDamage(entity);
        if (damage > 0) {
            double factor = DAMAGE_FACTOR_BASE + damage / DAMAGE_FACTOR_SCALE;
            return (float) Math.max(DAMAGE_FACTOR_MIN, Math.min(DAMAGE_FACTOR_MAX, factor));
        }
        return (float) Math.min(1.5, 0.6 + movedThisTick * 0.5);
    }

    /** Пытается прочитать числовой урон снаряда через рефлексию. -1, если не удалось. */
    private static double resolveDamage(Entity entity) {
        Object accessor = DAMAGE_ACCESSOR_CACHE.computeIfAbsent(entity.getClass(), BulletFlybyTracker::findDamageAccessor);
        if (accessor == NO_ACCESSOR) return -1;
        try {
            Number value = accessor instanceof Method
                    ? (Number) ((Method) accessor).invoke(entity)
                    : (Number) ((Field) accessor).get(entity);
            double d = value.doubleValue();
            return d > 0 ? d : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static Object findDamageAccessor(Class<?> clazz) {
        for (String name : DAMAGE_METHOD_NAMES) {
            Method m = findMethodInHierarchy(clazz, name);
            if (m != null && isNumeric(m.getReturnType()) && m.getParameterCount() == 0) {
                m.setAccessible(true);
                return m;
            }
        }
        for (String name : DAMAGE_FIELD_NAMES) {
            Field f = findFieldInHierarchy(clazz, name);
            if (f != null && isNumeric(f.getType())) {
                f.setAccessible(true);
                return f;
            }
        }
        return NO_ACCESSOR;
    }

    private static Method findMethodInHierarchy(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                // пробуем следующий класс в иерархии / следующее имя
            }
        }
        return null;
    }

    private static Field findFieldInHierarchy(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // пробуем следующий класс в иерархии / следующее имя
            }
        }
        return null;
    }

    private static boolean isNumeric(Class<?> type) {
        return type == double.class || type == float.class || type == int.class || type == long.class
                || Number.class.isAssignableFrom(type);
    }

    /**
     * Снаряд, который стоит отслеживать: любая ванильная/модовая сущность-снаряд (Projectile -
     * стрелы, снежки, трезубцы, кастомные снаряды других модов), а также отдельно - пули TACZ,
     * которые определяются по namespace регистрационного имени сущности ("tacz:..."), на случай
     * если конкретная версия TACZ не наследует свою пулю от ванильного Projectile.
     */
    private static boolean isRelevantProjectile(Entity entity) {
        if (entity instanceof Projectile) return true;

        EntityType<?> type = entity.getType();
        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(type);
        return key != null && "tacz".equals(key.getNamespace());
    }

    /** Кратчайшее расстояние от точки p до отрезка [a, b]. */
    private static double distancePointToSegment(Vec3 p, Vec3 a, Vec3 b) {
        double abx = b.x - a.x, aby = b.y - a.y, abz = b.z - a.z;
        double ab2 = abx * abx + aby * aby + abz * abz;
        if (ab2 < 1.0E-9) return p.distanceTo(a);

        double t = ((p.x - a.x) * abx + (p.y - a.y) * aby + (p.z - a.z) * abz) / ab2;
        t = Math.max(0.0, Math.min(1.0, t));

        double cx = a.x + abx * t, cy = a.y + aby * t, cz = a.z + abz * t;
        double dx = p.x - cx, dy = p.y - cy, dz = p.z - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static class Tracked {
        final Entity entity;
        Vec3 prev;
        int cooldown = 0;

        Tracked(Entity entity) {
            this.entity = entity;
            this.prev = entity.position();
        }
    }
}