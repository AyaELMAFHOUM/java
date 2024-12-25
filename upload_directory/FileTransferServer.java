import java.io.*;
import java.net.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class FileTransferServer {
    private int port;
    private AdminDashboard adminDashboard;

    public FileTransferServer(int port) {
        this.port = port;
        try {
            this.adminDashboard = new AdminDashboard(port); // Pass the port to AdminDashboard
        } catch (Exception e) {
            e.printStackTrace(); // Handle exception appropriately
            System.exit(1); // Optionally terminate the application if the dashboard fails to initialize
        }
    }

    public void start() {
        System.out.println("Server started on port: " + port);
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Client connected: " + clientSocket.getInetAddress());

                // Handle client in a separate thread
                new Thread(() -> handleClient(clientSocket)).start();
            }
        } catch (IOException e) {
            System.err.println("Error starting server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleClient(Socket clientSocket) {
        try (DataInputStream dis = new DataInputStream(clientSocket.getInputStream());
             DataOutputStream dos = new DataOutputStream(clientSocket.getOutputStream())) {

            // Read operation type
            String operation = dis.readUTF();
            if ("UPLOAD".equals(operation)) {
                String filename = dis.readUTF();
                long fileSize = dis.readLong();
                String owner = dis.readUTF();

                System.out.println("Receiving file: " + filename + " from owner: " + owner);
                File uploadDirectory = new File("upload_directory");
                if (!uploadDirectory.exists()) {
                    uploadDirectory.mkdirs(); // Create the directory if it doesn't exist
                }
                File file = new File(uploadDirectory, filename);

                // Prepare to read file content
                byte[] fileContent = new byte[(int) fileSize];
                try (FileOutputStream fos = new FileOutputStream(file);
                     BufferedOutputStream bos = new BufferedOutputStream(fos)) {

                    int bytesRead;
                    long totalBytesRead = 0;

                    // Read the file data from the client
                    while (totalBytesRead < fileSize && (bytesRead = dis.read(fileContent, (int) totalBytesRead, (int) Math.min(fileSize - totalBytesRead, fileContent.length))) != -1) {
                        bos.write(fileContent, 0, bytesRead);
                        totalBytesRead += bytesRead;
                    }
                    bos.flush();

                    // Save metadata to the database along with the file data
                    saveFileToDatabase(new FileMetadata(filename, owner, fileContent));

                    // Refresh the file list in the admin dashboard
                    adminDashboard.refreshFileList(); // Refresh from the database

                    System.out.println("File " + filename + " uploaded successfully.");
                }
            } else if ("GET_UPLOADED_FILES".equals(operation)) {
                String username = dis.readUTF();
                List<FileMetadata> files = fetchFilesFromDatabase(username); // Fetch files for the specific user
                dos.writeInt(files.size()); // Send number of files
                for (FileMetadata file : files) {
                    dos.writeUTF(file.getFilename());
                    dos.writeUTF(file.getOwner());
                    dos.writeUTF(file.getUploadDate() != null ? file.getUploadDate() : ""); // Send upload date, handle null
                }
            } else if ("DELETE".equals(operation)) {
                String filename = dis.readUTF();
                String owner = dis.readUTF();
                deleteFileFromDatabase(filename, owner); // Handle deletion
            }

        } catch (IOException e) {
            System.err.println("Error handling client: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void saveFileToDatabase(FileMetadata file) {
        try (Connection conn = DriverManager.getConnection("jdbc:mysql://localhost:3306/file_transfer", "root", "H@mm1d2024");
             PreparedStatement ps = conn.prepareStatement("INSERT INTO files (filename, owner, upload_date, file_data) VALUES (?, ?, ?, ?)")) {

            ps.setString(1, file.getFilename());
            ps.setString(2, file.getOwner());
            ps.setTimestamp(3, new Timestamp(System.currentTimeMillis()));

            // Save the actual file data into the database
            ps.setBytes(4, file.getFileContent()); // Save the byte array directly

            ps.executeUpdate();

            System.out.println("File saved to database: " + file.getFilename());
        } catch (SQLException e) {
            System.err.println("Error saving file to database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private List<FileMetadata> fetchFilesFromDatabase(String username) {
        List<FileMetadata> fileList = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection("jdbc:mysql://localhost:3306/file_transfer", "root", "H@mm1d2024");
             PreparedStatement ps = conn.prepareStatement("SELECT filename, owner, upload_date FROM files WHERE owner = ?")) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                fileList.add(new FileMetadata(rs.getString("filename"), rs.getString("owner"), rs.getTimestamp("upload_date")));
            }
        } catch (SQLException e) {
            System.err.println("Error fetching files from database: " + e.getMessage());
            e.printStackTrace();
        }
        return fileList;
    }

    private void deleteFileFromDatabase(String filename, String owner) {
        try (Connection conn = DriverManager.getConnection("jdbc:mysql://localhost:3306/file_transfer", "root", "H@mm1d2024");
             PreparedStatement ps = conn.prepareStatement("DELETE FROM files WHERE filename = ? AND owner = ?")) {
            ps.setString(1, filename);
            ps.setString(2, owner);
            ps.executeUpdate();
            System.out.println("File deleted from database: " + filename);
        } catch (SQLException e) {
            System.err.println("Error deleting file from database: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
