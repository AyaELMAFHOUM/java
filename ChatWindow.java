import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.*;
import java.net.Socket;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;

public class ChatWindow {
    private JFrame frame;
    private String username;
    private String serverIp;
    private int serverPort;
    private JTextArea messageArea;
    private JTextField messageInput;
    private JTable userTable;
    private DefaultTableModel userTableModel;
    private boolean isGroupChat = false;
    private JLabel typingIndicator;
    private Map<String, String> userStatusMap = new HashMap<>();
    private Timer messageRefreshTimer;

    public ChatWindow(String username, String serverIp, int serverPort) {
        this.username = username;
        this.serverIp = serverIp;
        this.serverPort = serverPort;
        initializeUI();
        loadUsers();
        startMessageRefresh();
        updateOnlineStatus("Online");
    }

    public void display() {
        frame.setVisible(true);
    }

    private void initializeUI() {
        frame = new JFrame("Chat Window");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(800, 600);
        frame.setLayout(new BorderLayout());
        
        // Set a blue color scheme for the background and UI components
        frame.getContentPane().setBackground(new Color(0, 51, 102));  // Dark blue background
        
        JLabel titleLabel = new JLabel("Chat", SwingConstants.CENTER);
        titleLabel.setFont(new Font("Arial", Font.BOLD, 16));
        titleLabel.setForeground(Color.WHITE);  // White text for the title
        frame.add(titleLabel, BorderLayout.NORTH);

        // User table for private chats
        userTableModel = new DefaultTableModel(new String[]{"Users", "Status"}, 0);
        userTable = new JTable(userTableModel);
        userTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userTable.setBackground(new Color(0, 102, 204)); // Light blue background for user table
        userTable.setForeground(Color.WHITE);  // White text for the table
        userTable.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                int row = userTable.getSelectedRow();
                if (row != -1) {
                    isGroupChat = false;
                    String selectedUser = (String) userTableModel.getValueAt(row, 0);
                    loadChat(selectedUser);
                }
            }
        });
        JScrollPane userScrollPane = new JScrollPane(userTable);
        userScrollPane.setPreferredSize(new Dimension(200, 0));
        frame.add(userScrollPane, BorderLayout.WEST);

        // Message area
        messageArea = new JTextArea();
        messageArea.setEditable(false);
        messageArea.setBackground(new Color(240, 240, 240));  // Light gray for message area
        messageArea.setForeground(Color.BLACK);  // Black text for the message area
        JScrollPane messageScrollPane = new JScrollPane(messageArea);
        frame.add(messageScrollPane, BorderLayout.CENTER);

        // Typing indicator
        typingIndicator = new JLabel("", SwingConstants.CENTER);
        typingIndicator.setForeground(Color.WHITE);  // White text for typing indicator
        frame.add(typingIndicator, BorderLayout.SOUTH);

        // Input panel for sending messages
        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.setBackground(new Color(0, 51, 102));  // Dark blue background
        messageInput = new JTextField();
        messageInput.setBackground(new Color(255, 255, 255));  // White background for input field
        messageInput.setForeground(Color.BLACK);  // Black text for input field
        messageInput.addKeyListener(new java.awt.event.KeyAdapter() {
            public void keyTyped(java.awt.event.KeyEvent e) {
                sendTypingNotification();
            }
        });
        
        JButton sendButton = new JButton("Send");
        sendButton.setBackground(new Color(0, 102, 204));  // Blue background for button
        sendButton.setForeground(Color.WHITE);  // White text for the button
        sendButton.addActionListener(e -> sendMessage());

        inputPanel.add(messageInput, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        frame.add(inputPanel, BorderLayout.SOUTH);

        // Global chat button
        JButton globalChatButton = new JButton("Global Chat");
        globalChatButton.setBackground(new Color(0, 102, 204));  // Blue background for button
        globalChatButton.setForeground(Color.WHITE);  // White text for button
        globalChatButton.addActionListener(e -> {
            isGroupChat = true;
            loadGlobalChat();
        });
        JPanel globalChatPanel = new JPanel(new BorderLayout());
        globalChatPanel.setBackground(new Color(0, 51, 102));  // Dark blue background
        globalChatPanel.add(globalChatButton, BorderLayout.CENTER);
        frame.add(globalChatPanel, BorderLayout.NORTH);
    }

    private void loadUsers() {
        try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + serverIp + ":3306/file_transfer", "root", "H@mm1d2024");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT username, status FROM users WHERE username != '" + username + "'");) {

            userTableModel.setRowCount(0);
            while (rs.next()) {
                String user = rs.getString("username");
                String status = rs.getString("status");
                userStatusMap.put(user, status);
                userTableModel.addRow(new Object[]{user, status});
            }
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(frame, "Error loading users: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

   private void loadChat(String selectedUser) {
        boolean hasNewMessages = false;
        messageArea.setText("");  // Clear the message area before loading new messages

        try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + serverIp + ":3306/file_transfer", "root", "H@mm1d2024");
             PreparedStatement ps = conn.prepareStatement("SELECT sender, message, timestamp, read_status FROM messages WHERE (sender = ? AND receiver = ?) OR (sender = ? AND receiver = ?) ORDER BY timestamp")) {

            ps.setString(1, username);  // Set the current user's username
            ps.setString(2, selectedUser);  // Set the selected user's username
            ps.setString(3, selectedUser);  // Set the selected user's username again (for reverse chat order)
            ps.setString(4, username);  // Set the current user's username again

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sender = rs.getString("sender");
                    String message = rs.getString("message");
                    Timestamp timestamp = rs.getTimestamp("timestamp");
                    String readStatus = rs.getString("read_status");

                    // Check for unread messages and set flag to true
                    if ("Unread".equals(readStatus) && !sender.equals(username)) {
                        hasNewMessages = true;
                    }

                    // Append the message to the message area with read status
                    messageArea.append(sender + ": " + message + " (" + timestamp + ") " + "[" + readStatus + "]\n");
                }
            }

            // If there are new unread messages, show a notification dialog
            if (hasNewMessages) {
                int option = JOptionPane.showConfirmDialog(frame, "You have new messages!", "New Message", JOptionPane.OK_OPTION, JOptionPane.INFORMATION_MESSAGE);
                if (option == JOptionPane.OK_OPTION) {
                    // Mark messages as "Read" once the user clicks "OK"
                    try (Connection updateConn = DriverManager.getConnection("jdbc:mysql://" + serverIp + ":3306/file_transfer", "root", "H@mm1d2024");
                         PreparedStatement updatePs = updateConn.prepareStatement("UPDATE messages SET read_status = 'Read' WHERE (sender = ? AND receiver = ?) OR (sender = ? AND receiver = ?) AND read_status = 'Unread'")) {

                        // Update the status of unread messages to "Read"
                        updatePs.setString(1, selectedUser);  // Set the selected user as sender
                        updatePs.setString(2, username);  // Set the current user as receiver
                        updatePs.setString(3, username);  // Set the current user as sender
                        updatePs.setString(4, selectedUser);  // Set the selected user as receiver
                        updatePs.executeUpdate();  // Execute the update query
                    } catch (SQLException e) {
                        JOptionPane.showMessageDialog(frame, "Error marking messages as read: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }

        } catch (SQLException e) {
            JOptionPane.showMessageDialog(frame, "Error loading chat: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadGlobalChat() {
        messageArea.setText("");
        try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + serverIp + ":3306/file_transfer", "root", "H@mm1d2024");
             PreparedStatement ps = conn.prepareStatement("SELECT sender, message, timestamp FROM messages WHERE receiver IS NULL ORDER BY timestamp")) {

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sender = rs.getString("sender");
                    String message = rs.getString("message");
                    Timestamp timestamp = rs.getTimestamp("timestamp");
                    messageArea.append(sender + ": " + message + " (" + timestamp + ")\n");
                }
            }
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(frame, "Error loading global chat: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void sendMessage() {
        String message = messageInput.getText();
        if (message.trim().isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Message cannot be empty.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + serverIp + ":3306/file_transfer", "root", "H@mm1d2024");
             PreparedStatement ps = conn.prepareStatement("INSERT INTO messages (sender, receiver, message, timestamp) VALUES (?, ?, ?, ?)")) {

            ps.setString(1, username);
            if (isGroupChat) {
                ps.setNull(2, Types.VARCHAR); // Global chat has no receiver
            } else {
                int selectedRow = userTable.getSelectedRow();
                if (selectedRow == -1) {
                    JOptionPane.showMessageDialog(frame, "No user selected.", "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                String selectedUser = (String) userTable.getValueAt(selectedRow, 0);
                ps.setString(2, selectedUser);
            }

            ps.setString(3, message);
            ps.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
            ps.executeUpdate();

            messageInput.setText("");
            if (isGroupChat) {
                loadGlobalChat();
            } else {
                loadChat((String) userTable.getValueAt(userTable.getSelectedRow(), 0));
            }

        } catch (SQLException e) {
            JOptionPane.showMessageDialog(frame, "Error sending message: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void sendTypingNotification() {
        try (Socket socket = new Socket(serverIp, serverPort);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            out.println(username + " is typing...");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, "Error sending typing notification: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateOnlineStatus(String status) {
        try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + serverIp + ":3306/file_transfer", "root", "H@mm1d2024");
             PreparedStatement ps = conn.prepareStatement("UPDATE users SET status = ? WHERE username = ?")) {

            ps.setString(1, status);
            ps.setString(2, username);
            ps.executeUpdate();
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(frame, "Error updating status: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void startMessageRefresh() {
        messageRefreshTimer = new Timer(5000, e -> {
            if (isGroupChat) {
                loadGlobalChat();
            } else {
                loadChat((String) userTable.getValueAt(userTable.getSelectedRow(), 0));
            }
        });
        messageRefreshTimer.start();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new ChatWindow("User1", "localhost", 12345).display();
        });
    }
}
