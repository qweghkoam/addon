package dev.echestdump.modules;

import dev.echestdump.EChestDumpAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.Hand;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Аварийная выгрузка: снимает броню, открывает ближайший эндер-сундук (стена не важна)
 * и перекладывает вещи в него по приоритету ценности.
 */
public class EChestDump extends Module {
    private enum Stage { STRIP_AND_OPEN, WAIT_MENU, DUMP }

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Double> range = sg.add(new DoubleSetting.Builder()
        .name("range")
        .description("Радиус поиска эндер-сундука (от глаз). Сервер обычно не даёт открыть дальше ~5.5.")
        .defaultValue(5.0).min(1.0).sliderMax(6.0)
        .build());

    private final Setting<Integer> clicksPerTick = sg.add(new IntSetting.Builder()
        .name("clicks-per-tick")
        .description("Сколько предметов перекладывать за тик. Больше = быстрее, но риск кика за спам пакетами.")
        .defaultValue(6).min(1).sliderMax(27)
        .build());

    private final Setting<Boolean> stripArmor = sg.add(new BoolSetting.Builder()
        .name("strip-armor")
        .description("Снимать надетую броню/элитры перед открытием сундука.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> stripOffhand = sg.add(new BoolSetting.Builder()
        .name("strip-offhand")
        .description("Класть в сундук и предмет из второй руки (тотем и т.п.).")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> dumpEverything = sg.add(new BoolSetting.Builder()
        .name("dump-everything")
        .description("Выключено: мусор (булыжник, земля и т.п.) не кладётся, место остаётся для ценного.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> rotate = sg.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Поворачиваться к сундуку перед открытием (нужно на серверах с проверкой ротации).")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> closeAfter = sg.add(new BoolSetting.Builder()
        .name("close-after")
        .description("Закрыть сундук, когда всё сложено.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> autoDisable = sg.add(new BoolSetting.Builder()
        .name("auto-disable")
        .description("Выключать модуль после выгрузки.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> openTimeout = sg.add(new IntSetting.Builder()
        .name("open-timeout")
        .description("Сколько тиков ждать открытия сундука, прежде чем нажать на него ещё раз.")
        .defaultValue(10).min(2).sliderMax(40)
        .build());

    private Stage stage;
    private BlockPos chestPos;
    private int waited;
    private int openAttempts;
    private int passes;
    private record Entry(int slot, Item item) {}

    private List<Entry> queue;
    private ScreenHandler dumpMenu;
    private int chestSlots;
    private int moved;

    public EChestDump() {
        super(EChestDumpAddon.CATEGORY, "echest-dump",
            "Снимает броню, открывает ближайший эндер-сундук и скидывает в него вещи по ценности (элитры > броня > незерит > остальное).");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            toggle();
            return;
        }
        chestPos = findChest();
        if (chestPos == null) {
            error("Эндер-сундук в радиусе %.1f не найден.", range.get());
            toggle();
            return;
        }
        if (isBlockedAbove(chestPos)) {
            warning("Над эндер-сундуком блок — ванильный сервер его не откроет!");
        }
        stage = Stage.STRIP_AND_OPEN;
        waited = 0;
        openAttempts = 0;
        passes = 0;
        queue = null;
        moved = 0;
    }

    @Override
    public void onDeactivate() {
        queue = null;
        dumpMenu = null;
        stage = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || stage == null) return;

        switch (stage) {
            case STRIP_AND_OPEN -> {
                // Если с прошлого прохода меню ещё открыто — сначала закрываем.
                if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) {
                    closeMenu();
                }
                stripEquipment();
                openChest();
                waited = 0;
                stage = Stage.WAIT_MENU;
            }
            case WAIT_MENU -> {
                ScreenHandler menu = mc.player.currentScreenHandler;
                if (menu != mc.player.playerScreenHandler && menu instanceof GenericContainerScreenHandler) {
                    buildQueue(menu);
                    stage = Stage.DUMP;
                    // сразу же начинаем перекладывать в этом же тике
                    dumpTick(menu);
                    return;
                }
                waited++;
                if (waited >= openTimeout.get()) {
                    openAttempts++;
                    if (openAttempts >= 3) {
                        error("Не удалось открыть эндер-сундук (закрыт блоком сверху или слишком далеко?).");
                        finish();
                        return;
                    }
                    openChest();
                    waited = 0;
                }
            }
            case DUMP -> {
                ScreenHandler menu = mc.player.currentScreenHandler;
                if (menu == mc.player.playerScreenHandler || !(menu instanceof GenericContainerScreenHandler)) {
                    // меню закрылось само
                    finish();
                    return;
                }
                dumpTick(menu);
            }
        }
    }

    // ---------------------------------------------------------------- поиск сундука

    private BlockPos findChest() {
        double ex = mc.player.getX();
        double ey = mc.player.getEyeY();
        double ez = mc.player.getZ();
        int r = (int) Math.ceil(range.get());
        double maxSq = range.get() * range.get();

        BlockPos best = null;
        double bestKey = Double.MAX_VALUE;

        int bx = (int) Math.floor(ex), by = (int) Math.floor(ey), bz = (int) Math.floor(ez);
        for (int x = bx - r; x <= bx + r; x++) {
            for (int y = by - r; y <= by + r; y++) {
                for (int z = bz - r; z <= bz + r; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = mc.world.getBlockState(pos);
                    if (state.getBlock() != Blocks.ENDER_CHEST) continue;

                    double dx = x + 0.5 - ex, dy = y + 0.5 - ey, dz = z + 0.5 - ez;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d > maxSq) continue;

                    // закрытые сверху сундуки — в самый конец очереди
                    double key = d + (isBlockedAbove(pos) ? 10000.0 : 0.0);
                    if (key < bestKey) {
                        bestKey = key;
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

    // ---------------------------------------------------------------- снятие брони

    private void stripEquipment() {
        if (!stripArmor.get() && !stripOffhand.get()) return;

        ScreenHandler inv = mc.player.playerScreenHandler;
        int free = 0;
        for (int i = 9; i <= 44; i++) {
            if (inv.getSlot(i).getStack().isEmpty()) free++;
        }

        // порядок: грудь (элитры) -> голова -> ноги -> ботинки
        int[] armorSlots = {6, 5, 7, 8};
        if (stripArmor.get()) {
            for (int slot : armorSlots) {
                if (free <= 0) break;
                if (inv.getSlot(slot).getStack().isEmpty()) continue;
                quickMove(inv, slot);
                free--;
            }
        }
        if (stripOffhand.get() && free > 0 && !inv.getSlot(45).getStack().isEmpty()) {
            quickMove(inv, 45);
        }
    }

    private boolean equipmentLeft() {
        ScreenHandler inv = mc.player.playerScreenHandler;
        if (stripArmor.get()) {
            for (int i = 5; i <= 8; i++) if (!inv.getSlot(i).getStack().isEmpty()) return true;
        }
        if (stripOffhand.get() && !inv.getSlot(45).getStack().isEmpty()) return true;
        return false;
    }

    private int freeMainSlots() {
        ScreenHandler inv = mc.player.playerScreenHandler;
        int free = 0;
        for (int i = 9; i <= 44; i++) if (inv.getSlot(i).getStack().isEmpty()) free++;
        return free;
    }

    // ---------------------------------------------------------------- открытие сундука

    private void openChest() {
        if (chestPos == null) return;
        double ex = mc.player.getX();
        double ey = mc.player.getEyeY();
        double ez = mc.player.getZ();

        Vec3d center = new Vec3d(chestPos.getX() + 0.5, chestPos.getY() + 0.5, chestPos.getZ() + 0.5);
        Direction face = nearestFace(ex - center.x, ey - center.y, ez - center.z);
        Vec3d hitVec = new Vec3d(center.x + face.getOffsetX() * 0.5, center.y + face.getOffsetY() * 0.5, center.z + face.getOffsetZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitVec, face, chestPos, false);

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(center), Rotations.getPitch(center), () -> BlockUtils.interact(hit, Hand.MAIN_HAND, true));
        } else {
            BlockUtils.interact(hit, Hand.MAIN_HAND, true);
        }
    }

    // ---------------------------------------------------------------- выгрузка

    private void buildQueue(ScreenHandler menu) {
        chestSlots = ((GenericContainerScreenHandler) menu).getRows() * 9;
        dumpMenu = menu;
        int size = chestSlots + 36;

        List<int[]> entries = new ArrayList<>();
        for (int i = chestSlots; i < size; i++) {
            ItemStack stack = menu.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            int score = score(stack);
            if (!dumpEverything.get() && score < 200) continue;
            entries.add(new int[]{i, score});
        }
        // по убыванию ценности; при равенстве — по порядку слотов
        entries.sort((a, b) -> a[1] != b[1] ? Integer.compare(b[1], a[1]) : Integer.compare(a[0], b[0]));

        queue = new ArrayList<>(entries.size());
        for (int[] e : entries) queue.add(new Entry(e[0], menu.getSlot(e[0]).getStack().getItem()));
    }

    private void dumpTick(ScreenHandler menu) {
        // Меню должно быть тем же самым, что и при составлении очереди — иначе номера слотов уже не те.
        if (menu != dumpMenu) {
            finish();
            return;
        }

        int clicks = 0;
        while (queue != null && !queue.isEmpty() && clicks < clicksPerTick.get()) {
            Entry e = queue.remove(0);

            // Жёсткая защита: кликаем ТОЛЬКО по слотам инвентаря игрока, никогда по слотам самого сундука.
            if (e.slot() < chestSlots || e.slot() >= chestSlots + 36) continue;

            ItemStack now = menu.getSlot(e.slot()).getStack();
            if (now.isEmpty()) continue;
            // В слоте уже другой предмет (рассинхрон) — не трогаем.
            if (now.getItem() != e.item()) continue;

            quickMove(menu, e.slot());
            clicks++;
            moved++;
        }

        if (queue != null && queue.isEmpty()) {
            // Броня могла остаться на теле, если не было места в инвентаре — делаем второй проход.
            if (passes < 1 && equipmentLeft() && freeMainSlots() > 0) {
                passes++;
                queue = null;
                dumpMenu = null;
                stage = Stage.STRIP_AND_OPEN;
                return;
            }
            finish();
        }
    }

    private void finish() {
        if (mc.player != null && closeAfter.get() && mc.player.currentScreenHandler != mc.player.playerScreenHandler) {
            closeMenu();
        }
        if (stage != null && moved > 0) info("Переложено стаков: %d.", moved);
        stage = null;
        queue = null;
        if (autoDisable.get() && isActive()) toggle();
    }

    private void closeMenu() {
        // setScreen(null) закрывает открытый контейнер и сам отправляет серверу пакет закрытия
        mc.setScreen(null);
    }

    private static Direction nearestFace(double dx, double dy, double dz) {
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) return dy >= 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private void quickMove(ScreenHandler menu, int slot) {
        mc.interactionManager.clickSlot(menu.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);
    }

    // ---------------------------------------------------------------- приоритет предметов

    private int score(ItemStack stack) {
        try {
            String id = stack.getItem().getTranslationKey(); // например "item.minecraft.netherite_pickaxe"
            String name = id.substring(id.lastIndexOf('.') + 1);
            return baseScore(name);
        } catch (Throwable t) {
            return 100;
        }
    }

    private static int baseScore(String n) {
        if (n.equals("elytra")) return 1000;

        boolean armor = n.endsWith("_helmet") || n.endsWith("_chestplate") || n.endsWith("_leggings") || n.endsWith("_boots");
        if (armor) {
            if (n.startsWith("netherite_")) return 900;
            if (n.startsWith("diamond_")) return 880;
            return 700;
        }

        boolean tool = n.endsWith("_sword") || n.endsWith("_pickaxe") || n.endsWith("_axe")
            || n.endsWith("_shovel") || n.endsWith("_hoe") || n.endsWith("_spear");
        if (tool) {
            if (n.startsWith("netherite_")) return 850;
            if (n.startsWith("diamond_")) return 750;
            return 300;
        }

        switch (n) {
            case "totem_of_undying": return 680;
            case "enchanted_golden_apple": return 670;
            case "netherite_block": return 665;
            case "netherite_ingot": return 660;
            case "netherite_scrap": return 650;
            case "ancient_debris": return 645;
            case "mace": case "trident": return 640;
            case "shield": return 520;
            case "diamond_block": return 600;
            case "diamond": return 590;
            case "end_crystal": return 560;
            case "ender_pearl": return 550;
            case "experience_bottle": return 540;
            case "golden_apple": return 530;
            case "emerald_block": return 480;
            case "emerald": return 470;
            case "gold_block": case "iron_block": return 400;
            case "gold_ingot": case "iron_ingot": return 380;
            case "firework_rocket": return 420;
            case "crossbow": case "bow": return 410;
            case "obsidian": case "crying_obsidian": return 350;
            case "cooked_beef": case "cooked_porkchop": case "golden_carrot": return 260;
            default: break;
        }

        if (n.endsWith("shulker_box")) return 630;
        if (n.endsWith("_chestplate") || n.equals("turtle_helmet")) return 700;
        if (n.endsWith("_netherite_upgrade_smithing_template") || n.equals("netherite_upgrade_smithing_template")) return 500;
        if (n.endsWith("_ore") || n.startsWith("raw_")) return 300;

        return 100;
    }
}
