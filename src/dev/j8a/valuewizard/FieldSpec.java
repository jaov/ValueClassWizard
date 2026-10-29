package dev.j8a.valuewizard;

/**
 * One field entered in the value class wizard.
 */
public final class FieldSpec {

    private String type = "";
    private String name = "";
    private boolean wither;

    public FieldSpec() {
    }

    public FieldSpec(String type, String name) {
        this(type, name, false);
    }

    public FieldSpec(String type, String name, boolean wither) {
        setType(type);
        setName(name);
        setWither(wither);
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type == null ? "" : type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    public boolean isWither() {
        return wither;
    }

    public void setWither(boolean wither) {
        this.wither = wither;
    }

    public boolean isEmpty() {
        return type.trim().isEmpty() && name.trim().isEmpty();
    }
}
