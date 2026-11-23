package com.datapackitems.server;

import com.datapackitems.DataPackItems;
import com.datapackitems.DatapackItemsConfig;
import com.datapackitems.GiveParser;
import net.minecraft.item.ItemStack;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端扫描：<b>服务端真正加载的数据包</b>里，function/item/give 下的 give 函数。
 *
 * <h2>这就是 B/C 模式能「看到服务端数据包」的原因</h2>
 *
 * <p>服务端手里的 ResourceManager 知道所有已启用数据包的完整内容（包括 zip 里的），
 * 客户端拿不到这份东西 —— 所以必须在服务端扫，再把结果推给客户端。
 *
 * <h2>★ 为什么读文件而不是函数表</h2>
 *
 * <p>函数表只记得住「解析成功的函数」。作者哪怕只是把 give 里的某个模组 ID 写错，
 * 整个函数会解析失败、从函数表里消失，页面上就"物品凭空不见了"，作者还查不出原因。
 * 直接读文件则是「文件在 → 页面就有这一格」，解析失败时把错误显示成一格红叉。
 */
public final class ServerCatalog {

    private static final String EXT = ".mcfunction";

    public record Entry(String functionId, String name, String group, String itemText,
                        ItemStack stack, String error) {
        public boolean ok() {
            return stack != null && !stack.isEmpty();
        }
    }

    public record Pack(String id, String title, List<Entry> entries) {
    }

    private static volatile List<Pack> packs = List.of();
    private static volatile boolean dirty = true;
    private static volatile int totalEntries = 0;

    private ServerCatalog() {
    }

    public static void markDirty() {
        dirty = true;
    }

    public static List<Pack> packs() {
        return packs;
    }

    public static int totalEntries() {
        return totalEntries;
    }

    /** 取（必要时重建）。必须在服务端主线程。 */
    public static List<Pack> get(MinecraftServer server) {
        if (dirty) {
            rebuild(server);
        }
        return packs;
    }

    public static Entry find(MinecraftServer server, String functionId) {
        if (functionId == null) {
            return null;
        }
        for (Pack pack : get(server)) {
            for (Entry e : pack.entries()) {
                if (e.functionId().equals(functionId)) {
                    return e;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 扫描

    private static synchronized void rebuild(MinecraftServer server) {
        if (!dirty) {
            return;
        }
        dirty = false;
        packs = List.of();
        totalEntries = 0;
        if (server == null) {
            return;
        }
        long t0 = System.nanoTime();

        Map<String, String> packTitles = collectPackTitles(server);
        Map<Identifier, List<Resource>> found;
        try {
            found = server.getResourceManager().findAllResources("function", id -> {
                String path = id.getPath();
                if (!path.endsWith(EXT)) {
                    return false;
                }
                return DatapackItemsConfig.matchesFunctionPath(path);
            });
        } catch (Throwable t) {
            DataPackItems.LOGGER.error("[数据包物品] 服务端扫描失败：{}", t.toString());
            return;
        }

        Map<String, Map<String, List<Entry>>> byPack = new LinkedHashMap<>();

        for (Map.Entry<Identifier, List<Resource>> fileEntry : found.entrySet()) {
            Identifier fileId = fileEntry.getKey();
            List<Resource> resources = fileEntry.getValue();
            if (resources == null || resources.isEmpty()) {
                continue;
            }
            Resource resource = resources.get(0);

            String fullPath = fileId.getPath();
            String functionPath = DatapackItemsConfig.stripFunctionDir(fullPath);
            String prefix = DatapackItemsConfig.matchedPrefix(fullPath);
            if (prefix == null) {
                continue;
            }
            String tail = functionPath.substring(prefix.length());
            while (tail.startsWith("/")) {
                tail = tail.substring(1);
            }
            int slash = tail.lastIndexOf('/');
            String group = slash < 0 ? "" : tail.substring(0, slash);
            String name = tail.isEmpty() ? functionPath : tail.substring(slash + 1);

            String body = readBody(resource);
            // 服务端解析：用服务器自己的注册表（它装了哪些模组，就能解哪些物品）
            GiveParser.Result parsed = GiveParser.parseBody(body, group,
                    server.getRegistryManager());

            Entry entry = new Entry(
                    fileId.getNamespace() + ":" + functionPath,
                    name,
                    group,
                    parsed.itemText() == null ? "" : parsed.itemText(),
                    parsed.ok() ? parsed.stack() : ItemStack.EMPTY,
                    parsed.ok() ? null : parsed.error());

            byPack.computeIfAbsent(resource.getPackId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(group, k -> new ArrayList<>())
                    .add(entry);
        }

        List<Pack> built = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<Entry>>> packEntry : byPack.entrySet()) {
            List<Entry> flat = new ArrayList<>();
            List<String> groups = new ArrayList<>(packEntry.getValue().keySet());
            groups.sort((a, b) -> {
                if (a.isEmpty() != b.isEmpty()) {
                    return a.isEmpty() ? -1 : 1;
                }
                return a.compareToIgnoreCase(b);
            });
            for (String g : groups) {
                List<Entry> list = packEntry.getValue().get(g);
                list.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
                flat.addAll(list);
            }
            String title = packTitles.get(packEntry.getKey());
            if (title == null || title.isBlank()) {
                title = prettyPackId(packEntry.getKey());
            }
            built.add(new Pack(packEntry.getKey(), title, List.copyOf(flat)));
            totalEntries += flat.size();
        }
        built.sort(Comparator.comparing(Pack::title, String.CASE_INSENSITIVE_ORDER));

        packs = List.copyOf(built);
        DataPackItems.LOGGER.info("[数据包物品] 服务端扫描完成：{} 个数据包 / {} 件物品，耗时 {} ms",
                packs.size(), totalEntries, (System.nanoTime() - t0) / 1_000_000L);
    }

    private static String readBody(Resource resource) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = resource.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            DataPackItems.LOGGER.warn("[数据包物品] 读取函数失败：{}", e.toString());
        }
        return sb.toString();
    }

    private static Map<String, String> collectPackTitles(MinecraftServer server) {
        Map<String, String> map = new HashMap<>();
        try {
            for (ResourcePackProfile profile : server.getDataPackManager().getEnabledProfiles()) {
                String name;
                try {
                    name = profile.getDisplayName().getString();
                } catch (Throwable t) {
                    name = profile.getId();
                }
                map.put(profile.getId(), name);
            }
        } catch (Throwable t) {
            // 拿不到就退回 id 美化
        }
        return map;
    }

    private static String prettyPackId(String packId) {
        String s = packId == null ? "" : packId;
        int slash = s.lastIndexOf('/');
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        if (s.endsWith(".zip")) {
            s = s.substring(0, s.length() - 4);
        }
        return s;
    }
}
