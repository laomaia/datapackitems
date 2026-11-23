package com.datapackitems.net;

import com.datapackitems.DataPackItems;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/**
 * 客户端 → 服务端：请把「你加载的数据包」目录发给我。
 *
 * <p>这个包同时也是{ @link com.datapackitems.client.ModeDetector 探测能力的探针}：
 * 客户端只要能 { @code canSend} 它，就说明服务端也装了这个模组（B/C 模式），
 * 否则退回本地读盘（A 模式）。
 */
public record RequestServerCatalogC2SPacket(boolean forceRescan) implements CustomPayload {

    public static final CustomPayload.Id<RequestServerCatalogC2SPacket> ID =
            new CustomPayload.Id<>(DataPackItems.id("server_catalog_request"));

    public static final PacketCodec<RegistryByteBuf, RequestServerCatalogC2SPacket> CODEC =
            PacketCodec.of(
                    (packet, buf) -> buf.writeBoolean(packet.forceRescan()),
                    buf -> new RequestServerCatalogC2SPacket(buf.readBoolean())
            );

 @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
