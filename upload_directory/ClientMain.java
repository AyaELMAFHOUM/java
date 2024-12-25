import javax.swing.*;

public class ClientMain {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new ConnectionSetup().display(); // Start with the connection setup screen
        });
    }
}
