package net.cama.livelylighting.dynamiclighting.entity;

import net.cama.livelylighting.dynamiclighting.engine.LightData;

import java.util.regex.Pattern;

/** An entity definition keyed by a regular expression over entity type ids. */
public class RegexEntityData {
    public final Pattern pattern;
    public final int level;
    public final LightData data;

    public RegexEntityData(Pattern pattern, LightData data) {
        this.pattern = pattern;
        this.level = data.level;
        this.data = data;
    }
}
