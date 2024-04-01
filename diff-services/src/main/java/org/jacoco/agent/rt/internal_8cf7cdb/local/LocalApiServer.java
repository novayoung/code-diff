package org.jacoco.agent.rt.internal_8cf7cdb.local;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.XmlUtil;
import cn.hutool.core.util.ZipUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.test.diff.common.domain.ClassInfo;
import com.test.diff.common.domain.MethodInfo;
import com.test.diff.common.enums.DiffResultTypeEnum;
import com.test.diff.services.consts.FileConst;
import lombok.*;
import org.apache.logging.log4j.util.Strings;
import org.jacoco.agent.rt.internal_8cf7cdb.FileHttpServer;
import org.jacoco.cli.internal.JacocoApi;
import org.jacoco.cli.internal.core.analysis.Analyzer;
import org.jacoco.cli.internal.core.analysis.CoverageBuilder;
import org.jacoco.cli.internal.core.analysis.IBundleCoverage;
import org.jacoco.cli.internal.core.analysis.IClassCoverage;
import org.jacoco.cli.internal.core.data.ExecutionData;
import org.jacoco.cli.internal.core.data.ExecutionDataStore;
import org.jacoco.cli.internal.core.data.MethodProbesInfo;
import org.jacoco.cli.internal.core.internal.analysis.ClassCoverageImpl;
import org.jacoco.cli.internal.core.tools.ExecFileLoader;
import org.jacoco.cli.internal.core.tools.MethodUriAdapter;
import org.jacoco.cli.internal.report.*;
import org.jacoco.cli.internal.report.html.HTMLFormatter;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.stream.Collectors;

@Setter
@Getter
@ToString
public class LocalApiServer implements HttpHandler, Runnable {

    private static final String BASE_PACKAGE = "com" + File.separator + FileConst.BASE_PACKAGE_NAME; //todo

    private static final String SRC_FLAG = File.separator + "src" + File.separator + "main" + File.separator + "java";

    private final String root;

    private final long sessionTimestamp = System.currentTimeMillis();

    private final int port;

    private final int filePort;

    private final int dumpPort;

    private final String workspace;

    private final String sourcePath;

    private final String classPath;

    private final long timestamp;

    private final String baseDir = "code-coverage";

    private final String classHashFileName = "class.hash";

    private Boolean refresh;
    private Long refreshInterval;

    private String appName;

    private final String dumpHost = "127.0.0.1";

    private final String mergeLevel; // class / method

    private final String curBranch;

    private static volatile boolean running = false;

    private String lastExecFileMD5 = null;

    private boolean isMasterCheckout = false;

    private boolean isMasterCopied = false;

    private String gitUser;

    private String gitPass;

    private String gitConfigUser;

    private String gitUrl;

    private Integer coverageRate;

    private int pushInterval = 60 * 1000;

    private String localhost;

    LocalApiServer(String root, int port, int filePort, int dumpPort, String sourcePath, String classPath, String workspace, String appName, Boolean refresh, Long refreshInterval, String mergeLevel, String curBranch) {
        this.root = root;
        this.port = port;
        this.filePort = filePort;
        this.dumpPort = dumpPort;
        this.sourcePath = sourcePath;
        this.classPath = classPath;
        this.workspace = workspace;
        this.appName = appName;
        this.refresh = refresh;
        this.refreshInterval = refreshInterval;
        this.mergeLevel = mergeLevel;
        this.curBranch = curBranch;
        this.timestamp = System.currentTimeMillis();
    }

