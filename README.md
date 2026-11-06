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
  - Optional staged `Builder` with a static `builder()` factory, `setX(...)` setters, and `build()`
- **Insert Code → Withable…** action for adding `withX(...)` methods to an existing class
- **Insert Code → Builder** action for adding a staged builder to an existing class
- **Alt+Enter** hint that offers to regenerate the staged builder when the class fields change
- Automatic import resolution using the NetBeans Java Source API (`ClasspathInfo` / `ClassIndex`)
  - Handles common JDK types such as `List`, `Map`, `LocalDate`, and `BigDecimal`
  - Handles nested types such as `Map.Entry`
  - Keeps fully qualified type names as entered
  - Reports ambiguous or unresolved types with a warning

## Requirements

- Apache NetBeans 30 (the module is built against it)
- The module and generated code target **Java 17**
- The NetBeans 30 build/test harness itself requires Java 21+; NetBeans ships its own JDK

## Build

From the project directory:

```bash
ant -f build.xml clean nbm
```

The installable module is written to:

```text
build/dev-j8a-valuewizard.nbm
```

## Test

The module and generated code stay on Java 17, but the NetBeans 30 build harness is compiled for Java 21+ and cannot run on JDK 17. The `JavaSource`/hint tests also need the JDK bundled with NetBeans 30 because `NoJavacHelper` checks `SourceVersion.RELEASE_26`.

Run the full suite with the bundled JDK:

```bash
JAVA_HOME=/usr/lib/apache-netbeans/jdk ant -f build.xml clean test
```

If you run Ant with a newer JDK and want the tests to use the NetBeans-bundled JDK, set this in `nbproject/private/platform-private.properties` or `~/.netbeans/30/build.properties`:

```properties
test.nbjdk.home=/path/to/netbeans/jdk
```

Do not put it in `nbproject/project.properties`: the harness's `-jdk-init` target runs before `project.properties` is loaded, so the property would be too late.

If your NetBeans installation does not bundle a JDK, point `test.nbjdk.home` at a JDK that has `SourceVersion.RELEASE_26`.

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
6. Check **Generate builder** to add a staged builder, if wanted
7. Click **Finish**

To add withers or a builder to an existing class:

1. Open the class in the Java editor
2. Press **Alt+Insert** (or choose **Source → Insert Code**)
3. Choose **Withable…** to add `withX(...)` methods, or **Builder** to add a staged builder
4. For **Withable…**, select the fields and click **OK**

The Withable action is offered for classes with fields. It explains why it cannot generate anything when the class is generic, abstract, a record, has no suitable all-arguments constructor, or already has withers for every field. Fields that already have a `withX(...)` method are skipped.

The Builder action requires a matching all-arguments constructor that does not throw checked exceptions, so `build()` can construct the value class. It explains why it cannot generate anything when the class is generic, abstract, a record, local, anonymous, has no fields, has no suitable all-arguments constructor, already has a `Builder` or `builder()` method, or would produce conflicting generated type names.

## Regenerating the builder

When a class already has the generated staged builder and its fields change, the editor offers an Alt+Enter hint on the nested `Builder` class:

1. Update the class fields and its all-arguments constructor.
2. Place the caret on the nested `Builder` declaration, or use the editor's lightbulb.
3. Choose **Regenerate Builder**.

The old generated `Builder`, stage interfaces, `CanBuild` interface, and `builder()` method are replaced with a fresh staged builder for the current fields. Regeneration replaces the generated builder, so any custom changes to it are lost. If there is no all-arguments constructor covering the current fields, the hint explains what is missing instead of offering the fix.

## Notes

- Types are inserted exactly as entered. Non-`java.lang` types are imported automatically when they can be resolved unambiguously.
- If a type cannot be resolved, the generated file keeps the name as entered and the wizard shows a warning. Use NetBeans' **Fix Imports** in that case.
- Array fields use reference-based equality through `Objects.equals`, which is the standard behavior for a simple value-class generator.
- The staged builder uses `HasX` / `CanBuild` interfaces so `build()` is only callable after every setter has been called. The setters must be called in field order.
- Builder setters are named `setX(...)`, while the value class's copy-with-one-change methods are named `withX(...)`. They intentionally have different semantics: `withX(...)` returns a new instance, while `setX(...)` mutates the builder.
- The Insert Code actions use an all-arguments constructor that does not throw checked exceptions, so the generated code compiles without adding `throws` clauses.
- The Insert Code actions require Java 17+ because the module is built against the NetBeans 30 editor and Java Source APIs.
