package net.cama.livelylighting.dynamiclighting.entity;

import java.util.regex.Pattern;

public class RegexEntityData {
    public final Pattern pattern;
    public final int level;

    public RegexEntityData(Pattern pattern, int level) {
        this.pattern = pattern;
        this.level = level;
    }
}
