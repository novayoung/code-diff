package com.test.diff.services.utils;

import cn.hutool.core.io.FileUtil;
import com.adrninistrator.jacg.common.DC;
import com.adrninistrator.jacg.common.enums.*;
import com.adrninistrator.jacg.conf.ConfigureWrapper;
import com.adrninistrator.jacg.dboper.DbOperWrapper;
import com.adrninistrator.jacg.dboper.DbOperator;
import com.adrninistrator.jacg.dto.write_db.WriteDbData4ClassAnnotation;
import com.adrninistrator.jacg.dto.write_db.WriteDbData4MethodAnnotation;
import com.adrninistrator.jacg.runner.RunnerGenAllGraph4Callee;
import com.adrninistrator.jacg.runner.RunnerGenAllGraph4Caller;
import com.adrninistrator.jacg.runner.RunnerWriteDb;
import com.adrninistrator.jacg.util.JACGSqlUtil;
import com.test.diff.services.consts.FileConst;
import lombok.Data;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CallGraphUtils {

    public static void create(String ws, String service, String dbFile, List<String> jarPaths) {
        RunnerWriteDb runnerWriteDb = new RunnerWriteDb();
        ConfigureWrapper configureWrapper = buildConfigureWrapper(ws, service, dbFile);
        configureWrapper.setOtherConfigList(OtherConfigFileUseListEnum.OCFULE_JAR_DIR, jarPaths);

        runnerWriteDb.run(configureWrapper);
    }

    public static void caller(String ws, String service, String dbFile, Set<String> caller) {
        RunnerGenAllGraph4Caller runnerGenAllGraph4Caller = new RunnerGenAllGraph4Caller();
        ConfigureWrapper configureWrapper = buildConfigureWrapper(ws, service, dbFile);
        configureWrapper.setOtherConfigSet(OtherConfigFileUseSetEnum.OCFUSE_METHOD_CLASS_4CALLER, caller);
        runnerGenAllGraph4Caller.run(configureWrapper);
    }

    public static com.test.diff.services.base.controller.result.BaseResult callee(String ws, String service, String dbFile, Set<String> caller) {
        String reportDir = ws + File.separator + "_jacg_o_ee";
        FileUtil.del(reportDir);
        RunnerGenAllGraph4Callee runnerGenAllGraph4Caller = new RunnerGenAllGraph4Callee();
        ConfigureWrapper configureWrapper = buildConfigureWrapper(ws, service, dbFile);
        configureWrapper.setOtherConfigSet(OtherConfigFileUseSetEnum.OCFUSE_METHOD_CLASS_4CALLEE, caller);
        runnerGenAllGraph4Caller.run(configureWrapper);

        DbOperWrapper dbOperWrapper = DbOperWrapper.genInstance(configureWrapper, RunnerGenAllGraph4Callee.class.getSimpleName());
        DbOperator dbOperator = dbOperWrapper.getDbOperator();

        try {
            Map<String, List<List<String>>> allTraces = new LinkedHashMap<>();
            File file = new File(reportDir);
            File methodsDir = Arrays.stream(file.listFiles()[0].listFiles()).filter(file1 -> file1.getName().equalsIgnoreCase("methods")).findFirst().orElse(null);
            for (File methodFile : methodsDir.listFiles()) {
                String methodsFileName = methodFile.getName();
                String emptyFlag = "-empty.txt";
                if (methodsFileName.endsWith(emptyFlag)) {
                    String methodFullName = methodsFileName.replace(emptyFlag, "").replace("@", ":");
                    Map<String, String> uriMap = getApiByControllerMethod(dbOperator, configureWrapper.getMainConfig(ConfigKeyEnum.CKE_APP_NAME), methodFullName);
                    if (uriMap != null) {
                        String uri = uriMap.get("uri");
                        String fullMethod = uriMap.get("fullMethod");
                        allTraces.computeIfAbsent(uri, (k) -> new LinkedList<>()).add(new LinkedList<String>() {{add(fullMethod);}});
                    }
                } else {
                    List<String> lines = FileUtil.readLines(methodFile, "UTF-8");
                    Stack<String> stack = new Stack<>();
                    for (String line : lines) {
                        if (!line.startsWith("[")) {
                            stack.clear();
                            continue;
                        }
                        Matcher matcher = Pattern.compile("\\[(\\d+)\\]#").matcher(line);
                        matcher.find();
                        int level = Integer.parseInt(matcher.group(1));
                        if (stack.isEmpty() || level >= stack.size()) {
                            stack.push(line);
                            continue;
                        }
                        String entry = stack.peek();
                        String uri = parseEntry(entry);                 // 解析uri
                        allTraces.computeIfAbsent(uri, (k) -> new LinkedList<>()).add(new LinkedList<>(stack));
                        int popSize = stack.size() - level;
                        for (int i = 0; i < popSize; i++) {
                            stack.pop();
                        }
                        stack.push(line);
                    }
                    if (!stack.isEmpty()) {
                        String entry = stack.peek();
                        String uri = parseEntry(entry);                 // 解析uri
                        allTraces.computeIfAbsent(uri, (k) -> new LinkedList<>()).add(new LinkedList<>(stack));
                    }
                }
            }
            return com.test.diff.services.base.controller.result.BaseResult.success(allTraces);
        } finally {
            dbOperator.closeDs();
        }

    }

    private static String parseEntry(String line) {
        if (isMapping(line)) {
            String tmp = Arrays.stream(line.split("@")).filter(CallGraphUtils::isMapping).findFirst().orElse(null);
            int start = tmp.indexOf("(");
            int end = tmp.indexOf(")");
            String uri = tmp.substring(start + 1, end);
            String[] arr = tmp.substring(0, start).split("\\.");
            String httpMethod = arr[arr.length - 1].replace("Mapping", "");
            if (uri.endsWith("/")) {
                uri = uri.substring(0, uri.length() - 1);
            }
            return httpMethod + ": " + uri;
        }
        return line;
    }

    private static boolean isMapping(String line) {
        return line.contains(GetMapping.class.getName()) ||
                line.contains(PostMapping.class.getName()) ||
                line.contains(PutMapping.class.getName()) ||
                line.contains(PatchMapping.class.getName()) ||
                line.contains(DeleteMapping.class.getName());
    }

    private static Map<String, String> getApiByControllerMethod(DbOperator dbOperator, String appName, String methodFullName) {
        String baseUriSql = "select " + "*" +
                " from " + DbTableInfoEnum.DTIE_CLASS_ANNOTATION.getTableName() +
                " where " + DC.COMMON_SIMPLE_CLASS_NAME + " = ?" +
                " and " + DC.COMMON_ANNOTATION_ANNOTATION_NAME + " = ?";
        baseUriSql = JACGSqlUtil.replaceAppNameInSql(baseUriSql, appName);
        WriteDbData4ClassAnnotation classAnnotation = dbOperator.queryObject(baseUriSql, WriteDbData4ClassAnnotation.class, methodFullName.split(":")[0], "org.springframework.web.bind.annotation.RequestMapping");
        if (classAnnotation == null) {
            return null;
        }
        String baseUri = classAnnotation.getAttributeValue().replace("\\\"", "");
        baseUri = baseUri.substring(2, baseUri.length() - 2);

        String className = classAnnotation.getClassName();
        String methodUriSql = "select " + "*" +
                " from " + DbTableInfoEnum.DTIE_METHOD_ANNOTATION.getTableName() +
                " where " + DC.COMMON_SIMPLE_CLASS_NAME + " = ?" +
//                " and " + DC.COMMON_FULL_METHOD + " = ?" +
                " and " + DC.MA_SPRING_MAPPING_ANNOTATION + " = 1" +
                " and (" +
                DC.COMMON_ANNOTATION_ANNOTATION_NAME + " = '" + GetMapping.class.getName() + "'" +
                " or " + DC.COMMON_ANNOTATION_ANNOTATION_NAME + " = '" + PostMapping.class.getName() + "'" +
                " or " + DC.COMMON_ANNOTATION_ANNOTATION_NAME + " = '" + PutMapping.class.getName() + "'" +
                " or " + DC.COMMON_ANNOTATION_ANNOTATION_NAME + " = '" + PatchMapping.class.getName() + "'" +
                " or " + DC.COMMON_ANNOTATION_ANNOTATION_NAME + " = '" + DeleteMapping.class.getName() + "'" +
                ")";
        methodUriSql = JACGSqlUtil.replaceAppNameInSql(methodUriSql, appName);
        String[] arr = methodFullName.split(":");
        String simpleClassName = arr[0];
        String fullMethod = className + ":" + arr[1];
        List<WriteDbData4MethodAnnotation> methodUriObjs = dbOperator.queryList(methodUriSql, WriteDbData4MethodAnnotation.class, simpleClassName);
        if (methodUriObjs == null || methodUriObjs.isEmpty()) {
            return null;
        }
        WriteDbData4MethodAnnotation methodUriObj = methodUriObjs.stream().filter(m -> m.getFullMethod().startsWith(fullMethod + "(")).findFirst().orElse(null);
        if (methodUriObj == null) {
            return null;
        }
        String methodUri = methodUriObj.getAttributeValue();
        methodUri = methodUri.substring(2, methodUri.length() - 2);
        String[] arr1 = methodUriObj.getAnnotationName().split("\\.");
        String httpMethod = arr1[arr1.length - 1].replace("Mapping", "");
        String finalBaseUri = baseUri;
        String finalMethodUri = methodUri;
        return new HashMap<String, String>() {{
            put("uri", httpMethod + ": " + finalBaseUri + finalMethodUri);
            put("fullMethod", fullMethod);
        }};
    }

    private static ConfigureWrapper buildConfigureWrapper(String ws, String service, String dbFile) {
        ConfigureWrapper configureWrapper = new ConfigureWrapper(false);
        configureWrapper.setMainConfig(ConfigDbKeyEnum.CDKE_DB_USE_H2, "true");
        configureWrapper.setMainConfig(ConfigDbKeyEnum.CDKE_DB_H2_FILE_PATH, dbFile);
        configureWrapper.setMainConfig(ConfigDbKeyEnum.CDKE_DB_DRIVER_NAME, "org.h2.Driver");
        configureWrapper.setMainConfig(ConfigDbKeyEnum.CDKE_DB_USERNAME, "sa");
        configureWrapper.setMainConfig(ConfigDbKeyEnum.CDKE_DB_PASSWORD, "");

        configureWrapper.setMainConfig(ConfigKeyEnum.CKE_APP_NAME, service.replace("-", "_"));
        configureWrapper.setMainConfig(ConfigKeyEnum.CKE_CALL_GRAPH_OUTPUT_DETAIL, "1");
        configureWrapper.setMainConfig(ConfigKeyEnum.CKE_OUTPUT_ROOT_PATH, ws);

        configureWrapper.setOtherConfigSet(OtherConfigFileUseSetEnum.OCFUSE_ALLOWED_CLASS_PREFIX, "com." + FileConst.BASE_PACKAGE_NAME);

        return configureWrapper;
    }
}
