package dev.j8a.valuewizard;

import java.awt.Component;
import java.util.List;
import javax.swing.event.ChangeListener;
import org.openide.WizardDescriptor;
import org.openide.util.ChangeSupport;
import org.openide.util.HelpCtx;

/**
 * Wizard panel that collects the value class fields.
 */
final class FieldsWizardPanel implements WizardDescriptor.Panel<WizardDescriptor> {

    static final String PROP_FIELDS = "valueClassFields";

    private final FieldsPanel component;
    private final ChangeSupport changeSupport = new ChangeSupport(this);
    private WizardDescriptor wizard;
    private boolean valid;

    FieldsWizardPanel() {
        component = new FieldsPanel();
        component.addChangeListener(e -> {
            updateState();
            changeSupport.fireChange();
        });
        valid = component.getValidationError() == null;
    }

    @Override
    public Component getComponent() {
        return component;
    }

    @Override
    public HelpCtx getHelp() {
        return HelpCtx.DEFAULT_HELP;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void readSettings(WizardDescriptor settings) {
        wizard = settings;
        Object value = settings.getProperty(PROP_FIELDS);
        if (value instanceof List) {
            component.setFields((List<FieldSpec>) value);
        }
        updateState();
    }

    @Override
    public void storeSettings(WizardDescriptor settings) {
        settings.putProperty(PROP_FIELDS, component.getFields());
    }

    @Override
    public boolean isValid() {
        updateState();
        return valid;
    }

    @Override
    public void addChangeListener(ChangeListener listener) {
        changeSupport.addChangeListener(listener);
    }

    @Override
    public void removeChangeListener(ChangeListener listener) {
        changeSupport.removeChangeListener(listener);
    }

    private void updateState() {
        String error = component.getValidationError();
        valid = error == null;
        if (wizard != null) {
            wizard.putProperty(WizardDescriptor.PROP_ERROR_MESSAGE, error);
        }
    }
}
