import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.sql.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class AdminDashboard {
    private JFrame frame;
    private JTable fileTable;
    private DefaultTableModel tableModel;
    private JTable userTable;
    private DefaultTableModel userTableModel;
    private Connection dbConnection;
    private int serverPort;
    private Timer autoRefreshTimer; // Timer for automatic refreshing

    public AdminDashboard(int port) throws Exception {
        this.serverPort = port;
        dbConnection = connectToDatabase();
        if (dbConnection == null) {
            throw new SQLException("Database connection failed.");
        }
        initializeUI();
        startAutoRefresh(); // Start automatic refreshing
    }

    private void initializeUI() {
        frame = new JFrame("Admin Dashboard");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1000, 600);

        JTabbedPane tabbedPane = new JTabbedPane();

        // File Management Tab
        JPanel fileManagementPanel = createFileManagementPanel();
        tabbedPane.add("File Management", fileManagementPanel);

        // User Management Tab
        JPanel userManagementPanel = createUserManagementPanel();
        tabbedPane.add("User Management", userManagementPanel);

        // Footer with Credits
        JLabel footerLabel = new JLabel("Created by Hamid, Malak, and Saad", SwingConstants.CENTER);
        footerLabel.setFont(new Font("Arial", Font.ITALIC, 12));
        footerLabel.setForeground(Color.DARK_GRAY);

        frame.add(tabbedPane, BorderLayout.CENTER);
        frame.add(footerLabel, BorderLayout.SOUTH);
        frame.setVisible(true);
    }

    private JPanel createFileManagementPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(240, 248, 255)); // Light blue background

        tableModel = new DefaultTableModel(new String[]{"Filename", "Upload Date", "Owner", "Status"}, 0);
        fileTable = new JTable(tableModel);

        JButton refreshButton = createStyledButton("Refresh");
        refreshButton.addActionListener(e -> refreshFileList());

        JButton downloadButton = createStyledButton("Download");
        downloadButton.addActionListener(e -> downloadSelectedFile());

        JButton deleteButton = createStyledButton("Delete");
        deleteButton.addActionListener(e -> deleteSelectedFile());

        JPanel buttonPanel = new JPanel();
        buttonPanel.setBackground(new Color(230, 230, 250)); // Light purple background
        buttonPanel.add(refreshButton);
        buttonPanel.add(downloadButton);
        buttonPanel.add(deleteButton);

        panel.add(new JScrollPane(fileTable), BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createUserManagementPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(240, 248, 255)); // Light blue background

        userTableModel = new DefaultTableModel(new String[]{"Username", "Actions"}, 0);
        userTable = new JTable(userTableModel);

        JButton refreshButton = createStyledButton("Refresh Users");
        refreshButton.addActionListener(e -> refreshUserList());

        JButton deleteUserButton = createStyledButton("Delete User");
        deleteUserButton.addActionListener(e -> deleteUser());

        JButton resetPasswordButton = createStyledButton("Reset Password");
        resetPasswordButton.addActionListener(e -> resetPassword());

        JPanel buttonPanel = new JPanel();
        buttonPanel.setBackground(new Color(230, 230, 250)); // Light purple background
        buttonPanel.add(refreshButton);
        buttonPanel.add(deleteUserButton);
        buttonPanel.add(resetPasswordButton);

        panel.add(new JScrollPane(userTable), BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JButton createStyledButton(String text) {
        JButton button = new JButton(text);
        button.setBackground(new Color(173, 216, 230)); // Light cyan background
        button.setForeground(Color.DARK_GRAY);
        button.setFocusPainted(false);
        button.setFont(new Font("Arial", Font.BOLD, 12));
        return button;
    }

    private void startAutoRefresh() {
        autoRefreshTimer = new Timer(5000, new ActionListener() { // Refresh every 5 seconds
            @Override
            public void actionPerformed(ActionEvent e) {
                refreshFileList(); // Refresh file list
                refreshUserList(); // Refresh user list
            }
        });
        autoRefreshTimer.start(); // Start the timer
    }

    public void refreshFileList() {
        SwingUtilities.invokeLater(() -> {
            tableModel.setRowCount(0); // Clear the table
            try (Statement stmt = dbConnection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT filename, upload_date, owner FROM files")) {

                while (rs.next()) {
                    tableModel.addRow(new Object[]{
                            rs.getString("filename"),
                            rs.getTimestamp("upload_date"),
                            rs.getString("owner"),
                            "Saved"
                    });
                }
            } catch (SQLException e) {
                JOptionPane.showMessageDialog(frame, "Error fetching files: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    public void refreshUserList() {
        SwingUtilities.invokeLater(() -> {
            userTableModel.setRowCount(0); // Clear the table
            try (Statement stmt = dbConnection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT username FROM users")) {

                while (rs.next()) {
                    userTableModel.addRow(new Object[]{rs.getString("username"), "Actions"});
                }
            } catch (SQLException e) {
                JOptionPane.showMessageDialog(frame, "Error fetching users: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private void downloadSelectedFile() {
        int selectedRow = fileTable.getSelectedRow();
        if (selectedRow == -1) {
            JOptionPane.showMessageDialog(frame, "No file selected.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String fileName = (String) tableModel.getValueAt(selectedRow, 0);
        try (PreparedStatement ps = dbConnection.prepareStatement("SELECT file_data FROM files WHERE filename = ?")) {
            ps.setString(1, fileName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    byte[] fileData = rs.getBytes("file_data");
                    JFileChooser fileChooser = new JFileChooser();
                    fileChooser.setSelectedFile(new java.io.File(fileName));
                    if (fileChooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
                        java.io.File saveFile = fileChooser.getSelectedFile();
                        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(saveFile)) {
                            fos.write(fileData);
                            JOptionPane.showMessageDialog(frame, "File downloaded successfully.", "Success", JOptionPane.INFORMATION_MESSAGE);
                        }
                    }
                } else {
                    JOptionPane.showMessageDialog(frame, "File not found in the database.", "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        } catch (SQLException | java.io.IOException e) {
            JOptionPane.showMessageDialog(frame, "Error downloading file: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteSelectedFile() {
        int selectedRow = fileTable.getSelectedRow();
        if (selectedRow == -1) {
            JOptionPane.showMessageDialog(frame, "No file selected.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String fileName = (String) tableModel.getValueAt(selectedRow, 0);
        try (PreparedStatement ps = dbConnection.prepareStatement("DELETE FROM files WHERE filename = ?")) {
            ps.setString(1, fileName);
            int rowsAffected = ps.executeUpdate();
            if (rowsAffected > 0) {
                JOptionPane.showMessageDialog(frame, "File deleted successfully.", "Success", JOptionPane.INFORMATION_MESSAGE);
                refreshFileList();
            } else {
                JOptionPane.showMessageDialog(frame, "File not found.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(frame, "Error deleting file: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteUser() {
        int selectedRow = userTable.getSelectedRow();
        if (selectedRow == -1) {
            JOptionPane.showMessageDialog(frame, "No user selected.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String username = (String) userTableModel.getValueAt(selectedRow, 0);
        try (PreparedStatement ps = dbConnection.prepareStatement("DELETE FROM users WHERE username = ?")) {
            ps.setString(1, username);
            int rowsAffected = ps.executeUpdate();
            if (rowsAffected > 0) {
                JOptionPane.showMessageDialog(frame, "User deleted successfully.", "Success", JOptionPane.INFORMATION_MESSAGE);
                refreshUserList();
            } else {
                JOptionPane.showMessageDialog(frame, "User not found.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(frame, "Error deleting user: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void resetPassword() {
        int selectedRow = userTable.getSelectedRow();
        if (selectedRow == -1) {
            JOptionPane.showMessageDialog(frame, "No user selected.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String username = (String) userTableModel.getValueAt(selectedRow, 0);
        try (PreparedStatement ps = dbConnection.prepareStatement("UPDATE users SET password = ? WHERE username = ?")) {
            ps.setString(1, "defaultpassword"); // Replace with a secure default password logic
            ps.setString(2, username);
            int rowsAffected = ps.executeUpdate();
            if (rowsAffected > 0) {
                JOptionPane.showMessageDialog(frame, "Password reset successfully.", "Success", JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(frame, "User not found.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(frame, "Error resetting password: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private Connection connectToDatabase() {
        try {
            String url = "jdbc:mysql://localhost:3306/file_transfer";
            String user = "root";
            String password = "H@mm1d2024";
            return DriverManager.getConnection(url, user, password);
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(frame, "Error connecting to the database: " + e.getMessage(), "Database Error", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }
}
