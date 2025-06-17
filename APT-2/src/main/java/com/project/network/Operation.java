package com.project.network;

import com.project.crdt.CRDTCharacter;
import com.project.crdt.Position;

public class Operation {

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
    private int documentLength = -1;

    public Operation(Type type, CRDTCharacter character, Position position, String userId, int cursorPosition) {
        this(type, character, position, userId, cursorPosition, null);
    }

    public Operation(Type type, CRDTCharacter character, Position position, String userId, int cursorPosition, String documentContent) {
        this.type = type;
        this.character = character;
        this.position = position;
        this.userId = userId;
        this.cursorPosition = cursorPosition;
        this.documentContent = documentContent;
    }

    public Type getType() {
        return type;
    }

    public CRDTCharacter getCharacter() {
        return character;
    }

    public Position getPosition() {
        return position;
    }

    public String getUserId() {
        return userId;
    }

    public int getCursorPosition() {
        return cursorPosition;
    }

    public String getDocumentContent() {
        return documentContent;
    }

    public void setDocumentLength(int length) {
        this.documentLength = length;
    }

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