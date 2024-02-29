package com.test.diff.services.controller;

import cn.hutool.core.io.FileUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.test.diff.services.base.controller.result.BaseResult;
import com.test.diff.services.consts.FileConst;
import com.test.diff.services.convert.ModelConvert;
import com.test.diff.services.entity.CoverageApp;
import com.test.diff.services.entity.CoverageReport;
import com.test.diff.services.entity.ProjectInfo;
import com.test.diff.services.enums.*;
import com.test.diff.services.params.*;
import com.test.diff.services.service.CallGraphService;
import com.test.diff.services.service.CoverageAppService;
import com.test.diff.services.service.CoverageReportService;
import com.test.diff.services.service.ProjectInfoService;
import com.test.diff.services.utils.CommonUtil;
import com.test.diff.services.vo.ProjectVo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.io.File;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @author wl
 */
@RestController
@RequestMapping("api/coverage")
@Slf4j
public class WebSiteController {

    @Resource
    private ProjectInfoService projectInfoService;

    @Resource
    private CoverageReportService coverageReportService;

    @Resource
    private CoverageAppService coverageAppService;

    @Resource
    private ModelConvert<ProjectInfo, ProjectVo> projectVoModelConvert;

    @Resource
    private CallGraphService callGraphService;

    @GetMapping("/list")
    public BaseResult getList(@RequestParam("currentPage") int page,
                              @RequestParam("pageSize") int size,
                              @RequestParam(value = "projectId", required = false) int projectId){
        ListProjectParams params = ListProjectParams.builder().page(page).size(size).projectId(projectId).build();
        Page<ProjectInfo> pages = projectInfoService.selectListByParams(params);
        Page<ProjectVo> projectVoPage = new Page<>();
        projectVoPage.setRecords(pages.getRecords().stream().map(projectInfo -> projectVoModelConvert.convert(projectInfo)).collect(Collectors.toList()));
        projectVoPage.setCurrent(pages.getCurrent());
        projectVoPage.setSize(pages.getSize());
        projectVoPage.setTotal(pages.getTotal());
        return BaseResult.success(projectVoPage);
    }

    @PostMapping("/save/project")
    public BaseResult saveProject(@RequestBody ProjectVo projectVo){
        //暂时没有考虑env+projectName的唯一性问题；包括app的也是如此
        ProjectInfo projectInfo = projectVoModelConvert.reconvert(projectVo);
        int projectId = projectInfo.getId().intValue();
        if(projectId == 0){
            projectId = (projectInfoService.create(projectInfo)).intValue();
        }else{
            projectInfo.setLastTime(new Date());
            projectInfoService.updateById(projectInfo);
            projectId = projectInfo.getId().intValue();
        }
        List<CoverageApp> coverageApps = projectVo.getApps();
        for(CoverageApp app: coverageApps){
            if(Objects.isNull(app.getId()) || app.getId() == 0){
                coverageAppService.create(projectId, app);
                continue;
            }
            app.setLastTime(new Date());
            coverageAppService.updateById(app);
        }
        return BaseResult.success(null, "保存成功");
    }

    @PostMapping(value = "collect/status", produces = "application/json;charset=UTF-8")
    public BaseResult updateCollectStatus(@Validated @RequestBody CollectParams params){
        if(Objects.isNull(params.getApps())
                || params.getApps().size() == 0){
            return BaseResult.error(StatusCode.PARAMS_ERROR, "需要收集的应用列表不能为空");
        }
        int projectId = params.getProjectId();
        ProjectInfo projectInfo = projectInfoService.getById(projectId);
        if(Objects.isNull(projectInfo)){
            return BaseResult.error(StatusCode.PROJECT_INFO_NOT_EXISTS);
        }
        switch (CollectStatusEnum.getObjByCode(params.getStatus())){
            case COLLECTING:
                return startOrContinueCollect(params.getApps(), projectId, projectInfo);
            case SUSPEND_COLLECT:
                return suspendCollect(projectInfo);
            case COLLECT_END:
                return stopCollect(params.getApps(), projectId, projectInfo);
            default:
                log.info("未知的收集操作状态： {}", projectId);
                return BaseResult.error(StatusCode.UNKNOWN_COLLECT_STATUS);
        }
    }

    @PostMapping(value = "/generate/report", produces = "application/json;charset=UTF-8")
    public BaseResult generateReport(@Validated  @RequestBody ReportParams params){
        if(params.getReportType() == ReportTypeEnum.INCREMENT.getCode()){
            if(params.getDiffType() == DiffTypeEnum.UNKNOWN.getCode()){
                return BaseResult.error(StatusCode.PARAMS_ERROR, "diff类型选择错误");
            }
            if(StringUtils.isBlank(params.getOldVersion())){
                return BaseResult.error(StatusCode.PARAMS_ERROR, "基线分支不允许为空");
            }
        }
        return coverageReportService.report(params);
    }

    @GetMapping( "/getReportUri")
    public BaseResult getReportUrl(@RequestParam("id") int projectId){
        if(projectId == 0){
            return BaseResult.error(StatusCode.PARAMS_ERROR, "项目id不允许为空");
        }
        return coverageReportService.getReportURI(projectId);
    }

    @PostMapping(value = "/generate/reportIm", produces = "application/json;charset=UTF-8")
    public BaseResult generateReportIm(@Validated  @RequestBody ReportImParams params){
        return coverageReportService.reportIm(params);
    }

    @PostMapping(value = "/generate/project", produces = "application/json;charset=UTF-8")
    public BaseResult generateProject(@Validated  @RequestBody ReportImParams params){
        Long projectId = coverageReportService.computeIfAbsentProject(params);
        return BaseResult.success(projectId);
    }

