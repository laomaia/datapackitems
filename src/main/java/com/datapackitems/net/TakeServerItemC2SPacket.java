package com.datapackitems.net;

import com.datapackitems.DataPackItems;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/**
 * 客户端 → 服务端：把某件登记物品给我一份（服务端代发）。
 *
 * <p>★ 为什么只发函数 ID、不发整件 ItemStack：
 * 客户端手里的栈是服务端发下去的副本，改包可以伪造。只传 ID，
 * 服务端拿自己的目录去查，发下去的永远是数据包里那份原始物品。
 *
 * <p>★ 什么时候用它：B/C 模式下服务端装了模组，走这条路更稳（不依赖玩家 OP、
 * 也不怕数据组件过长）。A 模式下没有服务端，客户端自己发 /give。
 */
public record TakeServerItemC2SPacket(String functionId) implements CustomPayload {

    public static final CustomPayload.Id<TakeServerItemC2SPacket> ID =
            new CustomPayload.Id<>(DataPackItems.id("take"));

    public static final PacketCodec<RegistryByteBuf, TakeServerItemC2SPacket> CODEC =
            PacketCodec.of(
                    (packet, buf) -> buf.writeString(packet.functionId(), 512),
                    buf -> new TakeServerItemC2SPacket(buf.readString(512))
            );

 @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
