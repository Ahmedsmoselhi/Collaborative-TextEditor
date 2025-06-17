package com.project.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.util.Pair;

/**
 * A dialog for joining a collaboration session.
 */
public class JoinSessionDialog extends Dialog<Pair<String, Boolean>> {
    
    /**
     * Creates a new join session dialog.
     */
    public JoinSessionDialog() {
        setTitle("Join Session");
        setHeaderText("Enter the session code to join a collaboration session");
        
        // Set the button types
        ButtonType joinButtonType = new ButtonType("Join", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(joinButtonType, ButtonType.CANCEL);
        
        // Create the code input field and role selection
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));
        
        TextField codeField = new TextField();
        codeField.setPromptText("Session Code");
        
        ToggleGroup roleGroup = new ToggleGroup();
        RadioButton editorButton = new RadioButton("Join as Editor");
        RadioButton viewerButton = new RadioButton("Join as Viewer");
        editorButton.setToggleGroup(roleGroup);
        viewerButton.setToggleGroup(roleGroup);
        editorButton.setSelected(true);
        
        grid.add(new Label("Session Code:"), 0, 0);
        grid.add(codeField, 1, 0);
        grid.add(editorButton, 0, 1, 2, 1);
        grid.add(viewerButton, 0, 2, 2, 1);
        
        getDialogPane().setContent(grid);
        
        // Request focus on the code field by default
        Platform.runLater(codeField::requestFocus);
        
        // Convert the result to a code/role pair when the join button is clicked
        setResultConverter(dialogButton -> {
            if (dialogButton == joinButtonType) {
                String code = codeField.getText();
                boolean isEditor = editorButton.isSelected();
                
                // Basic validation
                if (code == null || code.trim().isEmpty()) {
                    return null;
                }
                
                return new Pair<>(code, isEditor);
            }
            return null;
        });
    }
} 