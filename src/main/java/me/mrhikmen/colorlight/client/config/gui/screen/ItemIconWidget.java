package me.mrhikmen.colorlight.client.config.gui.screen;

import me.mrhikmen.colorlight.client.config.Translatable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.joml.Matrix3x2f;

import java.lang.reflect.Field;

/**
 * Block icon. First the vanilla inventory pipeline is used (the block item's model, drawn with GuiItemRenderState),
 * but without an ItemStack: the stack is only needed for its model id, and the model is looked up by the item id
 * directly. If that is not possible (no block item, or an exception), the mod's own renderer (BlockModelIcon) draws
 * the block from its model json, and a plain square is the last resort.
 */
public class ItemIconWidget extends AbstractWidget {

    private static Field displayContextField;

    private final Identifier blockId;
    private final int fallbackColor;
    /** Pixels of the mod's own renderer, built once per screen; null if the block could not be rendered that way. */
    private final int[] pixels;

    public ItemIconWidget(int x, int y, int size, Identifier blockId, int fallbackColor) {
        super(x, y, size, size, Component.empty());
        this.blockId = blockId;
        this.fallbackColor = fallbackColor;
        BlockModelIcon.Result result = BlockModelIcon.build(blockId, size);
        this.pixels = result != null ? result.pixels() : null;
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (drawVanilla(graphics)) return;
        if (this.pixels == null) {
            graphics.fillGradient(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(),
                    this.fallbackColor, this.fallbackColor);
            drawNoModelText(graphics);
            return;
        }
        int size = this.getWidth();
        for (int y = 0; y < size; y++) {
            int x = 0;
            while (x < size) {
                int c = this.pixels[y * size + x];
                int end = x + 1;
                while (end < size && this.pixels[y * size + end] == c) end++;
                if (c != 0) {
                    graphics.fillGradient(this.getX() + x, this.getY() + y, this.getX() + end, this.getY() + y + 1, c, c);
                }
                x = end;
            }
        }
    }

    /** "Нет модели" centred in the grey square, for blocks whose model could not be read at all. */
    private void drawNoModelText(GuiGraphicsExtractor graphics) {
        Font font = Minecraft.getInstance().font;
        FormattedCharSequence text = Translatable.NO_MODEL.getVisualOrderText();
        int x = this.getX() + (this.getWidth() - font.width(text)) / 2;
        int y = this.getY() + (this.getHeight() - font.lineHeight) / 2;
        graphics.text(font, text, x, y, 0xFFFFFFFF, true);
    }

    /** Draws the block item the way the inventory does. Returns false if the block has no item form or it failed. */
    private boolean drawVanilla(GuiGraphicsExtractor graphics) {
        try {
            Block block = BuiltInRegistries.BLOCK.getValue(this.blockId);
            if (block == null) return false;
            Item item = block.asItem();
            if (item == Items.AIR) return false;
            Identifier itemId = BuiltInRegistries.ITEM.getKey(item);

            Minecraft mc = Minecraft.getInstance();
            ItemModel model = mc.getModelManager().getItemModel(itemId);
            TrackingItemStackRenderState state = new TrackingItemStackRenderState();
            setDisplayContext(state, ItemDisplayContext.GUI);
            state.setOversizedInGui(mc.getModelManager().getItemProperties(itemId).oversizedInGui());
            // ItemStack.EMPTY: no components needed, so this also works before a world is loaded
            model.update(state, ItemStack.EMPTY, mc.getItemModelResolver(), ItemDisplayContext.GUI, null, null, 0);

            float scale = this.getWidth() / 16f;
            var pose = graphics.pose();
            pose.pushMatrix();
            try {
                pose.translate(this.getX(), this.getY());
                pose.scale(scale, scale);
                graphics.guiRenderState.addItem(new GuiItemRenderState(
                        new Matrix3x2f(pose), state, 0, 0, graphics.scissorStack.peek()));
            } finally {
                pose.popMatrix();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** displayContext is package-private in ItemStackRenderState; 26.x is not obfuscated, so the name is the real one. */
    private static void setDisplayContext(ItemStackRenderState state, ItemDisplayContext ctx) throws ReflectiveOperationException {
        if (displayContextField == null) {
            Field f = ItemStackRenderState.class.getDeclaredField("displayContext");
            f.setAccessible(true);
            displayContextField = f;
        }
        displayContextField.set(state, ctx);
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return false;
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
    }
}