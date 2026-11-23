package com.datapackitems.client;

import com.datapackitems.DataPackItems;
import com.datapackitems.DatapackItemsConfig;
import com.datapackitems.net.ServerCatalogS2CPacket;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * 客户端入口。
 *
 * <p>★ 一个 jar 覆盖三种形态的关键就在这里：
 * 客户端永远装这个模组（否则没有面板），服务端装不装决定走 B/C 还是 A。
 * { @link ModeDetector} 会自己判断，界面不需要知道。
 */
public class DataPackItemsClient implements ClientModInitializer {

 @Override
    public void onInitializeClient() {
        // 纯客户端模组没有 main 入口，配置加载放在这里
        DatapackItemsConfig.load();
        DataPackItems.LOGGER.info("数据包物品页已加载");

        // 服务端推送的目录（只有服务端装了本模组才会收到）
        ClientPlayNetworking.registerGlobalReceiver(ServerCatalogS2CPacket.ID,
                (packet, context) -> context.client().execute(() -> {
                    DatapackItemsClientData.acceptServer(packet);
                    if (context.client().currentScreen instanceof DatapackItemsScreen screen) {
                        screen.onCatalogUpdated();
                    }
                }));

        DatapackItemsKeyBindings.ensureRegistered();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (DatapackItemsKeyBindings.OPEN_PANEL.wasPressed()) {
                openPanel(client);
            }
            SelfTest.tick(client);
        });

        // 换世界：清缓存 + 重新判断模式
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            DatapackItemsClientData.clear();
            ModeDetector.reset();
        });
    }

    /** 打开面板：按当前模式取数据。 */
    private static void openPanel(net.minecraft.client.MinecraftClient client) {
        if (client.player == null || client.world == null) {
            return;
        }
        ModeDetector.reset();
        DatapackItemsClientData.refresh(client, false);
        // 服务端模式是异步的：先把面板开出来，收到推送后自己会刷新
        if (ModeDetector.detect() == ModeDetector.Source.LOCAL) {
            DatapackItemsClientData.rescanLocal(client);
        }
        DataPackItems.LOGGER.info("[数据包物品] 模式={} {}", ModeDetector.describe(),
                DatapackItemsClientData.note());
        client.setScreen(new DatapackItemsScreen());
    }
}
