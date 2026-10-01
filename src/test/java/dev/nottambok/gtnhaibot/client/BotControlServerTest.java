package dev.nottambok.gtnhaibot.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

class BotControlServerTest {

    @Test
    void repeatingAnImmediateActionDoesNotExecuteItAgain() {
        final int[] selections = { 0 };
        ClientBotController controller = new ClientBotController() {

            @Override
            public synchronized String holdItem(String item) {
                selections[0]++;
                return "HELD " + item;
            }
        };
        BotControlServer server = new BotControlServer(
            controller,
            new PairingManager("01234567890123456789012345678901"));
        JsonObject action = new JsonObject();
        action.addProperty("type", "selectItem");
        action.addProperty("item", "minecraft:stick");

        String first = server.executeAction("12345678-abcd", action);
        String repeated = server.executeAction("12345678-abcd", action);

        assertEquals(first, repeated);
        assertEquals(1, selections[0]);
    }

    @Test
    void doesNotTruncateFractionalCoordinatesIntoAnotherAction() {
        BotControlServer server = new BotControlServer(
            new ClientBotController(),
            new PairingManager("01234567890123456789012345678901"));
        JsonObject action = new JsonObject();
        action.addProperty("type", "goto");
        action.addProperty("x", 1.5);
        action.addProperty("y", 64);
        action.addProperty("z", 1);

        assertThrows(IllegalArgumentException.class, () -> server.executeAction("12345678-abcd", action));
    }

    @Test
    void aDecisionCannotOverrideAnInGameCommandIssuedAfterItsSnapshot() {
        ClientBotController controller = new ClientBotController() {

            @Override
            public synchronized void stopAll() {}
        };
        BotControlServer server = new BotControlServer(
            controller,
            new PairingManager("01234567890123456789012345678901"));
        controller.enqueueFromGame("craft minecraft:stick 1");
        JsonObject action = new JsonObject();
        action.addProperty("type", "goto");
        action.addProperty("x", 1);
        action.addProperty("y", 64);
        action.addProperty("z", 1);
        action.addProperty("expectedControlRevision", 0);

        assertThrows(IllegalArgumentException.class, () -> server.executeAction("12345678-abcd", action));
        assertEquals("[CRAFT(minecraft:stick,1)]", controller.listTasks());
    }
}
