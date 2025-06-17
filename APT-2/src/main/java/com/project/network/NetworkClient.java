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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javafx.application.Platform;

public class NetworkClient {
    private static final String DEFAULT_SERVER_URI = "ws://localhost:8887";
    
    private String userId;
    private String username;
    private WebSocketClient webSocketClient;
    private final Gson gson = new Gson();
    
    private final List<Consumer<Operation>> operationListeners = new ArrayList<>();
    private final List<Consumer<Map<String, String>>> presenceListeners = new ArrayList<>();
    private final List<Consumer<String>> errorListeners = new ArrayList<>();
    private final List<Consumer<CodePair>> codeListeners = new ArrayList<>();
    
    private volatile int lastSentCursorPosition = -1;
    
    private volatile boolean cursorMoveScheduled = false;
    
    private static final long CURSOR_MOVE_THROTTLE_MS = 40;
    private volatile long lastCursorMoveTime = 0;
    
    private Map<String, Long> lastOperationTimes = new ConcurrentHashMap<>();
    
    private Map<String, Integer> lastKnownCursorPositions = new ConcurrentHashMap<>();
    
    private boolean connected = false;
    
    private List<Consumer<Boolean>> connectionListeners = new ArrayList<>();
    
    private final Set<String> recentlySyncedDocuments = new HashSet<>();
    
    public WebSocketClient getWebSocketClient() {
        return webSocketClient;
    }
    
    public NetworkClient(String userId) {
        this.userId = userId;
        this.username = userId;
        
        startSyncHistoryCleaner();
    }
    
    public NetworkClient(String userId, String username) {
        this.userId = userId;
        this.username = username;
        
        startSyncHistoryCleaner();
        
        addConnectionListener(connected -> {
            if (connected) {
                JsonObject usernameMessage = new JsonObject();
                usernameMessage.addProperty("type", "update_username");
                usernameMessage.addProperty("userId", userId);
                usernameMessage.addProperty("username", username);
                webSocketClient.send(gson.toJson(usernameMessage));
            }
        });
    }
    
