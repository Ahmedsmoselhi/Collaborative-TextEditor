package com.project.ui;

import javafx.geometry.Bounds;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.text.Text;
import javafx.animation.FadeTransition;
import javafx.util.Duration;
import javafx.geometry.Point2D;

/**
 * A visual marker that represents another user's cursor in the editor.
 */
public class CursorMarker {
    private final Pane parent;
    private final String userId;
    private final Color color;
    private final TextArea textArea;
    
    // Visual elements
    private Line cursorLine;
    private Text usernameLabel;
    
    // Cursor position
    private int position;
    
    // Size constants
    private static final double CURSOR_HEIGHT = 16.0;
    private static final double CURSOR_WIDTH = 2.5; // Increased width for better visibility
    private static final double LABEL_OFFSET_Y = -16.0;
    
    private final FadeTransition blinkAnimation;
    
    // Add listeners for scroll events
    private javafx.beans.value.ChangeListener<Number> scrollListener;
    
    /**
     * Creates a new cursor marker.
     * @param parent The parent pane.
     * @param userId The user ID associated with this cursor.
     * @param color The color of the cursor.
     * @param textArea The text area this cursor is tracking.
     */
    public CursorMarker(Pane parent, String userId, Color color, TextArea textArea) {
        this.parent = parent;
        this.userId = userId;
        this.color = color;
        this.textArea = textArea;
        this.position = 0;
        
        createVisualElements();
        
        // Add a blinking animation to make the cursor more noticeable but still subtle
        blinkAnimation = new FadeTransition(Duration.millis(800), cursorLine);
        blinkAnimation.setFromValue(1.0); // Full opacity
        blinkAnimation.setToValue(0.4);
        blinkAnimation.setCycleCount(FadeTransition.INDEFINITE);
        blinkAnimation.setAutoReverse(true);
        blinkAnimation.play();
        
        // Add listeners for text area scrolling to update cursor positions
        setupScrollListeners();
    }
    
    /**
     * Sets up listeners to update cursor positions when text area scrolls
     */
    private void setupScrollListeners() {
        // Create a listener that updates cursor visuals on scroll
        scrollListener = (observable, oldValue, newValue) -> {
            updateVisuals();
        };
        
        // Add the listener to both vertical and horizontal scrollbars
        textArea.scrollTopProperty().addListener(scrollListener);
        textArea.scrollLeftProperty().addListener(scrollListener);
    }
    
    /**
     * Creates the visual elements for this cursor marker.
     */
    private void createVisualElements() {
        // Create a line for the cursor
        cursorLine = new Line();
        cursorLine.setStroke(color);
        cursorLine.setStrokeWidth(CURSOR_WIDTH);
        cursorLine.setVisible(false); // Hide initially
        cursorLine.setOpacity(0.9); // Slightly transparent but clearly visible
        
        // Create a label for the user ID
        usernameLabel = new Text(userId);
        usernameLabel.setFill(color);
        usernameLabel.setOpacity(0.9); // Matching opacity
        usernameLabel.setVisible(false); // Hide initially
        
        // Add the elements to the parent pane
        parent.getChildren().addAll(cursorLine, usernameLabel);
    }
    
    /**
     * Updates the cursor position.
     * @param newPosition The new position for the cursor.
     */
    public void updatePosition(int newPosition) {
        // Update the position
        this.position = newPosition;
        
        // Update the visual representation
        updateVisuals();
    }
    
    /**
     * Updates the visual representation of the cursor.
     */
    public void updateVisuals() {
        try {
            // Make sure the position is valid
            int textLength = textArea.getText().length();
            int safePosition = Math.min(position, textLength);
            
            // Get points for cursor position
            Point2D cursorPoint = getCursorPoint(safePosition);
            if (cursorPoint != null) {
                double x = cursorPoint.getX();
                double y = cursorPoint.getY();
                
                // Make sure cursor is within visible area
                if (y >= 0 && y <= textArea.getHeight() && 
                    x >= 0 && x <= textArea.getWidth()) {
                    
                    // Position the cursor line
                    cursorLine.setStartX(x);
                    cursorLine.setStartY(y);
                    cursorLine.setEndX(x);
                    cursorLine.setEndY(y + CURSOR_HEIGHT);
                    
                    // Position the username label above the cursor
                    usernameLabel.setLayoutX(x);
                    usernameLabel.setLayoutY(y + LABEL_OFFSET_Y);
                    
                    // Make sure the elements are visible
                    cursorLine.setVisible(true);
                    usernameLabel.setVisible(true);
                    return;
                }
            }
            
            // If we couldn't position cursor or it's out of view, hide it
            cursorLine.setVisible(false);
            usernameLabel.setVisible(false);
        } catch (Exception e) {
            System.err.println("Error updating cursor visuals: " + e.getMessage());
            // If there's an error, hide the cursor
            cursorLine.setVisible(false);
            usernameLabel.setVisible(false);
        }
    }
    
