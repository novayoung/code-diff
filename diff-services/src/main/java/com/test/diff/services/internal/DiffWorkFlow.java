package com.test.diff.services.internal;


import com.test.diff.common.domain.ClassInfo;
import com.test.diff.services.consts.FileConst;
import com.test.diff.services.consts.GitConst;
import com.test.diff.services.entity.ProjectInfo;
import com.test.diff.services.entity.RepoInfo;
import com.test.diff.services.enums.DiffTypeEnum;
import com.test.diff.services.enums.StatusCode;
import com.test.diff.services.exceptions.BizException;
import com.test.diff.services.params.ProjectDiffParams;
import com.test.diff.services.service.ProjectInfoService;
import com.test.diff.services.service.RepoInfoService;
import com.test.diff.services.utils.FileUtil;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.DiffCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * @author wl
 */
@Component
@Slf4j
public class DiffWorkFlow {

    @Resource
    private FileUtil fileUtil;

    @Resource
    private ProjectInfoService projectInfoService;

    @Resource
    private RepoInfoService repoInfoService;

    @Resource(name = "asyncExecutor")
    private Executor executor;

    private ThreadLocal<ProjectDiffParams> threadLocal = new ThreadLocal<>();

    public List<ClassInfo> diff(ProjectDiffParams params) throws GitAPIException, IOException {
        //设置全局参数
        threadLocal.set(params);
        ProjectInfo projectInfo = projectInfoService.getById(params.getId());
        //获取base repository
        BaseRepository baseRepository = initRepository(projectInfo);

        Git baseGit = null,newGit = null;
        AbstractTreeIterator baseTree, newTree;
        try {
            switch (DiffTypeEnum.getDiffTypeByCode(params.getDiffTypeCode())) {
                case BRANCH_DIFF:
                    baseGit = syncCode(baseRepository, projectInfo, params.getOldVersion(), "", true);
                    newGit = syncCode(baseRepository, projectInfo, params.getNewVersion(), "", true);
                    baseTree = baseRepository.prepareTreeParser(baseGit.getRepository(), params.getOldVersion());
                    newTree = baseRepository.prepareTreeParser(newGit.getRepository(), params.getNewVersion());
                    break;
                case COMMIT_DIFF:
                    baseGit = syncCode(baseRepository, projectInfo, params.getNewVersion(), params.getOldCommitId(), false);
                    newGit = syncCode(baseRepository, projectInfo, params.getNewVersion(), params.getNewCommitId(), false);
                    baseTree = baseRepository.prepareTreeParser(baseGit.getRepository(), params.getOldCommitId());
                    newTree = baseRepository.prepareTreeParser(newGit.getRepository(), params.getNewCommitId());
                    break;
                default:
                    throw new BizException(StatusCode.DIFF_TYPE_ERROR);
            }
            List<DiffEntry> list = getDiffCodeClasses(newGit, baseTree, newTree);
            if (DiffTypeEnum.getDiffTypeByCode(params.getDiffTypeCode()) == DiffTypeEnum.BRANCH_DIFF) {
                list = getDiffByBranch(baseRepository, projectInfo, newGit, params.getNewVersion(), params.getOldVersion());
                list = filterClass(list);
            }
            newGit.getRepository().getRefDatabase().close();
            log.info("需要比对的类个数：{}", list.size());

            Git finalBaseGit = baseGit;
            Git finalNewGit = newGit;
            List<CompletableFuture<ClassInfo>> futures = list.stream()
                    .map(diffEntry -> diffHandle(diffEntry, getFilePath(finalBaseGit, diffEntry.getOldPath()),
                            getFilePath(finalNewGit, diffEntry.getNewPath()), params.isIfFullParamName()))
                    .collect(Collectors.toList());
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<ClassInfo> classInfos = futures.stream().map(CompletableFuture::join).filter(Objects::nonNull).collect(Collectors.toList());
            log.info("计算最终差异类个数：{}", classInfos.size());

            return classInfos;
        } finally {
            if (baseGit != null) baseGit.close();
            if (newGit != null) newGit.close();
        }
    }

