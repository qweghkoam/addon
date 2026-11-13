package dev.echestdump.modules;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;

/** Проверка "это состояние данного блока" (в 1.21.4 прямой вызов isOf, рефлексия не нужна и ломается из-за intermediary-имён). */
public final class BlockCompat {
    private BlockCompat() {}

    public static boolean isBlock(BlockState state, Block block) {
        return state != null && block != null && state.isOf(block);
    }
}
