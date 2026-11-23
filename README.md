# 数据包物品页（Datapack Item Panel）

> **一个 jar，三种形态**：只装客户端 → 读本地数据包；两边都装 → **直接显示服务端加载的数据包**。
> 环境：Minecraft **1.21.1** / Fabric / Java 21

## 它解决什么

写数据包时会习惯把「注册物品用」的函数放在 `data/<命名空间>/function/item/give/`，
每条里一行 `give`。这个模组把这些函数变成一个类创造物品栏的面板，点一下领一件。

## ★ 一个 jar 覆盖 A / B / C

装在哪边，就自动是哪种形态，不需要你选：

| 形态 | 服务端 | 客户端 | 数据来源 | 能看到服务端数据包 |
| --- | --- | --- | --- | --- |
| **A** | 不装 | 装 | 本地磁盘 | ❌ |
| **B** | 装 | 装 | **服务端推送** | ✅ |
| **C** | 装 | 别人可能没装 | 服务端推送 | ✅ |

客户端启动时会用 `ClientPlayNetworking.canSend` 探测「服务端有没有装本模组」：

- **探测成功** → 服务端模式，请求并接收服务端扫描的结果
- **探测失败** → 本地模式，自己读磁盘

这是**同一个 jar**，`fabric.mod.json` 里同时有 `main`（服务端）和 `client` 两个入口，
Fabric 按环境各取所需。服务端没装时 `main` 不会执行；服务端装了但某些玩家没装客户端时，
所有网络包都先 `canSend` 探测，不会给没装的客户端发东西，游戏本身完全不受影响。

### 为什么 A 形态看不到服务端数据包

协议层面没有这条数据。1.21.1 所有 S2C 包里只有指令名（`CommandTreeS2CPacket`）、
配方、成就这类会同步，**没有任何一个包携带函数文件内容**。`give` 函数的正文
始终只存在于服务端磁盘上。所以「在服务端扫、推给客户端」是唯一可行的做法 —— 那就是 B。

## 用法

游戏内按 **`G`** 打开（「设置 → 按键控制 → 数据包物品页」可改键）。

| 操作 | 效果 |
| --- | --- |
| 一级页签（面板上方） | 切换数据包 |
| 二级页签（标题下方） | 切换 `give/` 下的子文件夹 |
| 左键点格子 | 领一份 |
| 右键点格子 | 复制 `/function <完整ID>` 到剪贴板 |
| 滚轮 | 翻页（一页 9×5） |
| `R` 键 | 重新取数据 |

版面对齐原版创造物品栏：主面板 **195×136**，物品格 **9×5**、每格 **18×18**。

## 权限

| 动作 | 要求 |
| --- | --- |
| **查看目录** | 所有玩家（B/C 模式下） |
| **领取物品** | **OP 等级 2**，和 `/give` 一致 |

查看不设限是有意的：纯客户端模式（A）下普通玩家本来就能看本地数据包，
如果服务端模式却要求 OP，同一个模组两种形态体验就割裂了。
真正需要拦住的是领取，那一步服务端会再判一次，客户端改包也绕不过。

## A 形态（只装客户端）

按顺序找数据包：

1. 当前单人存档 `saves/<存档名>/datapacks/`
2. 全局 `.minecraft/datapacks/`
3. 配置里手写的目录 `extraPackDirs`

连别人服务器时对方没装本模组，就只能用第 3 种 —— 把你手上那份数据包放到本地：

```json
{
  "givePrefixes": ["item/give", "give"],
  "extraPackDirs": ["E:/mc/datapacks"]
}
```

## 它认哪些函数

默认只认函数路径以 `item/give` 开头的，即
`data/<命名空间>/function/item/give/**/*.mcfunction`，另外也认裸的 `function/give/`。

```
data/gubao/function/item/give/xsz_a.mcfunction           → 根目录分类
data/gubao/function/item/give/book/rizhi1.mcfunction     → book 分类
data/gubao/function/item/give/xiansuo/dengji1.mcfunction → xiansuo 分类
```

每个函数只取**第一条 give**，按参数还原成 ItemStack
（`custom_name` / `lore` / `custom_model_data` / 书本内容全部原样保留）。

### 为什么不是「执行函数再截获产物」

作者常把 `give [at]s` 和 `give [at]p` 混着写。真执行函数，目标选择器会按
"谁执行"解析，产物跟"这条函数代表哪个物品"无关。字符串解析只看 item 参数。

### 解析失败不会消失

格子画**红叉**，悬停显示原因。最常见的是**服务端没装那个模组**
（比如数据包里写了 `candlelight:`，但服务器没装），这是真实反映。

## 排查

| 现象 | 原因 |
| --- | --- |
| 一直"正在向服务端要…" | 服务端装了但没回包，看服务端日志有无「已把目录推给」 |
| 面板空、显示"服务端没有 give 物品" | 服务端数据包里没有 `item/give`，或路径不匹配 |
| 点了没反应 | 不是 OP，服务端拒绝 |
| A 形态下面板空 | 数据包不在扫描路径里 |

## 构建

```bash
./gradlew build                      # -> build/libs/datapack-items-1.0.0.jar
./gradlew runClient -Pselftest       # 自动进存档 → 取数据 → 开面板 → 截图
```

## 结构

| 文件 | 作用 | 端 |
| --- | --- | --- |
| `DataPackItems` | main 入口，注册服务端网络 | 服务端 |
| `server/ServerCatalog` | 扫**服务端加载的**数据包 | 服务端 |
| `server/ServerNetworking` | 推送目录 / 代发物品 | 服务端 |
| `net/*` | 3 个网络包 | 共用 |
| `GiveParser` | give 行 → ItemStack（`ItemStringReader`） | 共用 |
| `DatapackGiveCatalog` | 本地读盘 / zip 扫描 | 客户端 |
| `DatapackItemsConfig` | 前缀 + 额外目录 | 共用 |
| `client/ModeDetector` | **A/B/C 判定** | 客户端 |
| `client/DatapackItemsClientData` | 统一两种来源 + 领取 | 客户端 |
| `client/DatapackItemsScreen` | 界面 | 客户端 |
