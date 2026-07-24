package ru.anton2319.vpnoverssh;

import java.io.OutputStream;
import java.net.Socket;
import java.util.Random;

public class PayloadEngine {

    public static String formatPayloadString(String rawPayload, String host, String port) {
        if (rawPayload == null || rawPayload.isEmpty()) return "";

        String parsed = rawPayload;
        parsed = parsed.replace("[crlf]", "\r\n");
        parsed = parsed.replace("[host]", host);
        parsed = parsed.replace("[port]", port);
        parsed = parsed.replace("[ua]", "Googlebot/2.1 (+http://google.com)");
        parsed = parsed.replace("[https/host]", "https://" + host);

        // Process [rotate=domain1;domain2] arrays for your Airtel or Google bug hosts
        if (parsed.contains("[rotate=")) {
            int start = parsed.indexOf("[rotate=");
            int end = parsed.indexOf("]", start);
            if (end != -1) {
                String fullTag = parsed.substring(start, end + 1);
                String[] domains = parsed.substring(start + 8, end).split(";");
                String chosenDomain = domains[new Random().nextInt(domains.length)];
                parsed = parsed.replace(fullTag, chosenDomain);
            }
        }
        return parsed;
    }

    public static void transmitPayload(Socket socket, String formattedPayload) throws Exception {
        OutputStream out = socket.getOutputStream();

        // Process the [split] rule to break packets and fool ISP firewalls
        if (formattedPayload.contains("[split]")) {
            String[] segments = formattedPayload.split("\\[split\\]");
            
            // Send the first payload packet chunk (e.g. standard Cloudflare check)
            out.write(segments[0].getBytes("UTF-8"));
            out.flush();
            
            // 50-millisecond drop delay (the secret behind HTTP Custom's DPI bypass)
            Thread.sleep(50);
            
            // Force flash the hidden UNLOCK instruction down the open stream
            out.write(segments[1].getBytes("UTF-8"));
        } else {
            out.write(formattedPayload.getBytes("UTF-8"));
        }
        out.flush();
    }
}
