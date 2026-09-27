package com.asuppression.mod.client;

import com.asuppression.mod.AsSuppressionMod;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Управляет чёрно-белым пост-процесс эффектом контузии от взрыва
 * (assets/assuppression/shaders/post/grayscale.json + program/grayscale.json + core/grayscale.fsh).
 *
 * ВКЛЮЧЕНИЕ по-прежнему через штатный GameRenderer#loadEffect (резко, без фейд-ина - как и раньше).
 *
 * ВЫКЛЮЧЕНИЕ (фейд-аут) теперь плавное. У PostChain НЕТ публичного getUniform(String) - он лежит
 * глубже: PostChain#passes (приватный List<PostPass>) -> PostPass#getEffect() (публичный,
 * возвращает EffectInstance) -> EffectInstance#getUniform("Intensity") (публичный, возвращает
 * Uniform с методом #set(float)). Поэтому рефлексией достаём только ДВА приватных поля:
 *  - GameRenderer#postEffect (сам активный PostChain)
 *  - PostChain#passes (список проходов шейдера, откуда берём EffectInstance)
 * Дальше всё через штатные публичные методы. Найденный Uniform кэшируется на время активности
 * эффекта (после каждого loadEffect создаётся новый PostChain/EffectInstance, поэтому кэш
 * сбрасывается при каждой новой активации).
 *
 * Если рефлексия сломается (другая версия Forge/маппингов - поля/классы не найдены, ClassCastException
 * и т.п.), REFLECTION_BROKEN взводится один раз и мод тихо откатывается на старое резкое (без фейда)
 * вкл/выкл, как было раньше - без падения игры.
 */
public class BWEffectHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation GRAYSCALE_EFFECT =
            new ResourceLocation(AsSuppressionMod.MODID, "shaders/post/grayscale.json");

    // Уровень flash, начиная с которого включается ч/б-шейдер
    private static final float ACTIVATE_THRESHOLD = 0.08f;

    // Насколько быстро currentIntensity едет к 0 каждый тик (экспоненциальное сглаживание)
    private static final float FADE_SPEED = 0.12f;

    private static boolean active = false;
    // Чтобы не спамить лог одной и той же ошибкой каждый тик, если что-то всё же сломано
    private static boolean loadFailedLastAttempt = false;

    // Текущая "отображаемая" интенсивность ч/б эффекта (0..1), плавно едет к 0 при выключении
    private static float currentIntensity = 0f;

    // Рефлексия: если один раз не сработала - больше не пытаемся, работаем как раньше
    private static boolean reflectionBroken = false;
    private static Field postEffectField = null;
    private static Field passesField = null;

    // Найденный uniform текущего активного эффекта, сбрасывается при каждой новой активации
    private static Uniform cachedUniform = null;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null || mc.level == null) {
            if (active) {
                mc.gameRenderer.shutdownEffect();
                active = false;
                currentIntensity = 0f;
                cachedUniform = null;
            }
            return;
        }

        float flash = ClientSuppressionHandler.getFlash();
        boolean shouldBeOn = flash >= ACTIVATE_THRESHOLD;

        // --- Включение эффекта (резко, без фейд-ина) ---
        if (shouldBeOn && !active) {
            if (loadFailedLastAttempt) {
                return; // уже пытались и упали в этом "заходе" flash - не долбим loadEffect каждый тик
            }
            try {
                mc.gameRenderer.loadEffect(GRAYSCALE_EFFECT);
                active = true;
                currentIntensity = 1f;
                cachedUniform = null; // новый PostChain - старый Uniform больше не валиден
                if (!reflectionBroken) {
                    trySetUniform(mc, 1f);
                }
            } catch (Exception e) {
                loadFailedLastAttempt = true;
                LOGGER.error("Failed to load grayscale post effect '{}': {}",
                        GRAYSCALE_EFFECT, e.getMessage());
            }
            return;
        }

        if (shouldBeOn) {
            loadFailedLastAttempt = false;
        }

        // --- Плавный фейд-аут, когда flash опустился ниже порога ---
        if (!shouldBeOn && active) {
            if (reflectionBroken) {
                // Фолбэк на старое поведение: мгновенное выключение
                mc.gameRenderer.shutdownEffect();
                active = false;
                currentIntensity = 0f;
                cachedUniform = null;
                loadFailedLastAttempt = false;
                return;
            }

            currentIntensity += (0f - currentIntensity) * FADE_SPEED;
            boolean uniformOk = trySetUniform(mc, currentIntensity);

            if (!uniformOk) {
                // Рефлексия сломалась прямо сейчас - откатываемся, выключаем резко и больше не пытаемся
                reflectionBroken = true;
                mc.gameRenderer.shutdownEffect();
                active = false;
                currentIntensity = 0f;
                cachedUniform = null;
                loadFailedLastAttempt = false;
                return;
            }

            if (currentIntensity < 0.02f) {
                mc.gameRenderer.shutdownEffect();
                active = false;
                currentIntensity = 0f;
                cachedUniform = null;
            }

            loadFailedLastAttempt = false;
        }
    }

    /**
     * Пытается выставить uniform "Intensity" у активного пост-эффекта.
     * Возвращает false, если что-то пошло не так - в этом случае вызывающий код должен
     * считать рефлексию сломанной и больше на неё не полагаться.
     */
    private static boolean trySetUniform(Minecraft mc, float value) {
        try {
            if (cachedUniform == null) {
                if (postEffectField == null) {
                    postEffectField = ObfuscationReflectionHelper.findField(GameRenderer.class, "postEffect");
                    postEffectField.setAccessible(true);
                }

                Object effectObj = postEffectField.get(mc.gameRenderer);
                if (!(effectObj instanceof PostChain postChain)) {
                    return false;
                }

                if (passesField == null) {
                    passesField = ObfuscationReflectionHelper.findField(PostChain.class, "passes");
                    passesField.setAccessible(true);
                }

                Object passesObj = passesField.get(postChain);
                if (!(passesObj instanceof List<?> passes)) {
                    return false;
                }

                for (Object passObj : passes) {
                    if (!(passObj instanceof PostPass pass)) continue;
                    EffectInstance effect = pass.getEffect();
                    if (effect == null) continue;
                    Uniform uniform = effect.getUniform("Intensity");
                    if (uniform != null) {
                        cachedUniform = uniform;
                        break;
                    }
                }

                if (cachedUniform == null) {
                    return false;
                }
            }

            cachedUniform.set(value);
            return true;
        } catch (Exception e) {
            LOGGER.warn("Smooth grayscale fade unavailable, falling back to instant toggle: {}", e.getMessage());
            return false;
        }
    }
}