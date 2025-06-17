package com.project.network;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.mindrot.jbcrypt.BCrypt;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DatabaseService {
    private static final String CONNECTION_STRING = "mongodb+srv://youssefshafik04:1gaifqXHyXhxccv2@cluster0.fsx0whh.mongodb.net/?retryWrites=true&w=majority&connectTimeoutMS=5000&serverSelectionTimeoutMS=5000";
    private static final String DATABASE_NAME = "collaborative_editor";
    private static final String USERS_COLLECTION = "users";
    private static final String DOCUMENTS_COLLECTION = "documents";
    private boolean mongoDbConnected = false;
    private MongoClient mongoClient;
    private MongoDatabase database;
    private MongoCollection<Document> usersCollection;
    private MongoCollection<Document> documentsCollection;
    private Map<String, User> userMap = new HashMap<>();
    private Map<String, InMemoryDocument> documentMap = new HashMap<>();
    private boolean useInMemoryStorage = false;
    private static DatabaseService instance;

    public static synchronized DatabaseService getInstance() {
        if (instance == null) {
            instance = new DatabaseService();
        }
        return instance;
    }

    private DatabaseService() {
        try {
            System.out.println("==================================================");
            System.out.println("Attempting to connect to MongoDB Atlas...");
            System.out.println("Using connection string: " + CONNECTION_STRING.replaceAll(":[^/]+@", ":******@"));
            mongoClient = MongoClients.create(CONNECTION_STRING);
            database = mongoClient.getDatabase(DATABASE_NAME);
            database.runCommand(new Document("ping", 1));
            usersCollection = database.getCollection(USERS_COLLECTION);
            documentsCollection = database.getCollection(DOCUMENTS_COLLECTION);
            long userCount = usersCollection.countDocuments();
            long docCount = documentsCollection.countDocuments();
            System.out.println("==================================================");
            System.out.println("MongoDB connection successful!");
            System.out.println("Users collection: " + userCount + " documents");
            System.out.println("Documents collection: " + docCount + " documents");
            System.out.println("Your data will be saved persistently to MongoDB");
            System.out.println("==================================================");
            if (!collectionExists(USERS_COLLECTION)) {
                database.createCollection(USERS_COLLECTION);
                System.out.println("Created users collection");
            }
            if (!collectionExists(DOCUMENTS_COLLECTION)) {
                database.createCollection(DOCUMENTS_COLLECTION);
                System.out.println("Created documents collection");
            }
            useInMemoryStorage = false;
            mongoDbConnected = true;
        } catch (Exception e) {
            System.err.println("==================================================");
            System.err.println("ERROR: Failed to connect to MongoDB Atlas!");
            System.err.println("Error message: " + e.getMessage());
            System.err.println("IMPORTANT: FALLING BACK TO IN-MEMORY STORAGE!");
            System.err.println("WARNING: Your data will NOT be saved permanently!");
            System.err.println("==================================================");
            e.printStackTrace();
            if (mongoClient != null) {
                try {
                    mongoClient.close();
                } catch (Exception ex) {
                }
                mongoClient = null;
            }
            useInMemoryStorage = true;
            mongoDbConnected = false;
            createDemoUser();
        }
    }

    private boolean collectionExists(String collectionName) {
        for (String name : database.listCollectionNames()) {
            if (name.equals(collectionName)) {
                return true;
            }
        }
        return false;
    }

    private void createDemoUser() {
        if (!useInMemoryStorage) {
            return;
        }
        String userId = UUID.randomUUID().toString();
        String username = "demo";
        String hashedPassword = BCrypt.hashpw("password", BCrypt.gensalt());
        User user = new User(userId, username, hashedPassword, new Date());
        userMap.put(userId, user);
        System.out.println("Created demo user. Username: 'demo', Password: 'password'");
        String documentId = createDocumentInMemory("Welcome Document", userId);
        InMemoryDocument document = documentMap.get(documentId);
        document.content = "Welcome to the collaborative editor!\n\nThis is a sample document created for demonstration purposes.";
    }

    public boolean registerUser(String username, String password) {
        if (username == null || username.trim().isEmpty() || password == null || password.isEmpty()) {
            System.err.println("Cannot register user: Username or password is empty");
            return false;
        }
        username = username.trim();
        if (useInMemoryStorage) {
            System.out.println("Using in-memory storage for user registration: " + username);
            return registerUserInMemory(username, password);
        }
        try {
            System.out.println("Attempting to register user in MongoDB: " + username);
            if (!mongoDbConnected) {
                System.err.println("MongoDB not connected, falling back to in-memory storage");
                return registerUserInMemory(username, password);
            }
            Document existingUser = usersCollection.find(Filters.eq("username", username)).first();
            if (existingUser != null) {
                System.out.println("Username already exists: " + username);
                return false;
            }
            String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());
            Document newUser = new Document()
                    .append("username", username)
                    .append("password", hashedPassword)
                    .append("createdAt", new Date())
                    .append("lastLogin", new Date());
            usersCollection.insertOne(newUser);
            Document confirmUser = usersCollection.find(Filters.eq("username", username)).first();
            if (confirmUser != null) {
                System.out.println("Successfully registered user in MongoDB: " + username);
                return true;
            } else {
                System.err.println("User registration verification failed: " + username);
                return registerUserInMemory(username, password);
            }
        } catch (Exception e) {
            System.err.println("Error registering user in MongoDB: " + e.getMessage());
            e.printStackTrace();
            return registerUserInMemory(username, password);
        }
    }

    private boolean registerUserInMemory(String username, String password) {
        if (userMap.values().stream().anyMatch(u -> u.username.equals(username))) {
            return false;
        }
        String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());
        String userId = UUID.randomUUID().toString();
        User user = new User(userId, username, hashedPassword, new Date());
        userMap.put(userId, user);
        System.out.println("Successfully registered user in memory: " + username + " with ID: " + userId);
        return true;
    }

    public String authenticateUser(String username, String password) {
        if (username == null || username.trim().isEmpty() || password == null || password.isEmpty()) {
            System.err.println("Cannot authenticate: Username or password is empty");
            return null;
        }
        username = username.trim();
        if (useInMemoryStorage) {
            System.out.println("Using in-memory storage for authentication: " + username);
            return authenticateUserInMemory(username, password);
        }
        try {
            System.out.println("Attempting to authenticate user in MongoDB: " + username);
            if (!mongoDbConnected) {
                System.err.println("MongoDB not connected, falling back to in-memory authentication");
                return authenticateUserInMemory(username, password);
            }
            Document user = usersCollection.find(Filters.eq("username", username)).first();
            if (user == null) {
                System.out.println("User not found in MongoDB: " + username);
                return authenticateUserInMemory(username, password);
            }
            String hashedPassword = user.getString("password");
            if (BCrypt.checkpw(password, hashedPassword)) {
                Object idObj = user.get("_id");
                String userId;
                if (idObj instanceof ObjectId) {
                    userId = ((ObjectId) idObj).toString();
                } else {
                    userId = idObj.toString();
                }
                usersCollection.updateOne(
                    Filters.eq("_id", user.get("_id")), 
                    Updates.set("lastLogin", new Date())
                );
                System.out.println("Successfully authenticated user in MongoDB: " + username + " with ID: " + userId);
                return userId;
            } else {
                System.out.println("Invalid password for user: " + username);
            }
            return null;
        } catch (Exception e) {
            System.err.println("Error authenticating user in MongoDB: " + e.getMessage());
            e.printStackTrace();
            return authenticateUserInMemory(username, password);
        }
    }

    private String authenticateUserInMemory(String username, String password) {
        for (Map.Entry<String, User> entry : userMap.entrySet()) {
            if (entry.getValue().username.equals(username)) {
                if (BCrypt.checkpw(password, entry.getValue().hashedPassword)) {
                    entry.getValue().lastLogin = new Date();
                    System.out.println("Successfully authenticated user in memory: " + username + " with ID: " + entry.getKey());
                    return entry.getKey();
                }
                return null;
            }
        }
        if (username.equals("demo") || userMap.isEmpty()) {
            String userId = UUID.randomUUID().toString();
            String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());
            User user = new User(userId, username, hashedPassword, new Date());
            userMap.put(userId, user);
            System.out.println("Auto-created and authenticated user in memory: " + username + " with ID: " + userId);
            return userId;
        }
        return null;
    }

    public String createDocument(String title, String ownerId) {
        if (useInMemoryStorage) {
            return createDocumentInMemory(title, ownerId);
        }
        try {
            Document newDocument = new Document()
                    .append("title", title)
                    .append("ownerId", ownerId)
                    .append("content", "")
                    .append("createdAt", new Date())
                    .append("updatedAt", new Date());
            documentsCollection.insertOne(newDocument);
            Object idObj = newDocument.get("_id");
            if (idObj instanceof ObjectId) {
                return ((ObjectId) idObj).toString();
            } else {
                return idObj.toString();
            }
        } catch (Exception e) {
            System.err.println("Error creating document: " + e.getMessage());
            e.printStackTrace();
            return createDocumentInMemory(title, ownerId);
        }
    }

    private String createDocumentInMemory(String title, String ownerId) {
        String documentId = UUID.randomUUID().toString();
        InMemoryDocument document = new InMemoryDocument(documentId, title, ownerId, "", new Date(), new Date());
        documentMap.put(documentId, document);
        return documentId;
    }

    public boolean updateDocument(String documentId, String content) {
        if (useInMemoryStorage) {
            return updateDocumentInMemory(documentId, content);
        }
        try {
            Object idToQuery;
            try {
                idToQuery = new ObjectId(documentId);
            } catch (Exception e) {
                System.out.println("Using string ID instead of ObjectId: " + documentId);
                idToQuery = documentId;
            }
            Bson filter = Filters.eq("_id", idToQuery);
            Bson update = Updates.combine(
                    Updates.set("content", content),
                    Updates.set("updatedAt", new Date())
            );
            documentsCollection.updateOne(filter, update);
            return true;
        } catch (Exception e) {
            System.err.println("Error updating document: " + e.getMessage());
            e.printStackTrace();
            return updateDocumentInMemory(documentId, content);
        }
    }

    private boolean updateDocumentInMemory(String documentId, String content) {
        InMemoryDocument document = documentMap.get(documentId);
        if (document != null) {
            document.content = content;
            document.updatedAt = new Date();
            return true;
        }
        return false;
    }

    public boolean updateDocumentWithSession(String documentId, String content, 
                                          String editorCode, String viewerCode) {
        if (useInMemoryStorage) {
            return updateDocumentWithSessionInMemory(documentId, content, editorCode, viewerCode);
        }
        try {
            if (!mongoDbConnected) {
                System.out.println("MongoDB not connected, falling back to in-memory storage");
                return updateDocumentWithSessionInMemory(documentId, content, editorCode, viewerCode);
            }
            Object documentObjectId;
            try {
                documentObjectId = new ObjectId(documentId);
            } catch (Exception e) {
                System.out.println("Invalid ObjectId format, using string ID: " + documentId);
                documentObjectId = documentId;
            }
            Document update = new Document();
            if (content != null) {
                update.append("content", content);
            }
            update.append("editorCode", editorCode)
                  .append("viewerCode", viewerCode)
                  .append("updatedAt", new Date());
            try {
                documentsCollection.updateOne(
                    Filters.eq("_id", documentObjectId),
                    new Document("$set", update));
                return true;
            } catch (IllegalStateException e) {
                System.out.println("MongoDB connection state error: " + e.getMessage());
                mongoDbConnected = false;
                if (attemptReconnect()) {
                    try {
                        documentsCollection.updateOne(
                            Filters.eq("_id", documentObjectId),
                            new Document("$set", update));
                        return true;
                    } catch (Exception e2) {
                        System.err.println("Update failed even after reconnect: " + e2.getMessage());
                        return updateDocumentWithSessionInMemory(documentId, content, editorCode, viewerCode);
                    }
                } else {
                    return updateDocumentWithSessionInMemory(documentId, content, editorCode, viewerCode);
                }
            } catch (Exception e) {
                System.err.println("Error updating document: " + e.getMessage());
                return updateDocumentWithSessionInMemory(documentId, content, editorCode, viewerCode);
            }
        } catch (Exception e) {
            System.err.println("Error updating document with session: " + e.getMessage());
            return updateDocumentWithSessionInMemory(documentId, content, editorCode, viewerCode);
        }
    }

    private synchronized boolean attemptReconnect() {
        if (useInMemoryStorage) {
            return false;
        }
        System.out.println("Attempting to reconnect to MongoDB...");
        try {
            if (mongoClient != null) {
                try {
                    mongoClient.close();
                } catch (Exception e) {
                }
                mongoClient = null;
            }
            mongoClient = MongoClients.create(
                CONNECTION_STRING + "&connectTimeoutMS=2000&serverSelectionTimeoutMS=2000");
            database = mongoClient.getDatabase(DATABASE_NAME);
            database.runCommand(new Document("ping", 1));
            System.out.println("Successfully reconnected to MongoDB");
            usersCollection = database.getCollection(USERS_COLLECTION);
            documentsCollection = database.getCollection(DOCUMENTS_COLLECTION);
            mongoDbConnected = true;
            return true;
        } catch (Exception e) {
            System.err.println("Failed to reconnect to MongoDB: " + e.getMessage());
            if (!useInMemoryStorage) {
                System.out.println("Permanently switching to in-memory storage after repeated connection failures");
                useInMemoryStorage = true;
                if (userMap.isEmpty()) {
                    createDemoUser();
                }
            }
            return false;
        }
    }

    public Document getDocument(String documentId) {
        if (documentId == null || documentId.isEmpty()) {
            System.err.println("Cannot get document: Document ID is null or empty");
            return null;
        }
        System.out.println("Retrieving document with ID: " + documentId);
        if (useInMemoryStorage) {
            System.out.println("Using in-memory storage to retrieve document");
            Document doc = getDocumentInMemory(documentId);
            if (doc != null) {
                System.out.println("Found document in memory with ID: " + documentId);
                System.out.println("Title: " + doc.getString("title"));
                System.out.println("Content length: " + (doc.getString("content") != null ? doc.getString("content").length() : 0) + " characters");
            } else {
                System.out.println("Document not found in memory: " + documentId);
            }
            return doc;
        }
        try {
            if (!mongoDbConnected || database == null) {
                System.err.println("MongoDB not connected for document retrieval, falling back to in-memory");
                return getDocumentInMemory(documentId);
            }
            System.out.println("Looking up document in MongoDB with ID: " + documentId);
            Object idToQuery;
            try {
                idToQuery = new ObjectId(documentId);
                System.out.println("Using ObjectId format: " + idToQuery);
            } catch (Exception e) {
                System.out.println("Using string ID format: " + documentId);
                idToQuery = documentId;
            }
            Document doc = documentsCollection.find(Filters.eq("_id", idToQuery)).first();
            if (doc != null) {
                System.out.println("Document found in MongoDB: " + documentId);
                System.out.println("Title: " + doc.getString("title"));
                System.out.println("Content length: " + (doc.getString("content") != null ? doc.getString("content").length() : 0) + " characters");
                if (doc.getString("content") == null) {
                    doc.append("content", "");
                }
            } else {
                System.err.println("Document not found in MongoDB: " + documentId);
            }
            return doc;
        } catch (Exception e) {
            System.err.println("Error getting document from MongoDB: " + e.getMessage());
            e.printStackTrace();
            System.out.println("Falling back to in-memory storage due to error");
            return getDocumentInMemory(documentId);
        }
    }

    private Document getDocumentInMemory(String documentId) {
        InMemoryDocument inMemoryDoc = documentMap.get(documentId);
        if (inMemoryDoc != null) {
            Document doc = new Document();
            doc.append("_id", inMemoryDoc.id);
            doc.append("title", inMemoryDoc.title);
            doc.append("ownerId", inMemoryDoc.ownerId);
            doc.append("content", inMemoryDoc.content);
            doc.append("editorCode", inMemoryDoc.editorCode);
            doc.append("viewerCode", inMemoryDoc.viewerCode);
            doc.append("createdAt", inMemoryDoc.createdAt);
            doc.append("updatedAt", inMemoryDoc.updatedAt);
            return doc;
        }
        return null;
    }

    public List<Document> getDocumentsByOwner(String ownerId) {
        if (useInMemoryStorage) {
            System.out.println("Using in-memory storage to retrieve documents for user: " + ownerId);
            return getDocumentsByOwnerInMemory(ownerId);
        }
        List<Document> documents = new ArrayList<>();
        try {
            System.out.println("Retrieving documents from MongoDB for user: " + ownerId);
            if (!mongoDbConnected || database == null) {
                System.err.println("MongoDB not connected for document retrieval, using in-memory");
                return getDocumentsByOwnerInMemory(ownerId);
            }
            try {
                documentsCollection.createIndex(Filters.eq("ownerId", 1));
            } catch (Exception e) {
                System.out.println("Note: Could not create index on ownerId: " + e.getMessage());
            }
            documentsCollection.find(Filters.eq("ownerId", ownerId))
                    .forEach(documents::add);
            System.out.println("Retrieved " + documents.size() + " documents from MongoDB for user: " + ownerId);
            if (documents.isEmpty()) {
                System.out.println("No documents found for user, creating default document");
                String docId = createDocument("Untitled Document", ownerId);
                Document newDoc = getDocument(docId);
                if (newDoc != null) {
                    documents.add(newDoc);
                    System.out.println("Created default document with ID: " + docId);
                }
            }
            return documents;
        } catch (Exception e) {
            System.err.println("Error getting documents from MongoDB: " + e.getMessage());
            e.printStackTrace();
            return getDocumentsByOwnerInMemory(ownerId);
        }
    }

    private List<Document> getDocumentsByOwnerInMemory(String ownerId) {
        List<Document> documents = new ArrayList<>();
        for (InMemoryDocument inMemoryDoc : documentMap.values()) {
            if (inMemoryDoc.ownerId.equals(ownerId)) {
                Document doc = new Document();
                doc.append("_id", inMemoryDoc.id);
                doc.append("title", inMemoryDoc.title);
                doc.append("ownerId", inMemoryDoc.ownerId);
                doc.append("content", inMemoryDoc.content);
                doc.append("editorCode", inMemoryDoc.editorCode);
                doc.append("viewerCode", inMemoryDoc.viewerCode);
                doc.append("createdAt", inMemoryDoc.createdAt);
                doc.append("updatedAt", inMemoryDoc.updatedAt);
                documents.add(doc);
            }
        }
        if (documents.isEmpty()) {
            String docId = createDocumentInMemory("Untitled Document", ownerId);
            InMemoryDocument inMemoryDoc = documentMap.get(docId);
            Document doc = new Document();
            doc.append("_id", inMemoryDoc.id);
            doc.append("title", inMemoryDoc.title);
            doc.append("ownerId", inMemoryDoc.ownerId);
            doc.append("content", inMemoryDoc.content);
            doc.append("editorCode", inMemoryDoc.editorCode);
            doc.append("viewerCode", inMemoryDoc.viewerCode);
            doc.append("createdAt", inMemoryDoc.createdAt);
            doc.append("updatedAt", inMemoryDoc.updatedAt);
            documents.add(doc);
        }
        return documents;
    }

    public List<Document> getDocumentsBySessionCode(String sessionCode) {
        if (useInMemoryStorage) {
            return getDocumentsBySessionCodeInMemory(sessionCode);
        }
        List<Document> documents = new ArrayList<>();
        try {
            Bson filter = Filters.or(
                Filters.eq("editorCode", sessionCode),
                Filters.eq("viewerCode", sessionCode)
            );
            documentsCollection.find(filter).forEach(documents::add);
            return documents;
        } catch (Exception e) {
            System.err.println("Error getting documents by session code: " + e.getMessage());
            e.printStackTrace();
            return getDocumentsBySessionCodeInMemory(sessionCode);
        }
    }

    private List<Document> getDocumentsBySessionCodeInMemory(String sessionCode) {
        List<Document> documents = new ArrayList<>();
        for (InMemoryDocument inMemoryDoc : documentMap.values()) {
            if ((inMemoryDoc.editorCode != null && inMemoryDoc.editorCode.equals(sessionCode)) ||
                (inMemoryDoc.viewerCode != null && inMemoryDoc.viewerCode.equals(sessionCode))) {
                Document doc = new Document();
                doc.append("_id", inMemoryDoc.id);
                doc.append("title", inMemoryDoc.title);
                doc.append("ownerId", inMemoryDoc.ownerId);
                doc.append("content", inMemoryDoc.content);
                doc.append("editorCode", inMemoryDoc.editorCode);
                doc.append("viewerCode", inMemoryDoc.viewerCode);
                doc.append("createdAt", inMemoryDoc.createdAt);
                doc.append("updatedAt", inMemoryDoc.updatedAt);
                documents.add(doc);
            }
        }
        return documents;
    }

    public void close() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    public boolean testMongoDBConnection() {
        try {
            if (mongoDbConnected && mongoClient != null) {
                try {
                    database.runCommand(new Document("ping", 1));
                    long userCount = usersCollection.countDocuments();
                    long docCount = documentsCollection.countDocuments();
                    System.out.println("==================================================");
                    System.out.println("MongoDB connection test: SUCCESS");
                    System.out.println("Connected to: " + DATABASE_NAME);
                    System.out.println("Users collection: " + userCount + " documents");
                    System.out.println("Documents collection: " + docCount + " documents");
                    System.out.println("==================================================");
                    return true;
                } catch (Exception e) {
                    System.err.println("==================================================");
                    System.err.println("MongoDB connection test: FAILED");
                    System.err.println("Error: " + e.getMessage());
                    System.err.println("Will attempt to reconnect...");
                    System.err.println("==================================================");
                    try {
                        mongoClient.close();
                    } catch (Exception ex) {
                    }
                    return attemptReconnect();
                }
            } else if (mongoDbConnected && mongoClient == null) {
                System.err.println("==================================================");
                System.err.println("MongoDB connection state inconsistent!");
                System.err.println("Attempting to reconnect...");
                System.err.println("==================================================");
                return attemptReconnect();
            } else {
                System.err.println("==================================================");
                System.err.println("Currently using IN-MEMORY STORAGE.");
                System.err.println("Your data is NOT being saved to MongoDB!");
                System.err.println("Attempting to reconnect to MongoDB...");
                System.err.println("==================================================");
                return attemptReconnect();
            }
        } catch (Exception e) {
            System.err.println("Error during connection test: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public Date getLastLoginTime(String userId) {
        if (useInMemoryStorage) {
            User user = userMap.get(userId);
            return user != null ? user.lastLogin : null;
        }
        try {
            if (!mongoDbConnected) {
                return null;
            }
            Document user = null;
            try {
                ObjectId objId = new ObjectId(userId);
                user = usersCollection.find(Filters.eq("_id", objId)).first();
            } catch (Exception e) {
                user = usersCollection.find(Filters.eq("_id", userId)).first();
            }
            if (user != null) {
                return user.getDate("lastLogin");
            }
            return null;
        } catch (Exception e) {
            System.err.println("Error getting last login time: " + e.getMessage());
            return null;
        }
    }

    private static class User {
        String id;
        String username;
        String hashedPassword;
        Date createdAt;
        Date lastLogin;

        public User(String id, String username, String hashedPassword, Date createdAt) {
            this.id = id;
            this.username = username;
            this.hashedPassword = hashedPassword;
            this.createdAt = createdAt;
            this.lastLogin = createdAt;
        }
    }

    private static class InMemoryDocument {
        public final String id;
        public final String title;
        public final String ownerId;
        public String content;
        public String editorCode;
        public String viewerCode;
        public final Date createdAt;
        public Date updatedAt;

        public InMemoryDocument(String id, String title, String ownerId, String content, Date createdAt, Date updatedAt) {
            this.id = id;
            this.title = title;
            this.ownerId = ownerId;
            this.content = content;
            this.editorCode = "";
            this.viewerCode = "";
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    public Document getDocumentBySessionCode(String sessionCode) {
        if (sessionCode == null || sessionCode.isEmpty()) {
            return null;
        }
        if (useInMemoryStorage) {
            return getDocumentBySessionCodeInMemory(sessionCode);
        }
        try {
            if (!mongoDbConnected && !attemptReconnect()) {
                return getDocumentBySessionCodeInMemory(sessionCode);
            }
            Document doc = documentsCollection.find(
                new Document("$or", List.of(
                    new Document("editorCode", sessionCode),
                    new Document("viewerCode", sessionCode)
                ))
            ).first();
            if (doc != null) {
                System.out.println("Found document with session code: " + sessionCode + ", document ID: " + doc.get("_id"));
                return doc;
            } else {
                System.out.println("No document found with session code: " + sessionCode);
                return null;
            }
        } catch (Exception e) {
            System.err.println("Error searching for document by session code: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private Document getDocumentBySessionCodeInMemory(String sessionCode) {
        for (InMemoryDocument doc : documentMap.values()) {
            if ((doc.editorCode != null && doc.editorCode.equals(sessionCode)) ||
                (doc.viewerCode != null && doc.viewerCode.equals(sessionCode))) {
                System.out.println("Found in-memory document with session code: " + sessionCode);
                Document document = new Document()
                    .append("_id", doc.id)
                    .append("title", doc.title)
                    .append("ownerId", doc.ownerId)
                    .append("content", doc.content)
                    .append("editorCode", doc.editorCode)
                    .append("viewerCode", doc.viewerCode)
                    .append("createdAt", doc.createdAt)
                    .append("updatedAt", doc.updatedAt);
                return document;
            }
        }
        System.out.println("No in-memory document found with session code: " + sessionCode);
        return null;
    }

    public boolean updateDocumentWithSession(String documentId, String content, String sessionCode) {
        return updateDocumentWithSession(documentId, content, sessionCode, sessionCode);
    }

    private boolean updateDocumentWithSessionInMemory(String documentId, String content, 
                                                     String editorCode, String viewerCode) {
        InMemoryDocument document = documentMap.get(documentId);
        if (document != null) {
            if (content != null) {
                document.content = content;
            }
            document.editorCode = editorCode;
            document.viewerCode = viewerCode;
            document.updatedAt = new Date();
            return true;
        }
        return false;
    }
}