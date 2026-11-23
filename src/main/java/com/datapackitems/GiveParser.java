package com.datapackitems;

import com.mojang.brigadier.StringReader;
import net.minecraft.command.argument.ItemStringReader;
import net.minecraft.component.ComponentChanges;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把一条 give 命令解析成 ItemStack —— <b>纯客户端，不需要任何服务端对象</b>。
 *
 * <h2>为什么不用「执行函数再截获产物」</h2>
 *
 * <p>数据包作者习惯把 give @s 和 give @p 混着写（gubao 包里 book/ 下就有 @p）。
 * 真去执行函数，目标选择器会按"谁执行"来解析，产物跟"这条函数代表哪个物品"无关。
 * 字符串解析只看 item 参数，跟 @s / @p 无关，语义正好是
 * 「这条 give 注册的是哪件物品」——这才是物品页要的东西。
 *
 * <h2>★ 为什么能纯客户端跑</h2>
 *
 * <p>早先的做法是「拼一条 give 指令丢给服务器的命令树解析」，那就必须拿到
 * MinecraftServer，纯客户端做不到。
 *
 * <p>现在直接用原版的 ItemStringReader：它<b>只依赖物品注册表</b>
 * （RegistryWrapper.WrapperLookup），而客户端自己就有一份完整注册表
 * （client.world.getRegistryManager()）。于是组件解析可以完全在客户端完成。
 *
 * <p>注意：注册表里没有的物品（服务端装了某模组、你本地没装）依然解析失败，
 * 页面显示成红叉 —— 这不是 bug，而是「你本地确实没有这件物品」的真实反映。
 */
public final class GiveParser {

    private static final Pattern GIVE = Pattern.compile("^\\s*give\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    /** 选择器符号本身。 */
    private static final String AT = String.valueOf((char) 64);

    /** 解析结果。 */
    public record Result(ItemStack stack, String itemText, String group, String error) {
        public boolean ok() {
            return stack != null && !stack.isEmpty();
        }
    }

    private GiveParser() {
    }

    public static Result parseBody(String body, String defaultGroup, RegistryWrapper.WrapperLookup lookup) {
        if (body == null || body.isEmpty()) {
            return new Result(null, "", defaultGroup, "函数是空的");
        }
        for (String rawLine : body.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (!line.regionMatches(true, 0, "give", 0, 4)) {
                int runIdx = line.toLowerCase(Locale.ROOT).indexOf(" run ");
                if (runIdx >= 0) {
                    line = line.substring(runIdx + " run".length()).trim();
                }
            }
            Matcher m = GIVE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            return parseGiveArguments(m.group(1), defaultGroup, lookup);
        }
        return new Result(null, "", defaultGroup, "里面没有 give 命令");
    }

    public static Result parseGiveArguments(String args, String defaultGroup,
                                            RegistryWrapper.WrapperLookup lookup) {
        StringReader reader = new StringReader(args);
        reader.skipWhitespace();
        try {
            skipTarget(reader);
            reader.skipWhitespace();

            String itemText = readItemToken(reader);
            if (itemText.isEmpty()) {
                return new Result(null, "", defaultGroup, "give 后面没有物品参数");
            }
            reader.skipWhitespace();

            int count = 1;
            if (reader.canRead()) {
                String rest = reader.getRemaining().trim();
                int sp = rest.indexOf(' ');
                String countText = sp < 0 ? rest : rest.substring(0, sp);
                try {
                    count = Math.max(1, Integer.parseInt(countText));
                } catch (NumberFormatException ignored) {
                }
            }

            ItemStack stack = buildStack(itemText, lookup);
            if (stack == null) {
                return new Result(null, itemText, defaultGroup,
                        "本地没有这件物品（可能缺模组）：" + itemText);
            }
            int max = Math.max(1, stack.getMaxCount());
            stack.setCount(Math.max(1, Math.min(count, max * 100)));
            return new Result(stack, itemText, defaultGroup, null);
        } catch (RuntimeException e) {
            DataPackItems.LOGGER.warn("[数据包物品] 解析 give 参数失败：{} -> {}", args, e.toString());
            return new Result(null, "", defaultGroup, "解析异常：" + e.getClass().getSimpleName());
        }
    }

    /** 「物品 ID + 数据组件」→ ItemStack，全程不碰服务端对象。 */
    public static ItemStack buildStack(String itemText, RegistryWrapper.WrapperLookup lookup) {
        if (lookup == null || itemText == null || itemText.isEmpty()) {
            return null;
        }
        try {
            StringReader reader = new StringReader(itemText);
            ItemStringReader itemReader = new ItemStringReader(lookup);
            ItemStringReader.ItemResult result = itemReader.consume(reader);
            RegistryEntry<net.minecraft.item.Item> item = result.item();
            ComponentChanges components = result.components();
            ItemStack stack = new ItemStack(item, 1,
                    components == null ? ComponentChanges.EMPTY : components);
            return stack.isEmpty() ? null : stack;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 词法

    private static void skipTarget(StringReader reader) {
        while (reader.canRead() && !isSpace(reader.peek())) {
            if (reader.peek() == '[') {
                reader.skip();
                readBalanced(reader, '[', ']');
            } else {
                reader.skip();
            }
        }
    }

    private static String readItemToken(StringReader reader) {
        StringBuilder sb = new StringBuilder();
        while (reader.canRead()) {
            char c = reader.peek();
            if (isSpace(c)) {
                break;
            }
            if (c == '[') {
                reader.skip();
                sb.append('[').append(readBalanced(reader, '[', ']')).append(']');
                continue;
            }
            reader.skip();
            sb.append(c);
        }
        return sb.toString();
    }

    private static String readBalanced(StringReader reader, char open, char close) {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (reader.canRead() && depth > 0) {
            char c = reader.peek();
            if (c == '\'' || c == '"') {
                sb.append(readQuoted(reader, c));
                continue;
            }
            reader.skip();
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String readQuoted(StringReader reader, char quote) {
        StringBuilder sb = new StringBuilder();
        sb.append(quote);
        reader.skip();
        while (reader.canRead()) {
            char c = reader.peek();
            reader.skip();
            sb.append(c);
            if (c == '\\' && reader.canRead()) {
                sb.append(reader.peek());
                reader.skip();
                continue;
            }
            if (c == quote) {
                break;
            }
        }
        return sb.toString();
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t';
    }
}