    public static synchronized LocalApiServer start(int filePort, int dumpPort) {
        if (running) {
            return null;
        }
        running = true;
        String root = System.getProperty("coverage.local.root", System.getProperty("user.home"));
//        int randomPort = 6000 + new Random().nextInt(1000);
        int port = FileHttpServer.tryPort(Integer.parseInt(System.getProperty("coverage.local.port", "6500")));
        String workspace = System.getProperty("coverage.local.ws");
        if (workspace == null) {
            workspace = guessWs();
        }
        String app = System.getProperty("coverage.local.app");
        String sourcePath = System.getProperty("coverage.local.source", "");
        String classPath = System.getProperty("coverage.local.class", "");
        Boolean refresh =  Boolean.valueOf(System.getProperty("coverage.local.refresh", "true"));
        Long refreshInterval =  Long.valueOf(System.getProperty("coverage.local.refresh.interval", "5000"));
        String mergeLevel = System.getProperty("coverage.local.mergeLevel", "method");
        String curBranch = execCmd(workspace, "git branch --show-current");
        System.setProperty("eureka.instance.metadata-map.coverageLocalBranch", curBranch);
        System.setProperty("eureka.instance.metadata-map.coverageLocalRefresh", String.valueOf(refresh));

        if (sourcePath.equals("")) {
            List<String> sourcePaths = new LinkedList<>();
            guessPath(workspace, "src" + File.separator + "main" + File.separator + "java", sourcePaths);
            sourcePath = String.join(",", sourcePaths);
        }
        if (classPath.equals("")) {
            List<String> classPaths = new LinkedList<>();
            guessPath(workspace, "target" + File.separator + "classes", classPaths);
            classPath = String.join(",", classPaths);
        }
        if (sourcePath.equals("") || classPath.equals("")) {
            return null;
        }
        LocalApiServer localApiServer = new LocalApiServer(root, port, filePort, dumpPort, sourcePath, classPath, workspace, app, refresh, refreshInterval, mergeLevel, curBranch);
        String configPushInterval = System.getProperty("coverage.local.pushInterval", "");
        if (!"".equals(configPushInterval)) {
            localApiServer.pushInterval = Integer.parseInt(configPushInterval);
        }

        String requestUrl = "http://127.0.0.1:8888/api/rpc/coverage/localConfig";
        List<Object> body = new LinkedList<>();
        body.add(localApiServer.toString());
        String resp = HttpUtil.post(requestUrl, JSONUtil.toJsonStr(body));
        Map<String, Object> map = JSONUtil.toBean(resp, Map.class);
        if (!"1".equalsIgnoreCase((String) map.get("code"))) {
            return null;
        }
        String gitUser = (String) map.get("gitUser");
        String gitPass = (String) map.get("gitPass");
        String pushInterval = (String) map.get("pushInterval");

        if (gitUser != null) {
            localApiServer.setGitUser(gitUser);
        }
        if (gitPass != null) {
            localApiServer.setGitPass(gitPass);
        }
        if (pushInterval != null) {
            localApiServer.pushInterval = Integer.parseInt(pushInterval);
        }

        String gitConfigUser = execCmd(workspace, "git config user.name");
        localApiServer.setGitConfigUser(gitConfigUser);

        String gitUrl = execCmd(workspace,"git config --get remote.origin.url");
        if (gitUrl.startsWith("ssh")) {
            List<Object> gitUrlBody = new LinkedList<>();
            Map<String, Object> gitUrlMap = new HashMap<>();
            gitUrlMap.put("gitUrl", gitUrl);
            gitUrlBody.add(gitUrlMap);
            String gitUrlResp = HttpUtil.post("http://127.0.0.1:8888/api/rpc/coverage/localGitUrl", JSONUtil.toJsonPrettyStr(gitUrlBody));
            Map<String, Object> gitUrlRespMap = JSONUtil.toBean(gitUrlResp, Map.class);
            if (!"1".equalsIgnoreCase((String) gitUrlRespMap.get("code"))) {
                return null;
            }
            gitUrl = (String) gitUrlRespMap.get("gitUrl");
        }
        localApiServer.setGitUrl(gitUrl);

        localApiServer.setLocalhost(String.join(",", getAllLocalHostIP()));

        Thread refreshThread = new Thread(localApiServer, "CoverageRefreshThread");
        refreshThread.start();

        HttpServer server;
        try {
            System.out.println("coverage local port: " + localApiServer.port);
            System.out.println("coverage local dumpPort: " + localApiServer.dumpPort);
            System.out.println("coverage local dumpHost: " + localApiServer.dumpHost);
            System.out.println("coverage local filePort: " + localApiServer.filePort);
            server = HttpServer.create(new InetSocketAddress(localApiServer.port), 0);
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        ThreadFactory threadFactory = runnable -> {
            Thread thread = Executors.defaultThreadFactory().newThread(runnable);
            thread.setName("CoverageWorkerThread-" + thread.getId());
            return thread;
        };
        ExecutorService executor = Executors.newFixedThreadPool(2, threadFactory);
        server.setExecutor(executor);
        server.createContext("/", localApiServer);
        server.start();
        return localApiServer;
    }

    private static String guessWs() {
        String classFilePath = LocalApiServer.class.getResource("/").getPath();
        System.out.println(classFilePath);
        File wsFile = new File(classFilePath).getParentFile().getParentFile();
        if (new File(wsFile.getParentFile().getAbsolutePath() + File.separator + "pom.xml").exists()) {
            String parentWs = wsFile.getParentFile().getAbsolutePath();
            System.out.println("ws root: " + parentWs);
            return parentWs;
        }
        System.out.println("ws dir: " + wsFile.getAbsolutePath());
        return wsFile.getAbsolutePath();
    }

    @SneakyThrows
    private static String execCmd(String workspace, String cmd) {
        String os = System.getProperty("os.name");
        Process process;
        if (os.toLowerCase().contains("windows")) {
            process = Runtime.getRuntime().exec(new String[]{"cmd", "/c", "cd " + workspace + " && " + cmd});
        } else {
            process = Runtime.getRuntime().exec(new String[]{"sh", "-c", "cd " + workspace + " | " + cmd});
        }
        String success = readInput(process.getInputStream());
        String error = readInput(process.getErrorStream());
        if (!"".equals(error)) {
            System.err.println(error);
        }
        return success;
    }

    private static String readInput(InputStream inputStream) throws IOException {
        BufferedReader stdOutput = new BufferedReader(new InputStreamReader(inputStream));
        String s;
        List<String> list = new LinkedList<>();
        while ((s = stdOutput.readLine()) != null) {
            list.add(s);
        }
        return String.join("\n", list);
    }

    private static void guessPath(String workspace, String endWith, List<String> paths) {
        File file = new File(workspace);
        if (!file.isDirectory()) {
            return;
        }
        if (file.getAbsolutePath().endsWith(endWith)) {
            paths.add(file.getAbsolutePath());
        }
        File[] files = file.listFiles();
        if (files != null) {
            for (File listFile : files) {
                if (!listFile.isDirectory()) {
                    continue;
                }
                guessPath(listFile.getAbsolutePath(), endWith, paths);
            }
        }
    }

    @SneakyThrows
    @Override
    public synchronized void handle(HttpExchange httpExchange) {
        String path = httpExchange.getRequestURI().getPath();
        if ("/report".equals(path)) {
            try {
                doReport(httpExchange);
                httpExchange.sendResponseHeaders(200, 0);
                httpExchange.getResponseBody().write("".getBytes(StandardCharsets.UTF_8));
                httpExchange.getResponseBody().close();
            } catch (Exception e) {
                e.printStackTrace();
                httpExchange.sendResponseHeaders(500, 0);
                httpExchange.getResponseBody().write("".getBytes(StandardCharsets.UTF_8));
                httpExchange.getResponseBody().close();
            }
        }
        if ("/config".equals(path)) {
            try {
                httpExchange.sendResponseHeaders(200, this.toString().getBytes().length);
                httpExchange.getResponseBody().write(this.toString().getBytes(StandardCharsets.UTF_8));
                httpExchange.getResponseBody().close();
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
        if ("/clear".equals(path)) {
            try {
                Map<String, String> queryMap = getQueryMap(httpExchange);
                String appName = queryMap.get("app");
                if (appName != null) {
                    this.appName = appName;
                }
                clear();
                httpExchange.sendResponseHeaders(200, this.toString().getBytes().length);
                httpExchange.getResponseBody().write(this.toString().getBytes(StandardCharsets.UTF_8));
                httpExchange.getResponseBody().close();
            } catch (Exception e) {
                e.printStackTrace();
                httpExchange.sendResponseHeaders(500, this.toString().getBytes().length);
                httpExchange.getResponseBody().write(this.toString().getBytes(StandardCharsets.UTF_8));
                httpExchange.getResponseBody().close();
            }
        }
        if ("/auto".equals(path)) {
            try {
                Map<String, String> queryMap = getQueryMap(httpExchange);
                String refresh = queryMap.get("refresh");
                if (refresh != null) {
                    this.refresh = Boolean.valueOf(refresh);
                }
                String interval = queryMap.get("interval");
                if (interval != null) {
                    this.refreshInterval = Long.valueOf(interval);
                }
                httpExchange.sendResponseHeaders(200, String.valueOf(this.refresh).getBytes().length);
                httpExchange.getResponseBody().write(String.valueOf(this.refresh).getBytes(StandardCharsets.UTF_8));
                httpExchange.getResponseBody().close();
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
        if ("/export".equals(path)) {
            try {
                Map<String, String> queryMap = getQueryMap(httpExchange);
                String app = queryMap.get("app");
                String branch = queryMap.get("branch");
                compressZip(app, branch);
                httpExchange.sendResponseHeaders(200, 0);
                httpExchange.getResponseBody().write("".getBytes());
                httpExchange.getResponseBody().close();
            } catch (Exception e) {
                e.printStackTrace();
                httpExchange.sendResponseHeaders(500, 0);
                httpExchange.getResponseBody().write("".getBytes(StandardCharsets.UTF_8));
                httpExchange.getResponseBody().close();
            }
        }
    }

    private synchronized String compressZip(String app, String branch) {
        String reportZipPath = root + File.separator + baseDir + File.separator + app + File.separator + branch + File.separator + "exportReport.zip";
        if (FileUtil.exist(reportZipPath)) {
            FileUtil.del(reportZipPath);
        }
        String reportDirPath = root + File.separator + baseDir + File.separator + app + File.separator + branch + File.separator + "report";
        ZipUtil.zip(reportDirPath, reportZipPath);
        return reportZipPath;
    }

    private synchronized void clear() {
        deleteFolder(new File(root + File.separator + baseDir + File.separator + this.appName + File.separator + this.curBranch));
    }

    private void doReport(HttpExchange httpExchange) throws Exception {
        /*
         * get params
         */
        Map<String, String> queryMap = getQueryMap(httpExchange);
        String appName = queryMap.get("app");
        if (appName != null) {
            this.appName = appName;
        }
        exec(queryMap.get("sign"));
    }

    private Map<String, String> getQueryMap(HttpExchange httpExchange) {
        String query = httpExchange.getRequestURI().getQuery();
        Map<String, String> queryMap = new HashMap<>();
        if (query != null) {
            Arrays.stream(query.split("&")).forEach(s -> {
                String[] arr = s.split("=");
                queryMap.put(arr[0], arr[1]);
            });
        }
        return queryMap;
    }

    private synchronized void exec(String sign) throws Exception {

        /*
         * 1. dump data to timestamp
         */
        String timestampDirPath = root + File.separator + baseDir + File.separator + this.appName +File.separator + curBranch + File.separator + timestamp;
        String timestampExecFilePath = timestampDirPath + File.separator + "dump.exec";
        String timestampMergedFilePath = timestampDirPath + File.separator + "merged.exec";
        String timestampClassIdFilePath = timestampDirPath + File.separator + classHashFileName;
        String timestampClassDirPath = timestampDirPath + File.separator + "class";
        String timestampSourceDirPath = timestampDirPath + File.separator + "src";

        List<File> timestampClassFileList = new LinkedList<>();

        File timestampDirFile = new File(timestampDirPath);
        Map<String, String> timestampClassNameIdMap = new HashMap<>();
        Map<String, String> packageMapping = new HashMap<>();

        if (!timestampDirFile.exists()) {
            timestampDirFile.mkdirs();
            Files.deleteIfExists(Paths.get(timestampExecFilePath));
            JacocoApi.execute("dump", "--destfile", timestampExecFilePath, "--address", this.dumpHost, "--port", this.dumpPort + "");
            String execFileMD5 = sha(FileUtil.readBytes(timestampExecFilePath));
            if (lastExecFileMD5 != null && lastExecFileMD5.equals(execFileMD5)) {
                return;         // no change
            }
            lastExecFileMD5 = execFileMD5;
            /*
             * 2.1 Generate class file hash to timestamp
             */
            List<String> classIdNameList = new LinkedList<>();
            for (String classDir : classPath.split(",")) {
                classDir = classDir.trim();
                addClassHashAndCopy(classIdNameList, classDir, timestampClassDirPath);
            }
            if (!classIdNameList.isEmpty()) {
                String content = String.join("\n", classIdNameList);
                Files.write(Paths.get(timestampClassIdFilePath), content.getBytes(StandardCharsets.UTF_8));
                timestampClassNameIdMap = classIdNameList.stream().distinct().map(e -> e.split(":")).collect(Collectors.toMap(e -> e[1], e -> e[0]));
            }

            /*
             * 2.2 Copy source file
             */
            for (String sourceDir : sourcePath.split(",")) {
                sourceDir = sourceDir.trim();
                String packagePath = sourceDir.substring(0, sourceDir.lastIndexOf(SRC_FLAG));
                String packageName = packagePath.substring(packagePath.lastIndexOf(File.separator) + 1);
                copySourceFile(sourceDir, timestampSourceDirPath, packageName, packageMapping);
            }
        } else {
            Files.deleteIfExists(Paths.get(timestampExecFilePath));
            JacocoApi.execute("dump", "--destfile", timestampExecFilePath, "--address", this.dumpHost, "--port", this.dumpPort + "");
        }

        /*
         * pull master branch code
         */
        List<ClassInfo> diffClassInfos = new LinkedList<>();
        if (!"master".equals(this.curBranch)) {
            String masterCloneSourceDir = root + File.separator + baseDir + File.separator + this.appName +File.separator + "master_clone";
            if (!isMasterCheckout) {    // only once
                File masterSourceFile = new File(masterCloneSourceDir);
                String masterCloneSourceGitDir;
                if (masterSourceFile.exists()) {
                    masterCloneSourceGitDir = Objects.requireNonNull(masterSourceFile.listFiles())[0].getAbsolutePath();
                    execCmd(masterCloneSourceGitDir, "git fetch origin");
                    execCmd(masterCloneSourceGitDir, "git pull origin master");
                } else {
                    masterSourceFile.mkdirs();
                    String cloneUrl = gitUrl;
                    if (gitUser != null && gitPass != null && !gitUser.trim().equals("") && !gitPass.trim().equals("")) {
                        cloneUrl = appendGitUserPass(gitUrl, gitUser, gitPass);
                    }
                    execCmd(masterCloneSourceDir, "git clone " + cloneUrl);
                    masterCloneSourceGitDir = Objects.requireNonNull(masterSourceFile.listFiles())[0].getAbsolutePath();
                    execCmd(masterCloneSourceGitDir, "git fetch origin");
                }
                String commonCommitId = getCommonCommitId(this.workspace, masterCloneSourceGitDir);
                if (commonCommitId != null && !commonCommitId.trim().equals("")) {
                    // checkout master by commitId
                    execCmd(masterCloneSourceGitDir, "git checkout " + commonCommitId);
                }
                isMasterCheckout = true;
            }

            String destMasterSourceDir = root + File.separator + baseDir + File.separator + this.appName +File.separator + "master_code";

            if (!isMasterCopied) {   // only once
                File destMasterSourceDirFile = new File(destMasterSourceDir);
                if (destMasterSourceDirFile.exists()) {
                    deleteFolder(destMasterSourceDirFile);
                }
                List<String> masterSourcePaths = new LinkedList<>();
                guessPath(masterCloneSourceDir, "src" + File.separator + "main" + File.separator + "java", masterSourcePaths);
                for (String masterSourcePath : masterSourcePaths) {
                    copySourceFile(masterSourcePath.trim(), destMasterSourceDir, null, null);
                }
                isMasterCopied = true;
            }

            getDiffCode(timestampSourceDirPath, timestampSourceDirPath, destMasterSourceDir, diffClassInfos, packageMapping);
        }
        if (!diffClassInfos.isEmpty()) {
            CoverageBuilder.setDiffList(diffClassInfos);
        }

        addClassFiles(timestampClassDirPath, timestampClassFileList);

        /*
         * 3. merge merged data to timestamp
         */
        String orgDirPath = root + File.separator + baseDir + File.separator + this.appName + File.separator + this.curBranch + File.separator + "org";
        String orgExecFilePath = orgDirPath + File.separator + "dump.exec";
        String orgClassIdFilePath = orgDirPath + File.separator + classHashFileName;
        String orgClassDirPath = orgDirPath + File.separator + "class";
        String orgSourceDirPath = orgDirPath + File.separator + "src";
        List<File> orgClassFileList = new LinkedList<>();

        Map<String, String> orgClassNameIdMap;
        File orgDirFile = new File(orgDirPath);

        if (orgDirFile.exists()) {
            if (!new File(orgClassIdFilePath).exists() || !new File(orgExecFilePath).exists()) {
                deleteFolder(new File(orgDirPath));
            }
        }

        if (!orgDirFile.exists()) {
            timestampDirFile.renameTo(orgDirFile);
        } else {
            addClassFiles(orgClassDirPath, orgClassFileList);
            orgClassNameIdMap = Files.readAllLines(Paths.get(orgClassIdFilePath)).stream().distinct().map(e -> e.split(":")).collect(Collectors.toMap(e -> e[1], e -> e[0]));

            //todo 找出修改，删除的class, 如果是删除, 则去掉探针，如果是修改，则探针重置，其他合并
            Map<String, String> finalOrgClassNameIdMap = orgClassNameIdMap;
            Set<String> modifiedClass = timestampClassNameIdMap.entrySet().stream().filter(entry -> finalOrgClassNameIdMap.containsKey(entry.getKey()) && !finalOrgClassNameIdMap.get(entry.getKey()).equals(entry.getValue())).map(Map.Entry::getKey).collect(Collectors.toSet());
            Map<String, String> finalTimestampClassNameIdMap = timestampClassNameIdMap;
            Set<String> deletedClass = orgClassNameIdMap.entrySet().stream().filter(entry -> !finalTimestampClassNameIdMap.containsKey(entry.getKey())).map(Map.Entry::getKey).collect(Collectors.toSet());

            ExecFileLoader timestampExecFileLoader = new ExecFileLoader();
            timestampExecFileLoader.load(new File(timestampExecFilePath));
            ExecutionDataStore timestampExecutionDataStore = timestampExecFileLoader.getExecutionDataStore();
            Collection<ExecutionData> timestampExecutionDataCollection = timestampExecutionDataStore.getContents();
            Map<String, IClassCoverage> timestampIClassCoverageMap = classAnalysis(timestampExecutionDataStore, timestampClassFileList);

            ExecFileLoader orgExecFileLoader = new ExecFileLoader();
            orgExecFileLoader.load(new File(orgExecFilePath));
            ExecutionDataStore orgExecutionDataStore = orgExecFileLoader.getExecutionDataStore();
            Map<String, IClassCoverage> orgIClassCoverageMap = classAnalysis(orgExecutionDataStore, orgClassFileList);


            for (ExecutionData timestampData : timestampExecutionDataCollection) {
                for (ExecutionData orgData : orgExecutionDataStore.getContents()) {
                    if (orgData.getName().equals(timestampData.getName())) {
                        String classDataName = timestampData.getName();
                        String className = classDataName.replace("/", ".");
                        if (modifiedClass.contains(className)) {
                            if (mergeLevel.equals("class")) {
                                timestampData.reset();
                                continue;
                            }
                            String relativeClassPath = timestampData.getName().replace("/", File.separator) + ".java";
                            String orgSourcePath = orgSourceDirPath + File.separator + relativeClassPath;
                            String timestampSourcePath = timestampSourceDirPath + File.separator + relativeClassPath;
                            if (new File(orgSourcePath).exists() && new File(timestampSourcePath).exists()) {
                                resetProbe(className, orgData, orgSourcePath, (ClassCoverageImpl) orgIClassCoverageMap.get(classDataName), timestampData, timestampSourcePath, (ClassCoverageImpl) timestampIClassCoverageMap.get(classDataName), timestampExecutionDataStore);
                            }
                        } else {
                            boolean[] timestampProbes = timestampData.getProbes();
                            boolean[] orgProbes = orgData.getProbes();
                            if (orgProbes.length >= timestampProbes.length) {
                                for (int i = 0; i <timestampProbes.length; i++) {
                                    timestampProbes[i] = timestampProbes[i] | orgProbes[i];
                                }
                            }
                        }
                    }
                }
            }

            for (String delete : deletedClass) {
                timestampExecutionDataCollection.stream().filter(e -> e.getName().replace("/", ".").equals(delete)).findFirst().ifPresent(timestampExecutionDataCollection::remove);
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
         * post to server
         */
        String requestUrl = "http://127.0.0.1:8888/api/rpc/coverage/local?sign + " + sign;
        List<Object> body = new LinkedList<>();
        body.add(this.toString());
        String resp = HttpUtil.post(requestUrl, JSONUtil.toJsonPrettyStr(body));
        Map<String, Object> map = JSONUtil.toBean(resp, Map.class);
        if (!"1".equalsIgnoreCase((String) map.get("code"))) {
            return;
        }

        /*
         * 5. generate report to root
         */
        String reportDirPath = root + File.separator + baseDir + File.separator + this.appName + File.separator + this.curBranch + File.separator + "report";
        deleteFolder(new File(reportDirPath));
        ExecFileLoader reportLoader = new ExecFileLoader();
        reportLoader.load(new File(orgExecFilePath));
        List<File> reportClassFiles = new LinkedList<>();
        addClassFiles(orgClassDirPath, reportClassFiles);
        IBundleCoverage bundle = analyze(this.appName + " (" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + ")", reportLoader.getExecutionDataStore(), reportClassFiles);
        writeReports(bundle, reportLoader, new File(reportDirPath));
    }

    private String appendGitUserPass(String gitUrl, String user, String pass) {
        if (gitUrl.startsWith("http")) {
            gitUrl = gitUrl.replace("://", "://" + user + ":" + pass + "@");
        }
        return gitUrl;
    }

    private String getCommonCommitId(String workspace, String masterCloneSourceDir) {
        String[] branchCommitIds = execCmd(this.workspace, "git log --pretty=format:\"%H\" -n 100").split("\n");
        String[] masterCommitIds = execCmd(masterCloneSourceDir, "git log origin/master --pretty=format:\"%H\" -n 100").split("\n");
        for (String branchCommitId :  branchCommitIds) {
            for (String masterCommitId : masterCommitIds) {
                if (branchCommitId.equals(masterCommitId) && !masterCommitId.equals(masterCommitIds[0])) {
                    return branchCommitId;
                }
            }
        }
        return null;
    }

    private void getDiffCode(String branchSourceDir, String branchFilePath, String masterPath, List<ClassInfo> diffClassInfos, Map<String, String> packageMapping) {
        File file = new File(branchFilePath);
        if (!file.isDirectory()) {
            String javaFileName = file.getName();
            String javaFilePath = file.getAbsolutePath();
            String classFileName = javaFilePath.replace(branchSourceDir, "").substring(1).replace(File.separator, ".");
            if (!javaFileName.endsWith(".java") || !javaFilePath.contains("com" + File.separator + "intramirror")) {
                return;
            }
            String masterJavaPath = masterPath + File.separator + javaFileName;
            String branchFileMD5 = sha(FileUtil.readBytes(branchFilePath));
            File masterJavaFile = new File(masterJavaPath);
            if (masterJavaFile.exists()) {
                String masterFileMD5 = sha(FileUtil.readBytes(masterJavaPath));
                if (branchFileMD5.equals(masterFileMD5)) {
                    return;
                }
            }

            File destDir = new File(masterPath);
            List<JavaMethodInfo> branchMethodInfos = parseJavaFile(branchFilePath);
            if (!destDir.exists()) {
                ClassInfo branchClassInfo = toClassInfo(packageMapping, classFileName, branchMethodInfos, null);
                if (branchClassInfo != null) {
                    diffClassInfos.add(branchClassInfo);
                }
                return;
            }

            ClassInfo classInfo;
            if (!masterJavaFile.exists()) {
                classInfo = toClassInfo(packageMapping, classFileName, branchMethodInfos, null);
            } else {
                classInfo = toClassInfo(packageMapping, classFileName, branchMethodInfos, parseJavaFile(masterJavaPath));
            }
            if (classInfo != null) {
                diffClassInfos.add(classInfo);
            }
            return;
        }
        for (File child : Objects.requireNonNull(file.listFiles())) {
            String newTimestampJavaPath = masterPath;
            if (child.isDirectory()) {
                newTimestampJavaPath = masterPath + File.separator + child.getName();
            }
            getDiffCode(branchSourceDir, child.getAbsolutePath(), newTimestampJavaPath, diffClassInfos, packageMapping);
        }
    }

    private ClassInfo toClassInfo(Map<String, String> packageMapping, String classFileName, List<JavaMethodInfo> branchMethodInfos, List<JavaMethodInfo> masterMethodInfos) {
        String className = classFileName.substring(0, classFileName.lastIndexOf("."));
        String packageName = packageMapping.get(className);
        className = className.replace(".", "/");
        if (masterMethodInfos == null) {
            List<MethodInfo> methodInfos = branchMethodInfos.stream().map(javaMethodInfo -> MethodInfo.builder().diffType(DiffResultTypeEnum.ADD).md5(javaMethodInfo.getMd5()).methodName(javaMethodInfo.getMethodName()).methodUri(javaMethodInfo.getMethodUri()).params(javaMethodInfo.getParams()).build()).collect(Collectors.toList());
            if (methodInfos.isEmpty()) {
                return null;
            }
            return ClassInfo.builder().className(className).packageName(packageName).diffType(DiffResultTypeEnum.ADD).methodInfos(methodInfos).build();
        }
        Set<String> sameMethod = new HashSet<>();
        for (JavaMethodInfo branchMethodInfo : branchMethodInfos) {
            for (JavaMethodInfo masterMethodInfo : masterMethodInfos) {
                if (branchMethodInfo.toString().equals(masterMethodInfo.toString())) {
                    sameMethod.add(branchMethodInfo.toString());
                }
            }
        }
        List<MethodInfo> diffMethods = branchMethodInfos.stream().filter(e -> !sameMethod.contains(e.toString())).map(e -> MethodInfo.builder().methodName(e.getMethodName()).params(e.getParams()).methodUri(e.getMethodUri()).md5(e.getMd5()).diffType(DiffResultTypeEnum.MODIFY).build()).collect(Collectors.toList());
        if (diffMethods.isEmpty()) {
            return null;
        }
        return ClassInfo.builder().className(className).packageName(packageName).diffType(DiffResultTypeEnum.MODIFY).methodInfos(diffMethods).build();
    }

    private void copySourceFile(String filePath, String timestampJavaPath, String packageName, Map<String, String> packageMapping) throws IOException {
        File file = new File(filePath);
        if (!file.isDirectory()) {
            String javaFileName = file.getName();
            String classFilePath = file.getAbsolutePath();
            if (!javaFileName.endsWith(".java") || !classFilePath.contains("com" + File.separator + "intramirror")) {
                return;
            }
            File destDir = new File(timestampJavaPath);
            if (!destDir.exists()) {
                destDir.mkdirs();
            }
            String className = classFilePath.substring(classFilePath.lastIndexOf(SRC_FLAG) + SRC_FLAG.length() + 1).replace(File.separator, ".");
            className = className.substring(0, className.lastIndexOf("."));
            if (packageName != null && packageMapping != null) {
                packageMapping.put(className, packageName);
            }
            Files.copy(Paths.get(classFilePath), Paths.get(timestampJavaPath + File.separator + javaFileName), StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        for (File child : Objects.requireNonNull(file.listFiles())) {
            String newTimestampJavaPath = timestampJavaPath;
            if (child.isDirectory()) {
                newTimestampJavaPath = timestampJavaPath + File.separator + child.getName();
            }
            copySourceFile(child.getAbsolutePath(), newTimestampJavaPath, packageName, packageMapping);
        }
    }

    private void resetProbe(String className, ExecutionData orgData, String orgClassPath, ClassCoverageImpl orgClassCoverage, ExecutionData timestampData, String timestampClassPath, ClassCoverageImpl timestampClassCoverage, ExecutionDataStore timestampExecutionDataStore) {
        if (orgClassCoverage == null) {
            return;
        }
        List<JavaMethodInfo> sameMethods = getSameMethods(orgClassPath, timestampClassPath);
        List<MethodProbesInfo> orgMethodProbesInfos = orgClassCoverage.getMethodProbesInfos();
        for (MethodProbesInfo orgMethodProbesInfo : orgMethodProbesInfos) {
            String name = orgMethodProbesInfo.getMethodName();
            String desc = orgMethodProbesInfo.getDesc();
            if(sameMethods.isEmpty() || !containInSameMethodInfo(sameMethods, name, desc)) {
               continue;
            }
            MethodProbesInfo timestampMethodProbesInfo = getTimestampMethodInfo(timestampClassCoverage.getMethodProbesInfos(), orgMethodProbesInfo);
            if (timestampMethodProbesInfo == null) {
                continue;
            }
            int length = timestampMethodProbesInfo.getEndIndex() - timestampMethodProbesInfo.getStartIndex() + 1;
            int newStartIndex = timestampMethodProbesInfo.getStartIndex();
            int oldStartIndex = orgMethodProbesInfo.getStartIndex();
            boolean[] newProbes = timestampData.getProbes();
            boolean[] oldProbes = orgData.getProbes();

            if (Objects.isNull(oldProbes)) {
                // ignore
                continue;
            } else if (Objects.isNull(newProbes)
                    && !Objects.isNull(oldProbes)) {
                // new exec modify class not init
                // get probes size
                int newProbesSize = 0;
                for (MethodProbesInfo info : timestampClassCoverage.getMethodProbesInfos()) {
                    int len = info.getEndIndex() - info.getStartIndex() + 1;
                    newProbesSize += len;
                }
                newProbes = new boolean[newProbesSize];
                ExecutionData data = new ExecutionData(timestampClassCoverage.getId(), className, newProbes);
                timestampExecutionDataStore.put(data);
            }
            // 这里应该还要加上set数据的合并
            while (length-- > 0) {
                if (newStartIndex < newProbes.length && oldStartIndex < oldProbes.length) {
                    newProbes[newStartIndex] = newProbes[newStartIndex] | oldProbes[oldStartIndex];
                    newStartIndex++;
                    oldStartIndex++;
                }
            }
        }
    }

    private MethodProbesInfo getTimestampMethodInfo(List<MethodProbesInfo> methodProbesInfos, MethodProbesInfo orgMethodProbesInfo) {
        for (MethodProbesInfo timestampMethodProbesInfo : methodProbesInfos) {
            if (timestampMethodProbesInfo.getMethodName().equals(orgMethodProbesInfo.getMethodName()) &&
                    timestampMethodProbesInfo.getDesc().equals(orgMethodProbesInfo.getDesc()) &&
                            timestampMethodProbesInfo.getMethodUri().equals(orgMethodProbesInfo.getMethodUri())) {
                return timestampMethodProbesInfo;
            }
        }
        return null;
    }

    private boolean containInSameMethodInfo(List<JavaMethodInfo> diffMethods, String name, String desc) {
        for (JavaMethodInfo methodInfo : diffMethods) {
            String params = methodInfo.getParams();
            if (methodInfo.getMethodName().equals(name) && MethodUriAdapter.checkParamsIn(params, desc)) {
                return true;
            }
        }
        return false;
    }

    private List<JavaMethodInfo> getSameMethods(String orgJavaPath, String timestampJavaPath) {
        List<JavaMethodInfo> orgMethodInfos = parseJavaFile(orgJavaPath);
        List<JavaMethodInfo> timestampMethodInfos = parseJavaFile(timestampJavaPath);
        List<JavaMethodInfo> sameMethodInfos = new LinkedList<>();
        for (JavaMethodInfo orgMethodInfo : orgMethodInfos) {
            for (JavaMethodInfo timestampMethodInfo : timestampMethodInfos) {
                if (orgMethodInfo.getMethodName().equals(timestampMethodInfo.getMethodName()) &&
                orgMethodInfo.getMd5().equals(timestampMethodInfo.getMd5()) &&
                        orgMethodInfo.getParams().equals(timestampMethodInfo.getParams())
                ) {
                    sameMethodInfos.add(orgMethodInfo);
                }
            }
        }
        return sameMethodInfos;
    }

    @SneakyThrows
    private List<JavaMethodInfo> parseJavaFile(String path) {
        List<JavaMethodInfo> list = new ArrayList<>();
        try (FileInputStream in = new FileInputStream(path)){
            JavaParser javaParser = new JavaParser();
            CompilationUnit cu = javaParser.parse(in).getResult().orElseThrow(() -> new RuntimeException("parse java error"));
            final List<?> types = cu.getTypes();
            boolean isInterface = types.stream().filter(t -> t instanceof ClassOrInterfaceDeclaration).anyMatch(t -> ((ClassOrInterfaceDeclaration) t).isInterface());
            if (isInterface) {
                return list;
            }
            cu.accept(new MethodVisitor(cu.getPackageDeclaration().orElse(null), cu.getImports()), list);
            return list;
        }
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
        final List<IReportVisitor> visitors = new ArrayList<>();
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
            File[] listFiles = classFile.listFiles();
            if (listFiles != null) {
                for (File file : listFiles) {
                    addClassFiles(file.getAbsolutePath(), classFileList);
                }
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
            Files.copy(Paths.get(classFilePath), Paths.get(timestampClassPath + File.separator + classFileName), StandardCopyOption.REPLACE_EXISTING);
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
            return sha(fileBytes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SneakyThrows
    private static String sha(byte[] bytes) {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(bytes);
        byte[] hashBytes = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public int getPort() {
        return port;
    }

    @SneakyThrows
    @Override
    public void run() {
        Thread.sleep(90 * 1000L);
        while (true) {
            try {
                Thread.sleep(refreshInterval);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            try {
                if (this.appName == null || !refresh) {
                    continue;
                }
                exec(null);
                push();
                //System.out.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.sss").format(new Date()) + ": auto refresh coverage report");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private long lastPushTimestamp;

    private int lastCoverageRate;

    private void push() {
        if (lastPushTimestamp > 0L && System.currentTimeMillis() - lastPushTimestamp <  pushInterval) {
            return;
        }
        try {
            Integer coverageRate = parseCoverage(String.format("http://127.0.0.1:%s/code-coverage/%s/%s/report/index.html", this.filePort, this.appName, this.curBranch));
            if (coverageRate == null) {
                return;
            }
            this.coverageRate = coverageRate;
            if (this.coverageRate < 0 || this.lastCoverageRate == this.coverageRate) {
                return;
            }
            this.lastCoverageRate = coverageRate;
            String json = JSONUtil.toJsonPrettyStr(this);
            Map<String, Object> map = JSONUtil.toBean(json, Map.class);
            List<Object> body = new ArrayList<>();
            body.add(map);
            body.add(FileUtil.readBytes(compressZip(this.appName, this.curBranch)));
            String requestUrl = "http://127.0.0.1:8888/api/rpc/coverage/localPushReport";
            HttpUtil.post(requestUrl, JSONUtil.toJsonStr(body));
            lastPushTimestamp = System.currentTimeMillis();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Integer parseCoverage(String reportUrl) {
        String xml = HttpUtil.get(reportUrl).replace("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">", "");
        org.w3c.dom.NodeList nodeList = XmlUtil.parseXml(xml).getDocumentElement().getElementsByTagName("tfoot");
        if (nodeList == null || nodeList.getLength() == 0) {
            return -1;
        }
        String coverage = nodeList.item(0).getChildNodes().item(0).getChildNodes().item(2).getTextContent().replace("%", "");
        return Integer.valueOf(coverage);
    }

    private static class MethodVisitor extends VoidVisitorAdapter<List<JavaMethodInfo>> {

        private final Map<String, String> importClassNames;
        private final String packageName;

        private MethodVisitor(PackageDeclaration packageDeclaration, NodeList<ImportDeclaration> imports) {
            this.packageName = packageDeclaration == null ? null : packageDeclaration.getNameAsString();
            importClassNames = new HashMap<>();
            if (imports != null) {
                imports.forEach(importDeclaration -> {
                    String name = importDeclaration.getNameAsString();
                    String[] arr = name.split("\\.");
                    String simpleName = arr.length == 1 ? arr[0] : arr[arr.length - 1];
                    importClassNames.put(simpleName, name);
                });
            }
        }

        @Override
        public void visit(ConstructorDeclaration n, List<JavaMethodInfo> list) {
            addMethod(n, list, "<init>");
            super.visit(n, list);
        }

        @SneakyThrows
        @Override
        public void visit(MethodDeclaration n, List<JavaMethodInfo> list) {
            addMethod(n, list, n.getNameAsString());
            super.visit(n, list);
        }

        private void addMethod(CallableDeclaration n, List<JavaMethodInfo> list, String methodName) {
            n.removeComment();
            String md5 = SecureUtil.md5(n.toString());
            StringBuilder params = new StringBuilder();
            NodeList<Parameter> parameters = n.getParameters();
            if(!(parameters == null || parameters.isEmpty())){
                for (Parameter parameter : parameters) {
                    Type paramType = parameter.getType();
                    String param = paramType.toString();
                    params.append(param.replaceAll(" ", ""));
                    params.append(";");
                }
            }
            JavaMethodInfo methodInfo = JavaMethodInfo.builder()
                    .md5(md5)
                    .methodName(methodName)
                    .params(params.toString())
                    .build();
            list.add(methodInfo);
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    @ToString
    public static class JavaMethodInfo implements Serializable {
        private static final long serialVersionUID = 1L;

        private String methodName;
        private String md5;
        private String methodUri;
        private String params;
    }

    public static List<String> getAllLocalHostIP() {
        List<String> list = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces;
            interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                Enumeration<InetAddress> addresss = ni.getInetAddresses();
                while(addresss.hasMoreElements()){
                    InetAddress nextElement = addresss.nextElement();
                    String hostAddress = nextElement.getHostAddress();
                    if (isIp(hostAddress) && !"127.0.0.1".equals(hostAddress)) {
                        list.add(hostAddress);
                    }
                }
            }
        } catch (Exception e) {
            //e.printStackTrace();
        }
        return list;
    }

    private static boolean isIp(String host) {
        return host.matches("([1-9]|[1-9]\\d|1\\d{2}|2[0-4]\\d|25[0-5])" +
                "(\\.(\\d|[1-9]\\d|1\\d{2}|2[0-4]\\d|25[0-5])){3}");
    }
}
