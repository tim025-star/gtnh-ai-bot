package dev.nottambok.gtnhaibot.client;

import net.minecraftforge.client.ClientCommandHandler;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import dev.nottambok.gtnhaibot.CommonProxy;

public class ClientProxy extends CommonProxy {

    private BotControlServer controlServer;

    @Override
    public void init(FMLInitializationEvent event) {
        PairingManager pairing = new PairingManager();
        ClientBotController controller = new ClientBotController();
        FMLCommonHandler.instance()
            .bus()
            .register(controller);
        ClientCommandHandler.instance.registerCommand(new CommandGtnhBot(controller, pairing));
        try {
            controlServer = new BotControlServer(controller, pairing);
            controlServer.start();
        } catch (Exception ex) {
            System.err.println("[GTNH AI Bot] Failed to start local control server: " + ex.getMessage());
        }
    }
}
