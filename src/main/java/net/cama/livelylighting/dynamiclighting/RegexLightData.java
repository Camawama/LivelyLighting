package net.cama.livelylighting.dynamiclighting;

import net.cama.livelylighting.dynamiclighting.engine.LightData;

import java.util.regex.Pattern;

public class RegexLightData {
    public final Pattern pattern;
    public final LightData data;

    public RegexLightData(Pattern pattern, LightData data) {
        this.pattern = pattern;
        this.data = data;
    }
}
