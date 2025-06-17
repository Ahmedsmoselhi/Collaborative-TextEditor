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
import java.util.Optional;
import java.util.function.Supplier;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

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
    
    @FXML
    private Label wordCountLabel;
    
    private ObservableList<String> users = FXCollections.observableArrayList();
    
    private CRDTDocument document;
    
    private NetworkClient networkClient;
    
    private boolean isEditor = true;
    
    private Map<String, CursorMarker> cursorMarkers = new HashMap<>();
    
    private String userId = null;
    
    private String username = null;
    
    private String documentId = null;
    private String documentTitle = null;
    
    private java.util.Timer autoSaveTimer;
    
    private AtomicBoolean isUpdatingText = new AtomicBoolean(false);
    private AtomicBoolean isSendingCursor = new AtomicBoolean(false);
    private long lastCursorSendTime = 0;
    private static final long CURSOR_THROTTLE_MS = 100;
    
    private StringBuilder pendingInserts = new StringBuilder();
    private int pendingInsertBasePosition = -1;
    private java.util.Timer batchTimer;
    
    private final Color[] cursorColors = {
        Color.web("#4285f4"),
        Color.web("#ea4335"),
        Color.web("#fbbc05"),
        Color.web("#34a853")
    };
    
    private Map<String, String> userMap = new HashMap<>();
    
    private boolean initialized = false;
    
    public void setUserId(String userId) {
        this.userId = userId;
        System.out.println("Initialized with userId: " + userId);
        
        if (networkClient == null) {
            networkClient = new NetworkClient(userId);
            setupNetworkListeners();
        } else {
            networkClient.setUserId(userId);
        }
    }
    
    public void setUsername(String username) {
        this.username = username;
        System.out.println("Initialized with username: " + username);
        
        if (userMap == null) {
            userMap = new HashMap<>();
        }
            userMap.put(userId, username);
        
        if (networkClient != null) {
            networkClient.setUsername(username);
            
            if (networkClient.isConnected()) {
                sendUsernameUpdate();
            }
        }
        
        Platform.runLater(() -> {
            users.clear();
            users.add(username + " (you)");
        });
    }
    
    private void sendUsernameUpdate() {
        if (networkClient != null && networkClient.isConnected() && username != null) {
            JsonObject message = new JsonObject();
            message.addProperty("type", "username_update");
            message.addProperty("userId", userId);
            message.addProperty("username", username);
            networkClient.getWebSocketClient().send(new Gson().toJson(message));
            System.out.println("Sent username update to server: " + username);
        }
    }
    
    public void setDocumentInfo(String documentId, String documentTitle) {
        this.documentId = documentId;
        this.documentTitle = documentTitle;
        
        if (initialized && documentId != null) {
            try {
                Thread.sleep(200);
                loadDocumentContent();
            } catch (Exception e) {
                updateStatus("Error loading document: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    
    public void setJoinSessionInfo(String sessionCode, boolean isEditorRole) {
        if (sessionCode == null || sessionCode.isEmpty()) {
            updateStatus("Invalid session code");
            return;
        }
        
        System.out.println("Setting join session info: Code=" + sessionCode + ", Role=" + (isEditorRole ? "EDITOR" : "VIEWER"));
        
        if (!initialized) {
            new Thread(() -> {
                while (!initialized) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                
                Platform.runLater(() -> joinExistingSession(sessionCode, isEditorRole));
            }).start();
        } else {
            joinExistingSession(sessionCode, isEditorRole);
        }
    }
    
    @FXML
    public void initialize() {
        if (initialized) {
            return;
        }
        
        try {
            String siteId = (userId != null) ? userId : UUID.randomUUID().toString();
            document = new CRDTDocument(siteId);
            
            networkClient = new NetworkClient(siteId, username);
            
            System.out.println("Initialized with username: " + username + ", userId: " + siteId);
            this.userId = siteId;
            
            networkClient.addOperationListener(this::handleRemoteOperation);
            
            networkClient.addPresenceListener(this::updateUserList);
            
            setupNetworkListeners();
            setupEditorListeners();
            
            usersListView.setItems(users);
            
            if (wordCountLabel == null) {
                wordCountLabel = new Label("Words: 0 | Characters: 0 | Lines: 0");
                wordCountLabel.setStyle("-fx-text-fill: #555; -fx-font-size: 12px; -fx-padding: 5px; -fx-background-color: #f5f5f5; -fx-border-color: #ddd; -fx-border-width: 1px; -fx-border-radius: 3px;");
                
                if (editorContainer != null && !editorContainer.getChildren().contains(wordCountLabel)) {
                    editorContainer.getChildren().add(wordCountLabel);
                    AnchorPane.setBottomAnchor(wordCountLabel, 10.0);
                    AnchorPane.setRightAnchor(wordCountLabel, 15.0);
                }
            }
            
            boolean connected = networkClient.connect();
            if (connected) {
                updateStatus("Connected to network as " + (username != null ? username : siteId));
                
                setupAutoSaveTimer();
                
                setupBatchTimer();
                
                initialized = true;
                
                if (documentId != null) {
                    System.out.println("Document ID already set, loading content: " + documentId);
                    new Thread(() -> {
                        try {
                            Thread.sleep(300);
                            Platform.runLater(this::loadDocumentContent);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                }
            } else {
                updateStatus("Failed to connect to network");
            }
            
            if (!editorContainer.getChildren().contains(editorArea)) {
                editorContainer.getChildren().add(editorArea);
                AnchorPane.setTopAnchor(editorArea, 0.0);
                AnchorPane.setRightAnchor(editorArea, 0.0);
                AnchorPane.setBottomAnchor(editorArea, 0.0);
                AnchorPane.setLeftAnchor(editorArea, 0.0);
            }
            
            updateWordCount();
        } catch (Exception e) {
            updateStatus("Error initializing editor: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void setupNetworkListeners() {
        networkClient.addOperationListener(this::handleRemoteOperation);
        
        networkClient.addPresenceListener(this::updateUserList);
        
        networkClient.addErrorListener(this::updateStatus);
        
        networkClient.addCodeListener(codes -> {
            Platform.runLater(() -> {
                System.out.println("=====================================");
                System.out.println("RECEIVED SESSION CODES:");
                System.out.println("Editor code: " + codes.getEditorCode());
                System.out.println("Viewer code: " + codes.getViewerCode());
                System.out.println("Current user role: " + (isEditor ? "EDITOR" : "VIEWER"));
                System.out.println("=====================================");
                
                if (isEditor) {
                    editorCodeField.setText(codes.getEditorCode());
                    viewerCodeField.setText(codes.getViewerCode());
                } else {
                    editorCodeField.setText("");
                    viewerCodeField.setText(codes.getViewerCode());
                }
                
                if (documentId != null) {
                    boolean saved = DatabaseService.getInstance().updateDocumentWithSession(
                        documentId, document.getText(), codes.getEditorCode(), codes.getViewerCode());
                    
                    if (saved) {
                        System.out.println("Session codes saved with document ID: " + documentId);
                    } else {
                        System.err.println("Failed to save session codes with document ID: " + documentId);
                    }
                }
            });
        });
    }
    
    private void setupEditorListeners() {
        editorArea.addEventFilter(KeyEvent.KEY_TYPED, event -> {
            if (!isEditor) {
                event.consume();
                return;
            }
            
            String ch = event.getCharacter();
            if (ch == null || ch.isEmpty()) {
                return;
            }
            
            char c = ch.charAt(0);
            
            if (c < 32 && c != '\t') {
                return;
            }
            
            if (c >= 127 && c <= 159) {
                event.consume();
                return;
            }
            
            int caretPosition = editorArea.getCaretPosition();
            
            addToBatchInsert(caretPosition, c);
            
            event.consume();
        });
        
        editorArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!isEditor) {
                event.consume();
                return;
            }
            
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
        
        editorArea.caretPositionProperty().addListener((obs, oldPos, newPos) -> {
            if (!isEditor || isUpdatingText.get() || isSendingCursor.get()) {
                return;
            }
            
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastCursorSendTime > CURSOR_THROTTLE_MS) {
                lastCursorSendTime = currentTime;
                
                isSendingCursor.set(true);
                try {
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
        }, 100, 100);
    }
    
    private void addToBatchInsert(int position, char c) {
        if (pendingInsertBasePosition == -1 || position != pendingInsertBasePosition + pendingInserts.length()) {
            processBatchInserts();
            
            pendingInsertBasePosition = position;
            pendingInserts.append(c);
        } else {
            pendingInserts.append(c);
        }
        
        String text = editorArea.getText();
        int insertPos = Math.min(position, text.length());
        text = text.substring(0, insertPos) + c + text.substring(insertPos);
        
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
        
        pendingInserts.setLength(0);
        pendingInsertBasePosition = -1;
        
        for (int i = 0; i < textToInsert.length(); i++) {
            char c = textToInsert.charAt(i);
            
            CRDTCharacter character = document.localInsert(startPosition + i, c);
            
            networkClient.sendInsert(character);
        }
        
        networkClient.sendCursorMove(startPosition + textToInsert.length());
        
        updateWordCount();
    }
    
    private void handleRemoteOperation(Operation operation) {
        if (operation.getType() == Operation.Type.INSERT || 
            operation.getType() == Operation.Type.DELETE || 
            operation.getType() == Operation.Type.DOCUMENT_SYNC) {
            
            System.out.println("Processing remote operation: " + operation.getType());
            
            try {
            synchronized (document) {
                switch (operation.getType()) {
                    case INSERT:
                        CRDTCharacter character = operation.getCharacter();
                            
                            if (character.getAuthorId().equals(userId)) {
                                Platform.runLater(() -> updateEditorText(document.getText()));
                                return;
                            }
                            
                        document.remoteInsert(character);
                            System.out.println("Inserted character at position: " + character.getPosition().toString());
                        break;
                            
                    case DELETE:
                        Position position = operation.getPosition();
                            
                            if (operation.getUserId().equals(userId)) {
                                Platform.runLater(() -> updateEditorText(document.getText()));
                                return;
                            }
                            
                        document.remoteDelete(position);
                            System.out.println("Deleted character at position: " + position.toString());
                        break;
                            
                    case DOCUMENT_SYNC:
                            System.out.println("Document sync received with " + 
                                (operation.getDocumentContent() != null ? operation.getDocumentContent().length() : 0) + 
                                " characters");
                        handleDocumentSync(operation.getDocumentContent());
                        return;
                            
                    default:
                        break;
                }
                
                final String documentText = document.getText();
                    
                    Platform.runLater(() -> {
                        try {
                            int caretPosition = editorArea.getCaretPosition();
                            
                            updateEditorText(documentText);
                            
                            if (caretPosition >= 0 && caretPosition <= documentText.length()) {
                                editorArea.positionCaret(caretPosition);
                            }
                            
                            if (operation.getType() == Operation.Type.INSERT || operation.getType() == Operation.Type.DELETE) {
                                networkClient.sendCursorMove(editorArea.getCaretPosition());
                            }
                        } catch (Exception e) {
                            System.err.println("Error updating UI after remote operation: " + e.getMessage());
                            e.printStackTrace();
                            
                            handlePossibleCorruption();
                        }
                    });
                
                    if (Math.random() < 0.05) {
                    networkClient.sendDocumentUpdate(documentText);
                }
            }
            } catch (Exception e) {
                System.err.println("Error processing remote operation: " + e.getMessage());
                e.printStackTrace();
                
                handlePossibleCorruption();
            }
        } else if (operation.getType() == Operation.Type.CURSOR_MOVE) {
            updateRemoteCursor(operation.getUserId(), operation.getCursorPosition());
        } else if (operation.getType() == Operation.Type.GET_DOCUMENT_LENGTH) {
                    if (document != null) {
                operation.setDocumentLength(document.getText().length());
            } else {
                operation.setDocumentLength(0);
            }
        } else if (operation.getType() == Operation.Type.REQUEST_DOCUMENT_RESYNC) {
            Platform.runLater(() -> {
                if (networkClient != null) {
                    String currentContent = document.getText();
                    networkClient.sendDocumentUpdate(currentContent);
                    System.out.println("Sent document resync with " + currentContent.length() + " characters");
                }
            });
        }
    }
    
    private void handleDocumentSync(String content) {
        try {
            if (content == null) {
                System.err.println("Received null content in document sync");
            return;
        }
        
            System.out.println("Received document sync with " + content.length() + " characters");
            
            final int currentCaretPosition = editorArea.getCaretPosition();
            final String currentText = document.getText();
            
            if (currentText != null && currentText.length() > 0 && content.length() > 0) {
                double lengthRatio = (double) Math.max(currentText.length(), content.length()) / 
                                    Math.min(Math.max(1, currentText.length()), content.length());
                
                if (lengthRatio > 5.0) {
                    System.err.println("Extreme document length mismatch: current=" + currentText.length() + 
                                       ", sync=" + content.length() + ", ratio=" + lengthRatio);
                    handlePossibleCorruption();
                }
            }
            
            if (content.equals(currentText)) {
                System.out.println("Document sync content matches current content, no update needed");
                
                if (networkClient != null) {
                    JsonObject confirmMsg = new JsonObject();
                    confirmMsg.addProperty("type", "sync_confirmation");
                    confirmMsg.addProperty("receivedLength", content.length());
                    confirmMsg.addProperty("userId", userId);
                    
                    networkClient.getWebSocketClient().send(new Gson().toJson(confirmMsg));
                    System.out.println("Sent sync confirmation for " + content.length() + " characters");
                }
                return;
            }
            
            synchronized (document) {
                CRDTDocument newDocument = new CRDTDocument(userId);
                
                    for (int i = 0; i < content.length(); i++) {
                    newDocument.localInsert(i, content.charAt(i));
                }
                
                document = newDocument;
                
                Platform.runLater(() -> {
                    int newPosition = Math.min(currentCaretPosition, content.length());
                
                    updateEditorText(content);
                    
                    try {
                        if (newPosition >= 0) {
                            editorArea.positionCaret(newPosition);
                            
                            if (newPosition != currentCaretPosition) {
                                networkClient.sendCursorMove(newPosition);
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("Error restoring cursor position: " + e.getMessage());
                    }
                    
                    updateWordCount();
                    
                    refreshCursorMarkers();
                });
                
                if (networkClient != null) {
                    JsonObject confirmMsg = new JsonObject();
                    confirmMsg.addProperty("type", "sync_confirmation");
                    confirmMsg.addProperty("receivedLength", content.length());
                    confirmMsg.addProperty("userId", userId);
                    
                    networkClient.getWebSocketClient().send(new Gson().toJson(confirmMsg));
                    System.out.println("Sent sync confirmation for " + content.length() + " characters");
                }
            }
        } catch (Exception e) {
            System.err.println("Error handling document sync: " + e.getMessage());
            e.printStackTrace();
            
            handlePossibleCorruption();
        }
    }
    
    private void updateWordCount() {
        if (document == null || wordCountLabel == null) {
            return;
        }
        
        String text = document.getText();
        int wordCount = 0;
        int charCount = 0;
        int lineCount = 0;
        
        if (text != null) {
            charCount = text.length();
            
            if (!text.isEmpty()) {
                lineCount = 1;
                for (char c : text.toCharArray()) {
                    if (c == '\n') {
                        lineCount++;
                    }
                }
                
                String[] words = text.split("\\s+");
                for (String word : words) {
                    if (!word.trim().isEmpty()) {
                        wordCount++;
                    }
                }
            } else {
                lineCount = 1;
            }
        }
        
        final int finalWordCount = wordCount;
        final int finalCharCount = charCount;
        final int finalLineCount = lineCount;
        
        Platform.runLater(() -> {
            wordCountLabel.setText(String.format("Words: %d | Characters: %d | Lines: %d", 
                finalWordCount, finalCharCount, finalLineCount));
        });
    }
    
    private void updateEditorText(String newText) {
        if (isUpdatingText.get()) {
            return;
        }
        
        isUpdatingText.set(true);
        try {
            int caretPosition = editorArea.getCaretPosition();
            
            editorArea.setText(newText);
            
            if (caretPosition > newText.length()) {
                caretPosition = newText.length();
            }
            editorArea.positionCaret(caretPosition);
            
            updateWordCount();
        } finally {
            isUpdatingText.set(false);
        }
    }
    
    private void updateUserList(Map<String, String> userMap) {
        if (userMap == null || userMap.isEmpty()) {
            return;
        }
        
        System.out.println("Received user update with " + userMap.size() + " users: " + userMap.keySet());
        
        Map<String, String> cleanUserMap = new HashMap<>();
        
        if (userId != null && username != null) {
            cleanUserMap.put(userId, username);
        }
        
        boolean foundNewValidUsers = false;
        
        boolean isSingleUserUpdate = userMap.size() == 1 && !userMap.containsKey(userId);
        
        for (Map.Entry<String, String> entry : userMap.entrySet()) {
            String id = entry.getKey();
            String name = entry.getValue();
            
            if (id.equals(userId)) {
                continue;
            }
            
            if (name == null || name.trim().isEmpty()) {
                System.out.println("Skipping user with empty name: " + id);
                continue;
            }
            
            cleanUserMap.put(id, name);
            
            if (!this.userMap.containsKey(id)) {
                foundNewValidUsers = true;
                System.out.println("Found new user: " + name + " (" + id + ")");
            }
        }
        
        if (isSingleUserUpdate && foundNewValidUsers && this.userMap != null && !this.userMap.isEmpty()) {
            for (Map.Entry<String, String> entry : this.userMap.entrySet()) {
                if (!cleanUserMap.containsKey(entry.getKey())) {
                    cleanUserMap.put(entry.getKey(), entry.getValue());
                }
            }
            System.out.println("Merged single-user update with existing users, now have " + 
                              cleanUserMap.size() + " users");
        }
        
        this.userMap = new HashMap<>(cleanUserMap);
        
        Platform.runLater(() -> {
            try {
                users.clear();
                
                if (userId != null && username != null) {
                    users.add(username + " (you)");
                }
                
                for (Map.Entry<String, String> entry : cleanUserMap.entrySet()) {
                    if (!entry.getKey().equals(userId)) {
                        users.add(entry.getValue());
                    }
                }
                
                boolean hasOtherUsers = users.size() > 1;
                if (hasOtherUsers) {
                    System.out.println("Collaborating with " + (users.size() - 1) + 
                                      " other user" + (users.size() > 2 ? "s" : ""));
                } else {
                    System.out.println("No other users connected");
                }
                
                System.out.println("Updated active users list with " + (cleanUserMap.size() - 1) + 
                                  " real users: " + users);
                
                updateCursorMarkers(new ArrayList<>(cleanUserMap.keySet()));
            } catch (Exception e) {
                System.err.println("Error updating user list: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
    
    private String getUsernameForId(String userId) {
        String username = userMap.get(userId);
        
        if (username == null || username.trim().isEmpty()) {
            return "User-" + userId.substring(0, Math.min(6, userId.length()));
        }
        
        return username;
    }
    
    private void updateCursorMarkers(List<String> userIds) {
        Platform.runLater(() -> {
            Set<String> currentUsers = new HashSet<>(userIds);
            Set<String> markersToRemove = new HashSet<>();
            
            for (String markerId : cursorMarkers.keySet()) {
                if (!currentUsers.contains(markerId)) {
                    markersToRemove.add(markerId);
                }
            }
            
            for (String markerId : markersToRemove) {
                CursorMarker marker = cursorMarkers.remove(markerId);
                cleanupCursorMarker(marker);
            }
            
            int colorIndex = 0;
            for (String userId : userIds) {
                if (!userId.equals(this.userId) && !cursorMarkers.containsKey(userId)) {
                    CursorMarker marker = new CursorMarker(getUsernameForId(userId), cursorColors[colorIndex % cursorColors.length]);
                    cursorMarkers.put(userId, marker);
                    editorContainer.getChildren().add(marker);
                    
                    colorIndex++;
                }
            }
        });
    }
    
    private Bounds getCaretBounds() {
        int caretPosition = editorArea.getCaretPosition();
        
        int lineNo = 0;
        int colNo = 0;
        int pos = 0;
        
        for (CharSequence paragraph : editorArea.getParagraphs()) {
            int paragraphLength = paragraph.length() + 1;
            if (pos + paragraphLength > caretPosition) {
                colNo = caretPosition - pos;
                break;
            }
            pos += paragraphLength;
            lineNo++;
        }
        
        double charWidth = 8.0;
        double lineHeight = 18.0;
        
        double x = colNo * charWidth;
        double y = lineNo * lineHeight;
        
        return new javafx.geometry.BoundingBox(x, y, 1, lineHeight);
    }
    
    private void updateRemoteCursor(String userId, int position) {
        if (position < 0) {
            Platform.runLater(() -> {
                CursorMarker marker = cursorMarkers.get(userId);
                if (marker != null) {
                    cleanupCursorMarker(marker);
                }
            });
            return;
        }

        final int cursorPosition = position;
        Platform.runLater(() -> {
            try {
                CursorMarker marker = cursorMarkers.get(userId);
                if (marker == null) {
                    String username = getUsernameForId(userId);
                    
                    int colorIndex = Math.abs(userId.hashCode() % cursorColors.length);
                    Color color = cursorColors[colorIndex];
                    
                    marker = new CursorMarker(username, color);
                    cursorMarkers.put(userId, marker);
                    editorContainer.getChildren().add(marker);
                    marker.toFront();
                }
        
                if (cursorPosition <= editorArea.getLength()) {
                    String content = editorArea.getText();
                    
                    int adjustedPosition = cursorPosition;
                    if (adjustedPosition > content.length()) {
                        adjustedPosition = content.length();
                    }
                    
                    int lineNo = 0;
                    int colNo = 0;
                    int currentPos = 0;
                    
                    for (String line : content.split("\\R", -1)) {
                        int lineLength = line.length() + 1;
                        
                        if (currentPos + lineLength > adjustedPosition) {
                            colNo = adjustedPosition - currentPos;
                            break;
                        }
                        
                        currentPos += lineLength;
                        lineNo++;
                    }
                    
                    double charWidth = 8.5;
                    double lineHeight = 18.0;
                    
                    double x = colNo * charWidth;
                    double y = lineNo * lineHeight;
                    
                    Bounds bounds = new javafx.geometry.BoundingBox(x, y, 1, lineHeight);
                    marker.updatePosition(bounds);
                    
                    String username = getUsernameForId(userId);
                    marker.setUsername(username);
                }
            } catch (Exception e) {
                System.err.println("Error updating cursor: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
    
    private void handleBackspace() {
        if (!isEditor) {
            return;
        }
        
        try {
            processBatchInserts();
            
            int caretPosition = editorArea.getCaretPosition();
            if (caretPosition > 0) {
                String text = editorArea.getText();
                
                if (text.isEmpty() || caretPosition <= 0) {
                    return;
                }
                
                if (caretPosition > text.length()) {
                    caretPosition = text.length();
                }
                
                System.out.println("Attempting backspace at position: " + caretPosition);
                
                CRDTCharacter deletedChar = document.localDelete(caretPosition - 1);
                
                if (deletedChar != null) {
                    System.out.println("Successfully deleted char: " + deletedChar.getValue() + " at position: " + deletedChar.getPosition());
                    networkClient.sendDelete(deletedChar.getPosition());
                    
                    String newText = text.substring(0, caretPosition - 1) + text.substring(caretPosition);
                    
                    isUpdatingText.set(true);
                    try {
                        editorArea.setText(newText);
                        editorArea.positionCaret(caretPosition - 1);
                    } finally {
                        isUpdatingText.set(false);
                    }
                    
                    if (Math.random() < 0.1) {
                        networkClient.sendDocumentUpdate(document.getText());
                    }
                    
                    updateWordCount();
                } else {
                    System.err.println("Failed to delete character at position " + (caretPosition - 1));
                    String currentDocText = document.getText();
                    updateEditorText(currentDocText);
                    System.err.println("Backspace failed - forced text resync");
                }
            }
        } catch (Exception e) {
            System.err.println("Error handling backspace: " + e.getMessage());
            e.printStackTrace();
            
            try {
                updateEditorText(document.getText());
            } catch (Exception ex) {
                System.err.println("Failed to update UI after error: " + ex.getMessage());
            }
        }
    }
    
    private void handleDelete() {
        if (!isEditor) {
            return;
        }
        
        try {
            processBatchInserts();
            
            int caretPosition = editorArea.getCaretPosition();
            String text = editorArea.getText();
            
            if (text.isEmpty() || caretPosition >= text.length()) {
                return;
            }
            
            System.out.println("Attempting to delete character at position: " + caretPosition);
            
            CRDTCharacter deletedChar = document.localDelete(caretPosition);
            
            if (deletedChar != null) {
                System.out.println("Successfully deleted char: " + deletedChar.getValue() + " at position: " + deletedChar.getPosition());
                networkClient.sendDelete(deletedChar.getPosition());
                
                String newText = text.substring(0, caretPosition) + text.substring(caretPosition + 1);
                
                isUpdatingText.set(true);
                try {
                    editorArea.setText(newText);
                    editorArea.positionCaret(caretPosition);
                } finally {
                    isUpdatingText.set(false);
                }
                
                if (Math.random() < 0.1) {
                    networkClient.sendDocumentUpdate(document.getText());
                }
                
                updateWordCount();
            } else {
                System.err.println("Failed to delete character at position " + caretPosition);
                String currentDocText = document.getText();
                updateEditorText(currentDocText);
                System.err.println("Delete failed - forced text resync");
            }
        } catch (Exception e) {
            System.err.println("Error handling delete: " + e.getMessage());
            e.printStackTrace();
            
            try {
                updateEditorText(document.getText());
            } catch (Exception ex) {
                System.err.println("Failed to update UI after error: " + ex.getMessage());
            }
        }
    }
    
    private void handleEnter() {
        processBatchInserts();
        
        int caretPosition = editorArea.getCaretPosition();
        
        String text = editorArea.getText();
        int insertPos = Math.min(caretPosition, text.length());
        text = text.substring(0, insertPos) + "\n" + text.substring(insertPos);
        
        isUpdatingText.set(true);
        try {
            editorArea.setText(text);
            editorArea.positionCaret(caretPosition + 1);
        } finally {
            isUpdatingText.set(false);
        }
        
        CRDTCharacter character = document.localInsert(caretPosition, '\n');
        
        networkClient.sendInsert(character);
        
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
                
                editorArea.clear();
                
                for (int i = 0; i < content.length(); i++) {
                    CRDTCharacter character = document.localInsert(i, content.charAt(i));
                    networkClient.sendInsert(character);
                }
                
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
        
        if (documentTitle != null) {
            fileChooser.setInitialFileName(documentTitle + ".txt");
        }
        
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
        
        processBatchInserts();
        
        if (document != null) {
            try {
                Operation undoOperation = document.peekUndo();
                if (undoOperation == null) {
                    updateStatus("Nothing to undo");
                    return;
                }

                String oldContent = document.getText();
                
                boolean undone = document.undo();
                if (undone) {
                    String newContent = document.getText();
                    
                    updateEditorText(newContent);
                    
                    if (undoOperation.getType() == Operation.Type.INSERT) {
                        if (networkClient != null && undoOperation.getPosition() != null) {
                            networkClient.sendDelete(undoOperation.getPosition());
                        }
                    } else if (undoOperation.getType() == Operation.Type.DELETE) {
                        if (networkClient != null && undoOperation.getCharacter() != null) {
                            networkClient.sendInsert(undoOperation.getCharacter());
                        }
                    }
                    
                    sendUndoRedoFullDocumentUpdate(newContent, "undo");
                    
                    updateStatus("Undo operation");
                } else {
                    updateStatus("Nothing to undo - or undo failed");
                    
                    String currentContent = document.getText();
                    networkClient.sendDocumentUpdate(currentContent);
                }
            } catch (Exception e) {
                System.err.println("Error performing undo: " + e.getMessage());
                e.printStackTrace();
                updateStatus("Error during undo: " + e.getMessage());
                
                try {
                    String currentContent = document.getText();
                    networkClient.sendDocumentUpdate(currentContent);
                } catch (Exception ex) {
                    System.err.println("Could not recover from undo error: " + ex.getMessage());
                }
            }
        }
    }
    
    @FXML
    private void handleRedo(ActionEvent event) {
        if (!isEditor) {
            updateStatus("Viewers cannot redo changes");
            return;
        }
        
        processBatchInserts();
        
        if (document != null) {
            try {
                Operation redoOperation = document.peekRedo();
                if (redoOperation == null) {
                    updateStatus("Nothing to redo");
                    return;
                }

                String oldContent = document.getText();
                
                boolean redone = document.redo();
                if (redone) {
                    String newContent = document.getText();
                    
                    updateEditorText(newContent);
                    
                    if (redoOperation.getType() == Operation.Type.INSERT) {
                        if (networkClient != null && redoOperation.getCharacter() != null) {
                            networkClient.sendInsert(redoOperation.getCharacter());
                        }
                    } else if (redoOperation.getType() == Operation.Type.DELETE) {
                        if (networkClient != null && redoOperation.getPosition() != null) {
                            networkClient.sendDelete(redoOperation.getPosition());
                        }
                    }
                    
                    sendUndoRedoFullDocumentUpdate(newContent, "redo");
                    
                    updateStatus("Redo operation");
                } else {
                    updateStatus("Nothing to redo - or redo failed");
                    
                    String currentContent = document.getText();
                    networkClient.sendDocumentUpdate(currentContent);
                }
            } catch (Exception e) {
                System.err.println("Error performing redo: " + e.getMessage());
                e.printStackTrace();
                updateStatus("Error during redo: " + e.getMessage());
                
                try {
                    String currentContent = document.getText();
                    networkClient.sendDocumentUpdate(currentContent);
                } catch (Exception ex) {
                    System.err.println("Could not recover from redo error: " + ex.getMessage());
                }
            }
        }
    }
    
    private void sendUndoRedoFullDocumentUpdate(String content, String operationType) {
        if (networkClient != null && networkClient.getWebSocketClient() != null) {
            try {
                JsonObject message = new JsonObject();
                message.addProperty("type", "instant_document_update");
                message.addProperty("userId", userId);
                message.addProperty("username", username);
                message.addProperty("content", content);
                message.addProperty("operation", operationType);
                message.addProperty("highPriority", true);
                message.addProperty("timestamp", System.currentTimeMillis());
                
                networkClient.getWebSocketClient().send(new Gson().toJson(message));
                System.out.println("Sent " + operationType + " full document update with " + content.length() + " chars");
            } catch (Exception e) {
                System.err.println("Error sending " + operationType + " sync: " + e.getMessage());
            }
        }
    }
    
    @FXML
    private void handleGenerateCodes(ActionEvent event) {
        if (!isEditor) {
            updateStatus("Viewers cannot generate codes");
            return;
        }
        
        String existingEditorCode = editorCodeField.getText();
        String existingViewerCode = viewerCodeField.getText();
        
        if (existingEditorCode != null && !existingEditorCode.isEmpty() &&
            existingViewerCode != null && !existingViewerCode.isEmpty()) {
            if (documentId != null) {
                boolean saved = DatabaseService.getInstance().updateDocumentWithSession(
                    documentId, document.getText(), existingEditorCode, existingViewerCode);
                
                if (saved) {
                    updateStatus("Session codes saved with document");
                } else {
                    updateStatus("Failed to save session codes");
                }
            }
            return;
        }
        
        networkClient.requestCodes();
    }
    
    @FXML
    private void handleJoinSession(ActionEvent event) {
        JoinSessionDialog dialog = new JoinSessionDialog();
        dialog.showAndWait().ifPresent(result -> {
            String code = result.getKey();
            boolean isEditorRole = result.getValue();
            
            try {
                System.out.println("Join session dialog returned: code=" + code + ", isEditor=" + isEditorRole);
                
                isEditor = isEditorRole;
                
                editorArea.setEditable(isEditor);
                
                processBatchInserts();
                updateEditorText("");
                document = new CRDTDocument(userId);
                
                for (CursorMarker marker : new ArrayList<>(cursorMarkers.values())) {
                    cleanupCursorMarker(marker);
                }
                cursorMarkers.clear();
                
                System.out.println("Joining session with code: " + code + " as " + (isEditorRole ? "editor" : "viewer"));
                networkClient.joinSession(code, isEditorRole);
                
                if (isEditor) {
                    editorCodeField.setText(code);
                    updateStatus("Joined session as editor - waiting for content sync...");
                } else {
                    updateStatus("Joined session as viewer - waiting for content sync...");
                    editorCodeField.setText("");
                    viewerCodeField.setText("");
                }
                
                new Thread(() -> {
                    try {
                        Thread.sleep(500);
                        
                        Platform.runLater(() -> {
                            updateStatus("Requesting document sync...");
                            
                            Operation requestResyncOperation = 
                                new Operation(Operation.Type.REQUEST_DOCUMENT_RESYNC, null, null, userId, -1);
                            
                            handleRemoteOperation(requestResyncOperation);
                        });
                    } catch (Exception e) {
                        System.err.println("Error in sync thread: " + e.getMessage());
                        e.printStackTrace();
                    }
                }).start();
            } catch (Exception e) {
                updateStatus("Error joining session: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
    
    private void createDocumentForSession(String sessionCode, boolean isEditorRole) {
        try {
            System.out.println("Creating document for session: " + sessionCode);
            
            org.bson.Document existingDoc = DatabaseService.getInstance().getDocumentBySessionCode(sessionCode);
            if (existingDoc != null) {
                String foundDocId = existingDoc.get("_id").toString();
                String foundTitle = existingDoc.getString("title");
                
                System.out.println("Found existing document for session: " + foundDocId + " (" + foundTitle + ")");
                
                documentId = foundDocId;
                documentTitle = foundTitle;
                
                final boolean isViewerMode = !isEditorRole;
                    Platform.runLater(() -> {
                    try {
                        String windowTitle = "Collaborative Text Editor - " + documentTitle;
                        if (isViewerMode) {
                            windowTitle += " (Viewer Mode)";
                        }
                        Stage stage = (Stage) editorArea.getScene().getWindow();
                        stage.setTitle(windowTitle);
                    } catch (Exception e) {
                        System.err.println("Error updating window title: " + e.getMessage());
                    }
                });
                
                return;
            }
            
            System.out.println("No existing document found for session. Creating new document.");
            
            String newTitle = "Shared Document - " + sessionCode;
            
            String newDocId = DatabaseService.getInstance().createDocument(newTitle, userId);
            
            boolean updated = DatabaseService.getInstance().updateDocumentWithSession(
                newDocId, "", sessionCode, sessionCode);
            
            if (updated) {
                System.out.println("Created new document with ID: " + newDocId + " for session: " + sessionCode);
            } else {
                System.err.println("Failed to update document with session codes");
            }
            
            documentId = newDocId;
            documentTitle = newTitle;
            
            final boolean isViewerMode = !isEditorRole;
                Platform.runLater(() -> {
                try {
                    String windowTitle = "Collaborative Text Editor - " + documentTitle;
                    if (isViewerMode) {
                        windowTitle += " (Viewer Mode)";
                    }
                    Stage stage = (Stage) editorArea.getScene().getWindow();
                    stage.setTitle(windowTitle);
                } catch (Exception e) {
                    System.err.println("Error updating window title: " + e.getMessage());
                }
            });
            
            if (networkClient != null) {
                final NetworkClient finalNetworkClient = networkClient;
                new Thread(() -> {
                    try {
                        Thread.sleep(500);
                    Platform.runLater(() -> {
                            JsonObject resyncRequest = new JsonObject();
                            resyncRequest.addProperty("type", "request_resync");
                            resyncRequest.addProperty("userId", userId);
                            finalNetworkClient.getWebSocketClient().send(new Gson().toJson(resyncRequest));
                            System.out.println("Requested document content from server for new session");
                        });
                    } catch (Exception e) {
                        System.err.println("Error requesting document sync: " + e.getMessage());
                    }
                }).start();
            }
        } catch (Exception e) {
            System.err.println("Error creating document for session: " + e.getMessage());
            e.printStackTrace();
            updateStatus("Error creating document: " + e.getMessage());
        }
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
        saveDocument();
        
        for (CursorMarker marker : new ArrayList<>(cursorMarkers.values())) {
            cleanupCursorMarker(marker);
        }
        cursorMarkers.clear();
        
        if (networkClient != null) {
            networkClient.leaveSession();
            
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            networkClient.disconnect();
        }
        
        ((Stage) editorArea.getScene().getWindow()).close();
    }
    
    private void copyToSystemClipboard(String text) {
        try {
            final javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
            final javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(text);
            clipboard.setContent(content);
        } catch (Exception e) {
            updateStatus("Could not copy to clipboard: " + e.getMessage());
        }
    }
    
    private void updateStatus(String message) {
        if (Platform.isFxApplicationThread()) {
            statusLabel.setText(message);
        } else {
            Platform.runLater(() -> statusLabel.setText(message));
        }
    }
    
    @FXML
    private void handleOpenDocuments(ActionEvent event) {
        try {
            processBatchInserts();
            
            saveDocument();
            
            leaveCurrentSession(false);
            
            Pair<String, String> documentInfo = DocumentSelectionDialog.showDocumentSelectionDialog(userId);
            if (documentInfo != null) {
                String key = documentInfo.getKey();
                
                if (key != null && key.startsWith("JOIN:")) {
                    String[] parts = key.split(":");
                    if (parts.length >= 3) {
                        String code = parts[1];
                        boolean isEditorRole = "EDITOR".equals(parts[2]);
                        
                        joinExistingSession(code, isEditorRole);
                        return;
                    }
                }
                
                documentId = key;
                documentTitle = documentInfo.getValue();
                
                Stage stage = (Stage) editorArea.getScene().getWindow();
                stage.setTitle("Collaborative Text Editor - " + documentTitle + " (" + username + ")");
                
                loadDocumentContent();
                
                updateStatus("Opened document: " + documentTitle);
            }
        } catch (Exception e) {
            updateStatus("Error opening document: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void joinExistingSession(String code, boolean isEditorRole) {
        try {
            System.out.println("======== JOIN EXISTING SESSION ========");
            System.out.println("Code: " + code);
            System.out.println("Role: " + (isEditorRole ? "EDITOR" : "VIEWER"));
            System.out.println("User ID: " + userId);
            
            DocumentSelectionDialog.saveRecentSessionCode(code);
            
            isEditor = isEditorRole;
            
            editorArea.setEditable(isEditor);
            
            processBatchInserts();
            updateEditorText("");
            document = new CRDTDocument(userId);
            
            for (CursorMarker marker : new ArrayList<>(cursorMarkers.values())) {
                cleanupCursorMarker(marker);
            }
            cursorMarkers.clear();
            
            if (networkClient == null || !networkClient.getWebSocketClient().isOpen()) {
                updateStatus("Establishing connection...");
                
                if (networkClient == null) {
                    networkClient = new NetworkClient(userId, username);
                    setupNetworkListeners();
                }
                
                boolean connected = networkClient.connect();
                if (!connected) {
                    updateStatus("Failed to connect to server. Retrying...");
                    new Thread(() -> {
                        try {
                            Thread.sleep(1000);
                            Platform.runLater(() -> {
                                if (networkClient.connect()) {
                                    completeJoinSession(code, isEditorRole);
                                } else {
                                    updateStatus("Failed to connect to collaboration server");
                                }
                            });
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }).start();
                    return;
                }
            }
            
            completeJoinSession(code, isEditorRole);
            
        } catch (Exception e) {
            updateStatus("Error joining session: " + e.getMessage());
            System.err.println("Error in joinExistingSession: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void completeJoinSession(String code, boolean isEditorRole) {
        try {
            System.out.println("=== JOINING SESSION ===");
            System.out.println("Session code: " + code);
            System.out.println("Joining as: " + (isEditorRole ? "EDITOR" : "VIEWER"));
            System.out.println("Local user ID: " + userId);
            
            isEditor = isEditorRole;
            
            editorArea.setEditable(isEditor);
            if (isEditor) {
                updateStatus("Joining session as editor - waiting for content sync...");
            } else {
                updateStatus("Joining session as viewer - waiting for content sync...");
                hideEditorControls();
            }
            
            networkClient.joinSession(code, isEditorRole);
            
        } catch (Exception e) {
            e.printStackTrace();
            updateStatus("Error joining session: " + e.getMessage());
        }
    }
    
    private void leaveCurrentSession(boolean fullDisconnect) {
        try {
            String currentEditorCode = editorCodeField.getText();
            String currentViewerCode = viewerCodeField.getText();
            
            for (CursorMarker marker : new ArrayList<>(cursorMarkers.values())) {
                cleanupCursorMarker(marker);
            }
            cursorMarkers.clear();
            
            users.clear();
            userMap.clear();
            
            isEditor = true;
            editorArea.setEditable(true);
            
            if (networkClient != null && networkClient.isConnected()) {
                networkClient.leaveSession();
                
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                if (username != null) {
                    users.add(username + " (you)");
                }
            }
            
            if (fullDisconnect) {
                editorCodeField.setText("");
                viewerCodeField.setText("");
                
                if (networkClient != null) {
                    networkClient.disconnect();
                    
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    
                    networkClient = new NetworkClient(userId, username);
                    setupNetworkListeners();
                    networkClient.connect();
                }
            } else {
                System.out.println("Temporary session transition - preserving codes: " + 
                                  "Editor=" + currentEditorCode + ", Viewer=" + currentViewerCode);
                
                if (documentId != null && currentEditorCode != null && !currentEditorCode.isEmpty()) {
                    DatabaseService.getInstance().updateDocumentWithSession(
                        documentId, document.getText(), currentEditorCode, currentViewerCode);
                }
            }
        } catch (Exception e) {
            System.err.println("Error during session transition: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void leaveCurrentSession() {
        leaveCurrentSession(true);
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
        }, 30000, 30000);
    }
    
    private void saveDocument() {
        processBatchInserts();
        
        if (documentId != null) {
            String content = document.getText();
            
            String editorCode = editorCodeField.getText();
            String viewerCode = viewerCodeField.getText();
            
            boolean saved = false;
            if (editorCode != null && !editorCode.isEmpty() && 
                viewerCode != null && !viewerCode.isEmpty()) {
                saved = DatabaseService.getInstance().updateDocumentWithSession(
                    documentId, content, editorCode, viewerCode);
            } else {
                saved = DatabaseService.getInstance().updateDocument(documentId, content);
            }
            
            if (saved) {
                networkClient.sendDocumentUpdate(content);
                
                Platform.runLater(() -> updateStatus("Document saved"));
            } else {
                Platform.runLater(() -> updateStatus("Failed to save document"));
            }
        }
    }
    
    @FXML
    private void handleSaveDocument(ActionEvent event) {
        try {
            processBatchInserts();
            
            saveDocument();
            
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
    
    private void cleanupCursorMarker(CursorMarker marker) {
        if (marker != null) {
            marker.setVisible(false);
            editorContainer.getChildren().remove(marker);
        }
    }
    
    @FXML
    private void handleFixDocument(ActionEvent event) {
        try {
            processBatchInserts();
            
            String currentText = editorArea.getText();
            
            StringBuilder cleanContent = new StringBuilder(currentText.length());
            for (int i = 0; i < currentText.length(); i++) {
                char c = currentText.charAt(i);
                if (c >= 32 || c == '\t' || c == '\n' || c == '\r') {
                    cleanContent.append(c);
                } else {
                    System.out.println("Removed problematic character at position " + i + 
                                      ": code=" + (int)c);
                }
            }
            
            String cleanedText = cleanContent.toString();
            
            synchronized (document) {
                document = new CRDTDocument(userId);
                
                for (int i = 0; i < cleanedText.length(); i++) {
                    document.localInsert(i, cleanedText.charAt(i));
                }
                
                updateEditorText(cleanedText);
                
                updateWordCount();
                
                Platform.runLater(() -> {
                    for (CursorMarker marker : new ArrayList<>(cursorMarkers.values())) {
                        cleanupCursorMarker(marker);
                    }
                    cursorMarkers.clear();
                    
                    if (userMap != null && !userMap.isEmpty()) {
                        updateCursorMarkers(new ArrayList<>(userMap.keySet()));
                    }
                });
                
                networkClient.sendDocumentUpdate(cleanedText);
                
                updateStatus("Document fixed and cleaned");
            }
        } catch (Exception e) {
            System.err.println("Error fixing document: " + e.getMessage());
            e.printStackTrace();
            updateStatus("Error fixing document: " + e.getMessage());
        }
    }
    
    @FXML
    private void handleTestDatabaseConnection(ActionEvent event) {
        try {
            updateStatus("Testing MongoDB connection...");
            
            boolean connected = DatabaseService.getInstance().testMongoDBConnection();
            
            if (connected) {
                updateStatus("MongoDB connection successful! Your data is being saved to the cloud.");
            } else {
                updateStatus("WARNING: Using in-memory storage. Your data is NOT being saved to MongoDB!");
            }
        } catch (Exception e) {
            updateStatus("Error testing MongoDB connection: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    @FXML
    private void handleShareCodes(ActionEvent event) {
        String editorCode = editorCodeField.getText();
        String viewerCode = viewerCodeField.getText();
        
        if (editorCode != null && !editorCode.isEmpty() && 
            viewerCode != null && !viewerCode.isEmpty()) {
            String shareMessage = "Join my document!\n" +
                                 "Editor code: " + editorCode + "\n" +
                                 "Viewer code: " + viewerCode;
            
            copyToSystemClipboard(shareMessage);
            updateStatus("Sharing codes copied to clipboard");
        } else {
            updateStatus("No sharing codes available for this document");
        }
    }
    
    private void loadDocumentContent() {
        try {
            processBatchInserts();
            document = new CRDTDocument(userId);
            updateEditorText("");
            
            updateStatus("Loading document: " + documentTitle + "...");
            
            if (documentId == null || documentId.isEmpty()) {
                updateStatus("Invalid document ID");
                return;
            }
            
            System.out.println("==================================================");
            System.out.println("LOADING DOCUMENT");
            System.out.println("Document ID: " + documentId);
            System.out.println("Document Title: " + documentTitle);
            System.out.println("User ID: " + userId);
            System.out.println("==================================================");
            
            org.bson.Document doc = null;
            try {
                doc = DatabaseService.getInstance().getDocument(documentId);
                if (doc == null) {
                    System.err.println("Document not found in database: " + documentId);
                    updateStatus("Document not found. Creating a new one.");
                    createNewDocumentWithSession();
                    return;
                }
            } catch (Exception e) {
                System.err.println("Database error: " + e.getMessage());
                e.printStackTrace();
                updateStatus("Error connecting to database. Using empty document.");
                createNewDocumentWithSession();
                return;
            }
            
            System.out.println("Document details from database:");
            System.out.println("  ID: " + doc.get("_id"));
            System.out.println("  Title: " + doc.getString("title"));
            System.out.println("  Owner ID: " + doc.getString("ownerId"));
            String docContent = doc.getString("content");
            System.out.println("  Content length: " + (docContent != null ? docContent.length() : 0) + " characters");
            
                String ownerId = doc.getString("ownerId");
                boolean isOwnedByCurrentUser = userId.equals(ownerId);
            System.out.println("Document is owned by current user: " + isOwnedByCurrentUser);
                
                String content = null;
                try {
                    content = doc.getString("content");
                if (content == null) {
                    content = "";
                    System.out.println("Content was null, using empty string");
                }
                } catch (Exception e) {
                    System.err.println("Error reading document content: " + e.getMessage());
                    updateStatus("Error reading document content. Using empty document.");
                content = "";
                }
                
                String existingEditorCode = null;
                String existingViewerCode = null;
                try {
                    existingEditorCode = doc.getString("editorCode");
                    existingViewerCode = doc.getString("viewerCode");
                System.out.println("Found session codes - Editor: " + existingEditorCode + ", Viewer: " + existingViewerCode);
                } catch (Exception e) {
                    System.err.println("Error reading session codes: " + e.getMessage());
                }
                
            final String finalEditorCode = existingEditorCode;
            final String finalViewerCode = existingViewerCode;
            
            if (finalEditorCode != null && !finalEditorCode.isEmpty()) {
                System.out.println("Document has existing session, joining it...");
                
                Platform.runLater(() -> {
                    if (isOwnedByCurrentUser) {
                        editorCodeField.setText(finalEditorCode);
                        if (finalViewerCode != null && !finalViewerCode.isEmpty()) {
                            viewerCodeField.setText(finalViewerCode);
                        } else {
                            viewerCodeField.setText(finalEditorCode);
                        }
                    } else {
                        editorCodeField.setText("");
                        if (finalViewerCode != null && !finalViewerCode.isEmpty()) {
                            viewerCodeField.setText(finalViewerCode);
                        } else {
                            viewerCodeField.setText(finalEditorCode);
                        }
                    }
                });
                
                if (content != null && !content.isEmpty()) {
                    try {
                        final String finalContent = content;
                        Platform.runLater(() -> {
                            try {
                                for (int i = 0; i < finalContent.length(); i++) {
                                    document.localInsert(i, finalContent.charAt(i));
                                }
                                
                                updateEditorText(finalContent);
                            } catch (Exception e) {
                                System.err.println("Error inserting document content: " + e.getMessage());
                                e.printStackTrace();
                            }
                        });
                    } catch (Exception e) {
                        System.err.println("Error loading document content: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
                
                boolean joinAsEditor = isOwnedByCurrentUser;
                String codeToUse = joinAsEditor ? finalEditorCode : (finalViewerCode != null && !finalViewerCode.isEmpty() ? finalViewerCode : finalEditorCode);
                System.out.println("Joining session with code " + codeToUse + " as " + (joinAsEditor ? "EDITOR" : "VIEWER") + 
                                  " (document owner: " + isOwnedByCurrentUser + ")");
                DocumentSelectionDialog.saveRecentSessionCode(codeToUse);
                joinExistingSession(codeToUse, joinAsEditor);
            } else {
                System.out.println("Document has no session, creating one...");
                
                if (content != null && !content.isEmpty()) {
                    try {
                        final String finalContent = content;
                        Platform.runLater(() -> {
                            try {
                                for (int i = 0; i < finalContent.length(); i++) {
                                    document.localInsert(i, finalContent.charAt(i));
                                }
                                
                                updateEditorText(finalContent);
                            } catch (Exception e) {
                                System.err.println("Error inserting document content: " + e.getMessage());
                                e.printStackTrace();
                            }
                        });
                    } catch (Exception e) {
                        System.err.println("Error loading document content: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
                
                if (isOwnedByCurrentUser) {
                    networkClient.requestCodes();
                    updateStatus("Creating new session for document...");
                    
                    new Thread(() -> {
                        try {
                            Thread.sleep(500);
                            Platform.runLater(() -> {
                                if (document != null) {
                                    String docText = document.getText();
                                    networkClient.sendDocumentUpdate(docText);
                                }
                            });
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }).start();
                } else {
                    updateStatus("Viewing document in read-only mode");
                    isEditor = false;
                    editorArea.setEditable(false);
                }
            }
        } catch (Exception e) {
            updateStatus("Error loading document: " + e.getMessage());
            e.printStackTrace();
            
            try {
                document = new CRDTDocument(userId);
                updateEditorText("");
                
                createNewDocumentWithSession();
            } catch (Exception ex) {
                System.err.println("Failed to reset document: " + ex.getMessage());
            }
        }
    }
    
    private void createNewDocumentWithSession() {
        try {
            if (documentId == null) {
                documentId = DatabaseService.getInstance().createDocument(
                    documentTitle != null ? documentTitle : "Untitled Document", 
                    userId
                );
                
                System.out.println("Created new document with ID: " + documentId);
            }
            
                    networkClient.requestCodes();
            updateStatus("Creating new session for document...");
                    
                    new Thread(() -> {
                        try {
                            Thread.sleep(500);
                            Platform.runLater(() -> {
                        isEditor = true;
                        editorArea.setEditable(true);
                        
                        networkClient.sendDocumentUpdate("");
                            });
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }).start();
        } catch (Exception e) {
            System.err.println("Error creating new document with session: " + e.getMessage());
            e.printStackTrace();
            updateStatus("Error creating document. Please try again.");
        }
    }

    private void refreshCursorMarkers() {
        if (cursorMarkers.isEmpty()) {
            return;
        }
        
        Platform.runLater(() -> {
            try {
                for (String userId : new HashSet<>(cursorMarkers.keySet())) {
                    if (userId.equals(this.userId)) {
                        continue;
                    }
                    
                    Integer position = networkClient.getLastKnownCursorPosition(userId);
                    if (position != null && position >= 0) {
                        updateRemoteCursor(userId, position);
                    }
                }
            } catch (Exception e) {
                System.err.println("Error refreshing cursor markers: " + e.getMessage());
            }
        });
    }
    
    @FXML
    private void handleResetClient(ActionEvent event) {
        resetClient();
    }
    
    private void resetClient() {
        try {
            Platform.runLater(() -> {
                updateStatus("Resetting client state...");
                
                processBatchInserts();
                
                if (documentId != null) {
                    try {
                        saveDocument();
                    } catch (Exception e) {
                        System.err.println("Error saving document during reset: " + e.getMessage());
                    }
                }
                
                for (CursorMarker marker : new ArrayList<>(cursorMarkers.values())) {
                    cleanupCursorMarker(marker);
                }
                cursorMarkers.clear();
                
                users.clear();
                
                userMap.clear();
                
                if (networkClient != null) {
                    try {
                        networkClient.disconnect();
        } catch (Exception e) {
                        System.err.println("Error disconnecting: " + e.getMessage());
                    }
                }
            
                new Thread(() -> {
            try {
                        Thread.sleep(500);
                        
                        Platform.runLater(() -> {
                            try {
                                document = new CRDTDocument(userId);
                                
                                networkClient = new NetworkClient(userId, username);
                                
                                setupNetworkListeners();
                                
                                networkClient.connect();
                                
                                if (documentId != null) {
                                    loadDocumentContent();
                                }
                                
                                updateStatus("Client reset completed successfully");
                            } catch (Exception e) {
                                System.err.println("Error during client reset: " + e.getMessage());
                                updateStatus("Error during client reset: " + e.getMessage());
                            }
                        });
                    } catch (Exception e) {
                        System.err.println("Error during client reset: " + e.getMessage());
                    }
                }).start();
            });
        } catch (Exception e) {
            System.err.println("Error resetting client: " + e.getMessage());
            updateStatus("Error resetting client: " + e.getMessage());
        }
    }
    
    private void handlePossibleCorruption() {
        try {
            if (document != null && editorArea != null) {
                String docText = document.getText();
                String uiText = editorArea.getText();
                
                if (docText != null && uiText != null) {
                    int docLength = docText.length();
                    int uiLength = uiText.length();
                    
                    double lengthRatio = (docLength > uiLength) ? 
                        (double)docLength / Math.max(1, uiLength) : 
                        (double)uiLength / Math.max(1, docLength);
                    
                    if (lengthRatio > 1.2) {
                        System.err.println("Document content significantly out of sync (ratio: " + lengthRatio + 
                                          "). Doc length: " + docLength + ", UI length: " + uiLength);
                        
                        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                        alert.setTitle("Document Sync Issue");
                        alert.setHeaderText("Document content is out of sync");
                        alert.setContentText("The editor has detected that the document content is significantly out of sync. " +
                                            "Would you like to reset the client to fix this issue?");
                        
                        Optional<ButtonType> result = alert.showAndWait();
                        if (result.isPresent() && result.get() == ButtonType.OK) {
                            resetClient();
                        } else {
                            updateEditorText(docText);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error checking for corruption: " + e.getMessage());
        }
    }

    private void hideEditorControls() {
        editorCodeField.setVisible(false);
        viewerCodeField.setVisible(false);
        
        statusLabel.setText("VIEWER MODE - Read Only");
        statusLabel.setStyle("-fx-text-fill: #885500;");
        editorArea.setStyle("-fx-background-color: #ffffee;");
    }
}