import java.io.Serializable;
import java.sql.Timestamp;

public class FileMetadata implements Serializable {
    private static final long serialVersionUID = 1L; // Recommended for Serializable classes

    private final int id;
    private final String filename;
    private final String uploadDate;
    private final byte[] fileContent; // Can be used if you want to send file content
    private final String owner;

    // Main constructor for all fields
    public FileMetadata(int id, String filename, String uploadDate, byte[] fileContent, String owner) {
        this.id = id;
        this.filename = filename;
        this.uploadDate = uploadDate;
        this.fileContent = fileContent;
        this.owner = owner;
    }

    // Constructor for uploading files with content
    public FileMetadata(String filename, String owner, byte[] fileContent) {
        this.id = -1; // Default ID for temporary metadata
        this.filename = filename;
        this.uploadDate = new Timestamp(System.currentTimeMillis()).toString(); // Set current time as upload date
        this.fileContent = fileContent;
        this.owner = owner;
    }

    // Overloaded constructor for metadata without file content
    public FileMetadata(String filename, String owner, Timestamp uploadDate) {
        this.id = -1; // Default ID for temporary metadata
        this.filename = filename;
        this.uploadDate = uploadDate != null ? uploadDate.toString() : null;
        this.fileContent = null; // No file content provided
        this.owner = owner;
    }

    // Getter methods
    public int getId() {
        return id;
    }

    public String getFilename() {
        return filename;
    }

    public String getUploadDate() {
        return uploadDate;
    }

    public byte[] getFileContent() {
        return fileContent;
    }

    public String getOwner() {
        return owner;
    }
}
