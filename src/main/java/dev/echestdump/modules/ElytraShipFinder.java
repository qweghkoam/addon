package dev.echestdump.modules;

import dev.echestdump.EChestDumpAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.NoFall;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.util.math.Direction;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Vec3d;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Поиск городов Края по сиду (v1.3): места городов считаются формулой vanilla (spacing 20 / separation 11),
 * а пустые места над бездной отсеиваются расчётом островов Края (TheEndBiomeSource) — город может быть
 * только на острове (биом highlands/midlands). Полёт Pitch40 с ракетой при падении, посадка у корабля, лут.
 */
public class ElytraShipFinder extends Module {
    private enum State { TAKEOFF, CRUISE, LAND, DIG, KILL, HIT, COLLECT, BOX, OPEN, STORE, BREAK, COLLECT_BOX, EXIT, REP_LAND, REP_MINE, REP_ROOF, REP_FIGHT }

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<String> seed = sg.add(new StringSetting.Builder()
        .name("seed")
        .description("Сид мира (число или любая строка, как при создании мира).")
        .defaultValue("")
        .build());

    private final Setting<Integer> height = sg.add(new IntSetting.Builder()
        .name("flight-height")
        .description("Высота полёта (Y).")
        .defaultValue(210).min(120).sliderMax(300)
        .build());

    private final Setting<Integer> searchRegions = sg.add(new IntSetting.Builder()
        .name("search-regions")
        .description("Радиус поиска в регионах (1 регион = 320 блоков).")
        .defaultValue(30).min(2).sliderMax(100)
        .build());

    private final Setting<Integer> minOrigin = sg.add(new IntSetting.Builder()
        .name("min-origin-distance")
        .description("Города Края не бывают ближе этого расстояния от центра (0,0).")
        .defaultValue(1000).min(0).sliderMax(3000)
        .build());

    private final Setting<Integer> skipRadius = sg.add(new IntSetting.Builder()
        .name("pass-radius")
        .description("Если пролетел ближе этого расстояния к городу и рамки с элитрами не видел — город считается пустым.")
        .defaultValue(48).min(8).sliderMax(150)
        .build());

    private final Setting<Integer> minIsland = sg.add(new IntSetting.Builder()
        .name("min-island-height")
        .description("Порог «высоты острова» по формуле Края (0 = любой остров, 40+ = только большие highlands). Города стоят на высоких островах. Если подходящих не осталось — порог сам снижается.")
        .defaultValue(25).min(0).sliderMax(80)
        .build());

    private final Setting<Boolean> rocketOnFall = sg.add(new BoolSetting.Builder()
        .name("rocket-on-fall")
        .description("Если во время Pitch40 начал падать (ниже нижней границы, потерял скорость или элитры закрылись) — раскрыть элитры и использовать фейерверк.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> fallMargin = sg.add(new IntSetting.Builder()
        .name("fall-margin")
        .description("На сколько блоков ниже нижней границы Pitch40 (flight-height - 55) использовать фейерверк.")
        .defaultValue(10).min(2).sliderMax(40)
        .build());

    private final Setting<Integer> rocketDelay = sg.add(new IntSetting.Builder()
        .name("rocket-delay")
        .description("Пауза между фейерверками при падении (в тиках).")
        .defaultValue(12).min(4).sliderMax(40)
        .build());

    private final Setting<Integer> knownX = sg.add(new IntSetting.Builder()
        .name("known-city-x")
        .description("Диагностика: X реального города Края (из /locate structure minecraft:end_city). 0 = выключено.")
        .defaultValue(0).sliderRange(-30000, 30000)
        .build());

    private final Setting<Integer> knownZ = sg.add(new IntSetting.Builder()
        .name("known-city-z")
        .description("Диагностика: Z того же города.")
        .defaultValue(0).sliderRange(-30000, 30000)
        .build());

    private final Setting<Boolean> autoLoot = sg.add(new BoolSetting.Builder()
        .name("auto-loot")
        .description("После посадки: копать вниз к рамке, убить шалкера (KillAura), забрать элитры, сложить в шалкер, выбраться и лететь дальше.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> shulkerRange = sg.add(new IntSetting.Builder()
        .name("shulker-range")
        .description("Шалкер ближе этого расстояния — включаю KillAura и жду его смерти. Дальше или нет шалкера — KillAura выключаю и игнорирую его.")
        .defaultValue(6).min(2).sliderMax(16)
        .build());

    private final Setting<Boolean> noFallAssist = sg.add(new BoolSetting.Builder()
        .name("nofall-on-landing")
        .description("Включать NoFall при посадке.")
        .defaultValue(true)
        .build());

    // --- ремонт элитр на острове Края (v1.4)
    private final Setting<Boolean> repairEnabled = sg.add(new BoolSetting.Builder()
        .name("repair-elytra")
        .description("Когда у элитр мало прочности: сесть на остров Края, добыть эндерняк, построить платформу 4x4 (крыша над головой + пол под ногами), агрить и убивать эндерменов (опыт чинит элитры через Mending), потом лететь дальше.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> repairBelow = sg.add(new IntSetting.Builder()
        .name("repair-below")
        .description("Идти на ремонт, когда у элитр осталось прочности не больше этого значения (макс. прочность элитр = 432).")
        .defaultValue(60).min(5).sliderMax(200)
        .build());

    private final Setting<Integer> maxAggro = sg.add(new IntSetting.Builder()
        .name("repair-max-aggro")
        .description("Сколько эндерменов одновременно агрить (пока они не убиты, новых не агрю).")
        .defaultValue(2).min(1).sliderMax(4)
        .build());

    private final Setting<Double> lookSpeed = sg.add(new DoubleSetting.Builder()
        .name("repair-look-speed")
        .description("Максимальная скорость поворота взгляда на эндермена, градусов за тик. Меньше = плавнее.")
        .defaultValue(6.0).min(1.0).sliderMax(20.0)
        .build());

    private final Setting<Integer> endermanRange = sg.add(new IntSetting.Builder()
        .name("repair-enderman-range")
        .description("Дальность, в пределах которой ищу эндерменов, чтобы на них посмотреть.")
        .defaultValue(40).min(8).sliderMax(64)
        .build());

    private final Setting<Integer> repairMinHealth = sg.add(new IntSetting.Builder()
        .name("repair-min-health")
        .description("Если здоровья не больше этого значения (в половинках сердец) — новых эндерменов не агрю, только отбиваюсь.")
        .defaultValue(10).min(1).sliderMax(20)
        .build());

    // --- состояние
    private State state;
    private long seedValue;
    private long doneSeed = Long.MIN_VALUE;
    private final Set<Long> emptyCities = new HashSet<>();
    private int[] target;
    private double fx, fy, fz;
    private int landY;
    private int tick;
    private int groundedTicks;
    private int lastRocket;
    private final Map<Setting<?>, Object> saved = new LinkedHashMap<>();
    private boolean noFallWasActive;
    private int stateTick;
    private int roofY;
    private int elytraBefore;
    private int landX, landZ;
    private boolean kaOn;
    private boolean exiting;
    private BlockPos boxPos;
    private boolean kaWasActive;
    private final Map<Setting<?>, Object> savedKa = new LinkedHashMap<>();
    private final List<double[]> looted = new ArrayList<>();
    private IslandNoise noise;
    private long noiseSeed = Long.MIN_VALUE;
    private final Map<Long, Cand> cityCache = new HashMap<>();
    private double prevX, prevY, prevZ;
    private boolean havePrev;
    private int rocketTick = -100;
    private boolean boosting;
    private boolean jumpHeld;
    private boolean noRocketWarned;

    // ремонт
    // платформа 4x4: крыша на высоте +2 над ногами (16 блоков) + пол под ногами (достраиваются только дыры)
    // + 2 блока временного столба-опоры. Клетки платформы: от PLAT_MIN до PLAT_MAX относительно якорного блока,
    // игрок стоит в центре (ax+1.0, az+1.0) — это общий угол 4 средних клеток.
    private static final int PLAT = 4;
    private static final int PLAT_MIN = -1;
    private static final int PLAT_MAX = 2;
    private static final int REP_BLOCKS = 22;   // 18 на крышу со столбом + запас на дыры в полу
    private boolean repairing;
    private boolean repairSearchWarned;
    private int repX, repY, repZ;
    private int roofPhase, roofIdx, roofFails, roofAx, roofAy, roofAz, roofPa, roofPb;
    private boolean roofAnchored;
    private int[][] roofList = new int[0][];
    private final Set<BlockPos> floorSkip = new HashSet<>();
    private final List<Entity> endermen = new ArrayList<>();
    private final Map<Integer, Integer> aggro = new HashMap<>();
    private Entity stare;
    private int stareTicks;
    private int noEndermanTicks;
    private int kills;
    private int repStartDamage;
    private boolean repIdleWarned;

    public ElytraShipFinder() {
        super(EChestDumpAddon.CATEGORY, "elytra-ship-finder",
            "Ищет по сиду города Края с кораблём и элитрами, летит к ним (Pitch40) и приземляется. Когда элитры почти сломаны — чинит их на острове (крыша + эндермены + Mending).");
    }

    // ------------------------------------------------------------------ жизненный цикл

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) { toggle(); return; }

        if (seed.get().trim().isEmpty()) {
            error("Введи сид в настройках модуля.");
            toggle();
            return;
        }
        seedValue = parseSeed(seed.get().trim());
        if (seedValue != doneSeed) {
            emptyCities.clear();
            doneSeed = seedValue;
        }
        if (noise == null || noiseSeed != seedValue) {
            noise = new IslandNoise(seedValue);
            noiseSeed = seedValue;
            cityCache.clear();
        }

        if (knownX.get() != 0 || knownZ.get() != 0) {
            calibrate();
            toggle();
            return;
        }

        if (mc.player.getEquippedStack(EquipmentSlot.CHEST).getItem() != Items.ELYTRA) {
            error("Надень элитры (в слот нагрудника).");
            toggle();
            return;
        }
        if (!InvUtils.findInHotbar(Items.FIREWORK_ROCKET).found()) {
            error("Положи фейерверки в хотбар (нужны для набора высоты).");
            toggle();
            return;
        }

        target = pickTarget();
        if (target == null) {
            error("Не нашёл подходящих городов в радиусе поиска.");
            toggle();
            return;
        }
        info("Сид: %d. Считаю острова Края и места городов (первый раз может занять секунду).", seedValue);
        info("Цель: город около X=%d Z=%d (до него ~%d блоков).", target[0], target[1], (int) distTo(target[0], target[1]));

        lastRocket = -100;
        tick = 0;
        groundedTicks = 0;
        exiting = false;
        havePrev = false;
        boosting = false;
        noRocketWarned = false;
        repairing = false;
        repairSearchWarned = false;
        state = State.TAKEOFF;
    }

    @Override
    public void onDeactivate() {
        state = null;
        boosting = false;
        jumpHeld = false;
        havePrev = false;
        if (mc.options != null) {
            mc.options.jumpKey.setPressed(false);
            mc.options.forwardKey.setPressed(false);
        }
        ElytraFly ef = Modules.get().get(ElytraFly.class);
        if (ef != null && ef.isActive()) ef.toggle();
        restore();
        restoreKillAura();
        NoFall nf = Modules.get().get(NoFall.class);
        if (nf != null && nf.isActive() && !noFallWasActive) nf.toggle();
    }

    // ------------------------------------------------------------------ тик

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || state == null) return;
        tick++;
        stateTick++;

