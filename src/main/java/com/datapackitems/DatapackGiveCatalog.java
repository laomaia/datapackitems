package com.datapackitems;

import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryWrapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 客户端扫描结果：所有能找到的数据包里「注册物品」的 give 函数。
 *
 * <h2>★ 纯客户端从哪读数据包（这是纯客户端化的核心）</h2>
 *
 * <p>客户端读不到服务端的存档，所以支持三种来源，按顺序找：
 * <ol>
 *   <li><b>本地存档目录</b> — { @code saves/&lt;存档名&gt;/datapacks/}（单人 / 自己开服时最常用）；</li>
 *   <li><b>全局数据包目录</b> — { @code .minecraft/datapacks/}；</li>
 *   <li><b>手动指定的目录</b> — 配置里写 { @code extraPackDirs}，连别人服务器时
 *       把你手上那份数据包解压到本地某个目录，指过来就能用。</li>
 * </ol>
 *
 * <p>每个来源都同时支持<b>文件夹</b>和 <b>.zip</b> 两种形态 —— 数据包本来就两种都能装。
 *
 * <p>★ 为什么不用 ResourceManager 扫：
 * 资源管理器只认「当前已加载」的数据包，而且路径被改写成 { @code function/xxx}。
 * 直接读磁盘更直观（作者把包丢进 datapacks/ 就能看到），也不受 /reload 时机影响。
 */
public final class DatapackGiveCatalog {

    private static final String EXT = ".mcfunction";

    /**
     * 一条登记项。
     *
     * <p>★ itemText 是 give 里那段「物品参数原文」，领取时原样拼进 /give 指令 ——
     * 这样连数据组件（名字、lore、模型）都一起带过去，服务器收到什么就是什么。
     */
    public record Entry(String packId, String packTitle, String functionId, String name,
                        String group, String itemText, ItemStack stack, String error) {
        public boolean ok() {
            return stack != null && !stack.isEmpty();
        }
    }

    /** 一个数据包的全部登记项。 */
    public record Pack(String id, String title, List<Entry> entries) {
    }

    private static volatile List<Pack> packs = List.of();
    private static volatile int totalEntries = 0;
    private static volatile String lastReport = "";

    private DatapackGiveCatalog() {
    }

    public static List<Pack> packs() {
        return packs;
    }

    public static int totalEntries() {
        return totalEntries;
    }

    public static String lastReport() {
        return lastReport;
    }

