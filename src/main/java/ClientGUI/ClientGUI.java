package ClientGUI;

import Shared.Message;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ClientGUI extends JFrame {
    private static final String S_ADDR = "localhost";
    private static final int S_PORT = 12345;
    private static final String C_DIR = "resources/Client/";

    private Socket sock;
    private ObjectOutputStream objOut;
    private ObjectInputStream objIn;
    private DataOutputStream dataOut;
    private DataInputStream dataIn;

    private String uname;
    private String cDir;
    private ClientWorker worker;

    private CardLayout cardLyt;
    private JPanel mainPnl;

    private JPanel loginPnl;
    private JTextField userFld;
    private JPasswordField passFld;
    private JButton loginBtn;
    private JLabel loginStatusLbl;

    private JPanel chatPnl;
    private JTextArea chatTxt;
    private JTextField msgFld;
    private JButton sendBtn;
    private JList<String> userLst;
    private DefaultListModel<String> userLstMdl;
    private JButton uploadBtn;
    private JButton downloadBtn;
    private JButton logoutBtn;

    public ClientGUI() {
        setTitle("Chat Client");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(800, 600);
        setLocationRelativeTo(null);

        cardLyt = new CardLayout();
        mainPnl = new JPanel(cardLyt);

        createLoginPnl();
        createChatPnl();

        mainPnl.add(loginPnl, "Login");
        mainPnl.add(chatPnl, "Chat");

        add(mainPnl);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                doLogoutOrExit();
            }
        });

        cardLyt.show(mainPnl, "Login");
        setVisible(true);
    }

    private void createLoginPnl() {
        loginPnl = new JPanel(new GridBagLayout());
        loginPnl.setBorder(new EmptyBorder(20, 20, 20, 20));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; loginPnl.add(new JLabel("Username:"), gbc);
        userFld = new JTextField(20);
        gbc.gridx = 1; gbc.gridy = 0; loginPnl.add(userFld, gbc);

        gbc.gridx = 0; gbc.gridy = 1; loginPnl.add(new JLabel("Password:"), gbc);
        passFld = new JPasswordField(20);
        gbc.gridx = 1; gbc.gridy = 1; loginPnl.add(passFld, gbc);

        loginBtn = new JButton("Login");
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2; gbc.anchor = GridBagConstraints.CENTER;
        loginPnl.add(loginBtn, gbc);

        loginStatusLbl = new JLabel(" ");
        loginStatusLbl.setForeground(Color.RED);
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2;
        loginPnl.add(loginStatusLbl, gbc);

        loginBtn.addActionListener(e -> doLogin());
        userFld.addActionListener(e -> doLogin());
        passFld.addActionListener(e -> doLogin());
    }

    private void createChatPnl() {
        chatPnl = new JPanel(new BorderLayout(10, 10));
        chatPnl.setBorder(new EmptyBorder(10, 10, 10, 10));

        chatTxt = new JTextArea();
        chatTxt.setEditable(false);
        chatTxt.setLineWrap(true);
        chatTxt.setWrapStyleWord(true);
        JScrollPane chatSP = new JScrollPane(chatTxt);
        chatPnl.add(chatSP, BorderLayout.CENTER);

        userLstMdl = new DefaultListModel<>();
        userLst = new JList<>(userLstMdl);
        JScrollPane userSP = new JScrollPane(userLst);
        userSP.setPreferredSize(new Dimension(150, 0));
        chatPnl.add(userSP, BorderLayout.EAST);

        JPanel botPnl = new JPanel(new BorderLayout(5,0));
        msgFld = new JTextField();
        sendBtn = new JButton("Send");
        botPnl.add(msgFld, BorderLayout.CENTER);
        botPnl.add(sendBtn, BorderLayout.EAST);
        chatPnl.add(botPnl, BorderLayout.SOUTH);

        JPanel topBtnPnl = new JPanel(new FlowLayout(FlowLayout.LEFT));
        uploadBtn = new JButton("Upload File");
        downloadBtn = new JButton("Download File");
        logoutBtn = new JButton("Logout");
        topBtnPnl.add(uploadBtn);
        topBtnPnl.add(downloadBtn);
        topBtnPnl.add(logoutBtn);
        chatPnl.add(topBtnPnl, BorderLayout.NORTH);

        sendBtn.addActionListener(e -> sendMsg());
        msgFld.addActionListener(e -> sendMsg());
        uploadBtn.addActionListener(e -> doUpload());
        downloadBtn.addActionListener(e -> doDownload());
        logoutBtn.addActionListener(e -> doLogoutOrExit());
    }

    private void doLogin() {
        String u = userFld.getText().trim();
        String p = new String(passFld.getPassword()).trim();

        if (u.isEmpty() || p.isEmpty()) {
            loginStatusLbl.setText("Username and password cannot be empty.");
            return;
        }
        loginStatusLbl.setText("Attempting login...");
        loginBtn.setEnabled(false);

        new Thread(() -> {
            try {
                sock = new Socket(S_ADDR, S_PORT);
                objOut = new ObjectOutputStream(sock.getOutputStream());
                objOut.flush();
                objIn = new ObjectInputStream(sock.getInputStream());
                dataOut = new DataOutputStream(sock.getOutputStream());
                dataIn = new DataInputStream(sock.getInputStream());

                objOut.writeObject(new Message(Message.LOGIN_REQUEST, u, u + ":" + p));
                objOut.flush();

                Message resp = (Message) objIn.readObject();
                if (resp.type == Message.LOGIN_SUCCESS) {
                    this.uname = u;
                    this.cDir = C_DIR + this.uname + "/";
                    Files.createDirectories(Paths.get(this.cDir));

                    worker = new ClientWorker(objIn, dataIn, this);
                    new Thread(worker).start();

                    SwingUtilities.invokeLater(() -> {
                        setTitle("Chat Client - " + this.uname);
                        cardLyt.show(mainPnl, "Chat");
                        loginBtn.setEnabled(true);
                        loginStatusLbl.setText(" ");
                        passFld.setText("");
                    });
                } else { // LOGIN_FAILURE
                    SwingUtilities.invokeLater(() -> {
                        loginStatusLbl.setText("Login failed: " + (resp.content != null ? resp.content : "No additional info."));
                        loginBtn.setEnabled(true);
                        closeRes();
                    });
                }
            } catch (EOFException eofEx) {
                SwingUtilities.invokeLater(() -> {
                    loginStatusLbl.setText("Connection error: Server closed connection (EOF).");
                    loginBtn.setEnabled(true);
                    closeRes();
                });
                eofEx.printStackTrace();
            } catch (SocketException sockEx) {
                SwingUtilities.invokeLater(() -> {
                    loginStatusLbl.setText("Connection error: Socket issue (" + (sockEx.getMessage() != null ? sockEx.getMessage() : "N/A") + ").");
                    loginBtn.setEnabled(true);
                    closeRes();
                });
                sockEx.printStackTrace();
            } catch (ClassNotFoundException cnfEx) {
                SwingUtilities.invokeLater(() -> {
                    loginStatusLbl.setText("Error: Class not found (" + (cnfEx.getMessage() != null ? cnfEx.getMessage() : "N/A") + ").");
                    loginBtn.setEnabled(true);
                    closeRes();
                });
                cnfEx.printStackTrace();
            }
            catch (Exception ex) {
                String errorMsg = ex.getMessage();
                String displayText = "Connection error: " + (errorMsg != null ? errorMsg : "Unknown (null message)");
                SwingUtilities.invokeLater(() -> {
                    loginStatusLbl.setText(displayText);
                    loginBtn.setEnabled(true);
                    closeRes();
                });
                ex.printStackTrace();
            }
        }).start();
    }

    private void sendMsg() {
        String txt = msgFld.getText().trim();
        if (!txt.isEmpty() && objOut != null) {
            try {
                objOut.writeObject(new Message(Message.CHAT_MESSAGE, uname, txt));
                objOut.flush();
                msgFld.setText("");
            } catch (IOException e) {
                appendChat("Error sending message: " + e.getMessage() + "\n");
            }
        }
    }

    private void doUpload() {
        JFileChooser fc = new JFileChooser(cDir);
        fc.setDialogTitle("Select File to Upload");
        int res = fc.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            File selFile = fc.getSelectedFile();
            if (selFile.exists() && selFile.isFile()) {
                new Thread(() -> {
                    try {
                        appendChat("[System] Requesting to upload " + selFile.getName() + "...\n");
                        objOut.writeObject(new Message(Message.FILE_UPLOAD_REQUEST_METADATA, uname, selFile.getName(), selFile.length()));
                        objOut.flush();

                        try (InputStream fIs = Files.newInputStream(selFile.toPath())) {
                            appendChat("[System] Sending file bytes for " + selFile.getName() + "...\n");
                            byte[] buf = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = fIs.read(buf)) != -1) {
                                dataOut.write(buf, 0, bytesRead);
                            }
                            dataOut.flush();
                        }
                        appendChat("[System] File " + selFile.getName() + " bytes sent. Waiting for confirmation...\n");
                    } catch (IOException ex) {
                        appendChat("[System] Error uploading file " + selFile.getName() + ": " + ex.getMessage() + "\n");
                    }
                }).start();
            } else {
                appendChat("[System] Selected file is invalid or does not exist.\n");
            }
        }
    }

    private void doDownload() {
        try {
            if (objOut != null) {
                appendChat("[System] Requesting file list from server...\n");
                objOut.writeObject(new Message(Message.FILE_LIST_REQUEST, uname, ""));
                objOut.flush();
            }
        } catch (IOException e) {
            appendChat("[System] Error requesting file list: " + e.getMessage() + "\n");
        }
    }

    public void procDownloadReq(String fname) {
        JFileChooser fc = new JFileChooser(cDir);
        fc.setDialogTitle("Save Downloaded File As");
        fc.setSelectedFile(new File(fname));
        int res = fc.showSaveDialog(this);

        if (res == JFileChooser.APPROVE_OPTION) {
            File saveFile = fc.getSelectedFile();
            new Thread(() -> {
                try {
                    appendChat("[System] Requesting to download: " + fname + " to " + saveFile.getName() + "\n");
                    objOut.writeObject(new Message(Message.FILE_DOWNLOAD_REQUEST, uname, fname));
                    objOut.flush();
                    if(worker != null) {
                        // Worker needs to know the expected file size from FILE_DOWNLOAD_INFO_AND_START
                        // This part requires ClientWorker to store the size when INFO_AND_START is received.
                        // For now, we pass the path and original name. Worker will use its stored size.
                        worker.setDlFile(saveFile.toPath(), fname);
                    }

                } catch (IOException ex) {
                    appendChat("[System] Error initiating download for " + fname + ": " + ex.getMessage() + "\n");
                }
            }).start();
        } else {
            appendChat("[System] File download cancelled by user.\n");
        }
    }

    public void appendChat(String msg) {
        SwingUtilities.invokeLater(() -> {
            chatTxt.append(msg);
            chatTxt.setCaretPosition(chatTxt.getDocument().getLength());
        });
    }

    public void updateUserLst(String[] users) {
        SwingUtilities.invokeLater(() -> {
            userLstMdl.clear();
            for (String u : users) {
                userLstMdl.addElement(u);
            }
        });
    }

    private void doLogoutOrExit() {
        if (objOut != null && uname != null) { // Check uname to ensure we were logged in
            try {
                objOut.writeObject(new Message(Message.CLIENT_DISCONNECT, uname, "User logging out."));
                objOut.flush();
            } catch (IOException e) {
                System.err.println("Error sending disconnect message: " + e.getMessage());
            }
        }
        closeRes();
        if (worker != null) {
            worker.stopRun();
        }
        dispose();
        System.exit(0);
    }

    private void closeRes() {
        try { if (objIn != null) objIn.close(); } catch (IOException e) { /* e.printStackTrace(); */ }
        try { if (objOut != null) objOut.close(); } catch (IOException e) { /* e.printStackTrace(); */ }
        try { if (dataIn != null) dataIn.close(); } catch (IOException e) { /* e.printStackTrace(); */ }
        try { if (dataOut != null) dataOut.close(); } catch (IOException e) { /* e.printStackTrace(); */ }
        try { if (sock != null && !sock.isClosed()) sock.close(); } catch (IOException e) { /* e.printStackTrace(); */ }
        objOut = null; objIn = null; dataOut = null; dataIn = null; sock = null;
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }
        SwingUtilities.invokeLater(ClientGUI::new);
    }
}
