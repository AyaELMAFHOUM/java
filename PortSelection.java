import javax.swing.*;
import java.awt.*;

public class PortSelection {
    private JFrame frame;
    private JTextField portField;

    public void display() {
        frame = new JFrame("Select Server Port");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(400, 200);
        frame.setLayout(new BorderLayout());

        // Main content panel
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel titleLabel = new JLabel("Start Server");
        titleLabel.setFont(new Font("Arial", Font.BOLD, 16));
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel portLabel = new JLabel("Enter Port:");
        portLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        portField = new JTextField("8080");
        portField.setMaximumSize(new Dimension(Integer.MAX_VALUE, portField.getPreferredSize().height));

        JButton startButton = createStyledButton("Start Server");
        startButton.addActionListener(e -> {
            try {
                int port = Integer.parseInt(portField.getText());
                frame.dispose();
                new Thread(() -> {
                    FileTransferServer server = new FileTransferServer(port);
                    server.start(); // Start the server
                }).start();
                JOptionPane.showMessageDialog(null, "Server started successfully on port: " + port, "Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(frame, "Invalid port number!", "Error", JOptionPane.ERROR_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "Error starting server: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        panel.add(titleLabel);
        panel.add(Box.createRigidArea(new Dimension(0, 10)));
        panel.add(portLabel);
        panel.add(portField);
        panel.add(Box.createRigidArea(new Dimension(0, 20)));
        panel.add(startButton);

        // Footer
        JLabel footerLabel = new JLabel("Project created by Hamid, Malak, and Saad", SwingConstants.CENTER);
        footerLabel.setFont(new Font("Arial", Font.ITALIC, 12));
        footerLabel.setForeground(Color.DARK_GRAY);

        frame.add(panel, BorderLayout.CENTER);
        frame.add(footerLabel, BorderLayout.SOUTH);
        frame.setVisible(true);
    }

    private JButton createStyledButton(String text) {
        JButton button = new JButton(text);
        button.setBackground(new Color(100, 149, 237)); // Cornflower blue
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.setFont(new Font("Arial", Font.BOLD, 14));
        button.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));
        return button;
    }
}
