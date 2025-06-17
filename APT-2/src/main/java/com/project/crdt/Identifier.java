package com.project.crdt;

import java.util.Objects;


public class Identifier implements Comparable<Identifier> {
    private final int position;
    private final String siteId;
    
    public Identifier(int position, String siteId) {
        this.position = position;
        this.siteId = siteId;
    }
    
    public int getPosition() {
        return position;
    }
    
    public String getSiteId() {
        return siteId;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Identifier that = (Identifier) o;
        return position == that.position && Objects.equals(siteId, that.siteId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(position, siteId);
    }
    
    @Override
    public int compareTo(Identifier other) {
        if (this.position != other.position) {
            return Integer.compare(this.position, other.position);
        }
        return this.siteId.compareTo(other.siteId);
    }
    
    @Override
    public String toString() {
        return position + ":" + siteId;
    }
} 