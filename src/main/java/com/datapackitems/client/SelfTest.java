package com.datapackitems.client;

import com.datapackitems.DataPackItems;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;

import java.io.File;

/**
 * 开发期自检：进世界后自动扫本地数据包、开面板、截一张图、退出。
 *
 * <p>只在 JVM 带了 { @code -Ddpi.selftest} 时才动作，正式包完全不受影响。
 *
 * <p>为什么需要它：图标没画、版面错位这类问题光靠编译看不出来，
 * 必须真的跑一帧再核对 —— 前几轮全靠这个抓到问题。
 *
 * <p>★ 纯客户端版不需要「提权」了：扫描本地数据、发 /give 都由原版权限自己判定，
 * 测试时只需要看页面画得对不对。
 */
public final class SelfTest {

    private static boolean fired = false;
    private static int debugTicks = 0;
    private static boolean worldRequested = false;
    private static int mainMenuTicks = 0;
    private static int worldTicks = 0;
    /** 0=待扫描 1=已扫描 2=已开面板 3=已截图。 */
    private static int stage = 0;

    /** 自检用的测试存档名（run/saves 下那个）。 */
    private static final String TEST_WORLD = "诡影古堡";

    private SelfTest() {
    }

    public static boolean enabled() {
        return System.getProperty("dpi.selftest") != null;
    }

    /** 每 tick 调用。 */
    public static void tick(MinecraftClient client) {
        if (!enabled()) {
            return;
        }
        if (debugTicks == 0) {
            DataPackItems.LOGGER.info("[self-test] tick alive, world={} player={}",
                    client.world != null, client.player != null);
        }
        debugTicks++;
        if (fired) {
            return;
        }
        // 还没进世界：什么都不做，等玩家自己进。
        // ★ 早先这里试过自动 createIntegratedServerLoader().start(...)，
        //   那会在渲染线程里同步加载世界，直接把游戏卡死（日志停住、CPU 空转）。
        //   宁可手动进一次世界，也不要让自检把游戏搞挂。
        if (client.world == null || client.player == null) {
            return;
        }

        // ── 已进世界：用「阶段」而不是「精确 tick 数」推进 ──
        worldTicks++;
        if (stage == 0 && worldTicks >= 20) {
            ModeDetector.reset();
            DatapackItemsClientData.refresh(client, true);
            // 服务端模式是异步的：推送到了才有内容，本地模式这里是同步的
            if (ModeDetector.detect() == ModeDetector.Source.LOCAL) {
                DatapackItemsClientData.rescanLocal(client);
            }
            log(client, "[self-test] 模式=" + ModeDetector.describe()
                    + " | " + DatapackItemsClientData.note());
            stage = 1;
            return;
        }
        if (stage == 1 && worldTicks >= 30) {
            client.setScreen(new DatapackItemsScreen());
            stage = 2;
            return;
        }
        // ★ 服务端模式是异步的：等到真的收到目录再截图，否则会拍到空面板
        if (stage == 2 && worldTicks >= 55 && DatapackItemsClientData.hasData()) {
            File dir = new File(client.runDirectory, "shots");
            dir.mkdirs();
            ScreenshotRecorder.saveScreenshot(dir, "dpi_selftest.png",
                    client.getFramebuffer(), text -> {
                    });
            log(client, "[self-test] shot taken | " + DatapackItemsClientData.note());
            stage = 3;
            return;
        }
        // 兜底：等太久也别卡死
        if (stage == 2 && worldTicks >= 200) {
            log(client, "[self-test] timed out waiting for catalog: "
                    + DatapackItemsClientData.note());
            stage = 3;
            return;
        }
        if (stage == 3 && worldTicks >= 75) {
            fired = true;
            client.scheduleStop();
        }
    }

    private static void log(MinecraftClient client, String msg) {
        DataPackItems.LOGGER.info(msg);
        if (client.player != null) {
            client.player.sendMessage(Text.literal(msg), false);
        }
    }
}
