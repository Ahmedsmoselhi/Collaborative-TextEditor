package com.project.crdt;

import java.util.*;


public class CRDTDocument {
    private final String siteId;
    
    private final TreeSet<CRDTCharacter> characters;
    
    private int counter;
    
    private final Deque<Operation> history;
    private final Deque<Operation> redoStack;
    private static final int MAX_HISTORY_SIZE = 50;
    
    /**
     * @param siteId 
     */
    public CRDTDocument(String siteId) {
        this.siteId = siteId;
        this.characters = new TreeSet<>();
        this.counter = 0;
        this.history = new LinkedList<>();
        this.redoStack = new LinkedList<>();
    }
    
    /**
     * @param index The index to insert at.
     * @param c The character to insert.
     * @return The CRDT character that was inserted.
     */
    public CRDTCharacter localInsert(int index, char c) {
        Position position = generatePositionBetween(index);
        
        CRDTCharacter character = new CRDTCharacter(
                c, position, siteId, System.currentTimeMillis());
        
        characters.add(character);
        
        // Add to history
        Operation insertOperation = new Operation(OperationType.INSERT, character);
        addToHistory(insertOperation);
        
        return character;
    }
    
    /**
     * @param index The index to delete at.
     * @return The CRDT character that was deleted, or null if deletion was not possible.
     */
    public CRDTCharacter localDelete(int index) {
        // Safety checks
        if (characters.isEmpty()) {
            System.err.println("Cannot delete from empty document");
            return null;
        }
        
        if (index < 0 || index >= characters.size()) {
            System.err.println("Delete attempted with invalid index: " + index + 
                            " (document size: " + characters.size() + ")");
            return null;
        }
        
        try {
            CRDTCharacter character = getCharacterAtIndex(index);
            
            if (character != null) {
                characters.remove(character);
                
                // Add to history
                Operation deleteOperation = new Operation(OperationType.DELETE, character);
                addToHistory(deleteOperation);
                
                return character;
            } else {
                System.err.println("Character at index " + index + " is null, cannot delete");
                
                
                if (index >= 0 && index < characters.size()) {
                    // Try to get the character by iterating through the set
                    int i = 0;
                    for (CRDTCharacter c : characters) {
                        if (i == index) {
                            // Found the character, now remove it
                            characters.remove(c);
                            
                            // Add to history
                            Operation deleteOperation = new Operation(OperationType.DELETE, c);
                            addToHistory(deleteOperation);
                            
                            System.out.println("Successfully deleted character using fallback method");
                            return c;
                        }
                        i++;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error during deletion: " + e.getMessage());
            e.printStackTrace();
            
            try {
                if (index >= 0 && index < characters.size()) {
                    // Get an array of characters for safer access
                    CRDTCharacter[] charArray = characters.toArray(new CRDTCharacter[0]);
                    if (index < charArray.length) {
                        CRDTCharacter charToDelete = charArray[index];
                        if (charToDelete != null) {
                            characters.remove(charToDelete);
                            
                            Operation deleteOperation = new Operation(OperationType.DELETE, charToDelete);
                            addToHistory(deleteOperation);
                            
                            System.out.println("Successfully deleted character using array recovery method");
                            return charToDelete;
                        }
                    }
                }
            } catch (Exception ex) {
                System.err.println("Recovery attempt also failed: " + ex.getMessage());
            }
        }
        
        return null;
    }
    
    /**
     * @param character 
     */
    public void remoteInsert(CRDTCharacter character) {
        characters.add(character);
    }
    
    /**
     * @param position 
     */
    public void remoteDelete(Position position) {
        CRDTCharacter toRemove = null;
        
        for (CRDTCharacter character : characters) {
            if (character.getPosition().equals(position)) {
                toRemove = character;
                break;
            }
        }
        
        if (toRemove != null) {
            characters.remove(toRemove);
        }
    }
    
    /**
     * @param index The index.
     * @return 
     */
    private CRDTCharacter getCharacterAtIndex(int index) {
        if (characters.isEmpty() || index < 0 || index >= characters.size()) {
            return null;
        }
        
        int i = 0;
        for (CRDTCharacter character : characters) {
            if (i == index) {
                return character;
            }
            i++;
        }
        
        return null;
    }
    
    /**
     * @param index The index to insert at.
     * @return A new position.
     */
    private Position generatePositionBetween(int index) {
        if (characters.isEmpty()) {
            // If the document is empty, create a position in the middle
            List<Identifier> identifiers = new ArrayList<>();
            identifiers.add(new Identifier(32768, siteId)); // Start with a position in the middle (2^15)
            return new Position(identifiers);
        }
        
        if (index == 0) {
            CRDTCharacter firstChar = characters.first();
            List<Identifier> firstIdentifiers = firstChar.getPosition().getIdentifiers();
            
            List<Identifier> newIdentifiers = new ArrayList<>();
            int firstPos = firstIdentifiers.get(0).getPosition();
            if (firstPos > 0) {
                newIdentifiers.add(new Identifier(firstPos / 2, siteId));
            } else {
                newIdentifiers.add(new Identifier(0, siteId));
                newIdentifiers.add(new Identifier(32768, siteId));
            }
            
            return new Position(newIdentifiers);
        }
        
        if (index >= characters.size()) {
            CRDTCharacter lastChar = characters.last();
            List<Identifier> lastIdentifiers = lastChar.getPosition().getIdentifiers();
            
            List<Identifier> newIdentifiers = new ArrayList<>();
            int lastPos = lastIdentifiers.get(0).getPosition();
            newIdentifiers.add(new Identifier(lastPos + 1, siteId));
            
            return new Position(newIdentifiers);
        }
        
        CRDTCharacter prevChar = getCharacterAtIndex(index - 1);
        CRDTCharacter nextChar = getCharacterAtIndex(index);
        
        List<Identifier> prevIdentifiers = prevChar.getPosition().getIdentifiers();
        List<Identifier> nextIdentifiers = nextChar.getPosition().getIdentifiers();
        
        int prevPos = prevIdentifiers.get(0).getPosition();
        int nextPos = nextIdentifiers.get(0).getPosition();
        
        if (nextPos - prevPos > 1) {
            List<Identifier> newIdentifiers = new ArrayList<>();
            newIdentifiers.add(new Identifier(prevPos + (nextPos - prevPos) / 2, siteId));
            return new Position(newIdentifiers);
        }
        
        List<Identifier> newIdentifiers = new ArrayList<>(prevIdentifiers);
        newIdentifiers.add(new Identifier(counter++, siteId));
        
        return new Position(newIdentifiers);
    }
    
    /**
     * @return The text.
     */
    public String getText() {
        StringBuilder sb = new StringBuilder();
        for (CRDTCharacter character : characters) {
            sb.append(character.getValue());
        }
        return sb.toString();
    }
    
    /**
     * @param operation The operation to add.
     */
    private void addToHistory(Operation operation) {
        history.push(operation);
        if (history.size() > MAX_HISTORY_SIZE) {
            history.removeLast();
        }
        redoStack.clear(); 
    }
    
    /**
     * @return 
     */
    public boolean undo() {
        if (history.isEmpty()) {
            return false;
        }
        
        Operation lastOperation = history.pop();
        redoStack.push(lastOperation);
        
        if (lastOperation.getType() == OperationType.INSERT) {
            // Undo an insert by removing the character
            characters.remove(lastOperation.getCharacter());
        } else {
            // Undo a delete by adding the character back
            characters.add(lastOperation.getCharacter());
        }
        
        return true;
    }
    
    /**
     * @return 
     */
    public boolean redo() {
        if (redoStack.isEmpty()) {
            return false;
        }
        
        Operation lastUndoneOperation = redoStack.pop();
        history.push(lastUndoneOperation);
        
        if (lastUndoneOperation.getType() == OperationType.INSERT) {
            characters.add(lastUndoneOperation.getCharacter());
        } else {
            characters.remove(lastUndoneOperation.getCharacter());
        }
        
        return true;
    }
    
    /**
     * @return The site ID.
     */
    public String getSiteId() {
        return siteId;
    }
    
    /**
     * Operation type enum.
     */
    private enum OperationType {
        INSERT,
        DELETE
    }
    
    /**
     */
    private static class Operation {
        private final OperationType type;
        private final CRDTCharacter character;
        
        public Operation(OperationType type, CRDTCharacter character) {
            this.type = type;
            this.character = character;
        }
        
        public OperationType getType() {
            return type;
        }
        
        public CRDTCharacter getCharacter() {
            return character;
        }
    }
    
    /**
     * @return 
     */
    public Operation getLastOperation() {
        return history.isEmpty() ? null : history.peek();
    }
    
    /**
     * @return The last undone operation, or null if there are no undone operations.
     */
    public Operation getLastUndoneOperation() {
        return redoStack.isEmpty() ? null : redoStack.peek();
    }
    
    /**
     * @return The next operation to undo, or null if there are no operations to undo.
     */
    public com.project.network.Operation peekUndo() {
        if (history.isEmpty()) {
            return null;
        }
        
        Operation internalOp = history.peek();
        if (internalOp == null) {
            return null;
        }
        
        return new com.project.network.Operation(
            internalOp.getType() == OperationType.INSERT ? 
                com.project.network.Operation.Type.INSERT : 
                com.project.network.Operation.Type.DELETE,
            internalOp.getCharacter(),
            internalOp.getCharacter().getPosition(),
            this.siteId,
            -1
        );
    }
    
    /**
     * @return 
     */
    public com.project.network.Operation peekRedo() {
        if (redoStack.isEmpty()) {
            return null;
        }
        
        Operation internalOp = redoStack.peek();
        if (internalOp == null) {
            return null;
        }
        
        return new com.project.network.Operation(
            internalOp.getType() == OperationType.INSERT ? 
                com.project.network.Operation.Type.INSERT : 
                com.project.network.Operation.Type.DELETE,
            internalOp.getCharacter(),
            internalOp.getCharacter().getPosition(),
            this.siteId,
            -1
        );
    }
} 