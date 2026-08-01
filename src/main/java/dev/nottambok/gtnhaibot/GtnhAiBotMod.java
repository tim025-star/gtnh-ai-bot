package dev.nottambok.gtnhaibot;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;

@Mod(
    modid = GtnhAiBotMod.MOD_ID,
    name = GtnhAiBotMod.MOD_NAME,
    version = GtnhAiBotMod.VERSION,
    acceptableRemoteVersions = "*")
public class GtnhAiBotMod {

    public static final String MOD_ID = "gtnh_ai_bot";
    public static final String MOD_NAME = "GTNH AI Bot";
    public static final String VERSION = "0.1.0";

    @SidedProxy(
        clientSide = "dev.nottambok.gtnhaibot.client.ClientProxy",
        serverSide = "dev.nottambok.gtnhaibot.CommonProxy")
    public static CommonProxy proxy;

    @EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }
}
