import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.net.Socket;

class ConnectionDashboard {
    JFrame frame;
    String username;
    String serverIp;  // Server IP from connection setup
    int serverPort;   // Server port from connection setup

    public ConnectionDashboard(String username, String serverIp, int serverPort) {
        this.username = username;
        this.serverIp = serverIp;
        this.serverPort = serverPort;
    }

    public void display() {
        frame = new JFrame("Connect to Server");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(400, 200);

        JPanel panel = new JPanel(new GridLayout(3, 2, 10, 10));
        JLabel ipLabel = new JLabel("Server IP:");
        JTextField ipField = new JTextField(serverIp); // Use the provided server IP
        JLabel portLabel = new JLabel("Server Port:");
        JTextField portField = new JTextField(String.valueOf(serverPort)); // Use the provided server port

        JButton connectButton = new JButton("Connect");
        connectButton.addActionListener(e -> {
            // Check connection to the server
            if (isServerReachable(ipField.getText(), Integer.parseInt(portField.getText()))) {
                JOptionPane.showMessageDialog(frame, "Connection successful!", "Success", JOptionPane.INFORMATION_MESSAGE);
                frame.dispose();
                new UserDashboard(username, ipField.getText(), Integer.parseInt(portField.getText())).display(); // Pass server details
            } else {
                JOptionPane.showMessageDialog(frame, "Cannot connect to the server. Please check the IP and port.", "Connection Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        panel.add(ipLabel);
        panel.add(ipField);
        panel.add(portLabel);
        panel.add(portField);
        panel.add(new JLabel());
        panel.add(connectButton);

        frame.add(panel);
        frame.setVisible(true);
    }

    private boolean isServerReachable(String serverIp, int serverPort) {
        try (Socket socket = new Socket(serverIp, serverPort)) {
            return true; // Connection successful
        } catch (IOException ex) {
            System.err.println("Server is not reachable: " + ex.getMessage()); // Debugging output
            return false; // Connection failed
        }
    }
}
