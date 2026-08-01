package dev.nottambok.gtnhaibot.client;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

public class CommandGtnhBot extends CommandBase {

    private final ClientBotController controller;
    private final PairingManager pairing;

    public CommandGtnhBot(ClientBotController controller, PairingManager pairing) {
        this.controller = controller;
        this.pairing = pairing;
    }

    @Override
    public String getCommandName() {
        return "gtnhbot";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/gtnhbot <pair|goto|follow|break|use|place|craft|status|diagnose|stop|list>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            reply(sender, getCommandUsage(sender));
            return;
        }
        if ("pair".equalsIgnoreCase(args[0])) {
            reply(sender, "Pairing code: " + pairing.issueCode() + " (expires in 2 minutes)");
            return;
        }
        if ("stop".equalsIgnoreCase(args[0])) {
            controller.stopAll();
            reply(sender, "Stopped");
            return;
        }
        if ("list".equalsIgnoreCase(args[0])) {
            reply(sender, controller.listTasks());
            return;
        }
        StringBuilder command = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) command.append(' ');
            command.append(args[i]);
        }
        reply(sender, controller.enqueueFromGame(command.toString()));
    }

    private void reply(ICommandSender sender, String message) {
        sender.addChatMessage(new ChatComponentText("[GTNH AI Bot] " + message));
    }
}