    private BaseRepository initRepository(ProjectInfo projectInfo){
        if(Objects.isNull(projectInfo)){
            throw new BizException(StatusCode.PROJECT_INFO_NOT_EXISTS);
        }
        RepoInfo repoInfo = repoInfoService.getById(projectInfo.getRepoId());
        if(Objects.isNull(repoInfo)){
            throw new BizException(StatusCode.REPO_INFO_NOT_EXITS);
        }
        return RepositoryFactory.create(repoInfo);
    }

    /**
     * 处理差异类的diff
     * @param diffEntry
     * @param oldFilePath
     * @param newFilePath
     * @return
     */
    private CompletableFuture<ClassInfo> diffHandle(DiffEntry diffEntry, String oldFilePath, String newFilePath, boolean ifFullParamName){
        return CompletableFuture.supplyAsync(() ->{
            ICodeComparator codeComparator = CodeComparatorFactory.createCodeComparator();
            return codeComparator.getDiffClassInfo(diffEntry, oldFilePath, newFilePath, ifFullParamName);
        }, executor);
    }

    /**
     * 获取java文件的额完整路径
     * @param git
     * @param packName
     * @return
     */
    private String getFilePath(Git git, String packName){
        return git.getRepository().getDirectory().getAbsolutePath()
                .replace(GitConst.GIT_FILE_SUFFIX, "") + packName;
    }

    /**
     * 获取差异类
     * @param newGit
     * @param baseTree
     * @param newTree
     * @return
     * @throws GitAPIException
     */
    private List<DiffEntry> getDiffCodeClasses(Git newGit,AbstractTreeIterator baseTree,
                                               AbstractTreeIterator newTree) throws GitAPIException {
        DiffCommand diff = newGit.diff();
        List<DiffEntry> list = diff.setOldTree(baseTree).setNewTree(newTree).setShowNameAndStatusOnly(true).call();
        return filterClass(list);
    }

    private static List<DiffEntry> filterClass(List<DiffEntry> list) {
        return list.stream()
                //只计算java文件
                .filter(diffEntry -> diffEntry.getNewPath().endsWith(GitConst.JAVA_FILE_SUFFIX))
//                .filter(diffEntry -> diffEntry.getNewPath().contains(GitConst.JAVA_DEFAULT_PATH))
                //过滤掉test
                .filter(diffEntry -> !diffEntry.getNewPath().contains(GitConst.TEST_DEFAULT_PATH))
                .filter(diffEntry -> DiffEntry.ChangeType.ADD.equals(diffEntry.getChangeType()) ||
                        DiffEntry.ChangeType.MODIFY.equals(diffEntry.getChangeType()) ||
                        DiffEntry.ChangeType.DELETE.equals(diffEntry.getChangeType())) //delete方法在合并不同版本exec文件时需要把相关链路的覆盖率重置
                .collect(Collectors.toList());
    }


    private Git syncCode(BaseRepository baseRepository, ProjectInfo projectInfo,
                         String branch, String commitId, boolean isBranchDiff) throws IOException {
        //这里直接生成路径
        String branch_path = fileUtil.getRepoPath(projectInfo, branch);
        if(!isBranchDiff){
            branch_path = branch_path + "_" + commitId + File.separator + "source";
        }
        return updateCode(baseRepository, branch_path,
                projectInfo.getProjectName(),projectInfo.getProjectUrl(), branch, commitId, isBranchDiff);
    }

    /**
     * 暂时不用了,这里是直接从code-diff根目录开始建项目文件夹的
     * 后期都改成了由group/env/project，这里就不适用了，没有传group和env，所以直接不用
     * 改为上游方法中直接调用fileUtil类中方法直接生成path
     * 检查项目路径是否正确
     * @param path 项目路径
     * @param projectName 项目名
     * @param branch 分支名
     * @return
     */
    @Deprecated
    private String checkProjectPath(String path, String projectName, String branch,
                                    String commitId, boolean isBranchDiff){
        if(!path.startsWith(FileConst.DIFF_ROOT_PATH)){
            path = FileConst.DIFF_ROOT_PATH;
        }
        if(!path.contains(projectName)){
            path = fileUtil.addPath(path, projectName);
        }
        //分支名中的‘/’符号使用下划线代替（目录无法使用‘/’命名）
        if(branch.contains("/")){
            branch = branch.replaceAll("/", "_");
        }
        if(!path.contains(branch)){
            if(isBranchDiff){
                path = fileUtil.addPath(path, branch);
            }else{
                path = fileUtil.addPath(path, branch+"_"+commitId);
            }
        }
        return path;
    }


