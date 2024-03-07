package com.test.diff.services.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpUtil;
import com.test.diff.services.base.controller.result.BaseResult;
import com.test.diff.services.consts.FileConst;
import com.test.diff.services.enums.StatusCode;
import com.test.diff.services.params.CallGraphParams;
import com.test.diff.services.service.CallGraphService;
import com.test.diff.services.utils.CallGraphUtils;
import com.test.diff.services.utils.JarUtil;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CallGraphServiceImpl implements CallGraphService {

    private Map<String, Object> locks = new HashMap<>();

    private Map<String, Boolean> runningJob = new HashMap<>();

    @Override
    public void refreshDB(CallGraphParams params) {
        String service = params.getService().toLowerCase();
        Object lock = locks.computeIfAbsent(service, k -> new Object());
        synchronized (lock) {
            runningJob.put(service, true);
            doRefreshDB(params);
            runningJob.remove(service);
        }
    }

    private void doRefreshDB(CallGraphParams params) {
        String service = params.getService().toLowerCase();
        String dirPath = FileConst.CALL_ROOT_PATH + File.separator + params.getGroup() + File.separator + params.getEnv() + File.separator + service;
        FileUtil.del(dirPath);
        FileUtil.mkdir(dirPath);
        String dbFile = getDbFile(params.getGroup(), params.getEnv(), service); //dirPath + File.separator + service + "_h2db";
        String instanceIp = params.getHost();
        String url = "http://" + instanceIp + ":6400/im-svc";
        List<String> jars = Arrays.stream(HttpUtil.get(url).split("\n")).filter(s -> s.endsWith(".jar") && !s.contains("jmx_prometheus_javaagent") && !s.startsWith("jacocoagent")).collect(Collectors.toList());
        for (String jar : jars) {
            String jarFile = dirPath + File.separator + jar;
            String jarDir = jarFile.substring(0, jarFile.lastIndexOf("."));
            HttpUtil.downloadFile(url + "/" + jar, jarFile);
            if (!JarUtil.hasAny(new File(jarFile), "BOOT-INF/classes/com/" + FileConst.BASE_PACKAGE_NAME + "/")) {
                continue;
            }
            List<String> jarPaths = new ArrayList<>();
            jarPaths.add(jarFile);
            JarUtil.uncompress(new File(jarFile), new File(jarDir));
            String libDir = jarDir + File.separator + "BOOT-INF" + File.separator + "lib";
            File libFile = new File(libDir);
            if (!libFile.exists() || libFile.listFiles() == null) {
                continue;
            }
            for (File libJarFile : Objects.requireNonNull(libFile.listFiles())) {
                String libJarFileName = libJarFile.getName();
                if (!libJarFileName.endsWith(".jar")) {
                    continue;
                }
                if (JarUtil.hasAny(libJarFile, "com/" + FileConst.BASE_PACKAGE_NAME)) {
                    jarPaths.add(libJarFile.getAbsolutePath());
                }
            }

            CallGraphUtils.create(dirPath, service, dbFile, jarPaths);
            break;
        }
    }

    @Override
    public BaseResult findCaller(CallGraphParams params) {
        String service = params.getService().toLowerCase();
        Object lock = locks.computeIfAbsent(service, k -> new Object());
        if (runningJob.containsKey(service)) {
            return BaseResult.error(StatusCode.OTHER_ERROR, "数据库刷新中，请稍后");
        }
        synchronized (lock) {
            return doFindCaller(params);
        }
    }

    private BaseResult doFindCaller(CallGraphParams params) {
        String service = params.getService().toLowerCase();
        String dirPath = getDBDir(params.getGroup(), params.getEnv(), service);
        String dbFile = getDbFile(params.getGroup(), params.getEnv(), service);
        if (!FileUtil.exist(dbFile + ".mv.db")) {
            doRefreshDB(params);
        }
        CallGraphUtils.caller(dirPath, service, dbFile, params.getCaller());
        return BaseResult.success("");
    }

    @Override
    public BaseResult findCallee(CallGraphParams params) {
        String service = params.getService().toLowerCase();
        Object lock = locks.computeIfAbsent(service, k -> new Object());
        if (runningJob.containsKey(service)) {
            return BaseResult.error(StatusCode.OTHER_ERROR, "数据库刷新中，请稍后");
        }
        synchronized (lock) {
            return doFindCallee(params);
        }
    }

    private BaseResult doFindCallee(CallGraphParams params) {
        String service = params.getService().toLowerCase();
        String dirPath = getDBDir(params.getGroup(), params.getEnv(), service);
        String dbFile = getDbFile(params.getGroup(), params.getEnv(), service);
        if (!FileUtil.exist(dbFile + ".mv.db")) {
            doRefreshDB(params);
        }
        return CallGraphUtils.callee(dirPath, service, dbFile, params.getCallee());
    }

    private String getDBDir(String group, String env, String service) {
        return FileConst.CALL_ROOT_PATH + File.separator + group + File.separator + env + File.separator + service;
    }

    private String getDbFile(String group, String env, String service) {
        service = service.toLowerCase();
        return FileConst.CALL_ROOT_PATH + File.separator + group + File.separator + env + File.separator + service + File.separator + service + "_h2db";
    }
}
