package dev.j8a.valuewizard;

/**
 * A field offered by the Withable code generator.
 */
final class WithableField {

    final String name;
    final String type;

    WithableField(String name, String type) {
        this.name = name;
        this.type = type;
    }
}
