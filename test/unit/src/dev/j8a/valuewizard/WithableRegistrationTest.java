package dev.j8a.valuewizard;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.netbeans.spi.editor.codegen.CodeGenerator;
import org.openide.cookies.InstanceCookie;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.util.lookup.Lookups;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class WithableRegistrationTest {

    @Test
    public void withableFactoryIsRegisteredForJava() throws Exception {
        FileObject layerFile = FileUtil.getConfigFile(
                "Editors/text/x-java/CodeGenerators/dev-j8a-valuewizard-WithableGenerator$Factory.instance");
        assertNotNull("Withable layer entry is missing", layerFile);

        InstanceCookie cookie = DataObject.find(layerFile)
                .getLookup().lookup(InstanceCookie.class);
        assertNotNull("Withable layer entry is not an instance", cookie);
        assertEquals("dev.j8a.valuewizard.WithableGenerator$Factory",
                cookie.instanceClass().getName());

        List<String> found = new ArrayList<>();
        for (CodeGenerator.Factory factory
                : Lookups.forPath("Editors/text/x-java/CodeGenerators")
                        .lookupAll(CodeGenerator.Factory.class)) {
            found.add(factory.getClass().getName());
        }
        assertTrue("Withable factory not found; registered: " + found,
                found.contains("dev.j8a.valuewizard.WithableGenerator$Factory"));
    }
}
