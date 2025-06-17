package com.project.network;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.project.crdt.CRDTCharacter;
import com.project.crdt.Position;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CollaborativeEditorServer extends WebSocketServer {
    
    private static final int DEFAULT_PORT = 8887;
    private final Gson gson = new Gson();
    
    private final Map<WebSocket, String> connectionToUserId = new ConcurrentHashMap<>();
    
    private final Map<String, EditorSession> sessionsByCode = new ConcurrentHashMap<>();
    
    private final Map<String, EditorSession> userSessions = new ConcurrentHashMap<>();
    
    private final Map<String, WebSocket> userConnections = new ConcurrentHashMap<>();
    
    private final Map<String, Integer> userCursorPositions = new ConcurrentHashMap<>();
    
    private final Map<String, String> userMap = new ConcurrentHashMap<>();
    
    private final Map<String, String> usernames = new ConcurrentHashMap<>();
    
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
            EditorSession session = userSessions.get(userId);
            if (session != null) {
                session.removeUser(userId);
                
                System.out.println("Users remaining in session: " + session.getAllUsers());
                
                if (session.isEmpty()) {
                    sessionsByCode.remove(session.getEditorCode());
                    sessionsByCode.remove(session.getViewerCode());
                    System.out.println("Session removed as it's now empty");
                } else {
                    broadcastPresenceUpdate(session);
                    
                    userCursorPositions.remove(userId);
                    
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
            usernames.remove(userId);
            userCursorPositions.remove(userId);
            
            cleanupInactiveConnections();
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
                case "leave_session":
                    handleLeaveSession(conn, jsonMessage);
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
                case "instant_document_update":
                    handleInstantDocumentUpdate(conn, jsonMessage);
                    break;
                case "undo":
                    handleUndo(conn, jsonMessage);
                    break;
                case "redo":
                    handleRedo(conn, jsonMessage);
                    break;
                case "sync_confirmation":
                    handleSyncConfirmation(conn, jsonMessage);
                    break;
                case "request_resync":
                    handleResyncRequest(conn, jsonMessage);
                    break;
                case "update_username":
                case "username_update":
                    handleUpdateUsername(conn, jsonMessage);
                    break;
                case "presence":
                    handlePresenceUpdate(conn, jsonMessage);
                    break;
                case "request_presence":
                    handleRequestPresence(conn, jsonMessage);
                    break;
                default:
                    sendError(conn, "Unknown message type: " + type);
            }
        } catch (Exception e) {
            System.err.println("Error processing message: " + e.getMessage());
            e.printStackTrace();
            sendError(conn, "Error processing message: " + e.getMessage());
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
        
        WebSocket existingConn = userConnections.get(userId);
        if (existingConn != null && existingConn != conn && existingConn.isOpen()) {
            System.out.println("Found existing connection for user " + userId + ", closing it");
            try {
                JsonObject disconnectMsg = new JsonObject();
                disconnectMsg.addProperty("type", "force_disconnect");
                disconnectMsg.addProperty("reason", "New connection established");
                existingConn.send(gson.toJson(disconnectMsg));
                
                existingConn.close();
            } catch (Exception e) {
                System.err.println("Error closing existing connection: " + e.getMessage());
            }
        }
        
        String username = null;
        if (message.has("username")) {
            username = message.get("username").getAsString();
            usernames.put(userId, username);
        }
        
        connectionToUserId.put(conn, userId);
        userConnections.put(userId, conn);
        
        JsonObject response = new JsonObject();
        response.addProperty("type", "register_ack");
        response.addProperty("userId", userId);
        conn.send(gson.toJson(response));
    }
    
    private void handleCreateSession(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        
        if (userId == null) {
            sendError(conn, "Not registered");
            return;
        }
        
        String editorCode = UUID.randomUUID().toString().substring(0, 6);
        String viewerCode = UUID.randomUUID().toString().substring(0, 6);
        
        while (editorCode.equals(viewerCode)) {
            viewerCode = UUID.randomUUID().toString().substring(0, 6);
        }
        
        String documentTitle = message.has("title") ? message.get("title").getAsString() : "Untitled Document";
        
        EditorSession session = new EditorSession(editorCode, viewerCode);
        
        sessionsByCode.put(editorCode, session);
        sessionsByCode.put(viewerCode, session);
        
        session.addEditor(userId);
        userSessions.put(userId, session);
        
        System.out.println("Created session with codes - Editor: " + editorCode + ", Viewer: " + viewerCode);
        
        JsonObject response = new JsonObject();
        response.addProperty("type", "create_session_ack");
        response.addProperty("editorCode", editorCode);
        response.addProperty("viewerCode", viewerCode);
        response.addProperty("userId", userId);
        response.addProperty("documentTitle", documentTitle);
        
        conn.send(gson.toJson(response));
        
        broadcastPresenceUpdate(session);
    }
    
    private void handleJoinSession(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        if (userId == null) {
            sendError(conn, "Not registered");
            return;
        }
        
        String sessionCode;
        if (message.has("code")) {
            sessionCode = message.get("code").getAsString();
        } else if (message.has("sessionId")) {
            sessionCode = message.get("sessionId").getAsString();
        } else {
            sendError(conn, "Missing session identifier");
            return;
        }
        
        boolean requestingEditorRole = message.has("asEditor") && message.get("asEditor").getAsBoolean();
        
        System.out.println("=== JOIN SESSION REQUEST ===");
        System.out.println("User: " + userId);
        System.out.println("Code: " + sessionCode);
        System.out.println("Requesting role: " + (requestingEditorRole ? "EDITOR" : "VIEWER"));
        
        EditorSession currentSession = userSessions.get(userId);
        if (currentSession != null && 
            !currentSession.getEditorCode().equals(sessionCode) && 
            !currentSession.getViewerCode().equals(sessionCode)) {
            
            System.out.println("User is leaving previous session to join new one");
            currentSession.removeUser(userId);
            
            if (currentSession.isEmpty()) {
                sessionsByCode.remove(currentSession.getEditorCode());
                sessionsByCode.remove(currentSession.getViewerCode());
                System.out.println("Previous session removed as it's now empty");
            } else {
                broadcastPresenceUpdate(currentSession);
            }
        }
        
        EditorSession session = sessionsByCode.get(sessionCode);
        
        if (session == null) {
            System.out.println("Session not found, creating new session with code: " + sessionCode);
            
            String editorCode = sessionCode;
            String viewerCode = sessionCode;
            
            session = new EditorSession(editorCode, viewerCode);
            
            sessionsByCode.put(editorCode, session);
            
            if (!editorCode.equals(viewerCode)) {
                sessionsByCode.put(viewerCode, session);
            }
            
            session.addEditor(userId);
            userSessions.put(userId, session);
            
            sendJoinResponse(conn, userId, session, true);
            return;
        }
        
        boolean assignedEditorRole;
        
        if (requestingEditorRole) {
            if (sessionCode.equals(session.getEditorCode())) {
                assignedEditorRole = true;
                session.addEditor(userId);
                System.out.println("Granted EDITOR role (requested + using editor code)");
            } else {
                sendError(conn, "Cannot join as editor using viewer code");
                System.out.println("Denied EDITOR role (using viewer code)");
                return;
            }
        } else {
            assignedEditorRole = false;
            session.addViewer(userId);
            System.out.println("Granted VIEWER role (as requested)");
        }
        
        userSessions.put(userId, session);
        
        if (message.has("username") && !message.get("username").isJsonNull()) {
            String providedUsername = message.get("username").getAsString();
            if (providedUsername != null && !providedUsername.isEmpty()) {
                usernames.put(userId, providedUsername);
                System.out.println("User joining with username: " + providedUsername);
            }
        }
        
        sendJoinResponse(conn, userId, session, assignedEditorRole);
    }
    
    private void sendJoinResponse(WebSocket conn, String userId, EditorSession session, boolean isEditor) {
        JsonObject usernamesObject = new JsonObject();
        for (String user : session.getAllUsers()) {
            if (usernames.containsKey(user)) {
                usernamesObject.addProperty(user, usernames.get(user));
            } else {
                usernamesObject.addProperty(user, "User-" + user.substring(0, 4));
            }
        }
        
        JsonObject response = new JsonObject();
        response.addProperty("type", "join_session_ack");
        response.addProperty("userId", userId);
        response.addProperty("asEditor", isEditor);
        response.addProperty("editorCode", session.getEditorCode());
        response.addProperty("viewerCode", session.getViewerCode());
        response.addProperty("documentContent", session.getDocumentContent());
        response.add("usernames", usernamesObject);
        
        conn.send(gson.toJson(response));
        System.out.println("Sent join confirmation to user " + userId + " as " + (isEditor ? "EDITOR" : "VIEWER"));
        
        broadcastPresenceUpdate(session);
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
        
        if (!session.isEditor(userId)) {
            sendError(conn, "Not authorized to edit");
            return;
        }
        
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
        
        if (!session.isEditor(userId)) {
            sendError(conn, "Not authorized to edit");
            return;
        }
        
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
        
        Integer oldPosition = userCursorPositions.put(userId, position);
        
        if (oldPosition != null && Math.abs(oldPosition - position) < 5) {
            return;
        }
        
        Set<String> targetUsers = session.getAllUsers();
        if (targetUsers.size() > 1) {
            for (String otherUserId : targetUsers) {
            if (!otherUserId.equals(userId)) {
                WebSocket otherConn = userConnections.get(otherUserId);
                if (otherConn != null && otherConn.isOpen()) {
                        try {
                    otherConn.send(gson.toJson(message));
                        } catch (Exception e) {
                            System.err.println("Error sending cursor update to " + otherUserId + ": " + e.getMessage());
                        }
                    }
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
        
        if (!session.isEditor(userId)) {
            sendError(conn, "Not authorized to edit");
            return;
        }
        
        String content = message.get("content").getAsString();
        
        String currentContent = session.getDocument();
        if (content.equals(currentContent)) {
            System.out.println("Document update ignored - content unchanged");
            return;
        }
        
        session.updateDocument(content);
        System.out.println("Document updated by user " + userId + " (" + content.length() + " characters)");
        
        JsonObject broadcastMsg = new JsonObject();
        broadcastMsg.addProperty("type", "document_sync");
        broadcastMsg.addProperty("content", content);
        broadcastMsg.addProperty("senderId", userId);
        
        for (String user : session.getUsers()) {
            if (!user.equals(userId)) {
                WebSocket userConn = userConnections.get(user);
                if (userConn != null && userConn.isOpen()) {
                    try {
                        userConn.send(gson.toJson(broadcastMsg));
                    } catch (Exception e) {
                        System.err.println("Error sending document update to user " + user + ": " + e.getMessage());
                    }
                }
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
        String code = prefix + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        
        while (sessionsByCode.containsKey(code)) {
            code = prefix + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        }
        
        return code;
    }
    
    private void handleSyncConfirmation(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        if (userId == null) {
            sendError(conn, "Not registered");
            return;
        }
        
        int receivedLength = message.get("receivedLength").getAsInt();
        System.out.println("User " + userId + " confirmed document sync with " + receivedLength + " characters");
        
        EditorSession session = userSessions.get(userId);
        if (session == null) {
            System.out.println("Warning: Sync confirmation from user not in a session: " + userId);
            return;
        }
        
        String docContent = session.getDocument();
        if (docContent != null && docContent.length() != receivedLength) {
            System.out.println("Document length mismatch: server=" + docContent.length() + ", client=" + receivedLength);
            
            JsonObject syncMessage = new JsonObject();
            syncMessage.addProperty("type", "document_sync");
            syncMessage.addProperty("content", docContent);
            syncMessage.addProperty("highPriority", true);
            
            try {
                conn.send(gson.toJson(syncMessage));
                System.out.println("Sent corrective document sync to user " + userId);
            } catch (Exception e) {
                System.err.println("Error sending corrective sync: " + e.getMessage());
            }
        }
    }
    
    private void handleInstantDocumentUpdate(WebSocket conn, JsonObject message) {
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
        
        if (!session.isEditor(userId)) {
            sendError(conn, "Not authorized to edit");
            return;
        }
        
        String content = message.get("content").getAsString();
        
        String currentContent = session.getDocument();
        if (content.equals(currentContent)) {
            System.out.println("Ignoring duplicate document update with same content");
            return;
        }
        
        session.setDocumentContent(content);
        System.out.println("Instant document update from user " + userId + " (" + content.length() + " chars)");
        
        JsonObject forwardMsg = new JsonObject();
        forwardMsg.addProperty("type", "document_sync");
        forwardMsg.addProperty("content", content);
        forwardMsg.addProperty("highPriority", true);
        forwardMsg.addProperty("timestamp", System.currentTimeMillis());
        
        String operation = message.has("operation") ? message.get("operation").getAsString() : "";
        if (!operation.isEmpty()) {
            forwardMsg.addProperty("operation", operation);
        }
        
        for (String otherUserId : session.getAllUsers()) {
            if (!otherUserId.equals(userId)) {
                WebSocket otherConn = userConnections.get(otherUserId);
                if (otherConn != null && otherConn.isOpen()) {
                    otherConn.send(gson.toJson(forwardMsg));
                }
            }
        }
    }
    
    private void handleUndo(WebSocket conn, JsonObject message) {
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
        
        message.addProperty("forwardedByServer", true);
        
        for (String otherUserId : session.getAllUsers()) {
            if (!otherUserId.equals(userId)) {
                WebSocket otherConn = userConnections.get(otherUserId);
                if (otherConn != null && otherConn.isOpen()) {
                    otherConn.send(gson.toJson(message));
                }
            }
        }
    }
    
    private void handleRedo(WebSocket conn, JsonObject message) {
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
        
        message.addProperty("forwardedByServer", true);
        
        for (String otherUserId : session.getAllUsers()) {
            if (!otherUserId.equals(userId)) {
                WebSocket otherConn = userConnections.get(otherUserId);
                if (otherConn != null && otherConn.isOpen()) {
                    otherConn.send(gson.toJson(message));
                }
            }
        }
    }
    
    private void handleResyncRequest(WebSocket conn, JsonObject message) {
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
        
        System.out.println("Document resync requested by user " + userId);
        
        String docContent = session.getDocument();
        if (docContent != null) {
            JsonObject syncMessage = new JsonObject();
            syncMessage.addProperty("type", "document_sync");
            syncMessage.addProperty("content", docContent);
            syncMessage.addProperty("highPriority", true);
            
            try {
                conn.send(gson.toJson(syncMessage));
                System.out.println("Sent document resync to user " + userId + " (" + docContent.length() + " chars)");
            } catch (Exception e) {
                System.err.println("Error sending document resync: " + e.getMessage());
            }
        } else {
            System.out.println("No document content available for resync");
        }
    }
    
    private void handleUpdateUsername(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        
        if (userId == null) {
            sendError(conn, "Not registered");
            return;
        }
        
        if (!message.has("username")) {
            sendError(conn, "Username not provided");
            return;
        }
        
        String username = null;
        try {
            if (!message.get("username").isJsonNull()) {
                username = message.get("username").getAsString();
            }
        } catch (Exception e) {
            System.err.println("Error parsing username: " + e.getMessage());
            username = null;
        }
        
        if (username == null || username.trim().isEmpty()) {
            username = "User-" + userId.substring(0, Math.min(6, userId.length()));
        }
        
        usernames.put(userId, username);
        System.out.println("Updated username for user " + userId + " to: " + username);
        
        EditorSession session = userSessions.get(userId);
        if (session != null) {
            broadcastPresenceUpdate(session);
            broadcastUsernames(session);
        }
        
        JsonObject response = new JsonObject();
        response.addProperty("type", "username_update_ack");
        response.addProperty("status", "success");
        response.addProperty("username", username);
        conn.send(gson.toJson(response));
    }
    
    private void handlePresenceUpdate(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        
        if (userId == null) {
            return;
        }
        
        if (message.has("username") && !message.get("username").isJsonNull()) {
            String username = message.get("username").getAsString();
            if (username != null && !username.isEmpty()) {
                usernames.put(userId, username);
            }
        }
    }
    
    private void handleLeaveSession(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        
        if (userId == null) {
            sendError(conn, "Not registered");
            return;
        }
        
        EditorSession session = userSessions.get(userId);
        if (session == null) {
            return;
        }
        
        System.out.println("User " + userId + " is leaving session: " + session.getEditorCode());
        
        session.removeUser(userId);
        
        userSessions.remove(userId);
        
        JsonObject response = new JsonObject();
        response.addProperty("type", "leave_session_ack");
        response.addProperty("status", "success");
        conn.send(gson.toJson(response));
        
        if (session.isEmpty()) {
            sessionsByCode.remove(session.getEditorCode());
            sessionsByCode.remove(session.getViewerCode());
            System.out.println("Session removed as it's now empty");
        } else {
            broadcastPresenceUpdate(session);
            broadcastUsernames(session);
        }
        
        userCursorPositions.remove(userId);
        
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
    
    private void cleanupInactiveConnections() {
        try {
            Set<String> inactiveUsers = new HashSet<>();
            
            for (Map.Entry<String, WebSocket> entry : userConnections.entrySet()) {
                String userId = entry.getKey();
                WebSocket conn = entry.getValue();
                
                if (conn == null || !conn.isOpen()) {
                    inactiveUsers.add(userId);
                    System.out.println("Detected inactive connection for user: " + userId);
                }
            }
            
            for (String userId : userSessions.keySet()) {
                if (!userConnections.containsKey(userId) || !userConnections.get(userId).isOpen()) {
                    inactiveUsers.add(userId);
                    System.out.println("Detected orphaned user in session: " + userId);
                }
            }
            
            for (String userId : inactiveUsers) {
                System.out.println("Cleaning up inactive user: " + userId);
                
                EditorSession session = userSessions.get(userId);
                if (session != null) {
                    session.removeUser(userId);
                    
                    if (session.isEmpty()) {
                        sessionsByCode.remove(session.getEditorCode());
                        sessionsByCode.remove(session.getViewerCode());
                        System.out.println("Removed empty session during cleanup");
                    } else {
                        broadcastPresenceUpdate(session);
                        broadcastUsernames(session);
                    }
                }
                
                userSessions.remove(userId);
                userConnections.remove(userId);
                userCursorPositions.remove(userId);
                usernames.remove(userId);
                
                for (Map.Entry<WebSocket, String> entry : new HashMap<>(connectionToUserId).entrySet()) {
                    if (userId.equals(entry.getValue())) {
                        connectionToUserId.remove(entry.getKey());
                    }
                }
            }
            
            if (!inactiveUsers.isEmpty()) {
                System.out.println("Cleaned up " + inactiveUsers.size() + " inactive users");
                
                System.out.println("Remaining active users: " + userConnections.keySet());
            }
            
            List<String> orphanedSessions = new ArrayList<>();
            for (Map.Entry<String, EditorSession> entry : sessionsByCode.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    orphanedSessions.add(entry.getKey());
                }
            }
            
            for (String sessionCode : orphanedSessions) {
                sessionsByCode.remove(sessionCode);
                System.out.println("Removed orphaned session: " + sessionCode);
            }
            
        } catch (Exception e) {
            System.err.println("Error during connection cleanup: " + e.getMessage());
        }
    }
    
    private boolean isUserConnected(String userId) {
        WebSocket conn = userConnections.get(userId);
        if (conn == null || !conn.isOpen()) {
            return false;
        }
        
        try {
            conn.sendPing();
            return true;
        } catch (Exception e) {
            System.err.println("Error pinging user " + userId + ": " + e.getMessage());
            return false;
        }
    }
    
    private static class EditorSession {
        private Set<String> editors = new HashSet<>();
        private Set<String> viewers = new HashSet<>();
        private String editorCode;
        private String viewerCode;
        private String documentContent = "";
        private long lastActivityTime = System.currentTimeMillis();
        
        public EditorSession(String editorCode, String viewerCode) {
            this.editorCode = editorCode;
            this.viewerCode = viewerCode;
        }
        
        public String getEditorCode() {
            return editorCode;
        }
        
        public String getViewerCode() {
            return viewerCode;
        }
        
        public void addEditor(String userId) {
            editors.add(userId);
            updateActivity();
        }
        
        public void addViewer(String userId) {
            viewers.add(userId);
            updateActivity();
        }
        
        public void addUser(String userId) {
            editors.add(userId);
            updateActivity();
            System.out.println("Added user " + userId + " as editor");
        }
        
        public boolean isEditor(String userId) {
            return editors.contains(userId);
        }
        
        public Set<String> getEditors() {
            return editors;
        }
        
        public Set<String> getViewers() {
            return viewers;
        }
        
        public Set<String> getAllUsers() {
            Set<String> allUsers = new HashSet<>(editors);
            allUsers.addAll(viewers);
            return allUsers;
        }
        
        public void setDocumentContent(String content) {
            this.documentContent = content;
            updateActivity();
        }
        
        public String getDocumentContent() {
            return documentContent;
        }
        
        public String getDocument() {
            return documentContent;
        }
        
        public void updateDocument(String content) {
            this.documentContent = content;
            updateActivity();
        }
        
        public Set<String> getUsers() {
            Set<String> users = new HashSet<>(editors);
            users.addAll(viewers);
            return users;
        }
        
        public void removeUser(String userId) {
            editors.remove(userId);
            viewers.remove(userId);
            updateActivity();
        }
        
        public boolean isEmpty() {
            return editors.isEmpty() && viewers.isEmpty();
        }
        
        public void syncUserLists(Set<String> filteredUsers) {
            Set<String> newEditors = new HashSet<>();
            for (String userId : filteredUsers) {
                if (editors.contains(userId)) {
                    newEditors.add(userId);
                }
            }
            editors = newEditors;
            
            Set<String> newViewers = new HashSet<>();
            for (String userId : filteredUsers) {
                if (viewers.contains(userId)) {
                    newViewers.add(userId);
                }
            }
            viewers = newViewers;
            
            updateActivity();
        }
        
        private void updateActivity() {
            lastActivityTime = System.currentTimeMillis();
        }
        
        public long getLastActivityTime() {
            return lastActivityTime;
        }
        
        public boolean isInactive() {
            return System.currentTimeMillis() - lastActivityTime > 7200000;
        }
    }
    
    private void handleRequestPresence(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        
        if (userId == null) {
            return;
        }
        
        System.out.println("Received presence request from user: " + userId);
        
        EditorSession session = userSessions.get(userId);
        if (session == null) {
            return;
        }
        
        broadcastPresenceUpdate(session);
        broadcastUsernames(session);
    }
    
    private void broadcastPresenceUpdate(EditorSession session) {
        if (session == null || session.isEmpty()) {
            return;
        }
        
        JsonObject message = new JsonObject();
        message.addProperty("type", "presence");
        
        cleanupInactiveConnections();
        
        Map<String, String> userMap = new HashMap<>();
        
        Set<String> validUsers = new HashSet<>();
        for (String userId : session.getAllUsers()) {
            WebSocket conn = userConnections.get(userId);
            if (conn == null || !conn.isOpen()) {
                System.out.println("Skipping inactive user: " + userId);
                continue;
            }
            
            if (userId.contains("-")) {
                System.out.println("Skipping UUID-format user: " + userId);
                continue;
            }
            
            if (userId.length() > 24) {
                System.out.println("Skipping overly long user ID: " + userId);
                continue;
            }
            
            String username = usernames.get(userId);
            
            if (username == null || username.isEmpty()) {
                System.out.println("Skipping user with empty username: " + userId);
                continue;
            }
            
            if (username.contains("-") && username.length() > 20) {
                System.out.println("Skipping user with UUID-like username: " + username);
                continue;
            }
            
            validUsers.add(userId);
            
            if (username == null || username.isEmpty()) {
                username = "User " + userId.substring(0, Math.min(6, userId.length()));
            }
            
            userMap.put(userId, username);
        }
        
        System.out.println("Broadcasting presence update for session " + session.getEditorCode() + 
                           " with " + validUsers.size() + " valid users");
        
        message.add("users", gson.toJsonTree(userMap));
        
        JsonArray editorsArray = new JsonArray();
        for (String editor : session.getEditors()) {
            if (validUsers.contains(editor)) {
                editorsArray.add(editor);
            }
        }
        message.add("editors", editorsArray);
        
        JsonArray viewersArray = new JsonArray();
        for (String viewer : session.getViewers()) {
            if (validUsers.contains(viewer)) {
                viewersArray.add(viewer);
            }
        }
        message.add("viewers", viewersArray);
        
        for (String userId : validUsers) {
            WebSocket conn = userConnections.get(userId);
            if (conn != null && conn.isOpen()) {
                try {
                    conn.send(gson.toJson(message));
                } catch (Exception e) {
                    System.err.println("Error sending presence update to " + userId + ": " + e.getMessage());
                }
            }
        }
        
        session.syncUserLists(validUsers);
        
        System.out.println("Session " + session.getEditorCode() + " has users: " + userMap);
    }
    
    private void broadcastUsernames(EditorSession session) {
        if (session == null || session.isEmpty()) {
            return;
        }
        
        try {
            JsonObject message = new JsonObject();
            message.addProperty("type", "usernames");
            
            JsonObject usernamesObj = new JsonObject();
            
            for (String userId : session.getAllUsers()) {
                WebSocket conn = userConnections.get(userId);
                if (conn == null || !conn.isOpen()) {
                    continue;
                }
                
                String username = usernames.get(userId);
                if (username == null || username.isEmpty()) {
                    username = "User-" + userId.substring(0, Math.min(6, userId.length()));
                }
                
                if (username.startsWith("User-") && username.length() > 5) {
                    username = "User " + username.substring(5);
                }
                
                usernamesObj.addProperty(userId, username);
                System.out.println("Adding user to broadcast: " + userId + " -> " + username);
            }
            
            message.add("usernames", usernamesObj);
            
            if (usernamesObj.size() > 0) {
                String messageJson = gson.toJson(message);
                for (String userId : session.getAllUsers()) {
                    WebSocket conn = userConnections.get(userId);
                    if (conn != null && conn.isOpen()) {
                        conn.send(messageJson);
                    }
                }
                
                System.out.println("Broadcasted " + usernamesObj.size() + " usernames to session");
            } else {
                System.out.println("No valid users to broadcast usernames for");
            }
        } catch (Exception e) {
            System.err.println("Error broadcasting usernames: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        
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