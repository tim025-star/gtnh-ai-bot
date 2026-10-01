package dev.nottambok.gtnhaibot.client.crafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RecipeDefinition {

    private final String id;
    private final String provider;
    private final RecipeStation station;
    private final String stationId;
    private final StackKey output;
    private final int outputCount;
    private final List<IngredientRequirement> slots;
    private final int width;
    private final int height;
    private final boolean shaped;
    private final boolean executable;

    public RecipeDefinition(String id, String provider, RecipeStation station, String stationId, StackKey output,
        int outputCount, List<IngredientRequirement> slots, int width, int height, boolean shaped, boolean executable) {
        if (id == null || id.length() == 0) throw new IllegalArgumentException("recipe id required");
        if (output == null || outputCount < 1) throw new IllegalArgumentException("recipe output required");
        this.id = id;
        this.provider = provider;
        this.station = station;
        this.stationId = stationId == null ? "" : stationId;
        this.output = output;
        this.outputCount = outputCount;
        this.slots = Collections.unmodifiableList(new ArrayList<IngredientRequirement>(slots));
        this.width = width;
        this.height = height;
        this.shaped = shaped;
        this.executable = executable;
    }

    public String getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public RecipeStation getStation() {
        return station;
    }

    public String getStationId() {
        return stationId;
    }

    public StackKey getOutput() {
        return output;
    }

    public int getOutputCount() {
        return outputCount;
    }

    public List<IngredientRequirement> getSlots() {
        return slots;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public boolean isShaped() {
        return shaped;
    }

    public boolean isExecutable() {
        return executable;
    }
}