    @PostMapping(value = "/generate/clearWorkspace", produces = "application/json;charset=UTF-8")
    public BaseResult clearWorkspace(@Validated  @RequestBody ReportImParams params){
        String group = params.getGroup();
        String service = params.getService();
        String env = params.getEnv();
        log.info("group {}, service {}, env {}", group, service, env);
        if (StringUtils.isBlank(group) || StringUtils.isBlank(service) || StringUtils.isBlank(env)) {
            throw new RuntimeException("清空参数不能为空!");
        }
        String dirPath = FileConst.DIFF_ROOT_PATH + File.separator + group + File.separator + env + File.separator + service;
        log.info("清空目录 {}", dirPath);
        FileUtil.del(new File(dirPath));

        String dirPath1 = FileConst.CALL_ROOT_PATH + File.separator + group + File.separator + env + File.separator + service;
        log.info("清空目录 {}", dirPath1);
        FileUtil.del(new File(dirPath1));
        return BaseResult.success(true);
    }

    @PostMapping(value = "/generate/createCallGraphDB", produces = "application/json;charset=UTF-8")
    public BaseResult createCallGraphDB(@Validated  @RequestBody CallGraphParams params){
        String group = params.getGroup();
        String service = params.getService();
        String env = params.getEnv();
        log.info("group {}, service {}, env {}", group, service, env);
        if (StringUtils.isBlank(group) || StringUtils.isBlank(service) || StringUtils.isBlank(env)) {
            throw new RuntimeException("清空参数不能为空!");
        }
        callGraphService.refreshDB(params);
        return BaseResult.success(true);
    }

    @PostMapping(value = "/generate/findCallerGraph", produces = "application/json;charset=UTF-8")
    public BaseResult findCallerGraph(@Validated  @RequestBody CallGraphParams params){
        String group = params.getGroup();
        String service = params.getService();
        String env = params.getEnv();
        log.info("group {}, service {}, env {}", group, service, env);
        if (StringUtils.isBlank(group) || StringUtils.isBlank(service) || StringUtils.isBlank(env)) {
            throw new RuntimeException("参数不能为空!");
        }
        return callGraphService.findCaller(params);
    }

    @PostMapping(value = "/generate/findCalleeGraph", produces = "application/json;charset=UTF-8")
    public BaseResult findCalleeGraph(@Validated  @RequestBody CallGraphParams params){
        String group = params.getGroup();
        String service = params.getService();
        String env = params.getEnv();
        log.info("group {}, service {}, env {}", group, service, env);
        if (StringUtils.isBlank(group) || StringUtils.isBlank(service) || StringUtils.isBlank(env)) {
            throw new RuntimeException("参数不能为空!");
        }
        return callGraphService.findCallee(params);
    }

    /**
     * 开始或继续收集
     * @param apps 应用id列表
     * @param projectId
     * @param projectInfo
     * @return
     */
    private BaseResult startOrContinueCollect(List<CoverageApp> apps, int projectId, ProjectInfo projectInfo){
        /**
         * 防止同一个工程并发请求，创建了多个report记录
         */
        synchronized (this){
            CoverageReport report = coverageReportService.selectUsedByProjectId(projectId);
            if(Objects.isNull(report)){
                String uuid = CommonUtil.getUUID();
                coverageReportService.create(projectId, uuid);
            }
        }
        for(CoverageApp app : apps){
            if(app.getId() == 0){
                return BaseResult.error(StatusCode.PARAMS_ERROR, "id："+app.getId()+ "的应用不存在，请检查参数");
            }
            //把选中的应用设置为收集状态
            app.setStatus(true);
            coverageAppService.updateById(app);
        }
        projectInfo.setCollectStatus(CollectStatusEnum.COLLECTING.getCode());
        projectInfoService.updateById(projectInfo);
        return BaseResult.success(null, "操作成功");
    }

    /**
     * 暂停收集
     * @param projectInfo
     * @return
     */
    private BaseResult suspendCollect(ProjectInfo projectInfo){
        projectInfo.setCollectStatus(CollectStatusEnum.SUSPEND_COLLECT.getCode());
        projectInfoService.updateById(projectInfo);
        return BaseResult.success(null, "操作成功");
    }

    /**
     * 结束收集
     * @param apps 应用id列表
     * @param projectId
     * @param projectInfo
     * @return
     */
    private BaseResult stopCollect(List<CoverageApp> apps, int projectId, ProjectInfo projectInfo){
        CoverageReport report = coverageReportService.selectUsedByProjectId(projectId);
        if(!Objects.isNull(report)){
            //报告记录设置为不使用
            report.setIsUsed(false);
            coverageReportService.updateById(report);
        }
//        for(Integer appId : apps){
//            CoverageApp app = coverageAppService.getById(appId);
//            if(Objects.isNull(app)){
//                return BaseResult.error(StatusCode.PARAMS_ERROR, "id："+appId+ "的应用不存在，请检查参数");
//            }
//            //把选中的应用设置为未收集状态
//            app.setStatus(false);
//            coverageAppService.updateById(app);
//        }
        //把这个工程的所有应用状态都置为未收集
        List<CoverageApp> list = coverageAppService.getListByProjectId(projectId);
        list.stream().forEach(coverageApp -> coverageApp.setStatus(false));
        coverageAppService.updateBatchById(list);
        //更新项目收集状态为：初始状态
        projectInfo.setCollectStatus(CollectStatusEnum.INIT.getCode());
        //更新项目报告状态为：初始状态
        projectInfo.setReportStatus(ReportStatusEnum.INIT.getCode());
        projectInfoService.updateById(projectInfo);
        return BaseResult.success(null, "结束收集操作成功");
    }
}
