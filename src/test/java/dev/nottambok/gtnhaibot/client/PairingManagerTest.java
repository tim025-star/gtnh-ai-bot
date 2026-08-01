package dev.nottambok.gtnhaibot.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PairingManagerTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void pairingCodeIsSingleUse() {
        PairingManager pairing = new PairingManager(TOKEN);
        String code = pairing.issueCode();

        assertTrue(pairing.authenticate(pairing.exchange(code)));
        assertNull(pairing.exchange(code));
    }

    @Test
    void invalidCredentialsAreRejected() {
        PairingManager pairing = new PairingManager(TOKEN);

        assertNull(pairing.exchange("000000"));
        assertFalse(pairing.authenticate("wrong"));
    }
}
