package com.datapackitems.client;

import com.datapackitems.net.RequestServerCatalogC2SPacket;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * 运行时判断「走哪条路」—— 这就是一个 jar 同时覆盖 A / B / C 的地方。
 *
 * <table>
 *   <caption>三种形态</caption>
 *   <tr><th>模式</th><th>服务端</th><th>客户端</th><th>数据来源</th><th>能看到服务端数据包</th></tr>
 *   <tr><td>A</td><td>不装</td><td>装</td><td>本地磁盘</td><td>否</td></tr>
 *   <tr><td>B</td><td>装</td><td>装</td><td>服务端推送</td><td>是</td></tr>
 *   <tr><td>C</td><td>装</td><td>不一定</td><td>服务端推送</td><td>是</td></tr>
 * </table>
 *
 * <p>★ 探测方式：{ @link ClientPlayNetworking#canSend}。
 * 它检查「服务端有没有注册这个 C2S 通道」，也就是「服务端装没装本模组」。
 * 这个判断很便宜，而且不需要真的发一个包。
 *
 * <p>★ C 形态里「客户端没装」的情况对模组来说是不可见的 ——
 * 没装客户端模组的人连入口都没有，面板根本不会出现。所以从模组视角看，
 * C 就是「服务端装了 + 客户端装了」，和 B 走同一条代码路径，
 * 区别只在于：C 的服务端不会因为某个客户端没装而出问题。
 */
 @Environment(EnvType.CLIENT)
public final class ModeDetector {

    /** 数据来源。 */
    public enum Source {
        /** 服务端装了模组（B/C）。 */
        SERVER,
        /** 服务端没装，退回本地读盘（A）。 */
        LOCAL
    }

    private static Source cached = null;

    private ModeDetector() {
    }

    /** 当前该用哪种数据来源。 */
    public static Source detect() {
        if (cached != null) {
            return cached;
        }
        boolean serverHasMod;
        try {
            serverHasMod = ClientPlayNetworking.canSend(RequestServerCatalogC2SPacket.ID);
        } catch (Throwable t) {
            // 通道没注册过会抛异常，那就是服务端没有
            serverHasMod = false;
        }
        cached = serverHasMod ? Source.SERVER : Source.LOCAL;
        return cached;
    }

    /** 换世界/重连时清掉缓存，避免拿上次的结论。 */
    public static void reset() {
        cached = null;
    }

    /** 给界面显示用的说明文字。 */
    public static String describe() {
        return switch (detect()) {
            case SERVER -> "服务端数据包";
            case LOCAL -> "本地数据包";
        };
    }
}
