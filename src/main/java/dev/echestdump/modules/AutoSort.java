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
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Auto Sort: раскладывает вещи по сундукам в радиусе по классам (оружие, броня, инструменты, еда ...).
 *
 * 1) SCAN: по очереди открывает все сундуки/бочки в радиусе и запоминает, какие классы где лежат.
 * 2) Каждому классу назначается "родной" сундук (где этого класса уже больше всего; если сундуков меньше,
 *    чем классов — несколько классов делят один сундук).
 * 3) SORT: заходит в каждый сундук: кладёт туда подходящие предметы из инвентаря и вытаскивает в инвентарь
 *    чужие, чтобы донести их до своего сундука (следующим заходом / проходом). Проходов несколько.
 */
public class AutoSort extends Module {
    private enum Cat {
        WEAPONS("Оружие"), ARMOR("Броня"), TOOLS("Инструменты"), FOOD("Еда"), POTIONS("Зелья"),
        RESOURCES("Ресурсы"), REDSTONE("Редстоун"), BLOCKS("Блоки"), MISC("Разное");

        final String title;
        Cat(String title) { this.title = title; }
    }

    private enum Stage { OPEN, WAIT, WORK }

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Double> radius = sg.add(new DoubleSetting.Builder()
        .name("radius")
        .description("Радиус поиска сундуков (от глаз). Сервер обычно не даёт открыть дальше ~5.5.")
        .defaultValue(5.0).min(1.0).sliderMax(6.0)
        .build());

    private final Setting<Boolean> barrels = sg.add(new BoolSetting.Builder()
        .name("barrels")
        .description("Сортировать ещё и бочки.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> keepHotbar = sg.add(new BoolSetting.Builder()
        .name("keep-hotbar")
        .description("Не трогать предметы, лежащие в хотбаре на момент запуска.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> clicksPerTick = sg.add(new IntSetting.Builder()
        .name("clicks-per-tick")
        .description("Сколько стаков перекладывать за тик. Больше = быстрее, но риск кика за спам пакетами.")
        .defaultValue(6).min(1).sliderMax(27)
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

    private final Setting<Integer> maxPasses = sg.add(new IntSetting.Builder()
        .name("max-passes")
        .description("Максимум проходов по всем сундукам (предметы, вытащенные из сундука, доносятся до своего сундука).")
        .defaultValue(5).min(1).sliderMax(10)
        .build());

    private final Setting<Boolean> rotate = sg.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Поворачиваться к сундуку перед открытием (нужно на серверах с проверкой ротации).")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> autoDisable = sg.add(new BoolSetting.Builder()
        .name("auto-disable")
        .description("Выключать модуль после сортировки.")
        .defaultValue(true)
        .build());

    private static final Block CHEST = Blocks.CHEST;
    private static final Block TRAPPED_CHEST = Blocks.TRAPPED_CHEST;
    private static final Block BARREL = Blocks.BARREL;

    private Stage stage;
    private boolean sorting;                       // false = сканируем, true = раскладываем
    private List<BlockPos> targets = new ArrayList<>();
    private final Set<BlockPos> skip = new HashSet<>();
    private final Map<BlockPos, int[]> scan = new LinkedHashMap<>();   // сколько предметов каждого класса в сундуке
    private final EnumMap<Cat, BlockPos> homeOf = new EnumMap<>(Cat.class);
    private final boolean[] hotbarKeep = new boolean[9];
    private final Set<Integer> tried = new HashSet<>();

    private BlockPos current;
    private int idx, waited, attempts, cooldown, passes, clicksPass, moved, chestSlots;
    private Object lastLevel;

    public AutoSort() {
        super(EChestDumpAddon.CATEGORY, "auto-sort",
            "Раскладывает предметы из инвентаря и сундуков по сундукам в радиусе по классам: оружие, броня, инструменты, еда и т.д.");
    }

    // ---------------------------------------------------------------- жизненный цикл

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            toggle();
            return;
        }
        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) mc.setScreen(null);