    public boolean connect() {
        try {
            if (webSocketClient != null && webSocketClient.isOpen()) {
                System.out.println("Already connected to WebSocket server");
                return true;
            }
            
            System.out.println("Connecting to WebSocket server");
            
            purgeDisconnectedUsers();
            
            webSocketClient = new WebSocketClient(new URI(DEFAULT_SERVER_URI)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    System.out.println("Connected to WebSocket server");
                    connected = true;
                    notifyConnectionListeners(true);
                    
                    sendRegistration();
                    
                    if (username != null && !username.isEmpty()) {
                        sendPresenceUpdate();
                    }
                }
                
                @Override
                public void onMessage(String message) {
                    handleServerMessage(message);
                }
                
                @Override
                public void onClose(int code, String reason, boolean remote) {
                    connected = false;
                    System.out.println("Connection closed: " + reason);
                    
                    lastKnownCursorPositions.clear();
                    
                    notifyConnectionListeners(false);
                    
                    if (remote) {
                        new Thread(() -> {
                            try {
                                System.out.println("Attempting to reconnect in 3 seconds...");
                                Thread.sleep(3000);
                                connect();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }).start();
                    }
                }
                
                @Override
                public void onError(Exception ex) {
                    notifyErrorListeners("WebSocket error: " + ex.getMessage());
                    System.err.println("WebSocket error: " + ex.getMessage());
                    ex.printStackTrace();
                    
                    if (connected) {
                        connected = false;
                        notifyConnectionListeners(false);
                    }
                }
            };
            
            webSocketClient.setConnectionLostTimeout(30);
            
            boolean success = false;
            try {
                success = webSocketClient.connectBlocking(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                System.err.println("Connection interrupted: " + e.getMessage());
                Thread.currentThread().interrupt();
            }
            
            if (!success) {
                System.err.println("Failed to connect to WebSocket server");
                notifyErrorListeners("Failed to connect to collaboration server");
                return false;
            }
            
            return true;
        } catch (URISyntaxException e) {
            notifyErrorListeners("Invalid server URI: " + e.getMessage());
            System.err.println("Invalid server URI: " + e.getMessage());
            return false;
        } catch (Exception e) {
            notifyErrorListeners("Connection error: " + e.getMessage());
            System.err.println("Connection error: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    private void sendRegistration() {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            try {
                JsonObject message = new JsonObject();
                message.addProperty("type", "register");
                message.addProperty("userId", userId);
                if (username != null && !username.isEmpty()) {
                    message.addProperty("username", username);
                }
                
                webSocketClient.send(gson.toJson(message));
                System.out.println("Registering with server as user: " + (username != null ? username : userId));
            } catch (Exception e) {
                System.err.println("Error sending registration: " + e.getMessage());
            }
        }
    }
    
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
    
    public void sendInsert(CRDTCharacter character) {
        if (!connected) {
            notifyErrorListeners("Not connected to server");
            return;
        }
        
        lastOperationTimes.put("insert", System.currentTimeMillis());
        
        JsonObject message = new JsonObject();
        message.addProperty("type", "insert");
        message.addProperty("userId", userId);
        
        JsonObject charObj = new JsonObject();
        charObj.addProperty("value", character.getValue());
        charObj.add("position", gson.toJsonTree(character.getPosition()));
        charObj.addProperty("authorId", character.getAuthorId());
        charObj.addProperty("timestamp", character.getTimestamp());
        message.add("character", charObj);
        
        webSocketClient.send(gson.toJson(message));
    }
    
    public void sendDelete(Position position) {
        if (!connected) {
            notifyErrorListeners("Not connected to server");
            return;
        }
        
        lastOperationTimes.put("delete", System.currentTimeMillis());
        
        JsonObject message = new JsonObject();
        message.addProperty("type", "delete");
        message.addProperty("userId", userId);
        message.add("position", gson.toJsonTree(position));
        
        System.out.println("Sending DELETE operation for position: " + position);
        
        webSocketClient.send(gson.toJson(message));
    }
    
    public void sendCursorMove(int position) {
        if (!connected) {
            return;
        }
        
        if (position == lastSentCursorPosition) {
            return;
        }
        
        long now = System.currentTimeMillis();
        long lastEditTime = Math.max(
            lastOperationTimes.getOrDefault("insert", 0L),
            lastOperationTimes.getOrDefault("delete", 0L)
        );
        
        if (now - lastEditTime < 100) {
            if (now - lastCursorMoveTime < CURSOR_MOVE_THROTTLE_MS * 2) {
                return;
            }
        }
        
        if (now - lastCursorMoveTime < CURSOR_MOVE_THROTTLE_MS) {
            if (cursorMoveScheduled) {
                lastSentCursorPosition = position;
                return;
            }
            
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
        
        sendCursorMoveNow(position);
    }
    
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
        
        try {
            webSocketClient.send(gson.toJson(message));
        } catch (Exception e) {
            System.err.println("Error sending cursor move: " + e.getMessage());
        }
    }
    
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
    
    public void joinSession(String code, boolean isEditor) {
        if (!connected) {
            System.err.println("Failed to join session: Not connected to server");
            notifyErrorListeners("Not connected to server");
            return;
        }
        
        try {
            JsonObject registerMsg = new JsonObject();
            registerMsg.addProperty("type", "register");
            registerMsg.addProperty("userId", userId);
            if (username != null && !username.isEmpty()) {
                registerMsg.addProperty("username", username);
            }
            webSocketClient.send(gson.toJson(registerMsg));
            
            System.out.println("=================================================");
            System.out.println("JOIN SESSION REQUEST:");
            System.out.println("Code: " + code);
            System.out.println("Role: " + (isEditor ? "EDITOR" : "VIEWER"));
            System.out.println("User ID: " + userId);
            System.out.println("=================================================");
            
            JsonObject joinMsg = new JsonObject();
            joinMsg.addProperty("type", "join_session");
            joinMsg.addProperty("code", code);
            joinMsg.addProperty("asEditor", isEditor);  
            joinMsg.addProperty("userId", userId);
            if (username != null && !username.isEmpty()) {
                joinMsg.addProperty("username", username);
            }
            
            webSocketClient.send(gson.toJson(joinMsg));
            System.out.println("Join request sent to server");
        } catch (Exception e) {
            System.err.println("Exception in joinSession: " + e.getMessage());
            e.printStackTrace();
            notifyErrorListeners("Error joining session: " + e.getMessage());
        }
    }
    
    private void handleServerMessage(String message) {
        try {
            JsonObject jsonMessage = gson.fromJson(message, JsonObject.class);
            String type = jsonMessage.get("type").getAsString();
            
            System.out.println("Received message from server: type=" + type);
            
            switch (type) {
                case "register_ack":
                    System.out.println("Registration acknowledged by server");
                    startSyncHistoryCleaner();
                    break;
                    
                case "create_session_ack":
                    System.out.println("Create session acknowledged by server");
                    
                    String editorCode, viewerCode;
                    
                    if (jsonMessage.has("editorCode") && jsonMessage.has("viewerCode")) {
                        editorCode = jsonMessage.get("editorCode").getAsString();
                        viewerCode = jsonMessage.get("viewerCode").getAsString();
                        System.out.println("Received distinct editor and viewer codes - Editor: " + editorCode + ", Viewer: " + viewerCode);
                    } else if (jsonMessage.has("sessionId")) {
                        editorCode = jsonMessage.get("sessionId").getAsString();
                        viewerCode = editorCode;
                        System.out.println("Received legacy session code: " + editorCode);
                    } else {
                        System.err.println("Invalid create_session_ack response - missing codes");
                        break;
                    }
                    
                    CodePair codePair = new CodePair(editorCode, viewerCode);
                    notifyCodeListeners(codePair);
                    break;
                    
                case "join_session_ack":
                    System.out.println("Join session acknowledged by server");
                    boolean asEditor = jsonMessage.has("asEditor") && jsonMessage.get("asEditor").getAsBoolean();
                    System.out.println("Joined as: " + (asEditor ? "EDITOR" : "VIEWER"));
                    
                    if (jsonMessage.has("editorCode")) {
                        String joinEditorCode = jsonMessage.get("editorCode").getAsString();
                        String joinViewerCode = jsonMessage.has("viewerCode") ? 
                            jsonMessage.get("viewerCode").getAsString() : joinEditorCode;
                        
                        System.out.println("Received session codes in join response:");
                        System.out.println("  Editor code: " + joinEditorCode);
                        System.out.println("  Viewer code: " + joinViewerCode);
                        
                        notifyCodeListeners(new CodePair(joinEditorCode, joinViewerCode));
                    }
                    
                    if (jsonMessage.has("documentContent")) {
                        String documentContent = jsonMessage.get("documentContent").getAsString();
                        System.out.println("Document content received: " + documentContent.length() + " characters");
                        
                        Operation syncOperation = new Operation(
                            Operation.Type.DOCUMENT_SYNC, 
                            null, 
                            null, 
                            userId, 
                            -1,
                            documentContent
                        );
                        
                        notifyOperationListeners(syncOperation);
                    } else {
                        System.out.println("No document content in join response - will need to request sync");
                    }
                    
                    if (jsonMessage.has("usernames")) {
                        try {
                            JsonObject usernamesObj = jsonMessage.getAsJsonObject("usernames");
                            Map<String, String> userMapFromServer = new HashMap<>();
                            
                            for (Map.Entry<String, com.google.gson.JsonElement> entry : usernamesObj.entrySet()) {
                                userMapFromServer.put(entry.getKey(), entry.getValue().getAsString());
                            }
                            
                            if (!userMapFromServer.isEmpty()) {
                                notifyPresenceListeners(userMapFromServer);
                                System.out.println("Received usernames for " + userMapFromServer.size() + " users");
                            }
                        } catch (Exception e) {
                            System.err.println("Error processing usernames: " + e.getMessage());
                        }
                    }
                    break;
                    
                case "session_joined":
                    boolean asEditorJoined = jsonMessage.get("asEditor").getAsBoolean();
                    String editorCodeJoined = jsonMessage.get("editorCode").getAsString();
                    String viewerCodeJoined = jsonMessage.get("viewerCode").getAsString();
                    
                    System.out.println("==================================================");
                    System.out.println("SESSION JOINED SUCCESSFULLY as " + (asEditorJoined ? "EDITOR" : "VIEWER"));
                    System.out.println("Editor code: " + editorCodeJoined);
                    System.out.println("Viewer code: " + viewerCodeJoined);
                    System.out.println("User ID: " + userId);
                    System.out.println("==================================================");
                    
                    notifyCodeListeners(new CodePair(editorCodeJoined, viewerCodeJoined));
                    
                    if (username != null && !username.isEmpty()) {
                        JsonObject usernameMessage = new JsonObject();
                        usernameMessage.addProperty("type", "update_username");
                        usernameMessage.addProperty("userId", userId);
                        usernameMessage.addProperty("username", username);
                        webSocketClient.send(gson.toJson(usernameMessage));
                        System.out.println("Sent username update: " + username);
                    }
                    break;
                    
                case "presence":
                    if (jsonMessage.has("users")) {
                        boolean highPriority = jsonMessage.has("highPriority") && 
                                               jsonMessage.get("highPriority").getAsBoolean();
                        
                        if (highPriority) {
                            System.out.println("Received HIGH PRIORITY presence update");
                        }
                        
                        if (jsonMessage.get("users").isJsonArray()) {
                            List<String> userIds = gson.fromJson(jsonMessage.get("users"), List.class);
                            
                            Map<String, String> userMap = new HashMap<>();
                            for (String id : userIds) {
                                if (id.equals(userId) && username != null) {
                                    userMap.put(id, username);
                                } else {
                                    userMap.put(id, id);
                                }
                            }
                            
                            if (highPriority) {
                                notifyPresenceListeners(userMap);
                            } else {
                                notifyPresenceListeners(userMap);
                            }
                        } else {
                            Map<String, String> userMap = gson.fromJson(jsonMessage.get("users"), Map.class);
                            
                            StringBuilder userMapStr = new StringBuilder();
                            for (Map.Entry<String, String> entry : userMap.entrySet()) {
                                userMapStr.append(entry.getKey()).append("->").append(entry.getValue()).append(", ");
                            }
                            System.out.println("Received user map: " + userMapStr.toString());
                            
                            if (highPriority) {
                                System.out.println("Forcing presence update due to high priority");
                                notifyPresenceListeners(userMap);
                            } else {
                                notifyPresenceListeners(userMap);
                            }
                        }
                    }
                    break;
                    
                case "update_username":
                    if (jsonMessage.has("userId") && jsonMessage.has("username")) {
                        String updatedUserId = jsonMessage.get("userId").getAsString();
                        String updatedUsername = jsonMessage.get("username").getAsString();
                        
                        Map<String, String> updateMap = new HashMap<>();
                        updateMap.put(updatedUserId, updatedUsername);
                        
                        notifyPresenceListeners(updateMap);
                    }
                    break;
                    
                case "username_updates":
                    if (jsonMessage.has("usernames")) {
                        try {
                            JsonObject usernamesObj = jsonMessage.getAsJsonObject("usernames");
                            Map<String, String> userMapFromServer = new HashMap<>();
                            
                            for (Map.Entry<String, com.google.gson.JsonElement> entry : usernamesObj.entrySet()) {
                                userMapFromServer.put(entry.getKey(), entry.getValue().getAsString());
                            }
                            
                            if (!userMapFromServer.isEmpty()) {
                                notifyPresenceListeners(userMapFromServer);
                                System.out.println("Received bulk username updates for " + userMapFromServer.size() + " users");
                            }
                        } catch (Exception e) {
                            System.err.println("Error processing username updates: " + e.getMessage());
                        }
                    }
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
                    int docLength = jsonMessage.get("documentLength").getAsInt();
                    System.out.println("Document sync confirmed - document length: " + docLength);
                    break;
                    
                case "error":
                    notifyErrorListeners(jsonMessage.get("message").getAsString());
                    break;
                    
                case "usernames":
                    Map<String, String> userMap = new HashMap<>();
                    JsonObject usernamesObj = jsonMessage.getAsJsonObject("usernames");
                    
                    if (usernamesObj != null) {
                        for (Map.Entry<String, com.google.gson.JsonElement> entry : usernamesObj.entrySet()) {
                            String id = entry.getKey();
                            String name = entry.getValue().getAsString();
                            userMap.put(id, name);
                        }
                        
                        System.out.println("Received usernames for " + userMap.size() + " users");
                        
                        notifyPresenceListeners(userMap);
                    }
                    break;
                    
                case "user_joined":
                    if (jsonMessage.has("userId") && jsonMessage.has("username")) {
                        String joinedUserId = jsonMessage.get("userId").getAsString();
                        String joinedUsername = jsonMessage.get("username").getAsString();
                        
                        System.out.println("⭐ IMPORTANT: Received notification that user joined: " + 
                                         joinedUsername + " (" + joinedUserId + ")");
                        
                        Map<String, String> joinUpdateMap = new HashMap<>();
                        
                        if (username != null && !username.isEmpty()) {
                            joinUpdateMap.put(userId, username);
                        }
                        
                        joinUpdateMap.put(joinedUserId, joinedUsername);
                        
                        notifyPresenceListeners(joinUpdateMap);
                        
                        JsonObject presenceRequest = new JsonObject();
                        presenceRequest.addProperty("type", "request_presence");
                        presenceRequest.addProperty("userId", userId);
                        webSocketClient.send(gson.toJson(presenceRequest));
                        
                        lastKnownCursorPositions.put(joinedUserId, -1);
                    }
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
            
            CRDTCharacter character = new CRDTCharacter(value, position, authorId, timestamp);
            
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
            
            lastKnownCursorPositions.put(sourceUserId, position);
            
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
            
            String syncId = "";
            if (message.has("timestamp")) {
                syncId = message.get("timestamp").getAsString();
            } else {
                syncId = content.hashCode() + "-" + System.currentTimeMillis();
            }
            
            synchronized (recentlySyncedDocuments) {
                if (recentlySyncedDocuments.contains(syncId)) {
                    System.out.println("Ignoring duplicate document sync: " + syncId);
                    return;
                }
                
                recentlySyncedDocuments.add(syncId);
                
                if (recentlySyncedDocuments.size() > 100) {
                    Iterator<String> iterator = recentlySyncedDocuments.iterator();
                    for (int i = 0; i < 50 && iterator.hasNext(); i++) {
                        iterator.next();
                        iterator.remove();
                    }
                }
            }
            
            System.out.println("Received document sync with " + content.length() + " characters");
            
            if (message.has("senderId") && message.get("senderId").getAsString().equals(userId)) {
                System.out.println("Ignoring document sync from our own user ID");
                return;
            }
            
            boolean highPriority = message.has("highPriority") && message.get("highPriority").getAsBoolean();
            if (highPriority) {
                System.out.println("HIGH PRIORITY document sync received");
            }
            
            Operation operation = new Operation(
                Operation.Type.DOCUMENT_SYNC, 
                null, 
                null, 
                message.has("senderId") ? message.get("senderId").getAsString() : userId, 
                -1, 
                content
            );
            
            notifyOperationListeners(operation);
            
            if (!message.has("senderId") || !message.get("senderId").getAsString().equals(userId)) {
                JsonObject confirmMsg = new JsonObject();
                confirmMsg.addProperty("type", "sync_confirmation");
                confirmMsg.addProperty("receivedLength", content.length());
                confirmMsg.addProperty("userId", userId);
                confirmMsg.addProperty("timestamp", System.currentTimeMillis());
                
                webSocketClient.send(gson.toJson(confirmMsg));
                System.out.println("Sent sync confirmation for " + content.length() + " characters");
            }
        } catch (Exception e) {
            System.err.println("Error processing document sync operation: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void handleSyncConfirmationRequest(JsonObject message) {
        int currentDocLength = -1;
        
        try {
            Operation getDocumentOperation = new Operation(Operation.Type.GET_DOCUMENT_LENGTH, null, null, userId, -1);
            
            for (Consumer<Operation> listener : operationListeners) {
                try {
                    listener.accept(getDocumentOperation);
                    currentDocLength = getDocumentOperation.getDocumentLength();
                    break;
                } catch (Exception e) {
                    System.err.println("Error getting document length: " + e.getMessage());
                }
            }
            
            JsonObject confirmMsg = new JsonObject();
            confirmMsg.addProperty("type", "sync_confirmation");
            confirmMsg.addProperty("receivedLength", currentDocLength);
            confirmMsg.addProperty("userId", userId);
            
            webSocketClient.send(gson.toJson(confirmMsg));
            
            System.out.println("Sent document sync confirmation with length: " + currentDocLength);
            
            int expectedLength = -1;
            if (message.has("expectedLength")) {
                expectedLength = message.get("expectedLength").getAsInt();
            }
            
            if ((expectedLength > 0 && currentDocLength <= 0) || 
                (expectedLength > 0 && expectedLength != currentDocLength)) {
                System.out.println("Length mismatch: local=" + currentDocLength + 
                                  ", expected=" + expectedLength + ". Requesting resync.");
                
                JsonObject resyncRequest = new JsonObject();
                resyncRequest.addProperty("type", "request_resync");
                resyncRequest.addProperty("userId", userId);
                webSocketClient.send(gson.toJson(resyncRequest));
                
                Operation requestResyncOperation = new Operation(Operation.Type.REQUEST_DOCUMENT_RESYNC, 
                                                              null, null, userId, -1);
                notifyOperationListeners(requestResyncOperation);
            }
        } catch (Exception e) {
            System.err.println("Error handling sync confirmation request: " + e.getMessage());
            e.printStackTrace();
            
            JsonObject errorMsg = new JsonObject();
            errorMsg.addProperty("type", "sync_confirmation");
            errorMsg.addProperty("receivedLength", -1);
            errorMsg.addProperty("error", e.getMessage());
            errorMsg.addProperty("userId", userId);
            
            webSocketClient.send(gson.toJson(errorMsg));
        }
    }
    
    public void addOperationListener(Consumer<Operation> listener) {
        operationListeners.add(listener);
    }
    
    public void addPresenceListener(Consumer<Map<String, String>> listener) {
        presenceListeners.add(listener);
    }
    
    public void addErrorListener(Consumer<String> listener) {
        errorListeners.add(listener);
    }
    
    public void addCodeListener(Consumer<CodePair> listener) {
        codeListeners.add(listener);
    }
    
    private void notifyOperationListeners(Operation operation) {
        final List<Consumer<Operation>> listenersCopy = new ArrayList<>(operationListeners);
        
        if (Platform.isFxApplicationThread()) {
            for (Consumer<Operation> listener : listenersCopy) {
                try {
                    listener.accept(operation);
                } catch (Exception e) {
                    System.err.println("Error in operation listener: " + e.getMessage());
                }
            }
        } else {
            Platform.runLater(() -> {
                for (Consumer<Operation> listener : listenersCopy) {
                    try {
                        listener.accept(operation);
                    } catch (Exception e) {
                        System.err.println("Error in operation listener: " + e.getMessage());
                    }
                }
            });
        }
    }
    
    private void notifyPresenceListeners(Map<String, String> userMap) {
        if (userMap == null) {
            return;
        }
        
        if (userMap.isEmpty() && !userMap.containsKey(userId)) {
            if (username != null && !username.isEmpty()) {
                userMap = new HashMap<>(userMap);
                userMap.put(userId, username);
            }
        }
        
        boolean hasOtherUsers = false;
        for (Map.Entry<String, String> entry : userMap.entrySet()) {
            if (!entry.getKey().equals(userId)) {
                hasOtherUsers = true;
                break;
            }
        }
        System.out.println("Notifying presence listeners with " + userMap.size() + 
                          " users" + (hasOtherUsers ? " including remote users" : " (only self)"));
        
        for (String remoteUserId : userMap.keySet()) {
            if (!remoteUserId.equals(userId) && !lastKnownCursorPositions.containsKey(remoteUserId)) {
                lastKnownCursorPositions.put(remoteUserId, -1);
            }
        }
        
        final List<Consumer<Map<String, String>>> listenersCopy = new ArrayList<>(presenceListeners);
        final Map<String, String> userMapCopy = new HashMap<>(userMap);
        
        if (Platform.isFxApplicationThread()) {
            for (Consumer<Map<String, String>> listener : listenersCopy) {
                try {
                    listener.accept(userMapCopy);
                } catch (Exception e) {
                    System.err.println("Error in presence listener: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } else {
            Platform.runLater(() -> {
                for (Consumer<Map<String, String>> listener : listenersCopy) {
                    try {
                        listener.accept(userMapCopy);
                    } catch (Exception e) {
                        System.err.println("Error in presence listener: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            });
        }
    }
    
    private void notifyErrorListeners(String error) {
        final List<Consumer<String>> listenersCopy = new ArrayList<>(errorListeners);
        final String errorMessage = error;
        
        if (Platform.isFxApplicationThread()) {
            for (Consumer<String> listener : listenersCopy) {
                try {
                    listener.accept(errorMessage);
                } catch (Exception e) {
                    System.err.println("Error in error listener: " + e.getMessage());
                }
            }
        } else {
            Platform.runLater(() -> {
                for (Consumer<String> listener : listenersCopy) {
                    try {
                        listener.accept(errorMessage);
                    } catch (Exception e) {
                        System.err.println("Error in error listener: " + e.getMessage());
                    }
                }
            });
        }
    }
    
    private void notifyCodeListeners(CodePair codePair) {
        final List<Consumer<CodePair>> listenersCopy = new ArrayList<>(codeListeners);
        final CodePair codePairCopy = codePair;
        
        if (Platform.isFxApplicationThread()) {
            for (Consumer<CodePair> listener : listenersCopy) {
                try {
                    listener.accept(codePairCopy);
                } catch (Exception e) {
                    System.err.println("Error in code listener: " + e.getMessage());
                }
            }
        } else {
            Platform.runLater(() -> {
                for (Consumer<CodePair> listener : listenersCopy) {
                    try {
                        listener.accept(codePairCopy);
                    } catch (Exception e) {
                        System.err.println("Error in code listener: " + e.getMessage());
                    }
                }
            });
        }
    }
    
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
    
    public void sendDocumentUpdate(String content) {
        if (!connected) {
            notifyErrorListeners("Not connected to server");
            return;
        }
        
        final String finalContent = (content == null) ? "" : content;
        
        long now = System.currentTimeMillis();
        long lastDocUpdateTime = lastOperationTimes.getOrDefault("document_update", 0L);
        
        if (now - lastDocUpdateTime < 1000) {
            System.out.println("Throttling document update - last update was " + (now - lastDocUpdateTime) + "ms ago");
            return;
        }
        
        lastOperationTimes.put("document_update", now);
        
        JsonObject message = new JsonObject();
        message.addProperty("type", "document_update");
        message.addProperty("userId", userId);
        message.addProperty("content", finalContent);
        message.addProperty("timestamp", now);
        
        message.addProperty("seq", System.currentTimeMillis());
        
        try {
            webSocketClient.send(gson.toJson(message));
            System.out.println("Sent document update with " + finalContent.length() + " chars");
        } catch (Exception e) {
            System.err.println("Error sending document update: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public void sendUndo(Operation operation) {
        if (!connected) {
            notifyErrorListeners("Not connected to server");
            return;
        }
        
        JsonObject message = new JsonObject();
        message.addProperty("type", "undo");
        message.addProperty("userId", userId);
        message.addProperty("username", username);
        
        if (operation.getType() == Operation.Type.INSERT) {
            message.add("position", gson.toJsonTree(operation.getCharacter().getPosition()));
        } else {
            JsonObject charObj = new JsonObject();
            charObj.addProperty("value", operation.getCharacter().getValue());
            charObj.add("position", gson.toJsonTree(operation.getCharacter().getPosition()));
            charObj.addProperty("authorId", operation.getCharacter().getAuthorId());
            charObj.addProperty("timestamp", operation.getCharacter().getTimestamp());
            message.add("character", charObj);
        }
        
        message.addProperty("operationType", operation.getType().toString());
        
        webSocketClient.send(gson.toJson(message));
    }
    
    public void sendRedo(Operation operation) {
        if (!connected) {
            notifyErrorListeners("Not connected to server");
            return;
        }
        
        JsonObject message = new JsonObject();
        message.addProperty("type", "redo");
        message.addProperty("userId", userId);
        message.addProperty("username", username);
        
        if (operation.getType() == Operation.Type.INSERT) {
            JsonObject charObj = new JsonObject();
            charObj.addProperty("value", operation.getCharacter().getValue());
            charObj.add("position", gson.toJsonTree(operation.getCharacter().getPosition()));
            charObj.addProperty("authorId", operation.getCharacter().getAuthorId());
            charObj.addProperty("timestamp", operation.getCharacter().getTimestamp());
            message.add("character", charObj);
        } else {
            message.add("position", gson.toJsonTree(operation.getCharacter().getPosition()));
        }
        
        message.addProperty("operationType", operation.getType().toString());
        
        webSocketClient.send(gson.toJson(message));
    }
    
    public void sendPresenceUpdate() {
        if (!connected) {
            return;
        }
        
        try {
            JsonObject message = new JsonObject();
            message.addProperty("type", "presence");
            message.addProperty("userId", userId);
            message.addProperty("username", username);
            message.addProperty("timestamp", System.currentTimeMillis());
            webSocketClient.send(gson.toJson(message));
        } catch (Exception e) {
            System.err.println("Error sending presence update: " + e.getMessage());
        }
    }
    
    public void addConnectionListener(Consumer<Boolean> listener) {
        connectionListeners.add(listener);
    }
    
    private void notifyConnectionListeners(boolean connected) {
        for (Consumer<Boolean> listener : connectionListeners) {
            listener.accept(connected);
        }
    }
    
    public Integer getLastKnownCursorPosition(String userId) {
        return lastKnownCursorPositions.get(userId);
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    public void setUsername(String username) {
        this.username = username;
    }
    
    public boolean isConnected() {
        return connected && webSocketClient != null && webSocketClient.isOpen();
    }
    
    private void startSyncHistoryCleaner() {
        new Timer(true).schedule(new TimerTask() {
            @Override
            public void run() {
                synchronized (recentlySyncedDocuments) {
                    recentlySyncedDocuments.clear();
                    System.out.println("Cleared sync history");
                }
            }
        }, 30000, 30000);
    }
    
    public void leaveSession() {
        if (!connected) {
            return;
        }
        
        try {
            JsonObject message = new JsonObject();
            message.addProperty("type", "leave_session");
            message.addProperty("userId", userId);
            webSocketClient.send(gson.toJson(message));
            System.out.println("Sent leave session message to server");
        } catch (Exception e) {
            System.err.println("Error sending leave session message: " + e.getMessage());
        }
    }
    
    private void purgeDisconnectedUsers() {
        lastKnownCursorPositions.clear();
        
        Map<String, String> emptyUserMap = new HashMap<>();
        
        if (username != null && !username.isEmpty()) {
            emptyUserMap.put(userId, username);
        }
        
        notifyPresenceListeners(emptyUserMap);
        
        System.out.println("Purged all disconnected users");
    }
}