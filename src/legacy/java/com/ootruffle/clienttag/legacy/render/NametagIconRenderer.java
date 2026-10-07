package com.ootruffle.clienttag.legacy.render;

import com.ootruffle.clienttag.config.ClientTagSettings;
import com.ootruffle.clienttag.render.ClientIcon;
import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Draws the client indicators (Lunar crescent, Dawn sunrise, Essential sparkle, NoRisk shield, LabyMod wolf) to the left of a player's
 * nametag, each tinted with the color its client reports for them.
 * <p>
 * Called from {@code Render#renderLivingLabel} twice, matching vanilla's two text passes:
 * once see-through (depth off, faint) and once depth-tested at full opacity.
 */
public final class NametagIconRenderer {

    private static final int ICON_SIZE = 8;
    private static final int GAP = 2;

    private static final Logger LOGGER = LogManager.getLogger("ClientTag");

    private static final Pattern FORMATTING_CODE = Pattern.compile("(?i)§[0-9a-fk-or]");

    private static boolean loggedDraw;
    private static boolean loggedSkip;

    private NametagIconRenderer() {}

    public static void render(Entity entity, String label, boolean seeThroughPass) {
        if (!(entity instanceof EntityPlayer) || !ClientTagSettings.showOnNametags()) {
            return;
        }
        final List<ClientIcon.Tinted> icons = ClientIcon.iconsFor(entity.getUniqueID());
        if (icons.isEmpty()) {
            return;
        }
        // renderLivingLabel also draws the below-name scoreboard line - only tag the name itself.
        // Compared without formatting codes: the drawn label can lack the trailing "§r" that
        // getFormattedText() appends.
        if (!stripFormatting(label).equals(stripFormatting(entity.getDisplayName().getFormattedText()))) {
            if (!loggedSkip) {
                loggedSkip = true;
                LOGGER.info("Skipped a label for {}: label {} != display name {}",
                        entity.getName(), label, entity.getDisplayName().getFormattedText());
            }
            return;
        }
        if (!loggedDraw) {
            loggedDraw = true;
            LOGGER.info("Drawing client icons for {}", entity.getName());
        }

        final Minecraft mc = Minecraft.getMinecraft();
        final int halfWidth = mc.fontRendererObj.getStringWidth(label) / 2;
        final int right = -halfWidth - 1;
        final int left = right - icons.size() * (ICON_SIZE + GAP);

        if (seeThroughPass) {
            // Extend vanilla's translucent background to cover the icons.
            final Tessellator tessellator = Tessellator.getInstance();
            final WorldRenderer worldRenderer = tessellator.getWorldRenderer();
            GlStateManager.disableTexture2D();
            worldRenderer.begin(7, DefaultVertexFormats.POSITION_COLOR);
            worldRenderer.pos(left, -1, 0.0D).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
            worldRenderer.pos(left, 8, 0.0D).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
            worldRenderer.pos(right, 8, 0.0D).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
            worldRenderer.pos(right, -1, 0.0D).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
            tessellator.draw();
            GlStateManager.enableTexture2D();
        }

        // Vanilla's see-through text uses alpha 0x20.
        final float alpha = seeThroughPass ? 32 / 255.0F : 1.0F;
        for (int i = 0; i < icons.size(); i++) {
            IconTextures.draw(icons.get(i), left + 1 + i * (ICON_SIZE + GAP), 0, ICON_SIZE, alpha);
        }
    }

    private static String stripFormatting(String text) {
        return FORMATTING_CODE.matcher(text).replaceAll("").trim();
    }

}
