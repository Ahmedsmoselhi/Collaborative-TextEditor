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
import com.project.network.CollaborativeEditorServer;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.prefs.Preferences;
import java.util.Date;
import java.io.PrintWriter;
import java.io.StringWriter;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;

public class Main extends Application {
    private static String userId = null;
    private static String username = null;
    private static String documentId = null;
    private static String documentTitle = null;

    private static final String PREF_LAST_USERNAME = "lastUsername";
    private static final String PREF_LAST_USER_ID = "lastUserId";

    @Override
    public void start(Stage primaryStage) {
        try {
            Preferences prefs = Preferences.userNodeForPackage(Main.class);
            String lastUsername = prefs.get(PREF_LAST_USERNAME, null);

            Pair<String, String> userInfo = LoginDialog.showLoginDialog(lastUsername);
            if (userInfo != null) {
                try {
                    username = userInfo.getKey();
                    userId = userInfo.getValue();

                    prefs.put(PREF_LAST_USERNAME, username);
                    prefs.put(PREF_LAST_USER_ID, userId);

                    System.out.println("Successfully logged in as: " + username + " (ID: " + userId + ")");

                    Date lastLogin = com.project.network.DatabaseService.getInstance().getLastLoginTime(userId);
                    if (lastLogin != null) {
                        System.out.println("Last login: " + lastLogin);
                    }

                    FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/editor.fxml"));
                    Parent root = loader.load();

                    EditorController controller = loader.getController();
                    controller.setUserId(userId);
                    controller.setUsername(username);

                    primaryStage.setTitle("Collaborative Text Editor - " + username);

                    primaryStage.setMinWidth(800);
                    primaryStage.setMinHeight(600);
                    primaryStage.setScene(new Scene(root, 1024, 768));

                    primaryStage.show();

                    if (documentId == null) {
                        handleShowDocumentSelection(controller, primaryStage);
                    } else {
                        controller.setDocumentInfo(documentId, documentTitle);
                    }

                    primaryStage.setOnCloseRequest(event -> {
                        System.out.println("Shutting down application...");
                        try {
                            com.project.network.DatabaseService.getInstance().close();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                } catch (Exception e) {
                    showError("Error loading editor", e.getMessage(), e);
                }
            } else {
                Platform.exit();
            }
        } catch (Exception e) {
            showError("Startup Error", "An error occurred during application startup", e);
            Platform.exit();
        }
    }

    private void handleShowDocumentSelection(EditorController controller, Stage stage) {
        Pair<String, String> documentInfo = DocumentSelectionDialog.showDocumentSelectionDialog(userId);
        if (documentInfo != null) {
            String key = documentInfo.getKey();

            if (key != null && key.startsWith("JOIN:")) {
                String[] parts = key.split(":");
                if (parts.length >= 3) {
                    String code = parts[1];
                    boolean isEditorRole = "EDITOR".equals(parts[2]);

                    System.out.println("Handling join request - Code: " + code + ", Role: " + (isEditorRole ? "EDITOR" : "VIEWER"));
                    controller.setJoinSessionInfo(code, isEditorRole);
                }
            } else {
                documentId = key;
                documentTitle = documentInfo.getValue();

                stage.setTitle("Collaborative Text Editor - " + documentTitle + " (" + username + ")");

                controller.setDocumentInfo(documentId, documentTitle);
            }
        } else {
            String defaultTitle = "Untitled Document";
            String defaultId = com.project.network.DatabaseService.getInstance().createDocument(defaultTitle, userId);

            stage.setTitle("Collaborative Text Editor - " + defaultTitle + " (" + username + ")");

            controller.setDocumentInfo(defaultId, defaultTitle);
        }
    }

    public static void main(String[] args) {
        CollaborativeEditorServer server = new CollaborativeEditorServer();
        server.start();
        System.out.println("Server started successfully on port " + server.getPort());

        if (args.length > 0) {
            userId = args[0];
            username = "Test User";
        }

        launch(args);
    }

    private void showError(String title, String message, Exception exception) {
        exception.printStackTrace();
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);

        if (exception != null) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            exception.printStackTrace(pw);
            String exceptionText = sw.toString();

            Label label = new Label("Exception stacktrace:");
            TextArea textArea = new TextArea(exceptionText);
            textArea.setEditable(false);
            textArea.setWrapText(true);
            textArea.setMaxWidth(Double.MAX_VALUE);
            textArea.setMaxHeight(Double.MAX_VALUE);

            GridPane expContent = new GridPane();
            expContent.setMaxWidth(Double.MAX_VALUE);
            expContent.add(label, 0, 0);
            expContent.add(textArea, 0, 1);

            alert.getDialogPane().setExpandableContent(expContent);
        }

        alert.showAndWait();
    }
}