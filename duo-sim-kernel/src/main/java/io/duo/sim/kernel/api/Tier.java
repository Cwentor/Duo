package io.duo.sim.kernel.api;

/** 实现档位（§6）：virtual &lt; embedded &lt; container &lt; real。 */
public enum Tier {
    VIRTUAL, EMBEDDED, CONTAINER, REAL;

    /** embedded+ 记法（§3 档位序）。 */
    public boolean isEmbeddedOrAbove() {
        return ordinal() >= EMBEDDED.ordinal();
    }

    public static Tier fromYaml(String name) {
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown tier: " + name, e);
        }
    }
}
