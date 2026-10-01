package dev.nottambok.gtnhaibot.client.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;

class TrackedActionTest {

    @Test
    void protocolStatusDoesNotDependOnSystemLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            TrackedAction action = new TrackedAction("12345678", "craft");
            action.running("planning", "Planning");
            assertEquals(
                "running",
                action.toJson()
                    .get("status")
                    .getAsString());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void terminalStateCannotBeOverwrittenByLateProgress() {
        TrackedAction action = new TrackedAction("12345678", "craft");

        action.running("planning", "Planning dependencies");
        action.complete("Verified by Minecraft");
        action.running("late", "Late tick");

        assertTrue(action.isTerminal());
        assertEquals(
            "completed",
            action.toJson()
                .get("status")
                .getAsString());
        assertEquals(
            "Verified by Minecraft",
            action.toJson()
                .get("message")
                .getAsString());
    }

    @Test
    void flattensMessagesBeforeReturningJson() {
        TrackedAction action = new TrackedAction("12345678", "craft");

        action.fail("missing\nitem");

        assertEquals(
            "missing item",
            action.toJson()
                .get("message")
                .getAsString());
    }
}
