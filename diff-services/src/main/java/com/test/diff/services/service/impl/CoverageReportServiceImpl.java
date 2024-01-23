package com.test.diff.services.service.impl;

import cn.hutool.http.HttpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.test.diff.common.util.JacksonUtil;
import com.test.diff.services.base.controller.result.BaseResult;
import com.test.diff.services.consts.JacocoConst;
import com.test.diff.services.entity.CoverageApp;
import com.test.diff.services.entity.CoverageReport;
import com.test.diff.services.entity.ProjectInfo;
import com.test.diff.services.enums.DiffTypeEnum;
import com.test.diff.services.enums.ReportStatusEnum;
import com.test.diff.services.enums.ReportTypeEnum;
import com.test.diff.services.enums.StatusCode;
import com.test.diff.services.internal.DiffWorkFlow;
import com.test.diff.services.internal.jacoco.JacocoHandle;
import com.test.diff.services.mapper.CoverageReportMapper;
import com.test.diff.services.params.*;
import com.test.diff.services.service.CoverageAppService;
import com.test.diff.services.service.CoverageReportService;
import com.test.diff.services.service.ProjectInfoService;
import com.test.diff.services.utils.CommonUtil;
import com.test.diff.services.utils.FileUtil;
import com.test.diff.services.utils.JarUtil;
import com.test.diff.services.utils.WildcardMatcher;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jacoco.cli.internal.core.tools.ExecFileLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class CoverageReportServiceImpl extends ServiceImpl<CoverageReportMapper, CoverageReport>
    implements CoverageReportService{

    @Value("${report.domain}")
    private String reportDomain;

    @Resource
    private CoverageReportMapper coverageReportMapper;

    @Resource
    private ProjectInfoService projectInfoService;

    @Resource
    private CoverageAppService coverageAppService;

    @Resource
    private FileUtil fileUtil;

    @Resource
    private DiffWorkFlow diffWorkFlow;

    @Override
    public int create(int projectId, String uuid) {
        CoverageReport report = new CoverageReport();
        report.setProjectId(projectId);
        report.setUuid(uuid);
        report.setAddTime(new Date());
        report.setLastTime(new Date());
        return coverageReportMapper.insert(report);
    }

    @Override
    public CoverageReport selectUsedByProjectId(long projectId) {
        LambdaQueryWrapper<CoverageReport> query = new LambdaQueryWrapper<>();
        query.eq(CoverageReport::getProjectId, projectId);
        query.eq(CoverageReport::getIsUsed, true);
        query.eq(CoverageReport::getIsDelete, false);
        CoverageReport report;
        report = coverageReportMapper.selectOne(query);
        return report;
    }

    @Override
    public List<CoverageReport> selectListByProjectId(long projectId, boolean isDesc) {
        LambdaQueryWrapper<CoverageReport> query = new LambdaQueryWrapper<>();
        query.eq(CoverageReport::getProjectId, projectId);
        query.eq(CoverageReport::getIsDelete, false);
        if(isDesc){
            query.orderByDesc(CoverageReport::getId);
        }
        return coverageReportMapper.selectList(query);
    }

    /**
     * 这个方法要重新写
     * @param params
     * @return
     */
    @Override
    public BaseResult report(ReportParams params) {
        ProjectInfo projectInfo = projectInfoService.getById(params.getProjectId());
        if(Objects.isNull(projectInfo)){
            return BaseResult.error(StatusCode.PARAMS_ERROR, "id= "+params.getProjectId()+" 的项目不存在");
        }
        //设置报告状态为：生成中
        log.info("{}项目的报告在生成中...", projectInfo.getProjectName());
        projectInfo.setReportStatus(ReportStatusEnum.REPORT_GENERATING.getCode());
        projectInfoService.updateById(projectInfo);

        CoverageReport report = selectUsedByProjectId(params.getProjectId());
        //没有使用中的记录，说明没有点开始收集，而是直接点击生成报告。所以先创建一条使用的report记录
        if(Objects.isNull(report)){
            String uuid = CommonUtil.getUUID();
            create(params.getProjectId(), uuid);
            report = selectByUUid(uuid);
            report.setIsUsed(true);
            saveOrUpdate(report);
        }

        //最后收集合并一次数据
        projectInfoService.pullExecData(projectInfo);

        //生成报告
        String uuidDir = fileUtil.getRepoPath(projectInfo, report.getUuid());
        String execFilePath = fileUtil.addPath(uuidDir, JacocoConst.DEFAULT_EXEC_FILE_NAME);
        ExecFileLoader loader = JacocoHandle.getLoader(execFilePath);
        String classBranch = JacocoHandle.getBranchName(loader);
        String commitId = JacocoHandle.getCommitId(loader);

        String sourceBranch = params.getNewVersion();
        if (StringUtils.isBlank(sourceBranch)) {
            sourceBranch = classBranch;
        }
        log.info("获取分支：{}，commit id:{}", sourceBranch, commitId);

        try {
            String diffResult = null;
            if(params.getReportType() == ReportTypeEnum.INCREMENT.getCode()){
                ProjectDiffParams diffParams = new ProjectDiffParams();
                diffParams.setId(params.getProjectId());
                diffParams.setDiffTypeCode(params.getDiffType());
                diffParams.setUpdateCode(true);
                //分支diff
                if(params.getDiffType() == DiffTypeEnum.BRANCH_DIFF.getCode()){
                    log.info("开始生成分支diff的增量报告...");
                    diffParams.setOldVersion(params.getOldVersion());
                    diffParams.setNewVersion(sourceBranch);
                    report.setNewBranch(sourceBranch);
                    report.setOldBranch(params.getOldVersion());
                    report.setReportType(ReportTypeEnum.INCREMENT.getCode());
                    report.setDiffType(DiffTypeEnum.BRANCH_DIFF.getCode());
                }
                //commit diff；暂时不考虑diff类型不存在的情况
                else{
                    log.info("开始生成commitId diff的增量报告...");
                    diffParams.setOldVersion(sourceBranch);
                    diffParams.setNewVersion(sourceBranch);
                    diffParams.setOldCommitId(params.getOldVersion());
                    diffParams.setNewCommitId(commitId);
                    //----------更新report
                    report.setNewBranch(commitId);
                    report.setOldBranch(params.getOldVersion());
                    report.setReportType(ReportTypeEnum.INCREMENT.getCode());
                    report.setDiffType(DiffTypeEnum.COMMIT_DIFF.getCode());
                }
                diffResult = JacksonUtil.serialize(diffWorkFlow.diff(diffParams));
            }
            else{
                report.setNewBranch(sourceBranch);
                report.setReportType(ReportTypeEnum.FULL.getCode());
            }
            //获取匹配规则
            List<String> filters = getAppFilterRules(projectInfo.getId());
            String filterRules = JacksonUtil.serialize(filters);

            String sourceBranchDir = fileUtil.addPath(fileUtil.getRepoPath(projectInfo, sourceBranch));
            String classBranchDir = fileUtil.addPath(fileUtil.getRepoPath(projectInfo, classBranch+"_"+commitId));

            downloadClasses(projectInfo.getId(), classBranchDir);

            List<String> sourcePaths = fileUtil.getAllSourcePathsByProject(sourceBranchDir);
            List<String> classFilePaths = fileUtil.getAllClassFilePathsByProject(classBranchDir);

            JacocoHandle.report(execFilePath, classFilePaths, sourcePaths, uuidDir, diffResult, filterRules);
            log.info("报告生成完成！");
            projectInfo.setReportStatus(ReportStatusEnum.REPORT_SUCCESS.getCode());
            projectInfoService.updateById(projectInfo);
        } catch (Exception e) {
            e.printStackTrace();
            log.error("{}项目报告生成失败", projectInfo.getProjectName(), e);
            projectInfo.setReportStatus(ReportStatusEnum.REPORT_FAILED.getCode());
            projectInfoService.updateById(projectInfo);
            return BaseResult.error(StatusCode.OTHER_ERROR, "报告生成失败");
        }
        report.setLastTime(new Date());
        report.setReportUri(uuidDir);
        coverageReportMapper.updateById(report);
        log.info("{}项目报告生成成功", projectInfo.getProjectName());
        return BaseResult.success("操作成功");
    }

    private CoverageReport selectByUUid(String uuid) {
        LambdaQueryWrapper<CoverageReport> query = new LambdaQueryWrapper<>();
        query.eq(CoverageReport::getUuid, uuid);
        return coverageReportMapper.selectOne(query);

    }

    public void downloadClasses(Long projectId, String classBranchDir) {
        List<CoverageApp> coverageApps = coverageAppService.getListByProjectId(projectId);
        for(CoverageApp coverageApp : coverageApps) {
            String instanceIp = coverageApp.getHost();
            downloadAndExtract(instanceIp, classBranchDir);
        }
    }

    private static void downloadAndExtract(String instanceIp, String classBranchDir) {
        String url = "http://" + instanceIp + ":6400/im-svc";
        List<String> jars = Arrays.stream(HttpUtil.get(url).split("\n")).filter(s -> s.endsWith(".jar") && !s.contains("jmx_prometheus_javaagent")).collect(Collectors.toList());
        for (String jar : jars) {
            String jarFile = classBranchDir + File.separator + "class-" + jar;
            String jarDir = jarFile.substring(0, jarFile.lastIndexOf("."));

            if (new File(jarDir).exists()) {
                continue;
            }
            cn.hutool.core.io.FileUtil.del(jarFile);
            HttpUtil.downloadFile(url + "/" + jar, jarFile);
            cn.hutool.core.io.FileUtil.del(new File(jarDir));
            JarUtil.uncompress(new File(jarFile), new File(jarDir));
            cn.hutool.core.io.FileUtil.del(jarFile);
            String libDir = jarDir + File.separator + "BOOT-INF" + File.separator + "lib";
            File libFile = new File(libDir);
            if (libFile.exists()) {
                String libClassDir = jarDir + File.separator  + "libclass";
                cn.hutool.core.io.FileUtil.mkdir(libClassDir);
                if (libFile.listFiles() == null) {
                    return;
                }
                for (File libJarFile : Objects.requireNonNull(libFile.listFiles())) {
                    String libJarFileName = libJarFile.getName();
                    if (!libJarFileName.endsWith(".jar")) {
                        continue;
                    }
                    String libJarClassDir = libClassDir + File.separator + libJarFileName.substring(0, libJarFileName.lastIndexOf("."));
                    cn.hutool.core.io.FileUtil.mkdir(libJarClassDir);
                    JarUtil.uncompress(libJarFile, new File(libJarClassDir + File.separator + "classes"));
                }
                cn.hutool.core.io.FileUtil.del(libDir);
            }
        }
    }

    @Override
    public BaseResult getReportURI(long projectId) {
        ProjectInfo projectInfo = projectInfoService.getById(projectId);
        if(Objects.isNull(projectInfo)){
            return BaseResult.error(StatusCode.PARAMS_ERROR, "id= "+projectId+" 的项目不存在");
        }
        CoverageReport report = selectUsedByProjectId(projectId);
        if(Objects.isNull(report)){
            List<CoverageReport> historyReports = selectListByProjectId(projectId, true);
            if(CollectionUtils.isEmpty(historyReports)){
                return BaseResult.error(StatusCode.REPORT_NOT_EXISTS);
            }
            CoverageReport lastReport = historyReports.get(historyReports.size()-1);
            if(StringUtils.isEmpty(lastReport.getReportUri())) {
                return BaseResult.error(StatusCode.REPORT_NOT_EXISTS, "最新报告记录未生成或生成失败");
            }
            return BaseResult.success(reportDomain + lastReport.getReportUri() + "/index.html");
        }
        if(StringUtils.isEmpty(report.getReportUri())){
            return BaseResult.error(StatusCode.REPORT_NOT_EXISTS, "请先点击生成报告再查看");
        }
        //返回路径不考虑是windows路径
        return BaseResult.success(reportDomain + report.getReportUri() + "/index.html");
    }

    private final Map<String, Long> jarTimestampMap = new HashMap<>();

    @Override
    public BaseResult reportIm(ReportImParams params) {
        /*
         * 如果数据库不存在则创建记录
         */
        Long projectId;
        Page<ProjectInfo> pages =  projectInfoService.selectListByParams(ListProjectParams.builder().env(params.getEnv()).projectGroup(params.getGroup()).projectName(params.getService()).page(1).size(10).build());
        if (pages.getRecords().isEmpty()) {
            ProjectInfo projectInfo = new ProjectInfo();
            projectInfo.setAddTime(new Date());
            projectInfo.setCollectStatus(0);
            projectInfo.setEnv(params.getEnv());
            projectInfo.setIsDelete(false);
            projectInfo.setIsDisable(false);
            projectInfo.setLastTime(new Date());
            projectInfo.setProjectGroup(params.getGroup());
            projectInfo.setProjectName(params.getService());
            projectInfo.setProjectUrl(params.getGitUrl());
            projectInfo.setRepoId(1);
            projectInfo.setReportStatus(0);

            projectId = projectInfoService.create(projectInfo);

            for (ReportImAppParam reportImAppParam : params.getApps()) {
                CoverageApp coverageApp = new CoverageApp();
                coverageApp.setAddTime(new Date());
                coverageApp.setAppName(reportImAppParam.getName());
                coverageApp.setExcludes(reportImAppParam.getExcludes());
                coverageApp.setHost(reportImAppParam.getHost());
                coverageApp.setIncludes(reportImAppParam.getIncludes());
                coverageApp.setIsDelete(false);
                coverageApp.setStatus(true);
                coverageApp.setIsDisable(false);
                coverageApp.setLastTime(new Date());
                coverageApp.setPort(reportImAppParam.getPort());
                coverageApp.setProjectId(projectId.intValue());
                coverageAppService.save(coverageApp);
            }
        } else {
            ProjectInfo projectInfo = pages.getRecords().get(0);
            projectInfo.setCollectStatus(0);
            projectInfo.setEnv(params.getEnv());
            projectInfo.setIsDelete(false);
            projectInfo.setIsDisable(false);
            projectInfo.setLastTime(new Date());
            projectInfo.setProjectGroup(params.getGroup());
            projectInfo.setProjectName(params.getService());
            projectInfo.setProjectUrl(params.getGitUrl());
            projectInfo.setRepoId(1);
            projectInfo.setReportStatus(0);
            projectId = projectInfo.getId();
            projectInfoService.saveOrUpdate(projectInfo);
            List<CoverageApp> coverageApps = coverageAppService.getListByProjectId(projectInfo.getId());
            for (int i = 0; i < coverageApps.size(); i++) {
                CoverageApp coverageApp = coverageApps.get(i);
                ReportImAppParam appParam = params.getApps().get(i);
                coverageApp.setHost(appParam.getHost());
                coverageApp.setPort(appParam.getPort());
                coverageApp.setIncludes(appParam.getIncludes());
                coverageApp.setExcludes(appParam.getExcludes());
                coverageApp.setLastTime(new Date());
                coverageAppService.saveOrUpdate(coverageApp);
            }
        }

        /*
         * 调用 api 生成报告
         */
        ReportParams reportParams = new ReportParams();
        reportParams.setProjectId(projectId.intValue());
        reportParams.setDiffType(DiffTypeEnum.BRANCH_DIFF.getCode());
        reportParams.setNewVersion(params.getFeatureBranch());
        reportParams.setOldVersion(params.getBaseBranch());
        reportParams.setReportType(params.getFull() == null || params.getFull() != 1 ? 1 : 0);

        BaseResult baseResult = report(reportParams);
        if (baseResult.getCode() == StatusCode.SUCCESS.getCode()) {
            //todo clear history ?
        }
        return baseResult;
    }

    /**
     * 获取指定工程：收集中的应用匹配类表达式
     * @param projectId
     * @return 返回list格式：[app1-includes, app1-excludes, app2-includes, app2-excludes, ...]
     */
    private List<String> getAppFilterRules(long projectId){
        List<CoverageApp> apps = coverageAppService.getListByProjectId(projectId).stream()
                .filter(app -> app.getStatus())
                .collect(Collectors.toList());
        List<String> list = new ArrayList();
        for(CoverageApp app: apps){
            list.add(app.getIncludes());
            list.add(app.getExcludes()==null? "" : app.getExcludes());
        }
        return list;
    }


    @Deprecated //(files是文件路径，匹配规则是针对全限定类名的，所以没法用) = 白写
    private List<String> filter(List<String> files, ProjectInfo projectInfo){
        //找出对应项目，收集中的所有应用
        List<CoverageApp> apps = coverageAppService.getListByProjectId(projectInfo.getId()).stream()
                .filter(app -> app.getStatus()).collect(Collectors.toList());
        List<String> list = new ArrayList();
        //过滤无效文件
        for(String file: files){
            //匹配任一一个应用中的includes && excludes 表达式，即为有效文件
            boolean flag = apps.stream().anyMatch(app -> {
                WildcardMatcher includes = new WildcardMatcher(app.getIncludes());
                WildcardMatcher excludes = new WildcardMatcher(app.getExcludes());
                return includes.matches(file) &&
                        !excludes.matches(file);
            });
            if(flag){
                list.add(file);
            }
        }
        return list;
    }
}




