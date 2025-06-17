# Collaborative Plain Text Editor - Detailed Documentation

This document provides a comprehensive explanation of the collaborative text editor implementation. It's designed for readers who may not be familiar with concepts like JavaFX, Spring Boot, WebSockets, or CRDTs.

## Table of Contents

1. [Project Overview](#project-overview)
2. [Technology Stack](#technology-stack)
3. [Project Structure](#project-structure)
4. [Core Concepts](#core-concepts)
5. [Implementation Details](#implementation-details)
6. [UI Implementation](#ui-implementation)
7. [CRDT Implementation](#crdt-implementation)
8. [Network Layer](#network-layer)
9. [Running the Application](#running-the-application)
10. [Possible Improvements](#possible-improvements)

## Project Overview

The Collaborative Plain Text Editor is a desktop application that allows multiple users to edit the same text document simultaneously. Changes made by one user are immediately visible to all other users, and the application ensures that all users see the same content regardless of network delays or the order in which changes are received.

Key features include:
- Real-time collaborative text editing
- User cursor tracking
- Presence awareness (seeing who's online)
- Role-based access (editors vs. viewers)
- File import/export
- Undo/Redo functionality

## Technology Stack

### Java
Java is a general-purpose programming language that runs on the Java Virtual Machine (JVM). It's widely used for building enterprise applications, Android apps, and desktop software. In our project, we use Java for all of the core logic.

### JavaFX
JavaFX is a set of graphics and media packages that enables developers to design, create, test, debug, and deploy rich client applications. It's the successor to Swing for building desktop applications with Java. We use JavaFX for our user interface.

### Maven
Maven is a build automation tool used primarily for Java projects. It helps manage dependencies (external libraries), compile code, run tests, and package the application. Our project uses Maven to handle these tasks and to make it easier to build and run the application.

### CRDT (Conflict-free Replicated Data Type)
A CRDT is a data structure that can be replicated across multiple computers in a network, where the replicas can be updated independently and concurrently without coordination, and it's always mathematically possible to resolve inconsistencies. We implement a tree-based CRDT for managing the text document.

## Project Structure

Our project follows a standard Maven project structure with additional organization for clarity:

```
collaborative-editor/
├── pom.xml                   # Maven configuration
├── README.md                 # Project documentation
├── DETAILED_DOCUMENTATION.md # This detailed documentation
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── project/
│   │   │           ├── Main.java                    # Application entry point
│   │   │           ├── crdt/                        # CRDT implementation
│   │   │           │   ├── Identifier.java          # Unique position identifier 
│   │   │           │   ├── Position.java            # Position in the document
│   │   │           │   ├── CRDTCharacter.java       # Character with position
│   │   │           │   └── CRDTDocument.java        # Document implementation
│   │   │           ├── network/                     # Network layer
│   │   │           │   ├── Operation.java           # Network operation
│   │   │           │   └── NetworkClient.java       # Client for communication
│   │   │           └── ui/                          # User interface
│   │   │               ├── EditorController.java    # Main UI controller
│   │   │               ├── CursorMarker.java        # Cursor visualization
│   │   │               └── JoinSessionDialog.java   # Dialog for joining sessions
│   │   └── resources/
│   │       ├── css/                                # CSS stylesheets
│   │       │   └── styles.css                      # Main stylesheet
│   │       └── fxml/                               # FXML layouts
│   │           └── editor.fxml                     # Main editor layout
│   └── test/                                       # Test code (not implemented)
└── target/                                         # Compiled output
```

## Core Concepts

### 1. Java and Object-Oriented Programming

Java is an object-oriented language, which means it organizes code into "objects" that contain data (fields) and code (methods). In our application:

- **Classes** define the blueprint for objects, like `CRDTDocument`, `EditorController`, etc.
- **Objects** are instances of classes, created at runtime
- **Inheritance** allows classes to inherit behavior from other classes
- **Interfaces** define contracts that classes can implement
- **Packages** organize related classes (like `com.project.crdt`, `com.project.ui`)

### 2. JavaFX UI Framework

JavaFX is a framework for building rich desktop applications. Key concepts include:

- **Stage**: The top-level window of the application
- **Scene**: The contents of a stage, containing UI elements
- **Nodes**: UI elements like buttons, text fields, etc.
- **Layouts**: Containers that arrange nodes in specific ways (like BorderPane, HBox, VBox)
- **FXML**: XML-based language for defining the UI structure
- **CSS**: Used for styling the UI elements
- **Controllers**: Java classes that handle the UI logic

### 3. CRDT (Conflict-free Replicated Data Type)

A CRDT is a data structure designed for concurrent editing. Our implementation uses a tree-based approach:

- Each character in the text has a unique position
- Positions are ordered lexicographically (like how words are ordered in a dictionary)
- When inserting between characters, we generate a new position that sorts between the existing positions
- This ensures that concurrent edits result in a consistent document state

### 4. Network Communication

In a collaborative application, network communication is essential:

- Users need to send their edits to other users
- Users need to receive edits from other users
- The application needs to handle network latency and lost messages
- Operations need to be idempotent (applying the same operation multiple times has the same effect as applying it once)

## Implementation Details

Now let's dive into the specific components of our application.

## UI Implementation

### Main.java

This is the entry point of our application. It:
1. Initializes the JavaFX application
2. Loads the FXML file that defines our UI
3. Sets up the primary stage (window)
4. Shows the application

```java
public class Main extends Application {
    @Override
    public void start(Stage primaryStage) {
        try {
            // Load the FXML file
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/editor.fxml"));
            Parent root = loader.load();
            
            // Set up the scene
            Scene scene = new Scene(root);
            
            // Set up the stage
            primaryStage.setTitle("Collaborative Text Editor");
            primaryStage.setScene(scene);
            primaryStage.show();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
```

### editor.fxml

This FXML file defines the layout of our application. It uses:
- **BorderPane**: A layout with five regions (top, right, bottom, left, center)
- **MenuBar**: For File, Edit, and Collaboration menus
- **ToolBar**: For quick access to common actions
- **SplitPane**: To divide the main area between the editor and the sidebar
- **AnchorPane**: To contain the editor and allow positioning of cursor markers
- **TextArea**: The main editing area
- **ListView**: To show the list of active users
- **GridPane**: To organize the sharing codes fields
- **HBox**: For the status bar at the bottom

### EditorController.java

This is the main controller for our UI. It:
1. Handles user interactions (button clicks, keyboard input)
2. Manages the CRDT document
3. Communicates with the network client
4. Updates the UI in response to local and remote changes
5. Manages cursor tracking

Key methods include:
- `initialize()`: Set up the UI components and listeners
- `setupNetworkListeners()`: Set up listeners for network events
- `setupEditorListeners()`: Set up listeners for editor events
- `handleImportFile()`, `handleExportFile()`: Import/export text files
- `handleUndo()`, `handleRedo()`: Undo/redo operations
- `handleJoinSession()`: Join a collaboration session
- `updateCursorMarkers()`: Update the visual cursor markers for other users

### CursorMarker.java

This class represents the visual cursor of another user in the editor. It:
1. Creates a colored triangle cursor shape
2. Shows the user ID next to the cursor
3. Provides methods to get/set the cursor position

### JoinSessionDialog.java

This is a dialog for joining a collaboration session. It:
1. Shows a text field for entering the session code
2. Provides radio buttons for selecting the role (editor or viewer)
3. Returns the code and role when the user clicks "Join"

## CRDT Implementation

### Identifier.java

This class represents a unique position identifier in the CRDT. It:
1. Stores a position value and a site ID
2. Implements `Comparable` to allow sorting of identifiers
3. Implements `equals` and `hashCode` for comparison
4. Provides a string representation for debugging

```java
public class Identifier implements Comparable<Identifier> {
    private final int position;
    private final String siteId;
    
    // Constructor and getters...
    
    @Override
    public int compareTo(Identifier other) {
        // Compare positions first, then site IDs
        if (this.position != other.position) {
            return Integer.compare(this.position, other.position);
        }
        return this.siteId.compareTo(other.siteId);
    }
    
    // equals, hashCode, toString...
}
```

### Position.java

This class represents a position in the document, as a sequence of identifiers. It:
1. Stores a list of identifiers that form a path in the tree
2. Implements `Comparable` to allow sorting of positions
3. Implements `equals` and `hashCode` for comparison
4. Provides a string representation for debugging

```java
public class Position implements Comparable<Position> {
    private final List<Identifier> identifiers;
    
    // Constructor and getters...
    
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
        
        // If one position is a prefix of the other, the shorter one comes first
        return Integer.compare(this.identifiers.size(), other.identifiers.size());
    }
    
    // equals, hashCode, toString...
}
```

### CRDTCharacter.java

This class represents a character in the document. It:
1. Stores the character value, position, author ID, and timestamp
2. Implements `Comparable` to allow sorting by position
3. Provides accessors for all properties
4. Implements `equals` and `hashCode` for position-based comparison

```java
public class CRDTCharacter implements Comparable<CRDTCharacter> {
    private final char value;
    private final Position position;
    private final String authorId;
    private final long timestamp;
    
    // Constructor and getters...
    
    @Override
    public int compareTo(CRDTCharacter other) {
        return this.position.compareTo(other.position);
    }
    
    // equals, hashCode, toString...
}
```

### CRDTDocument.java

This is the main CRDT implementation. It:
1. Stores all characters in a sorted set (TreeSet)
2. Provides methods for local and remote insertions and deletions
3. Implements undo/redo functionality
4. Generates positions for new characters
5. Converts the CRDT to a string for display

Key methods include:
- `localInsert(int index, char c)`: Insert a character locally
- `localDelete(int index)`: Delete a character locally
- `remoteInsert(CRDTCharacter character)`: Apply a remote insertion
- `remoteDelete(Position position)`: Apply a remote deletion
- `generatePositionBetween(int index)`: Generate a position for a new character
- `undo()`, `redo()`: Undo/redo operations
- `getText()`: Get the current document text

## Network Layer

### Operation.java

This class represents an operation that can be sent between users. It:
1. Defines operation types (INSERT, DELETE, CURSOR_MOVE, PRESENCE)
2. Stores the operation type, user ID, and relevant data
3. Provides constructors for different operation types
4. Provides accessors for all properties

```java
public class Operation {
    public enum Type {
        INSERT,
        DELETE,
        CURSOR_MOVE,
        PRESENCE
    }
    
    private final Type type;
    private final String userId;
    private final CRDTCharacter character;  // For INSERT
    private final Position position;        // For DELETE
    private final int cursorPosition;       // For CURSOR_MOVE
    private final boolean isActive;         // For PRESENCE
    
    // Constructors for different operation types...
    
    // Getters...
    
    @Override
    public String toString() {
        // String representation for debugging
    }
}
```

### NetworkClient.java

This class handles communication with the server. In our implementation, it:
1. Simulates a connection to a server
2. Provides methods to send operations
3. Notifies listeners of incoming operations
4. Manages presence information
5. Handles sharing codes

For a real implementation, this would be replaced with actual network code, likely using WebSockets.

Key methods include:
- `connect()`: Connect to the server
- `disconnect()`: Disconnect from the server
- `sendInsert()`, `sendDelete()`, `sendCursorMove()`: Send operations
- `requestCodes()`: Request shareable codes
- `joinSession()`: Join a session using a code
- `addOperationListener()`, etc.: Add listeners for events

## Running the Application

To run the application:
1. Make sure you have JDK 17+ and Maven installed
2. Clone the repository
3. Run `mvn clean package` to build the application
4. Run `mvn javafx:run` to start the application

## Possible Improvements

1. **Real Network Implementation**:
   - Implement a real server using WebSockets
   - Add proper error handling for network issues
   - Support reconnection after network drops

2. **Enhanced UI**:
   - Add syntax highlighting for code editing
   - Implement line numbers
   - Add search and replace functionality
   - Improve cursor tracking with selection highlighting

3. **Additional Features**:
   - Add user authentication
   - Implement document persistence
   - Add chat functionality
   - Support for comments and annotations

4. **Performance Optimizations**:
   - Optimize the CRDT for large documents
   - Implement batching for network operations
   - Add compression for network traffic

5. **Testing**:
   - Add unit tests for all components
   - Add integration tests
   - Add performance tests 