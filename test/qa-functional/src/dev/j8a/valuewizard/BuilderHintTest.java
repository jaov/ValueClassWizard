package dev.j8a.valuewizard;

import org.junit.Test;
import org.netbeans.modules.java.hints.test.api.HintTest;

import static org.junit.Assert.assertTrue;

public class BuilderHintTest {

    private static final String STALE_SOURCE = """
            package test;
            public class Test {
                private final String name;
                private final int age;
                private final String city;

                public Test(String name, int age, String city) {
                    this.name = name;
                    this.age = age;
                    this.city = city;
                }

                public static HasName builder() {
                    return new Builder();
                }

                public interface HasName {
                    HasAge setName(String name);
                }

                public interface HasAge {
                    CanBuild setAge(int age);
                }

                public interface CanBuild {
                    Test build();
                }

                private static final class Builder implements HasName, HasAge, CanBuild {
                    private String name;
                    private int age;

                    private Builder() {
                    }

                    @Override
                    public HasAge setName(String name) {
                        this.name = name;
                        return this;
                    }

                    @Override
                    public CanBuild setAge(int age) {
                        this.age = age;
                        return this;
                    }

                    @Override
                    public Test build() {
                        return new Test(name, age, null);
                    }
                }
            }
            """;

    @Test
    public void staleBuilderIsReportedAndRegenerated() throws Exception {
        HintTest.HintWarning warning = HintTest.create()
                .sourceLevel("17")
                .input(STALE_SOURCE)
                .run(BuilderHint.class)
                .findWarning(
                        "28:4-28:77:hint:"
                        + "Builder does not match the current fields.");

        String output = warning.applyFix()
                .assertCompilable()
                .getOutput();

        assertTrue(output.contains("interface HasCity"));
        assertTrue(output.contains("setCity(String city)"));
        assertTrue(output.contains(
                "new Test(this.name, this.age, this.city)"));
    }
}
