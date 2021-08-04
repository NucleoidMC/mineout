package xyz.nucleoid.mineout;

import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.nucleoid.mineout.game.MineoutConfig;
import xyz.nucleoid.mineout.game.MineoutWaiting;
import xyz.nucleoid.plasmid.game.GameType;

public final class Mineout implements ModInitializer {
    public static final String ID = "mineout";
    public static final Logger LOGGER = LogManager.getLogger(ID);

    @Override
    public void onInitialize() {
        GameType.register(
                new Identifier(Mineout.ID, "mineout"),
                MineoutConfig.CODEC,
                MineoutWaiting::open
        );
    }
}
