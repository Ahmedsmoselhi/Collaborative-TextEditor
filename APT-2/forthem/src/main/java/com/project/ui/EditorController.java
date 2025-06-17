package com.project.ui;

import com.project.crdt.CRDTCharacter;
import com.project.crdt.CRDTDocument;
import com.project.crdt.Position;
import com.project.network.NetworkClient;
import com.project.network.Operation;
import com.project.network.DatabaseService;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.scene.control.*;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Pair;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class EditorController {
    @FXML
    private TextArea editorArea;
    
    @FXML
    private ListView<String> usersListView;
    
    @FXML
    private TextField editorCodeField;
    
    @FXML
    private TextField viewerCodeField;
    
    @FXML
    private Label statusLabel;
    
    @FXML
    private AnchorPane editorContainer;
    
    // Observable list for the users
    private ObservableList<String> users = FXCollections.observableArrayList();
    
    // CRDT document
    private CRDTDocument document;
    
    // Network client
    private NetworkClient networkClient;
    
    // User role
    private boolean isEditor = true; // By default, the user is an editor
    
    // Cursor markers for other users
    private Map<String, CursorMarker> cursorMarkers = new HashMap<>();
    
    // Custom user ID (can be set from Main)
    private String userId = null;
    
    // User's username
    private String username = null;
    
    // Document information
    private String documentId = null;
    private String documentTitle = null;
    
    // Auto-save timer
    private java.util.Timer autoSaveTimer;
    
    // Performance optimization flags
    private AtomicBoolean isUpdatingText = new AtomicBoolean(false);
    private AtomicBoolean isSendingCursor = new AtomicBoolean(false);
    private long lastCursorSendTime = 0;
    private static final long CURSOR_THROTTLE_MS = 100; // Throttle cursor updates
    
    // Batch operation for improved performance
    private StringBuilder pendingInserts = new StringBuilder();
    private int pendingInsertBasePosition = -1;
    private java.util.Timer batchTimer;
    
    // Available colors for cursors
    private final Color[] cursorColors = {
        Color.web("#4285f4"), // Blue
        Color.web("#ea4335"), // Red
        Color.web("#fbbc05"), // Yellow
        Color.web("#34a853")  // Green
    };
    
    // Map of user IDs to usernames
    private Map<String, String> userMap = new HashMap<>();
    
    // Flag to track whether initialize() has been called
    private boolean initialized = false;
    
    /**
     * Sets a custom user ID. Must be called before initialize().
     * @param userId the user ID to set
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    /**
     * Sets the username. Must be called before initialize().
     * @param username the username to set
     */
    public void setUsername(String username) {
        this.username = username;
        // Store in user map right away
        if (userId != null && username != null) {
            userMap.put(userId, username);
        }
    }
    
    /**
     * Sets the document information. If initialize() has already been called,
     * this will immediately load the document.
     * @param documentId the document ID
     * @param documentTitle the document title
     */
    public void setDocumentInfo(String documentId, String documentTitle) {
        this.documentId = documentId;
        this.documentTitle = documentTitle;
        
        // If already initialized, load the document now
        if (initialized && documentId != null) {
            try {
                // Give a little time for network connection to establish
                Thread.sleep(200);
                loadDocumentContent();
            } catch (Exception e) {
                updateStatus("Error loading document: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    
    @FXML
    public void initialize() {
        // Prevent double initialization
        if (initialized) {
            return;
        }
        
        try {
            // Initialize the CRDT document with a site ID (user ID or random UUID)
            String siteId = (userId != null) ? userId : UUID.randomUUID().toString();
            document = new CRDTDocument(siteId);
            
            // Initialize the network client
            networkClient = new NetworkClient(siteId);
            
            // Set up listeners
            setupNetworkListeners();
            setupEditorListeners();
            
            // Initialize user list
            usersListView.setItems(users);
            
            // Connect to the network
            boolean connected = networkClient.connect();
            if (connected) {
                updateStatus("Connected to network as " + (username != null ? username : siteId));
                
                // Setup auto-save timer (save every 30 seconds)
                setupAutoSaveTimer();
                
                // Setup batch timer for performance
                setupBatchTimer();
            } else {
                updateStatus("Failed to connect to network");
            }
            
            // Make sure editorContainer contains editorArea
            if (!editorContainer.getChildren().contains(editorArea)) {
                editorContainer.getChildren().add(editorArea);
                AnchorPane.setTopAnchor(editorArea, 0.0);
                AnchorPane.setRightAnchor(editorArea, 0.0);
                AnchorPane.setBottomAnchor(editorArea, 0.0);
                AnchorPane.setLeftAnchor(editorArea, 0.0);
            }
            
            // Set flag to prevent double initialization
            initialized = true;
        } catch (Exception e) {
            updateStatus("Error initializing editor: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void setupNetworkListeners() {
        // Listen for operations from the network
        networkClient.addOperationListener(operation -> {
            Platform.runLater(() -> {
                handleRemoteOperation(operation);
            });
        });
        
        // Listen for presence updates
        networkClient.addPresenceListener(newUsers -> {
            Platform.runLater(() -> {
                users.clear();
                
                // Display usernames instead of user IDs
                for (String userId : newUsers) {
                    // Add self with "(you)" suffix
                    if (userId.equals(this.userId)) {
                        users.add((username != null ? username : "You") + " (you)");
                        userMap.put(userId, username != null ? username : "You");
                    } else {
                        // Try to get username from our map, or use a prettier fallback
                        String displayName = userMap.getOrDefault(userId, "User " + (userId.length() > 6 ? userId.substring(0, 6) : userId));
                        users.add(displayName);
                    }
                }
                
                // Update cursor markers
                updateCursorMarkers(newUsers);
            });
        });
        
        // Listen for error messages
        networkClient.addErrorListener(error -> {
            Platform.runLater(() -> {
                updateStatus("Error: " + error);
            });
        });
        
        // Listen for code updates
        networkClient.addCodeListener(codePair -> {
            Platform.runLater(() -> {
                editorCodeField.setText(codePair.getEditorCode());
                viewerCodeField.setText(codePair.getViewerCode());
                updateStatus("Generated sharing codes");
            });
        });
    }
    
    private void setupEditorListeners() {
        // Add a listener for key typing events
        editorArea.addEventFilter(KeyEvent.KEY_TYPED, event -> {
            if (!isEditor) {
                // Prevent editing for viewers
                event.consume();
                return;
            }
            
            char c = event.getCharacter().charAt(0);
            
            // Ignore control characters (e.g., backspace, delete)
            if (c < 32) {
                return;
            }
            
            // Get the current caret position
            int caretPosition = editorArea.getCaretPosition();
            
            // Add to batch for better performance
            addToBatchInsert(caretPosition, c);
            
            // Consume the event to prevent the default handling
            event.consume();
        });
        
        // Add a listener for key press events (for delete/backspace)
        editorArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!isEditor) {
                // Prevent editing for viewers
                event.consume();
                return;
            }
            
            // Process batch before handling these special keys
            processBatchInserts();
            
            switch (event.getCode()) {
                case BACK_SPACE:
                    handleBackspace();
                    event.consume();
                    break;
                case DELETE:
                    handleDelete();
                    event.consume();
                    break;
                case ENTER:
                    handleEnter();
                    event.consume();
                    break;
            }
        });
        
        // Add a listener for cursor position changes
        editorArea.caretPositionProperty().addListener((obs, oldPos, newPos) -> {
            // Only send cursor updates when user moves the cursor manually
            // Don't send cursor updates when the cursor position changes due to editing
            if (!isEditor || isUpdatingText.get() || isSendingCursor.get()) {
                return;
            }
            
            // Throttle cursor position updates to reduce network traffic
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastCursorSendTime > CURSOR_THROTTLE_MS) {
                lastCursorSendTime = currentTime;
                
                // Set flag to prevent recursive cursor updates
                isSendingCursor.set(true);
                try {
                    // Send cursor position to network
                    networkClient.sendCursorMove(newPos.intValue());
                } finally {
                    isSendingCursor.set(false);
                }
            }
        });
    }
    
    private void setupBatchTimer() {
        batchTimer = new java.util.Timer(true);
        batchTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                Platform.runLater(() -> processBatchInserts());
            }
        }, 100, 100); // Process batch every 100ms
    }
    
    private void addToBatchInsert(int position, char c) {
        // If this is first char in batch or not consecutive position
        if (pendingInsertBasePosition == -1 || position != pendingInsertBasePosition + pendingInserts.length()) {
            // Process any existing batch first
            processBatchInserts();
            
            // Start new batch
            pendingInsertBasePosition = position;
            pendingInserts.append(c);
        } else {
            // Add to existing batch
            pendingInserts.append(c);
        }
        
        // Update UI immediately for responsiveness
        String text = editorArea.getText();
        int insertPos = Math.min(position, text.length());
        text = text.substring(0, insertPos) + c + text.substring(insertPos);
        
        // Avoid triggering cursor update events
        isUpdatingText.set(true);
        try {
            editorArea.setText(text);
            editorArea.positionCaret(position + 1);
        } finally {
            isUpdatingText.set(false);
        }
    }
    
    private void processBatchInserts() {
        if (pendingInserts.length() == 0) {
            return;
        }
        
        int startPosition = pendingInsertBasePosition;
        String textToInsert = pendingInserts.toString();
        
        // Reset batch
        pendingInserts.setLength(0);
        pendingInsertBasePosition = -1;
        
        // Process each character in the batch
        for (int i = 0; i < textToInsert.length(); i++) {
            char c = textToInsert.charAt(i);
            
            // Insert the character in the CRDT document
            CRDTCharacter character = document.localInsert(startPosition + i, c);
            
            // Send the insertion to the network
            networkClient.sendInsert(character);
        }
        
        // Send cursor position to network once at end of batch
        networkClient.sendCursorMove(startPosition + textToInsert.length());
    }
    
    private void handleRemoteOperation(Operation operation) {
        switch (operation.getType()) {
            case INSERT:
                CRDTCharacter character = operation.getCharacter();
                document.remoteInsert(character);
                break;
            case DELETE:
                Position position = operation.getPosition();
                document.remoteDelete(position);
                break;
            case CURSOR_MOVE:
                updateRemoteCursor(operation.getUserId(), operation.getCursorPosition());
                break;
            case DOCUMENT_SYNC:
                handleDocumentSync(operation.getDocumentContent());
                break;
            case GET_DOCUMENT_LENGTH:
                // This is a request to get the current document length (for sync confirmation)
                // We just need to set the document length on the operation object
                operation.setDocumentLength(document != null ? document.getText().length() : 0);
                break;
            case REQUEST_DOCUMENT_RESYNC:
                // Force resend the current document content to the server
                if (document != null && document.getText().length() > 0) {
                    networkClient.sendDocumentUpdate(document.getText());
                }
                break;
            case PRESENCE:
                // This is handled by the presence listener
                break;
        }
        
        // Only update the text if it's an INSERT or DELETE operation
        if (operation.getType() == Operation.Type.INSERT || operation.getType() == Operation.Type.DELETE) {
            // Update the text area with the document's current text
            updateEditorText(document.getText());
            
            // Also update the server with the complete document text occasionally
            if (Math.random() < 0.1) { // 10% chance to send document update
                networkClient.sendDocumentUpdate(document.getText());
            }
        }
    }
    
    private void handleDocumentSync(String content) {
        if (content == null || isUpdatingText.get()) {
            return;
        }
        
        try {
            // Reset the document
            document = new CRDTDocument(userId);
            
            // Reset the UI first to show the sync is happening
            updateEditorText("");
            
            // Insert each character
            if (!content.isEmpty()) {
                // Insert all characters at once rather than one by one
                for (int i = 0; i < content.length(); i++) {
                    document.localInsert(i, content.charAt(i));
                }
            
                // Update the UI
                updateEditorText(content);
                
                // Log successful sync
                System.out.println("Document synchronized with " + content.length() + " characters");
                updateStatus("Document synchronized successfully");
            } else {
                updateStatus("Synchronized empty document");
            }
        } catch (Exception e) {
            System.err.println("Error during document sync: " + e.getMessage());
            e.printStackTrace();
            updateStatus("Error during document sync: " + e.getMessage());
        }
    }
    
    private void updateEditorText(String newText) {
        if (isUpdatingText.get()) {
            return;
        }
        
        isUpdatingText.set(true);
        try {
            // Save current selection/caret
            int caretPosition = editorArea.getCaretPosition();
            
            // Update text
            editorArea.setText(newText);
            
            // Restore selection/caret if possible
            if (caretPosition > newText.length()) {
                caretPosition = newText.length();
            }
            editorArea.positionCaret(caretPosition);
        } finally {
            isUpdatingText.set(false);
        }
    }
    
    private void updateCursorMarkers(List<String> userIds) {
        // Create a set of all existing markers
        Set<String> existingMarkers = new HashSet<>(cursorMarkers.keySet());
        
        // Add new markers for new users
        for (String userId : userIds) {
            if (!userId.equals(this.userId) && !cursorMarkers.containsKey(userId)) {
                // Choose a color for the user (based on user ID hash)
                Color color = cursorColors[Math.abs(userId.hashCode()) % cursorColors.length];
                
                // Get the username for display
                String displayName = userMap.getOrDefault(userId, "User " + (userId.length() > 6 ? userId.substring(0, 6) : userId));
                
                // Create a new cursor marker with username instead of ID
                CursorMarker marker = new CursorMarker(editorContainer, displayName, color, editorArea);
                cursorMarkers.put(userId, marker);
            }
            
            // Remove from the set of existing markers to keep track of removed users
            existingMarkers.remove(userId);
        }
        
        // Remove markers for users who have left
        for (String userId : existingMarkers) {
            CursorMarker marker = cursorMarkers.get(userId);
            if (marker != null) {
                marker.remove();
                marker.dispose();
                cursorMarkers.remove(userId);
            }
        }
    }
    
    private void updateRemoteCursor(String userId, int position) {
        if (userId.equals(this.userId)) {
            // Don't update our own cursor
            return;
        }
        
        Platform.runLater(() -> {
            try {
                CursorMarker marker = cursorMarkers.get(userId);
                if (marker != null) {
                    if (position >= 0) {
                        marker.updatePosition(position);
                    } else {
                        // Position -1 indicates cursor removal
                        marker.remove();
                        marker.dispose();
                        cursorMarkers.remove(userId);
                    }
                }
            } catch (Exception e) {
                System.err.println("Error updating cursor for user " + userId + ": " + e.getMessage());
            }
        });
    }
    
    private void handleBackspace() {
        int caretPosition = editorArea.getCaretPosition();
        if (caretPosition > 0) {
            CRDTCharacter deletedChar = document.localDelete(caretPosition - 1);
            if (deletedChar != null) {
                networkClient.sendDelete(deletedChar.getPosition());
                
                // Update the text area with the document's current text
                updateEditorText(document.getText());
            }
        }
    }
    
    private void handleDelete() {
        int caretPosition = editorArea.getCaretPosition();
        if (caretPosition < editorArea.getText().length()) {
            CRDTCharacter deletedChar = document.localDelete(caretPosition);
            if (deletedChar != null) {
                networkClient.sendDelete(deletedChar.getPosition());
                
                // Update the text area with the document's current text
                updateEditorText(document.getText());
            }
        }
    }
    
    private void handleEnter() {
        // Process any pending batch inserts first
        processBatchInserts();
        
        // Get the current caret position
        int caretPosition = editorArea.getCaretPosition();
        
        // First update the UI immediately for responsiveness
        String text = editorArea.getText();
        int insertPos = Math.min(caretPosition, text.length());
        text = text.substring(0, insertPos) + "\n" + text.substring(insertPos);
        
        // Avoid triggering cursor update events during UI update
        isUpdatingText.set(true);
        try {
            editorArea.setText(text);
            editorArea.positionCaret(caretPosition + 1);
        } finally {
            isUpdatingText.set(false);
        }
        
        // Insert a newline character in the CRDT document
        CRDTCharacter character = document.localInsert(caretPosition, '\n');
        
        // Send the insertion to the network
        networkClient.sendInsert(character);
        
        // Send cursor position to network
        networkClient.sendCursorMove(caretPosition + 1);
    }
    
    @FXML
    private void handleImportFile(ActionEvent event) {
        if (!isEditor) {
            updateStatus("Viewers cannot import files");
            return;
        }
        
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Import Text File");
        fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Text Files", "*.txt")
        );
        
        File selectedFile = fileChooser.showOpenDialog(editorArea.getScene().getWindow());
        if (selectedFile != null) {
            try {
                String content = new String(Files.readAllBytes(Paths.get(selectedFile.getPath())));
                
                // Clear the current document
                editorArea.clear();
                
                // Insert each character of the file into the CRDT document
                for (int i = 0; i < content.length(); i++) {
                    CRDTCharacter character = document.localInsert(i, content.charAt(i));
                    networkClient.sendInsert(character);
                }
                
                // Update the text area with the document's current text
                updateEditorText(document.getText());
                
                updateStatus("File imported: " + selectedFile.getName());
            } catch (IOException e) {
                updateStatus("Error importing file: " + e.getMessage());
            }
        }
    }
    
    @FXML
    private void handleExportFile(ActionEvent event) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Document");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text Files", "*.txt"));
        
        // Set initial file name to document title if available
        if (documentTitle != null) {
            fileChooser.setInitialFileName(documentTitle + ".txt");
        }
        
        // Show save dialog
        File file = fileChooser.showSaveDialog(editorArea.getScene().getWindow());
        
        if (file != null) {
            try {
                Files.write(Paths.get(file.getPath()), document.getText().getBytes());
                updateStatus("Document exported to " + file.getName());
            } catch (IOException e) {
                updateStatus("Error exporting document: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    
    @FXML
    private void handleUndo(ActionEvent event) {
        if (!isEditor) {
            updateStatus("Viewers cannot undo changes");
            return;
        }
        
        boolean undone = document.undo();
        if (undone) {
            // Update the text area with the document's current text
            updateEditorText(document.getText());
            updateStatus("Undo operation");
        } else {
            updateStatus("Nothing to undo");
        }
    }
    
    @FXML
    private void handleRedo(ActionEvent event) {
        if (!isEditor) {
            updateStatus("Viewers cannot redo changes");
            return;
        }
        
        boolean redone = document.redo();
        if (redone) {
            // Update the text area with the document's current text
            updateEditorText(document.getText());
            updateStatus("Redo operation");
        } else {
            updateStatus("Nothing to redo");
        }
    }
    
    @FXML
    private void handleGenerateCodes(ActionEvent event) {
        if (!isEditor) {
            updateStatus("Viewers cannot generate codes");
            return;
        }
        
        networkClient.requestCodes();
    }
    
    @FXML
    private void handleJoinSession(ActionEvent event) {
        // Show the join session dialog
        JoinSessionDialog dialog = new JoinSessionDialog();
        dialog.showAndWait().ifPresent(result -> {
            String code = result.getKey();
            boolean isEditorRole = result.getValue();
            
            try {
                // Update our editor status
                isEditor = isEditorRole;
                
                // Update UI based on role
                editorArea.setEditable(isEditor);
                
                // Clear the editor before joining to prepare for document sync
                processBatchInserts(); // Process any pending operations
                updateEditorText(""); // Clear editor
                document = new CRDTDocument(userId); // Reset document
                
                // Remove all cursor markers
                for (CursorMarker marker : new ArrayList<>(cursorMarkers.values())) {
                    marker.remove();
                    marker.dispose();
                }
                cursorMarkers.clear();
                
                // Join the session
                networkClient.joinSession(code, isEditor);
                
                if (isEditor) {
                    updateStatus("Joined session as editor - waiting for content sync...");
                } else {
                    updateStatus("Joined session as viewer - waiting for content sync...");
                    // Hide the sharing codes for viewers
                    editorCodeField.setText("");
                    viewerCodeField.setText("");
                }
                
                // Force a sync request after a short delay
                new Thread(() -> {
                    try {
                        Thread.sleep(500);
                        
                        // Tell the server we need content by sending a special sync request
                        if (networkClient != null) {
                            Platform.runLater(() -> {
                                updateStatus("Requesting document sync...");
                                Operation requestResyncOperation = 
                                    new Operation(Operation.Type.REQUEST_DOCUMENT_RESYNC, null, null, userId, -1);
                                handleRemoteOperation(requestResyncOperation);
                            });
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();
            } catch (Exception e) {
                updateStatus("Error joining session: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
    
    @FXML
    private void handleCopyEditorCode(ActionEvent event) {
        if (!isEditor) {
            updateStatus("Viewers cannot access codes");
            return;
        }
        
        String code = editorCodeField.getText();
        if (code != null && !code.isEmpty()) {
            copyToSystemClipboard(code);
            updateStatus("Editor code copied to clipboard");
        }
    }
    
    @FXML
    private void handleCopyViewerCode(ActionEvent event) {
        if (!isEditor) {
            updateStatus("Viewers cannot access codes");
            return;
        }
        
        String code = viewerCodeField.getText();
        if (code != null && !code.isEmpty()) {
            copyToSystemClipboard(code);
            updateStatus("Viewer code copied to clipboard");
        }
    }
    
    @FXML
    private void handleExit(ActionEvent event) {
        // Process any pending batches
        processBatchInserts();
        
        // Save document before exiting
        saveDocument();
        
        // Clean up resources
        if (autoSaveTimer != null) {
            autoSaveTimer.cancel();
        }
        
        if (batchTimer != null) {
            batchTimer.cancel();
        }
        
        // Dispose of cursor markers
        for (CursorMarker marker : cursorMarkers.values()) {
            marker.dispose();
        }
        
        // Disconnect from network
        if (networkClient != null) {
            networkClient.disconnect();
        }
        
        // Close the window
        ((Stage) editorArea.getScene().getWindow()).close();
    }
    
    /**
     * Copies text to the system clipboard.
     * @param text The text to copy.
     */
    private void copyToSystemClipboard(String text) {
        try {
            // Use JavaFX Clipboard
            final javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
            final javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(text);
            clipboard.setContent(content);
        } catch (Exception e) {
            updateStatus("Could not copy to clipboard: " + e.getMessage());
        }
    }
    
    private void updateStatus(String message) {
        statusLabel.setText(message);
    }
    
    @FXML
    private void handleOpenDocuments(ActionEvent event) {
        try {
            // Process any pending inserts before changing documents
            processBatchInserts();
            
            // Save current document if needed
            saveDocument();
            
            // Leave current session if in one
            leaveCurrentSession();
            
            // Show document selection dialog
            Pair<String, String> documentInfo = DocumentSelectionDialog.showDocumentSelectionDialog(userId);
            if (documentInfo != null) {
                documentId = documentInfo.getKey();
                documentTitle = documentInfo.getValue();
                
                // Set window title
                Stage stage = (Stage) editorArea.getScene().getWindow();
                stage.setTitle("Collaborative Text Editor - " + documentTitle + " (" + username + ")");
                
                // Load the newly selected document
                loadDocumentContent();
                
                updateStatus("Opened document: " + documentTitle);
            }
        } catch (Exception e) {
            updateStatus("Error opening document: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void loadDocumentContent() {
        try {
            // First clear any existing content and process any pending operations
            processBatchInserts();
            document = new CRDTDocument(userId);
            updateEditorText("");
            
            updateStatus("Loading document: " + documentTitle + "...");
            
            org.bson.Document doc = DatabaseService.getInstance().getDocument(documentId);
            if (doc != null) {
                String content = doc.getString("content");
                
                // Check for existing session
                String existingSessionCode = null;
                try {
                    existingSessionCode = doc.getString("sessionCode");
                } catch (Exception e) {
                    System.err.println("Error reading session code: " + e.getMessage());
                }
                
                // If this document has an existing session, join that session
                if (existingSessionCode != null && !existingSessionCode.isEmpty()) {
                    try {
                        updateStatus("Joining existing session: " + existingSessionCode);
                        
                        // Join the existing session as an editor
                        boolean isEditorRole = true;
                        networkClient.joinSession(existingSessionCode, isEditorRole);
                        isEditor = isEditorRole;
                        
                        // Update the editor code field
                        editorCodeField.setText(existingSessionCode);
                        
                        // Wait for the session join to complete and content sync
                        try {
                            Thread.sleep(500);
                            
                            // Force a document sync request after joining
                            Operation requestResyncOperation = 
                                new Operation(Operation.Type.REQUEST_DOCUMENT_RESYNC, null, null, userId, -1);
                            handleRemoteOperation(requestResyncOperation);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        
                        updateStatus("Joined existing document session: " + existingSessionCode);
                        return; // Content will be synced through the session
                    } catch (Exception e) {
                        System.err.println("Error joining existing session: " + e.getMessage());
                        // If session join fails, fall back to loading content directly
                    }
                }
                
                // Otherwise proceed with normal content loading
                if (content != null && !content.isEmpty()) {
                    updateStatus("Loading document content...");
                    
                    // Insert the content character by character
                    for (int i = 0; i < content.length(); i++) {
                        document.localInsert(i, content.charAt(i));
                    }
                    
                    // Update the text area
                    updateEditorText(content);
                    
                    // Explicitly send document update to sync with server
                    networkClient.sendDocumentUpdate(content);
                    
                    updateStatus("Loaded document: " + documentTitle);
                } else {
                    // Empty document
                    updateEditorText("");
                    updateStatus("Loaded empty document: " + documentTitle);
                }
            } else {
                // No document found
                updateStatus("Could not find document with ID: " + documentId);
            }
        } catch (Exception e) {
            updateStatus("Error loading document: " + e.getMessage());
            e.printStackTrace();
            
            // Reset to empty document in case of error
            try {
                document = new CRDTDocument(userId);
                updateEditorText("");
            } catch (Exception ex) {
                System.err.println("Failed to reset document: " + ex.getMessage());
            }
        }
    }
    
    /**
     * Leaves the current editing session if any.
     */
    private void leaveCurrentSession() {
        try {
            // Clear sharing codes
            editorCodeField.setText("");
            viewerCodeField.setText("");
            
            // Remove all cursor markers
            for (CursorMarker marker : cursorMarkers.values()) {
                marker.remove();
                marker.dispose();
            }
            cursorMarkers.clear();
            
            // Clear users list
            users.clear();
            
            // Reset to editor mode
            isEditor = true;
            editorArea.setEditable(true);
            
            // Send leave session message (if supported by the server)
            // For now, just disconnect and reconnect
            if (networkClient != null) {
                networkClient.disconnect();
                
                // Wait a bit before reconnecting
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                networkClient = new NetworkClient(userId);
                setupNetworkListeners();
                networkClient.connect();
            }
        } catch (Exception e) {
            System.err.println("Error leaving session: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void setupAutoSaveTimer() {
        if (autoSaveTimer != null) {
            autoSaveTimer.cancel();
        }
        
        autoSaveTimer = new java.util.Timer(true);
        autoSaveTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                saveDocument();
            }
        }, 30000, 30000); // 30 seconds
    }
    
    private void saveDocument() {
        // Process any pending inserts before saving
        processBatchInserts();
        
        if (documentId != null) {
            String content = document.getText();
            
            // Get the session code if we're in one
            String sessionCode = "";
            if (editorCodeField.getText() != null && !editorCodeField.getText().isEmpty()) {
                sessionCode = editorCodeField.getText();
            }
            
            boolean saved = DatabaseService.getInstance().updateDocumentWithSession(documentId, content, sessionCode);
            if (saved) {
                // Also update the server with the complete document
                networkClient.sendDocumentUpdate(content);
                
                Platform.runLater(() -> updateStatus("Document saved"));
            } else {
                Platform.runLater(() -> updateStatus("Failed to save document"));
            }
        }
    }
    
    /**
     * Handles the save document action event from the UI.
     * @param event the action event
     */
    @FXML
    private void handleSaveDocument(ActionEvent event) {
        try {
            // Process any pending batch operations first
            processBatchInserts();
            
            // Save the document
            saveDocument();
            
            // Force a document update to ensure it's synced with the server
            if (document != null) {
                String content = document.getText();
                networkClient.sendDocumentUpdate(content);
            }
            
            updateStatus("Document saved successfully");
        } catch (Exception e) {
            updateStatus("Error saving document: " + e.getMessage());
            e.printStackTrace();
        }
    }
} 