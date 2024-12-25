import javax.swing.*;

public class ServerMain {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new AdminLogin().display(); // Start with the admin login screen
        });
    }
}
