# LEARNINGS

Running notes from implementing the staged builder feature in the Value Class Wizard NetBeans module.

## Project facts

- The module has a flat source package: `dev.j8a.valuewizard`.
- The new-class path is template based:
  - `FieldsPanel` / `FieldsWizardPanel` collect fields.
  - `ValueClassWizardIterator` passes template parameters.
  - `resources/ValueClass.java.template` is a FreeMarker template.
- The Insert Code path is AST based:
  - `WithableGenerator` implements `CodeGenerator` and registers a nested `Factory`.
  - The factory is registered in `layer.xml` under `Editors/text/x-java/CodeGenerators`.
- Module spec version was `1.2.1` in `manifest.mf`; bumped to `1.3` for this feature.
- `nbproject/genfiles.properties` was already modified before this work; the Ant build updates its CRC values automatically. Do not revert unrelated local changes there.

## Design decisions

- Keep the builder in this module, not a separate project. It shares wizard plumbing and AST analysis with the existing Withable action.
- Generate a **nested** builder, not a separate file.
- Builder setters use `setX(...)`, not `withX(...)`. The class's `withX(...)` returns a new instance; a builder's setter mutates the builder. Reusing the same name would be a semantic trap.
- Use a **staged builder** so every field must be set before `build()` is callable.
- Stage interfaces are named `HasX` / `CanBuild`.
- The Insert Code builder action requires an all-arguments constructor, matching the Withable action.

## Technical findings

### Cyclic inheritance with self-nested interfaces

This does **not** compile:

```java
class Builder implements Builder.HasName {
    interface HasName { ... }
}
```

`javac` reports `cyclic inheritance involving Builder`.

Working shape: the stage interfaces are siblings of the private builder implementation.

```java
public interface HasName { HasAge setName(String name); }
public interface HasAge { CanBuild setAge(int age); }
public interface CanBuild { Person build(); }

private static final class Builder implements HasName, HasAge, CanBuild { ... }
```

### Self-type generics are not a substitute

`SELF extends Builder<SELF>` (the `Comparable<V extends Comparable<V>>` style) is for inheritance-friendly fluent APIs. It does not enforce that every setter was called. If a stage setter returns `SELF` as the concrete builder, all setters become available again and the staged guarantee is lost. Since the generated value class and builder are final, `SELF` adds no value.

### TreeMaker API

Verified against `build/public-package-jars/org-netbeans-modules-java-source-base.jar`:

- `TreeMaker.Interface(ModifiersTree, CharSequence, List<? extends TypeParameterTree>, List<? extends Tree>, List<? extends Tree>, List<? extends Tree>)` is the non-deprecated overload. The 5-argument version is deprecated; pass an empty permits list.
- `TreeMaker.Class(...)` likewise has a deprecated 6-argument overload; the 7-argument version takes an extra permits list.
- `TreeMaker.Method(...)` has two overloads whose last body parameter is either `BlockTree` or `String`. Passing a bare `null` for an abstract interface method is ambiguous; cast it: `(BlockTree) null`.
- `GeneratorUtilities.insertClassMembers(ClassTree, Iterable<? extends Tree>)` accepts a list containing interfaces, a nested class, and a method.
- `TreeMaker.Annotation(Tree, List<? extends ExpressionTree>)` is available for `@Override`.
- `Tree.Kind.RECORD` exists in the JDK used here (Java 17+), so enclosing-type detection can include it.

### FreeMarker template indexing

FreeMarker list loops expose `field_index` and `field_has_next`. The staged-builder template uses:

```ftl
<#if field_has_next>Has${fields[field_index + 1].name?cap_first}<#else>CanBuild</#if>
```

This rendered correctly for 1-, 2-, and 3-field classes. The 2-field output was compiled with `javac` to verify the staged builder shape. When the `builder` flag is false, the output is unchanged.

### Type-name collisions

- A nested type cannot have the same simple name as an enclosing class. The generated `Builder`, `HasX`, and `CanBuild` names must be checked against the value class name.
- `HasX` + `CanBuild` is a safe naming scheme: a field named `build` produces `HasBuild`, not `CanBuild`; a field named `canBuild` produces `HasCanBuild`, not `CanBuild`.
- Fields that differ only by case (`name` and `Name`) produce the same `setName`/`HasName` and must be rejected in both paths. The wizard's existing getter collision check already covers this; the Insert Code factory needs an explicit check.

## Implementation log

- Added `CodeGeneratorSupport.java` with shared field collection, constructor matching, method/type lookups, and enclosing-type detection.
- Updated `WithableGenerator` to use the shared helper and the broader enclosing-type detection (now also recognizes interfaces, enums, and records as the nearest type).
- Added `BuilderGenerator.java` with the staged builder generation and the Insert Code factory.
- Added the **Generate builder** checkbox and name-conflict validation to the wizard.
- Added the staged builder block to `ValueClass.java.template`.
- Added bundle strings, the layer registration, `BuilderRegistrationTest`, `FieldsPanelTest`, and README updates.
- Bumped the module spec version to `1.3`.
- `ant -f build.xml clean nbm test` succeeds.
- Verified the template output for 1-, 2-, and 3-field classes and for builder disabled; compiled the 2-field generated class.
- The unit tests pass (builder registration, withable registration, and builder class-name conflict validation). The NetBeans test harness prints a SEVERE `InaccessibleObjectException` about `java.net.URL.handler` on this JDK, but no test fails.
- Remaining: IDE-level smoke test of the Insert Code action and the wizard UI.

## Known caveats

- The staged builder fixes the setter order to field declaration order. That is intentional but should be documented for users.
- `build()` does not reject `null` reference values. Add `Objects.requireNonNull` in setters or `build()` if non-null enforcement is wanted later.
- The wizard rejects class names that collide with generated types (`Builder`, `CanBuild`, `HasX`). The Insert Code action additionally rejects existing nested types with those names and duplicate `setX` names from case-differing fields.

## Constructor selection and checked exceptions

`CodeGeneratorSupport.findSuitableConstructor` chooses the first all-arguments constructor whose parameters match the fields and whose `throws` clause contains only unchecked types. The check uses `Elements.getTypeElement("java.lang.RuntimeException")` / `"java.lang.Error"` and `Types.isSubtype`. Both Insert Code actions use this helper, so they no longer generate code that calls a constructor throwing checked exceptions without a `throws` clause. If no suitable constructor exists, the actions report the `noConstructor` message instead of producing uncompilable code.
