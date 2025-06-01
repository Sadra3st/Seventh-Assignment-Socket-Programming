package Client;

import Shared.Message;
import java.io.*;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class ClientReceiver implements Runnable {
    private ObjectInputStream ois;
    private DataInputStream dis;
    private volatile boolean running = true;
    private volatile boolean inChatMode = false;
    private String clientFileDirectory;
    private String clientUsername;


    private String pendingDownloadFilename;
    private long pendingDownloadFilesize;


    public ClientReceiver(ObjectInputStream ois, DataInputStream dis, String clientFileDir, String username) {
        this.ois = ois;
        this.dis = dis;
        this.clientFileDirectory = clientFileDir;
        this.clientUsername = username;
    }

    public void setInChatMode(boolean mode) {
        this.inChatMode = mode;
    }

    public void stopRunning() {
        this.running = false;
    }

    @Override
    public void run() {
        try {
            while (running) {
                Message serverMessage = (Message) ois.readObject();
                if (serverMessage == null) {
                    if (running) Client.handleServerInitiatedDisconnect();
                    break;
                }
                processServerMessage(serverMessage);
            }
        } catch (SocketException e) {
            if (running) {
                Client.handleServerInitiatedDisconnect();
            }
        } catch (EOFException e) {
            if (running) {
                Client.handleServerInitiatedDisconnect();
            }
        } catch (IOException | ClassNotFoundException e) {
            if (running) {
                Client.handleServerInitiatedDisconnect();
            }
        } finally {
            System.out.println("[Receiver] Thread stopped.");
        }
    }

    private void processServerMessage(Message msg) throws IOException {
        String prefix = inChatMode ? "" : "[" + msg.sender + " ("+msg.type+")]: "; // Added type for clarity
        switch (msg.type) {
            case Message.CHAT_MESSAGE:
                if (!msg.sender.equals(this.clientUsername)) { // Don't print own echoed messages
                    System.out.println((inChatMode ? "" : "[" + msg.sender + "]: ") + msg.content);
                }
                break;
            case Message.USER_JOINED_NOTIFICATION:
            case Message.USER_LEFT_NOTIFICATION:
                System.out.println("\n[System] " + msg.content);
                promptForInput();
                break;
            case Message.GENERAL_SERVER_MESSAGE:
            case Message.FILE_UPLOAD_CONFIRMATION:
                System.out.println("\n[Server] " + msg.content);
                promptForInput();
                break;
            case Message.FILE_UPLOAD_READY_FOR_BYTES:
                System.out.println("\n[Server] Ready for client to send file bytes for: " + msg.content);
                promptForInput();
                break;
            case Message.FILE_LIST_RESPONSE:
                System.out.println("\n--- Files available on Server ---");
                if (msg.content == null || msg.content.isEmpty()) {
                    System.out.println("No files available.");
                } else {
                    String[] files = msg.content.split(",");
                    for (String fileName : files) {
                        System.out.println("- " + fileName);
                    }
                }
                System.out.println("-------------------------------");
                promptForInput();
                break;
            case Message.FILE_DOWNLOAD_INFO_AND_START:
                this.pendingDownloadFilename = msg.content;
                this.pendingDownloadFilesize = msg.fileSize;
                System.out.println("\n[Server] Preparing to send file: " + pendingDownloadFilename + " (" + pendingDownloadFilesize + " bytes).");

                break;
            case Message.FILE_DOWNLOAD_SENDING_BYTES:
                if (this.pendingDownloadFilename != null && this.pendingDownloadFilesize > 0) {
                    System.out.println("\n[Server] Now receiving file bytes for: " + this.pendingDownloadFilename);
                    receiveFileFromServer(this.pendingDownloadFilename, this.pendingDownloadFilesize);
                    this.pendingDownloadFilename = null;
                    this.pendingDownloadFilesize = 0;
                } else {
                    System.err.println("\n[Receiver] Received FILE_DOWNLOAD_SENDING_BYTES but no pending file download info was set.");
                }
                promptForInput();
                break;
            case Message.FILE_DOWNLOAD_ERROR:
                System.err.println("\n[Server] Download Error: " + msg.content);
                this.pendingDownloadFilename = null;
                this.pendingDownloadFilesize = 0;
                promptForInput();
                break;
            case Message.LOGIN_SUCCESS:
            case Message.LOGIN_FAILURE:
                System.out.println("\n[Server Auth] " + msg.content);
                promptForInput();
                break;
            default:
                System.out.println("\n[Server - Unhandled Type " + msg.type + " From " + msg.sender + "]: " + msg.content);
                promptForInput();
        }
    }

    private void promptForInput() {
        if (!inChatMode) {
            System.out.print("\nEnter choice (from main menu): ");
        }
    }

    private void receiveFileFromServer(String fileName, long fileSize) {
        if (fileName == null || fileName.isEmpty() || fileSize <= 0) {
            System.err.println("\n[Receiver] Error: Invalid file details for download. Filename: " + fileName + ", Size: " + fileSize);
            return;
        }

        Path outputPath = Paths.get(clientFileDirectory, fileName);
        System.out.println("\n[Receiver] Starting download of " + fileName + " (" + fileSize + " bytes) to " + outputPath);

        try (OutputStream fileOs = Files.newOutputStream(outputPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[8192]; // 8KB buffer
            long bytesReceived = 0;
            int bytesReadCount;

            while (bytesReceived < fileSize) {
                if (!running) { // Check if client is shutting down
                    System.out.println("\n[Receiver] Download interrupted due to client shutdown.");
                    throw new IOException("Client shutdown during file download.");
                }
                int toRead = (int) Math.min(buffer.length, fileSize - bytesReceived);
                bytesReadCount = dis.read(buffer, 0, toRead);
                if (bytesReadCount == -1) {
                    throw new EOFException("Server closed connection during file download of " + fileName + ".");
                }
                fileOs.write(buffer, 0, bytesReadCount);
                bytesReceived += bytesReadCount;
                System.out.print("\r[Receiver] Downloading " + fileName + ": " + bytesReceived + "/" + fileSize + " bytes (" + (bytesReceived * 100 / fileSize) + "%)");
            }
            fileOs.flush();
            System.out.println("\n[Receiver] File " + fileName + " downloaded successfully to " + outputPath + ".");
        } catch (EOFException e) {
            try { Files.deleteIfExists(outputPath); } catch (IOException ignored) {}
        }
        catch (IOException e) {
            try { Files.deleteIfExists(outputPath); } catch (IOException ignored) {}
        }
    }
}