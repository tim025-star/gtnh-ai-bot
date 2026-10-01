package dev.nottambok.gtnhaibot.client.actions;

import java.util.Locale;

import com.google.gson.JsonObject;

public final class TrackedAction {

    public enum Status {
        ACCEPTED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private final String id;
    private final String type;
    private final long createdAt;
    private Status status;
    private String phase;
    private String message;
    private long updatedAt;

    public TrackedAction(String id, String type) {
        this.id = id;
        this.type = type;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = createdAt;
        this.status = Status.ACCEPTED;
        this.phase = "accepted";
        this.message = "Action accepted";
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isTerminal() {
        return status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED;
    }

    public void running(String phase, String message) {
        update(Status.RUNNING, phase, message);
    }

    public void complete(String message) {
        update(Status.COMPLETED, "completed", message);
    }

    public void fail(String message) {
        update(Status.FAILED, "failed", message);
    }

    public void cancel(String message) {
        update(Status.CANCELLED, "cancelled", message);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("type", type);
        json.addProperty(
            "status",
            status.name()
                .toLowerCase(Locale.ROOT));
        json.addProperty("phase", phase);
        json.addProperty("message", message);
        json.addProperty("createdAt", createdAt);
        json.addProperty("updatedAt", updatedAt);
        return json;
    }

    private void update(Status status, String phase, String message) {
        if (isTerminal()) return;
        this.status = status;
        this.phase = safe(phase, 80);
        this.message = safe(message, 500);
        this.updatedAt = System.currentTimeMillis();
    }

    private String safe(String value, int maxLength) {
        if (value == null) return "";
        String flattened = value.replace('\r', ' ')
            .replace('\n', ' ');
        return flattened.substring(0, Math.min(maxLength, flattened.length()));
    }
}
