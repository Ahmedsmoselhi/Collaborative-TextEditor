package com.project.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.project.crdt.CRDTCharacter;
import com.project.crdt.Position;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CollaborativeEditorServer extends WebSocketServer {
    
    private static final int DEFAULT_PORT = 8887;
    private final Gson gson = new Gson();
    
    // Map of connection to user ID
    private final Map<WebSocket, String> connectionToUserId = new ConcurrentHashMap<>();
    
    // Map of active sessions by code
    private final Map<String, EditorSession> sessionsByCode = new ConcurrentHashMap<>();
    
    // Map of user ID to session
    private final Map<String, EditorSession> userSessions = new ConcurrentHashMap<>();
    
    // Map of user ID to connection
    private final Map<String, WebSocket> userConnections = new ConcurrentHashMap<>();
    
    // Map of user ID to cursor position
    private final Map<String, Integer> userCursorPositions = new ConcurrentHashMap<>();
    
    public CollaborativeEditorServer() {
        super(new InetSocketAddress(DEFAULT_PORT));
    }
    
    public CollaborativeEditorServer(int port) {
        super(new InetSocketAddress(port));
    }
    
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("New connection from " + conn.getRemoteSocketAddress());
    }
    
    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String userId = connectionToUserId.get(conn);
        System.out.println("Connection closed for user " + userId);
        
        if (userId != null) {
            // Remove user from their session
            EditorSession session = userSessions.get(userId);
            if (session != null) {
                session.removeUser(userId);
                if (session.isEmpty()) {
                    // Remove the session if it's empty
                    sessionsByCode.remove(session.getEditorCode());
                    sessionsByCode.remove(session.getViewerCode());
                } else {
                    // Notify other users that this user has left
                    broadcastPresenceUpdate(session);
                    
                    // Also remove this user's cursor from others' view
                    userCursorPositions.remove(userId);
                    
                    // Broadcast cursor removal
                    JsonObject cursorRemoveMsg = new JsonObject();
                    cursorRemoveMsg.addProperty("type", "cursor_remove");
                    cursorRemoveMsg.addProperty("userId", userId);
                    
                    for (String otherUserId : session.getAllUsers()) {
                        WebSocket otherConn = userConnections.get(otherUserId);
                        if (otherConn != null && otherConn.isOpen()) {
                            otherConn.send(gson.toJson(cursorRemoveMsg));
                        }
                    }
                }
                userSessions.remove(userId);
            }
            
            userConnections.remove(userId);
            connectionToUserId.remove(conn);
        }
    }
    
    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JsonObject jsonMessage = gson.fromJson(message, JsonObject.class);
            String type = jsonMessage.get("type").getAsString();
            
            switch (type) {
                case "register":
                    handleRegister(conn, jsonMessage);
                    break;
                case "create_session":
                    handleCreateSession(conn, jsonMessage);
                    break;
                case "join_session":
                    handleJoinSession(conn, jsonMessage);
                    break;
                case "insert":
                    handleInsert(conn, jsonMessage);
                    break;
                case "delete":
                    handleDelete(conn, jsonMessage);
                    break;
                case "cursor_move":
                    handleCursorMove(conn, jsonMessage);
                    break;
                case "document_update":
                    handleDocumentUpdate(conn, jsonMessage);
                    break;
                case "sync_confirmation":
                    handleSyncConfirmation(conn, jsonMessage);
                    break;
                default:
                    System.out.println("Unknown message type: " + type);
            }
        } catch (Exception e) {
            System.err.println("Error processing message: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn != null) {
            System.err.println("Error occurred on connection " + conn.getRemoteSocketAddress() + ":" + ex.getMessage());
        } else {
            System.err.println("Server error occurred: " + ex.getMessage());
        }
        ex.printStackTrace();
    }
    
    @Override
    public void onStart() {
        System.out.println("WebSocket server started on port " + getPort());
    }
    
    private void handleRegister(WebSocket conn, JsonObject message) {
        String userId = message.get("userId").getAsString();
        connectionToUserId.put(conn, userId);
        userConnections.put(userId, conn);
        
        // Send confirmation
        JsonObject response = new JsonObject();
        response.addProperty("type", "register_ack");
        response.addProperty("userId", userId);
        conn.send(gson.toJson(response));
        
        System.out.println("Registered user: " + userId);
    }
    
    private void handleCreateSession(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        if (userId == null) {
            sendError(conn, "Not registered");
            return;
        }
        
        // Generate unique codes for editor and viewer
        String editorCode = generateUniqueCode("EDITOR");
        String viewerCode = generateUniqueCode("VIEWER");
        
        // Create a new session
        EditorSession session = new EditorSession(editorCode, viewerCode);
        session.addEditor(userId);
        
        // Store the session
        sessionsByCode.put(editorCode, session);
        sessionsByCode.put(viewerCode, session);
        userSessions.put(userId, session);
        
        // Send the codes to the user
        JsonObject response = new JsonObject();
        response.addProperty("type", "session_created");
        response.addProperty("editorCode", editorCode);
        response.addProperty("viewerCode", viewerCode);
        conn.send(gson.toJson(response));
        
        // Broadcast presence update
        broadcastPresenceUpdate(session);
        
        System.out.println("Created session: " + editorCode + " (editor), " + viewerCode + " (viewer)");
    }
    
    private void handleJoinSession(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        String sessionCode = message.get("code").getAsString();
        boolean asEditor = message.get("asEditor").getAsBoolean();
        
        if (userId == null) {
            sendError(conn, "Not registered");
            return;
        }
        
        // Find the session
        EditorSession session = sessionsByCode.get(sessionCode);
        if (session == null) {
            sendError(conn, "Invalid session code");
            return;
        }
        
        // Check if the user is allowed to join as an editor
        if (asEditor && !sessionCode.equals(session.getEditorCode())) {
            sendError(conn, "Not authorized to join as editor");
            return;
        }
        
        // Add the user to the session
        if (asEditor) {
            session.addEditor(userId);
        } else {
            session.addViewer(userId);
        }
        
        // Associate the user with the session
        userSessions.put(userId, session);
        
        // Send acknowledgment
        JsonObject response = new JsonObject();
        response.addProperty("type", "session_joined");
        response.addProperty("editorCode", session.getEditorCode());
        response.addProperty("viewerCode", session.getViewerCode());
        response.addProperty("asEditor", asEditor);
        conn.send(gson.toJson(response));
        
        // Broadcast presence update
        broadcastPresenceUpdate(session);
        
        // Send initial cursor positions to the new user
        for (String existingUserId : session.getAllUsers()) {
            if (!existingUserId.equals(userId) && userCursorPositions.containsKey(existingUserId)) {
                JsonObject cursorMsg = new JsonObject();
                cursorMsg.addProperty("type", "cursor_move");
                cursorMsg.addProperty("userId", existingUserId);
                cursorMsg.addProperty("position", userCursorPositions.get(existingUserId));
                conn.send(gson.toJson(cursorMsg));
            }
        }
        
        // Sync document content for the new user if there's already content
        if (session.getDocumentContent() != null && !session.getDocumentContent().isEmpty()) {
            // Use a separate thread to ensure reliable delivery with multiple attempts
            new Thread(() -> {
                try {
                    // First wait to ensure client is fully connected and ready
                    Thread.sleep(500);
                    
                    // Make up to 3 attempts to send the document content
                    boolean syncSuccessful = false;
                    int attempts = 0;
                    
                    while (!syncSuccessful && attempts < 3 && conn.isOpen()) {
                        attempts++;
                        
                        // Send document content
                        JsonObject syncMsg = new JsonObject();
                        syncMsg.addProperty("type", "document_sync");
                        syncMsg.addProperty("content", session.getDocumentContent());
                        syncMsg.addProperty("syncAttempt", attempts);
                        conn.send(gson.toJson(syncMsg));
                        
                        // Send confirmation request and wait for response
                        JsonObject confirmReqMsg = new JsonObject();
                        confirmReqMsg.addProperty("type", "sync_confirmation_request");
                        confirmReqMsg.addProperty("documentLength", session.getDocumentContent().length());
                        conn.send(gson.toJson(confirmReqMsg));
                        
                        // Wait for next attempt if needed
                        if (attempts < 3) {
                            Thread.sleep(1000);
                        }
                    }
                    
                    // Log sync attempt outcome
                    if (attempts >= 3) {
                        System.out.println("Warning: Document sync with user " + userId + " may not be complete after " + attempts + " attempts");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
        
        System.out.println("User " + userId + " joined session with code " + sessionCode + " as " + (asEditor ? "editor" : "viewer"));
    }
    
    private void handleInsert(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        if (userId == null) {
            sendError(conn, "Not registered");
            return;
        }
        
        EditorSession session = userSessions.get(userId);
        if (session == null) {
            sendError(conn, "Not in a session");
            return;
        }
        
        // Check if the user is an editor
        if (!session.isEditor(userId)) {
            sendError(conn, "Not authorized to edit");
            return;
        }
        
        // Forward the insert operation to all users in the session
        broadcastToSession(session, message, userId);
    }
    
    private void handleDelete(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        if (userId == null) {
            sendError(conn, "Not registered");
            return;
        }
        
        EditorSession session = userSessions.get(userId);
        if (session == null) {
            sendError(conn, "Not in a session");
            return;
        }
        
        // Check if the user is an editor
        if (!session.isEditor(userId)) {
            sendError(conn, "Not authorized to edit");
            return;
        }
        
        // Forward the delete operation to all users in the session
        broadcastToSession(session, message, userId);
    }
    
    private void handleCursorMove(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        int position = message.get("position").getAsInt();
        
        if (userId == null) {
            sendError(conn, "Not registered");
            return;
        }
        
        EditorSession session = userSessions.get(userId);
        if (session == null) {
            sendError(conn, "Not in a session");
            return;
        }
        
        // Store the cursor position
        userCursorPositions.put(userId, position);
        
        // Forward the cursor move to all users in the session except the sender
        // We'll optimize to send less traffic by omitting the userId
        // from the sender's own cursor move messages
        for (String otherUserId : session.getAllUsers()) {
            if (!otherUserId.equals(userId)) {
                WebSocket otherConn = userConnections.get(otherUserId);
                if (otherConn != null && otherConn.isOpen()) {
                    otherConn.send(gson.toJson(message));
                }
            }
        }
    }
    
    private void handleDocumentUpdate(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        
        if (userId == null) {
            sendError(conn, "Not registered");
            return;
        }
        
        EditorSession session = userSessions.get(userId);
        if (session == null) {
            sendError(conn, "Not in a session");
            return;
        }
        
        // Check if the user is an editor
        if (!session.isEditor(userId)) {
            sendError(conn, "Not authorized to edit");
            return;
        }
        
        // Update the session's document content
        String content = message.get("content").getAsString();
        session.setDocumentContent(content);
    }
    
    private void broadcastPresenceUpdate(EditorSession session) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "presence");
        message.add("users", gson.toJsonTree(session.getAllUsers()));
        
        for (String userId : session.getAllUsers()) {
            WebSocket conn = userConnections.get(userId);
            if (conn != null && conn.isOpen()) {
                conn.send(gson.toJson(message));
            }
        }
    }
    
    private void broadcastToSession(EditorSession session, JsonObject message, String excludeUserId) {
        for (String userId : session.getAllUsers()) {
            if (!userId.equals(excludeUserId)) {
                WebSocket conn = userConnections.get(userId);
                if (conn != null && conn.isOpen()) {
                    conn.send(gson.toJson(message));
                }
            }
        }
    }
    
    private void sendError(WebSocket conn, String errorMessage) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "error");
        message.addProperty("message", errorMessage);
        conn.send(gson.toJson(message));
    }
    
    private String generateUniqueCode(String prefix) {
        // Generate a simple 6-character code
        String code = prefix + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        
        // Make sure it's unique
        while (sessionsByCode.containsKey(code)) {
            code = prefix + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        }
        
        return code;
    }
    
    /**
     * Handles a sync confirmation message from a client.
     */
    private void handleSyncConfirmation(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        int receivedLength = message.get("receivedLength").getAsInt();
        
        if (userId == null) {
            return;
        }
        
        EditorSession session = userSessions.get(userId);
        if (session == null) {
            return;
        }
        
        // Check if the client has the correct document length
        int expectedLength = session.getDocumentContent().length();
        
        if (receivedLength == expectedLength) {
            System.out.println("Document sync confirmed for user " + userId + 
                " (length: " + receivedLength + ")");
        } else {
            System.out.println("Document sync mismatch for user " + userId + 
                " (received: " + receivedLength + ", expected: " + expectedLength + ")");
            
            // Re-send the document content if mismatch
            JsonObject syncMsg = new JsonObject();
            syncMsg.addProperty("type", "document_sync");
            syncMsg.addProperty("content", session.getDocumentContent());
            syncMsg.addProperty("syncRetry", true);
            conn.send(gson.toJson(syncMsg));
        }
    }
    
    /**
     * Class representing an editing session
     */
    private static class EditorSession {
        private final String editorCode;
        private final String viewerCode;
        private final Set<String> editors = new HashSet<>();
        private final Set<String> viewers = new HashSet<>();
        private String documentContent = "";
        
        public EditorSession(String editorCode, String viewerCode) {
            this.editorCode = editorCode;
            this.viewerCode = viewerCode;
        }
        
        public void addEditor(String userId) {
            editors.add(userId);
        }
        
        public void addViewer(String userId) {
            viewers.add(userId);
        }
        
        public void removeUser(String userId) {
            editors.remove(userId);
            viewers.remove(userId);
        }
        
        public boolean isEditor(String userId) {
            return editors.contains(userId);
        }
        
        public boolean isEmpty() {
            return editors.isEmpty() && viewers.isEmpty();
        }
        
        public Set<String> getAllUsers() {
            Set<String> allUsers = new HashSet<>(editors);
            allUsers.addAll(viewers);
            return allUsers;
        }
        
        public String getEditorCode() {
            return editorCode;
        }
        
        public String getViewerCode() {
            return viewerCode;
        }
        
        public String getDocumentContent() {
            return documentContent;
        }
        
        public void setDocumentContent(String content) {
            this.documentContent = content;
        }
    }
    
    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        
        // Check if a custom port was specified
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number. Using default port " + DEFAULT_PORT);
            }
        }
        
        CollaborativeEditorServer server = new CollaborativeEditorServer(port);
        server.start();
        System.out.println("Collaborative Editor Server started on port " + port);
    }
} 