        scan.clear();
        homeOf.clear();
        skip.clear();
        tried.clear();
        lastLevel = mc.world;

        for (int i = 0; i < 9; i++) {
            hotbarKeep[i] = keepHotbar.get() && !mc.player.playerScreenHandler.getSlot(36 + i).getStack().isEmpty();
        }

        targets = findTargets();
        if (targets.isEmpty()) {
            error("Сундуков в радиусе %.1f не найдено.", radius.get());
            toggle();
            return;
        }

        sorting = false;
        idx = 0;
        passes = 1;
        clicksPass = 0;
        moved = 0;
        cooldown = 0;
        stage = Stage.OPEN;
        info("Сканирую сундуков: %d.", targets.size());
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null && mc.player.currentScreenHandler != mc.player.playerScreenHandler && stage != null) mc.setScreen(null);
        stage = null;
    }

    // ---------------------------------------------------------------- главный цикл

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || stage == null) return;
        if (lastLevel != mc.world) {            // сменился мир/измерение
            error("Мир сменился — сортировка остановлена.");
            finish(false);
            return;
        }
        if (cooldown > 0) { cooldown--; return; }

        switch (stage) {
            case OPEN -> {
                // игрок сам что-то открыл — ждём, не мешаем
                if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) return;

                while (idx < targets.size() && skip.contains(targets.get(idx))) idx++;
                if (idx >= targets.size()) {
                    endOfList();
                    return;
                }
                current = targets.get(idx);
                attempts = 0;
                waited = 0;
                openChest(current);
                stage = Stage.WAIT;
            }
            case WAIT -> {
                ScreenHandler menu = mc.player.currentScreenHandler;
                if (menu != mc.player.playerScreenHandler && menu instanceof GenericContainerScreenHandler cm) {
                    chestSlots = cm.getRows() * 9;
                    if (!sorting) {
                        readScan(cm);
                        nextChest();
                    } else {
                        tried.clear();
                        stage = Stage.WORK;
                        workTick(cm);
                    }
                    return;
                }
                if (++waited >= openTimeout.get()) {
                    if (++attempts >= 3) {
                        skip.add(current);          // не открылся (закрыт сверху, нет доступа) — пропускаем
                        scan.remove(current);
                        nextChest();
                        return;
                    }
                    openChest(current);
                    waited = 0;
                }
            }
            case WORK -> {
                ScreenHandler menu = mc.player.currentScreenHandler;
                if (menu == mc.player.playerScreenHandler || !(menu instanceof GenericContainerScreenHandler cm)) {
                    nextChestNoClose();             // меню закрылось само
                    return;
                }
                workTick(cm);
            }
        }
    }

    private void nextChest() {
        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) mc.setScreen(null);
        nextChestNoClose();
    }

    private void nextChestNoClose() {
        idx++;
        stage = Stage.OPEN;
        cooldown = delay.get();
    }

    private void endOfList() {
        if (!sorting) {
            if (scan.isEmpty()) {
                error("Ни один сундук не удалось открыть.");
                finish(false);
                return;
            }
            assignHomes();
            if (homeOf.isEmpty()) {
                info("Нечего сортировать.");
                finish(false);
                return;
            }
            for (Map.Entry<Cat, BlockPos> e : homeOf.entrySet()) {
                BlockPos p = e.getValue();
                info("%s -> %d %d %d", e.getKey().title, p.getX(), p.getY(), p.getZ());
            }
            sorting = true;
            targets = new ArrayList<>(scan.keySet());
            skip.clear();
            idx = 0;
            passes = 1;
            clicksPass = 0;
            stage = Stage.OPEN;
            return;
        }

        // конец прохода: если что-то двигали и проходы ещё остались — идём заново
        if (clicksPass > 0 && passes < maxPasses.get()) {
            passes++;
            idx = 0;
            clicksPass = 0;
            stage = Stage.OPEN;
            return;
        }
        finish(true);
    }

    private void finish(boolean report) {
        if (mc.player != null && mc.player.currentScreenHandler != mc.player.playerScreenHandler) mc.setScreen(null);
        if (report) {
            info("Готово: переложено стаков %d, проходов %d.", moved, passes);
            int left = leftoverStacks();
            if (left > 0) warning("В инвентаре осталось %d стаков, которым не хватило места в своём сундуке.", left);
        }
        stage = null;
        if (autoDisable.get() && isActive()) toggle();
    }

    // ---------------------------------------------------------------- сканирование и назначение сундуков

    private void readScan(GenericContainerScreenHandler menu) {
        int[] counts = new int[Cat.values().length];
        for (int i = 0; i < chestSlots; i++) {
            ItemStack s = menu.getSlot(i).getStack();
            if (!s.isEmpty()) counts[categorize(s).ordinal()] += s.getCount();
        }
        scan.put(current, counts);

        // двойной сундук: вторая половинка открывается тем же меню — второй раз её не открываем
        if (chestSlots == 54) {
            Object block = mc.world.getBlockState(current).getBlock();
            for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                BlockPos n = current.offset(d);
                if (mc.world.getBlockState(n).getBlock() == block) skip.add(n);
            }
        }
    }

    private void assignHomes() {
        homeOf.clear();
        List<BlockPos> chests = new ArrayList<>(scan.keySet());
        int[] inv = inventoryCounts();
        Set<BlockPos> usedChests = new HashSet<>();
        Set<Cat> usedCats = EnumSet.noneOf(Cat.class);

        // 1) жадно: пара (сундук, класс) с наибольшим количеством предметов, по одному классу на сундук
        while (true) {
            BlockPos bestPos = null;
            Cat bestCat = null;
            int best = 0;
            for (BlockPos p : chests) {
                if (usedChests.contains(p)) continue;
                int[] c = scan.get(p);
                for (Cat cat : Cat.values()) {
                    if (usedCats.contains(cat)) continue;
                    if (c[cat.ordinal()] > best) {
                        best = c[cat.ordinal()];
                        bestPos = p;
                        bestCat = cat;
                    }
                }
            }
            if (bestPos == null) break;
            homeOf.put(bestCat, bestPos);
            usedChests.add(bestPos);
            usedCats.add(bestCat);
        }

        // 2) классы, у которых пока нет сундука (если они вообще встречаются)
        for (Cat cat : Cat.values()) {
            if (usedCats.contains(cat)) continue;
            int o = cat.ordinal();
            boolean present = inv[o] > 0;
            for (BlockPos p : chests) if (scan.get(p)[o] > 0) present = true;
            if (!present) continue;

            BlockPos pick = null;
            int bestTotal = Integer.MAX_VALUE;
            for (BlockPos p : chests) {                    // свободный сундук с наименьшим содержимым
                if (usedChests.contains(p)) continue;
                int total = Arrays.stream(scan.get(p)).sum();
                if (total < bestTotal) { bestTotal = total; pick = p; }
            }
            if (pick != null) {
                usedChests.add(pick);
            } else {                                       // сундуков не хватает — делим с тем, где этого класса больше
                int best = -1;
                for (BlockPos p : chests) {
                    int c = scan.get(p)[o];
                    if (c > best) { best = c; pick = p; }
                }
            }
            homeOf.put(cat, pick);
            usedCats.add(cat);
        }
    }

    private int[] inventoryCounts() {
        int[] counts = new int[Cat.values().length];
        ScreenHandler inv = mc.player.playerScreenHandler;
        for (int i = 9; i <= 44; i++) {
            if (i >= 36 && hotbarKeep[i - 36]) continue;
            ItemStack s = inv.getSlot(i).getStack();
            if (!s.isEmpty()) counts[categorize(s).ordinal()] += s.getCount();
        }
        return counts;
    }

    private int leftoverStacks() {
        int n = 0;
        ScreenHandler inv = mc.player.playerScreenHandler;
        for (int i = 9; i <= 44; i++) {
            if (i >= 36 && hotbarKeep[i - 36]) continue;
            ItemStack s = inv.getSlot(i).getStack();
            if (!s.isEmpty() && homeOf.get(categorize(s)) != null) n++;
        }
        return n;
    }

    // ---------------------------------------------------------------- работа внутри открытого сундука

    private void workTick(GenericContainerScreenHandler menu) {
        int total = chestSlots + 36;
        int max = clicksPerTick.get();
        int clicks = 0;

        // 1) из инвентаря в этот сундук — всё, что относится к его классу(ам)
        for (int i = chestSlots; i < total && clicks < max; i++) {
            if (tried.contains(i) || isProtected(i)) continue;
            ItemStack s = menu.getSlot(i).getStack();
            if (s.isEmpty()) continue;
            if (current.equals(homeOf.get(categorize(s)))) {
                quickMove(menu, i);
                tried.add(i);
                clicks++;
            }
        }

        // 2) из этого сундука в инвентарь — чужие предметы (их доставит следующий заход)
        int free = 0;
        for (int i = chestSlots; i < total; i++) if (menu.getSlot(i).getStack().isEmpty()) free++;
        for (int i = 0; i < chestSlots && clicks < max && free > 0; i++) {
            if (tried.contains(i)) continue;
            ItemStack s = menu.getSlot(i).getStack();
            if (s.isEmpty()) continue;
            BlockPos home = homeOf.get(categorize(s));
            if (home == null || home.equals(current)) continue;
            quickMove(menu, i);
            tried.add(i);
            clicks++;
            free--;
        }

        clicksPass += clicks;
        moved += clicks;
        if (clicks == 0) nextChest();   // здесь делать больше нечего — закрываем и идём к следующему
    }

    /** Слот меню — это слот хотбара, занятый на момент запуска (его не трогаем). */
    private boolean isProtected(int slot) {
        int hot = slot - (chestSlots + 27);
        return hot >= 0 && hot < 9 && hotbarKeep[hot];
    }

    private void quickMove(ScreenHandler menu, int slot) {
        mc.interactionManager.clickSlot(menu.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);
    }

    // ---------------------------------------------------------------- классификация предметов

    private static final Set<String> FOOD = Set.of(
        "apple", "golden_apple", "enchanted_golden_apple", "bread", "beef", "porkchop", "chicken", "mutton", "rabbit",
        "cod", "salmon", "tropical_fish", "pufferfish", "carrot", "golden_carrot", "potato", "baked_potato",
        "poisonous_potato", "beetroot", "beetroot_soup", "mushroom_stew", "rabbit_stew", "suspicious_stew",
        "melon_slice", "sweet_berries", "glow_berries", "cookie", "pumpkin_pie", "cake", "dried_kelp",
        "rotten_flesh", "spider_eye", "honey_bottle", "chorus_fruit");

    private static final Set<String> REDSTONE = Set.of(
        "redstone", "redstone_torch", "redstone_lamp", "redstone_block", "repeater", "comparator", "piston",
        "sticky_piston", "observer", "hopper", "dropper", "dispenser", "lever", "tripwire_hook", "tnt",
        "daylight_detector", "target", "note_block", "slime_block", "honey_block", "lightning_rod",
        "sculk_sensor", "crafter", "rail", "powered_rail", "detector_rail", "activator_rail");

    private static final Set<String> RESOURCES = Set.of(
        "diamond", "emerald", "coal", "charcoal", "lapis_lazuli", "quartz", "amethyst_shard", "netherite_scrap",
        "ancient_debris", "iron_block", "gold_block", "diamond_block", "emerald_block", "netherite_block",
        "coal_block", "lapis_block", "copper_block", "ender_pearl", "blaze_rod", "blaze_powder", "ghast_tear",
        "nether_star", "slime_ball", "gunpowder", "string", "feather", "leather", "bone", "bone_meal", "stick",
        "flint", "clay_ball", "brick", "nether_brick", "phantom_membrane", "shulker_shell", "end_crystal");

    private static String nameOf(ItemStack stack) {
        try {
            String id = stack.getItem().getTranslationKey();     // например "item.minecraft.netherite_pickaxe"
            return id.substring(id.lastIndexOf('.') + 1);
        } catch (Throwable t) {
            return "";
        }
    }

    private static Cat categorize(ItemStack stack) {
        String n = nameOf(stack);

        if (n.endsWith("_helmet") || n.endsWith("_chestplate") || n.endsWith("_leggings") || n.endsWith("_boots")
            || n.endsWith("_horse_armor") || n.equals("wolf_armor") || n.equals("elytra") || n.equals("shield")
            || n.equals("turtle_helmet") || n.equals("totem_of_undying")) return Cat.ARMOR;

        if (n.endsWith("_sword") || n.endsWith("_spear") || n.equals("bow") || n.equals("crossbow")
            || n.equals("trident") || n.equals("mace") || n.endsWith("arrow")) return Cat.WEAPONS;

        if (n.endsWith("_pickaxe") || n.endsWith("_axe") || n.endsWith("_shovel") || n.endsWith("_hoe")
            || n.equals("shears") || n.equals("fishing_rod") || n.equals("flint_and_steel") || n.equals("brush")
            || n.equals("spyglass") || n.equals("compass") || n.equals("clock")) return Cat.TOOLS;

        if (n.equals("potion") || n.equals("splash_potion") || n.equals("lingering_potion")
            || n.equals("experience_bottle") || n.equals("ominous_bottle")) return Cat.POTIONS;

        if (n.startsWith("cooked_") || FOOD.contains(n)) return Cat.FOOD;

        if (REDSTONE.contains(n) || n.endsWith("_button") || n.endsWith("_pressure_plate")) return Cat.REDSTONE;

        if (n.endsWith("_ingot") || n.endsWith("_nugget") || n.endsWith("_ore") || n.startsWith("raw_")
            || RESOURCES.contains(n)) return Cat.RESOURCES;

        if (n.endsWith("shulker_box")) return Cat.MISC;

        try {
            if (stack.getItem() instanceof BlockItem) return Cat.BLOCKS;
        } catch (Throwable ignored) {}

        return Cat.MISC;
    }

    // ---------------------------------------------------------------- поиск и открытие сундуков

    private List<BlockPos> findTargets() {
        double ex = mc.player.getX();
        double ey = mc.player.getEyeY();
        double ez = mc.player.getZ();
        double rad = radius.get();
        int r = (int) Math.ceil(rad);
        double maxSq = rad * rad;

        List<BlockPos> list = new ArrayList<>();
        List<Double> dist = new ArrayList<>();
        int bx = (int) Math.floor(ex), by = (int) Math.floor(ey), bz = (int) Math.floor(ez);
        for (int x = bx - r; x <= bx + r; x++) {
            for (int y = by - r; y <= by + r; y++) {
                for (int z = bz - r; z <= bz + r; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = mc.world.getBlockState(pos);
                    Object block = state.getBlock();
                    boolean isBarrel = barrels.get() && BARREL != null && block == BARREL;
                    boolean isChest = (CHEST != null && block == CHEST) || (TRAPPED_CHEST != null && block == TRAPPED_CHEST);
                    if (!isBarrel && !isChest) continue;

                    double dx = x + 0.5 - ex, dy = y + 0.5 - ey, dz = z + 0.5 - ez;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d > maxSq) continue;
                    if (isChest && isBlockedAbove(pos)) continue;   // сундук с блоком сверху ванильный сервер не откроет

                    // вставка с сортировкой по расстоянию
                    int at = 0;
                    while (at < dist.size() && dist.get(at) <= d) at++;
                    list.add(at, pos);
                    dist.add(at, d);
                }
            }
        }
        return list;
    }

    private boolean isBlockedAbove(BlockPos pos) {
        try {
            BlockPos up = pos.up();
            return mc.world.getBlockState(up).isSolidBlock(mc.world, up);
        } catch (Throwable t) {
            return false;
        }
    }

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

    private static Direction nearestFace(double dx, double dy, double dz) {
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) return dy > 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

}
