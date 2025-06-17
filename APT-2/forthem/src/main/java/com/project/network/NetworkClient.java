package com.project.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.project.crdt.CRDTCharacter;
import com.project.crdt.Position;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A WebSocket client for handling communication with the server.
 */
public class NetworkClient {
    private static final String DEFAULT_SERVER_URI = "ws://localhost:8887";
    
    private final String userId;
    private WebSocketClient webSocketClient;
    private final Gson gson = new Gson();
    
    private final List<Consumer<Operation>> operationListeners = new ArrayList<>();
    private final List<Consumer<List<String>>> presenceListeners = new ArrayList<>();
    private final List<Consumer<String>> errorListeners = new ArrayList<>();
    private final List<Consumer<CodePair>> codeListeners = new ArrayList<>();
    
    // Track current cursor position to avoid sending duplicates
    private volatile int lastSentCursorPosition = -1;
    
    // Track if a cursor move operation is pending
    private volatile boolean cursorMoveScheduled = false;
    
    // Cursor position update throttling
    private static final long CURSOR_MOVE_THROTTLE_MS = 50;
    private volatile long lastCursorMoveTime = 0;
    
    // Keep track of the last operation times
    private Map<String, Long> lastOperationTimes = new ConcurrentHashMap<>();
    
    private boolean connected = false;
    
    public NetworkClient(String userId) {
        this.userId = userId;
    }
    
    /**
     * Connects to the server.
     * @return true if connected successfully, false otherwise.
     */
    public boolean connect() {
        try {
            URI serverUri = new URI(DEFAULT_SERVER_URI);
            webSocketClient = new WebSocketClient(serverUri) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    connected = true;
                    System.out.println("Connected to WebSocket server");
                    
                    // Register with the server
                    JsonObject message = new JsonObject();
                    message.addProperty("type", "register");
                    message.addProperty("userId", userId);
                    send(gson.toJson(message));
                }
                
                @Override
                public void onMessage(String message) {
                    handleServerMessage(message);
                }
                
                @Override
                public void onClose(int code, String reason, boolean remote) {
                    connected = false;
                    System.out.println("Connection closed: " + reason);
                }
                
                @Override
                public void onError(Exception ex) {
                    notifyErrorListeners("WebSocket error: " + ex.getMessage());
                    System.err.println("WebSocket error: " + ex.getMessage());
                    ex.printStackTrace();
                }
            };
            
