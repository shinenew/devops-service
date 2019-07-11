package io.choerodon.devops.domain.application.repository;

import com.github.pagehelper.PageInfo;
import io.choerodon.base.domain.PageRequest;

import io.choerodon.devops.api.vo.iam.entity.ApplicationVersionE;

import io.choerodon.devops.infra.dto.ApplicationLatestVersionDO;

import java.util.List;

/**
 * Created by Zenger on 2018/4/3.
 */
public interface ApplicationVersionRepository {
    List<ApplicationLatestVersionDO> listAppLatestVersion(Long projectId);

    ApplicationVersionE create(ApplicationVersionE applicationVersionE);

    List<ApplicationVersionE> listByAppId(Long appId, Boolean isPublish);

    PageInfo<ApplicationVersionE> listByAppIdAndParamWithPage(Long appId, Boolean isPublish, Long appVersionId, PageRequest pageRequest, String searchParam);

    List<ApplicationVersionE> listDeployedByAppId(Long projectId, Long appId);

    ApplicationVersionE query(Long appVersionId);

    List<ApplicationVersionE> listByAppIdAndEnvId(Long projectId, Long appId, Long envId);

    String queryValue(Long versionId);

    ApplicationVersionE queryByAppAndVersion(Long appId, String version);

    void updatePublishLevelByIds(List<Long> appVersionIds, Long level);

    PageInfo<ApplicationVersionE> listApplicationVersionInApp(Long projectId, Long appId, PageRequest pageRequest,
                                                              String searchParam, Boolean isProjectOwner, Long userId);

    List<ApplicationVersionE> listAllPublishedVersion(Long applicationId);

    Boolean checkAppAndVersion(Long appId, List<Long> appVersionIds);

    Long setReadme(String readme);

    String getReadme(Long readmeValueId);

    void updateVersion(ApplicationVersionE applicationVersionE);

    List<ApplicationVersionE> selectUpgradeVersions(Long appVersionId);

    void checkProIdAndVerId(Long projectId, Long appVersionId);

    ApplicationVersionE queryByCommitSha(Long appId, String ref, String sha);

    ApplicationVersionE getLatestVersion(Long appId);

    List<ApplicationVersionE> listByAppVersionIds(List<Long> appVersionIds);

    List<ApplicationVersionE> listByAppIdAndBranch(Long appId, String branch);

    String queryByPipelineId(Long pipelineId, String branch,Long appId);

    String queryValueById(Long appId);

    ApplicationVersionE queryByAppAndCode(Long appId, String appVersion);

    void updatePublishTime();

}
