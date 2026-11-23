package com.datapackitems.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * 「数据包物品页」的快捷键。
 *
 * <p>默认 G；玩家可在「设置 → 按键控制 → 数据包物品页」里改。
 */
 @Environment(EnvType.CLIENT)
public final class DatapackItemsKeyBindings {

    public static final String CATEGORY = "key.categories.datapack_items";

    public static final KeyBinding OPEN_PANEL = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.datapack_items.open",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            CATEGORY
    ));

    private DatapackItemsKeyBindings() {
    }

    public static void ensureRegistered() {
        if (OPEN_PANEL == null) {
            throw new IllegalStateException("数据包物品页按键注册失败");
        }
    }
}
