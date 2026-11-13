package dev.echestdump.modules;

import dev.echestdump.EChestDumpAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BlockPosSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
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
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Miner Pro: автошахтёр на Baritone.
 *  - копает выбранные блоки/руды;
 *  - выкидывает лишнее (всё, чего не было в инвентаре при запуске и что не относится к добыче);
 *  - когда инвентарь полон — идёт к сундуку по координатам и сдаёт добычу;
 *  - когда кирка садится — идёт копать "руду для починки" (нужен Mending: чинит опыт);
 *  - если игрок стоит на месте — перезапускает Baritone.
 */
public class MinerPro extends Module {
    private enum State { MINING, REPAIRING, GOING_CHEST, OPENING, DEPOSITING }

    private final SettingGroup sgMine = settings.createGroup("Добыча");
    private final SettingGroup sgInv = settings.createGroup("Инвентарь и сундук");
    private final SettingGroup sgRepair = settings.createGroup("Починка кирки");
    private final SettingGroup sgStuck = settings.createGroup("Анти-застревание");

    // ---- добыча
    private final Setting<List<Block>> targets = sgMine.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Какие блоки / руды добывать.")
        .defaultValue(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE)
        .build());

    private final Setting<List<Item>> keepItems = sgMine.add(new ItemListSetting.Builder()
        .name("extra-keep-items")
        .description("Дополнительные предметы, которые нужно оставлять и сдавать в сундук (если дроп руды не распознан автоматически).")
        .defaultValue(new Item[0])
        .build());

    // ---- инвентарь и сундук
    private final Setting<Boolean> dropJunk = sgInv.add(new BoolSetting.Builder()
        .name("drop-junk")
        .description("Выбрасывать ВСЁ, кроме выбранной добычи и предметов, которые лежали в инвентаре при запуске.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> dropInterval = sgInv.add(new IntSetting.Builder()
        .name("drop-interval")
        .description("Как часто проверять и выбрасывать лишнее (тики).")
        .defaultValue(20).min(5).sliderMax(100)
        .build());

    private final Setting<Boolean> useChest = sgInv.add(new BoolSetting.Builder()
        .name("use-chest")
        .description("Когда основной инвентарь забит добычей — идти к сундуку и сдавать её. Если выключено — модуль просто остановится.")
        .defaultValue(true)
        .build());

    private final Setting<BlockPos> chestPos = sgInv.add(new BlockPosSetting.Builder()
        .name("chest-pos")
        .description("Координаты сундука (или бочки), куда сдавать добычу.")
        .defaultValue(new BlockPos(0, 0, 0))
        .build());

    private final Setting<Boolean> fullStacks = sgInv.add(new BoolSetting.Builder()
        .name("require-full-stacks")
        .description("Идти к сундуку, только когда ВСЕ слоты основного инвентаря (без хотбара) заполнены полными стаками добычи. Если выключить — достаточно, чтобы в них не было пустых слотов.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> clicksPerTick = sgInv.add(new IntSetting.Builder()
        .name("clicks-per-tick")
        .description("Сколько стаков сдавать в сундук за тик.")
        .defaultValue(6).min(1).sliderMax(27)
        .build());

    private final Setting<Boolean> throughWalls = sgInv.add(new BoolSetting.Builder()
        .name("through-walls")
        .description("Открывать сундук через стены: не требовать прямой видимости, а слать запрос открытия, как только сундук в зоне досягаемости. Если сервер отклонил — пробуются другие грани. Серверы с античитом такое могут не пропустить.")
        .defaultValue(true)
        .build());

    private final Setting<Double> wallReach = sgInv.add(new DoubleSetting.Builder()
        .name("wall-reach")
        .description("С какого расстояния (от глаз до центра сундука) пытаться открыть через стену. Ванильный сервер принимает примерно до 5.5.")
        .defaultValue(4.5).min(2).max(6).sliderMax(6)
        .build());

    private final Setting<Boolean> rotate = sgInv.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Поворачиваться к сундуку перед открытием (многие серверы без этого не дают открыть контейнер).")
        .defaultValue(true)
        .build());

    // ---- починка
    private final Setting<Boolean> repair = sgRepair.add(new BoolSetting.Builder()
        .name("repair-pickaxe")
        .description("Когда кирка садится — добывать руду для починки (опыт чинит кирку с Mending).")
        .defaultValue(true)
        .build());

    private final Setting<List<Block>> repairBlocks = sgRepair.add(new BlockListSetting.Builder()
        .name("repair-blocks")
        .description("Какую руду копать ради опыта для починки (например, незеритовый кварц).")
        .defaultValue(Blocks.NETHER_QUARTZ_ORE)
        .build());

    private final Setting<Double> repairBelow = sgRepair.add(new DoubleSetting.Builder()
        .name("repair-below-%")
        .description("Идти чиниться, когда у лучшей кирки в хотбаре осталось меньше этого процента прочности.")
        .defaultValue(15).min(1).max(99).sliderMax(60)
        .build());

    private final Setting<Double> repairUntil = sgRepair.add(new DoubleSetting.Builder()
        .name("repair-until-%")
        .description("Чиниться, пока прочность не дойдёт до этого процента.")
        .defaultValue(90).min(2).max(100).sliderMax(100)
        .build());

    private final Setting<Integer> repairTimeout = sgRepair.add(new IntSetting.Builder()
        .name("repair-timeout")
        .description("Если за столько секунд прочность не выросла (нет Mending / нет руды) — модуль остановится, чтобы не сломать кирку.")
        .defaultValue(120).min(20).sliderMax(600)
        .build());

    // ---- застревание
    private final Setting<Boolean> antiStuck = sgStuck.add(new BoolSetting.Builder()
        .name("anti-stuck")
        .description("Если игрок стоит на месте — перезапускать Baritone.")
        .defaultValue(true)
        .build());

    private final Setting<Double> stuckSeconds = sgStuck.add(new DoubleSetting.Builder()
        .name("stuck-seconds")
        .description("Сколько секунд стоять на месте, прежде чем перезапустить Baritone. Не ставьте слишком мало — добыча тоже занимает время.")
        .defaultValue(5).min(1).sliderMax(30)
        .build());

    private final Setting<Double> stuckDistance = sgStuck.add(new DoubleSetting.Builder()
        .name("stuck-distance")
        .description("Сдвиг меньше этого числа блоков считается \"стоит на месте\".")
        .defaultValue(0.6).min(0.1).sliderMax(3)
        .build());

    private State state;
    private boolean startPending, needOpen;
    private int cooldown, tickCount, waited, attempts, chestSlots, stuckTicks, idleTicks, idleRestarts;
    private int repairStartedTick;
    private double ax, ay, az, bestRepairSeen;
    private int lastProgressTick;
    private final Set<String> snapshot = new HashSet<>();
    private final Set<Integer> tried = new HashSet<>();
    private Object lastLevel;
    private int fullWarnTick = -1000;
    private boolean reapproached;
    private int openTry;
    private int approachRange = 2;
    private double openReach = 4.2;

    public MinerPro() {
        super(EChestDumpAddon.CATEGORY, "miner-pro",
            "Автошахтёр на Baritone: копает выбранные блоки, выбрасывает лишнее, сдаёт добычу в сундук, чинит кирку и сам выходит из застреваний.");
    }

    // ---------------------------------------------------------------- старт / стоп

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) { toggle(); return; }

        if (!BaritoneBridge.available()) {
            error("Baritone не найден. Установите baritone-meteor в папку mods.");
            toggle();
            return;
        }
        if (targets.get().isEmpty()) {
            error("Выберите хотя бы один блок в настройке blocks.");
            toggle();
            return;
        }
        if (useChest.get()) {
            BlockPos p = chestPos.get();
            if (p.getX() == 0 && p.getY() == 0 && p.getZ() == 0) {
                error("Укажите координаты сундука в настройке chest-pos (или выключите use-chest).");
                toggle();
                return;
            }
        }
        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) mc.setScreen(null);

        // запоминаем, что лежало в инвентаре на момент запуска — это не выбрасываем
        snapshot.clear();
        ScreenHandler inv = mc.player.playerScreenHandler;
        for (int i = 9; i <= 44; i++) {
            ItemStack s = inv.getSlot(i).getStack();
            if (!s.isEmpty()) snapshot.add(itemName(s));
        }

        lastLevel = mc.world;
        tickCount = 0;
        cooldown = 0;
        idleRestarts = 0;
        state = State.MINING;
        startPending = true;
        resetStuck();
        info("Miner Pro запущен.");
    }

    @Override
    public void onDeactivate() {
        try { if (state != null) BaritoneBridge.cancel(); } catch (Throwable ignored) {}
        if (mc.player != null && mc.player.currentScreenHandler != mc.player.playerScreenHandler) mc.setScreen(null);
        state = null;
    }

    private void fail(String msg) {
        error(msg);
        toggle();
    }

    // ---------------------------------------------------------------- главный цикл

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (state == null || mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (lastLevel != mc.world) { fail("Мир сменился — Miner Pro остановлен."); return; }
        if (cooldown > 0) { cooldown--; return; }
        tickCount++;

        try {
            switch (state) {
                case MINING, REPAIRING, GOING_CHEST -> tickTask();
                case OPENING -> tickOpening();
                case DEPOSITING -> tickDepositing();
            }
        } catch (Throwable t) {
            fail("Ошибка Miner Pro: " + t);
        }
    }

    private void tickTask() {
        if (startPending) {
            issue();
            startPending = false;
            resetStuck();
            return;
        }

        if (dropJunk.get() && tickCount % dropInterval.get() == 0) dropJunk();

        switch (state) {
            case MINING -> {
                if (repair.get() && needRepair()) {
                    info("Кирка села — иду чиниться (копаю руду для починки).");
                    switchTo(State.REPAIRING);
                    repairStartedTick = tickCount;
                    lastProgressTick = tickCount;
                    bestRepairSeen = Math.max(0, bestPickFraction());
                    return;
                }
                if (tickCount % 10 == 0) {
                    if (readyToDeposit()) {
                        if (!useChest.get()) { fail("Инвентарь забит добычей — Miner Pro остановлен."); return; }
                        info("Инвентарь забит добычей — иду к сундуку.");
                        reapproached = false;
                        approachRange = 2;
                        openReach = throughWalls.get() ? wallReach.get() : 4.2;
                        openTry = 0;
                        switchTo(State.GOING_CHEST);
                        return;
                    }
                    if (noEmptySlots() && tickCount - fullWarnTick > 400) {
                        fullWarnTick = tickCount;
                        warning("Инвентарь без пустых слотов, но условие сдачи не выполнено (в основном инвентаре есть не добыча или неполные стаки).");
                    }
                }
            }
            case REPAIRING -> {
                double f = bestPickFraction();
                if (f < 0 || f * 100.0 >= repairUntil.get()) {
                    info("Кирка починена — возвращаюсь к добыче.");
                    switchTo(State.MINING);
                    return;
                }
                if (f > bestRepairSeen + 0.003) {
                    bestRepairSeen = f;
                    lastProgressTick = tickCount;
                } else if ((tickCount - lastProgressTick) >= repairTimeout.get() * 20) {
                    fail("Прочность кирки не растёт — нужен Mending или руда для починки не находится. Miner Pro остановлен.");
                    return;
                }
            }
            case GOING_CHEST -> {
                BlockPos c = chestPos.get();
                double dx = c.getX() + 0.5 - mc.player.getX();
                double dy = c.getY() + 0.5 - mc.player.getEyeY();
                double dz = c.getZ() + 0.5 - mc.player.getZ();
                double d2 = dx * dx + dy * dy + dz * dz;
                // подошёл на расстояние руки, либо Baritone уже остановился недалеко (радиус цели мог оказаться больше)
                boolean near = d2 <= openReach * openReach || (idleTicks >= 5 && d2 <= 5.5 * 5.5);
                if (near) {
                    BaritoneBridge.cancel();
                    state = State.OPENING;
                    needOpen = true;
                    attempts = 0;
                    waited = 0;
                    cooldown = 3;
                    return;
                }
            }
            default -> {}
        }

        // Baritone ничего не делает (закончил / потерял цель) — через пару секунд пнём заново
        if (!BaritoneBridge.busy()) {
            if (++idleTicks >= 60) {
                idleTicks = 0;
                if (++idleRestarts % 10 == 0) warning("Baritone долго простаивает — возможно, нужных блоков рядом нет.");
                BaritoneBridge.cancel();
                startPending = true;
                cooldown = 5;
                return;
            }
        } else {
            idleTicks = 0;
        }

        if (antiStuck.get()) checkStuck();
    }

    private void switchTo(State s) {
        BaritoneBridge.cancel();
        state = s;
        startPending = true;
        cooldown = 3;
        idleTicks = 0;
        resetStuck();
    }

    private void issue() {
        switch (state) {
            case MINING -> {
                Block[] b = targets.get().toArray(new Block[0]);
                if (b.length == 0) { fail("Список blocks пуст."); return; }
                BaritoneBridge.mine(b);
            }
            case REPAIRING -> {
                Block[] b = repairBlocks.get().toArray(new Block[0]);
                if (b.length == 0) { fail("Список repair-blocks пуст — нечего копать для починки."); return; }
                BaritoneBridge.mine(b);
            }
            case GOING_CHEST -> BaritoneBridge.goNear(chestPos.get(), approachRange);
            default -> {}
        }
    }

    // ---------------------------------------------------------------- анти-застревание

    private void resetStuck() {
        if (mc.player == null) return;
        ax = mc.player.getX();
        ay = mc.player.getY();
        az = mc.player.getZ();
        stuckTicks = 0;
    }

    private void checkStuck() {
        double dx = mc.player.getX() - ax, dy = mc.player.getY() - ay, dz = mc.player.getZ() - az;
        double lim = stuckDistance.get();
        if (dx * dx + dy * dy + dz * dz > lim * lim) {
            resetStuck();
            return;
        }
        if (++stuckTicks >= stuckSeconds.get() * 20.0) {
            info("Стою на месте — перезапускаю Baritone.");
            BaritoneBridge.cancel();
            startPending = true;
            cooldown = 5;
            resetStuck();
        }
    }

    // ---------------------------------------------------------------- инвентарь

    private static String itemName(ItemStack stack) {
        try {
            String id = stack.getItem().getTranslationKey();
            return id.substring(id.lastIndexOf('.') + 1);
        } catch (Throwable t) {
            return "";
        }
    }

    /** Имена предметов, которые считаются добычей: дроп выбранных блоков + extra-keep-items. */
    private Set<String> collectNames() {
        Set<String> names = new HashSet<>();
        for (Block block : targets.get()) {
            String id = block.getTranslationKey();
            String bn = id.substring(id.lastIndexOf('.') + 1);
            names.add(bn);
            String core = bn.startsWith("deepslate_") ? bn.substring("deepslate_".length()) : bn;
            names.add(core);
            if (core.endsWith("_ore")) {
                String base = core.substring(0, core.length() - 4);
                switch (base) {
                    case "lapis" -> names.add("lapis_lazuli");
                    case "nether_quartz" -> names.add("quartz");
                    case "nether_gold" -> names.add("gold_nugget");
                    case "iron", "gold", "copper" -> names.add("raw_" + base);
                    default -> names.add(base);                 // diamond, emerald, coal, redstone
                }
            } else if (core.equals("glowstone")) {
                names.add("glowstone_dust");
            } else if (core.equals("amethyst_cluster")) {
                names.add("amethyst_shard");
            }
        }
        for (Item item : keepItems.get()) {
            String id = item.getTranslationKey();
            names.add(id.substring(id.lastIndexOf('.') + 1));
        }
        return names;
    }

    /**
     * Готов идти к сундуку: во ВСЕХ слотах основного инвентаря (9..35, хотбар не считается) лежат
     * стаки добычи (полные, если включено require-full-stacks). Слоты с предметами, что были в инвентаре
     * при запуске, не мешают — там алмазы лежать всё равно не могут.
     */
    private boolean readyToDeposit() {
        Set<String> collect = collectNames();
        ScreenHandler inv = mc.player.playerScreenHandler;
        boolean anyCollect = false;
        for (int i = 9; i <= 35; i++) {
            ItemStack s = inv.getSlot(i).getStack();
            if (s.isEmpty()) return false;
            if (!collect.contains(itemName(s))) continue;
            anyCollect = true;
            if (fullStacks.get() && s.getCount() < s.getMaxCount()) return false;
        }
        return anyCollect;
    }

    private boolean noEmptySlots() {
        ScreenHandler inv = mc.player.playerScreenHandler;
        for (int i = 9; i <= 44; i++) if (inv.getSlot(i).getStack().isEmpty()) return false;
        return true;
    }

    private boolean hasDepositable() {
        Set<String> collect = collectNames();
        ScreenHandler inv = mc.player.playerScreenHandler;
        for (int i = 9; i <= 44; i++) {
            ItemStack s = inv.getSlot(i).getStack();
            if (!s.isEmpty() && collect.contains(itemName(s))) return true;
        }
        return false;
    }

    private void dropJunk() {
        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) return;
        Set<String> keep = new HashSet<>(snapshot);
        keep.addAll(collectNames());
        ScreenHandler inv = mc.player.playerScreenHandler;
        int dropped = 0;
        for (int i = 9; i <= 44 && dropped < 5; i++) {
            ItemStack s = inv.getSlot(i).getStack();
            if (s.isEmpty()) continue;
            if (keep.contains(itemName(s))) continue;
            // button = 1 -> выбросить весь стак
            mc.interactionManager.clickSlot(inv.syncId, i, 1, SlotActionType.THROW, mc.player);
            dropped++;
        }
    }

    /** Лучшая доля прочности среди кирок в хотбаре (0..1), либо -1, если кирок с прочностью нет. */
    private double bestPickFraction() {
        ScreenHandler inv = mc.player.playerScreenHandler;
        double best = -1;
        for (int i = 36; i <= 44; i++) {
            ItemStack s = inv.getSlot(i).getStack();
            if (s.isEmpty() || !itemName(s).endsWith("_pickaxe") || !s.isDamageable()) continue;
            int max = s.getMaxDamage();
            if (max <= 0) continue;
            double f = (max - s.getDamage()) / (double) max;
            if (f > best) best = f;
        }
        return best;
    }

    private boolean needRepair() {
        double f = bestPickFraction();
        return f >= 0 && f * 100.0 < repairBelow.get();
    }

    // ---------------------------------------------------------------- сундук

    private void tickOpening() {
        if (needOpen) {
            openChest(chestPos.get());
            needOpen = false;
            waited = 0;
            return;
        }
        ScreenHandler menu = mc.player.currentScreenHandler;
        if (isContainer(menu)) {
            chestSlots = menu.slots.size() - 36;
            tried.clear();
            state = State.DEPOSITING;
            return;
        }
        if (++waited >= 15) {
            attempts++;
            if (attempts == 2 && !reapproached) {
                // два раза не открылось — подойдём вплотную и попробуем ещё
                reapproached = true;
                approachRange = 1;
                openReach = 3.0;
                info("Сундук не открывается — подхожу ближе.");
                switchTo(State.GOING_CHEST);
                return;
            }
            if (attempts >= 4) {
                fail("Не удалось открыть сундук. " + describeChest());
                return;
            }
            openChest(chestPos.get());
            waited = 0;
        }
    }

    /** Открыт именно контейнер (сундук, бочка, шалкер...), а не собственное меню инвентаря игрока. */
    private boolean isContainer(ScreenHandler menu) {
        return menu != null && menu != mc.player.playerScreenHandler && menu.syncId != 0 && menu.slots.size() > 36;
    }

    private String describeChest() {
        BlockPos p = chestPos.get();
        String where = p.getX() + " " + p.getY() + " " + p.getZ();
        try {
            String id = mc.world.getBlockState(p).getBlock().getTranslationKey();
            String name = id.substring(id.lastIndexOf('.') + 1);
            if (name.equals("air") || name.equals("cave_air") || name.equals("void_air")) {
                return "На " + where + " нет блока (воздух) — проверьте координаты chest-pos.";
            }
            BlockPos up = p.up();
            if (name.endsWith("chest") && mc.world.getBlockState(up).isSolidBlock(mc.world, up)) {
                return "На " + where + " лежит сундук, но над ним стоит твёрдый блок — такой сундук не открыть.";
            }
            BlockPos oh = otherHalf(p);
            if (oh != null && mc.world.getBlockState(oh.up()).isSolidBlock(mc.world, oh.up())) {
                return "Это двойной сундук, и над его второй половиной (" + oh.getX() + " " + oh.getY() + " " + oh.getZ() + ") стоит твёрдый блок — такой сундук не открыть.";
            }
            return "На " + where + " находится: " + name + ". Если это не сундук/бочка — проверьте chest-pos. "
                + "Если это сундук, сервер, вероятно, не принимает открытие без прямой видимости.";
        } catch (Throwable t) {
            return "Координаты: " + where + ".";
        }
    }

    private void tickDepositing() {
        ScreenHandler menu = mc.player.currentScreenHandler;
        if (!isContainer(menu)) {
            // меню закрылось само — пробуем ещё раз
            state = State.OPENING;
            needOpen = true;
            waited = 0;
            return;
        }

        Set<String> collect = collectNames();
        int total = chestSlots + 36;
        int clicks = 0;
        for (int i = chestSlots; i < total && clicks < clicksPerTick.get(); i++) {
            if (tried.contains(i)) continue;
            ItemStack s = menu.getSlot(i).getStack();
            if (s.isEmpty() || !collect.contains(itemName(s))) continue;
            mc.interactionManager.clickSlot(menu.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
            tried.add(i);
            clicks++;
        }
        if (clicks > 0) return;

        // всё, что можно было сложить, сложено
        boolean left = false;
        for (int i = chestSlots; i < total; i++) {
            ItemStack s = menu.getSlot(i).getStack();
            if (!s.isEmpty() && collect.contains(itemName(s))) { left = true; break; }
        }
        mc.setScreen(null);
        if (left) {
            fail("Сундук заполнен — часть добычи не поместилась. Miner Pro остановлен.");
            return;
        }
        info("Добыча сдана — возвращаюсь к работе.");
        state = State.MINING;
        startPending = true;
        cooldown = 5;
        resetStuck();
    }

    private void openChest(BlockPos pos) {
        // двойной сундук: нечётные попытки кликаем по второй половине (открывается один и тот же 54-слотовый контейнер)
        BlockPos other = otherHalf(pos);
        if (other != null && openTry % 2 == 1) pos = other;

        // с зажатым шифтом и предметом в руке сундук не откроется (Baritone мог оставить шифт)
        if (mc.player.isSneaking()) mc.player.setSneaking(false);

        double ex = mc.player.getX();
        double ey = mc.player.getEyeY();
        double ez = mc.player.getZ();

        Vec3d center = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        Direction face = bestFace(pos, ex - center.x, ey - center.y, ez - center.z);
        openTry++;
        Vec3d hitVec = new Vec3d(center.x + face.getOffsetX() * 0.5, center.y + face.getOffsetY() * 0.5, center.z + face.getOffsetZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitVec, face, pos, false);

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), () -> BlockUtils.interact(hit, Hand.MAIN_HAND, true));
        } else {
            BlockUtils.interact(hit, Hand.MAIN_HAND, true);
        }
    }

    /**
     * Грань для клика. Обычный режим: повёрнутая к игроку и не закрытая соседним твёрдым блоком.
     * Режим through-walls: грани по убыванию "повёрнутости" к игроку без учёта стен; с каждой попыткой берётся следующая.
     */
    private Direction bestFace(BlockPos pos, double dx, double dy, double dz) {
        Direction[] faces = {Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) len = 1;
        double[] score = new double[6];
        for (int i = 0; i < 6; i++) {
            Direction f = faces[i];
            score[i] = (dx * f.getOffsetX() + dy * f.getOffsetY() + dz * f.getOffsetZ()) / len;
            if (!throughWalls.get()) {
                BlockPos n = pos.offset(f);
                boolean blocked;
                try {
                    blocked = mc.world.getBlockState(n).isSolidBlock(mc.world, n);
                } catch (Throwable t) {
                    blocked = false;
                }
                if (blocked) score[i] -= 10;
            }
        }
        // порядок граней по убыванию score
        Integer[] order = {0, 1, 2, 3, 4, 5};
        java.util.Arrays.sort(order, (x, y) -> Double.compare(score[y], score[x]));
        int rank = throughWalls.get() ? Math.floorMod(openTry, 6) : 0;
        return faces[order[rank]];
    }

    private static String prop(String state, String key) {
        int i = state.indexOf(key + "=");
        if (i < 0) return "";
        int st = i + key.length() + 1;
        int e = st;
        while (e < state.length() && state.charAt(e) != ',' && state.charAt(e) != ']') e++;
        return state.substring(st, e);
    }

    /** Вторая половина двойного сундука (или null, если сундук одинарный / это не сундук). */
    private BlockPos otherHalf(BlockPos pos) {
        try {
            var st = mc.world.getBlockState(pos);
            String s = st.toString();
            String type = prop(s, "type");
            if (!type.equals("left") && !type.equals("right")) return null;
            String facing = prop(s, "facing");
            String want = type.equals("left") ? "right" : "left";
            for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                BlockPos n = pos.offset(d);
                var ns = mc.world.getBlockState(n);
                if (ns.getBlock() != st.getBlock()) continue;
                String t = ns.toString();
                if (prop(t, "type").equals(want) && prop(t, "facing").equals(facing)) return n;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Direction nearestFace(double dx, double dy, double dz) {
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) return dy > 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
