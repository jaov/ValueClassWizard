package dev.j8a.valuewizard;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.SourceVersion;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.event.ChangeListener;
import javax.swing.table.AbstractTableModel;
import org.openide.util.ChangeSupport;
import org.openide.util.NbBundle;

/**
 * Visual panel used to enter the value class fields.
 */
final class FieldsPanel extends JPanel {

    private final ChangeSupport changeSupport = new ChangeSupport(this);
    private final FieldsTableModel model = new FieldsTableModel();
    private final JTable table;
    private boolean updating;

    FieldsPanel() {
        super(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel hint = new JLabel("<html><body style=\"width: 420px\">"
                + NbBundle.getMessage(FieldsPanel.class, "FieldsPanel.hint")
                + "</body></html>");
        add(hint, BorderLayout.NORTH);

        table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(240);
        table.getColumnModel().getColumn(1).setPreferredWidth(170);
        table.setPreferredScrollableViewportSize(new Dimension(430, 170));
        add(new JScrollPane(table), BorderLayout.CENTER);

        JButton addButton = new JButton(NbBundle.getMessage(FieldsPanel.class, "FieldsPanel.add"));
        JButton removeButton = new JButton(NbBundle.getMessage(FieldsPanel.class, "FieldsPanel.remove"));
        addButton.addActionListener(e -> addRow());
        removeButton.addActionListener(e -> removeSelectedRow());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.add(addButton);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(removeButton);
        add(buttons, BorderLayout.SOUTH);

        model.addTableModelListener(e -> {
            if (!updating) {
                changeSupport.fireChange();
            }
        });

        model.addRow(new FieldSpec());
        table.setRowSelectionInterval(0, 0);
    }

    void addChangeListener(ChangeListener listener) {
        changeSupport.addChangeListener(listener);
    }

    void removeChangeListener(ChangeListener listener) {
        changeSupport.removeChangeListener(listener);
    }

    /**
     * Returns the non-empty rows, with surrounding whitespace removed.
     */
    List<FieldSpec> getFields() {
        commitEdit();
        List<FieldSpec> result = new ArrayList<>();
        for (FieldSpec field : model.getFields()) {
            if (field.isEmpty()) {
                continue;
            }
            result.add(new FieldSpec(field.getType().trim(), field.getName().trim()));
        }
        return result;
    }

    private void commitEdit() {
        if (table != null && table.isEditing() && table.getCellEditor() != null) {
            boolean previous = updating;
            updating = true;
            try {
                table.getCellEditor().stopCellEditing();
            } finally {
                updating = previous;
            }
        }
    }

    void setFields(List<FieldSpec> fields) {
        updating = true;
        try {
            model.setFields(fields);
            if (model.getRowCount() == 0) {
                model.addRow(new FieldSpec());
            }
        } finally {
            updating = false;
        }
        table.setRowSelectionInterval(0, 0);
    }

    /**
     * Returns null when the entered fields are valid, otherwise a localized
     * error message.
     */
    String getValidationError() {
        boolean any = false;
        Set<String> names = new HashSet<>();
        for (FieldSpec field : model.getFields()) {
            String type = field.getType().trim();
            String name = field.getName().trim();

            if (type.isEmpty() && name.isEmpty()) {
                continue;
            }
            any = true;

            if (type.isEmpty() || name.isEmpty()) {
                return NbBundle.getMessage(FieldsPanel.class, "FieldsPanel.error.incomplete");
            }
            if ("void".equals(type) || "var".equals(type)
                    || type.indexOf('\n') >= 0 || type.indexOf('\r') >= 0
                    || type.indexOf(';') >= 0 || type.indexOf('{') >= 0
                    || type.indexOf('}') >= 0 || type.indexOf('=') >= 0
                    || type.indexOf('(') >= 0 || type.indexOf(')') >= 0) {
                return NbBundle.getMessage(FieldsPanel.class, "FieldsPanel.error.invalidType", type);
            }
            if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
                return NbBundle.getMessage(FieldsPanel.class, "FieldsPanel.error.invalidName", name);
            }
            if (!names.add(name)) {
                return NbBundle.getMessage(FieldsPanel.class, "FieldsPanel.error.duplicate", name);
            }
        }

        if (!any) {
            return NbBundle.getMessage(FieldsPanel.class, "FieldsPanel.error.empty");
        }
        return null;
    }

    private void addRow() {
        model.addRow(new FieldSpec());
        int row = model.getRowCount() - 1;
        table.setRowSelectionInterval(row, row);
        table.editCellAt(row, 0);
        table.requestFocusInWindow();
    }

    private void removeSelectedRow() {
        int row = table.getSelectedRow();
        if (row >= 0) {
            model.removeRow(row);
        }
        if (model.getRowCount() == 0) {
            model.addRow(new FieldSpec());
        }
        int newRow = Math.min(Math.max(row, 0), model.getRowCount() - 1);
        table.setRowSelectionInterval(newRow, newRow);
    }

    private static final class FieldsTableModel extends AbstractTableModel {

        private final List<FieldSpec> rows = new ArrayList<>();
        private final String[] columns = {
            NbBundle.getMessage(FieldsPanel.class, "FieldsPanel.column.type"),
            NbBundle.getMessage(FieldsPanel.class, "FieldsPanel.column.name")
        };

        List<FieldSpec> getFields() {
            return new ArrayList<>(rows);
        }

        void setFields(List<FieldSpec> fields) {
            rows.clear();
            if (fields != null) {
                for (FieldSpec field : fields) {
                    if (field != null) {
                        rows.add(new FieldSpec(field.getType(), field.getName()));
                    }
                }
            }
            fireTableDataChanged();
        }

        void addRow(FieldSpec field) {
            rows.add(field);
            fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        }

        void removeRow(int row) {
            if (row < 0 || row >= rows.size()) {
                return;
            }
            rows.remove(row);
            fireTableRowsDeleted(row, row);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return true;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            FieldSpec field = rows.get(rowIndex);
            return columnIndex == 0 ? field.getType() : field.getName();
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            FieldSpec field = rows.get(rowIndex);
            String text = value == null ? "" : value.toString();
            if (columnIndex == 0) {
                field.setType(text);
            } else {
                field.setName(text);
            }
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }
}
