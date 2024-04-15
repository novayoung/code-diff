package com.test.diff.services.utils;

import cn.hutool.core.io.FileUtil;
import com.adrninistrator.jacg.common.DC;
import com.adrninistrator.jacg.common.enums.*;
import com.adrninistrator.jacg.conf.ConfigureWrapper;
import com.adrninistrator.jacg.dboper.DbOperWrapper;
import com.adrninistrator.jacg.dboper.DbOperator;
import com.adrninistrator.jacg.dto.write_db.WriteDbData4ClassAnnotation;
import com.adrninistrator.jacg.dto.write_db.WriteDbData4MethodAnnotation;
import com.adrninistrator.jacg.dto.write_db.WriteDbData4MethodInfo;
import com.adrninistrator.jacg.runner.RunnerGenAllGraph4Callee;
import com.adrninistrator.jacg.runner.RunnerGenAllGraph4Caller;
import com.adrninistrator.jacg.runner.RunnerWriteDb;
import com.adrninistrator.jacg.util.JACGSqlUtil;
import com.test.diff.services.consts.FileConst;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
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

    public static com.test.diff.services.base.controller.result.BaseResult callee(String ws, String service, String dbFile, Set<String> methods) {
        String reportDir = ws + File.separator + "_jacg_o_ee";
        FileUtil.del(reportDir);
        RunnerGenAllGraph4Callee runnerGenAllGraph4Caller = new RunnerGenAllGraph4Callee();
        ConfigureWrapper configureWrapper = buildConfigureWrapper(ws, service, dbFile);
        DbOperWrapper dbOperWrapper = buildDbOperWrapper(ws, service, dbFile, RunnerGenAllGraph4Callee.class.getSimpleName());
        DbOperator dbOperator = dbOperWrapper.getDbOperator();
        String appName = configureWrapper.getMainConfig(ConfigKeyEnum.CKE_APP_NAME);
        configureWrapper.setOtherConfigSet(OtherConfigFileUseSetEnum.OCFUSE_METHOD_CLASS_4CALLEE, replaceMethods(dbOperator, appName, methods));
        runnerGenAllGraph4Caller.run(configureWrapper);

        try {
            Map<String, List<List<String>>> allTraces = new LinkedHashMap<>();
            Map<String, String> uriComment = new LinkedHashMap<>();
            File file = new File(reportDir);
            File methodsDir = Arrays.stream(file.listFiles()[0].listFiles()).filter(file1 -> file1.getName().equalsIgnoreCase("methods")).findFirst().orElse(null);
            for (File methodFile : methodsDir.listFiles()) {
                String methodsFileName = methodFile.getName();
                String emptyFlag = "-empty.txt";
                if (methodsFileName.endsWith(emptyFlag)) {
                    String methodFullName = methodsFileName.replace(emptyFlag, "").replace("@", ":");
                    Map<String, String> uriMap = getApiByControllerMethod(dbOperator, appName, methodFullName);
                    if (uriMap != null) {
                        String uri = uriMap.get("uri");
                        String fullMethod = uriMap.get("fullMethod");
                        String comment = uriMap.get("comment");
                        allTraces.computeIfAbsent(uri, (k) -> new LinkedList<>()).add(new LinkedList<String>() {{
                            add(fullMethod);
                        }});
                        uriComment.put(uri, comment);
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
                        if (isMapping(entry)) {
                            String fullMethod = entry.split("@")[0];
                            fullMethod = fullMethod.substring(fullMethod.indexOf("com"));
                            uriComment.put(uri, findCommentByFullMethod(dbOperator, appName, fullMethod));
                        }
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
                        if (isMapping(entry)) {
                            String fullMethod = entry.split("@")[0];
                            fullMethod = fullMethod.substring(fullMethod.indexOf("com"));
                            uriComment.put(uri, findCommentByFullMethod(dbOperator, appName, fullMethod));
                        }
                    }
                }
            }
            Map<String, Object> data = new HashMap<>();
            data.put("allTraces", allTraces);
            data.put("uriComment", uriComment);
            return com.test.diff.services.base.controller.result.BaseResult.success(data);
        } finally {
            dbOperator.closeDs();
        }

    }

    public static DbOperWrapper buildDbOperWrapper(String ws, String service, String dbFile, String name) {
        ConfigureWrapper configureWrapper = buildConfigureWrapper(ws, service, dbFile);
        return DbOperWrapper.genInstance(configureWrapper, name);
    }

    private static String findCommentByFullMethod(DbOperator dbOperator, String appName, String fullMethod) {
        String sql = "select " + "*" +
                " from " + DbTableInfoEnum.DTIE_METHOD_ANNOTATION.getTableName() +
                " where " + DC.COMMON_FULL_METHOD + " = ?"
                //" and " + DC.COMMON_ANNOTATION_ANNOTATION_NAME + " = 'io.swagger.annotations.ApiOperation'"
                ;
        sql = JACGSqlUtil.replaceAppNameInSql(sql, appName);
        List<WriteDbData4MethodAnnotation> list = dbOperator.queryList(sql, WriteDbData4MethodAnnotation.class, fullMethod);
        if (list == null || list.isEmpty()) {
            return "";
        }
        WriteDbData4MethodAnnotation writeDbData4MethodAnnotation = list.stream().filter(e -> "io.swagger.annotations.ApiOperation".equalsIgnoreCase(e.getAnnotationName()) && "value".equalsIgnoreCase(e.getAttributeName())).findFirst().orElse(null);
        String swaggerValue = writeDbData4MethodAnnotation == null ? "" : writeDbData4MethodAnnotation.getAttributeValue();
        writeDbData4MethodAnnotation = list.stream().filter(e -> "com.xxl.job.core.handler.annotation.XxlJob".equalsIgnoreCase(e.getAnnotationName()) && "value".equalsIgnoreCase(e.getAttributeName())).findFirst().orElse(null);
        String xxlJobValue = writeDbData4MethodAnnotation == null ? "" : "xxlJob(" + writeDbData4MethodAnnotation.getAttributeValue() + ")";
        writeDbData4MethodAnnotation = list.stream().filter(e -> "com.intramirror.framework.mq.kafka.annotations.FrameworkKafkaListener".equalsIgnoreCase(e.getAnnotationName()) && "topics".equalsIgnoreCase(e.getAttributeName())).findFirst().orElse(null);
        String kafkaValue = writeDbData4MethodAnnotation == null ? "" : "Kafka(" + writeDbData4MethodAnnotation.getAttributeValue() + ")";

        List<String> commentList = new LinkedList<>();
        if (!swaggerValue.equalsIgnoreCase("")) {
            commentList.add(swaggerValue);
        }
        if (!xxlJobValue.equalsIgnoreCase("")) {
            commentList.add(xxlJobValue);
        }
        if (!kafkaValue.equalsIgnoreCase("")) {
            commentList.add(kafkaValue);
        }
        return String.join(" | ", commentList);
    }

    private static Set<String> replaceMethods(DbOperator dbOperator, String appName, Set<String> methods) {
        log.info("开始替换方法: {}", methods);
        Set<String> set = new HashSet<>();
        for (String method : methods) {
            if (method.endsWith("()")) {
                set.add(method);
                continue;
            }
            String[] arr = method.split(":");
            String className = arr[0];
            String simpleClassName = className.substring(className.lastIndexOf(".") + 1);
            String methodAndParams = arr[1];
            String methodName = methodAndParams.substring(0, methodAndParams.indexOf("("));
            String methodParams = methodAndParams.substring(methodAndParams.indexOf("(") + 1, methodAndParams.indexOf(")"));

            // todo convert com.test.class.method(DTO) to com.test.class.method(com.test.dto.DTO)
            String sql = "select " + "*" +
                    " from " + DbTableInfoEnum.DTIE_METHOD_INFO.getTableName() +
                    " where " + DC.COMMON_SIMPLE_CLASS_NAME + " = '" + simpleClassName + "'" +
                    " and " + DC.MI_METHOD_NAME + " = '" + methodName + "'";
            sql = JACGSqlUtil.replaceAppNameInSql(sql, appName);
            List<WriteDbData4MethodInfo> methodInfos = dbOperator.queryList(sql, WriteDbData4MethodInfo.class);
            if (methodInfos == null || methodInfos.isEmpty()) {
                log.warn("method not found in db: {}", method);
                continue;
            }
            methodInfos = methodInfos.stream().filter(methodInfo -> {
                if (!methodInfo.getFullMethod().startsWith(className)) {
                    return false;
                }
                String objFullMethod = methodInfo.getFullMethod();
                String[] objArr = objFullMethod.split(":");
                String objClassName = objArr[0];
                String objMethodAndParams = objArr[1];
                String objParams = objMethodAndParams.substring(objMethodAndParams.indexOf("(") + 1, objMethodAndParams.indexOf(")"));
                String params1 = Arrays.stream(objParams.split(",")).map(s -> s.lastIndexOf(".") > -1 ? s.substring(s.lastIndexOf(".") + 1).trim() : s.trim()).collect(Collectors.joining(","));
                String params2 = Arrays.stream(methodParams.split(",")).map(String::trim).collect(Collectors.joining(","));
                return params1.equals(params2) && objClassName.equals(className);
            }).collect(Collectors.toList());
            if (methodInfos.isEmpty()) {
                log.warn("method not found in db: {}, result: {}", method, methodInfos);
                continue;
            }
            if (methodInfos.size() > 1) {
                log.warn("method find more! {}, result: {}", method, methodInfos);
                continue;
            }
            set.add(methodInfos.get(0).getFullMethod());
        }
        log.info("结束替换方法: {}", set);
        return set;
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

    //todo
    private static Map<String, String> getApiByControllerMethod(DbOperator dbOperator, String appName, String methodFullName) {
        String baseUriSql = "select " + "*" +
                " from " + DbTableInfoEnum.DTIE_CLASS_ANNOTATION.getTableName() +
                " where " + DC.COMMON_SIMPLE_CLASS_NAME + " = ?" +
                " and " + DC.COMMON_ANNOTATION_ANNOTATION_NAME + " = ?";
        baseUriSql = JACGSqlUtil.replaceAppNameInSql(baseUriSql, appName);
        WriteDbData4ClassAnnotation classAnnotation = dbOperator.queryObject(baseUriSql, WriteDbData4ClassAnnotation.class, methodFullName.split(":")[0], "org.springframework.web.bind.annotation.RequestMapping");
        String baseUri = "";
        if (classAnnotation != null) {
            baseUri = classAnnotation.getAttributeValue().replace("\\\"", "");
            baseUri = baseUri.substring(2, baseUri.length() - 2);
        }

        String methodUriSql = "select " + "*" + " from " + DbTableInfoEnum.DTIE_METHOD_ANNOTATION.getTableName() + " where " + DC.COMMON_SIMPLE_CLASS_NAME + " = ?";
        methodUriSql = JACGSqlUtil.replaceAppNameInSql(methodUriSql, appName);
        String[] arr = methodFullName.split(":");
        String simpleClassName = arr[0];
        List<WriteDbData4MethodAnnotation> methodUriObjs = dbOperator.queryList(methodUriSql, WriteDbData4MethodAnnotation.class, simpleClassName);
        if (methodUriObjs == null || methodUriObjs.isEmpty()) {
            return new HashMap<String, String>() {{
                put("uri", methodFullName);
                put("fullMethod", methodFullName);
                put("comment", "");
            }};
        }
        String fullMethodMissFullParams = methodUriObjs.get(0).getFullMethod().split(":")[0] + ":" + arr[1];


        /*
         * Rest annotation
         */
        WriteDbData4MethodAnnotation restMethodUriObj = methodUriObjs.stream().filter(m -> m.getSpringMappingAnnotation() == 1
                && m.getFullMethod().startsWith(fullMethodMissFullParams + "(")
                && isMapping(m.getAnnotationName())).findFirst().orElse(null);
        if (restMethodUriObj != null) {
            String fullMethod = restMethodUriObj.getFullMethod();
            String methodUri = restMethodUriObj.getAttributeValue();
            methodUri = methodUri.substring(2, methodUri.length() - 2);
            String[] arr1 = restMethodUriObj.getAnnotationName().split("\\.");
            String httpMethod = arr1[arr1.length - 1].replace("Mapping", "");
            String finalBaseUri = baseUri;
            String finalMethodUri = methodUri;

            WriteDbData4MethodAnnotation methodCommentObj = methodUriObjs.stream().filter(m -> m.getFullMethod().equalsIgnoreCase(fullMethod)
                    && "io.swagger.annotations.ApiOperation".equals(m.getAnnotationName())).findFirst().orElse(null);
            String comment = methodCommentObj == null ? "" : methodCommentObj.getAttributeValue();
            return new HashMap<String, String>() {{
                put("uri", httpMethod + ": " + finalBaseUri + finalMethodUri);
                put("fullMethod", fullMethodMissFullParams);
                put("comment", comment);
            }};
        }

        /*
         * XXL Job Annotation
         */
        WriteDbData4MethodAnnotation xxlJobMethodUriObj = methodUriObjs.stream().filter(m -> m.getFullMethod().startsWith(fullMethodMissFullParams + "(")
                &&  "com.xxl.job.core.handler.annotation.XxlJob".equals(m.getAnnotationName())).findFirst().orElse(null);
        if (xxlJobMethodUriObj != null) {
            return new HashMap<String, String>() {{
                put("uri", fullMethodMissFullParams);
                put("fullMethod", fullMethodMissFullParams);
                put("comment", "XXLJob(" + xxlJobMethodUriObj.getAttributeValue() + ")");
            }};
        }

        /*
         * Kafka Consumer Annotation
         */
        WriteDbData4MethodAnnotation kafkaJobMethodUriObj = methodUriObjs.stream().filter(m -> m.getFullMethod().startsWith(fullMethodMissFullParams + "(")
                &&  "com.intramirror.framework.mq.kafka.annotations.FrameworkKafkaListener".equals(m.getAnnotationName())).findFirst().orElse(null);
        if (kafkaJobMethodUriObj != null) {
            return new HashMap<String, String>() {{
                put("uri", fullMethodMissFullParams);
                put("fullMethod", fullMethodMissFullParams);
                put("comment", "Kafka(" + kafkaJobMethodUriObj.getAttributeValue() + ")");
            }};
        }

        return new HashMap<String, String>() {{
            put("uri", fullMethodMissFullParams);
            put("fullMethod", fullMethodMissFullParams);
            put("comment", "");
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
