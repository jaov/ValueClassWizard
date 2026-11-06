package dev.j8a.valuewizard;

import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class FieldsPanelTest {

    @Test
    public void builderNameConflictDetectsGeneratedTypes() {
        FieldsPanel panel = new FieldsPanel();
        assertNull(panel.getBuilderNameConflict("Person"));
        assertNotNull(panel.getBuilderNameConflict("Builder"));
        assertNotNull(panel.getBuilderNameConflict("CanBuild"));

        panel.setFields(Collections.singletonList(
                new FieldSpec("String", "name", false)));
        assertNotNull(panel.getBuilderNameConflict("HasName"));
        assertNull(panel.getBuilderNameConflict("Person"));
    }
}
