import javax.swing.*;
import java.awt.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

class UserLogin {
    private JFrame frame;
    private JTextField usernameField;
    private JPasswordField passwordField;
    private String serverIp;
    private int serverPort;

    public UserLogin(String serverIp, int serverPort) {
        this.serverIp = serverIp;
        this.serverPort = serverPort;
    }

    public void display() {
        frame = new JFrame("User Login");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(400, 350);
        frame.setLayout(new BorderLayout());

        // Set background color for the frame
        frame.getContentPane().setBackground(new Color(173, 216, 230)); // Light blue color

        // Create panel with BoxLayout for better vertical component positioning
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel titleLabel = new JLabel("User Login");
        titleLabel.setFont(new Font("Arial", Font.BOLD, 18));
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel usernameLabel = new JLabel("Username:");
        usernameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        usernameField = new JTextField(20);
        usernameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, usernameField.getPreferredSize().height));

        JLabel passwordLabel = new JLabel("Password:");
        passwordLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        passwordField = new JPasswordField(20);
        passwordField.setMaximumSize(new Dimension(Integer.MAX_VALUE, passwordField.getPreferredSize().height));

        // Styled Login Button
        JButton loginButton = createStyledButton("Login");
        loginButton.addActionListener(e -> {
            String username = usernameField.getText();
            String password = new String(passwordField.getPassword());
            if (authenticateUser(username, password)) {
                frame.dispose();
                new UserDashboard(username, serverIp, serverPort).display();
            } else {
                JOptionPane.showMessageDialog(frame, "Invalid username or password!", "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        // Styled Signup Button
        JButton signupButton = createStyledButton("Sign Up");
        signupButton.addActionListener(e -> {
            frame.dispose();
            new UserSignUp(serverIp, serverPort).display();
        });

        // Layout configuration for username, password fields
        JPanel formPanel = new JPanel();
        formPanel.setLayout(new GridLayout(4, 1, 10, 10));
        formPanel.add(usernameLabel);
        formPanel.add(usernameField);
        formPanel.add(passwordLabel);
        formPanel.add(passwordField);

        panel.add(titleLabel);
        panel.add(formPanel);
        panel.add(loginButton);
        panel.add(signupButton);

        // Footer label
        JLabel footerLabel = new JLabel("Created by Hamid, Malak, and Saad", SwingConstants.CENTER);
        footerLabel.setFont(new Font("Arial", Font.ITALIC, 12));
        footerLabel.setForeground(new Color(100, 100, 100));

        // Add the main panel and footer to the frame
        frame.add(panel, BorderLayout.CENTER);
        frame.add(footerLabel, BorderLayout.SOUTH);
        frame.setVisible(true);
    }

    private boolean authenticateUser(String username, String password) {
        try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + serverIp + ":3306/file_transfer", "root", "H@mm1d2024");
             Statement stmt = conn.createStatement()) {
            String query = "SELECT * FROM users WHERE username = '" + username + "' AND password = '" + password + "'";
            ResultSet rs = stmt.executeQuery(query);
            return rs.next();
        } catch (Exception ex) {
            ex.printStackTrace();
            return false;
        }
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
}
