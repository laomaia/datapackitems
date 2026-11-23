package com.datapackitems;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「数据包物品页」配置（纯客户端）。
 *
 * <p>文件位置：{ @code config/datapack_items/panel.json}
 * <pre>
 * {
 *   "givePrefixes": ["item/give", "give"],
 *   "extraPackDirs": []
 * }
 * </pre>
 *
 * <h2>givePrefixes</h2>
 * <p>只把函数路径落在这些前缀下的 .mcfunction 当成「物品注册函数」。
 * 默认 item/give 对应 { @code data/&lt;命名空间&gt;/function/item/give/}。
 *
 * <h2>★ extraPackDirs（连别人服务器时的关键）</h2>
 * <p>纯客户端读不到服务端的存档，所以连别人的服时，需要你把它那个数据包
 * 放到本地某个目录里，再把路径写进来。比如：
 * <pre>
 * "extraPackDirs": ["E:/mc/datapacks"]
 * </pre>
 * 该目录下直接放数据包文件夹（如 { @code gubao_datapack/}）或 zip 都行。
 */
public final class DatapackItemsConfig {

    private static final String SUB_DIR = "datapack_items";
    private static final String FILE_NAME = "panel.json";

    private static final List<String> DEFAULT_PREFIXES = List.of("item/give", "give");

    private static List<String> prefixes = DEFAULT_PREFIXES;
    private static List<String> extraDirs = List.of();
    private static boolean loaded = false;

    private DatapackItemsConfig() {
    }

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(SUB_DIR).resolve(FILE_NAME);
    }

    public static synchronized void load() {
        loaded = true;
        Path p = path();
        if (!Files.exists(p)) {
            save();
            DataPackItems.LOGGER.info("已生成配置：{}", p);
            return;
        }
        try {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(text).getAsJsonObject();

            List<String> readPrefixes = new ArrayList<>();
            if (obj.has("givePrefixes") && obj.get("givePrefixes").isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray("givePrefixes");
                for (JsonElement el : arr) {
                    String s = normalizePrefix(el.getAsString());
                    if (!s.isEmpty()) {
                        readPrefixes.add(s);
                    }
                }
            }
            prefixes = readPrefixes.isEmpty() ? DEFAULT_PREFIXES : List.copyOf(readPrefixes);

            List<String> readDirs = new ArrayList<>();
            if (obj.has("extraPackDirs") && obj.get("extraPackDirs").isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray("extraPackDirs");
                for (JsonElement el : arr) {
                    String s = el.getAsString().trim();
                    if (!s.isEmpty()) {
                        readDirs.add(s);
                    }
                }
            }
            extraDirs = List.copyOf(readDirs);
            DataPackItems.LOGGER.info("扫描前缀：{}，额外目录：{}", prefixes, extraDirs);
        } catch (Exception e) {
            DataPackItems.LOGGER.error("配置读取失败，改用默认值：{}", e.toString());
            prefixes = DEFAULT_PREFIXES;
            extraDirs = List.of();
            save();
        }
    }

    public static synchronized void save() {
        Path p = path();
        try {
            Files.createDirectories(p.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("_comment",
                    "givePrefixes = 函数路径要以这些前缀开头才算物品注册函数。"
                            + "extraPackDirs = 额外的数据包目录（连别人服务器时把你本地那份数据包放这里）。");
            JsonArray pa = new JsonArray();
            for (String s : prefixes) {
                pa.add(s);
            }
            obj.add("givePrefixes", pa);
            JsonArray da = new JsonArray();
            for (String s : extraDirs) {
                da.add(s);
            }
            obj.add("extraPackDirs", da);
            Files.writeString(p, obj.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            DataPackItems.LOGGER.error("配置写入失败：{}", e.toString());
        }
    }

    private static String normalizePrefix(String raw) {
        String s = raw == null ? "" : raw.trim().replace('\\', '/');
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        if (s.startsWith("function/")) {
            s = s.substring("function/".length());
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public static List<String> prefixes() {
        if (!loaded) {
            load();
        }
        return prefixes;
    }

    public static List<String> extraDirs() {
        if (!loaded) {
            load();
        }
        return extraDirs;
    }

    /** 路径（可带 function/ 前缀和 .mcfunction 后缀）是否落在某个 give 前缀下。 */
    public static boolean matchesFunctionPath(String functionPath) {
        String path = stripFunctionDir(functionPath);
        if (path.isEmpty()) {
            return false;
        }
        for (String prefix : prefixes()) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    public static String matchedPrefix(String functionPath) {
        String path = stripFunctionDir(functionPath);
        String best = null;
        for (String prefix : prefixes()) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                if (best == null || prefix.length() > best.length()) {
                    best = prefix;
                }
            }
        }
        return best;
    }

    public static String stripFunctionDir(String path) {
        if (path == null) {
            return "";
        }
        String s = path;
        if (s.endsWith(".mcfunction")) {
            s = s.substring(0, s.length() - ".mcfunction".length());
        }
        if (s.startsWith("function/")) {
            s = s.substring("function/".length());
        }
        return s;
    }

    // ------------------------------------------------------------------ 扫描根目录

    /**
     * 汇总所有要扫的数据包目录。
     *
     * <p>顺序：单人存档的 datapacks → 全局 .minecraft/datapacks → 配置里手写的额外目录。
     * 用 Set 去重，避免同一个包被扫两遍。
     *
     * @param localWorldName 当前单人存档名；连别人服务器时传 null
     */
    public static List<Path> scanRoots(String localWorldName) {
        Set<Path> roots = new LinkedHashSet<>();
        Path gameDir = FabricLoader.getInstance().getGameDir();

        // 1) 当前存档自己的数据包
        if (localWorldName != null && !localWorldName.isBlank()) {
            Path p = gameDir.resolve("saves").resolve(localWorldName).resolve("datapacks");
            if (Files.isDirectory(p)) {
                roots.add(p);
            }
        }
        // 2) 全局数据包目录
        Path global = gameDir.resolve("datapacks");
        if (Files.isDirectory(global)) {
            roots.add(global);
        }
        // 3) 配置里额外指的目录
        for (String dir : extraDirs()) {
            try {
                Path p = Path.of(dir);
                if (Files.isDirectory(p)) {
                    roots.add(p);
                } else {
                    DataPackItems.LOGGER.warn("[数据包物品] 配置里的目录不存在：{}", dir);
                }
            } catch (Exception e) {
                DataPackItems.LOGGER.warn("[数据包物品] 配置里的目录非法：{}", dir);
            }
        }
        return new ArrayList<>(roots);
    }
}
