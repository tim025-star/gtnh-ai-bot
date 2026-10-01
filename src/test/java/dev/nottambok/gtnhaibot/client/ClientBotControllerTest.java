package dev.nottambok.gtnhaibot.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

class ClientBotControllerTest {

    @Test
    void interactionBlockAllowsAdjacentOneBlockHeightDifference() {
        assertTrue(ClientBotController.isInteractionBlock(757, 64, -153, 758, 63, -153));
    }

    @Test
    void interactionBlockRejectsDiagonalAndDistantPositions() {
        assertFalse(ClientBotController.isInteractionBlock(757, 64, -153, 758, 64, -152));
        assertFalse(ClientBotController.isInteractionBlock(757, 64, -153, 759, 64, -153));
    }

    @Test
    void aTimedOutBridgeRequestCannotExecuteOnALaterTick() {
        ClientBotController controller = new ClientBotController();
        final int[] executions = { 0 };

        assertThrows(TimeoutException.class, () -> controller.callOnClientThread(() -> {
            executions[0]++;
            return "executed";
        }));
        controller.processApiCalls();

        assertEquals(0, executions[0]);
    }
}
