# Value Class Wizard

A NetBeans module that adds a **Value Class** file template to the **New** submenu for Java packages.

## Features

- Two-step wizard:
  1. Class name, package, and location
  2. Field table with **Type** and **Name** columns
- Validation for incomplete rows, invalid field names, and duplicate names
- Generates:
  - `public final class`
  - `private final` fields
  - All-arguments constructor
  - Getters
  - `equals()` and `hashCode()`
  - `toString()`
  - Optional `withX(...)` wither methods, selectable per field or with the **Make all fields withable** master checkbox
- Automatic import resolution using the NetBeans Java Source API (`ClasspathInfo` / `ClassIndex`)
  - Handles common JDK types such as `List`, `Map`, `LocalDate`, and `BigDecimal`
  - Handles nested types such as `Map.Entry`
  - Keeps fully qualified type names as entered
  - Reports ambiguous or unresolved types with a warning

## Requirements

- Apache NetBeans 30 (the module is built against it)
- JDK 17 or newer (required by NetBeans)

## Build

From the project directory:

```bash
ant -f build.xml clean nbm
```

The installable module is written to:

```text
build/dev-j8a-valuewizard.nbm
```

## Run

Open the project in NetBeans and choose **Run → Run Project** to launch a development IDE with the module installed.

Alternatively, install the generated NBM:

1. Open **Tools → Plugins → Downloaded**
2. Click **Add Plugins…**
3. Select `build/dev-j8a-valuewizard.nbm`
4. Install the plugin and restart NetBeans if prompted

## Usage

In a Java project:

1. Right-click a package in the Projects window
2. Choose **New → Value Class**
3. Enter the class name and package
4. Add the fields (for example, `String` / `name` and `int` / `age`)
5. Check **With** for fields that should get a `withX(...)` method, or check **Make all fields withable**
6. Click **Finish**

## Notes

- Types are inserted exactly as entered. Non-`java.lang` types are imported automatically when they can be resolved unambiguously.
- If a type cannot be resolved, the generated file keeps the name as entered and the wizard shows a warning. Use NetBeans' **Fix Imports** in that case.
- Array fields use reference-based equality through `Objects.equals`, which is the standard behavior for a simple value-class generator.
