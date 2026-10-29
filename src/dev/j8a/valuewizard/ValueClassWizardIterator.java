package dev.j8a.valuewizard;

import java.awt.Component;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import javax.swing.JComponent;
import javax.swing.event.ChangeListener;
import org.netbeans.api.java.project.JavaProjectConstants;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.netbeans.api.project.SourceGroup;
import org.netbeans.spi.java.project.support.ui.templates.JavaTemplates;
import org.netbeans.spi.project.ui.templates.support.Templates;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.WizardDescriptor;
import org.openide.filesystems.FileObject;
import org.openide.loaders.DataFolder;
import org.openide.loaders.DataObject;
import org.openide.util.NbBundle;

/**
 * Wizard iterator for the Value Class template.
 */
public final class ValueClassWizardIterator
        implements WizardDescriptor.AsynchronousInstantiatingIterator<WizardDescriptor> {

    private transient int index;
    private transient WizardDescriptor.Panel<WizardDescriptor>[] panels;
    private transient String[] stepNames;
    private transient WizardDescriptor wizard;

    public static ValueClassWizardIterator createIterator() {
        return new ValueClassWizardIterator();
    }

    @Override
    public void initialize(WizardDescriptor wizard) {
        this.wizard = wizard;
        this.index = 0;

        Project project = Templates.getProject(wizard);
        if (project == null) {
            throw new IllegalStateException("The Value Class wizard requires a project.");
        }

        SourceGroup[] groups = ProjectUtils.getSources(project)
                .getSourceGroups(JavaProjectConstants.SOURCES_TYPE_JAVA);
        if (groups == null) {
            groups = new SourceGroup[0];
        }

        List<WizardDescriptor.Panel<WizardDescriptor>> panelList = new ArrayList<>();
        if (groups.length > 0) {
            panelList.add(JavaTemplates.createPackageChooser(project, groups));
        } else {
            panelList.add(Templates.buildSimpleTargetChooser(project, groups).create());
        }
        panelList.add(new FieldsWizardPanel());

        @SuppressWarnings({"unchecked", "rawtypes"})
        WizardDescriptor.Panel<WizardDescriptor>[] panelArray
                = panelList.toArray(new WizardDescriptor.Panel[0]);
        this.panels = panelArray;

        this.stepNames = new String[]{
            NbBundle.getMessage(ValueClassWizardIterator.class, "ValueClassWizardIterator.step1"),
            NbBundle.getMessage(ValueClassWizardIterator.class, "ValueClassWizardIterator.step2")
        };

        wizard.putProperty(WizardDescriptor.PROP_AUTO_WIZARD_STYLE, Boolean.TRUE);
        wizard.putProperty(WizardDescriptor.PROP_CONTENT_DISPLAYED, Boolean.TRUE);
        wizard.putProperty(WizardDescriptor.PROP_CONTENT_NUMBERED, Boolean.TRUE);
        wizard.putProperty(WizardDescriptor.PROP_CONTENT_DATA, stepNames);

        for (int i = 0; i < panels.length; i++) {
            Component component = panels[i].getComponent();
            if (component instanceof JComponent) {
                JComponent jComponent = (JComponent) component;
                jComponent.putClientProperty(WizardDescriptor.PROP_CONTENT_SELECTED_INDEX, i);
                jComponent.putClientProperty(WizardDescriptor.PROP_CONTENT_DATA, stepNames);
            }
        }
    }

    @Override
    public void uninitialize(WizardDescriptor wizard) {
        this.wizard = null;
        this.panels = null;
        this.stepNames = null;
        this.index = 0;
    }

    @Override
    public Set<FileObject> instantiate() throws IOException {
        FileObject targetFolder = Templates.getTargetFolder(wizard);
        String targetName = Templates.getTargetName(wizard);
        FileObject template = Templates.getTemplate(wizard);

        if (targetFolder == null || targetName == null || template == null) {
            throw new IOException("Missing target folder, file name, or template.");
        }

        List<FieldSpec> fields = getFields(wizard);
        TypeResolver.Result resolution = TypeResolver.resolve(targetFolder, targetName, fields);

        DataFolder folder = DataFolder.findFolder(targetFolder);
        DataObject templateObject = DataObject.find(template);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("fields", toTemplateFields(fields));
        parameters.put("imports", resolution.getImports());

        DataObject created = templateObject.createFromTemplate(folder, targetName, parameters);
        notifyUnresolved(resolution.getUnresolved());

        return Collections.singleton(created.getPrimaryFile());
    }

    private static List<FieldSpec> getFields(WizardDescriptor wizard) {
        Object value = wizard.getProperty(FieldsWizardPanel.PROP_FIELDS);
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }

        List<FieldSpec> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (item instanceof FieldSpec) {
                result.add((FieldSpec) item);
            }
        }
        return result;
    }

    /**
     * Converts the field beans to plain maps so the FreeMarker engine does not
     * need reflective access to this module's classes.
     */
    private static List<Map<String, Object>> toTemplateFields(List<FieldSpec> fields) {
        List<Map<String, Object>> result = new ArrayList<>(fields.size());
        for (FieldSpec field : fields) {
            Map<String, Object> map = new HashMap<>();
            map.put("type", field.getType());
            map.put("name", field.getName());
            map.put("wither", field.isWither());
            result.add(map);
        }
        return result;
    }

    private static void notifyUnresolved(List<String> unresolved) {
        if (unresolved == null || unresolved.isEmpty()) {
            return;
        }
        String types = String.join(", ", unresolved);
        String message = NbBundle.getMessage(ValueClassWizardIterator.class,
                "ValueClassWizardIterator.unresolved", types);
        DialogDisplayer.getDefault().notifyLater(
                new NotifyDescriptor.Message(message, NotifyDescriptor.WARNING_MESSAGE));
    }

    @Override
    public String name() {
        if (stepNames != null && index >= 0 && index < stepNames.length) {
            return stepNames[index];
        }
        return "";
    }

    @Override
    public boolean hasNext() {
        return panels != null && index < panels.length - 1;
    }

    @Override
    public boolean hasPrevious() {
        return index > 0;
    }

    @Override
    public void nextPanel() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        index++;
    }

    @Override
    public void previousPanel() {
        if (!hasPrevious()) {
            throw new NoSuchElementException();
        }
        index--;
    }

    @Override
    public WizardDescriptor.Panel<WizardDescriptor> current() {
        return panels[index];
    }

    @Override
    public void addChangeListener(ChangeListener listener) {
        // The iterator itself never changes panel count.
    }

    @Override
    public void removeChangeListener(ChangeListener listener) {
        // The iterator itself never changes panel count.
    }
}
