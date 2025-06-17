package com.project.crdt;

/**
 * Represents a character in the CRDT document.
 * Each character has a value and a position.
 */
public class CRDTCharacter implements Comparable<CRDTCharacter> {
    private final char value;
    private final Position position;
    private final String authorId;
    private final long timestamp;
    
    public CRDTCharacter(char value, Position position, String authorId, long timestamp) {
        this.value = value;
        this.position = position;
        this.authorId = authorId;
        this.timestamp = timestamp;
    }
    
    public char getValue() {
        return value;
    }
    
    public Position getPosition() {
        return position;
    }
    
    public String getAuthorId() {
        return authorId;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    @Override
    public int compareTo(CRDTCharacter other) {
        return this.position.compareTo(other.position);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        CRDTCharacter that = (CRDTCharacter) o;
        return position.equals(that.position);
    }
    
    @Override
    public int hashCode() {
        return position.hashCode();
    }
    
    @Override
    public String toString() {
        return "CRDTCharacter{" +
                "value=" + value +
                ", position=" + position +
                ", authorId='" + authorId + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
} 