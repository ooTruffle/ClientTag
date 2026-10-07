package com.ootruffle.clienttag.legacy.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

/**
 * A yes/no question with a wrapped, multi-line message - GuiYesNo only fits two lines. The
 * buttons only work after a delay, so the message gets read.
 */
public final class QuestionScreen extends GuiScreen {

    private static final int YES = 0;
    private static final int NO = 1;

    private final String title;
    private final String message;
    private final String yes;
    private final String no;
    private final Consumer<Boolean> onAnswer;
    private final long readyAt;
    private List<String> lines = new ArrayList<>();

    public QuestionScreen(String title, String message, String yes, String no, int delaySeconds, Consumer<Boolean> onAnswer) {
        this.title = title;
        this.message = message;
        this.yes = yes;
        this.no = no;
        this.onAnswer = onAnswer;
        this.readyAt = System.currentTimeMillis() + delaySeconds * 1000L;
    }

    @Override
    public void initGui() {
        lines = new ArrayList<>();
        for (String paragraph : message.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                lines.add("");
            } else {
                lines.addAll(fontRendererObj.listFormattedStringToWidth(paragraph, Math.min(width - 40, 360)));
            }
        }
        buttonList.clear();
        final int y = Math.min(height - 30, textTop() + 20 + lines.size() * (fontRendererObj.FONT_HEIGHT + 1) + 10);
        buttonList.add(new GuiButton(YES, width / 2 - 155, y, 150, 20, yes));
        buttonList.add(new GuiButton(NO, width / 2 + 5, y, 150, 20, no));
        updateScreen();
    }

    @Override
    public void updateScreen() {
        final boolean ready = System.currentTimeMillis() >= readyAt;
        for (GuiButton button : buttonList) {
            button.enabled = ready;
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.enabled && (button.id == YES || button.id == NO)) {
            onAnswer.accept(button.id == YES);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        // No Escape: the question has to be answered.
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int y = textTop();
        drawCenteredString(fontRendererObj, title, width / 2, y, 0xFFFFFF);
        y += 20;
        for (String line : lines) {
            drawCenteredString(fontRendererObj, line, width / 2, y, 0xD0D0D0);
            y += fontRendererObj.FONT_HEIGHT + 1;
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private int textTop() {
        final int textHeight = 20 + lines.size() * (fontRendererObj.FONT_HEIGHT + 1) + 40;
        return Math.max(10, (height - textHeight) / 2);
    }

}
