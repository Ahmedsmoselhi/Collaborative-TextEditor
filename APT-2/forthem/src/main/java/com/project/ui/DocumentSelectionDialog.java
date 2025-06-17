package com.project.ui;

import com.project.network.DatabaseService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Pair;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.List;
import java.util.Optional;

/**
 * Dialog for selecting or creating documents.
 */
public class DocumentSelectionDialog {
    
    /**
     * Shows a document selection dialog.
     * @param userId The user ID.
     * @return A pair containing the document ID and document title if a document was selected, null otherwise.
     */
    public static Pair<String, String> showDocumentSelectionDialog(String userId) {
        // Create the custom dialog
        Dialog<Pair<String, String>> dialog = new Dialog<>();
        dialog.setTitle("Document Selection");
        dialog.setHeaderText("Select a document or create a new one");
        
        // Set the button types
        ButtonType openButtonType = new ButtonType("Open", ButtonBar.ButtonData.OK_DONE);
        ButtonType createButtonType = new ButtonType("Create New", ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().getButtonTypes().addAll(openButtonType, createButtonType, ButtonType.CANCEL);
        
        // Create the layout
        VBox vbox = new VBox(10);
        vbox.setPadding(new Insets(20, 20, 10, 20));
        
        // Add list view for documents
        Label listLabel = new Label("Your Documents:");
        ListView<DocumentItem> documentsListView = new ListView<>();
        documentsListView.setPrefHeight(300);
        
        // Load documents
        loadDocuments(userId, documentsListView);
        
        vbox.getChildren().addAll(listLabel, documentsListView);
        
        dialog.getDialogPane().setContent(vbox);
        
        // Enable/Disable open button depending on whether a document is selected
        Node openButton = dialog.getDialogPane().lookupButton(openButtonType);
        openButton.setDisable(true);
        
        documentsListView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            openButton.setDisable(newValue == null);
        });
        
        // Convert the result when the open button is clicked
        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == openButtonType) {
                DocumentItem selectedItem = documentsListView.getSelectionModel().getSelectedItem();
                if (selectedItem != null) {
                    return new Pair<>(selectedItem.getId(), selectedItem.getTitle());
                }
            } else if (dialogButton == createButtonType) {
                return showCreateDocumentDialog(userId);
            }
            return null;
        });
        
        Optional<Pair<String, String>> result = dialog.showAndWait();
        return result.orElse(null);
    }
    
    /**
     * Shows a dialog for creating a new document.
     * @param userId The user ID.
     * @return A pair containing the document ID and document title if creation was successful, null otherwise.
     */
    private static Pair<String, String> showCreateDocumentDialog(String userId) {
        // Create the custom dialog
        Dialog<Pair<String, String>> dialog = new Dialog<>();
        dialog.setTitle("Create Document");
        dialog.setHeaderText("Create a new document");
        
        // Set the button types
        ButtonType createButtonType = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createButtonType, ButtonType.CANCEL);
        
        // Create the grid and add the components
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));
        
        TextField titleField = new TextField();
        titleField.setPromptText("Document Title");
        
        grid.add(new Label("Title:"), 0, 0);
        grid.add(titleField, 1, 0);
        
        // Enable/Disable create button depending on whether a title was entered
        Node createButton = dialog.getDialogPane().lookupButton(createButtonType);
        createButton.setDisable(true);
        
        // Do validation
        titleField.textProperty().addListener((observable, oldValue, newValue) -> {
            createButton.setDisable(newValue.trim().isEmpty());
        });
        
        dialog.getDialogPane().setContent(grid);
        
        // Request focus on the title field by default
        Platform.runLater(titleField::requestFocus);
        
        // Convert the result when the create button is clicked
        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == createButtonType) {
                String title = titleField.getText().trim();
                
                // Try to create the document
                String documentId = DatabaseService.getInstance().createDocument(title, userId);
                if (documentId != null) {
                    return new Pair<>(documentId, title);
                } else {
                    // Show an error alert
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Creation Failed");
                    alert.setHeaderText("Could not create document");
                    alert.setContentText("Please try again later.");
                    alert.showAndWait();
                }
            }
            return null;
        });
        
        Optional<Pair<String, String>> result = dialog.showAndWait();
        return result.orElse(null);
    }
    
    /**
     * Loads documents for the given user.
     * @param userId The user ID.
     * @param listView The list view to populate.
     */
    private static void loadDocuments(String userId, ListView<DocumentItem> listView) {
        ObservableList<DocumentItem> items = FXCollections.observableArrayList();
        
        // Get documents from the database
        List<Document> documents = DatabaseService.getInstance().getDocumentsByOwner(userId);
        
        // Add documents to the list
        for (Document doc : documents) {
            String id;
            // Handle the case where _id might be an ObjectId or a String
            Object idObj = doc.get("_id");
            if (idObj instanceof ObjectId) {
                id = ((ObjectId) idObj).toString();
            } else {
                id = idObj.toString();
            }
            String title = doc.getString("title");
            items.add(new DocumentItem(id, title));
        }
        
        // Sort by title
        items.sort((a, b) -> a.getTitle().compareToIgnoreCase(b.getTitle()));
        
        listView.setItems(items);
        
        // Set cell factory to display titles
        listView.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(DocumentItem item, boolean empty) {
                super.updateItem(item, empty);
                
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.getTitle());
                }
            }
        });
    }
    
    /**
     * Class representing a document item in the list view.
     */
    private static class DocumentItem {
        private final String id;
        private final String title;
        
        public DocumentItem(String id, String title) {
            this.id = id;
            this.title = title;
        }
        
        public String getId() {
            return id;
        }
        
        public String getTitle() {
            return title;
        }
        
        @Override
        public String toString() {
            return title;
        }
    }
} 