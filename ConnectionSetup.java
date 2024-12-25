import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.net.Socket;

public class ConnectionSetup {
    private JFrame frame;
    private String serverIp;
    private int serverPort;

    public void display() {
        frame = new JFrame("Connect to Server");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(400, 250); // Increased size for better layout

        // Set background color for the frame
        frame.getContentPane().setBackground(new Color(173, 216, 230)); // Light blue color

        // Create panel with GridBagLayout for more flexible design
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(new Color(173, 216, 230)); // Light blue color

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10); // Padding for each component

        JLabel ipLabel = new JLabel("Server IP:");
        JTextField ipField = new JTextField("127.0.0.1", 15); // Default IP with width
        JLabel portLabel = new JLabel("Server Port:");
        JTextField portField = new JTextField("8080", 5); // Default Port with width

        // Connect button with updated color and styling
        JButton connectButton = new JButton("Connect");
        connectButton.setBackground(new Color(70, 130, 180)); // SteelBlue color
        connectButton.setForeground(Color.WHITE); // White text
        connectButton.setFont(new Font("Arial", Font.BOLD, 14)); // Bold font for the button
        connectButton.setFocusPainted(false); // Remove the focus outline

        connectButton.addActionListener(e -> {
            serverIp = ipField.getText();
            try {
                serverPort = Integer.parseInt(portField.getText()); // Parse the port number

                // Check if the server is reachable
                if (isServerReachable(serverIp, serverPort)) {
                    JOptionPane.showMessageDialog(frame, "Connection successful!", "Success", JOptionPane.INFORMATION_MESSAGE);
                    frame.dispose();
                    new UserLogin(serverIp, serverPort).display(); // Pass server IP and port to UserLogin
                } else {
                    JOptionPane.showMessageDialog(frame, "Cannot connect to the server. Please check the IP and port.", "Connection Error", JOptionPane.ERROR_MESSAGE);
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(frame, "Invalid port number!", "Error", JOptionPane.ERROR_MESSAGE);
            } catch (Exception ex) {
                ex.printStackTrace(); // Print stack trace for debugging
                JOptionPane.showMessageDialog(frame, "Error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        // Layout configuration for components using GridBagLayout
        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(ipLabel, gbc);

        gbc.gridx = 1;
        panel.add(ipField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        panel.add(portLabel, gbc);

        gbc.gridx = 1;
        panel.add(portField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 2; // Make the button span across both columns
        panel.add(connectButton, gbc);

        // Add the panel to the frame
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
