package dev.j8a.valuewizard;

/**
 * One field entered in the value class wizard.
 */
public final class FieldSpec {

    private String type = "";
    private String name = "";

    public FieldSpec() {
    }

    public FieldSpec(String type, String name) {
        setType(type);
        setName(name);
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

    public boolean isEmpty() {
        return type.trim().isEmpty() && name.trim().isEmpty();
    }
}
