package com.github.tth05.codeviewer.gui;

import com.github.tth05.codeviewer.util.CodeHighlighter;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.List;

public class CodeViewScreen extends GuiScreen {

    private CodeTextField codeTextField;
    private Scrollbar scrollbar;

    private float scale = 1f;

    @Override
    public void initGui() {
        super.initGui();

        List<String> oldLines = null;
        if (codeTextField != null)
            oldLines = codeTextField.getLines();

        codeTextField = new CodeTextField(5, 5, this.width - 10, this.height - 10);

        if (oldLines != null)
            codeTextField.setLines(oldLines);

        scrollbar = new Scrollbar(0, oldLines == null ? 1 : oldLines.size() - 1);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);

        drawRect(0, 0, this.width, this.height, 0xFF282C34);
        boolean prev = this.fontRenderer.getUnicodeFlag();
        this.fontRenderer.setUnicodeFlag(false);

        this.codeTextField.draw(this.fontRenderer, scale, scrollbar.getOffset());

        this.fontRenderer.setUnicodeFlag(prev);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int delta = Mouse.getEventDWheel();

        if (!Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)) {
            scrollbar.mouseWheel(delta);
            return;
        }

        if (delta > 0)
            scale += 0.025f;
        else if (delta < 0)
            scale -= 0.025f;

        updateScrollbarParameters();
    }

    private void updateScrollbarParameters() {
        scrollbar.setMax(Math.max(0, this.codeTextField.getLines().size() - this.codeTextField.getVisibleRows(scale)));
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    public void setJavaCode(String str) {
        this.codeTextField.setLines(CodeHighlighter.getHighlightedJavaCode(str));

        updateScrollbarParameters();
    }
}
