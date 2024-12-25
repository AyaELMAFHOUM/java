import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.*;
import java.util.List;

class UserDashboard {
    private JFrame frame;
    private String username;
    private String serverIp;
    private int serverPort;
    private JTable fileTable;
    private DefaultTableModel tableModel;

    public UserDashboard(String username, String serverIp, int serverPort) {
        this.username = username;
        this.serverIp = serverIp;
        this.serverPort = serverPort;
    }

    public void display() {
        frame = new JFrame("User Dashboard");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(900, 600);
        frame.setLayout(new BorderLayout());
        frame.getContentPane().setBackground(new Color(245, 245, 245)); // Light background color

        // Welcome label
        JLabel welcomeLabel = new JLabel("Welcome, " + username, SwingConstants.CENTER);
        welcomeLabel.setFont(new Font("Arial", Font.BOLD, 18));
        welcomeLabel.setForeground(new Color(70, 130, 180)); // Steel blue for welcome label
        welcomeLabel.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));

        // Table for displaying uploaded files
        tableModel = new DefaultTableModel(new String[]{"Filename", "Owner"}, 0);
        fileTable = new JTable(tableModel);
        fileTable.setSelectionBackground(new Color(100, 149, 237)); // Cornflower blue for selected rows
        fileTable.setSelectionForeground(Color.WHITE); // White text on selected rows
        fileTable.setRowHeight(30); // Set row height for better readability
        fileTable.getTableHeader().setBackground(new Color(70, 130, 180)); // Header background color
        fileTable.getTableHeader().setForeground(Color.WHITE); // Header text color

        // Buttons
        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new GridLayout(1, 5, 20, 20));

        JButton uploadButton = createStyledButton("Upload File");
        JButton downloadButton = createStyledButton("Download File");
        JButton deleteButton = createStyledButton("Delete File");
        JButton chatButton = createStyledButton("Chat");
        JButton logoutButton = createStyledButton("Logout");

        uploadButton.addActionListener(e -> uploadFile());
        downloadButton.addActionListener(e -> downloadSelectedFile());
        deleteButton.addActionListener(e -> deleteSelectedFile());
        chatButton.addActionListener(e -> openChat());
        logoutButton.addActionListener(e -> {
            frame.dispose();
            new UserLogin(serverIp, serverPort).display();
        });

        buttonPanel.add(uploadButton);
        buttonPanel.add(downloadButton);
        buttonPanel.add(deleteButton);
        buttonPanel.add(chatButton);
        buttonPanel.add(logoutButton);

        // Layout setup
        frame.add(welcomeLabel, BorderLayout.NORTH);
        frame.add(new JScrollPane(fileTable), BorderLayout.CENTER);
        frame.add(buttonPanel, BorderLayout.SOUTH);

        loadAllUploadedFiles(); // Load files
        frame.setVisible(true);
    }

    private JButton createStyledButton(String text) {
        JButton button = new JButton(text);
        button.setBackground(new Color(70, 130, 180)); // Steel Blue
        button.setForeground(Color.WHITE);
        button.setFont(new Font("Arial", Font.BOLD, 14));
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));
        return button;
    }

    private void loadAllUploadedFiles() {
        try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + serverIp + ":3306/file_transfer", "root", "H@mm1d2024");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT filename, owner FROM files")) {

            tableModel.setRowCount(0); // Clear the table
            while (rs.next()) {
                String filename = rs.getString("filename");
                String owner = rs.getString("owner");
                tableModel.addRow(new Object[]{filename, owner}); // Show all files
            }
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(frame, "Error loading files: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void uploadFile() {
        JFileChooser fileChooser = new JFileChooser();
        if (fileChooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            try {
                // Create a new client instance for uploading
                FileTransferClient client = new FileTransferClient(serverIp, serverPort);
                client.uploadFileData(file, username);
                JOptionPane.showMessageDialog(frame, "File uploaded successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
                loadAllUploadedFiles(); // Refresh the file list after upload
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "Error uploading file: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void downloadSelectedFile() {
        int selectedRow = fileTable.getSelectedRow();
        if (selectedRow == -1) {
            JOptionPane.showMessageDialog(frame, "No file selected.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String fileName = (String) tableModel.getValueAt(selectedRow, 0);

        // Use JFileChooser to allow the user to specify the save location
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setSelectedFile(new File(fileName)); // Set the default file name

        if (fileChooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File saveFile = fileChooser.getSelectedFile();
            try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + serverIp + ":3306/file_transfer", "root", "H@mm1d2024");
                 PreparedStatement ps = conn.prepareStatement("SELECT file_data FROM files WHERE filename = ?")) {

                ps.setString(1, fileName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        byte[] fileData = rs.getBytes("file_data");
                        try (FileOutputStream fos = new FileOutputStream(saveFile)) {
                            fos.write(fileData);
                            JOptionPane.showMessageDialog(frame, "File downloaded successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
                        }
                    } else {
                        JOptionPane.showMessageDialog(frame, "File not found in the database.", "Error", JOptionPane.ERROR_MESSAGE);
                    }
                }
            } catch (SQLException | IOException ex) {
                JOptionPane.showMessageDialog(frame, "Error downloading file: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void deleteSelectedFile() {
        int selectedRow = fileTable.getSelectedRow();
        if (selectedRow == -1) {
            JOptionPane.showMessageDialog(frame, "No file selected.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String fileName = (String) tableModel.getValueAt(selectedRow, 0);
        String fileOwner = (String) tableModel.getValueAt(selectedRow, 1); // Get the owner of the file

        // Check if the current user is the owner of the file
        if (!fileOwner.equals(username)) {
            JOptionPane.showMessageDialog(frame, "You don't have permission to delete this file.", "Permission Denied", JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            // Create a new client instance for deleting
            FileTransferClient client = new FileTransferClient(serverIp, serverPort);
            client.deleteFile(fileName, username);
            JOptionPane.showMessageDialog(frame, "File deleted successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
            loadAllUploadedFiles(); // Refresh the file list after deletion
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, "Error deleting file: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openChat() {
        // Open the chat window without closing the dashboard
        ChatWindow chatWindow = new ChatWindow(username, serverIp, serverPort);
        chatWindow.display(); // Display the chat window
    }
}
