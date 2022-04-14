package com.ethanf108.tftplug;

import java.io.IOException;
import java.io.InputStream;

public class Credentials {

    public static final String
            AUTH_USERNAME,
            AUTH_PASSWORD,
            AUTH_URL,
            TFTP_PATH;

    static {
        String[] creds = null;
        try {
            final InputStream in = Credentials.class.getResourceAsStream("credentials.txt");
            StringBuilder sb = new StringBuilder();
            int read;
            while ((read = in.read()) != -1) {
                sb.append((char) read);
            }
            creds = sb.toString().split("\r?\n");
        } catch (IOException ex) {
            System.err.println("Could not read creds file. abort.");
            System.exit(1);
        }
        AUTH_USERNAME = creds[0];
        AUTH_PASSWORD = creds[1];
        AUTH_URL = creds[2];
        TFTP_PATH = creds[3];
    }
}