            return webSocketClient.connectBlocking();
        } catch (URISyntaxException | InterruptedException e) {
            notifyErrorListeners("Failed to connect: " + e.getMessage());
            System.err.println("Failed to connect: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Disconnects from the server.
     */
    public void disconnect() {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            try {
                webSocketClient.closeBlocking();
            } catch (InterruptedException e) {
                System.err.println("Error disconnecting: " + e.getMessage());
            }
        }
        connected = false;
    }
    
    /**
     * Sends an insert operation to the server.
     * @param character The character to insert.
     */
    public void sendInsert(CRDTCharacter character) {
        if (!connected) {
            notifyErrorListeners("Not connected to server");
            return;
        }
        
        // Record operation time
        lastOperationTimes.put("insert", System.currentTimeMillis());
        
        JsonObject message = new JsonObject();
        message.addProperty("type", "insert");
        message.addProperty("userId", userId);
        
        // Convert CRDTCharacter to JSON
        JsonObject charObj = new JsonObject();
        charObj.addProperty("value", character.getValue());
        charObj.add("position", gson.toJsonTree(character.getPosition()));
        charObj.addProperty("authorId", character.getAuthorId());
        charObj.addProperty("timestamp", character.getTimestamp());
        message.add("character", charObj);
        
        webSocketClient.send(gson.toJson(message));
    }
    
    /**
     * Sends a delete operation to the server.
     * @param position The position to delete.
     */
    public void sendDelete(Position position) {
        if (!connected) {
            notifyErrorListeners("Not connected to server");
            return;
        }
        
        // Record operation time
        lastOperationTimes.put("delete", System.currentTimeMillis());
        
        JsonObject message = new JsonObject();
        message.addProperty("type", "delete");
        message.addProperty("userId", userId);
        message.add("position", gson.toJsonTree(position));
        
        webSocketClient.send(gson.toJson(message));
    }
    
    /**
     * Sends a cursor move operation to the server.
     * This method uses throttling to avoid sending too many updates.
     * @param position The new cursor position.
     */
    public void sendCursorMove(int position) {
        if (!connected) {
            notifyErrorListeners("Not connected to server");
            return;
        }
        
        // Don't send if the position hasn't changed
        if (position == lastSentCursorPosition) {
            return;
        }
        
        // Check if we recently sent an edit operation, if so, delay the cursor update
        long now = System.currentTimeMillis();
        long lastEditTime = Math.max(
            lastOperationTimes.getOrDefault("insert", 0L),
            lastOperationTimes.getOrDefault("delete", 0L)
        );
        
        // If we recently did an edit, don't send cursor updates
        if (now - lastEditTime < 100) {
            return;
        }
        
        // Throttle updates
        if (now - lastCursorMoveTime < CURSOR_MOVE_THROTTLE_MS) {
            // If we're already scheduled to send an update, just update the position
            if (cursorMoveScheduled) {
                lastSentCursorPosition = position;
                return;
            }
            
            // Schedule a delayed update
            cursorMoveScheduled = true;
            lastSentCursorPosition = position;
            
            final int capturedPosition = position;
            new Thread(() -> {
                try {
                    Thread.sleep(CURSOR_MOVE_THROTTLE_MS);
                    sendCursorMoveNow(lastSentCursorPosition);
                    cursorMoveScheduled = false;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
            
            return;
        }
        
        // Send immediately
        sendCursorMoveNow(position);
    }
    
    /**
     * Immediately sends a cursor move without throttling.
     * @param position The cursor position.
     */
    private void sendCursorMoveNow(int position) {
        if (!connected) {
            return;
        }
        
        lastCursorMoveTime = System.currentTimeMillis();
        lastSentCursorPosition = position;
        
        JsonObject message = new JsonObject();
        message.addProperty("type", "cursor_move");
        message.addProperty("userId", userId);
        message.addProperty("position", position);
        
        webSocketClient.send(gson.toJson(message));
    }
    
    /**
     * Requests shareable codes from the server.
     */
    public void requestCodes() {
        if (!connected) {
            notifyErrorListeners("Not connected to server");
            return;
        }
        
        JsonObject message = new JsonObject();
        message.addProperty("type", "create_session");
        message.addProperty("userId", userId);
        
        webSocketClient.send(gson.toJson(message));
    }
    
    /**
     * Joins a session using a code.
     * @param code The session code.
     * @param isEditor Whether the user is joining as an editor or viewer.
     */
    public void joinSession(String code, boolean isEditor) {
        if (!connected) {
            notifyErrorListeners("Not connected to server");
            return;
        }
        
        JsonObject message = new JsonObject();
        message.addProperty("type", "join_session");
        message.addProperty("userId", userId);
        message.addProperty("code", code);
        message.addProperty("asEditor", isEditor);
        
        webSocketClient.send(gson.toJson(message));
    }
    
    /**
     * Handles messages from the server.
     * @param message The message from the server.
     */
    private void handleServerMessage(String message) {
        try {
            JsonObject jsonMessage = gson.fromJson(message, JsonObject.class);
            String type = jsonMessage.get("type").getAsString();
            
            switch (type) {
                case "register_ack":
                    System.out.println("Registered with server as " + jsonMessage.get("userId").getAsString());
                    break;
                    
                case "session_created":
                    String editorCode = jsonMessage.get("editorCode").getAsString();
                    String viewerCode = jsonMessage.get("viewerCode").getAsString();
                    notifyCodeListeners(new CodePair(editorCode, viewerCode));
                    break;
                    
                case "session_joined":
                    boolean asEditor = jsonMessage.get("asEditor").getAsBoolean();
                    System.out.println("Joined session as " + (asEditor ? "editor" : "viewer"));
                    break;
                    
                case "presence":
                    List<String> users = gson.fromJson(jsonMessage.get("users"), List.class);
                    notifyPresenceListeners(users);
                    break;
                    
                case "insert":
                    handleInsertOperation(jsonMessage);
                    break;
                    
                case "delete":
                    handleDeleteOperation(jsonMessage);
                    break;
                    
                case "cursor_move":
                    handleCursorMoveOperation(jsonMessage);
                    break;
                    
                case "cursor_remove":
                    handleCursorRemoveOperation(jsonMessage);
                    break;
                    
                case "document_sync":
                    handleDocumentSyncOperation(jsonMessage);
                    break;
                    
                case "sync_confirmation_request":
                    handleSyncConfirmationRequest(jsonMessage);
                    break;
                    
                case "sync_confirmation":
                    // Log that document sync is confirmed
                    int docLength = jsonMessage.get("documentLength").getAsInt();
                    System.out.println("Document sync confirmed - document length: " + docLength);
                    break;
                    
                case "error":
                    notifyErrorListeners(jsonMessage.get("message").getAsString());
                    break;
                    
                default:
                    System.out.println("Unknown message type: " + type);
            }
        } catch (Exception e) {
            System.err.println("Error processing message: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void handleInsertOperation(JsonObject message) {
        try {
            String sourceUserId = message.get("userId").getAsString();
            JsonObject characterObj = message.getAsJsonObject("character");
            
            char value = characterObj.get("value").getAsCharacter();
            Position position = gson.fromJson(characterObj.getAsJsonObject("position"), Position.class);
            String authorId = characterObj.get("authorId").getAsString();
            long timestamp = characterObj.get("timestamp").getAsLong();
            
            // Create the CRDTCharacter
            CRDTCharacter character = new CRDTCharacter(value, position, authorId, timestamp);
            
            // Create and notify with the operation
            Operation operation = new Operation(Operation.Type.INSERT, character, null, sourceUserId, -1);
            notifyOperationListeners(operation);
        } catch (Exception e) {
            System.err.println("Error processing insert operation: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void handleDeleteOperation(JsonObject message) {
        try {
            String sourceUserId = message.get("userId").getAsString();
            Position position = gson.fromJson(message.getAsJsonObject("position"), Position.class);
            
            // Create and notify with the operation
            Operation operation = new Operation(Operation.Type.DELETE, null, position, sourceUserId, -1);
            notifyOperationListeners(operation);
        } catch (Exception e) {
            System.err.println("Error processing delete operation: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void handleCursorMoveOperation(JsonObject message) {
        try {
            String sourceUserId = message.get("userId").getAsString();
            int position = message.get("position").getAsInt();
            
            // Create and notify with the operation
            Operation operation = new Operation(Operation.Type.CURSOR_MOVE, null, null, sourceUserId, position);
            notifyOperationListeners(operation);
        } catch (Exception e) {
            System.err.println("Error processing cursor move operation: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void handleCursorRemoveOperation(JsonObject message) {
        try {
            String sourceUserId = message.get("userId").getAsString();
            
            // Create and notify with the operation (position -1 means remove)
            Operation operation = new Operation(Operation.Type.CURSOR_MOVE, null, null, sourceUserId, -1);
            notifyOperationListeners(operation);
        } catch (Exception e) {
            System.err.println("Error processing cursor remove operation: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void handleDocumentSyncOperation(JsonObject message) {
        try {
            String content = message.get("content").getAsString();
            
            // Create a special operation for document sync
            Operation operation = new Operation(Operation.Type.DOCUMENT_SYNC, null, null, userId, -1, content);
            notifyOperationListeners(operation);
        } catch (Exception e) {
            System.err.println("Error processing document sync operation: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Handles a sync confirmation request from the server.
     * @param message The message from the server.
     */
    private void handleSyncConfirmationRequest(JsonObject message) {
        // Get the current document length through EditorController
        int currentDocLength = -1;
        
        try {
            // Create a special operation to request the document length
            Operation getDocumentOperation = new Operation(Operation.Type.GET_DOCUMENT_LENGTH, null, null, userId, -1);
            
            // Wait for listener to update the length
            for (Consumer<Operation> listener : operationListeners) {
                try {
                    listener.accept(getDocumentOperation);
                    // The listener should update the document length in the operation object
                    currentDocLength = getDocumentOperation.getDocumentLength();
                    break; // Only need one successful response
                } catch (Exception e) {
                    System.err.println("Error getting document length: " + e.getMessage());
                }
            }
            
            // Send confirmation back to server
            JsonObject confirmMsg = new JsonObject();
            confirmMsg.addProperty("type", "sync_confirmation");
            confirmMsg.addProperty("receivedLength", currentDocLength);
            confirmMsg.addProperty("userId", userId);
            
            webSocketClient.send(gson.toJson(confirmMsg));
            
            System.out.println("Sent document sync confirmation with length: " + currentDocLength);
            
            // Check if server expects us to have content but we don't
            int expectedLength = -1;
            if (message.has("expectedLength")) {
                expectedLength = message.get("expectedLength").getAsInt();
            }
            
            // If server expects content but we have none, or lengths don't match,
            // request a document resync
            if ((expectedLength > 0 && currentDocLength <= 0) || 
                (expectedLength > 0 && expectedLength != currentDocLength)) {
                System.out.println("Length mismatch: local=" + currentDocLength + 
                                  ", expected=" + expectedLength + ". Requesting resync.");
                
                // Force a document resync
                JsonObject resyncRequest = new JsonObject();
                resyncRequest.addProperty("type", "request_resync");
                resyncRequest.addProperty("userId", userId);
                webSocketClient.send(gson.toJson(resyncRequest));
                
                // Also notify listeners to trigger a local resync request
                Operation requestResyncOperation = new Operation(Operation.Type.REQUEST_DOCUMENT_RESYNC, 
                                                              null, null, userId, -1);
                notifyOperationListeners(requestResyncOperation);
            }
        } catch (Exception e) {
            System.err.println("Error handling sync confirmation request: " + e.getMessage());
            e.printStackTrace();
            
            // Send error notification
            JsonObject errorMsg = new JsonObject();
            errorMsg.addProperty("type", "sync_confirmation");
            errorMsg.addProperty("receivedLength", -1);
            errorMsg.addProperty("error", e.getMessage());
            errorMsg.addProperty("userId", userId);
            
            webSocketClient.send(gson.toJson(errorMsg));
        }
    }
    
    /**
     * Adds a listener for operations.
     * @param listener The listener to add.
     */
    public void addOperationListener(Consumer<Operation> listener) {
        operationListeners.add(listener);
    }
    
    /**
     * Adds a listener for presence updates.
     * @param listener The listener to add.
     */
    public void addPresenceListener(Consumer<List<String>> listener) {
        presenceListeners.add(listener);
    }
    
    /**
     * Adds a listener for errors.
     * @param listener The listener to add.
     */
    public void addErrorListener(Consumer<String> listener) {
        errorListeners.add(listener);
    }
    
    /**
     * Adds a listener for code updates.
     * @param listener The listener to add.
     */
    public void addCodeListener(Consumer<CodePair> listener) {
        codeListeners.add(listener);
    }
    
    private void notifyOperationListeners(Operation operation) {
        for (Consumer<Operation> listener : operationListeners) {
            listener.accept(operation);
        }
    }
    
    private void notifyPresenceListeners(List<String> users) {
        for (Consumer<List<String>> listener : presenceListeners) {
            listener.accept(users);
        }
    }
    
    private void notifyErrorListeners(String error) {
        for (Consumer<String> listener : errorListeners) {
            listener.accept(error);
        }
    }
    
    private void notifyCodeListeners(CodePair codePair) {
        for (Consumer<CodePair> listener : codeListeners) {
            listener.accept(codePair);
        }
    }
    
    /**
     * Class representing a pair of editor and viewer codes.
     */
    public static class CodePair {
        private final String editorCode;
        private final String viewerCode;
        
        public CodePair(String editorCode, String viewerCode) {
            this.editorCode = editorCode;
            this.viewerCode = viewerCode;
        }
        
        public String getEditorCode() {
            return editorCode;
        }
        
        public String getViewerCode() {
            return viewerCode;
        }
    }
    
    /**
     * Sends the full document content to the server.
     * @param content The full document content.
     */
    public void sendDocumentUpdate(String content) {
        if (!connected) {
            notifyErrorListeners("Not connected to server");
            return;
        }
        
        // Don't send empty content
        final String finalContent = (content == null) ? "" : content;
        
        JsonObject message = new JsonObject();
        message.addProperty("type", "document_update");
        message.addProperty("userId", userId);
        message.addProperty("content", finalContent);
        message.addProperty("timestamp", System.currentTimeMillis());
        
        // Add retry logic for important document updates
        try {
            webSocketClient.send(gson.toJson(message));
            
            // Add a small delay to allow the server to process before any further operations
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // For non-empty content, schedule an automatic resend after a delay
            // to ensure other clients receive it
            if (finalContent.length() > 0) {
                new Thread(() -> {
                    try {
                        // Wait longer before second attempt
                        Thread.sleep(500);
                        if (connected) {
                            // Add a sequence number to avoid duplicates on server
                            message.addProperty("seq", System.currentTimeMillis());
                            webSocketClient.send(gson.toJson(message));
                            System.out.println("Sent followup document update with " + finalContent.length() + " chars");
                        }
                    } catch (Exception ex) {
                        System.err.println("Scheduled resend failed: " + ex.getMessage());
                    }
                }).start();
            }
        } catch (Exception e) {
            System.err.println("Error sending document update: " + e.getMessage());
            e.printStackTrace();
            
            // Try again once after a short delay
            new Thread(() -> {
                try {
                    Thread.sleep(100);
                    if (connected) {
                        webSocketClient.send(gson.toJson(message));
                    }
                } catch (Exception ex) {
                    System.err.println("Retry failed: " + ex.getMessage());
                }
            }).start();
        }
    }
} 