        boolean lootState = state != State.TAKEOFF && state != State.CRUISE && state != State.LAND;
        if (lootState) {
            mc.options.forwardKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
        }

        // Любая ошибка внутри модуля больше не крашит игру: выключаемся и пишем причину в чат.
        State at = state;
        try {
            switch (state) {
                case TAKEOFF -> takeoff2();
                case CRUISE -> cruise();
                case LAND -> land();
                case DIG -> dig();
                case KILL -> kill();
                case HIT -> hit();
                case COLLECT -> collect();
                case BOX -> box();
                case OPEN -> open();
                case STORE -> store();
                case BREAK -> breakBox();
                case COLLECT_BOX -> collectBox();
                case EXIT -> exit();
                case REP_LAND -> repLand();
                case REP_MINE -> repMine();
                case REP_ROOF -> repRoof();
                case REP_FIGHT -> repFight();
            }
        } catch (Throwable t) {
            error("Ошибка на этапе %s: %s — модуль выключен (игра не крашнулась).", String.valueOf(at), String.valueOf(t));
            t.printStackTrace();
            toggle();
        }
    }

    /** Элитры почти сломаны, а мы стоим на земле: не взлетаем, а идём чиниться (добыча эндерняка -> крыша -> эндермены -> выход -> взлёт). */
    private boolean repairGuard() {
        if (!repairEnabled.get() || repairing) return false;
        if (!mc.player.isOnGround()) return false;
        if (elytraRemaining() > repairBelow.get()) return false;
        repairing = true;
        kills = 0;
        repStartDamage = mc.player.getEquippedStack(EquipmentSlot.CHEST).getDamage();
        info("Элитры почти сломаны — не взлетаю: добываю эндерняк и чиню элитры, потом вылечу.");
        roofAnchored = false;
        enter(State.REP_MINE);
        return true;
    }

    private void takeoff2() {
        if (repairGuard()) return;
        takeoff();
    }

    private void takeoff() {
        double y = mc.player.getY();
        if (exiting && y < roofY + 3) {
            mc.player.setPitch(-90f); // вылетаем строго вверх по шахте
        } else {
            exiting = false;
            faceTarget(target[0], target[1]);
            mc.player.setPitch(-60f); // смотрим вверх
        }

        if (!mc.player.isGliding()) {
            // Прыжок, затем второе нажатие прыжка в воздухе — ванильный запуск элитр.
            int t = tick % 12;
            mc.options.jumpKey.setPressed(t < 2 || (t >= 4 && t < 6));
            // если ванильный запуск не сработал — шлём пакет сами
            if (t == 6 && !mc.player.isOnGround() && mc.getNetworkHandler() != null) {
                mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
            }
            if (tick > 300) {
                error("Не удалось взлететь (проверь, что элитры надеты и целые).");
                toggle();
            }
            return;
        }
        mc.options.jumpKey.setPressed(false);

        if (y >= height.get()) {
            // Набрали высоту — дальше летит ElytraFly в режиме Pitch40.
            ElytraFly ef = Modules.get().get(ElytraFly.class);
            int h = height.get();
            put(ef.autoPilot, false);
            put(ef.dontGoIntoUnloadedChunks, false);
            put(ef.pitch40upperBounds, (double) (h - 10));
            put(ef.pitch40lowerBounds, (double) (h - 55));
            put(ef.flightMode, ElytraFlightModes.Pitch40);
            if (!ef.isActive()) ef.toggle();
            state = State.CRUISE;
            info("Высота %d достигнута, лечу Pitch40.", (int) y);
            return;
        }

        // фейерверк каждые ~15 тиков, пока не наберём высоту
        if (tick - lastRocket >= 15) {
            if (useRocket()) {
                lastRocket = tick;
            } else {
                error("Закончились фейерверки.");
                toggle();
            }
        }
    }

    private boolean useRocket() {
        FindItemResult r = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
        if (!r.found()) return false;
        if (r.isOffhand()) {
            mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
            mc.player.swingHand(Hand.OFF_HAND);
        } else {
            InvUtils.swap(r.slot(), true);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            mc.player.swingHand(Hand.MAIN_HAND);
            InvUtils.swapBack();
        }
        return true;
    }

    private void cruise() {
        ElytraFly ef = Modules.get().get(ElytraFly.class);

        if (mc.player.isOnGround()) {
            if (++groundedTicks > 20) {
                error("Упал на землю во время полёта — останавливаюсь.");
                toggle();
            }
            return;
        }
        groundedTicks = 0;

        // 0) не падаем ли? (ниже границы Pitch40 / потеряли скорость / элитры закрылись) -> фейерверк
        fallAssist();

        // 1) корабль с элитрами виден?
        if (tick % 4 == 0) {
            ItemFrameEntity frame = findElytraFrame();
            if (frame != null) {
                fx = frame.getX();
                fy = frame.getY();
                fz = frame.getZ();
                landY = computeRoof((int) Math.floor(fx), (int) Math.floor(fy), (int) Math.floor(fz));
                info("Нашёл элитры в рамке: X=%d Y=%d Z=%d. Захожу на посадку.", (int) fx, (int) fy, (int) fz);
                beginLanding(ef);
                return;
            }
        }

        // 1b) элитры почти сломаны -> садимся на остров Края и чиним
        if (repairEnabled.get() && tick % 20 == 0 && elytraRemaining() <= repairBelow.get()) {
            int[] spot = findIslandSpot();
            if (spot != null) {
                repX = spot[0]; repY = spot[1]; repZ = spot[2];
                info("Элитры почти сломаны (осталось %d). Сажусь на остров Края X=%d Y=%d Z=%d чиниться.", elytraRemaining(), repX, repY, repZ);
                beginLanding(ef);
                enter(State.REP_LAND);
                return;
            } else if (!repairSearchWarned) {
                warning("Элитры почти сломаны (осталось %d), но острова Края рядом не вижу — продолжаю лететь и ищу.", elytraRemaining());
                repairSearchWarned = true;
            }
        }

        // 2) пролетели мимо города без элитр
        if (distTo(target[0], target[1]) < skipRadius.get()) {
            emptyCities.add(key(target[0], target[1]));
            info("У места X=%d Z=%d рамки с элитрами не видел, пропускаю.", target[0], target[1]);
            target = pickTarget();
            if (target == null) {
                error("Подходящие города закончились.");
                toggle();
                return;
            }
            info("Следующая цель: X=%d Z=%d (~%d блоков).", target[0], target[1], (int) distTo(target[0], target[1]));
        }

        faceTarget(target[0], target[1]);
    }

    private void beginLanding(ElytraFly ef) {
        state = State.LAND;
        if (ef.isActive()) ef.toggle();
        mc.options.forwardKey.setPressed(false);

        NoFall nf = Modules.get().get(NoFall.class);
        if (nf != null) {
            noFallWasActive = nf.isActive();
            if (noFallAssist.get() && !nf.isActive()) nf.toggle();
        }
    }

    private void land() {
        double px = mc.player.getX(), py = mc.player.getEyeY(), pz = mc.player.getZ();
        double tx = fx, tz = fz, ty = landY + 1.0;

        if (mc.player.isOnGround()) {
            info("Приземлился у корабля (X=%d Y=%d Z=%d). Рамка с элитрами: X=%d Y=%d Z=%d.",
                (int) px, (int) mc.player.getY(), (int) pz, (int) fx, (int) fy, (int) fz);
            if (autoLoot.get()) {
                startLoot();
            } else {
                toggle();
            }
            return;
        }

        double dx = tx - px, dz = tz - pz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        mc.player.setYaw(yaw);

        // наклон: смотрим на точку посадки, но не круче 55° и не выше горизонта
        double pitch = Math.toDegrees(Math.atan2(py - ty, Math.max(dist, 0.5)));
        pitch = Math.max(-5.0, Math.min(55.0, pitch));
        mc.player.setPitch((float) pitch);
    }

    // ------------------------------------------------------------------ поиск целей

    private int[] pickTarget() {
        int[] t = pickTarget(minIsland.get());
        if (t == null && minIsland.get() > 0) {
            warning("Островов с высотой >= %d в радиусе не осталось — снижаю порог до 0.", minIsland.get());
            t = pickTarget(0);
        }
        return t;
    }

    private int[] pickTarget(int minH) {
        double px = mc.player.getX(), pz = mc.player.getZ();
        int pcx = Math.floorDiv((int) Math.floor(px), 16);
        int pcz = Math.floorDiv((int) Math.floor(pz), 16);
        int prx = Math.floorDiv(pcx, 20), prz = Math.floorDiv(pcz, 20);
        int r = searchRegions.get();
        long minSq = (long) minOrigin.get() * minOrigin.get();

        int[] best = null;
        double bestScore = Double.MAX_VALUE;
        for (int rx = prx - r; rx <= prx + r; rx++) {
            for (int rz = prz - r; rz <= prz + r; rz++) {
                Cand c = candidate(rx, rz);
                if (c == null || c.h < minH) continue;
                int bx = c.cx * 16 + 8, bz = c.cz * 16 + 8;
                if ((long) bx * bx + (long) bz * bz < minSq) continue;
                if (emptyCities.contains(key(bx, bz))) continue;
                // чем выше остров, тем вероятнее, что город реально сгенерировался (нужна поверхность Y >= 60)
                double score = distTo(bx, bz) - Math.max(0.0, c.h - 30.0) * 3.0;
                if (score < bestScore) { bestScore = score; best = new int[]{bx, bz}; }
            }
        }
        return best;
    }

    /** Кандидат города в регионе (rx, rz) или null, если там по формуле Края острова нет (бездна). Результат кэшируется. */
    private Cand candidate(int rx, int rz) {
        long k = ((long) rx << 32) ^ (rz & 0xffffffffL);
        if (cityCache.containsKey(k)) return cityCache.get(k);
        int[] c = cityChunk(seedValue, rx, rz);
        Cand res = null;
        // vanilla: в радиусе 64 чанков от центра биом the_end (пустота/центральный остров), городов нет
        if ((long) c[0] * c[0] + (long) c[1] * c[1] > 4096L) {
            float h = noise.islandHeight(c[0] * 2 + 1, c[1] * 2 + 1);
            if (h >= 0f) res = new Cand(c[0], c[1], h);   // h >= 0: биом midlands/highlands — только там бывают города
        }
        cityCache.put(k, res);
        return res;
    }

    /** Координата чанка потенциального города Края в регионе (rx, rz): spacing 20, separation 11, salt 10387313, triangular. */
    private static int[] cityChunk(long worldSeed, int rx, int rz) {
        long s = (long) rx * 341873128712L + (long) rz * 132897987541L + worldSeed + 10387313L;
        Random rnd = new Random(s);
        int k = 20 - 11;
        int a = (rnd.nextInt(k) + rnd.nextInt(k)) / 2;
        int b = (rnd.nextInt(k) + rnd.nextInt(k)) / 2;
        return new int[]{rx * 20 + a, rz * 20 + b};
    }

    /** Сверяет реальный город (из /locate) с формулой и показывает, считает ли формула островов там остров. */
    private void calibrate() {
        int kcx = Math.floorDiv(knownX.get(), 16), kcz = Math.floorDiv(knownZ.get(), 16);
        int rx = Math.floorDiv(kcx, 20), rz = Math.floorDiv(kcz, 20);
        info("Сид: %d. Реальный город: X=%d Z=%d (чанк %d, %d), регион %d, %d.", seedValue, knownX.get(), knownZ.get(), kcx, kcz, rx, rz);
        int[] c = cityChunk(seedValue, rx, rz);
        boolean ok = Math.abs(c[0] - kcx) <= 1 && Math.abs(c[1] - kcz) <= 1;
        info("Формула даёт чанк %d, %d (блок %d, %d) — %s", c[0], c[1], c[0] * 16 + 8, c[1] * 16 + 8, ok ? "СОВПАДАЕТ" : "не совпадает");
        IslandNoise n = new IslandNoise(seedValue);
        info("Высота острова в чанке реального города: %d (норма: 0 и выше; у больших highlands 40-80).", (int) n.islandHeight(kcx * 2 + 1, kcz * 2 + 1));
        if (ok) {
            info("Готово. Очисти known-city-x/z (поставь 0) и запускай модуль.");
        } else {
            warning("Не совпало: либо неверный сид, либо в этой версии другая формула. Пришли мне сид и координаты /locate.");
        }
    }

    private ItemFrameEntity findElytraFrame() {
        ItemFrameEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : mc.world.getEntities()) {
            if (e instanceof ItemFrameEntity f && f.getHeldItemStack().getItem() == Items.ELYTRA && !isLooted(f.getX(), f.getZ())) {
                double dx = f.getX() - mc.player.getX(), dz = f.getZ() - mc.player.getZ();
                double d = dx * dx + dz * dz;
                if (d < bestD) { bestD = d; best = f; }
            }
        }
        return best;
    }

    /** Верхний твёрдый блок над рамкой (крыша корабля), чтобы садиться сверху. */
    private int computeRoof(int x, int y, int z) {
        int top = y - 1;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int yy = Math.min(y + 40, 318); yy > top; yy--) {
                    if (!mc.world.getBlockState(new BlockPos(x + dx, yy, z + dz)).isAir()) {
                        if (dx == 0 && dz == 0) return yy;
                        break;
                    }
                }
            }
        }
        // над самой рамкой пусто — садимся на уровень рамки
        return y - 1;
    }

    // ------------------------------------------------------------------ лут после посадки

    private void enter(State st) {
        state = st;
        stateTick = 0;
        mc.options.forwardKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
    }

    private void startLoot() {
        roofY = (int) Math.floor(mc.player.getY() + 0.01);
        landX = (int) Math.floor(mc.player.getX());
        landZ = (int) Math.floor(mc.player.getZ());
        info("Копаю вниз к рамке.");
        enter(State.DIG);
    }

    /** Копаем блок под собой, пока не окажемся на уровне рамки. */
    private void dig() {
        double eyeDx = fx - mc.player.getX(), eyeDy = fy - mc.player.getEyeY(), eyeDz = fz - mc.player.getZ();
        double d = Math.sqrt(eyeDx * eyeDx + eyeDy * eyeDy + eyeDz * eyeDz);
        if (mc.player.getY() <= fy + 0.6 || d <= 3.0) {
            info("Дошёл до уровня рамки.");
            enter(State.KILL);
            return;
        }
        if (stateTick > 1200) {
            error("Слишком долго копаю вниз — останавливаюсь.");
            toggle();
            return;
        }
        if (!mc.player.isOnGround()) return; // падаем

        BlockPos below = new BlockPos((int) Math.floor(mc.player.getX()), (int) Math.floor(mc.player.getY() - 0.01), (int) Math.floor(mc.player.getZ()));
        var st = mc.world.getBlockState(below);
        if (st.isAir()) return;

        mc.player.setPitch(90f);
        FindItemResult tool = InvUtils.findFastestTool(st);
        if (tool.found() && tool.isHotbar()) InvUtils.swap(tool.slot(), false);
        BlockUtils.breakBlock(below, true);
    }

    /** Шалкер совсем рядом — включаем KillAura и ждём; нет шалкера рядом — KillAura выключаем и забываем про него. */
    private void kill() {
        Entity shulker = nearestShulker(shulkerRange.get());
        if (shulker == null) {
            restoreKillAura();
            enter(State.HIT);
            return;
        }
        if (!kaOn) {
            info("Шалкер рядом — включаю KillAura.");
            setupKillAura(shulker.getType());
        }
        if (stateTick > 1200) {
            warning("Шалкер не убит за 60 секунд — продолжаю.");
            restoreKillAura();
            enter(State.HIT);
        }
    }

    private void hit() {
        ItemFrameEntity f = elytraFrameNear(fx, fz, 14);
        if (f == null) {
            // рамки с элитрами уже нет — возможно, предмет уже выпал
            elytraBefore = countElytra();
            enter(State.COLLECT);
            return;
        }
        double dx = f.getX() - mc.player.getX(), dy = f.getY() - mc.player.getEyeY(), dz = f.getZ() - mc.player.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist > 4.3) {
            walkTo(f.getX(), f.getZ());
            if (stateTick > 400) { error("Не могу подойти к рамке."); toggle(); }
            return;
        }
        elytraBefore = countElytra();
        mc.interactionManager.attackEntity(mc.player, f);
        mc.player.swingHand(Hand.MAIN_HAND);
        enter(State.COLLECT);
    }

    private void collect() {
        if (countElytra() > elytraBefore) {
            looted.add(new double[]{fx, fz});
            info("Элитры подобраны.");
            enter(State.BOX);
            return;
        }
        // рамка ещё с элитрами — бьём снова
        ItemFrameEntity f = elytraFrameNear(fx, fz, 14);
        if (f != null && stateTick % 20 == 5) {
            mc.interactionManager.attackEntity(mc.player, f);
            mc.player.swingHand(Hand.MAIN_HAND);
        }
        ItemEntity item = nearestItem(st -> st.getItem() == Items.ELYTRA, 10);
        if (item != null) walkTo(item.getX(), item.getZ());
        if (stateTick > 300) {
            warning("Не удалось подобрать элитры.");
            enter(State.EXIT);
        }
    }

    private void box() {
        FindItemResult sh = InvUtils.find(ElytraShipFinder::isShulker);
        if (!sh.found()) {
            warning("В инвентаре нет шалкера — элитры остаются в инвентаре.");
            enter(State.EXIT);
            return;
        }
        if (!sh.isHotbar()) {
            InvUtils.move().from(sh.slot()).toHotbar(7);
            return;
        }
        int fxp = (int) Math.floor(mc.player.getX()), fyp = (int) Math.floor(mc.player.getY() + 0.01), fzp = (int) Math.floor(mc.player.getZ());
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        BlockPos spot = null;
        for (int[] dd : dirs) {
            BlockPos c = new BlockPos(fxp + dd[0], fyp, fzp + dd[1]);
            if (mc.world.getBlockState(c).isAir()
                && mc.world.getBlockState(new BlockPos(c.getX(), c.getY() + 1, c.getZ())).isAir()
                && !mc.world.getBlockState(new BlockPos(c.getX(), c.getY() - 1, c.getZ())).isAir()) {
                spot = c;
                break;
            }
        }
        if (spot == null) {
            warning("Некуда поставить шалкер — элитры остаются в инвентаре.");
            enter(State.EXIT);
            return;
        }
        if (BlockUtils.place(spot, sh, true, 50)) {
            boxPos = spot;
            enter(State.OPEN);
            return;
        }
        if (stateTick > 60) {
            warning("Не получилось поставить шалкер.");
            enter(State.EXIT);
        }
    }

    private void open() {
        if (mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler) {
            enter(State.STORE);
            return;
        }
        if (mc.world.getBlockState(boxPos).isAir() && stateTick > 5) {
            enter(State.BOX);
            return;
        }
        if (stateTick % 12 == 1) {
            Vec3d top = new Vec3d(boxPos.getX() + 0.5, boxPos.getY() + 1.0, boxPos.getZ() + 0.5);
            BlockUtils.interact(new BlockHitResult(top, Direction.UP, boxPos, false), Hand.MAIN_HAND, true);
        }
        if (stateTick > 100) {
            warning("Шалкер не открывается.");
            enter(State.BREAK);
        }
    }

    private void store() {
        ScreenHandler m = mc.player.currentScreenHandler;
        if (!(m instanceof ShulkerBoxScreenHandler)) {
            enter(State.BREAK);
            return;
        }
        int clicks = 0;
        for (int i = 27; i < 63 && clicks < 6; i++) {
            ItemStack st = m.getSlot(i).getStack();
            if (st.getItem() == Items.ELYTRA) {
                mc.interactionManager.clickSlot(m.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                clicks++;
            }
        }
        if (clicks == 0 || stateTick > 40) {
            mc.setScreen(null);
            info("Элитры сложены в шалкер.");
            enter(State.BREAK);
        }
    }

    private void breakBox() {
        if (boxPos == null || mc.world.getBlockState(boxPos).isAir()) {
            enter(State.COLLECT_BOX);
            return;
        }
        var st = mc.world.getBlockState(boxPos);
        FindItemResult tool = InvUtils.findFastestTool(st);
        if (tool.found() && tool.isHotbar()) InvUtils.swap(tool.slot(), false);
        BlockUtils.breakBlock(boxPos, true);
        if (stateTick > 300) {
            warning("Не удаётся сломать шалкер.");
            enter(State.EXIT);
        }
    }

    private void collectBox() {
        if (InvUtils.find(ElytraShipFinder::isShulker).found()) {
            enter(State.EXIT);
            return;
        }
        ItemEntity item = nearestItem(ElytraShipFinder::isShulker, 10);
        if (item != null) walkTo(item.getX(), item.getZ());
        if (stateTick > 200) {
            warning("Шалкер не подобран.");
            enter(State.EXIT);
        }
    }

    /**
     * Выход наверх: если над головой чисто до неба — взлетаем; иначе идём к шахте, по которой спустились,
     * а если не получается — ломаем блоки над собой.
     */
    private void exit() {
        if (stateTick > 900) {
            error("Не нашёл выход наверх — выбирайся сам.");
            toggle();
            return;
        }
        int px = (int) Math.floor(mc.player.getX());
        int py = (int) Math.floor(mc.player.getY() + 0.01);
        int pz = (int) Math.floor(mc.player.getZ());

        if (skyClear(px, py + 2, pz)) {
            exitLaunch(px, py, pz);
            return;
        }

        double dx = landX + 0.5 - mc.player.getX(), dz = landZ + 0.5 - mc.player.getZ();
        double hd = Math.sqrt(dx * dx + dz * dz);
        if (stateTick < 300 && hd <= 25 && skyClear(landX, roofY, landZ)) {
            if (hd > 1.0) {
                walkTo(landX + 0.5, landZ + 0.5);
            } else {
                // у самой шахты — аккуратно встаём точно по центру
                Vec3d dm = mc.player.getVelocity();
                mc.player.setVelocity(dx * 0.3, dm.y, dz * 0.3);
            }
            return;
        }

        // ломаем блок над головой
        for (int yy = py + 2; yy <= py + 6; yy++) {
            BlockPos cell = new BlockPos(px, yy, pz);
            var st = mc.world.getBlockState(cell);
            if (st.isAir()) continue;
            mc.player.setPitch(-90f);
            FindItemResult tool = InvUtils.findFastestTool(st);
            if (tool.found() && tool.isHotbar()) InvUtils.swap(tool.slot(), false);
            BlockUtils.breakBlock(cell, true);
            return;
        }
        // над головой 5 клеток чисто, но выше — блок: поднимаемся прыжком
        mc.options.jumpKey.setPressed(mc.player.isOnGround());
    }

    /**
     * Над головой уже чисто до неба — перед вылетом расширяем проход до 2 блоков в ширину
     * (2x2 в сторону ближайшего угла, 5 клеток над головой). Ломает по одному блоку за тик;
     * когда ломать нечего — вылетаем.
     */
    private void exitLaunch(int px, int py, int pz) {
        if (stateTick < 600) {
            int sx = (mc.player.getX() - px) >= 0.5 ? 1 : -1;
            int sz = (mc.player.getZ() - pz) >= 0.5 ? 1 : -1;
            for (int yy = py + 2; yy <= py + 6; yy++) {
                for (int k = 0; k < 3; k++) {
                    int cx = (k == 1) ? px : px + sx;
                    int cz = (k == 0) ? pz : pz + sz;
                    BlockPos cell = new BlockPos(cx, yy, cz);
                    var st = mc.world.getBlockState(cell);
                    if (st.isAir()) continue;
                    mc.player.setPitch(-90f);
                    FindItemResult tool = InvUtils.findFastestTool(st);
                    if (tool.found() && tool.isHotbar()) InvUtils.swap(tool.slot(), false);
                    BlockUtils.breakBlock(cell, true);
                    return;
                }
            }
        }
        beginRelaunch();
    }

    private boolean skyClear(int x, int y0, int z) {
        for (int yy = y0; yy <= 330; yy++) {
            if (!mc.world.getBlockState(new BlockPos(x, yy, z)).isAir()) return false;
        }
        return true;
    }

    private void beginRelaunch() {
        restoreKillAura();
        if (repairing) {
            repairing = false;
            repairSearchWarned = false;
            info("Вылетаю после ремонта. Цель прежняя: X=%d Z=%d (~%d блоков).", target[0], target[1], (int) distTo(target[0], target[1]));
        } else {
            emptyCities.add(key(target[0], target[1]));
            target = pickTarget();
            if (target == null) {
                error("Подходящие города закончились.");
                toggle();
                return;
            }
            info("Вылетаю. Следующая цель: X=%d Z=%d (~%d блоков).", target[0], target[1], (int) distTo(target[0], target[1]));
        }
        lastRocket = -100;
        tick = 0;
        exiting = true;
        enter(State.TAKEOFF);
    }

    // ------------------------------------------------------------------ помощники лута

    @SuppressWarnings("unchecked")
    private void setupKillAura(EntityType<?> shulkerType) {
        if (kaOn) return;
        KillAura ka = Modules.get().get(KillAura.class);
        if (ka == null) {
            warning("KillAura не найден — шалкера придётся убить самому.");
            return;
        }
        kaOn = true;
        Setting<Object> entities = (Setting<Object>) ka.settings.get("entities");
        if (entities != null) {
            savedKa.putIfAbsent(entities, entities.get());
            entities.set(new HashSet<EntityType<?>>(Set.<EntityType<?>>of(shulkerType)));
        }
        Setting<Object> autoSwitch = (Setting<Object>) ka.settings.get("auto-switch");
        if (autoSwitch != null) {
            savedKa.putIfAbsent(autoSwitch, autoSwitch.get());
            autoSwitch.set(true);
        }
        Setting<Object> onlyClick = (Setting<Object>) ka.settings.get("only-on-click");
        if (onlyClick != null) {
            savedKa.putIfAbsent(onlyClick, onlyClick.get());
            onlyClick.set(false);
        }
        kaWasActive = ka.isActive();
        if (!ka.isActive()) ka.toggle();
    }

    @SuppressWarnings("unchecked")
    private void restoreKillAura() {
        for (Map.Entry<Setting<?>, Object> e : savedKa.entrySet()) {
            ((Setting<Object>) e.getKey()).set(e.getValue());
        }
        savedKa.clear();
        KillAura ka = Modules.get().get(KillAura.class);
        if (kaOn && ka != null && ka.isActive() && !kaWasActive) ka.toggle();
        kaOn = false;
    }

    private void walkTo(double x, double z) {
        double dx = x - mc.player.getX(), dz = z - mc.player.getZ();
        mc.player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mc.options.forwardKey.setPressed(true);
        mc.options.jumpKey.setPressed(mc.player.horizontalCollision && mc.player.isOnGround());
    }

    private Entity nearestShulker(double range) {
        Entity best = null;
        double bestD = range * range;
        for (Entity e : mc.world.getEntities()) {
            if (isShulkerEntity(e) && e.isAlive()) {
                double dx = e.getX() - mc.player.getX(), dy = e.getY() - mc.player.getY(), dz = e.getZ() - mc.player.getZ();
                double d = dx * dx + dy * dy + dz * dz;
                if (d < bestD) { bestD = d; best = e; }
            }
        }
        return best;
    }

    /** Шалкер определяем по классу сущности. */
    private static boolean isShulkerEntity(Entity e) {
        return e instanceof net.minecraft.entity.mob.ShulkerEntity;
    }

    private ItemEntity nearestItem(Predicate<ItemStack> test, double range) {
        ItemEntity best = null;
        double bestD = range * range;
        for (Entity e : mc.world.getEntities()) {
            if (e instanceof ItemEntity ie && test.test(ie.getStack())) {
                double dx = ie.getX() - mc.player.getX(), dy = ie.getY() - mc.player.getY(), dz = ie.getZ() - mc.player.getZ();
                double d = dx * dx + dy * dy + dz * dz;
                if (d < bestD) { bestD = d; best = ie; }
            }
        }
        return best;
    }

    private ItemFrameEntity elytraFrameNear(double x, double z, double range) {
        ItemFrameEntity best = null;
        double bestD = range * range;
        for (Entity e : mc.world.getEntities()) {
            if (e instanceof ItemFrameEntity f && f.getHeldItemStack().getItem() == Items.ELYTRA) {
                double dx = f.getX() - x, dz = f.getZ() - z;
                double d = dx * dx + dz * dz;
                if (d < bestD) { bestD = d; best = f; }
            }
        }
        return best;
    }

    private boolean isLooted(double x, double z) {
        for (double[] l : looted) {
            double dx = l[0] - x, dz = l[1] - z;
            if (dx * dx + dz * dz < 24 * 24) return true;
        }
        return false;
    }

    private int countElytra() {
        return InvUtils.find(Items.ELYTRA).count();
    }

    private static boolean isShulker(ItemStack st) {
        try {
            return st.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock;
        } catch (Throwable t) {
            return false;
        }
    }


    // ------------------------------------------------------------------ ремонт элитр (v1.4)

    private int elytraRemaining() {
        ItemStack st = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (st.getItem() != Items.ELYTRA) return Integer.MAX_VALUE;
        return st.getMaxDamage() - st.getDamage();
    }

    private int countEndStone() {
        return InvUtils.find(Items.END_STONE).count();
    }

    private boolean isEndStone(int x, int y, int z) {
        return BlockCompat.isBlock(mc.world.getBlockState(new BlockPos(x, y, z)), Blocks.END_STONE);
    }

    private boolean isAirAt(int x, int y, int z) {
        return mc.world.getBlockState(new BlockPos(x, y, z)).isAir();
    }

    /** Ровное место на острове Края (эндерняк, вокруг тоже эндерняк, сверху 2 воздуха) рядом с игроком. Возвращает {x, yВерхнегоБлока, z}. */
    private int[] findIslandSpot() {
        int px = (int) Math.floor(mc.player.getX()), pz = (int) Math.floor(mc.player.getZ());
        int top = Math.min((int) Math.floor(mc.player.getY()), 120);
        final int radius = 96, step = 6;
        int[] best = null;
        long bestD = Long.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                long d = (long) dx * dx + (long) dz * dz;
                if (d >= bestD || d > (long) radius * radius) continue;
                int x = px + dx, z = pz + dz;
                for (int y = top; y >= 30; y--) {
                    var st = mc.world.getBlockState(new BlockPos(x, y, z));
                    if (st.isAir()) continue;
                    if (BlockCompat.isBlock(st, Blocks.END_STONE)
                        && isEndStone(x + 1, y, z) && isEndStone(x - 1, y, z) && isEndStone(x, y, z + 1) && isEndStone(x, y, z - 1)
                        && isAirAt(x, y + 1, z) && isAirAt(x, y + 2, z)) {
                        best = new int[]{x, y, z};
                        bestD = d;
                    }
                    break; // верхний твёрдый блок колонки найден (подошёл он или нет)
                }
            }
        }
        return best;
    }

    /** Посадка на выбранное место острова (как land(), но к точке repX/repY/repZ). */
    private void repLand() {
        if (mc.player.isOnGround()) {
            repairing = true;
            kills = 0;
            ItemStack el = mc.player.getEquippedStack(EquipmentSlot.CHEST);
            repStartDamage = el.getDamage();
            info("Приземлился на острове. Добываю %d блоков эндерняка.", REP_BLOCKS);
            roofAnchored = false;
            enter(State.REP_MINE);
            return;
        }
        if (stateTick > 1200) {
            error("Не смог приземлиться на остров — останавливаюсь.");
            toggle();
            return;
        }
        double px = mc.player.getX(), py = mc.player.getEyeY(), pz = mc.player.getZ();
        double dx = repX + 0.5 - px, dz = repZ + 0.5 - pz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        mc.player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        double pitch = Math.toDegrees(Math.atan2(py - (repY + 1.0), Math.max(dist, 0.5)));
        mc.player.setPitch((float) Math.max(-5.0, Math.min(55.0, pitch)));
    }

    // ---- добыча 9 эндерняка

    private void repMine() {
        // якорь платформы 4x4 фиксируем сразу: вокруг него эндерняк НЕ добываем (там будет пол)
        if (!roofAnchored) anchorPlatform();
        int have = countEndStone();
        if (have >= REP_BLOCKS) {
            info("Эндерняка хватает (%d). Строю платформу 4x4.", have);
            roofPhase = 0; roofIdx = 0; roofFails = 0;
            enter(State.REP_ROOF);
            return;
        }
        if (stateTick > 1800) {
            warning("Не набрал эндерняк за 90 секунд (нужна кирка?) — иду драться без платформы.");
            startFight();
            return;
        }

        // выпавший эндерняк — подобрать
        ItemEntity drop = nearestItem(st -> st.getItem() == Items.END_STONE, 5);
        if (drop != null) {
            double dx = drop.getX() - mc.player.getX(), dz = drop.getZ() - mc.player.getZ();
            if (dx * dx + dz * dz > 0.4) {
                walkTo(drop.getX(), drop.getZ());
                return;
            }
        }

        int px = (int) Math.floor(mc.player.getX());
        int py = (int) Math.floor(mc.player.getY() + 0.01);
        int pz = (int) Math.floor(mc.player.getZ());
        // сначала берём блоки не ближе 2 клеток (ближние нужны как опора для столба крыши)
        BlockPos target = nextMineBlock(px, py, pz, stateTick > 250 ? 1 : 2);
        if (target == null) {
            if (stateTick > 400) {
                warning("Рядом нет эндерняка для добычи — иду драться без платформы.");
                startFight();
            }
            return;
        }
        var st = mc.world.getBlockState(target);
        FindItemResult tool = InvUtils.findFastestTool(st);
        if (tool.found() && tool.isHotbar()) InvUtils.swap(tool.slot(), false);
        BlockUtils.breakBlock(target, true);
    }

    /** Ближайший достижимый верхний блок эндерняка вокруг (не под самим игроком). */
    private BlockPos nextMineBlock(int px, int py, int pz, int minCheb) {
        double ex = mc.player.getX(), ey = mc.player.getEyeY(), ez = mc.player.getZ();
        BlockPos best = null;
        double bestD = 4.4 * 4.4;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) < minCheb) continue;
                int bx = px + dx, bz = pz + dz;
                if (bx >= roofAx + PLAT_MIN && bx <= roofAx + PLAT_MAX && bz >= roofAz + PLAT_MIN && bz <= roofAz + PLAT_MAX) continue;   // пол платформы не трогаем
                for (int dy = -1; dy >= -2; dy--) {
                    BlockPos p = new BlockPos(px + dx, py + dy, pz + dz);
                    if (!BlockCompat.isBlock(mc.world.getBlockState(p), Blocks.END_STONE)) continue;
                    double ddx = p.getX() + 0.5 - ex, ddy = p.getY() + 0.5 - ey, ddz = p.getZ() + 0.5 - ez;
                    double d = ddx * ddx + ddy * ddy + ddz * ddz;
                    if (d < bestD) { bestD = d; best = p; }
                    break; // только верхний слой в колонке
                }
            }
        }
        return best;
    }

    // ---- платформа 4x4: крыша (над головой, высота +2) и пол (под ногами)

    private void anchorPlatform() {
        roofAx = (int) Math.floor(mc.player.getX());
        roofAy = (int) Math.floor(mc.player.getY() + 0.01);
        roofAz = (int) Math.floor(mc.player.getZ());
        roofAnchored = true;
        floorSkip.clear();
    }

    /** c = {a, h, b}: a/b — смещение по X/Z от якорного блока, h — высота от ног. */
    private BlockPos roofCell(int[] c) {
        return new BlockPos(roofAx + c[0], roofAy + c[1], roofAz + c[2]);
    }

    /**
     * Строит порядок постройки: столб-опора (2 временных блока) в одной из крайних клеток платформы,
     * затем крыша 4x4 обходом в ширину от верха столба — каждый новый блок граничит с уже стоящим.
     * Столб в крайней клетке, чтобы не упираться в хитбокс игрока (он стоит в центре).
     */
    private boolean buildRoofList() {
        int pa = 0, pb = 0;
        boolean found = false;
        outer:
        for (int a = PLAT_MIN; a <= PLAT_MAX; a++) {
            for (int b = PLAT_MIN; b <= PLAT_MAX; b++) {
                if (a != PLAT_MIN && a != PLAT_MAX && b != PLAT_MIN && b != PLAT_MAX) continue;   // только крайние клетки
                boolean ok = true;
                for (int h = 0; h <= 2; h++) {
                    if (!mc.world.getBlockState(roofCell(new int[]{a, h, b})).isAir()) { ok = false; break; }
                }
                if (ok && !mc.world.getBlockState(roofCell(new int[]{a, -1, b})).isAir()) {
                    pa = a; pb = b; found = true;
                    break outer;
                }
            }
        }
        if (!found) return false;

        List<int[]> list = new ArrayList<>();
        list.add(new int[]{pa, 0, pb});
        list.add(new int[]{pa, 1, pb});
        list.add(new int[]{pa, 2, pb});

        boolean[][] seen = new boolean[PLAT][PLAT];
        List<int[]> queue = new ArrayList<>();
        queue.add(new int[]{pa, pb});
        seen[pa - PLAT_MIN][pb - PLAT_MIN] = true;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int i = 0; i < queue.size(); i++) {
            int[] c = queue.get(i);
            for (int[] d : dirs) {
                int na = c[0] + d[0], nb = c[1] + d[1];
                if (na < PLAT_MIN || na > PLAT_MAX || nb < PLAT_MIN || nb > PLAT_MAX) continue;
                if (seen[na - PLAT_MIN][nb - PLAT_MIN]) continue;
                seen[na - PLAT_MIN][nb - PLAT_MIN] = true;
                queue.add(new int[]{na, nb});
                list.add(new int[]{na, 2, nb});
            }
        }
        roofList = list.toArray(new int[0][]);
        roofPa = pa;
        roofPb = pb;
        return true;
    }

    /** Ближайшая к центру дыра в полу платформы, в которую есть куда ставить блок (рядом есть опора). */
    private BlockPos nextFloorHole() {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        double cx = roofAx + 1.0, cz = roofAz + 1.0;
        for (int a = PLAT_MIN; a <= PLAT_MAX; a++) {
            for (int b = PLAT_MIN; b <= PLAT_MAX; b++) {
                BlockPos p = new BlockPos(roofAx + a, roofAy - 1, roofAz + b);
                if (!mc.world.getBlockState(p).isAir() || floorSkip.contains(p)) continue;
                int hx = p.getX(), hy = p.getY(), hz = p.getZ();
                boolean support = !mc.world.getBlockState(p.down()).isAir()
                    || !mc.world.getBlockState(new BlockPos(hx, hy, hz - 1)).isAir() || !mc.world.getBlockState(new BlockPos(hx, hy, hz + 1)).isAir()
                    || !mc.world.getBlockState(new BlockPos(hx + 1, hy, hz)).isAir() || !mc.world.getBlockState(new BlockPos(hx - 1, hy, hz)).isAir();
                if (!support) continue;
                double dx = p.getX() + 0.5 - cx, dz = p.getZ() + 0.5 - cz;
                double d = dx * dx + dz * dz;
                if (d < bestD) { bestD = d; best = p; }
            }
        }
        return best;
    }

    private int countFloorHoles() {
        int n = 0;
        for (int a = PLAT_MIN; a <= PLAT_MAX; a++) {
            for (int b = PLAT_MIN; b <= PLAT_MAX; b++) {
                if (mc.world.getBlockState(new BlockPos(roofAx + a, roofAy - 1, roofAz + b)).isAir()) n++;
            }
        }
        return n;
    }

    private void repRoof() {
        if (stateTick > 2400) {
            warning("Платформа строится слишком долго — иду драться как есть.");
            startFight();
            return;
        }
        if (!roofAnchored) anchorPlatform();
        // центр платформы 4x4 — общий угол четырёх средних клеток
        double cx = roofAx + 1.0, cz = roofAz + 1.0;

        if (roofPhase == 0) {
            // встаём точно в центр платформы (и на её уровень — вдруг скатились в яму после добычи)
            double dx = cx - mc.player.getX(), dz = cz - mc.player.getZ();
            double hd = Math.sqrt(dx * dx + dz * dz);
            boolean levelOk = Math.abs(mc.player.getY() - roofAy) < 0.3;
            if ((hd > 0.12 || !levelOk) && stateTick < 120) {
                if (hd > 1.0 || !levelOk) {
                    walkTo(cx, cz);
                } else {
                    mc.options.forwardKey.setPressed(false);
                    mc.options.jumpKey.setPressed(false);
                    Vec3d dm = mc.player.getVelocity();
                    mc.player.setVelocity(dx * 0.3, dm.y, dz * 0.3);
                }
                return;
            }
            mc.options.forwardKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            if (!buildRoofList()) {
                warning("Нет подходящего места для столба-опоры — иду драться без платформы.");
                startFight();
                return;
            }
            roofPhase = 1; roofIdx = 0; roofFails = 0;
            return;
        }

        if (roofPhase == 1) {            // столб + вся крыша 4x4
            if (placeList(roofList)) { roofPhase = 2; roofFails = 0; }
            return;
        }

        if (roofPhase == 2) {            // ломаем столб (его блоки вернутся в инвентарь), верхний блок столба остаётся частью крыши
            BlockPos[] pillar = {roofCell(new int[]{roofPa, 1, roofPb}), roofCell(new int[]{roofPa, 0, roofPb})};
            for (BlockPos p : pillar) {
                if (mc.world.getBlockState(p).isAir()) continue;
                if (++roofFails > 200) break;
                var st = mc.world.getBlockState(p);
                FindItemResult tool = InvUtils.findFastestTool(st);
                if (tool.found() && tool.isHotbar()) InvUtils.swap(tool.slot(), false);
                BlockUtils.breakBlock(p, true);
                return;
            }
            roofPhase = 3; roofFails = 0;
            return;
        }

        if (roofPhase == 3) {            // подобрать блоки и вернуться в центр
            ItemEntity drop = nearestItem(st -> st.getItem() == Items.END_STONE, 5);
            if (drop != null && ++roofFails < 120) {
                double dx = drop.getX() - mc.player.getX(), dz = drop.getZ() - mc.player.getZ();
                if (dx * dx + dz * dz > 0.4) { walkTo(drop.getX(), drop.getZ()); return; }
            }
            double dx = cx - mc.player.getX(), dz = cz - mc.player.getZ();
            if (Math.sqrt(dx * dx + dz * dz) > 0.3 && roofFails < 200) {
                roofFails++;
                walkTo(cx, cz);
                return;
            }
            mc.options.forwardKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            roofPhase = 4; roofIdx = 0; roofFails = 0;
            return;
        }

        // фаза 4: пол 4x4 под ногами — латаем дыры (край острова, ямы от добычи)
        BlockPos hole = nextFloorHole();
        if (hole == null) {
            int left = countFloorHoles();
            if (left > 0) warning("В полу платформы остались дыры без опоры: %d.", left);
            info("Платформа 4x4 готова (крыша и пол). Смотрю на эндерменов.");
            startFight();
            return;
        }
        if (placeEndStone(hole)) {
            roofFails = 0;
        } else if (++roofFails > 40) {
            warning("Не удалось поставить блок пола %d %d %d — пропускаю.", hole.getX(), hole.getY(), hole.getZ());
            floorSkip.add(hole);
            roofFails = 0;
        }
    }

    /** Ставит блоки списка по одному за тик. Возвращает true, когда весь список обработан. */
    private boolean placeList(int[][] list) {
        if (roofIdx >= list.length) return true;
        BlockPos p = roofCell(list[roofIdx]);
        if (!mc.world.getBlockState(p).isAir()) { roofIdx++; roofFails = 0; return roofIdx >= list.length; }
        if (placeEndStone(p)) {
            roofIdx++;
            roofFails = 0;
        } else if (++roofFails > 40) {
            warning("Не удалось поставить блок крыши %d/%d — пропускаю.", roofIdx + 1, list.length);
            roofIdx++;
            roofFails = 0;
        }
        return roofIdx >= list.length;
    }

    private boolean placeEndStone(BlockPos p) {
        FindItemResult es = InvUtils.find(Items.END_STONE);
        if (!es.found()) return false;
        if (!es.isHotbar() && !es.isOffhand()) {
            InvUtils.move().from(es.slot()).toHotbar(6);
            return false;
        }
        return BlockUtils.place(p, es, true, 50);
    }

    // ---- эндермены: плавно смотрим, агрим по maxAggro штук, убиваем KillAura, ждём полной починки

    private void startFight() {
        endermen.clear();
        aggro.clear();
        stare = null;
        stareTicks = 0;
        noEndermanTicks = 0;
        repIdleWarned = false;
        enter(State.REP_FIGHT);
    }

    private void finishRepair() {
        restoreKillAura();
        endermen.clear();
        aggro.clear();
        stare = null;
        roofY = (int) Math.floor(mc.player.getY() + 0.01);
        landX = (int) Math.floor(mc.player.getX());
        landZ = (int) Math.floor(mc.player.getZ());
        enter(State.EXIT);   // exit() ломает крышу над головой и вызывает beginRelaunch() (цель остаётся прежней)
    }

    private static boolean isEndermanEntity(Entity e) {
        return e instanceof net.minecraft.entity.mob.EndermanEntity;
    }

    private double distSq(Entity e) {
        double dx = e.getX() - mc.player.getX(), dy = e.getY() - mc.player.getY(), dz = e.getZ() - mc.player.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private void repFight() {
        ItemStack el = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (el.getItem() != Items.ELYTRA) {
            error("Элитры пропали из слота нагрудника — останавливаюсь.");
            toggle();
            return;
        }
        int dmg = el.getDamage();
        if (dmg <= 0) {
            info("Элитры полностью целые (убито эндерменов: %d). Взлетаю.", kills);
            finishRepair();
            return;
        }
        if (stateTick % 400 == 0) {
            info("Починка: прочность %d/%d, убито эндерменов: %d.", el.getMaxDamage() - dmg, el.getMaxDamage(), kills);
        }

        // список эндерменов обновляем редко (не каждый тик) — так нет лагов
        if (stateTick % 10 == 1) {
            endermen.clear();
            double r = endermanRange.get();
            double r2 = r * r;
            for (Entity e : mc.world.getEntities()) {
                if (isEndermanEntity(e) && e.isAlive() && distSq(e) <= r2) endermen.add(e);
            }
        }
        endermen.removeIf(e -> !e.isAlive());

        // учёт убитых/потерянных среди заагренных
        Iterator<Map.Entry<Integer, Integer>> it = aggro.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Integer> en = it.next();
            Entity e = mc.world.getEntityById(en.getKey());
            if (e == null) { it.remove(); continue; }
            if (!e.isAlive()) { kills++; it.remove(); continue; }
            if (tick - en.getValue() > 400) it.remove();   // не пришёл за 20 секунд — считаем потерянным
        }

        // Mending не работает? (6 убитых и прочность не выросла)
        if (kills >= 6 && dmg >= repStartDamage) {
            error("Убил %d эндерменов, а прочность элитр не растёт — нет Mending или опыт идёт в другие вещи. Останавливаюсь.", kills);
            restoreKillAura();
            toggle();
            return;
        }

        if (endermen.isEmpty()) {
            noEndermanTicks++;
            if (noEndermanTicks == 600 && !repIdleWarned) {
                info("Эндерменов рядом нет, жду (они спавнятся в темноте поблизости).");
                repIdleWarned = true;
            }
            if (noEndermanTicks > 1800 && elytraRemaining() >= 120) {
                warning("Эндерменов нет уже 90 секунд, прочности хватает — лечу дальше.");
                finishRepair();
            }
            return;
        }
        noEndermanTicks = 0;

        // KillAura: включаем один раз, как только увидели эндермена (бьёт только тех, кто на нас агрится)
        if (!kaOn) setupKillAura(endermen.get(0).getType());

        // кто-то уже рядом — KillAura работает сама, взгляд не трогаем
        Entity near = null;
        double nd = 8.0 * 8.0;
        for (Entity e : endermen) {
            double d = distSq(e);
            if (d < nd) { nd = d; near = e; }
        }
        if (near != null) {
            stare = null;
            stareTicks = 0;
            return;
        }

        if (aggro.size() >= maxAggro.get() || mc.player.getHealth() <= repairMinHealth.get()) {
            stare = null;
            stareTicks = 0;
            return;   // нужное число уже агрено — ждём, пока придут и умрут
        }

        double range = endermanRange.get();
        if (stare != null && (!stare.isAlive() || aggro.containsKey(stare.getId())
            || distSq(stare) > range * range || !mc.player.canSee(stare))) {
            stare = null;
            stareTicks = 0;
        }
        if (stare == null) {
            double bd = range * range;
            for (Entity e : endermen) {
                if (aggro.containsKey(e.getId())) continue;
                double d = distSq(e);
                if (d < bd && mc.player.canSee(e)) { bd = d; stare = e; }
            }
            stareTicks = 0;
            if (stare == null) return;
        }

        // плавный поворот на глаза эндермена (агрятся от взгляда на голову ~5 тиков)
        if (smoothLook(stare.getX(), stare.getEyeY(), stare.getZ())) {
            if (++stareTicks >= 8) {
                aggro.put(stare.getId(), tick);
                info("Заагрил эндермена (%d/%d).", aggro.size(), maxAggro.get());
                stare = null;
                stareTicks = 0;
            }
        } else {
            stareTicks = 0;
        }
    }

    /** Поворот к точке не быстрее lookSpeed градусов за тик. true — уже смотрим точно на неё. */
    private boolean smoothLook(double tx, double ty, double tz) {
        double dx = tx - mc.player.getX(), dy = ty - mc.player.getEyeY(), dz = tz - mc.player.getZ();
        double hd = Math.sqrt(dx * dx + dz * dz);
        float wantYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, hd));
        float yaw = mc.player.getYaw(), pitch = mc.player.getPitch();
        float dYaw = wantYaw - yaw;
        while (dYaw > 180f) dYaw -= 360f;
        while (dYaw < -180f) dYaw += 360f;
        float dPitch = wantPitch - pitch;
        float step = lookSpeed.get().floatValue();
        float newYaw = yaw + Math.max(-step, Math.min(step, dYaw));
        float newPitch = Math.max(-90f, Math.min(90f, pitch + Math.max(-step, Math.min(step, dPitch))));
        mc.player.setYaw(newYaw);
        mc.player.setPitch(newPitch);
        return Math.abs(dYaw) < 1.5f && Math.abs(dPitch) < 1.5f;
    }

    // ------------------------------------------------------------------ утилиты

    private void faceTarget(int tx, int tz) {
        double dx = tx + 0.5 - mc.player.getX();
        double dz = tz + 0.5 - mc.player.getZ();
        mc.player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
    }

    private double distTo(int x, int z) {
        double dx = x - mc.player.getX(), dz = z - mc.player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static long parseSeed(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return s.hashCode();
        }
    }

    private <T> void put(Setting<T> s, T value) {
        saved.putIfAbsent(s, s.get());
        s.set(value);
    }

    @SuppressWarnings("unchecked")
    private void restore() {
        for (Map.Entry<Setting<?>, Object> e : saved.entrySet()) {
            ((Setting<Object>) e.getKey()).set(e.getValue());
        }
        saved.clear();
    }

    // ------------------------------------------------------------------ падение при Pitch40

    /** Если падаем — раскрываем элитры и используем фейерверк, пока не вернёмся к нормальному Pitch40. */
    private void fallAssist() {
        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
        double hs = havePrev ? Math.sqrt((x - prevX) * (x - prevX) + (z - prevZ) * (z - prevZ)) : 1.0; // блоков/тик
        double vy = havePrev ? y - prevY : 0.0;
        prevX = x; prevY = y; prevZ = z; havePrev = true;
        if (!rocketOnFall.get()) return;

        double lower = height.get() - 55.0;               // нижняя граница Pitch40, её ставит takeoff()
        boolean gliding = mc.player.isGliding();
        boolean low = y < lower - fallMargin.get();
        boolean stalled = gliding && hs < 0.35 && vy < -0.45; // почти нет скорости и падаем

        if (!gliding) {
            // элитры закрылись: прыжок + пакет, как при взлёте
            jumpHeld = true;
            mc.options.jumpKey.setPressed(tick % 4 < 2);
            if (tick % 6 == 3 && mc.getNetworkHandler() != null) {
                mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
            }
            boosting = true;
            return;
        }
        if (jumpHeld) {
            mc.options.jumpKey.setPressed(false);
            jumpHeld = false;
        }

        if (low || stalled) {
            if (!boosting) info("Падаю (Y=%d) — использую фейерверки.", (int) y);
            boosting = true;
        } else if (y >= lower + 3.0 && hs > 0.5) {
            boosting = false;
        }

        if (boosting && tick - rocketTick >= rocketDelay.get()) {
            if (useRocketSafe()) {
                rocketTick = tick;
                noRocketWarned = false;
            } else if (!noRocketWarned && !InvUtils.find(Items.FIREWORK_ROCKET).found()) {
                warning("Фейерверки закончились, а я падаю!");
                noRocketWarned = true;
            }
        }
    }

    /** Фейерверк из хотбара; если есть только в инвентаре — перекладываем в хотбар (сработает на следующем тике). */
    private boolean useRocketSafe() {
        if (useRocket()) return true;
        FindItemResult inv = InvUtils.find(Items.FIREWORK_ROCKET);
        if (inv.found() && !inv.isHotbar() && !inv.isOffhand()) {
            InvUtils.move().from(inv.slot()).toHotbar(8);
        }
        return false;
    }

    // ------------------------------------------------------------------ острова Края (копия vanilla TheEndBiomeSource)

    private static final class Cand {
        final int cx, cz;
        final float h;
        Cand(int cx, int cz, float h) { this.cx = cx; this.cz = cz; this.h = h; }
    }

    /**
     * Точная копия EndIslandDensityFunction/TheEndBiomeSource.getHeightValue из vanilla.
     * SimplexNoise сидится от мирового сида (LegacyRandomSource + пропуск 17292 вызовов), 2D-значение < -0.9
     * в точке (чанк за пределами 64 чанков) — центр острова. Высота >= 0 — midlands, > 40 — highlands.
     */
    private static final class IslandNoise {
        private static final int[][] GRAD = {
            {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0}, {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
            {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}, {1, 1, 0}, {0, -1, 1}, {-1, 1, 0}, {0, -1, -1}
        };
        private static final double SQRT3 = Math.sqrt(3.0);
        private static final double F2 = 0.5 * (SQRT3 - 1.0);
        private static final double G2 = (3.0 - SQRT3) / 6.0;
        private static final double ISLAND_LIMIT = (double) -0.9F;
        private final int[] p = new int[512];

        IslandNoise(long worldSeed) {
            Random r = new Random(worldSeed);          // java.util.Random == LegacyRandomSource
            for (int i = 0; i < 17292; i++) r.nextInt();
            r.nextDouble(); r.nextDouble(); r.nextDouble(); // xo, yo, zo
            int[] q = new int[256];
            for (int i = 0; i < 256; i++) q[i] = i;
            for (int i = 0; i < 256; i++) {
                int j = r.nextInt(256 - i);
                int t = q[i];
                q[i] = q[j + i];
                q[j + i] = t;
            }
            for (int i = 0; i < 512; i++) p[i] = q[i & 255];
        }

        private int perm(int i) { return p[i & 255]; }

        private static double corner(int idx, double x, double y) {
            double d = 0.5 - x * x - y * y;
            if (d < 0.0) return 0.0;
            d *= d;
            int[] g = GRAD[idx];
            return d * d * (g[0] * x + g[1] * y);
        }

        double value(double x, double y) {
            double d = (x + y) * F2;
            int i = (int) Math.floor(x + d);
            int j = (int) Math.floor(y + d);
            double e = (i + j) * G2;
            double h = x - (i - e);
            double k = y - (j - e);
            int l, m;
            if (h > k) { l = 1; m = 0; } else { l = 0; m = 1; }
            double n = h - l + G2;
            double o = k - m + G2;
            double pp = h - 1.0 + 2.0 * G2;
            double qq = k - 1.0 + 2.0 * G2;
            int r = i & 255;
            int t = j & 255;
            int a = perm(r + perm(t)) % 12;
            int b = perm(r + l + perm(t + m)) % 12;
            int c = perm(r + 1 + perm(t + 1)) % 12;
            return 70.0 * (corner(a, h, k) + corner(b, n, o) + corner(c, pp, qq));
        }

        /** x, z — в единицах по 8 блоков (blockX / 8); для биома чанка (cx, cz) это (2*cx+1, 2*cz+1). */
        float islandHeight(int x, int z) {
            int i = x / 2, j = z / 2;
            int k = x % 2, l = z % 2;
            float f = 100.0F - (float) Math.sqrt((float) (x * x + z * z)) * 8.0F;
            f = Math.max(-100.0F, Math.min(80.0F, f));
            for (int m = -12; m <= 12; m++) {
                for (int n = -12; n <= 12; n++) {
                    long o = i + m, pq = j + n;
                    if (o * o + pq * pq > 4096L && value((double) o, (double) pq) < ISLAND_LIMIT) {
                        float g = (Math.abs((float) o) * 3439.0F + Math.abs((float) pq) * 147.0F) % 13.0F + 9.0F;
                        float hh = (float) (k + m * 2);
                        float qq = (float) (l + n * 2);
                        float rr = 100.0F - (float) Math.sqrt(hh * hh + qq * qq) * g;
                        rr = Math.max(-100.0F, Math.min(80.0F, rr));
                        f = Math.max(f, rr);
                    }
                }
            }
            return f;
        }
    }
}
