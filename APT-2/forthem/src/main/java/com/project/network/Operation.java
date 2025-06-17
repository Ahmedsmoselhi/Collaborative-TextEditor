package com.project.network;

import com.project.crdt.CRDTCharacter;
import com.project.crdt.Position;

/**
 * Represents an operation in the collaborative editor.
 */
public class Operation {
    
    /**
     * The type of operation.
     */
    public enum Type {
        INSERT,
        DELETE,
        CURSOR_MOVE,
        PRESENCE,
        DOCUMENT_SYNC,
        GET_DOCUMENT_LENGTH,
        REQUEST_DOCUMENT_RESYNC
    }
    
    private final Type type;
    private final CRDTCharacter character;
    private final Position position;
    private final String userId;
    private final int cursorPosition;
    private final String documentContent;
    private int documentLength = -1; // For GET_DOCUMENT_LENGTH operation response
    
    /**
     * Creates a new operation.
     * @param type The operation type.
     * @param character The character for insert operations.
     * @param position The position for delete operations.
     * @param userId The user ID of the originator.
     * @param cursorPosition The cursor position for cursor move operations.
     */
    public Operation(Type type, CRDTCharacter character, Position position, String userId, int cursorPosition) {
        this(type, character, position, userId, cursorPosition, null);
    }
    
    /**
     * Creates a new operation with document content.
     * @param type The operation type.
     * @param character The character for insert operations.
     * @param position The position for delete operations.
     * @param userId The user ID of the originator.
     * @param cursorPosition The cursor position for cursor move operations.
     * @param documentContent The document content for sync operations.
     */
    public Operation(Type type, CRDTCharacter character, Position position, String userId, int cursorPosition, String documentContent) {
        this.type = type;
        this.character = character;
        this.position = position;
        this.userId = userId;
        this.cursorPosition = cursorPosition;
        this.documentContent = documentContent;
    }
    
    /**
     * Gets the operation type.
     * @return The operation type.
     */
    public Type getType() {
        return type;
    }
    
    /**
     * Gets the character for insert operations.
     * @return The character.
     */
    public CRDTCharacter getCharacter() {
        return character;
    }
    
    /**
     * Gets the position for delete operations.
     * @return The position.
     */
    public Position getPosition() {
        return position;
    }
    
    /**
     * Gets the user ID of the originator.
     * @return The user ID.
     */
    public String getUserId() {
        return userId;
    }
    
    /**
     * Gets the cursor position for cursor move operations.
     * @return The cursor position.
     */
    public int getCursorPosition() {
        return cursorPosition;
    }
    
    /**
     * Gets the document content for sync operations.
     * @return The document content.
     */
    public String getDocumentContent() {
        return documentContent;
    }
    
    /**
     * Sets the document length for GET_DOCUMENT_LENGTH operation responses.
     * @param length The document length.
     */
    public void setDocumentLength(int length) {
        this.documentLength = length;
    }
    
    /**
     * Gets the document length for GET_DOCUMENT_LENGTH operation responses.
     * @return The document length.
     */
    public int getDocumentLength() {
        return documentLength;
    }
    
    @Override
    public String toString() {
        switch (type) {
            case INSERT:
                return "INSERT operation by " + userId + ": " + character;
            case DELETE:
                return "DELETE operation by " + userId + ": " + position;
            case CURSOR_MOVE:
                return "CURSOR_MOVE operation by " + userId + " to position " + cursorPosition;
            case PRESENCE:
                return "PRESENCE operation by " + userId;
            case DOCUMENT_SYNC:
                return "DOCUMENT_SYNC operation by " + userId;
            case GET_DOCUMENT_LENGTH:
                return "GET_DOCUMENT_LENGTH operation by " + userId;
            case REQUEST_DOCUMENT_RESYNC:
                return "REQUEST_DOCUMENT_RESYNC operation by " + userId;
            default:
                return "Unknown operation type";
        }
    }
} 