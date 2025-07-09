package ClientGUI;

import Shared.Message;
import javax.swing.*;
import java.io.*;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class ClientWorker implements Runnable {
    private ObjectInputStream objIn;
    private DataInputStream dataIn;
    private ClientGUI gui;
    private volatile boolean running = true;

    private Path dlPath;
    private String dlName;
    private long dlSize = -1;


    public ClientWorker(ObjectInputStream objIn, DataInputStream dataIn, ClientGUI gui) {
        this.objIn = objIn;
        this.dataIn = dataIn;
        this.gui = gui;
    }

    public void stopRun() {
        running = false;
    }

    public void setDlFile(Path path, String name) {
        this.dlPath = path;
        this.dlName = name;
    }

    public void setDlDetails(Path path, String name, long size) {
        this.dlPath = path;
        this.dlName = name;
        this.dlSize = size;
    }


    @Override
    public void run() {
        try {
            while (running) {
                Message srvMsg = (Message) objIn.readObject();
                if (!running) break;

                procSrvMsg(srvMsg);
            }
        } catch (SocketException e) {
            if (running) gui.appendChat("[System] Connection to server lost: " + e.getMessage() + "\n");
        } catch (EOFException e) {
            if (running) gui.appendChat("[System] Server closed the connection.\n");
        } catch (IOException | ClassNotFoundException e) {
            if (running) gui.appendChat("[System] Error receiving message: " + e.getMessage() + "\n");
            e.printStackTrace();
        } finally {
            gui.appendChat("[System] Disconnected from server.\n");
        }
    }

    private void procSrvMsg(Message msg) {
        SwingUtilities.invokeLater(() -> {
            switch (msg.type) {
                case Message.CHAT_MESSAGE:
                    gui.appendChat(msg.sender + ": " + msg.content + "\n");
                    break;
                case Message.USER_JOINED_NOTIFICATION:
                case Message.USER_LEFT_NOTIFICATION:
                case Message.GENERAL_SERVER_MESSAGE:
                    gui.appendChat("[System] " + msg.content + "\n");
                    break;
                case Message.USER_LIST_UPDATE:
                    if (msg.payload instanceof String[]) {
                        gui.updateUserLst((String[]) msg.payload);
                    }
                    break;
                case Message.FILE_UPLOAD_CONFIRMATION:
                case Message.FILE_UPLOAD_READY_FOR_BYTES:
                    gui.appendChat("[Server] " + msg.content + "\n");
                    break;
                case Message.FILE_LIST_RESPONSE:
                    procFileListResp(msg.content);
                    break;
                case Message.FILE_DOWNLOAD_INFO_AND_START:
                    gui.appendChat("[Server] Preparing to download: " + msg.content + " (" + msg.fileSize + " bytes)\n");
                    this.dlName = msg.content; // Store original name
                    this.dlSize = msg.fileSize; // Store expected size
                    break;
                case Message.FILE_DOWNLOAD_SENDING_BYTES:
                    if (dlPath != null && dlName != null && dlSize > 0) {
                        gui.appendChat("[Server] Receiving file: " + dlName + "\n");
                        recvFile(dlName, dlSize);
                    } else {
                        gui.appendChat("[System] Error: Download requested but path or size not set correctly.\n");
                        gui.appendChat("Debug: dlPath=" + dlPath + ", dlName=" + dlName + ", dlSize=" + dlSize + "\n");

                    }
                    break;
                case Message.FILE_DOWNLOAD_ERROR:
                    gui.appendChat("[Server] Download Error: " + msg.content + "\n");
                    dlPath = null; dlName = null; dlSize = -1;
                    break;
                default:
                    gui.appendChat("[Server - Unhandled Type " + msg.type + "]: " + msg.content + "\n");
            }
        });
    }

    private void procFileListResp(String fListContent) {
        if (fListContent == null || fListContent.isEmpty()) {
            JOptionPane.showMessageDialog(gui, "No files available for download on the server.", "Server Files", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String[] files = fListContent.split(",");
        if (files.length == 0) {
            JOptionPane.showMessageDialog(gui, "No files available for download on the server.", "Server Files", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String selFile = (String) JOptionPane.showInputDialog(
                gui, "Select a file to download:", "Download File from Server",
                JOptionPane.PLAIN_MESSAGE, null, files, files[0]
        );

        if (selFile != null && !selFile.isEmpty()) {
            gui.procDownloadReq(selFile);
        }
    }

    private void recvFile(String fname, long expectedSize) {
        if (dlPath == null || fname == null || fname.isEmpty() || expectedSize <= 0) {
            gui.appendChat("[System] Cannot download: File details missing or invalid size.\n");
            gui.appendChat("Debug: dlPath=" + dlPath + ", fname=" + fname + ", expectedSize=" + expectedSize + "\n");
            return;
        }

        gui.appendChat("[System] Starting download of " + fname + " to " + dlPath.getFileName() + "...\n");

        JProgressBar progBar = new JProgressBar(0, 100);
        progBar.setStringPainted(true);

        JDialog progDlg = new JDialog(gui, "Downloading " + fname, false); // false = not modal
        progDlg.add(progBar);
        progDlg.pack();
        progDlg.setLocationRelativeTo(gui);
        progDlg.setVisible(true);


        try (OutputStream fOs = Files.newOutputStream(dlPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[8192];
            long bytesRecvd = 0;
            int bytesReadCnt;

            while (bytesRecvd < expectedSize) {
                if (!running) {
                    throw new IOException("Download interrupted by client stopping.");
                }
                int toRead = (int) Math.min(buf.length, expectedSize - bytesRecvd);
                bytesReadCnt = dataIn.read(buf, 0, toRead);
                if (bytesReadCnt == -1) {
                    throw new EOFException("Server closed connection during file download of " + fname);
                }
                fOs.write(buf, 0, bytesReadCnt);
                bytesRecvd += bytesReadCnt;

                final int progress = (int) ((bytesRecvd * 100) / expectedSize);
                SwingUtilities.invokeLater(() -> progBar.setValue(progress));
            }
            fOs.flush();
            SwingUtilities.invokeLater(() -> {
                progBar.setValue(100);
                gui.appendChat("[System] File " + fname + " downloaded successfully to " + dlPath.getFileName() + ".\n");
                progDlg.dispose();
            });
        } catch (IOException e) {
            SwingUtilities.invokeLater(() -> {
                gui.appendChat("[System] Error downloading file " + fname + ": " + e.getMessage() + "\n");
                progDlg.dispose();
            });
            try { Files.deleteIfExists(dlPath); } catch (IOException ignored) {}
        } finally {
            dlPath = null; dlName = null; dlSize = -1;
        }
    }
}