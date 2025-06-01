package Shared;

import java.io.Serializable;

public class Message implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final int LOGIN_REQUEST = 0;
    public static final int LOGIN_SUCCESS = 1;
    public static final int LOGIN_FAILURE = 2;
    public static final int CHAT_MESSAGE = 3;
    public static final int USER_JOINED_NOTIFICATION = 4;
    public static final int USER_LEFT_NOTIFICATION = 5;
    public static final int FILE_UPLOAD_REQUEST_METADATA = 6;
    public static final int FILE_UPLOAD_CONFIRMATION = 7;
    public static final int FILE_LIST_REQUEST = 8;
    public static final int FILE_LIST_RESPONSE = 9;
    public static final int FILE_DOWNLOAD_REQUEST = 10;
    public static final int FILE_DOWNLOAD_INFO_AND_START = 11;
    public static final int CLIENT_DISCONNECT = 12;
    public static final int GENERAL_SERVER_MESSAGE = 13;
    public static final int FILE_DOWNLOAD_ERROR = 14;
    public static final int FILE_UPLOAD_READY_FOR_BYTES = 15;
    public static final int FILE_DOWNLOAD_SENDING_BYTES = 16;
    public static final int USER_LIST_UPDATE = 17;


    public int type;
    public String sender;
    public String content;
    public long fileSize;
    public Object payload;

    public Message(int type, String sender, String content) {
        this.type = type;
        this.sender = sender;
        this.content = content;
        this.fileSize = -1;
    }

    public Message(int type, String sender, String content, long fileSize) {
        this(type, sender, content);
        this.fileSize = fileSize;
    }
    public Message(int type, String sender, Object payload) {
        this.type = type;
        this.sender = sender;
        this.payload = payload;
        this.fileSize = -1;
    }

    public Message() {}

    @Override
    public String toString() {
        return "Message{" +
                "type=" + type +
                ", sender='" + sender + '\'' +
                ", content='" + content + '\'' +
                ", fileSize=" + fileSize +
                ", payload=" + payload +
                '}';
    }
}