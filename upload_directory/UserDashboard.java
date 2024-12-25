import javax.swing.*;
import java.awt.*;
import java.io.File;

class UserDashboard {
    private JFrame frame;
    private FileTransferClient client;
    private String username;
    private String serverIp; // Store server IP
    private int serverPort; // Store server port

    public UserDashboard(String username, String serverIp, int serverPort) {
        this.client = new FileTransferClient(serverIp, serverPort); // Use provided server details
        this.username = username;
        this.serverIp = serverIp; // Store server IP
        this.serverPort = serverPort; // Store server port
    }

    public void display() {
        frame = new JFrame("User Dashboard");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 400);
        JLabel welcomeLabel = new JLabel("Welcome, " + username, SwingConstants.CENTER);
        welcomeLabel.setFont(new Font("Arial", Font.BOLD, 16));

        JButton uploadButton = new JButton("Upload File");
        JButton viewFilesButton = new JButton("View Uploaded Files");
        JButton downloadButton = new JButton("Download File");
        JButton deleteButton = new JButton("Delete File");
        JButton logoutButton = new JButton("Logout");

        uploadButton.addActionListener(e -> uploadFile());
        viewFilesButton.addActionListener(e -> viewFiles());
        downloadButton.addActionListener(e -> downloadFile());
        deleteButton.addActionListener(e -> deleteFile());
        logoutButton.addActionListener(e -> {
            frame.dispose();
            new UserLogin(serverIp, serverPort).display(); // Return to login screen with server details
        });

        JPanel buttonPanel = new JPanel();
        buttonPanel.add(uploadButton);
        buttonPanel.add(viewFilesButton);
        buttonPanel.add(downloadButton);
        buttonPanel.add(deleteButton);
        buttonPanel.add(logoutButton);

        frame.add(welcomeLabel, BorderLayout.NORTH);
        frame.add(buttonPanel, BorderLayout.CENTER);
        frame.setVisible(true);
    }

    private void uploadFile() {
        JFileChooser fileChooser = new JFileChooser();
        if (fileChooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            System.out.println("Selected file: " + file.getAbsolutePath()); // Debugging output
            try {
                client.uploadFileData(file, username);
                JOptionPane.showMessageDialog(frame, "File uploaded successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "Error uploading file: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                ex.printStackTrace(); // Print stack trace for debugging
            }
        }
    }

    private void viewFiles() {
        JOptionPane.showMessageDialog(frame, "Feature under construction.", "Info", JOptionPane.INFORMATION_MESSAGE);
    }

    private void downloadFile() {
        String filename = JOptionPane.showInputDialog(frame, "Enter the filename to download:");
        String savePath = JOptionPane.showInputDialog(frame, "Enter the save path for the file:");
        try {
            client.downloadFile(filename, savePath);
            JOptionPane.showMessageDialog(frame, "File downloaded successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, "Error downloading file: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            ex.printStackTrace(); // Print stack trace for debugging
        }
    }

    private void deleteFile() {
        String filename = JOptionPane.showInputDialog(frame, "Enter the filename to delete:");
        try {
            client.deleteFile(filename, username);
            JOptionPane.showMessageDialog(frame, "File deleted successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, "Error deleting file: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            ex.printStackTrace(); // Print stack trace for debugging
        }
    }
}
