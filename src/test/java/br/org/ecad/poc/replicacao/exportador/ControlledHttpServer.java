package br.org.ecad.poc.replicacao.exportador;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Portable, bounded HTTP/1.1 fixture; no com.sun APIs or additional runtime dependency. */
final class ControlledHttpServer implements AutoCloseable {
    private final ServerSocket listener;
    private final Thread thread;

    ControlledHttpServer(Function<Request, Response> handler) throws Exception {
        listener = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        thread = Thread.ofVirtual().start(() -> {
            while (!listener.isClosed()) {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    serve(socket, handler);
                } catch (Exception ignored) {
                    // A client may close an in-flight request while testing exporter shutdown.
                }
            }
        });
    }

    int port() {
        return listener.getLocalPort();
    }

    private static void serve(Socket socket, Function<Request, Response> handler) throws Exception {
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
        String first = reader.readLine();
        if (first == null) return;
        String[] requestLine = first.split(" ", 3);
        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            headers.put(
                    line.substring(0, colon).toLowerCase(Locale.ROOT),
                    line.substring(colon + 1).trim());
        }
        int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        if (length < 0 || length > 100000) return;
        char[] body = new char[length];
        int offset = 0;
        while (offset < length) {
            int read = reader.read(body, offset, length - offset);
            if (read < 0) return;
            offset += read;
        }
        // ISO-8859-1 preserves individual bytes while headers and body share one buffered reader.
        String text = new String(new String(body).getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        Response response = handler.apply(new Request(requestLine[0], requestLine[1], headers, text));
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + response.status()
                + " Controlled\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: "
                + bytes.length + "\r\n\r\n";
        socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
        if (!requestLine[0].equals("HEAD")) socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    @Override
    public void close() throws Exception {
        listener.close();
        thread.join(6000);
        if (thread.isAlive()) throw new IllegalStateException("HTTP test fixture did not stop");
    }

    record Request(String method, String path, Map<String, String> headers, String body) {}

    record Response(int status, String body) {}
}
