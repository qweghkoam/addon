package dev.echestdump.modules;

import dev.echestdump.EChestDumpAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;

/**
 * Auto Close Chest: сам закрывает сундук / бочку / эндер-сундук через N тиков после открытия.
 * Пока работают другие модули аддона (они сами открывают и закрывают сундуки) — не вмешивается.
 */
public class AutoCloseChest extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Integer> delay = sg.add(new IntSetting.Builder()
        .name("delay")
        .description("Через сколько тиков после открытия закрывать сундук (20 тиков = 1 секунда).")
        .defaultValue(10).min(1).sliderMax(100)
        .build());

    private ScreenHandler last;
    private int ticks;

    public AutoCloseChest() {
        super(EChestDumpAddon.CATEGORY, "auto-close-chest",
            "Автоматически закрывает сундук через заданное время после открытия.");
    }

    @Override
    public void onDeactivate() {
        last = null;
        ticks = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        ScreenHandler menu = mc.player.currentScreenHandler;
        if (menu == mc.player.playerScreenHandler || !(menu instanceof GenericContainerScreenHandler)) {
            last = null;
            ticks = 0;
            return;
        }

        if (menu != last) {
            last = menu;
            ticks = 0;
        }

        if (otherModuleBusy()) return;

        if (++ticks >= delay.get()) {
            // setScreen(null) закрывает контейнер и сам отправляет серверу пакет закрытия
            mc.setScreen(null);
            last = null;
            ticks = 0;
        }
    }

    private boolean otherModuleBusy() {
        Modules m = Modules.get();
        return m.isActive(AutoSort.class) || m.isActive(MinerPro.class) || m.isActive(ChestSearch.class) || m.isActive(EChestDump.class);
    }
}
