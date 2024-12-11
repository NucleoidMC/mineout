package xyz.nucleoid.mineout;

import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.nucleoid.mineout.game.MineoutConfig;
import xyz.nucleoid.mineout.game.MineoutWaiting;
import xyz.nucleoid.plasmid.api.game.GameType;

public final class Mineout implements ModInitializer {
    public static final String ID = "mineout";
    public static final Logger LOGGER = LogManager.getLogger(ID);

    @Override
    public void onInitialize() {
        GameType.register(
                Mineout.identifier("mineout"),
                MineoutConfig.CODEC,
                MineoutWaiting::open
        );
    }

    public static Identifier identifier(String path) {
        return Identifier.of(Mineout.ID, path);
    }
}
