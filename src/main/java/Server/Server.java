package Server;

import Shared.Message;
import Shared.User;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Server {
    private static final int PORT = 12345;
    private static final User[] DEF_USERS = {
            new User("user1", "1234"),
            new User("user2", "1234"),
            new User("user3", "1234"),
            new User("user4", "1234"),
            new User("user5", "1234"),
    };

    public static List<ClientHandler> clients = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) {
        System.out.println("Server starting on port " + PORT + "...");
        try (ServerSocket srvSocket = new ServerSocket(PORT)) {
            System.out.println("Server started. Waiting for clients...");
            while (true) {
                try {
                    Socket cliSocket = srvSocket.accept();
                    System.out.println("New client connection attempt from: " + cliSocket.getInetAddress());
                    ClientHandler cliHandler = new ClientHandler(cliSocket, clients);
                    new Thread(cliHandler).start();
                } catch (IOException e) {
                    System.err.println("Error accepting client connection: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Could not start server on port " + PORT + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static boolean authUser(String uname, String pass) {
        for (User defUser : DEF_USERS) {
            if (defUser.getUsername().equals(uname)) {
                return defUser.getPassword().equals(pass);
            }
        }
        return false;
    }

    public static void addCli(ClientHandler cliHandler) {
        synchronized (clients) {
            clients.add(cliHandler);
            bcastUserList();
        }
    }

    public static void remCli(ClientHandler cliHandler) {
        synchronized (clients) {
            clients.remove(cliHandler);
            if (cliHandler.getUname() != null) {
                System.out.println("Client " + cliHandler.getUname() + " disconnected. Remaining: " + clients.size());
            } else {
                System.out.println("Unauth client disconnected. Remaining: " + clients.size());
            }
            bcastUserList();
        }
    }

    public static void bcastUserList() {
        synchronized (clients) {
            List<String> unames = new ArrayList<>();
            for (ClientHandler ch : clients) {
                if (ch.isLoggedIn() && ch.getUname() != null) {
                    unames.add(ch.getUname());
                }
            }
            Message ulistMsg = new Message(Message.USER_LIST_UPDATE, "Server", unames.toArray(new String[0]));
            for (ClientHandler ch : clients) {
                if (ch.isLoggedIn()) {
                    ch.sendMsgToCli(ulistMsg);
                }
            }
        }
    }
}