package com.ethanf108.tftplug;

import javax.imageio.ImageIO;
import javax.net.SocketFactory;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.URL;
import java.util.Base64;
import java.util.Locale;

public class PlugManager {

    private final String secret;

    public PlugManager(String secret) {
        this.secret = secret;
    }

    private static BufferedImage scale(BufferedImage before) {
        int w = before.getWidth();
        int h = before.getHeight();
        BufferedImage after = new BufferedImage(640, 480, BufferedImage.TYPE_INT_ARGB);
        AffineTransform scaleInstance = AffineTransform.getScaleInstance(640.0 / w, 480.0 / h);
        AffineTransformOp scaleOp
                = new AffineTransformOp(scaleInstance, AffineTransformOp.TYPE_BILINEAR);
        scaleOp.filter(before, after);
        return after;
    }

    private String getPlugImageURL() throws IOException {
        Socket s = SSLSocketFactory.getDefault().createSocket("plug.csh.rit.edu", 443);
        OutputStreamWriter out = new OutputStreamWriter(s.getOutputStream());
        out.write("GET /data-pxe HTTP/1.1\r\nHost: plug.csh.rit.edu\r\nX-PXE-Secret: " + this.secret + "\r\n\r\n");
        out.flush();
        int read;
        StringBuilder sb = new StringBuilder();
        while ((read = s.getInputStream().read()) != -1 && !sb.toString().endsWith("\r\n\r\n")) {
            sb.append((char) read);
        }
        final String[] lines = sb.toString().split("\r?\n");
        for (String line : lines) {
            if (line.toLowerCase().startsWith("location: ")) {
                return line.substring("location: ".length());
            }
        }
        throw new IllegalStateException("OOPS");
    }

    private byte[] getImageData(String url) throws IOException {
        HttpsURLConnection con = (HttpsURLConnection) new URL(url).openConnection();
        con.setDoInput(true);
        con.setRequestMethod("GET");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int read;
        while ((read = con.getInputStream().read()) != -1) {
            out.write(read);
        }
        return out.toByteArray();
    }

    public byte[] getPlugImage() throws IOException {
        String plugURL;
        do {
            plugURL = this.getPlugImageURL();
        } while (!plugURL.substring(0, plugURL.indexOf("?")).endsWith(".png"));
        final byte[] badSize = this.getImageData(plugURL);
        final BufferedImage orig = ImageIO.read(new ByteArrayInputStream(badSize));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ImageIO.write(scale(orig), "PNG", buf);
        return buf.toByteArray();
    }
}
