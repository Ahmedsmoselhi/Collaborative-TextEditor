# Collaborative Plain Text Editor

A real-time collaborative text editor implemented in Java with JavaFX. This application allows multiple users to simultaneously edit the same document through shareable codes.

## Features

- **Real-time collaborative editing**: Multiple users can edit the same document simultaneously.
- **Character-based editing**: Each character is uniquely identified and tracked.
- **Cursor tracking**: See where other users are editing in real-time.
- **User presence**: See who is currently editing the document.
- **Permissions**: Two types of shareable codes - one for editors and one for read-only viewers.
- **Import/Export**: Import and export plain text files.
- **Undo/Redo**: Users can undo and redo their own changes (up to 3 operations).

## Requirements

- Java Development Kit (JDK) 17 or higher
- Maven 3.6 or higher

## How to Build and Run

1. Clone the repository:

   ```
   git clone <repository-url>
   cd collaborative-editor
   ```

2. Build the project using Maven:

   ```
   mvn clean package
   ```

3. Run the application:
   ```
   mvn javafx:run
   ```

## Usage

1. **Starting the application**: When you start the application, you'll see a text editor interface.

2. **Creating a document**:

   - Type directly in the text area
   - Or import a text file via File > Import File...

3. **Sharing a document**:

   - Click "Share" or select Collaboration > Generate Sharing Codes
   - Two codes will be generated:
     - Editor Code: Allows users to edit the document
     - Viewer Code: Allows users to view the document (read-only)

4. **Joining a session**:

   - Click "Join" or select Collaboration > Join Session
   - Enter the code you received
   - Choose whether to join as an editor or viewer (viewer role is enforced by the code)

5. **Collaborative editing**:

   - Type to add text
   - Press Backspace/Delete to remove text
   - You'll see other users' cursors in different colors
   - The list of active users is shown on the right side

6. **Exporting a document**:
   - Select File > Export File... to save the document as a text file

## Architecture

The application is built with the following components:

1. **UI Layer (JavaFX)**:

   - EditorController: Manages the user interface and interactions
   - CursorMarker: Visual representation of other users' cursors
   - Dialog components for interactions

2. **CRDT Implementation**:

   - CRDTDocument: Main document model using a tree-based CRDT
   - CRDTCharacter: A character in the document with position information
   - Position: A unique position in the document
   - Identifier: A component of a position in the tree

3. **Network Layer**:
   - NetworkClient: Handles communication with the server
   - Operation: Represents an operation (insert, delete, cursor move, presence)

## Understanding the CRDT

The Conflict-free Replicated Data Type (CRDT) used in this application is a tree-based implementation:

1. Each character has a unique position in the document
2. Positions are represented as paths in a tree
3. When inserting between characters, a new position is generated between the existing positions
4. This ensures that concurrent edits result in the same document state, regardless of the order of operations

## License

[MIT License](LICENSE)
