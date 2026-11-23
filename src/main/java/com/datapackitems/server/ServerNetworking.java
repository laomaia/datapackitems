package com.datapackitems.server;

import com.datapackitems.DataPackItems;
import com.datapackitems.net.RequestServerCatalogC2SPacket;
import com.datapackitems.net.ServerCatalogS2CPacket;
import com.datapackitems.net.TakeServerItemC2SPacket;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端半边：让「服务端加载的数据包」能被客户端看到。
 *
 * <h2>★ 一个 jar 覆盖三种形态（A/B/C）</h2>
 *
 * <p>这个类只在<b>服务端装了本模组</b>时才有意义：
 * <ul>
 *   <li><b>B/C</b>：服务端装了 → 扫描自己加载的数据包 → 推给装了模组的客户端。
 *       客户端因此能看到「服务端上有、但本地没有」的物品。</li>
 *   <li><b>A</b>：服务端没装 → 这些包根本不会被注册，
 *       客户端 { @code canSend} 探测失败后自动退回本地读盘。</li>
 * </ul>
 *
 * <p>★ 权限：和 /give 保持一致，OP 2 级。
 * 服务端在「要目录」和「拿物品」两个入口都再判一次，客户端改包拿不到东西。
 */
public final class ServerNetworking {

    private ServerNetworking() {
    }

    public static void registerPayloads() {
        PayloadTypeRegistry.playS2C().register(ServerCatalogS2CPacket.ID, ServerCatalogS2CPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(
                RequestServerCatalogC2SPacket.ID, RequestServerCatalogC2SPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(
                TakeServerItemC2SPacket.ID, TakeServerItemC2SPacket.CODEC);
    }

    public static void registerHandlers() {
        // 玩家登录：标脏 + 推一份（所有装了客户端的玩家都能拿到目录）
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerCatalog.markDirty();
            ServerPlayerEntity player = handler.getPlayer();
            server.execute(() -> push(player));
        });

        // 请求目录（所有玩家都能要，查看不设限）
        ServerPlayNetworking.registerGlobalReceiver(RequestServerCatalogC2SPacket.ID,
                (packet, context) -> context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (!canView(player)) {
                        return;
                    }
                    if (packet.forceRescan()) {
                        ServerCatalog.markDirty();
                    }
                    push(player);
                }));

        // 领取
        ServerPlayNetworking.registerGlobalReceiver(TakeServerItemC2SPacket.ID,
                (packet, context) -> context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (!canUse(player)) {
                        player.sendMessage(Text.translatable("message.datapack_items.no_permission")
                                .formatted(Formatting.RED), true);
                        return;
                    }
                    ServerCatalog.Entry entry = ServerCatalog.find(context.server(), packet.functionId());
                    if (entry == null) {
                        ServerCatalog.markDirty();
                        entry = ServerCatalog.find(context.server(), packet.functionId());
                    }
                    if (entry == null) {
                        player.sendMessage(Text.translatable("message.datapack_items.missing",
                                packet.functionId()).formatted(Formatting.RED), true);
                        return;
                    }
                    if (!entry.ok()) {
                        player.sendMessage(Text.translatable("message.datapack_items.broken",
                                        entry.error() == null ? "?" : entry.error())
                                .formatted(Formatting.RED), true);
                        return;
                    }
                    giveTo(player, entry.stack());
                }));

        // /reload 后标脏
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, rm, success) -> {
            if (success) {
                ServerCatalog.markDirty();
            }
        });

        // 每 tick 兜底（不脏时空转）
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 100 == 0) {
                ServerCatalog.get(server);
            }
        });
    }

    /** 领取权限：与 /give 一致，OP 2 级。 */
    public static boolean canUse(ServerPlayerEntity player) {
        return player != null && player.hasPermissionLevel(2);
    }

    /**
     * 查看权限：<b>所有玩家</b>都能看到目录。
     *
     * <p>★ 为什么查看不要求 OP：
     * 「这个服上有哪些数据包物品」本身不是秘密（玩家执行 /function 也能试出来），
     * 而且纯客户端模式（A）下普通玩家本来就能看本地数据包 ——
     * 如果服务端模式却要求 OP，同一个模组两种形态体验就割裂了。
     * <p>真正需要拦住的是<b>领取</b>，那一步仍然要求 OP（见 canUse）。
     */
    public static boolean canView(ServerPlayerEntity player) {
        return player != null;
    }

    /** 给一个人推目录（客户端没装就静默跳过）。 */
    public static void push(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        if (server == null || !canView(player)) {
            return;
        }
        if (!ServerPlayNetworking.canSend(player, ServerCatalogS2CPacket.ID)) {
            return;
        }
        ServerCatalogS2CPacket packet;
        try {
            packet = toPacket(server);
        } catch (Throwable t) {
            DataPackItems.LOGGER.error("[数据包物品] 组装服务端目录失败：{}", t.toString());
            return;
        }
        ServerPlayNetworking.send(player, packet);
        DataPackItems.LOGGER.info("[数据包物品] 已把目录推给 {}（{} 个包）",
                player.getName().getString(), packet.packs().size());
    }

    public static ServerCatalogS2CPacket toPacket(MinecraftServer server) {
        List<ServerCatalogS2CPacket.Pack> out = new ArrayList<>();
        int budget = ServerCatalogS2CPacket.MAX_ENTRIES;
        boolean truncated = false;
        for (ServerCatalog.Pack pack : ServerCatalog.get(server)) {
            List<ServerCatalogS2CPacket.Entry> entries = new ArrayList<>();
            for (ServerCatalog.Entry e : pack.entries()) {
                if (budget <= 0) {
                    truncated = true;
                    break;
                }
                budget--;
                entries.add(new ServerCatalogS2CPacket.Entry(
                        e.functionId(), e.name(), e.group(), e.itemText(),
                        e.ok() ? e.stack() : ItemStack.EMPTY, e.error()));
            }
            out.add(new ServerCatalogS2CPacket.Pack(pack.id(), pack.title(), List.copyOf(entries)));
            if (truncated) {
                break;
            }
        }
        return new ServerCatalogS2CPacket(List.copyOf(out), truncated);
    }

    /** 塞背包，塞不下就掉落（和 /give 一致）。 */
    private static void giveTo(ServerPlayerEntity player, ItemStack template) {
        ItemStack stack = template.copy();
        if (stack.isEmpty()) {
            return;
        }
        int remaining = stack.getCount();
        int max = Math.max(1, stack.getMaxCount());
        while (remaining > 0) {
            int part = Math.min(remaining, max);
            ItemStack one = stack.copyWithCount(part);
            boolean all = player.getInventory().insertStack(one);
            if (!all && !one.isEmpty()) {
                player.dropItem(one, false, false);
            }
            remaining -= part;
        }
        player.currentScreenHandler.syncState();
        player.sendMessage(Text.translatable("message.datapack_items.received",
                        stack.getName().copy().formatted(Formatting.AQUA), stack.getCount())
                .formatted(Formatting.GRAY), true);
    }
}
