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
    private Socket socket;
    private ObjectOutputStream oos;
    private ObjectInputStream ois;
    private DataOutputStream dos;
    private DataInputStream dis;
    private List<ClientHandler> allClients;
    private String username;
    private boolean isLoggedIn = false;

    private static final String SERVER_FILES_DIR = "resources/Server/";

    public ClientHandler(Socket socket, List<ClientHandler> allClients) {
        this.socket = socket;
        this.allClients = allClients;
        try {
            this.oos = new ObjectOutputStream(socket.getOutputStream());
            this.oos.flush();
            this.ois = new ObjectInputStream(socket.getInputStream());
            this.dos = new DataOutputStream(socket.getOutputStream());
            this.dis = new DataInputStream(socket.getInputStream());
            Files.createDirectories(Paths.get(SERVER_FILES_DIR)); // Ensure server directory exists
        } catch (IOException e) {
            System.err.println("ClientHandler IO Exception during setup for " + socket.getInetAddress() + ": " + e.getMessage());
            closeConnection();
        }
    }

    public String getUsername() {
        return username;
    }

    @Override
    public void run() {
        try {
            performLogin();

            if (isLoggedIn) {
                processClientMessages();
            }
        } catch (SocketException e) {
            System.out.println("Client " + getClientIdentifier() + " disconnected abruptly: " + e.getMessage());
        } catch (EOFException e) {
            System.out.println("Client " + getClientIdentifier() + " closed the connection.");
        } catch (IOException | ClassNotFoundException e) {
            if (isLoggedIn) {
                System.err.println("Error handling client " + username + ": " + e.getMessage());
                e.printStackTrace();
            }
        } finally {
            cleanupAfterDisconnect();
        }
    }

    private void performLogin() throws IOException, ClassNotFoundException {
        while (!isLoggedIn && !socket.isClosed()) {
            Message loginMsg = (Message) ois.readObject();
            if (loginMsg.type == Message.LOGIN_REQUEST) {
                handleLoginRequest(loginMsg);
            } else {
                sendMessageToClient(new Message(Message.LOGIN_FAILURE, "Server", "Invalid request. Please login first."));
            }
        }
    }

    private void processClientMessages() throws IOException, ClassNotFoundException {
        while (isLoggedIn && !socket.isClosed()) {
            Message clientMessage = (Message) ois.readObject();
            handleMessage(clientMessage);
        }
    }

    private String getClientIdentifier() {
        return username != null ? username : socket.getInetAddress().toString();
    }

    private void handleMessage(Message msg) throws IOException {
        switch (msg.type) {
            case Message.CHAT_MESSAGE:
                handleChatMessage(msg);
                break;
            case Message.FILE_UPLOAD_REQUEST_METADATA:
                handleFileUploadRequest(msg);
                break;
            case Message.FILE_LIST_REQUEST:
                handleFileListRequest();
                break;
            case Message.FILE_DOWNLOAD_REQUEST:
                handleFileDownloadRequest(msg);
                break;
            case Message.CLIENT_DISCONNECT:
                handleClientDisconnect();
                break;
            default:
                System.out.println("Unknown message type " + msg.type + " from " + username);
                sendMessageToClient(new Message(Message.GENERAL_SERVER_MESSAGE, "Server", "Unknown request type."));
        }
    }

    private void handleLoginRequest(Message loginMsg) throws IOException {
        String[] credentials = loginMsg.content.split(":", 2);
        if (credentials.length != 2) {
            sendMessageToClient(new Message(Message.LOGIN_FAILURE, "Server", "Malformed login request."));
            return;
        }

        String attemptUsername = credentials[0];
        String attemptPassword = credentials[1];

        synchronized (allClients) {
            boolean userAlreadyActive = allClients.stream()
                    .anyMatch(ch -> ch.isLoggedIn && ch.getUsername().equals(attemptUsername));
            if (userAlreadyActive) {
                sendMessageToClient(new Message(Message.LOGIN_FAILURE, "Server", "User " + attemptUsername + " is already logged in."));
                return;
            }
        }

        if (Server.authenticate(attemptUsername, attemptPassword)) {
            this.username = attemptUsername;
            this.isLoggedIn = true;
            synchronized (allClients) {
                allClients.add(this);
            }
            sendMessageToClient(new Message(Message.LOGIN_SUCCESS, "Server", "Welcome " + username + "!"));
            System.out.println(username + " logged in. Total clients: " + allClients.size());
            broadcast(new Message(Message.USER_JOINED_NOTIFICATION, "Server", username + " has joined the chat."), this);
        } else {
            sendMessageToClient(new Message(Message.LOGIN_FAILURE, "Server", "Invalid username or password."));
        }
    }

    private void handleChatMessage(Message msg) {
        System.out.println("Chat from " + msg.sender + ": " + msg.content);
        broadcast(new Message(Message.CHAT_MESSAGE, this.username, msg.content), this);
    }

    private void handleFileUploadRequest(Message msg) throws IOException {
        System.out.println("File upload request from " + username + ": " + msg.content + " (" + msg.fileSize + " bytes)");
        sendMessageToClient(new Message(Message.FILE_UPLOAD_READY_FOR_BYTES, "Server", msg.content)); // Acknowledge
        receiveFileFromClient(msg.content, msg.fileSize);
    }

    private void handleFileListRequest() throws IOException {
        System.out.println("File list request from " + username);
        sendFileListToClient();
    }

    private void handleFileDownloadRequest(Message msg) throws IOException {
        System.out.println("File download request from " + username + " for: " + msg.content);
        sendFileToClient(msg.content);
    }

    private void handleClientDisconnect() {
        System.out.println(username + " is disconnecting.");
        isLoggedIn = false;
    }

    private void sendMessageToClient(Message msg) {
        try {
            if (oos != null && !socket.isClosed()) {
                oos.writeObject(msg);
                oos.flush();
            }
        } catch (IOException e) {
        }
    }

    private void broadcast(Message msg, ClientHandler senderHandler) {
        System.out.println("Broadcasting: \"" + msg.content + "\" (from " + msg.sender + ")");
        synchronized (allClients) {
            for (ClientHandler client : allClients) {
                if (client.isLoggedIn && client != senderHandler) {
                    client.sendMessageToClient(msg);
                }
            }
        }
    }

    private void sendFileListToClient() throws IOException {
        File dir = new File(SERVER_FILES_DIR);
        String fileListStr = "";
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles(f -> f.isFile());
            if (files != null) {
                fileListStr = java.util.Arrays.stream(files)
                        .map(File::getName)
                        .collect(Collectors.joining(","));
            }
        } else {
            Files.createDirectories(Paths.get(SERVER_FILES_DIR));
        }
        sendMessageToClient(new Message(Message.FILE_LIST_RESPONSE, "Server", fileListStr));
    }

    private void sendFileToClient(String fileName) throws IOException {
        Path filePath = Paths.get(SERVER_FILES_DIR, fileName);
        if (!Files.exists(filePath) || !Files.isReadable(filePath)) {
            sendMessageToClient(new Message(Message.FILE_DOWNLOAD_ERROR, "Server", "File not found or not accessible: " + fileName));
            return;
        }

        long fileSize = Files.size(filePath);
        sendMessageToClient(new Message(Message.FILE_DOWNLOAD_INFO_AND_START, "Server", fileName, fileSize));
        sendMessageToClient(new Message(Message.FILE_DOWNLOAD_SENDING_BYTES, "Server", fileName)); // Signal start of byte stream

        try (InputStream fileIs = Files.newInputStream(filePath)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fileIs.read(buffer)) != -1) {
                dos.write(buffer, 0, bytesRead);
            }
            dos.flush();
        }
        System.out.println("File " + fileName + " (" + fileSize + " bytes) sent to " + username);
    }

    private void receiveFileFromClient(String filename, long fileLength) throws IOException {
        Path outputPath = Paths.get(SERVER_FILES_DIR, filename);
        try (OutputStream fileOs = Files.newOutputStream(outputPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[8192];
            long bytesReceived = 0;
            int currentBytesRead;

            while (bytesReceived < fileLength) {
                int toRead = (int) Math.min(buffer.length, fileLength - bytesReceived);
                currentBytesRead = dis.read(buffer, 0, toRead);
                if (currentBytesRead == -1) {
                    throw new EOFException("Connection closed by client during file upload of " + filename);
                }
                fileOs.write(buffer, 0, currentBytesRead);
                bytesReceived += currentBytesRead;
            }
            fileOs.flush();
            System.out.println("File " + filename + " (" + fileLength + " bytes) received from " + username + " and saved.");
            sendMessageToClient(new Message(Message.FILE_UPLOAD_CONFIRMATION, "Server", "File '" + filename + "' uploaded successfully."));
        } catch (IOException e) {
            try { Files.deleteIfExists(outputPath); } catch (IOException ignored) {} // Attempt to delete partial file
        }
    }

    private void cleanupAfterDisconnect() {
        if (isLoggedIn) {
            Server.removeClient(this);
        }
        closeConnection();
    }

    private void closeConnection() {
        try {
            if (ois != null) ois.close();
            if (oos != null) oos.close();
            if (dis != null) dis.close();
            if (dos != null) dos.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) {

        }
        System.out.println("Connection closed for " + getClientIdentifier());
    }
}