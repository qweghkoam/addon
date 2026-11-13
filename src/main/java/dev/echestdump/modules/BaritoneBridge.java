package dev.echestdump.modules;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.Block;

/**
 * Вся работа с Baritone собрана здесь, чтобы остальной аддон нормально загружался,
 * даже если Baritone не установлен (тогда ошибка возникнет только при вызове этих методов).
 */
final class BaritoneBridge {
    private BaritoneBridge() {}

    static boolean available() {
        try {
            Class.forName("baritone.api.BaritoneAPI");
            return BaritoneAPI.getProvider().getPrimaryBaritone() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static IBaritone b() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    static void mine(Block[] blocks) {
        b().getMineProcess().mine(blocks);
    }

    static void goNear(BlockPos pos, int range) {
        b().getCustomGoalProcess().setGoalAndPath(new GoalNear(pos, range));
    }

    static void cancel() {
        IBaritone bar = b();
        bar.getMineProcess().cancel();
        bar.getPathingBehavior().cancelEverything();
    }

    static boolean busy() {
        IBaritone bar = b();
        return bar.getPathingBehavior().isPathing()
            || bar.getMineProcess().isActive()
            || bar.getCustomGoalProcess().isActive();
    }
}
