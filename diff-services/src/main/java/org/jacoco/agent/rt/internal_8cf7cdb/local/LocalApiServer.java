package org.jacoco.agent.rt.internal_8cf7cdb.local;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.jacoco.agent.rt.internal_8cf7cdb.FileHttpServer;
import org.jacoco.cli.internal.JacocoApi;
import org.jacoco.cli.internal.core.analysis.Analyzer;
import org.jacoco.cli.internal.core.analysis.CoverageBuilder;
import org.jacoco.cli.internal.core.analysis.IBundleCoverage;
import org.jacoco.cli.internal.core.analysis.IClassCoverage;
import org.jacoco.cli.internal.core.data.ExecutionData;
import org.jacoco.cli.internal.core.data.ExecutionDataStore;
import org.jacoco.cli.internal.core.tools.ExecFileLoader;
import org.jacoco.cli.internal.report.*;
import org.jacoco.cli.internal.report.html.HTMLFormatter;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class LocalApiServer implements HttpHandler {

    private static final String BASE_PACKAGE = "com" + File.separator + "test"; //todo

    private final String root;

    private final int port;

    private final int dumpPort;

    private final String sourcePath;

    private final String classPath;

    private final long timestamp;

    private final String baseDir = "code-coverage";

    private final String classHashFileName = "class.hash";

    private static volatile boolean running = false;

    LocalApiServer(String root, int port, int dumpPort, String sourcePath, String classPath) {
        this.root = root;
        this.port = port;
        this.dumpPort = dumpPort;
        this.sourcePath = sourcePath;
        this.classPath = classPath;
        this.timestamp = System.currentTimeMillis();
    }

    public static synchronized LocalApiServer start(int dumpPort) {
        if (running) {
            return null;
        }
        running = true;
        String root = System.getProperty("coverage.local.root", System.getProperty("user.home"));
        int port = FileHttpServer.tryPort(Integer.parseInt(System.getProperty("coverage.local.port", "6500")));
        String sourcePath = System.getProperty("coverage.local.source", "");
        String classPath = System.getProperty("coverage.local.class", "");

        if (sourcePath.equals("") || classPath.equals("")) {
            return null;
        }

        LocalApiServer localApiServer = new LocalApiServer(root, port, dumpPort, sourcePath, classPath);
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(localApiServer.port), 0);
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/", localApiServer);
        server.start();

        return localApiServer;
    }

    @Override
    public void handle(HttpExchange httpExchange) {
        String path = httpExchange.getRequestURI().getPath();
        if ("/report".equals(path)) {
            try {
                doReport(httpExchange);
                httpExchange.sendResponseHeaders(200, "".getBytes().length);
                httpExchange.getResponseBody().write("".getBytes(StandardCharsets.UTF_8));
                httpExchange.getResponseBody().close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void doReport(HttpExchange httpExchange) throws Exception {
        /*
         * get params
         */
        String query = httpExchange.getRequestURI().getQuery();
        Map<String, String> queryMap = new HashMap<>();
        if (query != null) {
            Arrays.stream(query.split("&")).forEach(s -> {
                String[] arr = s.split("=");
                queryMap.put(arr[0], arr[1]);
            });
        }
        String dumpPort = queryMap.get("dumpPort");
        String dumpHost = queryMap.get("dumpHost");
        String appName = queryMap.get("appName");

        /*
         * 1. dump data to timestamp
         */
        String timestampDirPath = root + File.separator + baseDir + File.separator + appName + File.separator + timestamp;
        String timestampExecFilePath = timestampDirPath + File.separator + "dump.exec";
        String timestampMergedFilePath = timestampDirPath + File.separator + "merged.exec";
        String timestampClassIdFilePath = timestampDirPath + File.separator + classHashFileName;
        String timestampClassDirPath = timestampDirPath + File.separator + "class";
        List<File> timestampClassFileList = new LinkedList<>();

        File timestampDirFile = new File(timestampDirPath);
        Map<String, String> timestampClassNameIdMap = new HashMap<>();
        if (!timestampDirFile.exists()) {
            timestampDirFile.mkdirs();
            /*
             * 2. get class hash to timestamp, //todo
             */
            List<String> classIdNameList = new LinkedList<>();
            for (String classDir : classPath.split(",")) {
                classDir = classDir.trim();
                addClassHashAndCopy(classIdNameList, classDir, timestampClassDirPath);
            }
            if (!classIdNameList.isEmpty()) {
                String content = String.join("\n", classIdNameList);
                Files.write(Paths.get(timestampClassIdFilePath), content.getBytes(StandardCharsets.UTF_8));
                timestampClassNameIdMap = classIdNameList.stream().map(e -> e.split(":")).collect(Collectors.toMap(e -> e[1], e -> e[0]));
            }
        }
        addClassFiles(timestampClassDirPath, timestampClassFileList);
        Files.deleteIfExists(Paths.get(timestampExecFilePath));
        JacocoApi.execute("dump", "--destfile", timestampExecFilePath, "--address", dumpHost, "--port", dumpPort, "--reset");

        /*
         * 3. merge merged data to timestamp
         */
        String orgDirPath = root + File.separator + baseDir + File.separator + appName + File.separator + "org";
        String orgExecFilePath = orgDirPath + File.separator + "dump.exec";
        String orgClassIdFilePath = orgDirPath + File.separator + classHashFileName;
        String orgClassDirPath = orgDirPath + File.separator + "class";
        List<File> orgClassFileList = new LinkedList<>();

        Map<String, String> orgClassNameIdMap;
        File orgDirFile = new File(orgDirPath);
        if (!orgDirFile.exists()) {
            timestampDirFile.renameTo(orgDirFile);
        } else {
            addClassFiles(orgClassDirPath, orgClassFileList);
            orgClassNameIdMap = Files.readAllLines(Paths.get(orgClassIdFilePath)).stream().map(e -> e.split(":")).collect(Collectors.toMap(e -> e[1], e -> e[0]));

            //todo 找出修改，删除的class, 如果是删除, 则去掉探针，如果是修改，则探针重置，其他合并
            Map<String, String> finalOrgClassNameIdMap = orgClassNameIdMap;
            Set<String> modifiedClass = timestampClassNameIdMap.entrySet().stream().filter(entry -> finalOrgClassNameIdMap.containsKey(entry.getKey()) && !finalOrgClassNameIdMap.get(entry.getKey()).equals(entry.getValue())).map(Map.Entry::getKey).collect(Collectors.toSet());
            Map<String, String> finalTimestampClassNameIdMap = timestampClassNameIdMap;
            Set<String> deletedClass = orgClassNameIdMap.entrySet().stream().filter(entry -> !finalTimestampClassNameIdMap.containsKey(entry.getKey())).map(Map.Entry::getKey).collect(Collectors.toSet());

            ExecFileLoader timestampExecFileLoader = new ExecFileLoader();
            timestampExecFileLoader.load(new File(timestampExecFilePath));
            ExecutionDataStore timestampExecutionDataStore = timestampExecFileLoader.getExecutionDataStore();
            Collection<ExecutionData> timestampExecutionDataCollection = timestampExecutionDataStore.getContents();

            ExecFileLoader orgExecFileLoader = new ExecFileLoader();
            orgExecFileLoader.load(new File(orgExecFilePath));
            ExecutionDataStore orgExecutionDataStore = orgExecFileLoader.getExecutionDataStore();

            for (ExecutionData timestampData : timestampExecutionDataCollection) {
                if (modifiedClass.contains(timestampData.getName().replace(File.separator, "."))) {
                    timestampData.reset();
                    continue;
                }
                for (ExecutionData orgData : orgExecutionDataStore.getContents()) {
                    if (orgData.getName().equals(timestampData.getName())) {
                        boolean[] timestampProbes = timestampData.getProbes();
                        boolean[] orgProbes = orgData.getProbes();
                        for (int i = 0; i <timestampProbes.length; i++) {
                            timestampProbes[i] = timestampProbes[i] | orgProbes[i];
                        }
                    }
                }
            }

            for (String delete : deletedClass) {
                timestampExecutionDataCollection.stream().filter(e -> e.getName().replace(File.separator, ".").equals(delete)).findFirst().ifPresent(timestampExecutionDataCollection::remove);
            }

            timestampExecFileLoader.save(new File(timestampMergedFilePath), true);

            /*
             * 4. rename merged.exec to dump.exec, delete org dir, rename timestamp dir to org dir
             */
            Files.deleteIfExists(Paths.get(timestampExecFilePath));
            new File(timestampMergedFilePath).renameTo(new File(timestampExecFilePath));
            deleteFolder(new File(orgDirPath));
            new File(timestampDirPath).renameTo(new File(orgDirPath));
        }

        /*
         * 5. generate report to root
         */
        String reportDirPath = root + File.separator + baseDir + File.separator + appName + File.separator + "report";
        deleteFolder(new File(reportDirPath));
        ExecFileLoader reportLoader = new ExecFileLoader();
        reportLoader.load(new File(orgExecFilePath));
        List<File> reportClassFiles = new LinkedList<>();
        addClassFiles(orgClassDirPath, reportClassFiles);
        IBundleCoverage bundle = analyze(appName, reportLoader.getExecutionDataStore(), reportClassFiles);
        writeReports(bundle, reportLoader, new File(reportDirPath));
    }

    private void writeReports(final IBundleCoverage bundle, final ExecFileLoader loader, File html)
            throws IOException {
        final IReportVisitor visitor = createReportVisitor(html);
        visitor.visitInfo(loader.getSessionInfoStore().getInfos(),
                loader.getExecutionDataStore().getContents());
        visitor.visitBundle(bundle, getSourceLocator());
        visitor.visitEnd();
    }

    private ISourceFileLocator getSourceLocator() {
        MultiSourceFileLocator multi = new MultiSourceFileLocator(4);
        for (String source : sourcePath.split(",")) {
            multi.add(new DirectorySourceFileLocator(new File(source), "UTF-8", 4));
        }
        return multi;
    }

    private IReportVisitor createReportVisitor(File html) throws IOException {
        final List<IReportVisitor> visitors = new ArrayList<IReportVisitor>();
        final HTMLFormatter formatter = new HTMLFormatter();
            visitors.add(
                    formatter.createVisitor(new FileMultiReportOutput(html)));
        return new MultiReportVisitor(visitors);
    }

    private IBundleCoverage analyze(String appName, ExecutionDataStore data, List<File> classFiles) throws IOException {
        final CoverageBuilder builder = new CoverageBuilder();
        final Analyzer analyzer = new Analyzer(data, builder);
        for (final File f : classFiles) {
            analyzer.analyzeAll(f);
        }
        return builder.getBundle(appName);
    }

    private static void deleteFolder(File folder) {
        if (folder.isDirectory()) {
            File[] files = folder.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteFolder(file);
                }
            }
        }
        folder.delete();
    }


    private void addClassFiles(String classDir, List<File> classFileList) {
        File classFile = new File(classDir);
        if (classFile.isDirectory()) {
            for (File file : classFile.listFiles()) {
                addClassFiles(file.getAbsolutePath(), classFileList);
            }
            return;
        }
        if (classFile.getName().endsWith(".class")) {
            classFileList.add(classFile);
        }
    }

    private Map<String, IClassCoverage> classAnalysis(ExecutionDataStore data, List<File> classFiles) throws IOException {
        final CoverageBuilder builder = new CoverageBuilder();
        final Analyzer analyzer = new Analyzer(data, builder);
        for (final File f : classFiles) {
            analyzer.analyzeAll(f);
        }
        return builder.getClassesMap();
    }

    private void addClassHashAndCopy(List<String> classIdNameList, String filePath, String timestampClassPath) throws IOException {
        File file = new File(filePath);
        if (!file.isDirectory()) {
            String classFileName = file.getName();
            String classFilePath = file.getAbsolutePath();
            if (!classFileName.endsWith(".class") || !classFilePath.contains("com" + File.separator + "intramirror")) {
                return;
            }
            File destDir = new File(timestampClassPath);
            if (!destDir.exists()) {
                destDir.mkdirs();
            }
            Files.copy(Paths.get(classFilePath), Paths.get(timestampClassPath + File.separator + classFileName));
            String className = getClassName(classFilePath);
            String classId = getClassId(classFilePath);
            classIdNameList.add(classId + ":" + className);
            return;
        }
        for (File child : Objects.requireNonNull(file.listFiles())) {
            String newTimestampClassPath = timestampClassPath;
            if (child.isDirectory()) {
                newTimestampClassPath = timestampClassPath + File.separator + child.getName();
            }
            addClassHashAndCopy(classIdNameList, child.getAbsolutePath(), newTimestampClassPath);
        }
    }

    private String getClassName(String path) {
        int idx = path.indexOf(BASE_PACKAGE);
        return path.substring(idx).replace(".class", "").replace(File.separator, ".");
    }

    private String getClassId(String path) {
        try {
            Path file = Paths.get(path);
            byte[] fileBytes = Files.readAllBytes(file);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(fileBytes);
            byte[] hashBytes = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public int getPort() {
        return port;
    }

}
