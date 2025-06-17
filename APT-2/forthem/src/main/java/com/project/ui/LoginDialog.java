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

/**
 * Dialog for user login and registration.
 */
public class LoginDialog {
    
    /**
     * Shows a login dialog and returns the authenticated user details.
     * @return A pair containing the username and user ID if login was successful, null otherwise.
     */
    public static Pair<String, String> showLoginDialog() {
        // Create the custom dialog
        Dialog<Pair<String, String>> dialog = new Dialog<>();
        dialog.setTitle("Login");
        dialog.setHeaderText("Welcome to Collaborative Text Editor");
        
        // Set the button types
        ButtonType loginButtonType = new ButtonType("Login", ButtonBar.ButtonData.OK_DONE);
        ButtonType registerButtonType = new ButtonType("Register", ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().getButtonTypes().addAll(loginButtonType, registerButtonType, ButtonType.CANCEL);
        
        // Create the grid and add the components
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));
        
        TextField usernameField = new TextField();
        usernameField.setPromptText("Username");
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Password");
        
        // Add error label for validation messages
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: red;");
        errorLabel.setWrapText(true);
        errorLabel.setMaxWidth(Double.MAX_VALUE);
        
        grid.add(new Label("Username:"), 0, 0);
        grid.add(usernameField, 1, 0);
        grid.add(new Label("Password:"), 0, 1);
        grid.add(passwordField, 1, 1);
        grid.add(errorLabel, 0, 2, 2, 1);
        
        // Enable/Disable login button depending on whether a username was entered
        Node loginButton = dialog.getDialogPane().lookupButton(loginButtonType);
        loginButton.setDisable(true);
        
        // Do validation
        usernameField.textProperty().addListener((observable, oldValue, newValue) -> {
            loginButton.setDisable(newValue.trim().isEmpty());
            // Clear error on input change
            errorLabel.setText("");
        });
        
        passwordField.textProperty().addListener((observable, oldValue, newValue) -> {
            // Clear error on input change
            errorLabel.setText("");
        });
        
        dialog.getDialogPane().setContent(grid);
        
        // Request focus on the username field by default
        Platform.runLater(usernameField::requestFocus);
        
        // Use a wrapper for the result to handle retry
        final Pair<String, String>[] resultWrapper = new Pair[1];
        
        // Convert the result to a username-password pair when the login button is clicked
        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == loginButtonType) {
                // Try to authenticate
                String username = usernameField.getText().trim();
                String password = passwordField.getText();
                
                try {
                    String userId = DatabaseService.getInstance().authenticateUser(username, password);
                    if (userId != null) {
                        return new Pair<>(username, userId);
                    } else {
                        // Show an error in the dialog instead of an alert
                        errorLabel.setText("Invalid username or password. Please try again.");
                        
                        // Prevent dialog from closing
                        Platform.runLater(() -> {
                            passwordField.clear();
                            passwordField.requestFocus();
                        });
                        
                        return null;
                    }
                } catch (Exception e) {
                    // Show error message for any exceptions
                    errorLabel.setText("Login error: " + e.getMessage());
                    
                    // Prevent dialog from closing
                    Platform.runLater(() -> {
                        passwordField.clear();
                        passwordField.requestFocus();
                    });
                    
                    return null;
                }
            } else if (dialogButton == registerButtonType) {
                // Show the registration dialog
                Pair<String, String> regResult = showRegistrationDialog();
                if (regResult != null) {
                    // If registration was successful, return the result directly
                    return regResult;
                } else {
                    // If registration was cancelled, keep the login dialog open
                    Platform.runLater(() -> dialog.show());
                    return null;
                }
            }
            return null;
        });
        
        // Show the dialog and handle the result
        while (resultWrapper[0] == null) {
            Optional<Pair<String, String>> result = dialog.showAndWait();
            if (result.isPresent()) {
                resultWrapper[0] = result.get();
            } else {
                // User cancelled
                break;
            }
        }
        
        return resultWrapper[0];
    }
    
    /**
     * Shows a registration dialog.
     * @return A pair containing the username and user ID if registration was successful, null otherwise.
     */
    private static Pair<String, String> showRegistrationDialog() {
        // Create the custom dialog
        Dialog<Pair<String, String>> dialog = new Dialog<>();
        dialog.setTitle("Register");
        dialog.setHeaderText("Create a new account");
        
        // Set the button types
        ButtonType registerButtonType = new ButtonType("Register", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(registerButtonType, ButtonType.CANCEL);
        
        // Create the grid and add the components
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
        
        // Enable/Disable register button depending on whether input is valid
        Node registerButton = dialog.getDialogPane().lookupButton(registerButtonType);
        registerButton.setDisable(true);
        
        // Add a label to show validation errors
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: red;");
        errorLabel.setWrapText(true);
        errorLabel.setMaxWidth(Double.MAX_VALUE);
        grid.add(errorLabel, 0, 3, 2, 1);
        
        // Do validation
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
        
        // Request focus on the username field by default
        Platform.runLater(usernameField::requestFocus);
        
        // Use a wrapper for the result to handle retry
        final Pair<String, String>[] resultWrapper = new Pair[1];
        
        // Convert the result when the register button is clicked
        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == registerButtonType) {
                String username = usernameField.getText().trim();
                String password = passwordField.getText();
                
                try {
                    // Try to register
                    boolean registered = DatabaseService.getInstance().registerUser(username, password);
                    if (registered) {
                        // If registration succeeded, try to authenticate to get the user ID
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
                        // Show error in the dialog
                        errorLabel.setText("Username may already be taken. Please choose another username.");
                        
                        // Keep dialog open
                        Platform.runLater(() -> {
                            usernameField.requestFocus();
                        });
                        
                        return null;
                    }
                } catch (Exception e) {
                    // Show error message for any exceptions
                    errorLabel.setText("Registration error: " + e.getMessage());
                    
                    // Keep dialog open
                    Platform.runLater(() -> {
                        usernameField.requestFocus();
                    });
                    
                    return null;
                }
            }
            return null;
        });
        
        // Show the dialog and handle the result
        Optional<Pair<String, String>> result = dialog.showAndWait();
        return result.orElse(null);
    }
} 