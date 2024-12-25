import java.io.*;
import java.net.*;
import java.sql.Timestamp; // Make sure to import this
import java.util.ArrayList;
import java.util.List;

public class FileTransferClient {
    private String serverAddress;
    private int port;

    public FileTransferClient(String serverAddress, int port) {
        this.serverAddress = serverAddress;
        this.port = port;
    }

    // Method to check if the server is reachable
    public boolean checkConnection() {
        try (Socket socket = new Socket(serverAddress, port)) {
            return true; // Connection successful
        } catch (IOException ex) {
            System.err.println("Connection failed: " + ex.getMessage());
            return false; // Connection failed
        }
    }

    public void uploadFileData(File file, String owner) {
        if (file == null || !file.exists()) {
            System.out.println("File not found: " + (file != null ? file.getAbsolutePath() : "null"));
            return;
        }

        // Check connection before uploading
        if (!checkConnection()) {
            System.out.println("Cannot connect to the server. Please check the server address and port.");
            return;
        }

        try (Socket socket = new Socket(serverAddress, port);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             FileInputStream fis = new FileInputStream(file)) {

            // Send upload operation
            dos.writeUTF("UPLOAD");
            dos.writeUTF(file.getName());
            dos.writeLong(file.length());
            dos.writeUTF(owner);

            // Now send the file data
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) > 0) {
                dos.write(buffer, 0, bytesRead);
            }
            dos.flush(); // Ensure all data is sent

            System.out.println("File uploaded successfully: " + file.getName());

        } catch (IOException ex) {
            System.err.println("Error uploading file: " + ex.getMessage());
            ex.printStackTrace(); // Print stack trace for debugging
        }
    }

    public List<FileMetadata> getUploadedFiles(String username) {
        List<FileMetadata> fileList = new ArrayList<>();
        try (Socket socket = new Socket(serverAddress, port);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream())) {

            // Request to get uploaded files
            dos.writeUTF("GET_UPLOADED_FILES");
            dos.writeUTF(username != null ? username : ""); // Send an empty string if username is null

            // Read the number of files
            int numberOfFiles = dis.readInt();
            for (int i = 0; i < numberOfFiles; i++) {
                String filename = dis.readUTF();
                String owner = dis.readUTF();
                String uploadDate = dis.readUTF(); // Assuming upload date is sent as a string
                fileList.add(new FileMetadata(filename, owner, Timestamp.valueOf(uploadDate))); // Create FileMetadata object
            }
        } catch (IOException e) {
            System.err.println("Error retrieving uploaded files: " + e.getMessage());
        }
        return fileList;
    }

    public void downloadFile(String filename, String savePath) {
        try (Socket socket = new Socket(serverAddress, port);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream())) {

            // Request file download
            dos.writeUTF("DOWNLOAD");
            dos.writeUTF(filename);

            // Receive response
            boolean fileExists = dis.readBoolean();
            if (!fileExists) {
                System.out.println("File not found on server: " + filename);
                return;
            }

            // Receive and save file
            File file = new File(savePath);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = dis.read(buffer)) > 0) {
                    fos.write(buffer, 0, bytesRead);
                }
            }

            System.out.println("File downloaded successfully: " + savePath);

        } catch (IOException ex) {
            System.err.println("Error downloading file: " + ex.getMessage());
            ex.printStackTrace(); // Print stack trace for debugging
        }
    }

    public void deleteFile(String filename, String owner) {
        try (Socket socket = new Socket(serverAddress, port);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {

            // Request file deletion
            dos.writeUTF("DELETE");
            dos.writeUTF(filename);
            dos.writeUTF(owner);

            System.out.println("File deletion request sent for: " + filename);

        } catch (IOException ex) {
            System.err.println("Error deleting file: " + ex.getMessage());
            ex.printStackTrace(); // Print stack trace for debugging
        }
    }
}
