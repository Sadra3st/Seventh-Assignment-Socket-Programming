package Client;

import Shared.Message;
import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Scanner;

public class Client {
    private static Socket socket;
    private static ObjectOutputStream oos;
    private static ObjectInputStream ois;
    private static DataOutputStream dos; // For sending raw file bytes
    private static DataInputStream dis;  // For receiving raw file bytes

    private static String username;
    private static volatile boolean loggedIn = false;
    private static volatile boolean running = true;
    private static ClientReceiver clientReceiver;
    private static final String CLIENT_BASE_DIR = "resources/Client/";
    private static String clientSpecificDir;


    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        try {
            socket = new Socket("localhost", 12345);
            System.out.println("Connected to server: " + socket.getInetAddress());


            oos = new ObjectOutputStream(socket.getOutputStream());
            oos.flush();
            ois = new ObjectInputStream(socket.getInputStream());

            dos = new DataOutputStream(socket.getOutputStream());
            dis = new DataInputStream(socket.getInputStream());



            System.out.println("== Welcome to Room ==");
            while (!loggedIn && running) {
                System.out.print("Username:\n " +
                        "(use user1(1-5))");
                String inputUsername = scanner.nextLine().trim();
                if (inputUsername.isEmpty()) continue;

                System.out.print("Password: \n" +
                        "(1234)")
                ;
                String password = scanner.nextLine().trim();
                if (password.isEmpty()) continue;

                sendLoginRequest(inputUsername, password);


                try {
                    Message serverResponse = (Message) ois.readObject();
                    if (serverResponse.type == Message.LOGIN_SUCCESS) {
                        username = inputUsername;
                        loggedIn = true;
                        System.out.println(serverResponse.content);
                        clientSpecificDir = CLIENT_BASE_DIR + username + "/";
                        Files.createDirectories(Paths.get(clientSpecificDir));
                    } else if (serverResponse.type == Message.LOGIN_FAILURE) {
                        System.err.println("Login failed: " + serverResponse.content);
                    } else {
                        System.err.println("Unexpected response during login: " + serverResponse);
                    }
                } catch (ClassNotFoundException e) {
                    System.err.println("Error reading login response: " + e.getMessage());
                    running = false;
                } catch (EOFException | SocketException e) {
                    System.err.println("Disconnected from server during login. Exiting.");
                    running = false;
                }
            }

            if (loggedIn) {

                clientReceiver = new ClientReceiver(ois, dis, clientSpecificDir, username);
                new Thread(clientReceiver).start();


                while (running && loggedIn) {
                    printMenu();
                    System.out.print("Enter choice: ");
                    String choice = scanner.nextLine().trim();

                    if (!socket.isConnected() || socket.isClosed()) {
                        System.out.println("Connection to server lost.");
                        running = false;
                        break;
                    }

                    switch (choice) {
                        case "1":
                            enterChat(scanner);
                            break;
                        case "2":
                            uploadFile(scanner);
                            break;
                        case "3":
                            requestDownload(scanner);
                            break;
                        case "0":
                            System.out.println("Exiting...");
                            sendMessageToServer(new Message(Message.CLIENT_DISCONNECT, username, "User initiated disconnect."));
                            running = false;
                            loggedIn = false;
                            break;
                        default:
                            System.out.println("Invalid choice. Please try again.");
                    }
                }
            }

        } catch (IOException e) {
            System.err.println("Client I/O error: " + e.getMessage());
        } finally {
            running = false;
            if (clientReceiver != null) {
                clientReceiver.stopRunning();
            }
            closeClientResources();
            scanner.close();
            System.out.println("Client application closed.");
        }
    }

    private static void printMenu() {
        System.out.println("\n--- Main Menu (" + username + ") ---");
        System.out.println("1. Enter chat box");
        System.out.println("2. Upload a file");
        System.out.println("3. Download a file");
        System.out.println("0. Exit");
    }

    private static void sendLoginRequest(String reqUsername, String password) {
        sendMessageToServer(new Message(Message.LOGIN_REQUEST, reqUsername, reqUsername + ":" + password));
    }

    private static void enterChat(Scanner scanner) {
        System.out.println("You have entered the chat. Type '/exit' to return to the menu.");
        System.out.println("-----------------------------------------------------------");
        clientReceiver.setInChatMode(true);

        while (running && loggedIn) {
            String messageString = scanner.nextLine();
            if ("/exit".equalsIgnoreCase(messageString.trim())) {
                break;
            }
            if (messageString.trim().isEmpty()) continue;

            sendChatMessage(messageString);
        }
        clientReceiver.setInChatMode(false);
        System.out.println("-----------------------------------------------------------");
        System.out.println("Exited chat box.");
    }

    private static void sendChatMessage(String messageToSend) {
        sendMessageToServer(new Message(Message.CHAT_MESSAGE, username, messageToSend));
    }

    private static void uploadFile(Scanner scanner) {
        File clientDir = new File(clientSpecificDir);
        if (!clientDir.exists()) {
            System.out.println("Client directory " + clientSpecificDir + " does not exist. Creating it.");
            clientDir.mkdirs();
        }

        File[] files = clientDir.listFiles(File::isFile);
        if (files == null || files.length == 0) {
            System.out.println("No files to upload in your directory: " + clientSpecificDir);
            System.out.println("Place some files there (e.g., test.txt) and try again.");
            return;
        }

        System.out.println("Select a file to upload from " + clientSpecificDir + ":");
        for (int i = 0; i < files.length; i++) {
            System.out.println((i + 1) + ". " + files[i].getName() + " (" + files[i].length() + " bytes)");
        }

        System.out.print("Enter file number (or 0 to cancel): ");
        int choice;
        try {
            choice = Integer.parseInt(scanner.nextLine());
            if (choice == 0) {
                System.out.println("Upload cancelled.");
                return;
            }
            choice--;
        } catch (NumberFormatException e) {
            System.out.println("Invalid input. Please enter a number.");
            return;
        }

        if (choice < 0 || choice >= files.length) {
            System.out.println("Invalid file choice.");
            return;
        }

        File selectedFile = files[choice];
        String fileName = selectedFile.getName();
        long fileSize = selectedFile.length();

        try {
            System.out.println("Requesting to upload " + fileName + " (" + fileSize + " bytes)...");
            sendMessageToServer(new Message(Message.FILE_UPLOAD_REQUEST_METADATA, username, fileName, fileSize));



            System.out.println("Sending file bytes for " + fileName + "...");
            try (InputStream fileIs = Files.newInputStream(selectedFile.toPath())) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fileIs.read(buffer)) != -1) {
                    dos.write(buffer, 0, bytesRead);
                }
                dos.flush();
            }
            System.out.println("File " + fileName + " bytes sent. Waiting for server confirmation...");

        } catch (IOException e) {
            System.err.println("Error during file upload for " + fileName + ": " + e.getMessage());
        }
    }


    private static void requestDownload(Scanner scanner) {

        System.out.println("Requesting file list from server...");
        sendMessageToServer(new Message(Message.FILE_LIST_REQUEST, username, ""));
        System.out.println("Server will list available files. Please check the output.");
        System.out.print("Enter the name of the file you want to download (or type 'cancel'): ");
        String fileNameToDownload = scanner.nextLine().trim();

        if (fileNameToDownload.equalsIgnoreCase("cancel") || fileNameToDownload.isEmpty()) {
            System.out.println("Download cancelled.");
            return;
        }


        System.out.println("Requesting to download file: " + fileNameToDownload);
        sendMessageToServer(new Message(Message.FILE_DOWNLOAD_REQUEST, username, fileNameToDownload));
        System.out.println("If the file exists, download will begin. Check console for progress/completion messages from receiver.");
    }


    public static void sendMessageToServer(Message msg) {
        try {
            if (oos != null && socket != null && !socket.isClosed()) {
                oos.writeObject(msg);
                oos.flush();
            } else {
                System.err.println("Cannot send message. Not connected or output stream closed.");
                running = false;
            }
        } catch (IOException e) {
            System.err.println("Error sending message to server: " + e.getMessage());

            running = false;
            loggedIn = false;
        }
    }

    private static void closeClientResources() {
        System.out.println("Closing client resources...");
        try {
            if (ois != null) ois.close();
            if (oos != null) oos.close();
            if (dis != null) dis.close();
            if (dos != null) dos.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) {
            System.err.println("Error closing client resources: " + e.getMessage());
        }
    }


    public static void handleServerInitiatedDisconnect() {
        if (running) {
            System.out.println("Disconnected by server or connection lost.");
            running = false;
            loggedIn = false;
        }
    }
}
