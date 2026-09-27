package com.asuppression.mod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.api.distmarker.Dist;
import com.asuppression.mod.AsSuppressionMod;

/**
 * Затемнение по краям экрана (виньетка), усиливающееся вместе с уровнем подавления.
 *
 * ВАЖНО: верх/низ рисуем через штатный GuiGraphics#fillGradient (он умеет только
 * вертикальный градиент "из коробки" - это ок для верха/низа).
 *
 * Для лево/право раньше был отдельный "сырой" draw call через Tesselator.getInstance() -
 * тот же самый Tesselator, которым пользуется сам GuiGraphics для буферизации СВОИХ
 * вызовов fillGradient/fill. Смешивание immediate-mode отрисовки с буферизованной
 * отрисовкой GuiGraphics в одном кадре ненадёжно (порядок flush'а не гарантирован) -
 * визуально это давало не плавный градиент, а сплошной чёрный блок. Поэтому лево/право
 * тоже рисуем через graphics.fill() ступеньками (тонкими вертикальными полосками с
 * линейно меняющимся alpha) - тот же самый проверенно рабочий путь, что и верх/низ.
 */
@Mod.EventBusSubscriber(modid = AsSuppressionMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class SuppressionOverlay {

    public static final IGuiOverlay HUD = SuppressionOverlay::render;

    // Количество полосок для аппроксимации горизонтального градиента - 64 более чем достаточно
    // для гладкой картинки и дёшево по производительности (это просто 64 обычных fill()).
    private static final int GRADIENT_STEPS = 64;

    @SubscribeEvent
    public static void register(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("suppression_vignette", HUD);
    }

    private static void render(net.minecraftforge.client.gui.overlay.ForgeGui gui, GuiGraphics graphics,
                                float partialTick, int width, int height) {
        float supp = ClientSuppressionHandler.getSuppression();
        float flash = ClientSuppressionHandler.getFlash();
        if (supp <= 0.001f && flash <= 0.001f) return;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        if (supp > 0.001f) {
            int maxAlpha = (int) (supp * 200);
            int edge = (int) (Math.max(width, height) * (0.20f + supp * 0.18f));

            int black = 0x000000;
            int transparent = withAlpha(black, 0);
            int opaque = withAlpha(black, maxAlpha);

            // Верх / низ - встроенный vertical-gradient работает правильно "из коробки"
            graphics.fillGradient(0, 0, width, edge, opaque, transparent);
            graphics.fillGradient(0, height - edge, width, height, transparent, opaque);

            // Лево / право - ступенчатая аппроксимация горизонтального градиента через fill()
            fillHorizontalGradientStepped(graphics, 0, 0, edge, height, opaque, transparent);
            fillHorizontalGradientStepped(graphics, width - edge, 0, width, height, transparent, opaque);

            if (supp > 0.6f) {
                int redAlpha = (int) ((supp - 0.6f) / 0.4f * 90);
                int red = 0x400000;
                graphics.fill(0, 0, width, height, withAlpha(red, redAlpha));
            }
        }

        if (flash > 0.001f) {
            int dimAlpha = (int) (flash * 150);
            graphics.fill(0, 0, width, height, withAlpha(0x000000, dimAlpha));
        }

        RenderSystem.disableBlend();
    }

    /**
     * Аппроксимирует горизонтальный градиент colorLeft -> colorRight полосками через
     * обычный graphics.fill() - тот же самый метод, которым GuiGraphics рисует всё
     * остальное в этом оверлее, поэтому нет риска рассинхронизации буферов/шейдеров.
     */
    private static void fillHorizontalGradientStepped(GuiGraphics graphics, int x1, int y1, int x2, int y2,
                                                        int colorLeft, int colorRight) {
        int totalWidth = x2 - x1;
        if (totalWidth <= 0) return;

        int steps = Math.min(GRADIENT_STEPS, totalWidth);
        for (int i = 0; i < steps; i++) {
            float t0 = (float) i / steps;
            float t1 = (float) (i + 1) / steps;

            int sx1 = x1 + Math.round(t0 * totalWidth);
            int sx2 = x1 + Math.round(t1 * totalWidth);
            if (sx2 <= sx1) continue;

            float tMid = (t0 + t1) / 2f;
            int color = lerpColor(colorLeft, colorRight, tMid);

            graphics.fill(sx1, y1, sx2, y2, color);
        }
    }

    private static int lerpColor(int colorFrom, int colorTo, float t) {
        int aF = (colorFrom >> 24) & 0xFF, rF = (colorFrom >> 16) & 0xFF, gF = (colorFrom >> 8) & 0xFF, bF = colorFrom & 0xFF;
        int aT = (colorTo >> 24) & 0xFF, rT = (colorTo >> 16) & 0xFF, gT = (colorTo >> 8) & 0xFF, bT = colorTo & 0xFF;

        int a = Math.round(aF + (aT - aF) * t);
        int r = Math.round(rF + (rT - rF) * t);
        int g = Math.round(gF + (gT - gF) * t);
        int b = Math.round(bF + (bT - bF) * t);

        return withAlpha((r << 16) | (g << 8) | b, a);
    }

    private static int withAlpha(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }
}
