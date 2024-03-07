package org.jacoco.agent.rt.internal_8cf7cdb;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class FileHttpServer implements HttpHandler {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final static Map<String, String> CONTENT_TYPE_MAP = new HashMap<>();
    
    static {
        CONTENT_TYPE_MAP.put("aac" , "audio/aac");
        CONTENT_TYPE_MAP.put("abw" , "application/x-abiword");
        CONTENT_TYPE_MAP.put("arc" , "application/x-freearc");
        CONTENT_TYPE_MAP.put("avi" , "video/x-msvideo");
        CONTENT_TYPE_MAP.put("azw" , "application/vnd.amazon.ebook");
        CONTENT_TYPE_MAP.put("bin" , "application/octet-stream");
        CONTENT_TYPE_MAP.put("bmp" , "image/bmp");
        CONTENT_TYPE_MAP.put("bz" , "application/x-bzip");
        CONTENT_TYPE_MAP.put("bz2" , "application/x-bzip2");
        CONTENT_TYPE_MAP.put("csh" , "application/x-csh");
        CONTENT_TYPE_MAP.put("css" , "text/css");
        CONTENT_TYPE_MAP.put("csv" , "text/csv");
        CONTENT_TYPE_MAP.put("doc" , "application/msword");
        CONTENT_TYPE_MAP.put("docx" , "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        CONTENT_TYPE_MAP.put("eot" , "application/vnd.ms-fontobject");
        CONTENT_TYPE_MAP.put("epub" , "application/epub+zip");
        CONTENT_TYPE_MAP.put("gif" , "image/gif");
        CONTENT_TYPE_MAP.put("htm" , "text/html");
        CONTENT_TYPE_MAP.put("html" , "text/html");
        CONTENT_TYPE_MAP.put("ico" , "image/vnd.microsoft.icon");
        CONTENT_TYPE_MAP.put("ics" , "text/calendar");
        CONTENT_TYPE_MAP.put("jar" , "application/java-archive");
        CONTENT_TYPE_MAP.put("jpeg" , "image/jpeg");
        CONTENT_TYPE_MAP.put("jpg" , "image/jpeg");
        CONTENT_TYPE_MAP.put("js" , "text/javascript");
        CONTENT_TYPE_MAP.put("json" , "application/json");
        CONTENT_TYPE_MAP.put("jsonld" , "application/ld+json");
        CONTENT_TYPE_MAP.put("mid" , "audio/x-midi");
        CONTENT_TYPE_MAP.put("midi" , "audio/x-midi");
        CONTENT_TYPE_MAP.put("mjs" , "text/javascript");
        CONTENT_TYPE_MAP.put("mp3" , "audio/mpeg");
        CONTENT_TYPE_MAP.put("mpeg" , "video/mpeg");
        CONTENT_TYPE_MAP.put("mpkg" , "application/vnd.apple.installer+xml");
        CONTENT_TYPE_MAP.put("odp" , "application/vnd.oasis.opendocument.presentation");
        CONTENT_TYPE_MAP.put("ods" , "application/vnd.oasis.opendocument.spreadsheet");
        CONTENT_TYPE_MAP.put("odt" , "application/vnd.oasis.opendocument.text");
        CONTENT_TYPE_MAP.put("oga" , "audio/ogg");
        CONTENT_TYPE_MAP.put("ogv" , "video/ogg");
        CONTENT_TYPE_MAP.put("ogx" , "application/ogg");
        CONTENT_TYPE_MAP.put("otf" , "font/otf");
        CONTENT_TYPE_MAP.put("png" , "image/png");
        CONTENT_TYPE_MAP.put("pdf" , "application/pdf");
        CONTENT_TYPE_MAP.put("ppt" , "application/vnd.ms-powerpoint");
        CONTENT_TYPE_MAP.put("pptx" , "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        CONTENT_TYPE_MAP.put("rar" , "application/x-rar-compressed");
        CONTENT_TYPE_MAP.put("rtf" , "application/rtf");
        CONTENT_TYPE_MAP.put("sh" , "application/x-sh");
        CONTENT_TYPE_MAP.put("svg" , "image/svg+xml");
        CONTENT_TYPE_MAP.put("swf" , "application/x-shockwave-flash");
        CONTENT_TYPE_MAP.put("tar" , "application/x-tar");
        CONTENT_TYPE_MAP.put("tif" , "image/tiff");
        CONTENT_TYPE_MAP.put("tiff" , "image/tiff");
        CONTENT_TYPE_MAP.put("ttf" , "font/ttf");
        CONTENT_TYPE_MAP.put("txt" , "text/plain");
        CONTENT_TYPE_MAP.put("vsd" , "application/vnd.visio");
        CONTENT_TYPE_MAP.put("wav" , "audio/wav");
        CONTENT_TYPE_MAP.put("weba" , "audio/webm");
        CONTENT_TYPE_MAP.put("webm" , "video/webm");
        CONTENT_TYPE_MAP.put("webp" , "image/webp");
        CONTENT_TYPE_MAP.put("woff" , "font/woff");
        CONTENT_TYPE_MAP.put("woff2" , "font/woff2");
        CONTENT_TYPE_MAP.put("xhtml" , "application/xhtml+xml");
        CONTENT_TYPE_MAP.put("xls" , "application/vnd.ms-excel");
        CONTENT_TYPE_MAP.put("xlsx" , "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        CONTENT_TYPE_MAP.put("xml" , "text/xml");
        CONTENT_TYPE_MAP.put("xul" , "application/vnd.mozilla.xul+xml");
        CONTENT_TYPE_MAP.put("zip" , "application/zip");
        CONTENT_TYPE_MAP.put("3gp" , "video/3gpp");
        CONTENT_TYPE_MAP.put("3g2" , "video/3gpp2");
        CONTENT_TYPE_MAP.put("7z" , "application/x-7z-compressed");
        CONTENT_TYPE_MAP.put("log" , "text/plain");
    }

    private final String root;

    private final int port;

    private static volatile boolean running = false;

    FileHttpServer(String root, int port) {
        this.root = root;
        this.port = port;
    }

    public static synchronized FileHttpServer start(boolean local) {
        if (running) {
            return null;
        }
        running = true;
        String root = System.getProperty("fileHttpServer.root", "/");
        if (local && root.equals("/")) {
            root = "~";
        }
        if (root.equals("~")) {
            root = System.getProperty("user.home");
        }
        int port = Integer.parseInt(System.getProperty("fileHttpServer.port", "6400"));
        if (local) {
            port = tryPort(port);
        }
        FileHttpServer fileHttpServer = new FileHttpServer(root, port);
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(fileHttpServer.port), 0);
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/", fileHttpServer);
        server.start();
        return fileHttpServer;
    }

    public int getPort() {
        return port;
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        String path = httpExchange.getRequestURI().getPath();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        String query = httpExchange.getRequestURI().getQuery();
        Map<String, String> queryMap = new HashMap<>();
        if (query != null) {
            Arrays.stream(query.split("&")).forEach(s -> {
                String[] arr = s.split("=");
                queryMap.put(arr[0], arr[1]);
            });
        }

        String dir = queryMap.getOrDefault("dir", root);
        if (dir.equals("/")) {
            dir = "";
        }
        String filePath = dir + path;
        File file = new File(filePath);
        if (!file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            String files = Arrays.stream(Objects.requireNonNull(file.listFiles())).map(File::getName).collect(Collectors.joining("\n"));
            if ("html".equals(queryMap.get("format"))) {
                if (path.equals("/")) {
                    path = "";
                }
                if (path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
                String finalPath = path;
                files = Arrays.stream(Objects.requireNonNull(file.listFiles())).map(file1 -> "<a href='" + finalPath + "/" + file1.getName() + "?" + query + "'>" + file1.getName() + "</a>").collect(Collectors.joining("<br/>"));
                files = "<html>" + files + "</html>";
                httpExchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            } else {
                httpExchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            }
            httpExchange.sendResponseHeaders(200, files.getBytes().length);
            httpExchange.getResponseBody().write(files.getBytes(StandardCharsets.UTF_8));
            httpExchange.getResponseBody().close();
            return;
        }

        String[] arr = filePath.split("/");
        String fileName = arr[arr.length - 1];

        String contentType = DEFAULT_CONTENT_TYPE;
        int idx = fileName.lastIndexOf(".");
        String suffix = idx == -1 ? null : fileName.substring(idx + 1);
        if (suffix != null && CONTENT_TYPE_MAP.containsKey(suffix)) {
            contentType = CONTENT_TYPE_MAP.get(suffix);
        }

        httpExchange.getResponseHeaders().add("Content-Type", contentType);
        if (contentType.equals(DEFAULT_CONTENT_TYPE)) {
            httpExchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        }
        httpExchange.sendResponseHeaders(200, file.length());
        try (FileInputStream inputStream = new FileInputStream(file)) {
            int b;
            byte[] buffer = new byte[4096];
            while ((b = inputStream.read(buffer)) > 0) {
                httpExchange.getResponseBody().write(buffer, 0, b);
            }
            httpExchange.getResponseBody().flush();
            httpExchange.getResponseBody().close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static int tryPort(int port) {
        for (int i = 1; i <= 10; i++) {
            if (isUsing(port)) {
                port = port + 1;
                continue;
            }
            return port;
        }
        return port;
    }

    public static boolean isUsing(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
        // ignore
    }
}