    /**
     * Gets cursor position point for a specific character position.
     * @param position The character position.
     * @return The point, or null if it couldn't be determined.
     */
    private Point2D getCursorPoint(int position) {
        try {
            String text = textArea.getText();
            if (text.isEmpty() || position < 0) {
                // For empty text or invalid position, position at top-left
                return new Point2D(5, 5);
            }
            
            // Ensure position is within bounds
            int safePosition = Math.min(position, text.length());
            
            // Calculate line and column for position
            int line = 0;
            int lineStartPos = 0;
            
            // Find which line contains our position
            for (int i = 0; i < safePosition; i++) {
                if (i < text.length() && text.charAt(i) == '\n') {
                    line++;
                    lineStartPos = i + 1;
                }
            }
            
            // Calculate column (chars from start of current line)
            int column = safePosition - lineStartPos;
            
            // Create a temporary text node to calculate position
            Text tempText = new Text();
            tempText.setFont(textArea.getFont());
            
            // Get the line content up to our position
            String lineContent = "";
            int nextNewline = text.indexOf('\n', lineStartPos);
            if (nextNewline == -1) {
                // Last line
                lineContent = text.substring(lineStartPos);
            } else {
                lineContent = text.substring(lineStartPos, nextNewline);
            }
            
            // Get column content (for width calculation)
            String columnContent = column > 0 ? lineContent.substring(0, Math.min(column, lineContent.length())) : "";
            tempText.setText(columnContent);
            
            // Calculate text width and height
            double charWidth = tempText.getBoundsInLocal().getWidth() / Math.max(1, columnContent.length());
            double lineHeight = new Text("X").getBoundsInLocal().getHeight() * 1.2; // Line height
            
            // Calculate position
            double x = 5.0 + (column * charWidth); // Add padding
            double y = 5.0 + (line * lineHeight);  // Add padding
            
            // Adjust for scroll position
            x -= textArea.getScrollLeft();
            y -= textArea.getScrollTop();
            
            return new Point2D(x, y);
        } catch (Exception e) {
            System.err.println("Error calculating cursor position: " + e.getMessage());
            // Default to top-left corner if calculation fails
            return new Point2D(5, 5);
        }
    }
    
    /**
     * Removes this cursor marker from its parent.
     */
    public void remove() {
        // Remove scroll listeners
        if (scrollListener != null) {
            textArea.scrollTopProperty().removeListener(scrollListener);
            textArea.scrollLeftProperty().removeListener(scrollListener);
        }
        
        // Remove visual elements
        parent.getChildren().removeAll(cursorLine, usernameLabel);
    }
    
    /**
     * Gets the user ID associated with this cursor marker.
     * @return The user ID.
     */
    public String getUserId() {
        return userId;
    }
    
    /**
     * Gets the cursor color.
     * @return The cursor color.
     */
    public Color getColor() {
        return color;
    }
    
    /**
     * Gets the cursor position.
     * @return The cursor position.
     */
    public int getPosition() {
        return position;
    }
    
    /**
     * Sets the cursor position.
     * @param position The new position.
     */
    public void setPosition(int position) {
        this.position = position;
    }
    
    /**
     * Sets the cursor height.
     * @param height The new height.
     */
    public void setCursorHeight(double height) {
        cursorLine.setEndY(cursorLine.getStartY() + height);
    }
    
    /**
     * Stops the cursor animation when the marker is no longer needed.
     */
    public void dispose() {
        blinkAnimation.stop();
        remove();
    }
} 