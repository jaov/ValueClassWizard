package dev.j8a.valuewizard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.lang.model.element.TypeElement;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.source.ClassIndex;
import org.netbeans.api.java.source.ClasspathInfo;
import org.netbeans.api.java.source.ElementHandle;
import org.openide.filesystems.FileObject;

/**
 * Resolves the simple type names entered in the wizard against the target
 * project classpath and produces the import statements needed by the generated
 * source file.
 */
final class TypeResolver {

    private static final Logger LOG = Logger.getLogger(TypeResolver.class.getName());

    private static final Set<String> IGNORED = new HashSet<>(Arrays.asList(
            "boolean", "byte", "char", "short", "int", "long", "float", "double",
            "void", "extends", "super", "final", "static", "volatile", "transient",
            "abstract", "native", "synchronized", "strictfp", "public", "private",
            "protected", "default", "var"
    ));

    private static final List<String> PREFERRED_PACKAGES = Arrays.asList(
            "java.lang",
            "java.util",
            "java.util.concurrent",
            "java.util.function",
            "java.util.stream",
            "java.time",
            "java.math",
            "java.io",
            "java.nio",
            "java.net",
            "java.text",
            "java.sql",
            "javax.swing",
            "java.awt"
    );

    private TypeResolver() {
    }

    static Result resolve(FileObject targetFolder, String targetName, List<FieldSpec> fields) {
        TreeSet<String> imports = new TreeSet<>();
        TreeSet<String> unresolved = new TreeSet<>();

        if (targetFolder == null || fields == null || fields.isEmpty()) {
            return new Result(new ArrayList<>(imports), new ArrayList<>(unresolved));
        }

        try {
            ClasspathInfo classpathInfo = ClasspathInfo.create(targetFolder);
            ClassPath sourcePath = classpathInfo.getClassPath(ClasspathInfo.PathKind.SOURCE);
            String targetPackage = "";
            if (sourcePath != null) {
                String pkg = sourcePath.getResourceName(targetFolder, '.', false);
                if (pkg != null) {
                    targetPackage = pkg;
                }
            }

            ClassIndex index = classpathInfo.getClassIndex();
            String className = simpleClassName(targetName);
            Map<String, String> importBySimpleName = new HashMap<>();

            for (FieldSpec field : fields) {
                if (field == null) {
                    continue;
                }
                for (String chain : scan(field.getType())) {
                    resolveChain(chain, index, targetPackage, className,
                            imports, importBySimpleName, unresolved);
                }
            }
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "Could not resolve imports for " + targetName, ex);
        }

