package com.project.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Pair;

public class JoinSessionDialog extends Dialog<Pair<String, Boolean>> {
    
    public JoinSessionDialog() {
        setTitle("Join Session");
        setHeaderText("Enter the session code to join a collaboration session");
        
        ButtonType joinButtonType = new ButtonType("Join", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(joinButtonType, ButtonType.CANCEL);
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));
        
        TextField codeField = new TextField();
        codeField.setPromptText("Enter session code");
        
        ToggleGroup roleGroup = new ToggleGroup();
        RadioButton editorRole = new RadioButton("Editor (can make changes)");
        RadioButton viewerRole = new RadioButton("Viewer (read-only)");
        editorRole.setToggleGroup(roleGroup);
        viewerRole.setToggleGroup(roleGroup);
        editorRole.setSelected(true);
        
        TextFlow infoText = new TextFlow();
        Text helpText = new Text(
            "When joining a session:\n" +
            "• For EDITOR access: Use the editor code shared by the host\n" +
            "• For VIEW-ONLY access: Use either the viewer code or editor code\n\n" +
            "Using the wrong code for your selected role may be rejected."
        );
        helpText.setStyle("-fx-font-size: 12px;");
        infoText.getChildren().add(helpText);
        infoText.setStyle("-fx-background-color: #f8f8f8; -fx-padding: 10px; -fx-border-color: #ccc;");
        
        grid.add(new Label("Session Code:"), 0, 0);
        grid.add(codeField, 1, 0);
        
        VBox roleBox = new VBox(10);
        roleBox.getChildren().addAll(
            new Label("Select your role:"),
            editorRole,
            viewerRole
        );
        grid.add(roleBox, 0, 1, 2, 1);
        
        grid.add(infoText, 0, 2, 2, 1);
        
        Button joinButton = (Button) getDialogPane().lookupButton(joinButtonType);
        joinButton.setDisable(true);
        
        codeField.textProperty().addListener((observable, oldValue, newValue) -> {
            joinButton.setDisable(newValue.trim().isEmpty());
        });
        
        getDialogPane().setContent(grid);
        
        Platform.runLater(codeField::requestFocus);
        
        setResultConverter(dialogButton -> {
            if (dialogButton == joinButtonType) {
                return new Pair<>(codeField.getText(), editorRole.isSelected());
            }
            return null;
        });
        
        System.out.println("Join Session Dialog initialized");
    }
}