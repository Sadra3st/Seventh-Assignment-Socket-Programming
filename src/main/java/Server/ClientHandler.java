package Server;

import Shared.Message;
import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Collectors;

public class ClientHandler implements Runnable {
    private Socket sock;
    private ObjectOutputStream objOut;
    private ObjectInputStream objIn;
    private DataOutputStream dataOut;
    private DataInputStream dataIn;
    private List<ClientHandler> clientsList;
    private String uname;
    private boolean loggedIn = false;

    private static final String S_DIR = "resources/Server/";

    public ClientHandler(Socket sock, List<ClientHandler> clientsList) {
        this.sock = sock;
        this.clientsList = clientsList;
        try {
            this.objOut = new ObjectOutputStream(sock.getOutputStream());
            this.objOut.flush();
            this.objIn = new ObjectInputStream(sock.getInputStream());
            this.dataOut = new DataOutputStream(sock.getOutputStream());
            this.dataIn = new DataInputStream(sock.getInputStream());
            Files.createDirectories(Paths.get(S_DIR));
        } catch (IOException e) {
            System.err.println("ClientHandler IO Exception during setup: " + e.getMessage());
            closeConn();
        }
    }

    public String getUname() { return uname; }
    public boolean isLoggedIn() { return loggedIn; }

    @Override
    public void run() {
        try {
            doLogin();
            if (loggedIn) {
                procCliMsgs();
            }
        } catch (SocketException e) {
            System.out.println("Client " + getCliId() + " disconnected abruptly: " + e.getMessage());
        } catch (EOFException e) {
            System.out.println("Client " + getCliId() + " closed the connection (EOF in run).");
        } catch (IOException | ClassNotFoundException e) {
            if (loggedIn) {
                System.err.println("Error handling client " + uname + ": " + e.getMessage());
            } else {
                System.err.println("Error with unauthenticated client " + getCliId() + ": " + e.getMessage());
            }
        } finally {
            cleanup();
        }
    }

    private void doLogin() throws IOException, ClassNotFoundException {
        if (sock.isClosed()) return;

        try {
            Message loginMsg = (Message) objIn.readObject();
            if (loginMsg.type == Message.LOGIN_REQUEST) {
                procLoginReq(loginMsg);
            } else {
                sendMsgToCli(new Message(Message.LOGIN_FAILURE, "Server", "Invalid request type. Expected LOGIN_REQUEST."));
            }
        } catch (EOFException e) {
            System.out.println("Client " + getCliId() + " closed connection before sending login request.");
        }
    }

    private void procCliMsgs() throws IOException, ClassNotFoundException {
        while (loggedIn && !sock.isClosed()) {
            Message cliMsg = (Message) objIn.readObject();
            handleMsg(cliMsg);
        }
    }

    private String getCliId() {
        return uname != null ? uname : sock.getInetAddress().toString();
    }

    private void handleMsg(Message msg) throws IOException {
        switch (msg.type) {
            case Message.CHAT_MESSAGE: procChatMsg(msg); break;
            case Message.FILE_UPLOAD_REQUEST_METADATA: procUploadReq(msg); break;
            case Message.FILE_LIST_REQUEST: procFileListReq(); break;
            case Message.FILE_DOWNLOAD_REQUEST: procDownloadReq(msg); break;
            case Message.CLIENT_DISCONNECT: procCliDisconnect(); break;
            default:
                System.out.println("Unknown message type " + msg.type + " from " + uname);
                sendMsgToCli(new Message(Message.GENERAL_SERVER_MESSAGE, "Server", "Unknown request type."));
        }
    }

    private void procLoginReq(Message loginMsg) throws IOException {
        String[] creds = loginMsg.content.split(":", 2);
        if (creds.length != 2) {
            Message failMsg = new Message(Message.LOGIN_FAILURE, "Server", "Malformed login request.");
            System.out.println("[SERVER DEBUG] Sending LOGIN_FAILURE. Type: " + failMsg.type + ", Sender: " + failMsg.sender + ", Content: '" + failMsg.content + "'");
            sendMsgToCli(failMsg);
            return;
        }
        String attemptUname = creds[0];
        String attemptPass = creds[1];

        synchronized (clientsList) {
            if (clientsList.stream().anyMatch(ch -> ch.loggedIn && ch.getUname().equals(attemptUname))) {
                Message failMsg = new Message(Message.LOGIN_FAILURE, "Server", "User " + attemptUname + " is already logged in.");
                System.out.println("[SERVER DEBUG] Sending LOGIN_FAILURE. Type: " + failMsg.type + ", Sender: " + failMsg.sender + ", Content: '" + failMsg.content + "'");
                sendMsgToCli(failMsg);
                return;
            }
        }

        if (Server.authUser(attemptUname, attemptPass)) {
            this.uname = attemptUname;

            Message successMsg = new Message(Message.LOGIN_SUCCESS, "Server", "Welcome " + uname + "!");
            System.out.println("[SERVER DEBUG] Sending LOGIN_SUCCESS. Type: " + successMsg.type + ", Sender: " + successMsg.sender + ", Content: '" + successMsg.content + "'");
            sendMsgToCli(successMsg);

            this.loggedIn = true;
            Server.addCli(this);

            System.out.println(uname + " logged in. Total clients: " + Server.clients.size());
            bcast(new Message(Message.USER_JOINED_NOTIFICATION, "Server", uname + " has joined the chat."), this);
        } else {
            Message failMsg = new Message(Message.LOGIN_FAILURE, "Server", "Invalid username or password.");
            System.out.println("[SERVER DEBUG] Sending LOGIN_FAILURE. Type: " + failMsg.type + ", Sender: " + failMsg.sender + ", Content: '" + failMsg.content + "'");
            sendMsgToCli(failMsg);
        }
    }

