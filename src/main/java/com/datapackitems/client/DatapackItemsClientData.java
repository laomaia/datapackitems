package com.datapackitems.client;

import com.datapackitems.DataPackItems;
import com.datapackitems.DatapackGiveCatalog;
import com.datapackitems.net.RequestServerCatalogC2SPacket;
import com.datapackitems.net.ServerCatalogS2CPacket;
import com.datapackitems.net.TakeServerItemC2SPacket;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 客户端目录：统一「服务端推送」和「本地读盘」两种来源。
 *
 * <h2>★ 这就是一个 jar 同时是 A/B/C 的地方</h2>
 *
 * <ul>
 *   <li>服务端装了本模组 → { @link ModeDetector.Source#SERVER}：
 *       发请求、收推送，看到的是<b>服务端真正加载的数据包</b>；</li>
 *   <li>服务端没装 → { @link ModeDetector.Source#LOCAL}：
 *       自己读本地磁盘，功能仍可用（只是读不到对方服务器的包）。</li>
 * </ul>
 *
 * <p>两者对外暴露同一种 { @link Pack} 结构，界面完全不用区分。
 */
 @Environment(EnvType.CLIENT)
public final class DatapackItemsClientData {

    /** 界面看到的数据包结构（两种来源统一成这个）。 */
    public record Pack(String id, String title, List<Entry> entries) {
    }

    /** 界面看到的物品结构。 */
    public record Entry(String functionId, String name, String group, String itemText,
                        ItemStack stack, String error) {
        public boolean ok() {
            return stack != null && !stack.isEmpty();
        }
    }

    private static List<Pack> packs = List.of();
    private static boolean hasData = false;
    private static String note = "";
    private static boolean truncated = false;

    private DatapackItemsClientData() {
    }

    // ------------------------------------------------------------------ 状态

    public static List<Pack> packs() {
        return packs;
    }

    public static boolean hasData() {
        return hasData;
    }

    public static String note() {
        return note;
    }

    public static boolean truncated() {
        return truncated;
    }

    public static int totalCount() {
        int n = 0;
        for (Pack p : packs) {
            n += p.entries().size();
        }
        return n;
    }

    public static void clear() {
        packs = List.of();
        hasData = false;
        note = "";
        truncated = false;
        ModeDetector.reset();
    }

    // ------------------------------------------------------------------ 服务端来源

    /** 收到服务端推送。 */
    public static void acceptServer(ServerCatalogS2CPacket packet) {
        List<Pack> out = new ArrayList<>();
        for (ServerCatalogS2CPacket.Pack p : packet.packs()) {
            List<Entry> list = new ArrayList<>();
            for (ServerCatalogS2CPacket.Entry e : p.entries()) {
                list.add(new Entry(e.functionId(), e.name(), e.group(), e.itemText(),
                        e.stack(), e.error()));
            }
            out.add(new Pack(p.id(), p.title(), List.copyOf(list)));
        }
        packs = List.copyOf(out);
        truncated = packet.truncated();
        hasData = true;
        note = "服务端数据包 · " + packs.size() + " 个包 · " + totalCount() + " 件";
        DataPackItems.LOGGER.info("[数据包物品] 收到服务端目录：{}", note);
    }

    // ------------------------------------------------------------------ 本地来源

    /** 扫本地数据包（A 模式，或服务端不可用时兜底）。 */
    public static void rescanLocal(MinecraftClient client) {
        if (client == null || client.world == null) {
            return;
        }
        try {
            List<java.nio.file.Path> roots = ScanTargets.roots(client);
            DatapackGiveCatalog.rebuild(roots, client.world.getRegistryManager());

            List<Pack> out = new ArrayList<>();
            for (DatapackGiveCatalog.Pack p : DatapackGiveCatalog.packs()) {
                List<Entry> list = new ArrayList<>();
                for (DatapackGiveCatalog.Entry e : p.entries()) {
                    list.add(new Entry(e.functionId(), e.name(), e.group(), e.itemText(),
                            e.stack(), e.error()));
                }
                out.add(new Pack(p.id(), p.title(), List.copyOf(list)));
            }
            packs = List.copyOf(out);
            truncated = false;
            hasData = true;
            note = (ScanTargets.isSingleplayer(client) ? "单人存档" : "多人·本地包")
                    + " · " + roots.size() + " 个目录 · " + totalCount() + " 件";
        } catch (Throwable t) {
            hasData = true;
            note = "本地扫描失败：" + t.getClass().getSimpleName();
            DataPackItems.LOGGER.error("[数据包物品] 本地扫描失败", t);
        }
    }

    // ------------------------------------------------------------------ 刷新

    /** 打开/刷新时调用：按模式选来源。 */
    public static void refresh(MinecraftClient client, boolean force) {
        switch (ModeDetector.detect()) {
            case SERVER -> {
                if (ClientPlayNetworking.canSend(RequestServerCatalogC2SPacket.ID)) {
                    ClientPlayNetworking.send(new RequestServerCatalogC2SPacket(force));
                }
            }
            case LOCAL -> rescanLocal(client);
        }
    }

    // ------------------------------------------------------------------ 领取

    /**
     * 领一件。
     *
     * <p>两条路：
     * <ul>
     *   <li>服务端装了模组 → 发包让它代发（最稳，不依赖玩家 OP 之外的东西）；</li>
     *   <li>服务端没装 → 自己发 /give（权限交给服务器判定）。</li>
     * </ul>
     */
    public static void take(MinecraftClient client, Entry entry) {
        if (client == null || entry == null || !entry.ok()) {
            return;
        }
        if (ModeDetector.detect() == ModeDetector.Source.SERVER
                && ClientPlayNetworking.canSend(TakeServerItemC2SPacket.ID)) {
            ClientPlayNetworking.send(new TakeServerItemC2SPacket(entry.functionId()));
            return;
        }
        if (client.getNetworkHandler() != null) {
            String text = entry.itemText() == null || entry.itemText().isEmpty()
                    ? entry.stack().getItem().toString()
                    : entry.itemText();
            client.getNetworkHandler().sendChatCommand("give " + AT + "s " + text);
        }
    }

    private static final String AT = String.valueOf((char) 64);

    // ------------------------------------------------------------------ 分组工具

    public static List<String> groupsOf(Pack pack) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (Entry e : pack.entries()) {
            set.add(e.group() == null ? "" : e.group());
        }
        List<String> list = new ArrayList<>(set);
        list.sort((a, b) -> {
            if (a.isEmpty() != b.isEmpty()) {
                return a.isEmpty() ? -1 : 1;
            }
            return a.compareToIgnoreCase(b);
        });
        return list;
    }

    public static List<Entry> entriesOf(Pack pack, String group) {
        String g = group == null ? "" : group;
        List<Entry> out = new ArrayList<>();
        for (Entry e : pack.entries()) {
            if (g.equals(e.group() == null ? "" : e.group())) {
                out.add(e);
            }
        }
        return out;
    }
}
