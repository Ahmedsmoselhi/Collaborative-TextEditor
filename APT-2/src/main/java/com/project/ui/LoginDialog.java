package com.project.ui;

import com.project.network.DatabaseService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Pair;

import java.util.Optional;

public class LoginDialog {
    
    public static Pair<String, String> showLoginDialog(String lastUsername) {
        Dialog<Pair<String, String>> dialog = new Dialog<>();
        dialog.setTitle("Login");
        dialog.setHeaderText("Welcome to Collaborative Text Editor");
        
        ButtonType loginButtonType = new ButtonType("Login", ButtonBar.ButtonData.OK_DONE);
        ButtonType registerButtonType = new ButtonType("Register", ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().getButtonTypes().addAll(loginButtonType, registerButtonType, ButtonType.CANCEL);
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));
        
        TextField usernameField = new TextField();
        usernameField.setPromptText("Username");
        if (lastUsername != null && !lastUsername.isEmpty()) {
            usernameField.setText(lastUsername);
        }
        
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Password");
        
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: red;");
        errorLabel.setWrapText(true);
        errorLabel.setMaxWidth(Double.MAX_VALUE);
        
        grid.add(new Label("Username:"), 0, 0);
        grid.add(usernameField, 1, 0);
        grid.add(new Label("Password:"), 0, 1);
        grid.add(passwordField, 1, 1);
        grid.add(errorLabel, 0, 2, 2, 1);
        
        Node loginButton = dialog.getDialogPane().lookupButton(loginButtonType);
        loginButton.setDisable(true);
        
        usernameField.textProperty().addListener((observable, oldValue, newValue) -> {
            loginButton.setDisable(newValue.trim().isEmpty());
            errorLabel.setText("");
        });
        
        passwordField.textProperty().addListener((observable, oldValue, newValue) -> {
            errorLabel.setText("");
        });
        
        dialog.getDialogPane().setContent(grid);
        
        Platform.runLater(usernameField::requestFocus);
        
        final Pair<String, String>[] resultWrapper = new Pair[1];
        
        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == loginButtonType) {
                String username = usernameField.getText().trim();
                String password = passwordField.getText();
                
                try {
                    String userId = DatabaseService.getInstance().authenticateUser(username, password);
                    if (userId != null) {
                        return new Pair<>(username, userId);
                    } else {
                        errorLabel.setText("Invalid username or password. Please try again.");
                        
                        Platform.runLater(() -> {
                            passwordField.clear();
                            passwordField.requestFocus();
                        });
                        
                        return null;
                    }
                } catch (Exception e) {
                    errorLabel.setText("Login error: " + e.getMessage());
                    
                    Platform.runLater(() -> {
                        passwordField.clear();
                        passwordField.requestFocus();
                    });
                    
                    return null;
                }
            } else if (dialogButton == registerButtonType) {
                Pair<String, String> regResult = showRegistrationDialog();
                if (regResult != null) {
                    return regResult;
                } else {
                    Platform.runLater(() -> dialog.show());
                    return null;
                }
            }
            return null;
        });
        
        while (resultWrapper[0] == null) {
            Optional<Pair<String, String>> result = dialog.showAndWait();
            if (result.isPresent()) {
                resultWrapper[0] = result.get();
            } else {
                break;
            }
        }
        
        return resultWrapper[0];
    }
    
    public static Pair<String, String> showLoginDialog() {
        return showLoginDialog(null);
    }
    
    private static Pair<String, String> showRegistrationDialog() {
        Dialog<Pair<String, String>> dialog = new Dialog<>();
        dialog.setTitle("Register");
        dialog.setHeaderText("Create a new account");
        
        ButtonType registerButtonType = new ButtonType("Register", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(registerButtonType, ButtonType.CANCEL);
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));
        
        TextField usernameField = new TextField();
        usernameField.setPromptText("Username");
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Password");
        PasswordField confirmPasswordField = new PasswordField();
        confirmPasswordField.setPromptText("Confirm Password");
        
        grid.add(new Label("Username:"), 0, 0);
        grid.add(usernameField, 1, 0);
        grid.add(new Label("Password:"), 0, 1);
        grid.add(passwordField, 1, 1);
        grid.add(new Label("Confirm Password:"), 0, 2);
        grid.add(confirmPasswordField, 1, 2);
        
        Node registerButton = dialog.getDialogPane().lookupButton(registerButtonType);
        registerButton.setDisable(true);
        
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: red;");
        errorLabel.setWrapText(true);
        errorLabel.setMaxWidth(Double.MAX_VALUE);
        grid.add(errorLabel, 0, 3, 2, 1);
        
        Runnable validateInput = () -> {
            String username = usernameField.getText().trim();
            String password = passwordField.getText();
            String confirmPassword = confirmPasswordField.getText();
            
            if (username.isEmpty()) {
                errorLabel.setText("Username cannot be empty");
                registerButton.setDisable(true);
            } else if (password.isEmpty()) {
                errorLabel.setText("Password cannot be empty");
                registerButton.setDisable(true);
            } else if (!password.equals(confirmPassword)) {
                errorLabel.setText("Passwords do not match");
                registerButton.setDisable(true);
            } else {
                errorLabel.setText("");
                registerButton.setDisable(false);
            }
        };
        
        usernameField.textProperty().addListener((observable, oldValue, newValue) -> validateInput.run());
        passwordField.textProperty().addListener((observable, oldValue, newValue) -> validateInput.run());
        confirmPasswordField.textProperty().addListener((observable, oldValue, newValue) -> validateInput.run());
        
        dialog.getDialogPane().setContent(grid);
        
        Platform.runLater(usernameField::requestFocus);
        
        final Pair<String, String>[] resultWrapper = new Pair[1];
        
        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == registerButtonType) {
                String username = usernameField.getText().trim();
                String password = passwordField.getText();
                
                try {
                    boolean registered = DatabaseService.getInstance().registerUser(username, password);
                    if (registered) {
                        String userId = DatabaseService.getInstance().authenticateUser(username, password);
                        if (userId != null) {
                            Alert alert = new Alert(Alert.AlertType.INFORMATION);
                            alert.setTitle("Registration Successful");
                            alert.setHeaderText("Your account has been created");
                            alert.setContentText("You can now log in with your credentials.");
                            alert.showAndWait();
                            return new Pair<>(username, userId);
                        } else {
                            errorLabel.setText("Registration succeeded but authentication failed. Please try logging in directly.");
                            return null;
                        }
                    } else {
                        errorLabel.setText("Username may already be taken. Please choose another username.");
                        
                        Platform.runLater(() -> {
                            usernameField.requestFocus();
                        });
                        
                        return null;
                    }
                } catch (Exception e) {
                    errorLabel.setText("Registration error: " + e.getMessage());
                    
                    Platform.runLater(() -> {
                        usernameField.requestFocus();
                    });
                    
                    return null;
                }
            }
            return null;
        });
        
        Optional<Pair<String, String>> result = dialog.showAndWait();
        return result.orElse(null);
    }
}