    /** 按数据包 id + 函数路径找一条（拿取时用）。 */
    public static Entry find(String functionId) {
        for (Pack pack : packs) {
            for (Entry e : pack.entries()) {
                if (e.functionId().equals(functionId)) {
                    return e;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 扫描入口

    /**
     * 重建目录。必须在客户端主线程调用（要读注册表）。
     *
     * @param roots 要扫的数据包根目录（每个目录下直接放 &lt;pack&gt;/ 或 &lt;pack&gt;.zip）
     * @param lookup 物品注册表
     */
    public static synchronized void rebuild(List<Path> roots, RegistryWrapper.WrapperLookup lookup) {
        long t0 = System.nanoTime();
        List<Pack> built = new ArrayList<>();
        StringBuilder report = new StringBuilder();

        for (Path root : roots) {
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            List<Path> candidates = new ArrayList<>();
            try (var stream = Files.list(root)) {
                stream.filter(p -> Files.isDirectory(p) || p.toString().toLowerCase().endsWith(".zip"))
                        .sorted()
                        .forEach(candidates::add);
            } catch (IOException e) {
                DataPackItems.LOGGER.warn("[数据包物品] 无法列出目录 {}：{}", root, e.toString());
                continue;
            }
            for (Path candidate : candidates) {
                Pack pack = scanOne(candidate, lookup);
                if (pack != null && !pack.entries().isEmpty()) {
                    built.add(pack);
                    report.append(pack.title()).append("(").append(pack.entries().size()).append(") ");
                }
            }
        }

        built.sort(Comparator.comparing(Pack::title, String.CASE_INSENSITIVE_ORDER));
        int total = built.stream().mapToInt(p -> p.entries().size()).sum();
        packs = List.copyOf(built);
        totalEntries = total;
        lastReport = report.toString().trim();

        DataPackItems.LOGGER.info("[数据包物品] 扫描完成：{} 个数据包 / {} 件物品，耗时 {} ms {}",
                packs.size(), total, (System.nanoTime() - t0) / 1_000_000L,
                report.length() > 0 ? "[" + report.toString().trim() + "]" : "");
    }

    // ------------------------------------------------------------------ 单个数据包

    private static Pack scanOne(Path candidate, RegistryWrapper.WrapperLookup lookup) {
        String title = simpleName(candidate);
        Map<String, String> functions = new LinkedHashMap<>();

        try {
            if (Files.isDirectory(candidate)) {
                Path dataDir = candidate.resolve("data");
                if (!Files.isDirectory(dataDir)) {
                    return null;
                }
                collectFromDirectory(dataDir, functions);
            } else {
                collectFromZip(candidate, functions);
            }
        } catch (Throwable t) {
            DataPackItems.LOGGER.warn("[数据包物品] 读取 {} 失败：{}", candidate, t.toString());
            return null;
        }

        if (functions.isEmpty()) {
            return null;
        }

        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, String> e : functions.entrySet()) {
            // key = "命名空间:函数路径"，value = 文件正文
            String functionId = e.getKey();
            String body = e.getValue();

            String pathPart = functionId.substring(functionId.indexOf(':') + 1);
            String prefix = DatapackItemsConfig.matchedPrefix(pathPart);
            if (prefix == null) {
                continue;
            }
            String tail = pathPart.substring(prefix.length());
            while (tail.startsWith("/")) {
                tail = tail.substring(1);
            }
            int slash = tail.lastIndexOf('/');
            String group = slash < 0 ? "" : tail.substring(0, slash);
            String name = slash < 0 ? tail : tail.substring(slash + 1);

            GiveParser.Result parsed = GiveParser.parseBody(body, group, lookup);
            entries.add(new Entry(candidate.toString(), title, functionId, name, group,
                    parsed.itemText() == null ? "" : parsed.itemText(),
                    parsed.ok() ? parsed.stack() : ItemStack.EMPTY,
                    parsed.ok() ? null : parsed.error()));
        }

        if (entries.isEmpty()) {
            return null;
        }
        entries.sort(Comparator
                .comparing((Entry en) -> en.group().isEmpty() ? "" : "1" + en.group())
                .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return new Pack(candidate.toString(), title, List.copyOf(entries));
    }

    /** 从解压形态的数据包收集 item/give 下的 .mcfunction。 */
    private static void collectFromDirectory(Path dataDir, Map<String, String> out) throws IOException {
        try (var namespaces = Files.list(dataDir)) {
            for (Path nsDir : namespaces.filter(Files::isDirectory).toList()) {
                String namespace = nsDir.getFileName().toString();
                Path funcDir = nsDir.resolve("function");
                if (!Files.isDirectory(funcDir)) {
                    continue;
                }
                try (var walk = Files.walk(funcDir)) {
                    for (Path file : walk.filter(Files::isRegularFile).toList()) {
                        String rel = funcDir.relativize(file).toString().replace('\\', '/');
                        if (!rel.endsWith(EXT)) {
                            continue;
                        }
                        String path = rel.substring(0, rel.length() - EXT.length());
                        if (!DatapackItemsConfig.matchesFunctionPath(path)) {
                            continue;
                        }
                        out.put(namespace + ":" + path, Files.readString(file, StandardCharsets.UTF_8));
                    }
                }
            }
        }
    }

    /** 从 zip 形态的数据包收集。 */
    private static void collectFromZip(Path zip, Map<String, String> out) throws IOException {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry entry = en.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String full = entry.getName().replace('\\', '/');
                // data/<ns>/function/<path>.mcfunction
                if (!full.startsWith("data/") || !full.endsWith(EXT)) {
                    continue;
                }
                int nsEnd = full.indexOf('/', "data/".length());
                if (nsEnd < 0) {
                    continue;
                }
                String namespace = full.substring("data/".length(), nsEnd);
                String afterNs = full.substring(nsEnd + 1);
                if (!afterNs.startsWith("function/")) {
                    continue;
                }
                String rel = afterNs.substring("function/".length());
                String path = rel.substring(0, rel.length() - EXT.length());
                if (!DatapackItemsConfig.matchesFunctionPath(path)) {
                    continue;
                }
                try (InputStream in = zf.getInputStream(entry);
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                    out.put(namespace + ":" + path, sb.toString());
                }
            }
        }
    }

    private static String simpleName(Path p) {
        String n = p.getFileName().toString();
        if (n.toLowerCase().endsWith(".zip")) {
            n = n.substring(0, n.length() - 4);
        }
        return n;
    }
}
