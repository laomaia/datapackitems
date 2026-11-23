package com.datapackitems.client;

import com.datapackitems.DatapackItemsConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 决定「该扫哪些目录」—— 纯客户端读不到服务端存档，这里是唯一的绕法。
 *
 * <h2>★ 三种来源</h2>
 *
 * <ol>
 *   <li><b>单人存档</b>：服务器自己知道存档根目录
 *       （{ @code getSavePath(WorldSavePath.ROOT)}），拼上 datapacks/ 即可。
 *       作者改完数据包按 R 就能看到最新内容。</li>
 *   <li><b>全局目录</b>：{ @code .minecraft/datapacks/}，两边世界都扫。</li>
 *   <li><b>额外目录</b>：配置里手写的路径。连别人服务器时把对方那份数据包
 *       放到本地某个目录、指过来，就能正常用。</li>
 * </ol>
 */
public final class ScanTargets {

    private ScanTargets() {
    }

    /** 当前是否单人（有 IntegratedServer 才算）。 */
    public static boolean isSingleplayer(MinecraftClient client) {
        return client != null && client.getServer() != null;
    }

    /** 单人存档的 datapacks 目录；多人返回 null。 */
    public static Path singleplayerDatapacks(MinecraftClient client) {
        MinecraftServer server = client == null ? null : client.getServer();
        if (server == null) {
            return null;
        }
        try {
            Path root = server.getSavePath(WorldSavePath.ROOT);
            Path dp = root.resolve("datapacks");
            return Files.isDirectory(dp) ? dp : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 当前该扫的全部目录（去重，保持顺序：单人存档 → 全局 → 额外）。 */
    public static List<Path> roots(MinecraftClient client) {
        Set<Path> out = new LinkedHashSet<>();
        Path sp = singleplayerDatapacks(client);
        if (sp != null) {
            out.add(sp);
        }
        for (Path p : DatapackItemsConfig.scanRoots(null)) {
            out.add(p);
        }
        return new ArrayList<>(out);
    }
}
