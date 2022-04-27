package com.ethanf108.tftplug;

import java.io.IOException;
import java.io.InputStream;

public class Credentials {

    public static final String
            PXE_SECRET,
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
        PXE_SECRET = creds[0];
        TFTP_PATH = creds[1];
    }
}
