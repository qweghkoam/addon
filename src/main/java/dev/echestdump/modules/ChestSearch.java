package dev.echestdump.modules;

import dev.echestdump.EChestDumpAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Names;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.Hand;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chest Search: по очереди открывает сундуки в радиусе, смотрит что внутри и подсвечивает те,
 * где лежит выбранный предмет. Содержимое запоминается, так что смена предмета подсвечивает новые сундуки сразу.
 */
public class ChestSearch extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Подсветка");

    private final Setting<Double> radius = sg.add(new DoubleSetting.Builder()
        .name("radius")
        .description("Радиус поиска сундуков (от глаз). Сервер обычно не даёт открыть дальше ~5.5.")
        .defaultValue(5.0).min(1.0).sliderMax(6.0)
        .build());

    private final Setting<Item> targetItem = sg.add(new ItemSetting.Builder()
        .name("item")
        .description("Какой предмет искать в сундуках.")
        .defaultValue(Items.ELYTRA)
        .build());

    private final Setting<Boolean> barrels = sg.add(new BoolSetting.Builder()
        .name("barrels")
        .description("Проверять ещё и бочки.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> delay = sg.add(new IntSetting.Builder()
        .name("delay")
        .description("Пауза между сундуками, тики.")
        .defaultValue(3).min(0).sliderMax(20)
        .build());

    private final Setting<Integer> openTimeout = sg.add(new IntSetting.Builder()
        .name("open-timeout")
        .description("Сколько тиков ждать открытия сундука до повторной попытки (всего 3 попытки).")
        .defaultValue(8).min(2).sliderMax(40)
        .build());

    private final Setting<Boolean> rotate = sg.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Поворачиваться к сундуку перед открытием (нужно на серверах с проверкой ротации).")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> notify = sg.add(new BoolSetting.Builder()
        .name("notify")
        .description("Писать в чат координаты сундука, когда в нём нашёлся предмет.")
        .defaultValue(true)
        .build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Как рисовать подсветку.")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Цвет заливки.")
        .defaultValue(new SettingColor(255, 40, 40, 60))
        .build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Цвет контура.")
        .defaultValue(new SettingColor(255, 40, 40, 255))
        .build());

    private enum Stage { IDLE, WAIT_MENU }

    private static final Block CHEST = Blocks.CHEST;
    private static final Block TRAPPED_CHEST = Blocks.TRAPPED_CHEST;
    private static final Block BARREL = Blocks.BARREL;

    private final Map<BlockPos, Set<Item>> contents = new HashMap<>();
    private final Set<BlockPos> failed = new HashSet<>();
    private Stage stage = Stage.IDLE;
    private BlockPos current;
    private int waited, attempts, cooldown;
    private Object lastLevel;

    public ChestSearch() {
        super(EChestDumpAddon.CATEGORY, "chest-search",
            "Открывает сундуки в радиусе, проверяет содержимое и подсвечивает те, где есть выбранный предмет.");
    }

    @Override
    public void onActivate() {
        clearState();
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null && mc.player.currentScreenHandler != mc.player.playerScreenHandler && stage == Stage.WAIT_MENU) closeMenu();
        clearState();
    }

    private void clearState() {
        contents.clear();
        failed.clear();
        stage = Stage.IDLE;
        current = null;
        waited = 0;
        attempts = 0;
        cooldown = 0;
        lastLevel = mc.world;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (lastLevel != mc.world) clearState();   // сменился мир/измерение

        switch (stage) {
            case IDLE -> {
                if (cooldown > 0) { cooldown--; return; }
                // игрок сам открыл что-то — не мешаем
                if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) return;
                current = findNext();
                if (current == null) return;
                attempts = 0;
                waited = 0;
                openChest(current);
                stage = Stage.WAIT_MENU;
            }
            case WAIT_MENU -> {
                ScreenHandler menu = mc.player.currentScreenHandler;
                if (menu != mc.player.playerScreenHandler && menu instanceof GenericContainerScreenHandler) {
                    readMenu(menu);
                    closeMenu();
                    stage = Stage.IDLE;
                    cooldown = delay.get();
                    return;
                }
                if (++waited >= openTimeout.get()) {
                    if (++attempts >= 3) {
                        failed.add(current);   // не открылся (закрыт, далеко, нет доступа) — больше не трогаем
                        stage = Stage.IDLE;
                        cooldown = delay.get();
                        return;
                    }
                    openChest(current);
                    waited = 0;
                }
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (contents.isEmpty()) return;
        Item want = targetItem.get();
        for (Map.Entry<BlockPos, Set<Item>> e : contents.entrySet()) {
            if (e.getValue().contains(want)) {
                event.renderer.box(e.getKey(), sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            }
        }
    }

    // ---------------------------------------------------------------- поиск

    private BlockPos findNext() {
        double ex = mc.player.getX();
        double ey = mc.player.getEyeY();
        double ez = mc.player.getZ();
        double rad = radius.get();
        int r = (int) Math.ceil(rad);
        double maxSq = rad * rad;

        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        int bx = (int) Math.floor(ex), by = (int) Math.floor(ey), bz = (int) Math.floor(ez);
        for (int x = bx - r; x <= bx + r; x++) {
            for (int y = by - r; y <= by + r; y++) {
                for (int z = bz - r; z <= bz + r; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (contents.containsKey(pos) || failed.contains(pos)) continue;
                    BlockState state = mc.world.getBlockState(pos);
                    Object block = state.getBlock();
                    boolean isBarrel = barrels.get() && block == BARREL && BARREL != null;
                    boolean isChest = (block == CHEST && CHEST != null) || (block == TRAPPED_CHEST && TRAPPED_CHEST != null);
                    if (!isBarrel && !isChest) continue;

                    double dx = x + 0.5 - ex, dy = y + 0.5 - ey, dz = z + 0.5 - ez;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d > maxSq) continue;

                    // сундук с блоком сверху ванильный сервер не откроет (бочку — откроет)
                    if (isChest && isBlockedAbove(pos)) { failed.add(pos); continue; }

                    if (d < bestD) {
                        bestD = d;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    private boolean isBlockedAbove(BlockPos pos) {
        try {
            BlockPos up = pos.up();
            return mc.world.getBlockState(up).isSolidBlock(mc.world, up);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- открытие / чтение

    private void openChest(BlockPos pos) {
        double ex = mc.player.getX();
        double ey = mc.player.getEyeY();
        double ez = mc.player.getZ();

        Vec3d center = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        Direction face = nearestFace(ex - center.x, ey - center.y, ez - center.z);
        Vec3d hitVec = new Vec3d(center.x + face.getOffsetX() * 0.5, center.y + face.getOffsetY() * 0.5, center.z + face.getOffsetZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitVec, face, pos, false);

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(center), Rotations.getPitch(center), () -> BlockUtils.interact(hit, Hand.MAIN_HAND, true));
        } else {
            BlockUtils.interact(hit, Hand.MAIN_HAND, true);
        }
    }

    private void readMenu(ScreenHandler menu) {
        int slots = ((GenericContainerScreenHandler) menu).getRows() * 9;
        Set<Item> items = new HashSet<>();
        for (int i = 0; i < slots; i++) {
            ItemStack stack = menu.getSlot(i).getStack();
            if (!stack.isEmpty()) items.add(stack.getItem());
        }
        contents.put(current, items);

        Item want = targetItem.get();
        if (notify.get() && items.contains(want)) {
            info("Найдено (%s): сундук %d %d %d.", Names.get(want), current.getX(), current.getY(), current.getZ());
        }
    }

    private void closeMenu() {
        // setScreen(null) закрывает открытый контейнер и сам отправляет серверу пакет закрытия
        mc.setScreen(null);
    }

    private static Direction nearestFace(double dx, double dy, double dz) {
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) return dy > 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

}
