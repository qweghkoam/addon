package dev.echestdump;

import dev.echestdump.modules.AutoCloseChest;
import dev.echestdump.modules.AutoSort;
import dev.echestdump.modules.EChestDump;
import dev.echestdump.modules.MinerPro;
import dev.echestdump.modules.ChestSearch;
import dev.echestdump.modules.ElytraShipFinder;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class EChestDumpAddon extends MeteorAddon {
    public static final Category CATEGORY = new Category("44444 dev");

    @Override
    public void onInitialize() {
        Modules.get().add(new EChestDump());
        Modules.get().add(new ElytraShipFinder());
        Modules.get().add(new ChestSearch());
        Modules.get().add(new AutoSort());
        Modules.get().add(new AutoCloseChest());
        Modules.get().add(new MinerPro());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "dev.echestdump";
    }
}