    private void procChatMsg(Message msg) {
        System.out.println("Chat from " + msg.sender + ": " + msg.content);
        bcast(new Message(Message.CHAT_MESSAGE, this.uname, msg.content), this);
    }

    private void procUploadReq(Message msg) throws IOException {
        System.out.println("File upload request from " + uname + ": " + msg.content + " (" + msg.fileSize + " bytes)");
        sendMsgToCli(new Message(Message.FILE_UPLOAD_READY_FOR_BYTES, "Server", msg.content));
        recvFile(msg.content, msg.fileSize);
    }

    private void procFileListReq() throws IOException {
        System.out.println("File list request from " + uname);
        sendFileList();
    }

    private void procDownloadReq(Message msg) throws IOException {
        System.out.println("File download request from " + uname + " for: " + msg.content);
        sendFile(msg.content);
    }

    private void procCliDisconnect() {
        System.out.println(uname + " is disconnecting.");
        loggedIn = false;
    }

    public void sendMsgToCli(Message msg) {
        try {
            if (objOut != null && !sock.isClosed()) {
                objOut.writeObject(msg);
                objOut.flush();
            }
        } catch (IOException e) {
            System.err.println("Error sending message to " + getCliId() + ": " + e.getMessage());
        }
    }

    private void bcast(Message msg, ClientHandler sender) {
        System.out.println("Broadcasting: \"" + msg.content + "\" (from " + msg.sender + ")");
        synchronized (clientsList) {
            for (ClientHandler cli : clientsList) {
                if (cli.loggedIn && cli != sender) {
                    cli.sendMsgToCli(msg);
                }
            }
        }
    }

    private void sendFileList() throws IOException {
        File dir = new File(S_DIR);
        String fListStr = "";
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles(f -> f.isFile());
            if (files != null) {
                fListStr = java.util.Arrays.stream(files)
                        .map(File::getName)
                        .collect(Collectors.joining(","));
            }
        } else {
            Files.createDirectories(Paths.get(S_DIR));
        }
        sendMsgToCli(new Message(Message.FILE_LIST_RESPONSE, "Server", fListStr));
    }

    private void sendFile(String fname) throws IOException {
        Path fPath = Paths.get(S_DIR, fname);
        if (!Files.exists(fPath) || !Files.isReadable(fPath)) {
            sendMsgToCli(new Message(Message.FILE_DOWNLOAD_ERROR, "Server", "File not found: " + fname));
            return;
        }
        long fSize = Files.size(fPath);
        sendMsgToCli(new Message(Message.FILE_DOWNLOAD_INFO_AND_START, "Server", fname, fSize));
        sendMsgToCli(new Message(Message.FILE_DOWNLOAD_SENDING_BYTES, "Server", fname));
        try (InputStream fIs = Files.newInputStream(fPath)) {
            byte[] buf = new byte[8192];
            int bytesRead;
            while ((bytesRead = fIs.read(buf)) != -1) {
                dataOut.write(buf, 0, bytesRead);
            }
            dataOut.flush();
        }
        System.out.println("File " + fname + " (" + fSize + " bytes) sent to " + uname);
    }

    private void recvFile(String fname, long fLen) throws IOException {
        Path outPath = Paths.get(S_DIR, fname);
        try (OutputStream fOs = Files.newOutputStream(outPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[8192];
            long bytesRecvd = 0;
            int curBytesRead;
            while (bytesRecvd < fLen) {
                int toRead = (int) Math.min(buf.length, fLen - bytesRecvd);
                curBytesRead = dataIn.read(buf, 0, toRead);
                if (curBytesRead == -1) throw new EOFException("Client closed connection during file upload.");
                fOs.write(buf, 0, curBytesRead);
                bytesRecvd += curBytesRead;
            }
            fOs.flush();
            System.out.println("File " + fname + " received from " + uname + " and saved.");
            sendMsgToCli(new Message(Message.FILE_UPLOAD_CONFIRMATION, "Server", "File '" + fname + "' uploaded successfully."));
        } catch (IOException e) {
            System.err.println("Error receiving file " + fname + ": " + e.getMessage());
            sendMsgToCli(new Message(Message.FILE_UPLOAD_CONFIRMATION, "Server", "File upload failed for '" + fname + "'."));
            try { Files.deleteIfExists(outPath); } catch (IOException ignored) {}
        }
    }

    private void cleanup() {
        if (loggedIn) {
            bcast(new Message(Message.USER_LEFT_NOTIFICATION, "Server", uname + " has left the chat."), this);
        }
        Server.remCli(this);
        closeConn();
    }

    private void closeConn() {
        try {
            if (objIn != null) objIn.close();
            if (objOut != null) objOut.close();
            if (dataIn != null) dataIn.close();
            if (dataOut != null) dataOut.close();
            if (sock != null && !sock.isClosed()) sock.close();
        } catch (IOException e) {
            System.err.println("Error closing connection for " + getCliId() + ": " + e.getMessage());
        }
        System.out.println("Connection closed for " + getCliId());
    }
}