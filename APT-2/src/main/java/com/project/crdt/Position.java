package com.project.crdt;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


public class Position implements Comparable<Position> {
    private final List<Identifier> identifiers;
    
    public Position(List<Identifier> identifiers) {
        this.identifiers = new ArrayList<>(identifiers);
    }
    
    public List<Identifier> getIdentifiers() {
        return new ArrayList<>(identifiers);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Position position = (Position) o;
        
        if (identifiers.size() != position.identifiers.size()) {
            return false;
        }
        
        for (int i = 0; i < identifiers.size(); i++) {
            if (!identifiers.get(i).equals(position.identifiers.get(i))) {
                return false;
            }
        }
        
        return true;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(identifiers);
    }
    
    @Override
    public int compareTo(Position other) {
        // Compare positions lexicographically
        int minLength = Math.min(this.identifiers.size(), other.identifiers.size());
        
        for (int i = 0; i < minLength; i++) {
            int comp = this.identifiers.get(i).compareTo(other.identifiers.get(i));
            if (comp != 0) {
                return comp;
            }
        }
        
        return Integer.compare(this.identifiers.size(), other.identifiers.size());
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < identifiers.size(); i++) {
            sb.append(identifiers.get(i));
            if (i < identifiers.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append("]");
        return sb.toString();
    }
} 