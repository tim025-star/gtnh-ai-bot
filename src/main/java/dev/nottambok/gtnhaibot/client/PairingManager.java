package dev.nottambok.gtnhaibot.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Properties;

public class PairingManager {

    private static final long CODE_LIFETIME_MS = 120000L;
    private static final File CONFIG_FILE = new File("config/gtnh-ai-bot.properties");
    private final SecureRandom random = new SecureRandom();
    private final String token;
    private String activeCode;
    private long activeCodeExpiresAt;

    public PairingManager() {
        token = loadOrCreateToken();
    }

    PairingManager(String token) {
        if (token == null || token.length() < 32) throw new IllegalArgumentException("token is too short");
        this.token = token;
    }

    public synchronized String issueCode() {
        activeCode = String.format("%06d", Integer.valueOf(random.nextInt(1000000)));
        activeCodeExpiresAt = System.currentTimeMillis() + CODE_LIFETIME_MS;
        return activeCode;
    }

    public synchronized String exchange(String code) {
        if (activeCode == null || System.currentTimeMillis() > activeCodeExpiresAt
            || !constantTimeEquals(activeCode, code)) {
            return null;
        }
        activeCode = null;
        activeCodeExpiresAt = 0L;
        return token;
    }

    public boolean authenticate(String candidate) {
        return constantTimeEquals(token, candidate == null ? "" : candidate);
    }

    static boolean constantTimeEquals(String expected, String actual) {
        try {
            return MessageDigest.isEqual(expected.getBytes("UTF-8"), actual.getBytes("UTF-8"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private String loadOrCreateToken() {
        Properties properties = new Properties();
        try {
            if (CONFIG_FILE.exists()) {
                FileInputStream input = new FileInputStream(CONFIG_FILE);
                properties.load(input);
                input.close();
                String existing = properties.getProperty("pairingToken", "")
                    .trim();
                if (existing.length() >= 64) return existing;
            }
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            StringBuilder generated = new StringBuilder(64);
            for (int i = 0; i < bytes.length; i++)
                generated.append(String.format("%02x", Integer.valueOf(bytes[i] & 0xff)));
            if (CONFIG_FILE.getParentFile() != null) CONFIG_FILE.getParentFile()
                .mkdirs();
            properties.setProperty("pairingToken", generated.toString());
            FileOutputStream output = new FileOutputStream(CONFIG_FILE);
            properties.store(output, "GTNH AI Bot local pairing token. Keep this file private.");
            output.close();
            return generated.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to load pairing token", ex);
        }
    }
}