    private final Map<String, Object> updateCodeLocks = new ConcurrentHashMap<>();
    private Git updateCode(BaseRepository repository, String path,
                           String projectName, String projectUrl,
                           String branch, String commitId, boolean isBranchDiff) throws IOException {
//        path = checkProjectPath(path, projectName, branch, commitId, isBranchDiff);
        Object lock = updateCodeLocks.computeIfAbsent(path, (k) -> new Object());
        synchronized (lock) {
            String gitPath = fileUtil.addPath(path, GitConst.GIT_FILE_SUFFIX);
            if(new File(path).exists()){
                if(this.threadLocal.get().isUpdateCode() && isBranchDiff){
                    repository.pull(gitPath, branch);
                }
                Git git;
                if (!new File(gitPath).exists()) {
                    //不存在就先克隆项目
                    git = repository.clone(projectUrl, path, branch);
                    //然后再回退到指定commit id 版本
                    repository.pullByCommitId(gitPath, branch, commitId);
                } else {
                    git = GitRepository.getGit(gitPath);
                }
                return git;
            }
            if(isBranchDiff){
                return repository.clone(projectUrl, path, branch);
            }else{
                //不存在就先克隆项目
                Git git = repository.clone(projectUrl, path, branch);
                //然后再回退到指定commit id 版本
                repository.pullByCommitId(gitPath, branch, commitId);
                //最后返回Git对象
                return git;
            }
        }
    }

    private List<DiffEntry> getDiffByBranch(BaseRepository baseRepository, ProjectInfo projectInfo, Git featureGit, String featureBranch, String baseBranch) {
        String branch_path = fileUtil.getRepoPath(projectInfo, featureBranch);
        FileRepositoryBuilder builder = new FileRepositoryBuilder();

        try (Repository repository = builder.setGitDir(new File(branch_path + File.separator + ".git"))
                .readEnvironment()
                .findGitDir()
                .build()) {
            if (repository.getRefDatabase().findRef(baseBranch) != null) {
                featureGit.branchDelete().setForce(true).setBranchNames(baseBranch).call();
            }
            String baseBranchRemoteRef = "refs/remotes/origin/" + baseBranch;
            featureGit.branchCreate().setName(baseBranch).setStartPoint(baseBranchRemoteRef).call();
            ObjectId baseBranchId = repository.resolve(baseBranch + "^{commit}");
            ObjectId featureBranchId = repository.resolve(featureBranch + "^{commit}");

            // Use RevWalk to parse commit and get the tree
            try (RevWalk revWalk = new RevWalk(repository)) {
                RevCommit baseCommit = revWalk.parseCommit(baseBranchId);
                RevCommit featureCommit = revWalk.parseCommit(featureBranchId);

                // Check if feature branch is contained in the base branch (no changes to show if true)
                if (revWalk.isMergedInto(featureCommit, baseCommit)) {
                    return new ArrayList<>();
                } else {
                    // If not fully merged, do the diff
                    try (DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                        df.setRepository(repository);
                        df.setDetectRenames(true);
                        return df.scan(baseCommit.getTree(), featureCommit.getTree());
                    }

//                    revWalk.markStart(featureCommit);
//                    revWalk.markStart(baseCommit);
//                    RevCommit mergeBase = revWalk.next();
//
//                    // Now we have the merge base, we can compute the diff
//                    List<DiffEntry> diffEntries;
//                    try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
//                        diffFormatter.setRepository(repository);
//                        diffFormatter.setDetectRenames(true);
//                        diffEntries = diffFormatter.scan(mergeBase.getTree(), featureCommit.getTree());
//                    }
//                    return diffEntries;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            log.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

}
