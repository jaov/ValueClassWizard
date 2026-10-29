package dev.j8a.valuewizard;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import org.openide.util.NbBundle;

/**
 * Dialog content used by the Withable code generator to choose fields.
 */
final class WithablePanel extends JPanel {

    private final WithableTableModel model;
    private final JCheckBox selectAllCheckBox;
    private boolean updating;

    WithablePanel(List<WithableField> fields) {
        super(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel hint = new JLabel("<html><body style=\"width: 340px\">"
                + NbBundle.getMessage(WithablePanel.class, "WithablePanel.hint")
                + "</body></html>");
        selectAllCheckBox = new JCheckBox(
                NbBundle.getMessage(WithablePanel.class, "WithablePanel.selectAll"), true);

        JPanel header = new JPanel(new BorderLayout(0, 6));
        header.add(hint, BorderLayout.NORTH);
        header.add(selectAllCheckBox, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        model = new WithableTableModel(fields);
        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(300);
        table.getColumnModel().getColumn(1).setPreferredWidth(60);
        int height = Math.max(80, Math.min(220, fields.size() * 22 + 24));
        table.setPreferredScrollableViewportSize(new Dimension(370, height));
        add(new JScrollPane(table), BorderLayout.CENTER);

        selectAllCheckBox.addItemListener(e -> {
            if (updating) {
                return;
            }
            updating = true;
            try {
                model.setAll(selectAllCheckBox.isSelected());
            } finally {
                updating = false;
            }
        });

        model.addTableModelListener(e -> {
            if (!updating) {
                updateSelectAll();
            }
        });
    }

    List<String> getSelectedNames() {
        return model.getSelectedNames();
    }

    private void updateSelectAll() {
        boolean all = model.getRowCount() > 0;
        for (int i = 0; i < model.getRowCount(); i++) {
            if (!model.isSelected(i)) {
                all = false;
                break;
            }
        }
        boolean previous = updating;
        updating = true;
        try {
            selectAllCheckBox.setSelected(all);
        } finally {
            updating = previous;
        }
    }

    private static final class WithableTableModel extends AbstractTableModel {

        private final List<WithableField> fields;
        private final boolean[] selected;
        private final String[] columns = {
            NbBundle.getMessage(WithablePanel.class, "WithablePanel.column.field"),
            NbBundle.getMessage(WithablePanel.class, "WithablePanel.column.with")
        };

        WithableTableModel(List<WithableField> fields) {
            this.fields = new ArrayList<>(fields);
            this.selected = new boolean[fields.size()];
            Arrays.fill(this.selected, true);
        }

        void setAll(boolean value) {
            Arrays.fill(selected, value);
            fireTableDataChanged();
        }

        boolean isSelected(int row) {
            return selected[row];
        }

        List<String> getSelectedNames() {
            List<String> result = new ArrayList<>();
            for (int i = 0; i < selected.length; i++) {
                if (selected[i]) {
                    result.add(fields.get(i).name);
                }
            }
            return result;
        }

        @Override
        public int getRowCount() {
            return fields.size();
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
            return columnIndex == 1 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 1;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            WithableField field = fields.get(rowIndex);
            if (columnIndex == 0) {
                return field.name + " : " + field.type;
            }
            return selected[rowIndex];
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex == 1) {
                selected[rowIndex] = Boolean.TRUE.equals(value);
                fireTableCellUpdated(rowIndex, columnIndex);
            }
        }
    }
}
