package com.project;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;
import com.project.ui.EditorController;
import com.project.ui.LoginDialog;
import com.project.ui.DocumentSelectionDialog;
import javafx.util.Pair;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

public class Main extends Application {
    private static String userId = null;
    private static String username = null;
    private static String documentId = null;
    private static String documentTitle = null;
    
    @Override
    public void start(Stage primaryStage) {
        try {
            // Show login dialog if user is not authenticated
            if (userId == null) {
                boolean loginSuccess = false;
                
                while (!loginSuccess) {
                    try {
                        Pair<String, String> userInfo = LoginDialog.showLoginDialog();
                        if (userInfo == null) {
                            // User cancelled login
                            System.exit(0);
                            return;
                        }
                        
                        username = userInfo.getKey();
                        userId = userInfo.getValue();
                        loginSuccess = true;
                    } catch (Exception e) {
                        // Show error and allow retry
                        Alert error = new Alert(Alert.AlertType.ERROR, 
                            "Login error: " + e.getMessage() + "\nWould you like to try again?", 
                            ButtonType.YES, ButtonType.NO);
                        
                        Optional<ButtonType> result = error.showAndWait();
                        if (result.isEmpty() || result.get() == ButtonType.NO) {
                            System.exit(0);
                            return;
                        }
                    }
                }
            }
            
            // Show document selection dialog with retry mechanism
            boolean documentSelectionSuccess = false;
            
            while (!documentSelectionSuccess) {
                try {
                    Pair<String, String> documentInfo = DocumentSelectionDialog.showDocumentSelectionDialog(userId);
                    if (documentInfo == null) {
                        // User cancelled document selection
                        System.exit(0);
                        return;
                    }
                    
                    documentId = documentInfo.getKey();
                    documentTitle = documentInfo.getValue();
                    documentSelectionSuccess = true;
                } catch (Exception e) {
                    // Show error and allow retry
                    Alert error = new Alert(Alert.AlertType.ERROR, 
                        "Document selection error: " + e.getMessage() + "\nWould you like to try again?", 
                        ButtonType.YES, ButtonType.NO);
                    
                    Optional<ButtonType> result = error.showAndWait();
                    if (result.isEmpty() || result.get() == ButtonType.NO) {
                        System.exit(0);
                        return;
                    }
                }
            }
            
            // Load the FXML file with retry mechanism
            boolean fxmlLoadSuccess = false;
            Parent root = null;
            EditorController controller = null;
            
            while (!fxmlLoadSuccess) {
                try {
                    // Load the FXML file
                    FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/editor.fxml"));
                    root = loader.load();
                    
                    // Get the controller
                    controller = loader.getController();
                    fxmlLoadSuccess = true;
                } catch (IOException e) {
                    // Show error and allow retry
                    Alert error = new Alert(Alert.AlertType.ERROR, 
                        "Error loading application: " + e.getMessage() + "\nWould you like to try again?", 
                        ButtonType.YES, ButtonType.NO);
                    
                    Optional<ButtonType> result = error.showAndWait();
                    if (result.isEmpty() || result.get() == ButtonType.NO) {
                        System.exit(0);
                        return;
                    }
                }
            }
            
            try {
                // Set up the controller
                controller.setUserId(userId);
                controller.setUsername(username);
                
                // Init controller before setting document info to ensure network is ready
                controller.initialize();
                
                // Set document info after controller initialization
                controller.setDocumentInfo(documentId, documentTitle);
                
                // Set up the scene
                Scene scene = new Scene(root);
                
                // Set up the stage
                primaryStage.setTitle("Collaborative Text Editor - " + documentTitle + " (" + username + ")");
                primaryStage.setScene(scene);
                primaryStage.show();
                
                // Set up exception handler for JavaFX thread
                Thread.currentThread().setUncaughtExceptionHandler((thread, throwable) -> {
                    System.err.println("Uncaught exception in JavaFX thread: " + throwable.getMessage());
                    throwable.printStackTrace();
                    
                    Platform.runLater(() -> {
                        Alert error = new Alert(Alert.AlertType.ERROR, 
                            "Application error: " + throwable.getMessage(), 
                            ButtonType.OK);
                        error.showAndWait();
                    });
                });
            } catch (Exception e) {
                e.printStackTrace();
                Alert error = new Alert(Alert.AlertType.ERROR, 
                    "Error initializing application: " + e.getMessage(), 
                    ButtonType.OK);
                error.showAndWait();
                System.exit(1);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static void main(String[] args) {
        // Check if a user ID was provided as a command-line argument (for testing)
        if (args.length > 0) {
            userId = args[0];
            username = "Test User";
        }
        
        launch(args);
    }
} 