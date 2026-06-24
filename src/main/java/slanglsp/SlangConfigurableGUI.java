package slanglsp;

import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;

import java.awt.GridBagConstraints;
import java.util.Vector;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class SlangConfigurableGUI {
    private static final int TEXT_FIELD_COLUMNS = 40;

    private Project mProject;
    private SlangPersistentStateConfig mConfig;

    private JCheckBox enableInlayHintsForDeducedTypes;
    private JCheckBox enableInlayHintsForParameterNames;
    private JCheckBox enableSearchingSubDirectoriesOfWorkspace;
    private JCheckBox enableStrictPerModuleIsolation;
    private JTextField explicitSlangdLocation;
    private JPanel root;
    private JButton additionalIncludePathsButton;
    private JButton predefinedMacrosButton;
    private JPanel additionalIncludePathsContainer;
    private JPanel predefinedMacrosContainer;
    private JComboBox enableCommitCharactersInAutoCompletion;
    private JLabel seperatorLabel1;
    private JLabel seperatorLabel0;

    JPanel getRootPanel()
    {
        return root;
    }

    Vector<JTextField> convertStringListIntoTextFieldVector(java.util.List<String> stringList)
    {
        Vector<JTextField> textFieldList = new Vector<>();
        textFieldList.setSize(stringList.size());
        for(int i = 0; i < stringList.size(); i++)
        {
            JTextField textField = new JTextField(stringList.get(i));
            textField.setColumns(TEXT_FIELD_COLUMNS);
            textFieldList.set(i, textField);
        }
        return textFieldList;
    }

    Vector<String> getStringListFromPanelOwnedTextFields(JPanel panel)
    {
        Vector<String> stringList = new Vector<>();
        for(var obj : panel.getComponents())
        {
            if(!(obj instanceof JTextField))
                continue;
            stringList.add(((JTextField)obj).getText());
        }
        return stringList;
    }

    boolean addedDefaultListeners = false;
    public void createUI(Project project)
    {
        mProject = project;
        mConfig = SlangPersistentStateConfig.getInstance(project);
        setGUIStateWithState(mConfig.getState());
        addDefaultListeners();
    }

    SlangPersistentStateConfig.State deriveStateFromGUI()
    {
        SlangPersistentStateConfig.State state = new SlangPersistentStateConfig.State();
        state.additionalIncludePaths = getStringListFromPanelOwnedTextFields(additionalIncludePathsContainer);
        state.predefinedMacros = getStringListFromPanelOwnedTextFields(predefinedMacrosContainer);

        state.explicitSlangdLocation = explicitSlangdLocation.getText();

        state.enableCommitCharactersInAutoCompletion = (String)enableCommitCharactersInAutoCompletion.getSelectedItem();

        state.enableInlayHintsForDeducedTypes = enableInlayHintsForDeducedTypes.isSelected();
        state.enableInlayHintsForParameterNames = enableInlayHintsForParameterNames.isSelected();
        state.enableSearchingSubDirectoriesOfWorkspace = enableSearchingSubDirectoriesOfWorkspace.isSelected();
        state.enableStrictPerModuleIsolation = enableStrictPerModuleIsolation.isSelected();

        return state;
    }

    void setGUIStateWithState(SlangPersistentStateConfig.State state)
    {
        setPanelContentToListOfObjects(additionalIncludePathsContainer, convertStringListIntoTextFieldVector(state.additionalIncludePaths));
        setPanelContentToListOfObjects(predefinedMacrosContainer, convertStringListIntoTextFieldVector(state.predefinedMacros));

        explicitSlangdLocation.setText(state.explicitSlangdLocation);

        enableCommitCharactersInAutoCompletion.setSelectedItem(state.enableCommitCharactersInAutoCompletion);

        enableInlayHintsForDeducedTypes.setSelected(state.enableInlayHintsForDeducedTypes);
        enableInlayHintsForParameterNames.setSelected(state.enableInlayHintsForParameterNames);
        enableSearchingSubDirectoriesOfWorkspace.setSelected(state.enableSearchingSubDirectoriesOfWorkspace);
        enableStrictPerModuleIsolation.setSelected(state.enableStrictPerModuleIsolation);

        root.revalidate();
        root.repaint();
        root.updateUI();
    }

    void addTextFieldToPanel(JPanel panel, JTextField field)
    {
        field.setColumns(Math.max(field.getColumns(), TEXT_FIELD_COLUMNS));

        GridBagConstraints fieldConstraint = new GridBagConstraints();
        fieldConstraint.gridy = panel.getComponentCount() / 2;
        fieldConstraint.gridx = 0;
        fieldConstraint.weightx = 1;
        fieldConstraint.weighty = 0;
        fieldConstraint.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraint.anchor = GridBagConstraints.NORTHWEST;
        fieldConstraint.insets = JBUI.insets(2, 0, 2, 6);
        panel.add(field, fieldConstraint);

        GridBagConstraints buttonConstraint = new GridBagConstraints();
        buttonConstraint.gridy = fieldConstraint.gridy;
        buttonConstraint.gridx = 1;
        buttonConstraint.weightx = 0;
        buttonConstraint.weighty = 0;
        buttonConstraint.anchor = GridBagConstraints.NORTHEAST;
        buttonConstraint.insets = JBUI.insets(2, 0);
        JButton deleteButton = new JButton("-");
        deleteButton.setBackground(new JBColor(new Color(0, 120, 229), new Color(0, 120, 229)));
        deleteButton.addActionListener(new ActionListenerDeleteObjWhenClicked(panel, deleteButton, field));
        panel.add(deleteButton, buttonConstraint);

        panel.revalidate();
        panel.repaint();
    }

    void setPanelContentToListOfObjects(JPanel panel, Vector<JTextField> objects)
    {
        panel.removeAll();
        for(var i : objects)
        {
            addTextFieldToPanel(panel, i);
        }
    }

    public void apply()
    {
        mConfig.setState(this.deriveStateFromGUI());

        // Bring the static/per-module servers in line with the new settings. This must
        // go through syncWithStrictModeSetting rather than a bare restartLanguageServer:
        // toggling strict mode off needs the static definition re-enabled and started,
        // not just restarted, and toggling it on needs the static definition disabled
        // *before* it could be lazily started again — see that method's javadoc.
        SlangLanguageServerFactory.syncWithStrictModeSetting(mProject);
    }

    SlangPersistentStateConfig.State resetState = null;
    public void reset()
    {
        if(resetState != null)
        {
            setGUIStateWithState(resetState);
            apply();
        }
        else
            resetState = this.deriveStateFromGUI();
    }

    public boolean isModified()
    {
        return !mConfig.getState().equals(deriveStateFromGUI());
    }

    class ActionListenerAddWhenClicked implements ActionListener
    {
        JPanel toModify;
        ActionListenerAddWhenClicked(JPanel toModify)
        {
            this.toModify = toModify;
        }

        public void actionPerformed(ActionEvent e)
        {
            JTextField textField = new JTextField();
            textField.setColumns(TEXT_FIELD_COLUMNS);
            addTextFieldToPanel(toModify, textField);
            textField.requestFocusInWindow();
        }
    }

    static class ActionListenerDeleteObjWhenClicked implements ActionListener
    {
        JPanel parentPanel;
        JButton listeningObject;
        JTextField pairedObject;
        ActionListenerDeleteObjWhenClicked(JPanel parentPanel, JButton listeningObject, JTextField pairedObject)
        {
            this.parentPanel = parentPanel;
            this.listeningObject = listeningObject;
            this.pairedObject = pairedObject;
        }

        public void actionPerformed(ActionEvent e)
        {
            GridBagLayout layout = (GridBagLayout)parentPanel.getLayout();
            GridBagConstraints removedLayout = layout.getConstraints(listeningObject);

            parentPanel.remove(listeningObject);
            parentPanel.remove(pairedObject);

            for(var i : parentPanel.getComponents())
            {
                GridBagConstraints layoutToModify = layout.getConstraints(i);
                if(layoutToModify.gridy > removedLayout.gridy)
                {
                    layoutToModify.gridy -= 1;
                    layout.setConstraints(i, layoutToModify);
                }
            }

            parentPanel.revalidate();
            parentPanel.repaint();
        }
    }

    private void addDefaultListeners()
    {
        if(addedDefaultListeners)
            return;

        addedDefaultListeners = true;
        additionalIncludePathsButton.addActionListener(new ActionListenerAddWhenClicked(additionalIncludePathsContainer));
        predefinedMacrosButton.addActionListener(new ActionListenerAddWhenClicked(predefinedMacrosContainer));
    }
}