        return new Result(new ArrayList<>(imports), new ArrayList<>(unresolved));
    }

    private static void resolveChain(String chain, ClassIndex index, String targetPackage,
            String className, Set<String> imports, Map<String, String> importBySimpleName,
            Set<String> unresolved) {
        if (chain == null || chain.isEmpty()) {
            return;
        }

        int dot = chain.indexOf('.');
        String first = dot < 0 ? chain : chain.substring(0, dot);
        if (first.isEmpty() || IGNORED.contains(first)) {
            return;
        }

        // Package-qualified or fully-qualified names are left as entered.
        if (!Character.isUpperCase(first.charAt(0))) {
            return;
        }

        // A single upper-case letter is almost always a type variable.
        if (first.length() == 1) {
            return;
        }

        // A reference to the class being generated is already in scope.
        if (first.equals(className)) {
            return;
        }

        Set<ElementHandle<TypeElement>> handles;
        try {
            handles = index.getDeclaredTypes(first, ClassIndex.NameKind.SIMPLE_NAME,
                    EnumSet.of(ClassIndex.SearchScope.SOURCE,
                            ClassIndex.SearchScope.DEPENDENCIES));
        } catch (RuntimeException ex) {
            unresolved.add(first);
            return;
        }

        if (handles == null || handles.isEmpty()) {
            unresolved.add(first);
            return;
        }

        Candidate chosen = choose(handles, targetPackage);
        if (chosen == null) {
            unresolved.add(first);
            return;
        }

        if (!needsImport(chosen, targetPackage, className)) {
            if (chosen.packageName.isEmpty() && !targetPackage.isEmpty()) {
                unresolved.add(chosen.simpleName);
            }
            return;
        }

        String existing = importBySimpleName.get(chosen.simpleName);
        if (existing == null) {
            importBySimpleName.put(chosen.simpleName, chosen.qualifiedName);
            imports.add(chosen.qualifiedName);
        } else if (!existing.equals(chosen.qualifiedName)) {
            // Two different types with the same simple name: do not import
            // either one, and let the user fix the conflict manually.
            imports.remove(existing);
            importBySimpleName.remove(chosen.simpleName);
            unresolved.add(chosen.simpleName);
        }
    }

    private static Candidate choose(Set<ElementHandle<TypeElement>> handles, String targetPackage) {
        Map<String, Candidate> byName = new TreeMap<>();
        for (ElementHandle<TypeElement> handle : handles) {
            Candidate candidate = candidate(handle);
            if (candidate != null) {
                byName.put(candidate.qualifiedName, candidate);
            }
        }
        if (byName.isEmpty()) {
            return null;
        }

        List<Candidate> candidates = new ArrayList<>(byName.values());

        // Same package, preferring top-level types over nested ones.
        for (Candidate candidate : candidates) {
            if (!candidate.nested && candidate.packageName.equals(targetPackage)) {
                return candidate;
            }
        }
        for (Candidate candidate : candidates) {
            if (candidate.packageName.equals(targetPackage)) {
                return candidate;
            }
        }

        // java.lang top-level types need no import.
        for (Candidate candidate : candidates) {
            if (!candidate.nested && "java.lang".equals(candidate.packageName)) {
                return candidate;
            }
        }

        // Common JDK packages, in a predictable preference order.
        for (String preferred : PREFERRED_PACKAGES) {
            for (Candidate candidate : candidates) {
                if (preferred.equals(candidate.packageName)) {
                    return candidate;
                }
            }
        }

        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static Candidate candidate(ElementHandle<TypeElement> handle) {
        try {
            String qualifiedName = handle.getQualifiedName();
            if (qualifiedName == null || qualifiedName.isEmpty()) {
                return null;
            }

            String binaryName;
            try {
                binaryName = handle.getBinaryName();
            } catch (IllegalStateException ex) {
                binaryName = qualifiedName;
            }

            boolean nested = binaryName.indexOf('$') >= 0;
            int lastDot = qualifiedName.lastIndexOf('.');
            String simpleName = lastDot < 0
                    ? qualifiedName
                    : qualifiedName.substring(lastDot + 1);

            String topLevel = binaryName;
            int dollar = topLevel.indexOf('$');
            if (dollar >= 0) {
                topLevel = topLevel.substring(0, dollar);
            }
            int packageDot = topLevel.lastIndexOf('.');
            String packageName = packageDot < 0 ? "" : topLevel.substring(0, packageDot);

            return new Candidate(qualifiedName, simpleName, packageName, nested);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean needsImport(Candidate candidate, String targetPackage, String className) {
        if (candidate.simpleName.equals(className)) {
            return false;
        }
        // Types in the default package cannot be imported.
        if (candidate.packageName.isEmpty() && !targetPackage.isEmpty()) {
            return false;
        }
        if (candidate.nested) {
            return true;
        }
        if (candidate.packageName.equals(targetPackage)) {
            return false;
        }
        if ("java.lang".equals(candidate.packageName)) {
            return false;
        }
        return !candidate.packageName.isEmpty();
    }

    private static String simpleClassName(String targetName) {
        if (targetName == null) {
            return "";
        }
        String name = targetName;
        if (name.endsWith(".java")) {
            name = name.substring(0, name.length() - 5);
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    /**
     * Extracts dotted identifier chains from a Java type expression. Generic
     * arguments, arrays and wildcards are handled naturally because only the
     * identifier chains are collected.
     */
    private static List<String> scan(String type) {
        List<String> result = new ArrayList<>();
        if (type == null || type.isEmpty()) {
            return result;
        }

        int i = 0;
        int length = type.length();
        while (i < length) {
            char c = type.charAt(i);
            if (!Character.isJavaIdentifierStart(c)) {
                i++;
                continue;
            }

            int start = i++;
            while (i < length && Character.isJavaIdentifierPart(type.charAt(i))) {
                i++;
            }

            StringBuilder chain = new StringBuilder(type.substring(start, i));
            int end = i;
            while (true) {
                int dot = skipWhitespace(type, end);
                if (dot >= length || type.charAt(dot) != '.') {
                    break;
                }
                int nextStart = skipWhitespace(type, dot + 1);
                if (nextStart >= length || !Character.isJavaIdentifierStart(type.charAt(nextStart))) {
                    break;
                }
                int nextEnd = nextStart + 1;
                while (nextEnd < length && Character.isJavaIdentifierPart(type.charAt(nextEnd))) {
                    nextEnd++;
                }
                chain.append('.').append(type, nextStart, nextEnd);
                end = nextEnd;
            }

            result.add(chain.toString());
            i = end;
        }
        return result;
    }

    private static int skipWhitespace(String text, int index) {
        int i = index;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    static final class Result {

        private final List<String> imports;
        private final List<String> unresolved;

        Result(List<String> imports, List<String> unresolved) {
            this.imports = Collections.unmodifiableList(imports);
            this.unresolved = Collections.unmodifiableList(unresolved);
        }

        List<String> getImports() {
            return imports;
        }

        List<String> getUnresolved() {
            return unresolved;
        }
    }

    private static final class Candidate {

        final String qualifiedName;
        final String simpleName;
        final String packageName;
        final boolean nested;

        Candidate(String qualifiedName, String simpleName, String packageName, boolean nested) {
            this.qualifiedName = qualifiedName;
            this.simpleName = simpleName;
            this.packageName = packageName;
            this.nested = nested;
        }
    }
}
