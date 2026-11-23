package com.datapackitems.net;

import com.datapackitems.DataPackItems;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：整份「服务端数据包物品」目录。
 *
 * <p>这是 B/C 模式的核心 —— 服务端把<b>它真正加载的</b>数据包解析结果推下来，
 * 客户端因此能看到「服务端上有、但我本地没有」的物品。
 *
 * <p>★ 空栈的坑（踩过一次，直接导致玩家掉线）：
 * { @code ItemStack.PACKET_CODEC} <b>不允许编码空栈</b>，会抛
 * { @code "Empty ItemStack not allowed"} 把整包编码搞挂。
 * 解析失败的条目本来就没有物品，所以这里先写一个 hasStack 标志位，没有就不写栈。
 */
public record ServerCatalogS2CPacket(List<Pack> packs, boolean truncated) implements CustomPayload {

    public record Pack(String id, String title, List<Entry> entries) {
    }

    /**
     * 一条物品。
     *
     * <p>itemText 是 give 里那段物品参数原文，领取时原样拼回 /give 指令，
     * 这样数据组件（名字、lore、模型）都能完整带过去。
     */
    public record Entry(String functionId, String name, String group, String itemText,
                        net.minecraft.item.ItemStack stack, String error) {
        public boolean ok() {
            return stack != null && !stack.isEmpty();
        }
    }

    public static final int MAX_ENTRIES = 2048;

    public static final CustomPayload.Id<ServerCatalogS2CPacket> ID =
            new CustomPayload.Id<>(DataPackItems.id("server_catalog"));

    public static final PacketCodec<RegistryByteBuf, ServerCatalogS2CPacket> CODEC =
            PacketCodec.of(
                    (packet, buf) -> {
                        buf.writeVarInt(packet.packs().size());
                        for (Pack pack : packet.packs()) {
                            buf.writeString(pack.id(), 256);
                            buf.writeString(pack.title(), 256);
                            buf.writeVarInt(pack.entries().size());
                            for (Entry e : pack.entries()) {
                                buf.writeString(e.functionId(), 512);
                                buf.writeString(e.name(), 256);
                                buf.writeString(e.group(), 256);
                                buf.writeString(e.itemText(), 1024);
                                // ★ 空栈不能编码：先写标志位
                                boolean hasStack = e.ok();
                                buf.writeBoolean(hasStack);
                                if (hasStack) {
                                    net.minecraft.item.ItemStack.PACKET_CODEC.encode(buf, e.stack());
                                }
                                buf.writeBoolean(e.error() != null);
                                if (e.error() != null) {
                                    buf.writeString(e.error(), 512);
                                }
                            }
                        }
                        buf.writeBoolean(packet.truncated());
                    },
                    buf -> {
                        int packCount = buf.readVarInt();
                        List<Pack> packs = new ArrayList<>(packCount);
                        for (int i = 0; i < packCount; i++) {
                            String id = buf.readString(256);
                            String title = buf.readString(256);
                            int n = buf.readVarInt();
                            List<Entry> entries = new ArrayList<>(n);
                            for (int j = 0; j < n; j++) {
                                String functionId = buf.readString(512);
                                String name = buf.readString(256);
                                String group = buf.readString(256);
                                String itemText = buf.readString(1024);
                                net.minecraft.item.ItemStack stack = buf.readBoolean()
                                        ? net.minecraft.item.ItemStack.PACKET_CODEC.decode(buf)
                                        : net.minecraft.item.ItemStack.EMPTY;
                                String error = buf.readBoolean() ? buf.readString(512) : null;
                                entries.add(new Entry(functionId, name, group, itemText, stack, error));
                            }
                            packs.add(new Pack(id, title, List.copyOf(entries)));
                        }
                        return new ServerCatalogS2CPacket(List.copyOf(packs), buf.readBoolean());
                    }
            );

 @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
