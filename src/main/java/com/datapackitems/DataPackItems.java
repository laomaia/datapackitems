package com.datapackitems;

import com.datapackitems.server.ServerNetworking;
import net.fabricmc.api.ModInitializer;

/**
 * 服务端入口 —— 只有<b>服务端装了本模组</b>时才会执行。
 *
 * <h2>★ 一个 jar 覆盖 A / B / C</h2>
 *
 * <p>同一个 jar 里同时有 { @code main}（服务端）和 { @code client}（客户端）
 * 两个入口点，Fabric 按环境各取所需：
 * <ul>
 *   <li><b>只装在客户端</b>（A）：main 不会跑，客户端探测不到服务端通道，
 *       退回本地读盘；</li>
 *   <li><b>两边都装</b>（B）：服务端扫自己加载的数据包推给客户端，
 *       客户端看到的就是服务端数据包；</li>
 *   <li><b>装在服务端、别的玩家没装客户端</b>（C）：服务端照常工作，
 *       没装的人只是没有面板，游戏本身完全不受影响
 *       （所有网络包都先 canSend 探测，不会给没装的客户端发东西）。</li>
 * </ul>
 *
 * <p>也就是说：<b>装哪边，就自动是哪种形态</b>，不需要用户选。
 */
public class DataPackItems implements ModInitializer {

    public static final String MOD_ID = "datapack_items";
    public static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(MOD_ID);

 @Override
    public void onInitialize() {
        // 服务端启动：加载配置 + 注册网络通道
        DatapackItemsConfig.load();
        ServerNetworking.registerPayloads();
        ServerNetworking.registerHandlers();
        LOGGER.info("数据包物品页已加载（服务端模式：可把加载中的数据包物品同步给客户端）");
    }

    public static net.minecraft.util.Identifier id(String path) {
        return net.minecraft.util.Identifier.of(MOD_ID, path);
    }
}
