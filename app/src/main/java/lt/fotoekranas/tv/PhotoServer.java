package lt.fotoekranas.tv;

import android.content.res.AssetManager;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Mažas HTTP serveris: telefonas per naršyklę įkelia nuotraukas į TV. */
public class PhotoServer {

    public interface Listener {
        void onPhotosChanged();
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long MAX_UPLOAD = 40L * 1024 * 1024;

    private final int port;
    private final Storage storage;
    private final AssetManager assets;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private volatile Listener listener;
    private volatile boolean running;
    private ServerSocket serverSocket;

    public PhotoServer(int port, Storage storage, AssetManager assets) {
        this.port = port;
        this.storage = storage;
        this.assets = assets;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public Listener getListener() {
        return listener;
    }

    public void start() {
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        serverSocket = new ServerSocket();
                        serverSocket.setReuseAddress(true);
                        serverSocket.bind(new InetSocketAddress(port));
                        while (running) {
                            final Socket s = serverSocket.accept();
                            pool.execute(new Runnable() {
                                @Override
                                public void run() {
                                    handle(s);
                                }
                            });
                        }
                    } catch (IOException e) {
                        try {
                            if (serverSocket != null) serverSocket.close();
                        } catch (IOException ignored) {
                        }
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException ie) {
                            return;
                        }
                    }
                }
            }
        }, "http-server");
        t.setDaemon(true);
        t.start();
    }

    private void changed() {
        Listener l = listener;
        if (l != null) l.onPhotosChanged();
    }

    private void handle(Socket sock) {
        try {
            sock.setSoTimeout(60000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();

            String line = readLine(in);
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.ROOT);
            String target = parts[1];

            Map<String, String> headers = new HashMap<>();
            String h;
            while ((h = readLine(in)) != null && h.length() > 0) {
                int i = h.indexOf(':');
                if (i > 0) {
                    headers.put(h.substring(0, i).trim().toLowerCase(Locale.ROOT), h.substring(i + 1).trim());
                }
            }
            long len = 0;
            try {
                String cl = headers.get("content-length");
                if (cl != null) len = Long.parseLong(cl);
            } catch (NumberFormatException ignored) {
            }

            String path = target;
            Map<String, String> q = new HashMap<>();
            int qi = target.indexOf('?');
            if (qi >= 0) {
                path = target.substring(0, qi);
                parseQuery(target.substring(qi + 1), q);
            }

            if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
                send(out, 200, "text/html; charset=utf-8", readAsset("index.html"));
                return;
            }
            if (path.equals("/favicon.ico")) {
                send(out, 404, "text/plain", new byte[0]);
                return;
            }

            if (!path.startsWith("/api/")) {
                send(out, 404, "text/plain", "Nerasta".getBytes(UTF8));
                return;
            }

            String pin = headers.get("x-pin");
            if (pin == null) pin = q.get("pin");
            if (pin == null || !pin.equals(storage.pin())) {
                sendJson(out, 403, "{\"ok\":false,\"error\":\"pin\"}");
                return;
            }

            if (method.equals("GET") && path.equals("/api/list")) {
                StringBuilder sb = new StringBuilder("{\"ok\":true,\"photos\":[");
                List<File> list = storage.listPhotos();
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append('"').append(list.get(i).getName()).append('"');
                }
                sb.append("],\"interval\":").append(storage.interval());
                sb.append(",\"logo\":").append(storage.logo() != null);
                sb.append(",\"brk\":").append(storage.breakImage() != null);
                sb.append(",\"breakSec\":").append(storage.breakSeconds()).append('}');
                sendJson(out, 200, sb.toString());
            } else if (method.equals("GET") && path.equals("/api/photo")) {
                File f = storage.photo(q.get("n"));
                if (f == null) send(out, 404, "text/plain", new byte[0]);
                else sendFile(out, "image/jpeg", f);
            } else if (method.equals("GET") && path.equals("/api/logo")) {
                File f = storage.logo();
                if (f == null) send(out, 404, "text/plain", new byte[0]);
                else sendFile(out, "image/png", f);
            } else if (method.equals("GET") && path.equals("/api/break")) {
                File f = storage.breakImage();
                if (f == null) send(out, 404, "text/plain", new byte[0]);
                else sendFile(out, "image/jpeg", f);
            } else if (method.equals("POST") && path.equals("/api/break")) {
                if (len <= 0 || len > MAX_UPLOAD) {
                    sendJson(out, 400, "{\"ok\":false,\"error\":\"size\"}");
                    return;
                }
                storage.saveBreak(in, len);
                changed();
                sendJson(out, 200, "{\"ok\":true}");
            } else if (method.equals("POST") && path.equals("/api/break/delete")) {
                storage.deleteBreak();
                changed();
                sendJson(out, 200, "{\"ok\":true}");
            } else if (method.equals("POST") && path.equals("/api/breaksec")) {
                try {
                    storage.setBreakSeconds(Integer.parseInt(q.get("s")));
                } catch (Exception ignored) {
                }
                changed();
                sendJson(out, 200, "{\"ok\":true}");
            } else if (method.equals("POST") && path.equals("/api/upload")) {
                if (len <= 0 || len > MAX_UPLOAD) {
                    sendJson(out, 400, "{\"ok\":false,\"error\":\"size\"}");
                    return;
                }
                String name = storage.savePhoto(in, len);
                changed();
                sendJson(out, 200, "{\"ok\":true,\"name\":\"" + name + "\"}");
            } else if (method.equals("POST") && path.equals("/api/logo")) {
                if (len <= 0 || len > MAX_UPLOAD) {
                    sendJson(out, 400, "{\"ok\":false,\"error\":\"size\"}");
                    return;
                }
                storage.saveLogo(in, len);
                changed();
                sendJson(out, 200, "{\"ok\":true}");
            } else if (method.equals("POST") && path.equals("/api/logo/delete")) {
                storage.deleteLogo();
                changed();
                sendJson(out, 200, "{\"ok\":true}");
            } else if (method.equals("POST") && path.equals("/api/delete")) {
                boolean ok = storage.deletePhoto(q.get("n"));
                changed();
                sendJson(out, 200, "{\"ok\":" + ok + "}");
            } else if (method.equals("POST") && path.equals("/api/clear")) {
                storage.clearPhotos();
                changed();
                sendJson(out, 200, "{\"ok\":true}");
            } else if (method.equals("POST") && path.equals("/api/interval")) {
                try {
                    storage.setInterval(Integer.parseInt(q.get("s")));
                } catch (Exception ignored) {
                }
                changed();
                sendJson(out, 200, "{\"ok\":true}");
            } else {
                send(out, 404, "text/plain", new byte[0]);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void parseQuery(String qs, Map<String, String> out) {
        for (String p : qs.split("&")) {
            if (p.isEmpty()) continue;
            int i = p.indexOf('=');
            try {
                String k = URLDecoder.decode(i >= 0 ? p.substring(0, i) : p, "UTF-8");
                String v = i >= 0 ? URLDecoder.decode(p.substring(i + 1), "UTF-8") : "";
                out.put(k, v);
            } catch (Exception ignored) {
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 16384) throw new IOException("line too long");
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    private byte[] readAsset(String name) throws IOException {
        InputStream is = assets.open(name);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = is.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toByteArray();
        } finally {
            is.close();
        }
    }

    private static String status(int code) {
        switch (code) {
            case 200: return "200 OK";
            case 400: return "400 Bad Request";
            case 403: return "403 Forbidden";
            case 404: return "404 Not Found";
            default: return code + " Error";
        }
    }

    private static void writeHead(OutputStream out, int code, String type, long len) throws IOException {
        String head = "HTTP/1.1 " + status(code) + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + len + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(UTF8));
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        writeHead(out, code, type, body.length);
        out.write(body);
        out.flush();
    }

    private static void sendJson(OutputStream out, int code, String json) throws IOException {
        send(out, code, "application/json; charset=utf-8", json.getBytes(UTF8));
    }

    private static void sendFile(OutputStream out, String type, File f) throws IOException {
        writeHead(out, 200, type, f.length());
        FileInputStream fis = new FileInputStream(f);
        try {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = fis.read(buf)) > 0) out.write(buf, 0, r);
        } finally {
            fis.close();
        }
        out.flush();
    }
}
