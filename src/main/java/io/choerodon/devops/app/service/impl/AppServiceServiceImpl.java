package io.choerodon.devops.app.service.impl;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.google.gson.Gson;
import io.kubernetes.client.JSON;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;

import io.choerodon.asgard.saga.annotation.Saga;
import io.choerodon.asgard.saga.producer.StartSagaBuilder;
import io.choerodon.asgard.saga.producer.TransactionalProducer;
import io.choerodon.base.domain.PageRequest;
import io.choerodon.core.exception.CommonException;
import io.choerodon.core.iam.ResourceLevel;
import io.choerodon.devops.api.validator.ApplicationValidator;
import io.choerodon.devops.api.vo.*;
import io.choerodon.devops.api.vo.sonar.*;
import io.choerodon.devops.app.eventhandler.DevopsSagaHandler;
import io.choerodon.devops.app.eventhandler.constants.SagaTopicCodeConstants;
import io.choerodon.devops.app.eventhandler.payload.AppServiceImportPayload;
import io.choerodon.devops.app.eventhandler.payload.DevOpsAppImportServicePayload;
import io.choerodon.devops.app.eventhandler.payload.DevOpsAppServicePayload;
import io.choerodon.devops.app.eventhandler.payload.DevOpsUserPayload;
import io.choerodon.devops.app.service.*;
import io.choerodon.devops.infra.config.ConfigurationProperties;
import io.choerodon.devops.infra.config.HarborConfigurationProperties;
import io.choerodon.devops.infra.dto.*;
import io.choerodon.devops.infra.dto.gitlab.*;
import io.choerodon.devops.infra.dto.harbor.ProjectDetail;
import io.choerodon.devops.infra.dto.harbor.User;
import io.choerodon.devops.infra.dto.iam.ApplicationDTO;
import io.choerodon.devops.infra.dto.iam.IamUserDTO;
import io.choerodon.devops.infra.dto.iam.OrganizationDTO;
import io.choerodon.devops.infra.dto.iam.ProjectDTO;
import io.choerodon.devops.infra.enums.*;
import io.choerodon.devops.infra.feign.ChartClient;
import io.choerodon.devops.infra.feign.HarborClient;
import io.choerodon.devops.infra.feign.SonarClient;
import io.choerodon.devops.infra.feign.operator.BaseServiceClientOperator;
import io.choerodon.devops.infra.feign.operator.GitlabServiceClientOperator;
import io.choerodon.devops.infra.handler.RetrofitHandler;
import io.choerodon.devops.infra.mapper.*;
import io.choerodon.devops.infra.util.*;


/**
 * Created by younger on 2018/3/28.
 */
@Service
@EnableConfigurationProperties(HarborConfigurationProperties.class)
public class AppServiceServiceImpl implements AppServiceService {
    public static final String SEVERITIES = "severities";
    public static final Logger LOGGER = LoggerFactory.getLogger(AppServiceServiceImpl.class);
    public static final String NODELETED = "nodeleted";
    private static final String HARBOR = "harbor";
    private static final String CHART = "chart";
    private static final String GIT = ".git";
    private static final String SONAR_KEY = "%s-%s:%s";
    private static final Pattern REPOSITORY_URL_PATTERN = Pattern.compile("^http.*\\.git");
    private static final String SITE_APP_GROUP_NAME_FORMAT = "choerodon-market-%s";
    private static final String DOCKER_FILE_NAME = "Dockerfile";
    private static final String ISSUE = "issue";
    private static final String COVERAGE = "coverage";
    private static final String SONAR = "sonar";
    private static final String MEMBER = "member";
    private static final String OWNER = "owner";
    private static final String NORMAL = "normal";
    private static final String PROJECT_OWNER = "role/project/default/project-owner";
    private static final String PROJECT_MEMBER = "role/project/default/project-member";
    private static final String APP_SERVICE = "appService";
    private static final String ERROR_USER_NOT_OWNER = "error.user.not.owner";
    private static final String METRICS = "metrics";
    private static final String SONAR_NAME = "sonar_default";
    private static final String NORMAL_SERVICE = "normal_service";
    private static final String SHARE_SERVICE = "share_service";
    private static final String MARKET_SERVICE = "market_service";

    private static final String APPLICATION = "application";
    private static final String TEST = "test-application";
    private static final String DUPLICATE = "duplicate";
    @Autowired
    DevopsSagaHandler devopsSagaHandler;
    private Gson gson = new Gson();
    private JSON json = new JSON();
    @Value("${services.gitlab.url}")
    private String gitlabUrl;
    @Value("${spring.application.name}")
    private String applicationName;
    @Value("${services.sonarqube.url:}")
    private String sonarqubeUrl;
    @Value("${services.gateway.url}")
    private String gatewayUrl;
    @Value("${services.sonarqube.username:}")
    private String userName;
    @Value("${services.sonarqube.password:}")
    private String password;
    @Autowired
    private GitUtil gitUtil;
    @Autowired
    private AppServiceMapper appServiceMapper;
    @Autowired
    private AppServiceInstanceMapper appServiceInstanceMapper;
    @Autowired
    private AppServiceVersionMapper appServiceVersionMapper;
    @Autowired
    private UserAttrMapper userAttrMapper;
    @Autowired
    private AppServiceUserRelMapper appServiceUserRelMapper;
    @Autowired
    private TransactionalProducer producer;
    @Autowired
    private UserAttrService userAttrService;
    @Autowired
    private GitlabGroupMemberService gitlabGroupMemberService;
    @Autowired
    private DevopsProjectService devopsProjectService;
    @Autowired
    private BaseServiceClientOperator baseServiceClientOperator;
    @Autowired
    private AppServiceUserPermissionService appServiceUserPermissionService;
    @Autowired
    private GitlabServiceClientOperator gitlabServiceClientOperator;
    @Autowired
    private DevopsConfigService devopsConfigService;
    @Autowired
    private DevopsBranchService devopsBranchService;
    @Autowired
    private DevopsEnvAppServiceMapper devopsEnvAppServiceMapper;
    @Autowired
    private AppServiceVersionService appServiceVersionService;
    @Value("${services.helm.url}")
    private String helmUrl;
    @Autowired
    private AppServiceShareRuleMapper appServiceShareRuleMapper;

    @Override
    @Saga(code = SagaTopicCodeConstants.DEVOPS_CREATE_APPLICATION_SERVICE,
            description = "Devops创建应用服务", inputSchema = "{}")
    @Transactional
    public AppServiceRepVO create(Long projectId, AppServiceReqVO appServiceReqVO) {
        UserAttrVO userAttrVO = userAttrService.queryByUserId(TypeUtil.objToLong(GitUserNameUtil.getUserId()));
        ApplicationValidator.checkApplicationService(appServiceReqVO.getCode());
        ProjectDTO projectDTO = baseServiceClientOperator.queryIamProjectById(projectId);

        // 校验模板id和模板版本id是否都有值或者都为空
        boolean isTemplateNull = appServiceReqVO.getTemplateAppServiceId() == null;
        boolean isTemplateVersionNull = appServiceReqVO.getTemplateAppServiceVersionId() == null;

        if ((isTemplateNull && !isTemplateVersionNull) || (!isTemplateNull && isTemplateVersionNull)) {
            throw new CommonException("error.template.fields");
        }

        // 查询创建应用服务所在的gitlab应用组
        DevopsProjectDTO devopsProjectDTO = devopsProjectService.baseQueryByProjectId(projectId);
        MemberDTO memberDTO = gitlabGroupMemberService.queryByUserId(
                TypeUtil.objToInteger(devopsProjectDTO.getDevopsAppGroupId()),
                TypeUtil.objToInteger(userAttrVO.getGitlabUserId()));
        if (memberDTO == null || !memberDTO.getAccessLevel().equals(AccessLevel.OWNER.value)) {
            throw new CommonException(ERROR_USER_NOT_OWNER);
        }
        AppServiceDTO appServiceDTO = getApplicationServiceDTO(projectId, appServiceReqVO);
        //默认权限为项目下所有
        appServiceDTO.setIsSkipCheckPermission(true);
        appServiceDTO = baseCreate(appServiceDTO);

        //创建saga payload
        DevOpsAppServicePayload devOpsAppServicePayload = new DevOpsAppServicePayload();
        devOpsAppServicePayload.setPath(appServiceDTO.getCode());
        devOpsAppServicePayload.setOrganizationId(projectDTO.getOrganizationId());
        devOpsAppServicePayload.setUserId(TypeUtil.objToInteger(userAttrVO.getGitlabUserId()));
        devOpsAppServicePayload.setGroupId(TypeUtil.objToInteger(devopsProjectDTO.getDevopsAppGroupId()));
        devOpsAppServicePayload.setSkipCheckPermission(true);
        devOpsAppServicePayload.setAppServiceId(appServiceDTO.getId());
        devOpsAppServicePayload.setIamProjectId(projectId);
        devOpsAppServicePayload.setTemplateAppServiceId(appServiceReqVO.getTemplateAppServiceId());
        devOpsAppServicePayload.setTemplateAppServiceVersionId(appServiceReqVO.getTemplateAppServiceVersionId());

        producer.apply(
                StartSagaBuilder
                        .newBuilder()
                        .withLevel(ResourceLevel.PROJECT)
                        .withRefType("")
                        .withSagaCode(SagaTopicCodeConstants.DEVOPS_CREATE_APPLICATION_SERVICE)
                        .withPayloadAndSerialize(devOpsAppServicePayload)
                        .withRefId("")
                        .withSourceId(projectId),
                builder -> {
                });
        return ConvertUtils.convertObject(baseQueryByCode(appServiceDTO.getCode(), appServiceDTO.getProjectId()), AppServiceRepVO.class);
    }

    @Override
    public AppServiceRepVO query(Long projectId, Long appServiceId) {
        ProjectDTO projectDTO = baseServiceClientOperator.queryIamProjectById(projectId);
        OrganizationDTO organizationDTO = baseServiceClientOperator.queryOrganizationById(projectDTO.getOrganizationId());
        AppServiceDTO appServiceDTO = appServiceMapper.selectByPrimaryKey(appServiceId);
        AppServiceRepVO appServiceRepVO = dtoToRepVo(appServiceDTO);
        List<DevopsConfigVO> devopsConfigVOS = devopsConfigService.queryByResourceId(appServiceId, APP_SERVICE);
        if (!devopsConfigVOS.isEmpty()) {
            devopsConfigVOS.forEach(devopsConfigVO -> {
                if (devopsConfigVO.getType().equals(HARBOR)) {
                    appServiceRepVO.setHarbor(devopsConfigVO);
                }
                if (devopsConfigVO.getType().equals(CHART)) {
                    appServiceRepVO.setChart(devopsConfigVO);
                }
            });
        }
        //url地址拼接
        String urlSlash = gitlabUrl.endsWith("/") ? "" : "/";
        if (appServiceDTO.getGitlabProjectId() != null) {
            appServiceRepVO.setRepoUrl(gitlabUrl + urlSlash
                    + organizationDTO.getCode() + "-" + projectDTO.getCode() + "/"
                    + appServiceDTO.getCode() + ".git");
        }
        return appServiceRepVO;
    }

    @Transactional
    @Override
    public void delete(Long projectId, Long appServiceId) {
        AppServiceDTO appServiceDTO = appServiceMapper.selectByPrimaryKey(appServiceId);

        if (appServiceDTO == null) {
            return;
        }

        // 禁止删除未失败的应用
        if (!Boolean.TRUE.equals(appServiceDTO.getFailed())) {
            throw new CommonException("error.delete.nonfailed.app.service", appServiceDTO.getName());
        }

        //删除应用服务权限
        appServiceUserPermissionService.baseDeleteByAppServiceId(appServiceId);
        //删除gitlab project
        if (appServiceDTO.getGitlabProjectId() != null) {
            Integer gitlabProjectId = appServiceDTO.getGitlabProjectId();
            GitlabProjectDTO gitlabProjectDTO = gitlabServiceClientOperator.queryProjectById(gitlabProjectId);
            if (gitlabProjectDTO != null && gitlabProjectDTO.getId() != null) {
                UserAttrDTO userAttrDTO = userAttrService.baseQueryById(TypeUtil.objToLong(GitUserNameUtil.getUserId()));
                Integer gitlabUserId = TypeUtil.objToInt(userAttrDTO.getGitlabUserId());
                gitlabServiceClientOperator.deleteProjectById(gitlabProjectId, gitlabUserId);
            }
        }
        appServiceMapper.deleteByPrimaryKey(appServiceId);
    }

    @Saga(code = SagaTopicCodeConstants.DEVOPS_UPDATE_GITLAB_USERS,
            description = "Devops更新gitlab用户", inputSchema = "{}")
    @Override
    @Transactional
    public Boolean update(Long projectId, AppServiceUpdateDTO appServiceUpdateDTO) {
        AppServiceDTO appServiceDTO = ConvertUtils.convertObject(appServiceUpdateDTO, AppServiceDTO.class);
        Long appServiceId = appServiceUpdateDTO.getId();
        List<DevopsConfigVO> devopsConfigVOS = new ArrayList<>();

        DevopsConfigVO harbor = new DevopsConfigVO();
        DevopsConfigVO chart = new DevopsConfigVO();
        if (ObjectUtils.isEmpty(appServiceUpdateDTO.getHarbor())) {
            harbor.setCustom(false);
            harbor.setType(HARBOR);
            devopsConfigVOS.add(harbor);
        } else {
            devopsConfigVOS.add(appServiceUpdateDTO.getHarbor());
        }

        if (ObjectUtils.isEmpty(appServiceUpdateDTO.getChart())) {
            chart.setCustom(false);
            chart.setType(CHART);
            devopsConfigVOS.add(chart);
        } else {
            devopsConfigVOS.add(appServiceUpdateDTO.getChart());
        }


        devopsConfigService.operate(appServiceId, APP_SERVICE, devopsConfigVOS);

        if (appServiceUpdateDTO.getHarbor() != null) {
            DevopsConfigDTO harborConfig = devopsConfigService.queryRealConfig(appServiceId, APP_SERVICE, HARBOR);
            appServiceDTO.setHarborConfigId(harborConfig.getId());
        }
        if (appServiceUpdateDTO.getChart() != null) {
            DevopsConfigDTO chartConfig = devopsConfigService.queryRealConfig(appServiceId, APP_SERVICE, CHART);
            appServiceDTO.setChartConfigId(chartConfig.getId());
        }


        AppServiceDTO oldAppServiceDTO = appServiceMapper.selectByPrimaryKey(appServiceId);

        if (oldAppServiceDTO == null) {
            return false;
        }

        if (!oldAppServiceDTO.getName().equals(appServiceUpdateDTO.getName())) {
            checkName(oldAppServiceDTO.getProjectId(), appServiceDTO.getName());
        }
        baseUpdate(appServiceDTO);
        return true;
    }


    @Override
    @Transactional
    public Boolean updateActive(Long projectId, Long appServiceId, final Boolean active) {
        // 为空则默认true
        Boolean toUpdateValue = Boolean.FALSE.equals(active) ? Boolean.FALSE : Boolean.TRUE;

        AppServiceDTO appServiceDTO = appServiceMapper.selectByPrimaryKey(appServiceId);

        // 如果原先的值和更新的值相等，则不更新
        if (toUpdateValue.equals(appServiceDTO.getActive())) {
            return false;
        }

        // 如果不相等，且将停用应用服务，检查该应用服务是否可以被停用
        if (!toUpdateValue) {
            checkCanDisable(appServiceId, projectId);
            // 如果能停用，删除其和他所属项目下的环境之间的关联关系
            devopsEnvAppServiceMapper.deleteRelevanceInProject(appServiceId, projectId);
        }

        appServiceDTO.setActive(toUpdateValue);
        baseUpdate(appServiceDTO);
        return true;
    }

    /**
     * 检查当前应用服务是否还有相关的资源和共享规则
     * 不能停用则会抛出异常 {@link CommonException}
     *
     * @param appServiceId 服务id
     * @param projectId    项目id
     */
    private void checkCanDisable(Long appServiceId, Long projectId) {
        int nonDeleteInstancesCount = appServiceInstanceMapper.countNonDeletedInstances(appServiceId, projectId);
        if (nonDeleteInstancesCount > 0) {
            throw new CommonException("error.disable.application.service", appServiceId);
        }

        int shareRulesCount = appServiceShareRuleMapper.countShareRulesByAppServiceId(appServiceId);
        if (shareRulesCount > 0) {
            throw new CommonException("error.disable.application.service.due.to.share");
        }

        if (devopsEnvAppServiceMapper.countRelatedSecret(appServiceId, null, projectId) != 0
                || devopsEnvAppServiceMapper.countRelatedService(appServiceId, null, projectId) != 0
                || devopsEnvAppServiceMapper.countRelatedConfigMap(appServiceId, null, projectId) != 0) {
            throw new CommonException("error.disable.application.service.due.to.resources");
        }
    }

    @Override
    public PageInfo<AppServiceRepVO> pageByOptions(Long projectId, Boolean isActive, Boolean hasVersion,
                                                   Boolean appMarket,
                                                   String type, Boolean doPage,
                                                   PageRequest pageRequest, String params) {

        ProjectDTO projectDTO = baseServiceClientOperator.queryIamProjectById(projectId);
        PageInfo<AppServiceDTO> applicationServiceDTOS = basePageByOptions(projectId, isActive, hasVersion, appMarket, type, doPage, pageRequest, params);
        OrganizationDTO organizationDTO = baseServiceClientOperator.queryOrganizationById(projectDTO.getOrganizationId());
        String urlSlash = gitlabUrl.endsWith("/") ? "" : "/";
        initApplicationParams(projectDTO, organizationDTO, applicationServiceDTOS.getList(), urlSlash);

        return ConvertUtils.convertPage(applicationServiceDTOS, this::dtoToRepVo);
    }


    @Override
    public PageInfo<AppServiceRepVO> pageCodeRepository(Long projectId, PageRequest pageRequest, String params) {
        UserAttrDTO userAttrDTO = userAttrMapper.selectByPrimaryKey(TypeUtil.objToLong(GitUserNameUtil.getUserId()));
        ProjectDTO projectDTO = baseServiceClientOperator.queryIamProjectById(projectId);
        Boolean isProjectOwner = baseServiceClientOperator.isProjectOwner(userAttrDTO.getIamUserId(), projectDTO);
        OrganizationDTO organizationDTO = baseServiceClientOperator.queryOrganizationById(projectDTO.getOrganizationId());

        Map maps = gson.fromJson(params, Map.class);
        PageInfo<AppServiceDTO> applicationServiceDTOPageInfo = PageHelper.startPage(pageRequest.getPage(), pageRequest.getSize(), PageRequestUtil.getOrderBy(pageRequest)).doSelectPageInfo(() -> appServiceMapper.listCodeRepository(projectId,
                TypeUtil.cast(maps.get(TypeUtil.SEARCH_PARAM)),
                TypeUtil.cast(maps.get(TypeUtil.PARAMS)), isProjectOwner, userAttrDTO.getIamUserId()));
        String urlSlash = gitlabUrl.endsWith("/") ? "" : "/";

        initApplicationParams(projectDTO, organizationDTO, applicationServiceDTOPageInfo.getList(), urlSlash);

        return ConvertUtils.convertPage(applicationServiceDTOPageInfo, AppServiceRepVO.class);
    }

    @Override
    public List<AppServiceRepVO> listByActive(Long projectId) {
        Long userId = TypeUtil.objToLong(GitUserNameUtil.getUserId());
        ProjectDTO projectDTO = baseServiceClientOperator.queryIamProjectById(projectId);
        Boolean projectOwner = baseServiceClientOperator.isProjectOwner(userId, projectDTO);
        List<AppServiceDTO> applicationDTOServiceList;
        if (projectOwner) {
            applicationDTOServiceList = appServiceMapper.listByActive(projectId);
        } else {
            applicationDTOServiceList = appServiceMapper.listProjectMembersAppServiceByActive(projectId, userId);
        }

        OrganizationDTO organizationDTO = baseServiceClientOperator.queryOrganizationById(projectDTO.getOrganizationId());
        String urlSlash = gitlabUrl.endsWith("/") ? "" : "/";

        initApplicationParams(projectDTO, organizationDTO, applicationDTOServiceList, urlSlash);
        return ConvertUtils.convertList(applicationDTOServiceList, this::dtoToRepVo);
    }

    @Override
    public Integer countByActive(Long projectId) {
        Long userId = TypeUtil.objToLong(GitUserNameUtil.getUserId());
        ProjectDTO projectDTO = baseServiceClientOperator.queryIamProjectById(projectId);
        Boolean projectOwner = baseServiceClientOperator.isProjectOwner(userId, projectDTO);
        int count;
        if (projectOwner) {
            count = appServiceMapper.countByActive(projectId);
        } else {
            count = appServiceMapper.countProjectMembersAppServiceByActive(projectId, userId);
        }

        return count;
    }

    @Override
    public List<AppServiceRepVO> listAll(Long projectId) {
        return ConvertUtils.convertList(baseListAll(projectId), AppServiceRepVO.class);
    }

    @Override
    public void checkName(Long projectId, String name) {
        AppServiceDTO appServiceDTO = new AppServiceDTO();
        appServiceDTO.setProjectId(projectId);
        appServiceDTO.setName(name);
        if (appServiceMapper.selectOne(appServiceDTO) != null) {
            throw new CommonException("error.name.exist");
        }
    }

    @Override
    public void checkCode(Long projectId, String code) {
        AppServiceDTO appServiceDTO = new AppServiceDTO();
        appServiceDTO.setProjectId(projectId);
        appServiceDTO.setCode(code);
        if (appServiceMapper.selectOne(appServiceDTO) != null) {
            throw new CommonException("error.code.exist");
        }
    }

    @Override
    public AppServiceBatchCheckVO checkCodeByProjectId(Long projectId, AppServiceBatchCheckVO appServiceBatchCheckVO) {
        AppServiceBatchCheckVO batchCheckVO = new AppServiceBatchCheckVO();
        batchCheckVO.setListCode(
                appServiceBatchCheckVO.getListCode().stream().filter(code -> {
                    AppServiceDTO appServiceDTO = new AppServiceDTO();
                    appServiceDTO.setProjectId(projectId);
                    appServiceDTO.setCode(code);
                    List<AppServiceDTO> list = appServiceMapper.select(appServiceDTO);
                    return list != null && !list.isEmpty();
                }).collect(Collectors.toList()));
        batchCheckVO.setListName(appServiceBatchCheckVO.getListName().stream().filter(name -> {
            AppServiceDTO appServiceDTO = new AppServiceDTO();
            appServiceDTO.setProjectId(projectId);
            appServiceDTO.setName(name);
            List<AppServiceDTO> list = appServiceMapper.select(appServiceDTO);
            return list != null && !list.isEmpty();
        }).collect(Collectors.toList()));
        return batchCheckVO;
    }

    @Override
    public void operationApplication(DevOpsAppServicePayload devOpsAppServicePayload) {
        DevopsProjectDTO devopsProjectDTO = devopsProjectService.baseQueryByGitlabAppGroupId(
                TypeUtil.objToInteger(devOpsAppServicePayload.getGroupId()));

        AppServiceDTO appServiceDTO = baseQueryByCode(devOpsAppServicePayload.getPath(),
                devopsProjectDTO.getIamProjectId());
        UserAttrDTO userAttrDTO = userAttrService.baseQueryByGitlabUserId(TypeUtil.objToLong(devOpsAppServicePayload.getUserId()));

        ProjectDTO projectDTO = baseServiceClientOperator.queryIamProjectById(devopsProjectDTO.getIamProjectId());
        OrganizationDTO organizationDTO = baseServiceClientOperator.queryOrganizationById(projectDTO.getOrganizationId());
        GitlabProjectDTO gitlabProjectDO = gitlabServiceClientOperator
                .queryProjectByName(organizationDTO.getCode() + "-" + projectDTO.getCode(), appServiceDTO.getCode(),
                        devOpsAppServicePayload.getUserId());
        Integer gitlabProjectId = gitlabProjectDO.getId();
        if (gitlabProjectId == null) {
            gitlabProjectDO = gitlabServiceClientOperator.createProject(devOpsAppServicePayload.getGroupId(),
                    devOpsAppServicePayload.getPath(),
                    devOpsAppServicePayload.getUserId(), false);
        }
        devOpsAppServicePayload.setGitlabProjectId(gitlabProjectDO.getId());

        String applicationServiceToken = getApplicationToken(devOpsAppServicePayload.getGitlabProjectId(), devOpsAppServicePayload.getUserId());
        appServiceDTO.setGitlabProjectId(gitlabProjectDO.getId());
        appServiceDTO.setToken(applicationServiceToken);
        appServiceDTO.setSynchro(true);
        appServiceDTO.setFailed(false);
        setProjectHook(appServiceDTO, devOpsAppServicePayload.getGitlabProjectId(), applicationServiceToken, devOpsAppServicePayload.getUserId());

        // 为项目下的成员分配对于此gitlab项目的权限
        operateGitlabMemberPermission(devOpsAppServicePayload);
        if (devOpsAppServicePayload.getTemplateAppServiceId() != null && devOpsAppServicePayload.getTemplateAppServiceVersionId() != null) {
            LOGGER.info("The current app service id is {} and the service code is {}", appServiceDTO.getId(), appServiceDTO.getCode());
            LOGGER.info("The template app service id is not null: {}, start to clone template repository", devOpsAppServicePayload.getTemplateAppServiceId());

            String repoUrl = !gitlabUrl.endsWith("/") ? gitlabUrl + "/" : gitlabUrl;
            String newGroupName = organizationDTO.getCode() + "-" + projectDTO.getCode();
            String repositoryUrl = repoUrl + newGroupName + "/" + appServiceDTO.getCode() + GIT;
            cloneAndPushCode(appServiceDTO, userAttrDTO, devOpsAppServicePayload.getTemplateAppServiceId(), devOpsAppServicePayload.getTemplateAppServiceVersionId(), repositoryUrl, newGroupName);
        }

        appServiceMapper.updateByIdSelectiveWithoutAudit(appServiceDTO);
    }


    @Override
    public void operationAppServiceImport(DevOpsAppImportServicePayload devOpsAppServiceImportPayload) {
        // 准备相关的数据
        DevopsProjectDTO devopsProjectDTO = devopsProjectService.baseQueryByGitlabAppGroupId(
                TypeUtil.objToInteger(devOpsAppServiceImportPayload.getGroupId()));
        AppServiceDTO appServiceDTO = baseQueryByCode(devOpsAppServiceImportPayload.getPath(),
                devopsProjectDTO.getIamProjectId());
        ProjectDTO projectDTO = baseServiceClientOperator.queryIamProjectById(devopsProjectDTO.getIamProjectId());
        OrganizationDTO organizationDTO = baseServiceClientOperator.queryOrganizationById(projectDTO.getOrganizationId());

        GitlabProjectDTO gitlabProjectDO = gitlabServiceClientOperator.queryProjectByName(
                organizationDTO.getCode() + "-" + projectDTO.getCode(),
                appServiceDTO.getCode(),
                devOpsAppServiceImportPayload.getUserId());
        if (gitlabProjectDO.getId() == null) {
            gitlabProjectDO = gitlabServiceClientOperator.createProject(devOpsAppServiceImportPayload.getGroupId(),
                    devOpsAppServiceImportPayload.getPath(),
                    devOpsAppServiceImportPayload.getUserId(), false);
        }
        devOpsAppServiceImportPayload.setGitlabProjectId(gitlabProjectDO.getId());

        // 为项目下的成员分配对于此gitlab项目的权限
        operateGitlabMemberPermission(devOpsAppServiceImportPayload);

        UserAttrDTO userAttrDTO = userAttrService.baseQueryByGitlabUserId(TypeUtil.objToLong(devOpsAppServiceImportPayload.getUserId()));


        // clone外部代码仓库
        String applicationDir = APPLICATION + GenerateUUID.generateUUID();
        Git repositoryGit = gitUtil.cloneRepository(applicationDir, devOpsAppServiceImportPayload.getRepositoryUrl(), devOpsAppServiceImportPayload.getAccessToken());

        // 设置Application对应的gitlab项目的仓库地址
        String repoUrl = !gitlabUrl.endsWith("/") ? gitlabUrl + "/" : gitlabUrl;
        appServiceDTO.setRepoUrl(repoUrl + organizationDTO.getCode()
                + "-" + projectDTO.getCode() + "/" + appServiceDTO.getCode() + ".git");

        File applicationWorkDir = new File(gitUtil.getWorkingDirectory(applicationDir));

        String protectedBranchName = null;

        try {
            List<Ref> refs = repositoryGit.branchList().setListMode(ListBranchCommand.ListMode.ALL).call();
            for (Ref ref : refs) {
                String branchName;
                if (ref.getName().contains(Constants.R_HEADS)) {
                    branchName = ref.getName().split("/")[2];
                    // 当前的本地的 refs/heads/ 内的引用是保护分支的名称，大部分保护分支是master，不排除develop等其他分支的可能
                    protectedBranchName = branchName;
                } else {
                    branchName = ref.getName().split("/")[3];
                }

                // 跳过对活跃本地分支A: /refs/heads/A 和 /refs/remotes/origin/A 之间的第二次重复的推送
                if (branchName.equals(protectedBranchName) && ref.getName().contains(Constants.R_REMOTES)) {
                    continue;
                }

                if (ref.getName().contains(Constants.R_REMOTES)) {
                    repositoryGit.checkout().setCreateBranch(true).setName(branchName).setStartPoint(ref.getName()).call();
                }

                // 获取push代码所需的access token
                String accessToken = getToken(devOpsAppServiceImportPayload.getGitlabProjectId(), applicationDir, userAttrDTO);

                BranchDTO branchDTO = gitlabServiceClientOperator.queryBranch(gitlabProjectDO.getId(), branchName);
                if (branchDTO.getName() == null) {
                    try {
                        // 提交并推代码
                        gitUtil.push(repositoryGit, appServiceDTO.getRepoUrl(), accessToken, branchName);
                    } catch (CommonException e) {
                        releaseResources(applicationWorkDir, repositoryGit);
                        throw e;
                    }
                }
                initBranch(devOpsAppServiceImportPayload, appServiceDTO, branchName);
            }

            BranchDTO branchDTO = gitlabServiceClientOperator.queryBranch(gitlabProjectDO.getId(), protectedBranchName);
            //解决push代码之后gitlab给master分支设置保护分支速度和程序运行速度不一致
            if (!branchDTO.getProtected()) {
                try {
                    gitlabServiceClientOperator.createProtectBranch(devOpsAppServiceImportPayload.getGitlabProjectId(), protectedBranchName, AccessLevel.MASTER.toString(), AccessLevel.MASTER.toString(), devOpsAppServiceImportPayload.getUserId());
                } catch (CommonException e) {
                    // 出现异常时重试一次
                    if (!gitlabServiceClientOperator.queryBranch(gitlabProjectDO.getId(), protectedBranchName).getProtected()) {
                        gitlabServiceClientOperator.createProtectBranch(devOpsAppServiceImportPayload.getGitlabProjectId(), protectedBranchName, AccessLevel.MASTER.toString(), AccessLevel.MASTER.toString(), devOpsAppServiceImportPayload.getUserId());
                    }
                }
            }
        } catch (GitAPIException e) {
            LOGGER.error("GitAPIException: {}", e);
        }

        releaseResources(applicationWorkDir, repositoryGit);

        try {
            // 设置application的属性
            String applicationServiceToken = getApplicationToken(gitlabProjectDO.getId(), devOpsAppServiceImportPayload.getUserId());
            appServiceDTO.setGitlabProjectId(TypeUtil.objToInteger(devOpsAppServiceImportPayload.getGitlabProjectId()));
            appServiceDTO.setToken(applicationServiceToken);
            appServiceDTO.setSynchro(true);
            appServiceDTO.setFailed(false);

            // set project hook id for application
            setProjectHook(appServiceDTO, gitlabProjectDO.getId(), applicationServiceToken, devOpsAppServiceImportPayload.getUserId());

            appServiceMapper.updateByIdSelectiveWithoutAudit(appServiceDTO);
        } catch (Exception e) {
            throw new CommonException(e.getMessage(), e);
        }
    }


    @Override
    @Saga(code = SagaTopicCodeConstants.DEVOPS_CREATE_APP_FAIL,
            description = "Devops设置application状态为创建失败(devops set app status create err)", inputSchema = "{}")
    public void setAppErrStatus(String input, Long projectId) {
        producer.applyAndReturn(
                StartSagaBuilder
                        .newBuilder()
                        .withLevel(ResourceLevel.PROJECT)
                        .withRefType("")
                        .withSagaCode(SagaTopicCodeConstants.DEVOPS_CREATE_APP_FAIL),
                builder -> builder
                        .withJson(input)
                        .withRefId("")
                        .withSourceId(projectId));
    }

    @Override
    public String queryFile(String token, String type) {
        AppServiceDTO appServiceDTO = baseQueryByToken(token);
        if (appServiceDTO == null) {
            return null;
        }
        try {
            ProjectDTO projectDTO = baseServiceClientOperator.queryIamProjectById(appServiceDTO.getProjectId());
            OrganizationDTO organizationDTO = baseServiceClientOperator.queryOrganizationById(projectDTO.getOrganizationId());
            ConfigVO harborProjectConfig = gson.fromJson(devopsConfigService.queryRealConfig(appServiceDTO.getId(), APP_SERVICE, HARBOR).getConfig(), ConfigVO.class);
            ConfigVO chartProjectConfig = gson.fromJson(devopsConfigService.queryRealConfig(appServiceDTO.getId(), APP_SERVICE, CHART).getConfig(), ConfigVO.class);
            InputStream  inputStream = this.getClass().getResourceAsStream("/shell/ci.sh");
            Map<String, String> params = new HashMap<>();
            String groupName = organizationDTO.getCode() + "-" + projectDTO.getCode();
            if (harborProjectConfig.getProject() != null) {
                groupName = harborProjectConfig.getProject();
            }
            String dockerUrl = harborProjectConfig.getUrl().replace("http://", "").replace("https://", "");
            dockerUrl = dockerUrl.endsWith("/") ? dockerUrl.substring(0, dockerUrl.length() - 1) : dockerUrl;
            DevopsConfigDTO sonarConfig = devopsConfigService.baseQueryByName(null, SONAR_NAME);
            if (sonarConfig != null) {
                params.put("{{ SONAR_LOGIN }}", sonarConfig.getConfig());
                params.put("{{ SONAR_URL }}", sonarqubeUrl);
            }else {
                params.put("{{ SONAR_LOGIN }}", "");
                params.put("{{ SONAR_URL }}", "");
            }
            params.put("{{ GROUP_NAME }}", groupName);
            params.put("{{ PROJECT_NAME }}", appServiceDTO.getCode());
            params.put("{{ PRO_CODE }}", projectDTO.getCode());
            params.put("{{ ORG_CODE }}", organizationDTO.getCode());
            params.put("{{ DOCKER_REGISTRY }}", dockerUrl);
            params.put("{{ DOCKER_USERNAME }}", harborProjectConfig.getUserName());
            params.put("{{ DOCKER_PASSWORD }}", harborProjectConfig.getPassword());
            params.put("{{ CHART_REGISTRY }}", chartProjectConfig.getUrl().endsWith("/") ? chartProjectConfig.getUrl().substring(0, chartProjectConfig.getUrl().length() - 1) : chartProjectConfig.getUrl());
            return FileUtil.replaceReturnString(inputStream, params);
        } catch (CommonException e) {
            return null;
        }
    }

    @Override
    public List<AppServiceCodeVO> listByEnvId(Long projectId, Long envId, String status, Long appServiceId) {
        List<AppServiceCodeVO> applicationCodeVOS = ConvertUtils
                .convertList(baseListByEnvId(projectId, envId, status),
                        AppServiceCodeVO.class);
        if (appServiceId != null) {
            AppServiceDTO appServiceDTO = baseQuery(appServiceId);
            AppServiceCodeVO applicationCodeVO = new AppServiceCodeVO();
            BeanUtils.copyProperties(appServiceDTO, applicationCodeVO);
            for (int i = 0; i < applicationCodeVOS.size(); i++) {
                if (applicationCodeVOS.get(i).getId().equals(appServiceDTO.getId())) {
                    applicationCodeVOS.remove(applicationCodeVOS.get(i));
                }
            }
            applicationCodeVOS.add(0, applicationCodeVO);
        }
        return applicationCodeVOS;
    }

    @Override
    public PageInfo<AppServiceCodeVO> pageByIds(Long projectId, Long envId, Long appServiceId, PageRequest pageRequest) {
        return ConvertUtils.convertPage(basePageByEnvId(projectId, envId, appServiceId, pageRequest),
                AppServiceCodeVO.class);
    }

    @Override
    public PageInfo<AppServiceReqVO> pageByActiveAndPubAndVersion(Long projectId, PageRequest pageRequest,
                                                                  String params) {
        return ConvertUtils.convertPage(basePageByActiveAndPubAndHasVersion(projectId, true, pageRequest, params), AppServiceReqVO.class);
    }

    @Override
    public List<AppServiceUserPermissionRespVO> listAllUserPermission(Long appServiceId) {
        List<Long> userIds = appServiceUserPermissionService.baseListByAppId(appServiceId).stream().map(AppServiceUserRelDTO::getIamUserId)
                .collect(Collectors.toList());
        List<IamUserDTO> userEList = baseServiceClientOperator.listUsersByIds(userIds);
        List<AppServiceUserPermissionRespVO> resultList = new ArrayList<>();
        userEList.forEach(
                e -> resultList.add(new AppServiceUserPermissionRespVO(e.getId(), e.getLoginName(), e.getRealName())));
        return resultList;
    }

    @Override
    public Boolean validateRepositoryUrlAndToken(GitPlatformType gitPlatformType, String repositoryUrl, String
            accessToken) {
        if (!REPOSITORY_URL_PATTERN.matcher(repositoryUrl).matches()) {
            return Boolean.FALSE;
        }

        // 当不存在access_token时，默认将仓库识别为公开的
        return GitUtil.validRepositoryUrl(repositoryUrl, accessToken);
    }

    @Override
    @Saga(code = SagaTopicCodeConstants.DEVOPS_IMPORT_GITLAB_PROJECT,
            description = "Devops从外部代码平台导入到gitlab项目", inputSchema = "{}")
    @Transactional(rollbackFor = Exception.class)
    public AppServiceRepVO importApp(Long projectId, AppServiceImportVO appServiceImportVO) {
        // 获取当前操作的用户的信息
        UserAttrVO userAttrVO = userAttrService.queryByUserId(TypeUtil.objToLong(GitUserNameUtil.getUserId()));

        // 校验application信息的格式
        ApplicationValidator.checkApplicationService(appServiceImportVO.getCode());
        // 校验名称唯一性
        checkName(projectId, appServiceImportVO.getName());

        // 校验code唯一性
        checkCode(projectId, appServiceImportVO.getCode());

        AppServiceDTO appServiceDTO = new AppServiceDTO();
        appServiceDTO.setProjectId(projectId);
        appServiceDTO.setName(appServiceImportVO.getName());
        appServiceDTO.setCode(appServiceImportVO.getCode());

        // 校验repository（和token） 地址是否有效
        GitPlatformType gitPlatformType = GitPlatformType.from(appServiceImportVO.getPlatformType());
        checkRepositoryUrlAndToken(gitPlatformType, appServiceImportVO.getRepositoryUrl(), appServiceImportVO.getAccessToken());

        ProjectDTO projectDTO = baseServiceClientOperator.queryIamProjectById(projectId);

        appServiceDTO = fromImportVoToDto(appServiceImportVO);

        appServiceDTO.setProjectId(projectId);
        appServiceDTO.setActive(true);
        appServiceDTO.setSynchro(false);
        appServiceDTO.setSkipCheckPermission(Boolean.TRUE);
        appServiceDTO.setHarborConfigId(appServiceImportVO.getHarborConfigId());
        appServiceDTO.setChartConfigId(appServiceImportVO.getChartConfigId());

        // 查询创建应用所在的gitlab应用组
        DevopsProjectDTO devopsProjectDTO = devopsProjectService.baseQueryByProjectId(appServiceDTO.getProjectId());
        MemberDTO memberDTO = gitlabGroupMemberService.queryByUserId(
                TypeUtil.objToInteger(devopsProjectDTO.getDevopsAppGroupId()),
                TypeUtil.objToInteger(userAttrVO.getGitlabUserId()));

        // 校验用户的gitlab权限
        if (memberDTO == null || !memberDTO.getAccessLevel().equals(AccessLevel.OWNER.toValue())) {
            throw new CommonException(ERROR_USER_NOT_OWNER);
        }

        // 创建应用服务
        appServiceDTO = baseCreate(appServiceDTO);
        Long appServiceId = appServiceDTO.getId();

        //创建saga payload
        DevOpsAppImportServicePayload devOpsAppImportServicePayload = new DevOpsAppImportServicePayload();
        devOpsAppImportServicePayload.setPath(appServiceDTO.getCode());
        devOpsAppImportServicePayload.setOrganizationId(projectDTO.getOrganizationId());
        devOpsAppImportServicePayload.setUserId(TypeUtil.objToInteger(userAttrVO.getGitlabUserId()));
        devOpsAppImportServicePayload.setGroupId(TypeUtil.objToInteger(devopsProjectDTO.getDevopsAppGroupId()));
        devOpsAppImportServicePayload.setUserIds(Collections.emptyList());
        devOpsAppImportServicePayload.setSkipCheckPermission(appServiceDTO.getSkipCheckPermission());
        devOpsAppImportServicePayload.setAppServiceId(appServiceDTO.getId());
        devOpsAppImportServicePayload.setIamProjectId(projectId);
        devOpsAppImportServicePayload.setRepositoryUrl(appServiceImportVO.getRepositoryUrl());
        devOpsAppImportServicePayload.setAccessToken(appServiceImportVO.getAccessToken());

        producer.applyAndReturn(
                StartSagaBuilder
                        .newBuilder()
                        .withLevel(ResourceLevel.PROJECT)
                        .withRefType("")
                        .withSagaCode(SagaTopicCodeConstants.DEVOPS_IMPORT_GITLAB_PROJECT),
                builder -> builder
                        .withPayloadAndSerialize(devOpsAppImportServicePayload)
                        .withRefId("")
                        .withSourceId(projectId));

        return ConvertUtils.convertObject(baseQuery(appServiceId), AppServiceRepVO.class);
    }

    @Override
    public AppServiceRepVO queryByCode(Long projectId, String code) {
        return ConvertUtils.convertObject(baseQueryByCode(code, projectId), AppServiceRepVO.class);
    }

    @Override
    public Boolean checkHarbor(String url, String userName, String password, String project, String email) {
        ConfigurationProperties configurationProperties = new ConfigurationProperties();
        configurationProperties.setBaseUrl(url);
        configurationProperties.setUsername(userName);
        configurationProperties.setPassword(password);
        configurationProperties.setInsecureSkipTlsVerify(false);
        configurationProperties.setProject(project);
        configurationProperties.setType(HARBOR);
        Retrofit retrofit = RetrofitHandler.initRetrofit(configurationProperties);
        HarborClient harborClient = retrofit.create(HarborClient.class);
        Call<User> getUser = harborClient.getCurrentUser();
        Response<User> userResponse;
        try {
            userResponse = getUser.execute();
            if (userResponse.raw().code() != 200) {
                if (userResponse.raw().code() == 401) {
                    throw new CommonException("error.harbor.user.password");
                } else {
                    throw new CommonException(userResponse.errorBody().string());
                }
            }
        } catch (IOException e) {
            throw new CommonException(e);
        }
        //校验用户的邮箱是否匹配
        if (!email.equals(userResponse.body().getEmail())) {
            throw new CommonException("error.user.email.not.equal");
        }

        //如果传入了project,校验用户是否有project的权限
        if (project != null) {
            Call<List<ProjectDetail>> listProject = harborClient.listProject(project);
            Response<List<ProjectDetail>> projectResponse;
            try {
                projectResponse = listProject.execute();
                if (projectResponse.body() == null) {
                    throw new CommonException("error.harbor.project.permission");
                } else {
                    List<ProjectDetail> projects = (projectResponse.body()).stream().filter(a -> (a.getName().equals(configurationProperties.getProject()))).collect(Collectors.toList());
                    if (projects.isEmpty()) {
                        throw new CommonException("error.harbor.project.permission");
                    }
                }
            } catch (IOException e) {
                throw new CommonException(e);
            }
        }
        return true;
    }

    @Override
    public Boolean checkChart(String url) {
        ConfigurationProperties configurationProperties = new ConfigurationProperties();
        configurationProperties.setBaseUrl(url);
        configurationProperties.setType(CHART);
        Retrofit retrofit = RetrofitHandler.initRetrofit(configurationProperties);
        ChartClient chartClient = retrofit.create(ChartClient.class);
        chartClient.getHealth();
        Call<Object> getHealth = chartClient.getHealth();
        try {
            getHealth.execute();
        } catch (IOException e) {
            throw new CommonException(e);
        }
        return true;
    }

    @Override
    public SonarContentsVO getSonarContent(Long projectId, Long appServiceId) {

        //没有使用sonarqube直接返回空对象
        if (sonarqubeUrl.equals("")) {
            return new SonarContentsVO();
        }
        SonarContentsVO sonarContentsVO = new SonarContentsVO();
        List<SonarContentVO> sonarContentVOS = new ArrayList<>();
        AppServiceDTO appServiceDTO = baseQuery(appServiceId);
        ProjectDTO projectDTO = baseServiceClientOperator.queryIamProjectById(projectId);
        OrganizationDTO organization = baseServiceClientOperator.queryOrganizationById(projectDTO.getOrganizationId());


        //初始化sonarClient
        SonarClient sonarClient = RetrofitHandler.getSonarClient(sonarqubeUrl, SONAR, userName, password);
        String key = String.format(SONAR_KEY, organization.getCode(), projectDTO.getCode(), appServiceDTO.getCode());
        sonarqubeUrl = sonarqubeUrl.endsWith("/") ? sonarqubeUrl : sonarqubeUrl + "/";

        //校验sonarqube地址是否正确
        try {
            sonarClient.getUser().execute();
        } catch (IOException e) {
            return new SonarContentsVO();
        }

        try {
            //初始化查询参数
            Map<String, String> queryContentMap = new HashMap<>();
            queryContentMap.put("additionalFields", "metrics,periods");
            queryContentMap.put("componentKey", key);
            queryContentMap.put("metricKeys", "quality_gate_details,bugs,vulnerabilities,new_bugs,new_vulnerabilities,sqale_index,code_smells,new_technical_debt,new_code_smells,coverage,tests,new_coverage,duplicated_lines_density,duplicated_blocks,new_duplicated_lines_density,ncloc,ncloc_language_distribution");

            //根据project-key查询sonarqube项目内容
            Response<SonarComponent> sonarComponentResponse = sonarClient.getSonarComponent(queryContentMap).execute();
            if (sonarComponentResponse.raw().code() != 200) {
                if (sonarComponentResponse.raw().code() == 404) {
                    return new SonarContentsVO();
                }
                if (sonarComponentResponse.raw().code() == 401) {
                    throw new CommonException("error.sonarqube.user");
                }
                throw new CommonException(sonarComponentResponse.errorBody().string());
            }
            if (sonarComponentResponse.body() == null) {
                return new SonarContentsVO();
            }
            if (sonarComponentResponse.body().getPeriods() != null && !sonarComponentResponse.body().getPeriods().isEmpty()) {
                sonarContentsVO.setDate(sonarComponentResponse.body().getPeriods().get(0).getDate());
                sonarContentsVO.setMode(sonarComponentResponse.body().getPeriods().get(0).getMode());
                sonarContentsVO.setParameter(sonarComponentResponse.body().getPeriods().get(0).getParameter());
            } else {
                Map<String, String> analyseMap = new HashMap<>();
                analyseMap.put("project", key);
                analyseMap.put("ps", "3");

                //查询上一次的分析时间
                Response<SonarAnalyses> sonarAnalyses = sonarClient.getAnalyses(analyseMap).execute();
                if (sonarAnalyses.raw().code() == 200 && sonarAnalyses.body().getAnalyses() != null && !sonarAnalyses.body().getAnalyses().isEmpty()) {
                    sonarContentsVO.setDate(sonarAnalyses.body().getAnalyses().get(0).getDate());
                }
            }

            //分类型对sonarqube project查询返回的结果进行处理
            sonarComponentResponse.body().getComponent().getMeasures().forEach(measure -> {
                SonarQubeType sonarQubeType = SonarQubeType.forValue(String.valueOf(measure.getMetric()));
                switch (sonarQubeType) {
                    case BUGS:
                        SonarContentVO bug = new SonarContentVO();
                        bug.setKey(measure.getMetric());
                        bug.setValue(measure.getValue() == null ? "0" : measure.getValue());
                        bug.setUrl(String.format("%sproject/issues?id=%s&resolved=false&types=BUG", sonarqubeUrl, key));
                        try {
                            Map<String, String> queryBugMap = getQueryMap(key, "BUG", false);
                            Response<Bug> bugResponse = sonarClient.getBugs(queryBugMap).execute();
                            if (bugResponse.raw().code() != 200) {
                                throw new CommonException(bugResponse.errorBody().string());
                            }
                            List<Facet> facets = bugResponse.body().getFacets();
                            getRate(bug, facets);
                        } catch (IOException e) {
                            throw new CommonException(e);
                        }
                        sonarContentVOS.add(bug);
                        break;
                    case VULNERABILITIES:
                        SonarContentVO vulnerabilities = new SonarContentVO();
                        vulnerabilities.setKey(measure.getMetric());
                        vulnerabilities.setValue(measure.getValue() == null ? "0" : measure.getValue());
                        vulnerabilities.setUrl(String.format("%sproject/issues?id=%s&resolved=false&types=VULNERABILITY", sonarqubeUrl, key));
                        try {
                            Map<String, String> queryVulnerabilitiesMap = getQueryMap(key, "VULNERABILITY", false);
                            Response<Vulnerability> vulnerabilityResponse = sonarClient.getVulnerability(queryVulnerabilitiesMap).execute();
                            if (vulnerabilityResponse.raw().code() != 200) {
                                throw new CommonException(vulnerabilityResponse.errorBody().string());
                            }
                            List<Facet> facets = vulnerabilityResponse.body().getFacets();
                            getRate(vulnerabilities, facets);
                        } catch (IOException e) {
                            throw new CommonException(e);
                        }
                        sonarContentVOS.add(vulnerabilities);
                        break;
                    case NEW_BUGS:
                        SonarContentVO newBug = new SonarContentVO();
                        newBug.setKey(measure.getMetric());
                        newBug.setValue(measure.getValue() == null ? "0" : measure.getValue());
                        newBug.setUrl(String.format("%sproject/issues?id=%s&resolved=false&sinceLeakPeriod=true&types=BUG", sonarqubeUrl, key));
                        try {
                            Map<String, String> queryNewBugMap = getQueryMap(key, "BUG", true);

                            Response<Bug> newBugResponse = sonarClient.getNewBugs(queryNewBugMap).execute();
                            if (newBugResponse.raw().code() != 200) {
                                throw new CommonException(newBugResponse.errorBody().string());
                            }
                            List<Facet> facets = newBugResponse.body().getFacets();
                            getRate(newBug, facets);
                        } catch (IOException e) {
                            throw new CommonException(e);
                        }
                        sonarContentVOS.add(newBug);
                        break;
                    case NEW_VULNERABILITIES:
                        SonarContentVO newVulnerabilities = new SonarContentVO();
                        newVulnerabilities.setKey(measure.getMetric());
                        newVulnerabilities.setValue(measure.getPeriods().get(0).getValue());
                        newVulnerabilities.setUrl(String.format("%sproject/issues?id=%s&resolved=false&sinceLeakPeriod=true&types=VULNERABILITY", sonarqubeUrl, key));
                        try {
                            Map<String, String> queryNewVulnerabilitiesMap = getQueryMap(key, "VULNERABILITY", true);
                            Response<Vulnerability> newVulnerabilityResponse = sonarClient.getNewVulnerability(queryNewVulnerabilitiesMap).execute();
                            if (newVulnerabilityResponse.raw().code() != 200) {
                                throw new CommonException(newVulnerabilityResponse.errorBody().string());
                            }
                            List<Facet> facets = newVulnerabilityResponse.body().getFacets();
                            getRate(newVulnerabilities, facets);
                        } catch (IOException e) {
                            throw new CommonException(e);
                        }
                        sonarContentVOS.add(newVulnerabilities);
                        break;
                    case SQALE_INDEX:
                        SonarContentVO debt = new SonarContentVO();
                        debt.setKey(measure.getMetric());
                        debt.setValue(measure.getValue() == null ? "0" : measure.getValue());
                        double day = measure.getValue() == null ? 0 : TypeUtil.objTodouble(measure.getValue()) / 480;
                        double hour = measure.getValue() == null ? 0 : TypeUtil.objTodouble(measure.getValue()) / 60;
                        if (day >= 1) {
                            debt.setValue(String.format("%sd", Math.round(day)));
                        } else if (hour >= 1) {
                            debt.setValue(String.format("%sh", Math.round(hour)));
                        } else {
                            debt.setValue(String.format("%s%s", Math.round(TypeUtil.objTodouble(measure.getValue() == null ? 0 : measure.getValue())), measure.getValue() == null ? "" : "min"));
                        }
                        debt.setUrl(String.format("%sproject/issues?facetMode=effort&id=%s&resolved=false&types=CODE_SMELL", sonarqubeUrl, key));
                        sonarContentVOS.add(debt);
                        break;
                    case CODE_SMELLS:
                        SonarContentVO codeSmells = new SonarContentVO();
                        codeSmells.setKey(measure.getMetric());
                        double result = measure.getValue() == null ? 0 : TypeUtil.objToLong(measure.getValue()) / 1000;
                        if (result > 0) {
                            if (TypeUtil.objToLong(measure.getValue()) % 1000 == 0) {
                                codeSmells.setValue(String.format("%sK", result));
                            } else {
                                BigDecimal codeSmellDecimal = BigDecimal.valueOf(result);
                                codeSmells.setValue(String.format("%sK", codeSmellDecimal.setScale(1, BigDecimal.ROUND_HALF_UP).doubleValue()));
                            }
                        } else {
                            codeSmells.setValue(measure.getValue() == null ? "0" : measure.getValue());
                        }
                        codeSmells.setUrl(String.format("%sproject/issues?id=%s&resolved=false&types=CODE_SMELL", sonarqubeUrl, key));
                        sonarContentVOS.add(codeSmells);
                        break;
                    case NEW_TECHNICAL_DEBT:
                        SonarContentVO newDebt = new SonarContentVO();
                        newDebt.setKey(measure.getMetric());
                        double newDay = TypeUtil.objTodouble(measure.getPeriods().get(0).getValue()) / 480;
                        double newHour = TypeUtil.objTodouble(measure.getPeriods().get(0).getValue()) / 60;
                        if (newDay >= 1) {
                            newDebt.setValue(String.format("%sd", Math.round(newDay)));
                        } else if (newHour >= 1) {
                            newDebt.setValue(String.format("%sh", Math.round(newHour)));
                        } else {
                            newDebt.setValue(String.format("%s%s", measure.getPeriods().get(0).getValue(), measure.getPeriods().get(0).getValue().equals("0") ? "" : "min"));
                        }
                        newDebt.setUrl(String.format("%sproject/issues?facetMode=effort&id=%s&resolved=false&sinceLeakPeriod=true&types=CODE_SMELL", sonarqubeUrl, key));
                        sonarContentVOS.add(newDebt);
                        break;
                    case NEW_CODE_SMELLS:
                        SonarContentVO newCodeSmells = new SonarContentVO();
                        newCodeSmells.setKey(measure.getMetric());
                        double newResult = TypeUtil.objToLong(measure.getPeriods().get(0).getValue()) / 1000.0;
                        if (newResult > 0) {
                            if (TypeUtil.objToLong(measure.getPeriods().get(0).getValue()) % 1000 == 0) {
                                newCodeSmells.setValue(String.format("%sK", newResult));
                            } else {
                                BigDecimal codeSmellDecimal = BigDecimal.valueOf(newResult);
                                newCodeSmells.setValue(String.format("%sK", codeSmellDecimal.setScale(1, BigDecimal.ROUND_HALF_UP).doubleValue()));
                            }
                        } else {
                            newCodeSmells.setValue(measure.getPeriods().get(0).getValue());
                        }
                        newCodeSmells.setUrl(String.format("%sproject/issues?id=%s&resolved=false&sinceLeakPeriod=true&types=CODE_SMELL", sonarqubeUrl, key));
                        sonarContentVOS.add(newCodeSmells);
                        break;
                    case COVERAGE:
                        SonarContentVO coverage = new SonarContentVO();
                        coverage.setKey(measure.getMetric());
                        coverage.setValue(measure.getValue() == null ? "0" : measure.getValue());
                        coverage.setUrl(String.format("%scomponent_measures?id=%s&metric=coverage", sonarqubeUrl, key));
                        sonarContentVOS.add(coverage);
                        break;
                    case NEW_COVERAGE:
                        SonarContentVO newCoverage = new SonarContentVO();
                        newCoverage.setKey(measure.getMetric());
                        BigDecimal codeSmellDecimal = new BigDecimal(measure.getPeriods().get(0).getValue());
                        newCoverage.setValue(String.format("%s", codeSmellDecimal.setScale(1, BigDecimal.ROUND_HALF_UP).doubleValue()));
                        newCoverage.setUrl(String.format("%scomponent_measures?id=%s&metric=new_coverage", sonarqubeUrl, key));
                        sonarContentVOS.add(newCoverage);
                        break;
                    case DUPLICATED_LINES_DENSITY:
                        SonarContentVO duplicated = new SonarContentVO();
                        duplicated.setKey(measure.getMetric());
                        duplicated.setValue(measure.getValue() == null ? "0" : measure.getValue());
                        duplicated.setUrl(String.format("%scomponent_measures?id=%s&metric=duplicated_lines_density", sonarqubeUrl, key));
                        if (TypeUtil.objTodouble(measure.getValue()) >= 0 && TypeUtil.objTodouble(measure.getValue()) < 3) {
                            duplicated.setRate("A");
                        } else if (TypeUtil.objTodouble(measure.getValue()) >= 3 && TypeUtil.objTodouble(measure.getValue()) < 10) {
                            duplicated.setRate("B");
                        } else if (TypeUtil.objTodouble(measure.getValue()) >= 10 && TypeUtil.objTodouble(measure.getValue()) < 20) {
                            duplicated.setRate("C");
                        } else {
                            duplicated.setRate("D");
                        }
                        sonarContentVOS.add(duplicated);
                        break;
                    case DUPLICATED_BLOCKS:
                        SonarContentVO duplicatedBlocks = new SonarContentVO();
                        duplicatedBlocks.setKey(measure.getMetric());
                        duplicatedBlocks.setValue(measure.getValue() == null ? "0" : measure.getValue());
                        duplicatedBlocks.setUrl(String.format("%scomponent_measures?id=%s&metric=duplicated_blocks", sonarqubeUrl, key));
                        sonarContentVOS.add(duplicatedBlocks);
                        break;
                    case NEW_DUPLICATED_LINES_DENSITY:
                        SonarContentVO newDuplicated = new SonarContentVO();
                        newDuplicated.setKey(measure.getMetric());
                        if (TypeUtil.objTodouble(measure.getPeriods().get(0).getValue()) == 0) {
                            newDuplicated.setValue("0");
                        } else {
                            BigDecimal b = BigDecimal.valueOf(TypeUtil.objTodouble(measure.getPeriods().get(0).getValue()));
                            newDuplicated.setValue(TypeUtil.objToString(b.setScale(1, BigDecimal.ROUND_HALF_UP).doubleValue()));
                        }
                        newDuplicated.setUrl(String.format("%scomponent_measures?id=%s&metric=new_duplicated_lines_density", sonarqubeUrl, key));
                        sonarContentVOS.add(newDuplicated);
                        break;
                    case NCLOC:
                        SonarContentVO ncloc = new SonarContentVO();
                        ncloc.setKey(measure.getMetric());
                        double nclocResult = TypeUtil.objTodouble(measure.getValue()) / 1000;
                        if (nclocResult >= 0) {
                            if (TypeUtil.objToLong(measure.getValue()) % 1000 == 0) {
                                ncloc.setValue(String.format("%sK", nclocResult));
                            } else {
                                BigDecimal nclocDecimal = BigDecimal.valueOf(nclocResult);
                                ncloc.setValue(String.format("%sK", nclocDecimal.setScale(1, BigDecimal.ROUND_HALF_UP).doubleValue()));
                            }
                        } else {
                            ncloc.setValue(measure.getValue());
                        }
                        if (TypeUtil.objToLong(measure.getValue()) > 0 && TypeUtil.objToLong(measure.getValue()) < 1000) {
                            ncloc.setRate("XS");
                        } else if (TypeUtil.objToLong(measure.getValue()) >= 1000 && TypeUtil.objToLong(measure.getValue()) < 10000) {
                            ncloc.setRate("S");
                        } else if (TypeUtil.objToLong(measure.getValue()) >= 10000 && TypeUtil.objToLong(measure.getValue()) < 100000) {
                            ncloc.setRate("M");
                        } else if (TypeUtil.objToLong(measure.getValue()) >= 100000 && TypeUtil.objToLong(measure.getValue()) < 500000) {
                            ncloc.setRate("L");
                        } else {
                            ncloc.setRate("XL");
                        }
                        sonarContentVOS.add(ncloc);
                        break;
                    case TESTS:
                        SonarContentVO test = new SonarContentVO();
                        test.setKey(measure.getMetric());
                        test.setValue(measure.getValue() == null ? "0" : measure.getValue());
                        test.setUrl(String.format("%scomponent_measures?id=%s&metric=tests", sonarqubeUrl, key));
                        sonarContentVOS.add(test);
                        break;
                    case NCLOC_LANGUAGE_DISTRIBUTION:
                        SonarContentVO nclocLanguage = new SonarContentVO();
                        nclocLanguage.setKey(measure.getMetric());
                        nclocLanguage.setValue(measure.getValue());
                        sonarContentVOS.add(nclocLanguage);
                        break;
                    case QUALITY_GATE_DETAILS:
                        Quality quality = gson.fromJson(measure.getValue(), Quality.class);
                        sonarContentsVO.setStatus(quality.getLevel());
                        break;
                    default:
                        break;
                }
            });
            sonarContentsVO.setSonarContents(sonarContentVOS);
        } catch (IOException e) {
            throw new CommonException(e);
        }
        return sonarContentsVO;
    }

    @Override
    public SonarTableVO getSonarTable(Long projectId, Long appServiceId, String type, Date startTime, Date endTime) {
        if (sonarqubeUrl.equals("")) {
            return new SonarTableVO();
        }
        Calendar c = Calendar.getInstance();
        c.setTime(endTime);
        c.add(Calendar.DAY_OF_MONTH, 1);
        Date tomorrow = c.getTime();
        SonarTableVO sonarTableVO = new SonarTableVO();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss+0000");
        AppServiceDTO applicationDTO = baseQuery(appServiceId);
        ProjectDTO projectDTO = baseServiceClientOperator.queryIamProjectById(projectId);
        OrganizationDTO organizationDTO = baseServiceClientOperator.queryOrganizationById(projectDTO.getOrganizationId());
        SonarClient sonarClient = RetrofitHandler.getSonarClient(sonarqubeUrl, SONAR, userName, password);
        String key = String.format(SONAR_KEY, organizationDTO.getCode(), projectDTO.getCode(), applicationDTO.getCode());
        sonarqubeUrl = sonarqubeUrl.endsWith("/") ? sonarqubeUrl : sonarqubeUrl + "/";
        Map<String, String> queryMap = new HashMap<>();
        queryMap.put("component", key);
        queryMap.put("ps", "1000");
        if (ISSUE.equals(type)) {
            queryMap.put(METRICS, "bugs,code_smells,vulnerabilities");
            try {
                Response<SonarTables> sonarTablesResponse = sonarClient.getSonarTables(queryMap).execute();
                if (sonarTablesResponse.raw().code() != 200) {
                    if (sonarTablesResponse.raw().code() == 404) {
                        return new SonarTableVO();
                    }
                    if (sonarTablesResponse.raw().code() == 401) {
                        throw new CommonException("error.sonarqube.user");
                    }
                    throw new CommonException(sonarTablesResponse.errorBody().string());
                }
                List<String> bugs = new ArrayList<>();
                List<String> dates = new ArrayList<>();
                List<String> codeSmells = new ArrayList<>();
                List<String> vulnerabilities = new ArrayList<>();
                sonarTablesResponse.body().getMeasures().forEach(sonarTableMeasure -> {
                    if (sonarTableMeasure.getMetric().equals(SonarQubeType.BUGS.getType())) {
                        sonarTableMeasure.getHistory().stream().filter(sonarHistory ->
                                getHistory(startTime, tomorrow, sdf, sonarHistory)
                        ).forEach(sonarHistory -> {
                            bugs.add(sonarHistory.getValue());
                            dates.add(sonarHistory.getDate());
                        });
                        sonarTableVO.setDates(dates);
                        sonarTableVO.setBugs(bugs);
                    }
                    if (sonarTableMeasure.getMetric().equals(SonarQubeType.CODE_SMELLS.getType())) {
                        sonarTableMeasure.getHistory()
                                .stream()
                                .filter(sonarHistory -> getHistory(startTime, tomorrow, sdf, sonarHistory))
                                .forEach(sonarHistory -> codeSmells.add(sonarHistory.getValue()));
                        sonarTableVO.setCodeSmells(codeSmells);
                    }
                    if (sonarTableMeasure.getMetric().equals(SonarQubeType.VULNERABILITIES.getType())) {
                        sonarTableMeasure.getHistory()
                                .stream()
                                .filter(sonarHistory -> getHistory(startTime, tomorrow, sdf, sonarHistory))
                                .forEach(sonarHistory -> vulnerabilities.add(sonarHistory.getValue()));
                        sonarTableVO.setVulnerabilities(vulnerabilities);
                    }
                });
            } catch (IOException e) {
                throw new CommonException(e);
            }
        }
        if (COVERAGE.equals(type)) {
            queryMap.put(METRICS, "lines_to_cover,uncovered_lines,coverage");
            try {
                Response<SonarTables> sonarTablesResponse = sonarClient.getSonarTables(queryMap).execute();
                if (sonarTablesResponse.raw().code() != 200) {
                    if (sonarTablesResponse.raw().code() == 404) {
                        return new SonarTableVO();
                    }
                    throw new CommonException(sonarTablesResponse.errorBody().string());
                }
                List<String> linesToCover = new ArrayList<>();
                List<String> dates = new ArrayList<>();
                List<String> unCoverLines = new ArrayList<>();
                List<String> coverLines = new ArrayList<>();
                List<String> coverage = new ArrayList<>();
                sonarTablesResponse.body().getMeasures().forEach(sonarTableMeasure -> {
                    if (sonarTableMeasure.getMetric().equals(SonarQubeType.COVERAGE.getType())) {
                        sonarTableMeasure.getHistory().stream().filter(sonarHistory ->
                                getHistory(startTime, tomorrow, sdf, sonarHistory)
                        ).forEach(sonarHistory -> coverage.add(sonarHistory.getValue()));
                        sonarTableVO.setCoverage(coverage);
                    }
                    if (sonarTableMeasure.getMetric().equals(SonarQubeType.LINES_TO_COVER.getType())) {
                        sonarTableMeasure.getHistory().stream().filter(sonarHistory ->
                                getHistory(startTime, tomorrow, sdf, sonarHistory)
                        ).forEach(sonarHistory -> {
                            linesToCover.add(sonarHistory.getValue());
                            dates.add(sonarHistory.getDate());
                        });
                        sonarTableVO.setDates(dates);
                        sonarTableVO.setLinesToCover(linesToCover);
                    }

                    if (sonarTableMeasure.getMetric().equals(SonarQubeType.UNCOVERED_LINES.getType())) {
                        sonarTableMeasure.getHistory().stream().filter(sonarHistory ->
                                getHistory(startTime, tomorrow, sdf, sonarHistory)
                        ).forEach(sonarHistory -> unCoverLines.add(sonarHistory.getValue()));
                    }
                });
                for (int i = 0; i < linesToCover.size(); i++) {
                    coverLines.add(TypeUtil.objToString(TypeUtil.objToLong(linesToCover.get(i)) - TypeUtil.objToLong(unCoverLines.get(i))));
                }
                sonarTableVO.setCoverLines(coverLines);
            } catch (IOException e) {
                throw new CommonException(e);
            }
        }
        if (DUPLICATE.equals(type)) {
            queryMap.put(METRICS, "ncloc,duplicated_lines,duplicated_lines_density");
            try {
                Response<SonarTables> sonarTablesResponse = sonarClient.getSonarTables(queryMap).execute();
                if (sonarTablesResponse.raw().code() != 200) {
                    if (sonarTablesResponse.raw().code() == 404) {
                        return new SonarTableVO();
                    }
                    throw new CommonException(sonarTablesResponse.errorBody().string());
                }
                List<String> nclocs = new ArrayList<>();
                List<String> dates = new ArrayList<>();
                List<String> duplicatedLines = new ArrayList<>();
                List<String> duplicatedLinesRate = new ArrayList<>();
                sonarTablesResponse.body().getMeasures().forEach(sonarTableMeasure -> {
                    if (sonarTableMeasure.getMetric().equals(SonarQubeType.NCLOC.getType())) {
                        sonarTableMeasure.getHistory().stream().filter(sonarHistory ->
                                getHistory(startTime, tomorrow, sdf, sonarHistory)
                        ).forEach(sonarHistory -> {
                            nclocs.add(sonarHistory.getValue());
                            dates.add(sonarHistory.getDate());
                        });
                        sonarTableVO.setNclocs(nclocs);
                        sonarTableVO.setDates(dates);
                    }
                    if (sonarTableMeasure.getMetric().equals(SonarQubeType.DUPLICATED_LINES.getType())) {
                        sonarTableMeasure.getHistory().stream().filter(sonarHistory ->
                                getHistory(startTime, tomorrow, sdf, sonarHistory)
                        ).forEach(sonarHistory ->
                                duplicatedLines.add(sonarHistory.getValue())
                        );
                        sonarTableVO.setDuplicatedLines(duplicatedLines);
                    }
                    if (sonarTableMeasure.getMetric().equals(SonarQubeType.DUPLICATED_LINES_DENSITY.getType())) {
                        sonarTableMeasure.getHistory().stream().filter(sonarHistory ->
                                getHistory(startTime, tomorrow, sdf, sonarHistory)
                        ).forEach(sonarHistory -> duplicatedLinesRate.add(sonarHistory.getValue()));
                        sonarTableVO.setDuplicatedLinesRate(duplicatedLinesRate);
                    }
                });
            } catch (IOException e) {
                throw new CommonException(e);
            }
        }
        return sonarTableVO;
    }

    @Override
    public PageInfo<AppServiceRepVO> pageShareAppService(Long projectId, PageRequest pageRequest, String searchParam) {
        Map<String, Object> searchParamMap = TypeUtil.castMapParams(searchParam);

        Long organizationId = baseServiceClientOperator.queryIamProjectById(projectId).getOrganizationId();
        List<Long> appServiceIds = new ArrayList<>();
        baseServiceClientOperator.listIamProjectByOrgId(organizationId).forEach(proId ->
                baseListAll(projectId).forEach(appServiceDTO -> appServiceIds.add(appServiceDTO.getId()))
        );
        PageInfo<AppServiceDTO> applicationServiceDTOPageInfo = PageHelper.startPage(pageRequest.getPage(), pageRequest.getSize(), PageRequestUtil.getOrderBy(pageRequest)).doSelectPageInfo(() -> appServiceMapper.listShareApplicationService(appServiceIds, projectId, null, TypeUtil.cast(searchParamMap.get(TypeUtil.PARAMS))));
        return ConvertUtils.convertPage(applicationServiceDTOPageInfo, AppServiceRepVO.class);
    }

    @Override
    public PageInfo<DevopsUserPermissionVO> pagePermissionUsers(Long projectId, Long appServiceId, PageRequest pageRequest, String
            searchParam) {
        AppServiceDTO appServiceDTO = appServiceMapper.selectByPrimaryKey(appServiceId);

        RoleAssignmentSearchVO roleAssignmentSearchVO = new RoleAssignmentSearchVO();
        roleAssignmentSearchVO.setEnabled(true);
        Map<String, Object> searchParamMap;
        // 处理搜索参数
        if (!org.springframework.util.StringUtils.isEmpty(searchParam)) {
            Map maps = gson.fromJson(searchParam, Map.class);
            searchParamMap = TypeUtil.cast(maps.get(TypeUtil.SEARCH_PARAM));
            List<String> list = TypeUtil.cast(maps.get(TypeUtil.PARAMS));
            if (!CollectionUtils.isEmpty(list)) {
                String[] arrayParams = new String[list.size()];
                list.toArray(arrayParams);
                roleAssignmentSearchVO.setParam(arrayParams);
            }
            if (!CollectionUtils.isEmpty(searchParamMap)) {
                if (searchParamMap.get("loginName") != null) {
                    String loginName = TypeUtil.objToString(searchParamMap.get("loginName"));
                    roleAssignmentSearchVO.setLoginName(loginName);
                }
                if (searchParamMap.get("realName") != null) {
                    String realName = TypeUtil.objToString(searchParamMap.get("realName"));
                    roleAssignmentSearchVO.setRealName(realName);
                }
            }
        }


        // 根据参数搜索所有的项目成员
        Long memberRoleId = baseServiceClientOperator.queryRoleIdByCode(PROJECT_MEMBER);
        List<DevopsUserPermissionVO> allProjectMembers = ConvertUtils.convertList(
                baseServiceClientOperator.pagingQueryUsersByRoleIdOnProjectLevel(
                        new PageRequest(0, 0), roleAssignmentSearchVO, memberRoleId, projectId, false).getList(), iamUserDTO -> iamUserTOUserPermissionVO(iamUserDTO, MEMBER, appServiceDTO.getCreationDate()));
        // 获取项目下所有的项目所有者
        Long ownerId = baseServiceClientOperator.queryRoleIdByCode(PROJECT_OWNER);
        List<DevopsUserPermissionVO> allProjectOwners = ConvertUtils.convertList(
                baseServiceClientOperator.pagingQueryUsersByRoleIdOnProjectLevel(new PageRequest(0, 0), roleAssignmentSearchVO, ownerId, projectId, false).getList(), iamUserDTO -> iamUserTOUserPermissionVO(iamUserDTO, OWNER, appServiceDTO.getCreationDate()));

        if (!appServiceDTO.getSkipCheckPermission()) {
            List<AppServiceUserRelDTO> userPermissionDTOS = appServiceUserRelMapper.listAllUserPermissionByAppId(appServiceId);
            List<Long> assigned = userPermissionDTOS.stream().map(AppServiceUserRelDTO::getIamUserId).collect(Collectors.toList());
            allProjectMembers = allProjectMembers.stream().filter(member -> assigned.contains(member.getIamUserId()))
                    .peek(member -> {
                        Optional<AppServiceUserRelDTO> optional = userPermissionDTOS.stream().filter(permissionDTO -> permissionDTO.getIamUserId().equals(member.getIamUserId())).findFirst();
                        member.setCreationDate(optional.get().getLastUpdateDate());
                    }).collect(Collectors.toList());
        }


        Set<Long> ownerIds = allProjectOwners.stream().map(DevopsEnvUserVO::getIamUserId).collect(Collectors.toSet());
        //去除项目成员中的项目所有者成员

        //合并项目所有者和项目成员
        List<DevopsUserPermissionVO> userPermissionVOS = allProjectMembers.stream()
                .filter(e -> !ownerIds.contains(e.getIamUserId()))
                .sorted(comparing(DevopsUserPermissionVO::getCreationDate).reversed()).collect(Collectors.toList());
        userPermissionVOS.addAll(allProjectOwners);

        //没有任何项目成员和项目所有者
        if (userPermissionVOS.isEmpty()) {
            return ConvertUtils.convertPage(new PageInfo<>(), DevopsUserPermissionVO.class);
        } else {
            return PageInfoUtil.createPageFromList(new ArrayList<>(userPermissionVOS), pageRequest);
        }
    }

    @Override
    public List<DevopsUserPermissionVO> listMembers(Long projectId, Long appServiceId, String params) {
        RoleAssignmentSearchVO roleAssignmentSearchVO = new RoleAssignmentSearchVO();
        roleAssignmentSearchVO.setParam(new String[]{params});
        roleAssignmentSearchVO.setEnabled(true);

        // 根据参数搜索所有的项目成员
        Long memberRoleId = baseServiceClientOperator.queryRoleIdByCode(PROJECT_MEMBER);
        PageInfo<IamUserDTO> allProjectMembers = baseServiceClientOperator.pagingQueryUsersByRoleIdOnProjectLevel(new PageRequest(0, 0), roleAssignmentSearchVO, memberRoleId, projectId, false);
        if (allProjectMembers.getList().isEmpty()) {
            return Collections.emptyList();
        }
        // 获取项目下所有的项目所有者
        Long ownerId = baseServiceClientOperator.queryRoleIdByCode(PROJECT_OWNER);
        List<Long> allProjectOwnerIds = baseServiceClientOperator.pagingQueryUsersByRoleIdOnProjectLevel(
                new PageRequest(0, 0), roleAssignmentSearchVO, ownerId, projectId, false)
                .getList()
                .stream()
                .map(IamUserDTO::getId)
                .collect(Collectors.toList());
        // 数据库中已被分配权限的
        List<Long> assigned = appServiceUserRelMapper.listAllUserPermissionByAppId(appServiceId).stream().map(AppServiceUserRelDTO::getIamUserId).collect(Collectors.toList());

        // 过滤项目成员中的项目所有者和已被分配权限的
        List<IamUserDTO> members = allProjectMembers.getList()
                .stream()
                .filter(member -> !allProjectOwnerIds.contains(member.getId()))
                .filter(member -> !assigned.contains(member.getId()))
                .collect(Collectors.toList());

        return ConvertUtils.convertList(members,
                iamUserDTO -> new DevopsUserPermissionVO(iamUserDTO.getId(), iamUserDTO.getLdap() ? iamUserDTO.getLoginName() : iamUserDTO.getEmail(), iamUserDTO.getRealName()));
    }

    @Override
    public void updatePermission(Long projectId, Long appServiceId, AppServicePermissionVO applicationPermissionVO) {
        // 创建gitlabUserPayload
        AppServiceDTO appServiceDTO = appServiceMapper.selectByPrimaryKey(appServiceId);

        DevOpsUserPayload devOpsUserPayload = new DevOpsUserPayload();
        devOpsUserPayload.setIamProjectId(projectId);
        devOpsUserPayload.setAppServiceId(appServiceId);
        devOpsUserPayload.setGitlabProjectId(appServiceDTO.getGitlabProjectId());

        //原先是否跳过权限检查
        boolean skip = appServiceDTO.getSkipCheckPermission();
        if (skip) {
            if (applicationPermissionVO.getSkipCheckPermission()) {
                //原来跳过权限检查，现在也跳过权限检查
                return;
            } else {
                //原来跳过权限检查，现在不跳过权限检查
                appServiceDTO.setId(appServiceId);
                appServiceDTO.setSkipCheckPermission(false);
                appServiceMapper.updateByPrimaryKeySelective(appServiceDTO);
                if (!CollectionUtils.isEmpty(applicationPermissionVO.getUserIds())) {
                    applicationPermissionVO.getUserIds().stream().filter(Objects::nonNull).forEach(u -> appServiceUserPermissionService.baseCreate(u, appServiceId));
                }
                devOpsUserPayload.setIamUserIds(applicationPermissionVO.getUserIds());
                devOpsUserPayload.setOption(1);
            }
        } else {
            if (applicationPermissionVO.getSkipCheckPermission()) {
                //原来不跳过权限检查，现在跳过权限检查
                appServiceDTO.setId(appServiceId);
                appServiceDTO.setSkipCheckPermission(true);
                appServiceMapper.updateByPrimaryKeySelective(appServiceDTO);
                appServiceUserPermissionService.baseDeleteByAppServiceId(appServiceId);
                devOpsUserPayload.setOption(2);
            } else {
                //原来不跳过权限检查，现在也不跳过权限检查，新增用户权限
                if (!CollectionUtils.isEmpty(applicationPermissionVO.getUserIds())) {
                    applicationPermissionVO.getUserIds().stream().filter(Objects::nonNull).forEach(u -> appServiceUserPermissionService.baseCreate(u, appServiceId));
                }
                devOpsUserPayload.setIamUserIds(applicationPermissionVO.getUserIds());
                devOpsUserPayload.setOption(3);
            }
        }

        producer.applyAndReturn(
                StartSagaBuilder
                        .newBuilder()
                        .withLevel(ResourceLevel.PROJECT)
                        .withRefType("app")
                        .withSagaCode(SagaTopicCodeConstants.DEVOPS_UPDATE_GITLAB_USERS),
                builder -> builder
                        .withPayloadAndSerialize(devOpsUserPayload)
                        .withRefId(String.valueOf(appServiceId))
                        .withSourceId(projectId));
    }

    @Override
    public void deletePermission(Long projectId, Long appServiceId, Long userId) {
        AppServiceDTO appServiceDTO = appServiceMapper.selectByPrimaryKey(appServiceId);
        appServiceUserPermissionService.baseDeleteByUserIdAndAppIds(Arrays.asList(appServiceId), userId);
        //原来不跳，现在也不跳，删除用户在gitlab权限
        DevOpsUserPayload devOpsUserPayload = new DevOpsUserPayload();
        devOpsUserPayload.setIamProjectId(projectId);
        devOpsUserPayload.setAppServiceId(appServiceId);
        devOpsUserPayload.setGitlabProjectId(appServiceDTO.getGitlabProjectId());
        devOpsUserPayload.setIamUserIds(Arrays.asList(userId));
        devOpsUserPayload.setOption(4);
        producer.applyAndReturn(
                StartSagaBuilder
                        .newBuilder()
                        .withLevel(ResourceLevel.PROJECT)
                        .withRefType("app")
                        .withSagaCode(SagaTopicCodeConstants.DEVOPS_UPDATE_GITLAB_USERS),
                builder -> builder
                        .withPayloadAndSerialize(devOpsUserPayload)
                        .withRefId(String.valueOf(appServiceId))
                        .withSourceId(projectId));
    }

    @Override
    public List<ProjectVO> listProjects(Long organizationId, Long projectId, String params) {
        List<ProjectDTO> projectDTOS = baseServiceClientOperator.listIamProjectByOrgId(organizationId, null, null, params).stream()
                .filter(v -> v.getEnabled())
                .filter(v -> !projectId.equals(v.getId())).collect(Collectors.toList());
        ;
        List<ProjectVO> projectVOS = ConvertUtils.convertList(projectDTOS, ProjectVO.class);
        if (projectVOS == null) {
            return new ArrayList<>();
        }
        return projectVOS;
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    @Saga(code = SagaTopicCodeConstants.DEVOPS_IMPORT_INTERNAL_APPLICATION_SERVICE,
            description = "Devops创建应用服务", inputSchema = "{}")
    public void importAppServiceInternal(Long projectId, List<ApplicationImportInternalVO> importInternalVOS) {
        ProjectDTO projectDTO = baseServiceClientOperator.queryIamProjectById(projectId);
        OrganizationDTO organizationDTO = baseServiceClientOperator.queryOrganizationById(projectDTO.getOrganizationId());
        UserAttrVO userAttrVO = userAttrService.queryByUserId(TypeUtil.objToLong(GitUserNameUtil.getUserId()));
        importInternalVOS.forEach(importInternalVO -> {
            AppServiceDTO appServiceDTO = new AppServiceDTO();
            appServiceDTO.setProjectId(projectId);
            if (importInternalVO.getAppCode() != null) {
                // 校验application信息的格式
                ApplicationValidator.checkApplicationService(importInternalVO.getAppCode());

                // 校验名称唯一性
                checkName(projectId, importInternalVO.getAppName());

                // 校验code唯一性
                checkCode(projectId, importInternalVO.getAppCode());

                appServiceDTO.setCode(importInternalVO.getAppCode());
                appServiceDTO.setName(importInternalVO.getAppName());
            } else {
                AppServiceDTO oldAppService = baseQuery(importInternalVO.getAppServiceId());
                appServiceDTO.setCode(oldAppService.getCode());
                appServiceDTO.setName(oldAppService.getName());
            }

            appServiceDTO.setProjectId(projectId);
            appServiceDTO.setActive(true);
            appServiceDTO.setSynchro(false);
            appServiceDTO.setIsSkipCheckPermission(true);
            appServiceDTO.setType(NORMAL);
            appServiceDTO = baseCreate(appServiceDTO);

            // 查询创建应用所在的gitlab应用组
            DevopsProjectDTO devopsProjectDTO = devopsProjectService.baseQueryByProjectId(projectId);
            // 查询创建应用所在的gitlab应用组 用户权限
            MemberDTO memberDTO = gitlabGroupMemberService.queryByUserId(
                    TypeUtil.objToInteger(devopsProjectDTO.getDevopsAppGroupId()),
                    TypeUtil.objToInteger(userAttrVO.getGitlabUserId()));
            if (memberDTO == null || !memberDTO.getAccessLevel().equals(AccessLevel.OWNER.value)) {
                throw new CommonException(ERROR_USER_NOT_OWNER);
            }

            AppServiceImportPayload appServiceImportPayload = new AppServiceImportPayload();
            appServiceImportPayload.setAppServiceId(appServiceDTO.getId());
            appServiceImportPayload.setGitlabGroupId(TypeUtil.objToInteger(devopsProjectDTO.getDevopsAppGroupId()));
            appServiceImportPayload.setIamUserId(TypeUtil.objToLong(GitUserNameUtil.getUserId()));
            appServiceImportPayload.setVersionId(importInternalVO.getVersionId());
            appServiceImportPayload.setOrgCode(organizationDTO.getCode());
            appServiceImportPayload.setProjectId(projectId);
            appServiceImportPayload.setProCode(projectDTO.getCode());
            appServiceImportPayload.setOldAppServiceId(importInternalVO.getAppServiceId());
            producer.apply(
                    StartSagaBuilder
                            .newBuilder()
                            .withLevel(ResourceLevel.PROJECT)
                            .withRefType("")
                            .withSagaCode(SagaTopicCodeConstants.DEVOPS_IMPORT_INTERNAL_APPLICATION_SERVICE)
                            .withPayloadAndSerialize(appServiceImportPayload)
                            .withRefId("")
                            .withSourceId(projectId),
                    builder -> {
                    });
        });
    }

    @Override
    public void importAppServiceGitlab(AppServiceImportPayload appServiceImportPayload) {
        AppServiceDTO appServiceDTO = appServiceMapper.selectByPrimaryKey(appServiceImportPayload.getAppServiceId());
        UserAttrDTO userAttrDTO = userAttrService.baseQueryById(appServiceImportPayload.getIamUserId());

        String newGroupName = appServiceImportPayload.getOrgCode() + "-" + appServiceImportPayload.getProCode();
        GitlabProjectDTO gitlabProjectDTO = gitlabServiceClientOperator.queryProjectByName(
                newGroupName,
                appServiceDTO.getCode(),
                TypeUtil.objToInteger(userAttrDTO.getGitlabUserId()));
        //创建gitlab 应用
        if (gitlabProjectDTO.getId() == null) {
            gitlabProjectDTO = gitlabServiceClientOperator.createProject(
                    appServiceImportPayload.getGitlabGroupId(),
                    appServiceDTO.getCode(),
                    TypeUtil.objToInteger(userAttrDTO.getGitlabUserId()), false);
        }

        appServiceDTO.setGitlabProjectId(gitlabProjectDTO.getId());
        String applicationServiceToken = getApplicationToken(appServiceDTO.getGitlabProjectId(), TypeUtil.objToInteger(userAttrDTO.getGitlabUserId()));
        appServiceDTO.setToken(applicationServiceToken);
        appServiceDTO.setSynchro(true);
        appServiceDTO.setFailed(false);
        setProjectHook(appServiceDTO, appServiceDTO.getGitlabProjectId(), applicationServiceToken, TypeUtil.objToInteger(userAttrDTO.getGitlabUserId()));

        String repoUrl = !gitlabUrl.endsWith("/") ? gitlabUrl + "/" : gitlabUrl;
        String repositoryUrl = repoUrl + appServiceImportPayload.getOrgCode() + "-" + appServiceImportPayload.getProCode() + "/" + appServiceDTO.getCode() + GIT;
        cloneAndPushCode(appServiceDTO, userAttrDTO, appServiceImportPayload.getOldAppServiceId(), appServiceImportPayload.getVersionId(), repositoryUrl, newGroupName);

        appServiceMapper.updateByIdSelectiveWithoutAudit(appServiceDTO);
    }

    private void cloneAndPushCode(AppServiceDTO appServiceDTO, UserAttrDTO userAttrDTO, Long oldAppServiceId, Long oldAppServiceVersionId, String repositoryUrl, String newGroupName) {
        AppServiceDTO oldAppServiceDTO = appServiceMapper.selectByPrimaryKey(oldAppServiceId);
        AppServiceVersionDTO oldAppServiceVersionDTO = appServiceVersionService.baseQuery(oldAppServiceVersionId);
        String repoUrl = !gitlabUrl.endsWith("/") ? gitlabUrl + "/" : gitlabUrl;
        String oldGroup;
        if (oldAppServiceDTO.getMktAppId() == null) {
            ProjectDTO oldProjectDTO = baseServiceClientOperator.queryIamProjectById(oldAppServiceDTO.getProjectId());
            OrganizationDTO oldOrganizationDTO = baseServiceClientOperator.queryOrganizationById(oldProjectDTO.getOrganizationId());
            oldGroup = oldOrganizationDTO.getCode() + "-" + oldProjectDTO.getCode();
        } else {
            ApplicationDTO oldApplicationDTO = baseServiceClientOperator.queryAppById(oldAppServiceDTO.getMktAppId());
            oldGroup = String.format(SITE_APP_GROUP_NAME_FORMAT, oldApplicationDTO.getCode());
        }
        //拉取代码
        // 获取push代码所需的access token
        String applicationDir = APPLICATION + System.currentTimeMillis();
        String pushToken = getToken(appServiceDTO.getGitlabProjectId(), applicationDir, userAttrDTO);

        //获取admin的token
        String pullToken = gitlabServiceClientOperator.getAdminToken();
        String oldRepository = repoUrl + oldGroup + "/" + oldAppServiceDTO.getCode() + GIT;
        String workingDirectory = gitUtil.cloneAppMarket(applicationDir, oldAppServiceVersionDTO.getCommit(), oldRepository, pullToken);
        replaceParams(appServiceDTO.getCode(), newGroupName, applicationDir, oldAppServiceDTO.getCode(), oldGroup);
        Git git = gitUtil.initGit(new File(workingDirectory));
        //push 到远程仓库
        GitLabUserDTO gitLabUserDTO = gitlabServiceClientOperator.queryUserById(TypeUtil.objToInteger(userAttrDTO.getGitlabUserId()));
        gitUtil.push(git, applicationDir, "The template version:" + oldAppServiceVersionDTO.getVersion(), repositoryUrl, gitLabUserDTO.getUsername(), pushToken);
    }

    @Override
    public void baseCheckApp(Long projectId, Long appServiceId) {
        AppServiceDTO appServiceDTO = appServiceMapper.selectByPrimaryKey(appServiceId);
        if (appServiceDTO == null || !projectId.equals(appServiceDTO.getProjectId())) {
            throw new CommonException("error.app.project.notMatch");
        }
    }

    @Override
    public AppServiceDTO baseUpdate(AppServiceDTO applicationDTO) {
        AppServiceDTO oldAppServiceDTO = appServiceMapper.selectByPrimaryKey(applicationDTO.getId());
        applicationDTO.setObjectVersionNumber(oldAppServiceDTO.getObjectVersionNumber());
        if (appServiceMapper.updateByPrimaryKeySelective(applicationDTO) != 1) {
            throw new CommonException("error.app.service.update");
        }
        return appServiceMapper.selectByPrimaryKey(applicationDTO.getId());
    }

    @Override
    public void updateApplicationStatus(AppServiceDTO applicationDTO) {
        appServiceMapper.updateApplicationStatus(applicationDTO.getId(), applicationDTO.getToken(),
                applicationDTO.getGitlabProjectId(), applicationDTO.getHookId(), applicationDTO.getSynchro());
    }

    @Override
    public AppServiceDTO baseQuery(Long appServiceId) {
        return appServiceMapper.selectByPrimaryKey(appServiceId);
    }

    @Override
    public PageInfo<AppServiceDTO> basePageByOptions(Long projectId, Boolean isActive, Boolean hasVersion, Boolean
            appMarket, String type, Boolean doPage, PageRequest pageRequest, String params) {

        Map<String, Object> mapParams = TypeUtil.castMapParams(params);
        Long userId = GitUserNameUtil.getUserId().longValue();
        ProjectDTO projectDTO = baseServiceClientOperator.queryIamProjectById(projectId);
        Boolean projectOwner = baseServiceClientOperator.isProjectOwner(userId, projectDTO);
        List<AppServiceDTO> list;
        if (projectOwner) {
            //是否需要分页
            if (doPage == null || doPage) {
                return PageHelper
                        .startPage(pageRequest.getPage(), pageRequest.getSize(), PageRequestUtil.getOrderBy(pageRequest))
                        .doSelectPageInfo(
                                () -> appServiceMapper.list(projectId, isActive, hasVersion, type,
                                        TypeUtil.cast(mapParams.get(TypeUtil.SEARCH_PARAM)),
                                        TypeUtil.cast(mapParams.get(TypeUtil.PARAMS)), PageRequestUtil.checkSortIsEmpty(pageRequest)));
            } else {
                list = appServiceMapper.list(projectId, isActive, hasVersion, type,
                        TypeUtil.cast(mapParams.get(TypeUtil.SEARCH_PARAM)),
                        TypeUtil.cast(mapParams.get(TypeUtil.PARAMS)), PageRequestUtil.checkSortIsEmpty(pageRequest));
            }
        } else {
            //是否需要分页
            if (doPage == null || doPage) {
                return PageHelper
                        .startPage(pageRequest.getPage(), pageRequest.getSize(), PageRequestUtil.getOrderBy(pageRequest))
                        .doSelectPageInfo(
                                () -> appServiceMapper.listProjectMembersAppService(projectId, isActive, hasVersion, type,
                                        TypeUtil.cast(mapParams.get(TypeUtil.SEARCH_PARAM)),
                                        TypeUtil.cast(mapParams.get(TypeUtil.PARAMS)), PageRequestUtil.checkSortIsEmpty(pageRequest), userId));
            } else {
                list = appServiceMapper.listProjectMembersAppService(projectId, isActive, hasVersion, type,
                        TypeUtil.cast(mapParams.get(TypeUtil.SEARCH_PARAM)),
                        TypeUtil.cast(mapParams.get(TypeUtil.PARAMS)), PageRequestUtil.checkSortIsEmpty(pageRequest), userId);
            }
        }

        return new PageInfo<>(list);
    }

    @Override
    public PageInfo<AppServiceDTO> basePageCodeRepository(Long projectId, PageRequest pageRequest, String params,
                                                          Boolean isProjectOwner, Long userId) {
        Map maps = gson.fromJson(params, Map.class);
        return PageHelper.startPage(pageRequest.getPage(), pageRequest.getSize(), PageRequestUtil.getOrderBy(pageRequest)).doSelectPageInfo(() -> appServiceMapper.listCodeRepository(projectId,
                TypeUtil.cast(maps.get(TypeUtil.SEARCH_PARAM)),
                TypeUtil.cast(maps.get(TypeUtil.PARAMS)), isProjectOwner, userId));
    }

    @Override
    public AppServiceDTO baseQueryByCode(String code, Long projectId) {
        AppServiceDTO applicationDTO = new AppServiceDTO();
        applicationDTO.setProjectId(projectId);
        applicationDTO.setCode(code);
        return appServiceMapper.selectOne(applicationDTO);
    }

    @Override
    public AppServiceDTO baseQueryByMktAppId(String code, Long mktAppId) {
        AppServiceDTO applicationDTO = new AppServiceDTO();
        applicationDTO.setMktAppId(mktAppId);
        applicationDTO.setCode(code);
        return appServiceMapper.selectOne(applicationDTO);
    }

    @Override
    public AppServiceDTO baseQueryByCodeWithNullProject(String code) {
        return appServiceMapper.queryByCodeWithNoProject(code);
    }

    @Override
    public List<AppServiceDTO> baseListByEnvId(Long projectId, Long envId, String status) {
        return appServiceMapper.listByEnvId(projectId, envId, null, status);
    }

    @Override
    public PageInfo<AppServiceDTO> basePageByEnvId(Long projectId, Long envId, Long appServiceId, PageRequest pageRequest) {
        return PageHelper.startPage(pageRequest.getPage(), pageRequest.getSize(), PageRequestUtil.getOrderBy(pageRequest)).doSelectPageInfo(() -> appServiceMapper.listByEnvId(projectId, envId, appServiceId, NODELETED));

    }

    @Override
    public List<AppServiceDTO> baseListDeployedApp(Long projectId) {
        return appServiceMapper.listDeployedApp(projectId);
    }

    @Override
    public PageInfo<AppServiceDTO> basePageByActiveAndPubAndHasVersion(Long projectId, Boolean isActive,
                                                                       PageRequest pageRequest, String params) {
        Map<String, Object> searchParam = null;
        List<String> paramList = null;
        if (!StringUtils.isEmpty(params)) {
            Map<String, Object> searchParamMap = json.deserialize(params, Map.class);
            searchParam = TypeUtil.cast(searchParamMap.get(TypeUtil.SEARCH_PARAM));
            paramList = TypeUtil.cast(searchParamMap.get(TypeUtil.PARAMS));
        }
        final Map<String, Object> finalSearchParam = searchParam;
        final List<String> finalParam = paramList;

        return PageHelper.startPage(pageRequest.getPage(), pageRequest.getSize(), PageRequestUtil.getOrderBy(pageRequest)).doSelectPageInfo(() -> appServiceMapper
                .basePageByActiveAndPubAndHasVersion(projectId, isActive, finalSearchParam, finalParam));
    }

    @Override
    public AppServiceDTO baseQueryByToken(String token) {
        return appServiceMapper.queryByToken(token);
    }

    @Override
    public List<AppServiceDTO> baseListByCode(String code) {
        return appServiceMapper.listByCode(code);
    }

    @Override
    public List<AppServiceDTO> baseListByGitLabProjectIds(List<Long> gitLabProjectIds) {
        return appServiceMapper.listByGitLabProjectIds(gitLabProjectIds);
    }

    @Override
    public void baseDelete(Long appServiceId) {
        appServiceMapper.deleteByPrimaryKey(appServiceId);
    }

    @Override
    public List<AppServiceDTO> baseListByProjectIdAndSkipCheck(Long projectId) {
        AppServiceDTO appServiceDTO = new AppServiceDTO();
        appServiceDTO.setProjectId(projectId);
        appServiceDTO.setSkipCheckPermission(true);
        return appServiceMapper.select(appServiceDTO);
    }

    @Override
    public List<AppServiceDTO> baseListByProjectId(Long projectId) {
        AppServiceDTO appServiceDTO = new AppServiceDTO();
        appServiceDTO.setProjectId(projectId);
        return appServiceMapper.select(appServiceDTO);
    }

    @Override
    public void baseUpdateHarborConfig(Long projectId, Long newConfigId, Long oldConfigId, boolean harborPrivate) {
        appServiceMapper.updateHarborConfig(projectId, newConfigId, oldConfigId, harborPrivate);
    }

    @Override
    public String getGitlabUrl(Long projectId, Long appServiceId) {
        AppServiceDTO appServiceDTO = baseQuery(appServiceId);
        if (appServiceDTO.getGitlabProjectId() != null) {
            ProjectDTO projectDTO = baseServiceClientOperator.queryIamProjectById(projectId);
            OrganizationDTO organizationDTO = baseServiceClientOperator.queryOrganizationById(projectDTO.getOrganizationId());
            String urlSlash = gitlabUrl.endsWith("/") ? "" : "/";
            return gitlabUrl + urlSlash
                    + organizationDTO.getCode() + "-" + projectDTO.getCode() + "/"
                    + appServiceDTO.getCode();
        }
        return "";
    }


    private boolean getHistory(Date startTime, Date endTime, SimpleDateFormat sdf, SonarHistroy sonarHistory) {
        try {
            return sdf.parse(sonarHistory.getDate()).compareTo(startTime) >= 0 && sdf.parse(sonarHistory.getDate()).compareTo(endTime) <= 0;
        } catch (ParseException e) {
            throw new CommonException(e);
        }
    }

    private void getRate(SonarContentVO sonarContentVO, List<Facet> facets) {
        sonarContentVO.setRate("A");
        facets.stream().filter(facet -> facet.getProperty().equals(SEVERITIES)).forEach(facet -> {
            facet.getValues().forEach(value -> {
                if (value.getVal().equals(Rate.MINOR.getRate()) && value.getCount() >= 1) {
                    if (sonarContentVO.getRate().equals("A")) {
                        sonarContentVO.setRate("B");
                    }
                }
                if (value.getVal().equals(Rate.MAJOR.getRate()) && value.getCount() >= 1) {
                    if (!sonarContentVO.getRate().equals("D") && !sonarContentVO.getRate().equals("E")) {
                        sonarContentVO.setRate("C");
                    }
                }
                if (value.getVal().equals(Rate.CRITICAL.getRate()) && value.getCount() >= 1) {
                    if (!sonarContentVO.getRate().equals("E")) {
                        sonarContentVO.setRate("D");
                    }
                }
                if (value.getVal().equals(Rate.BLOCKER.getRate()) && value.getCount() >= 1) {
                    sonarContentVO.setRate("E");
                }
            });
        });
    }

    private Map<String, String> getQueryMap(String key, String type, Boolean newAdd) {
        Map<String, String> map = new HashMap<>();
        map.put("componentKeys", key);
        map.put("s", "FILE_LINE");
        map.put("resolved", "false");
        map.put("types", type);
        if (newAdd) {
            map.put("sinceLeakPeriod", "true");
        }
        map.put("ps", "100");
        map.put("facets", "severities,types");
        map.put("additionalFields", "_all");
        return map;
    }

    @Override
    public AppServiceDTO getApplicationServiceDTO(Long projectId, AppServiceReqVO appServiceReqVO) {
        AppServiceDTO appServiceDTO = ConvertUtils.convertObject(appServiceReqVO, AppServiceDTO.class);
        checkName(projectId, appServiceDTO.getName());
        checkCode(projectId, appServiceDTO.getCode());
        appServiceDTO.setActive(true);
        appServiceDTO.setSynchro(false);
        appServiceDTO.setProjectId(projectId);
        // 创建服务默认跳过权限校验
        appServiceDTO.setSkipCheckPermission(Boolean.TRUE);
        appServiceDTO.setHarborConfigId(appServiceReqVO.getHarborConfigId());
        appServiceDTO.setChartConfigId(appServiceReqVO.getChartConfigId());
        return appServiceDTO;
    }

    @Override
    public AppServiceDTO baseCreate(AppServiceDTO appServiceDTO) {
        if (appServiceMapper.insert(appServiceDTO) != 1) {
            throw new CommonException("error.application.create.insert");
        }
        return appServiceDTO;
    }

    @Override
    public PageInfo<AppServiceGroupInfoVO> pageAppServiceByMode(Long projectId, Boolean share, Long searchProjectId, String param, PageRequest pageRequest) {

        List<AppServiceGroupInfoVO> appServiceGroupInfoVOS = new ArrayList<>();
        if (Boolean.TRUE.equals(share)) {
            Long organizationId = baseServiceClientOperator.queryIamProjectById(projectId).getOrganizationId();
            List<Long> projectIds = new ArrayList<>();
            if (ObjectUtils.isEmpty(searchProjectId)) {
                projectIds = baseServiceClientOperator.listIamProjectByOrgId(organizationId).stream()
                        .filter(v -> v.getEnabled())
                        .filter(v -> !projectId.equals(v.getId()))
                        .map(ProjectDTO::getId).collect(Collectors.toList());
            } else {
                projectIds.add(searchProjectId);
            }
            //查询组织共享和共享项目的应用服务
            List<AppServiceDTO> organizationAppServices = appServiceMapper.queryOrganizationShareApps(projectIds, param, searchProjectId);
            List<AppServiceDTO> projectAppServices = appServiceMapper.listShareProjectApps(projectId, param, searchProjectId);
            organizationAppServices.addAll(projectAppServices);
            // 去重
            ArrayList<AppServiceDTO> collect = organizationAppServices.stream().collect(collectingAndThen(
                    toCollection(() -> new TreeSet<>(comparing(AppServiceDTO::getId))), ArrayList::new));
            initAppServiceGroupInfoVOList(appServiceGroupInfoVOS, collect, share, projectId);
        } else {
            ProjectDTO projectDTO = baseServiceClientOperator.queryIamProjectById(projectId);
            List<Long> appServiceIds = baseServiceClientOperator.listServicesForMarket(projectDTO.getOrganizationId(), false);
            if (appServiceIds != null && !appServiceIds.isEmpty()) {
                List<AppServiceDTO> marketServices = appServiceMapper.queryMarketDownloadApps(null, param, appServiceIds,searchProjectId);
                List<AppServiceDTO> appServiceDTOList = marketServices.stream().filter(v -> !ObjectUtils.isEmpty(v.getMktAppId())).collect(Collectors.toList());
                initAppServiceGroupInfoVOList(appServiceGroupInfoVOS, appServiceDTOList, share, projectId);
            }

        }

        return PageInfoUtil.createPageFromList(appServiceGroupInfoVOS, pageRequest);
    }

    private void initAppServiceGroupInfoVOList(List<AppServiceGroupInfoVO> appServiceGroupInfoVOS, List<AppServiceDTO> appServiceDTOList, Boolean share, Long projectId) {

        if (appServiceDTOList.isEmpty()) return;
        // 获取应用服务id集合
        Set<Long> appServiceIds = appServiceDTOList.stream().map(AppServiceDTO::getId).collect(Collectors.toSet());
        List<ProjectDTO> projects = new ArrayList<>();
        List<AppServiceVersionDTO> versionList = new ArrayList<>();
        if (share) {
            Set<Long> projectIds = appServiceDTOList.stream().map(AppServiceDTO::getProjectId).collect(Collectors.toSet());
            projects = baseServiceClientOperator.queryProjectsByIds(projectIds);
            // 分别查询组织共享和共享到项目版本信息
            List<AppServiceVersionDTO> appServiceVersionDTOS = appServiceVersionService.listServiceVersionByAppServiceIds(appServiceIds, "share", projectId);
            List<AppServiceVersionDTO> appServiceVersionDTOS1 = appServiceVersionService.listServiceVersionByAppServiceIds(appServiceIds, "project", projectId);
            appServiceVersionDTOS.addAll(appServiceVersionDTOS1);
            // 去掉重复版本信息
            versionList = appServiceVersionDTOS.stream().collect(collectingAndThen(
                    toCollection(() -> new TreeSet<>(Comparator.comparing(AppServiceVersionDTO::getId))), ArrayList::new));
        } else {
            List<AppServiceVersionDTO> versionListTemp = new ArrayList<>();
            ProjectDTO projectDTO = baseServiceClientOperator.queryIamProjectById(projectId);
            List<Long> appServiceVersionIds = baseServiceClientOperator.listServiceVersionsForMarket(projectDTO.getOrganizationId(), false);
            List<ApplicationDTO> applicationDTOS = new ArrayList<>();
            if (appServiceIds != null && !appServiceIds.isEmpty() && appServiceVersionIds != null && !appServiceVersionIds.isEmpty()) {
                versionList.addAll(appServiceVersionMapper.listByAppServiceVersionIdForMarketBatch(new ArrayList<>(appServiceIds), appServiceVersionIds, null, null, null, null));
                Set<Long> appMktIds = appServiceDTOList.stream().map(AppServiceDTO::getMktAppId).collect(Collectors.toSet());
                appMktIds
                        .forEach(appMktId -> {
                            ApplicationDTO applicationDTO = baseServiceClientOperator.queryAppById(appMktId);
                            if (!ObjectUtils.isEmpty(applicationDTO)) {
                                applicationDTOS.add(applicationDTO);
                            }
                        });
            }

            projects = ConvertUtils.convertList(applicationDTOS, ProjectDTO.class);
        }
        // 将版本信息集合和项目信息集合 转为 Map类型
        Map<Long, List<AppServiceVersionDTO>> versionMap = versionList.stream().collect(Collectors.groupingBy(AppServiceVersionDTO::getAppServiceId));
        Map<Long, ProjectDTO> projectDTOMap = new HashMap<>();
        if (!CollectionUtils.isEmpty(projects)) {
            projectDTOMap = projects.stream().collect(Collectors.toMap(ProjectDTO::getId, Function.identity()));
        }

        Map<Long, ProjectDTO> finalProjectDTOMap = projectDTOMap;
        appServiceDTOList.stream().forEach(appServiceDTO -> {
            // 根据应用服务的ID查询出versionList中对应的版本信息
            List<AppServiceVersionDTO> appServiceVersionDTOS = versionMap.get(appServiceDTO.getId());
            // 应用服务的版本不为空才加入List
            if (!CollectionUtils.isEmpty(appServiceVersionDTOS)) {
                AppServiceGroupInfoVO appServiceGroupInfoVO = dtoToGroupInfoVO(appServiceDTO);
                ProjectDTO projectDTO = new ProjectDTO();
                if (share) {
                    projectDTO = finalProjectDTOMap.get(appServiceDTO.getProjectId());
                    appServiceGroupInfoVO.setShare(true);
                } else {
                    projectDTO = finalProjectDTOMap.get(appServiceDTO.getMktAppId());
                    appServiceGroupInfoVO.setShare(false);
                }
                if (!ObjectUtils.isEmpty(projectDTO)) {
                    appServiceGroupInfoVO.setProjectName(projectDTO.getName());
                }
                if (!CollectionUtils.isEmpty(appServiceVersionDTOS)) {
                    // 获取项目信息，并传入项目名
                    appServiceGroupInfoVO.setVersions(appServiceVersionDTOS);
                }
                appServiceGroupInfoVOS.add(appServiceGroupInfoVO);
            }
        });
    }

    @Override
    public List<AppServiceGroupVO> listAllAppServices(Long projectId, String type, String param, Boolean deployOnly, String serviceType) {
        List<AppServiceDTO> list = new ArrayList<>();
        List<String> params = new ArrayList<>();
        List<AppServiceGroupVO> appServiceGroupList = new ArrayList<>();
        if (param != null && !param.isEmpty()) {
            params.add(param);
        }
        switch (type) {
            case NORMAL_SERVICE: {
                list.addAll(appServiceMapper.list(projectId, null, true, serviceType, null, params, ""));
                AppServiceGroupVO appServiceGroupVO = new AppServiceGroupVO();
                appServiceGroupVO.setAppServiceList(ConvertUtils.convertList(list, this::dtoToGroupInfoVO));
                appServiceGroupList.add(appServiceGroupVO);
                break;
            }
            case SHARE_SERVICE: {
                Long organizationId = baseServiceClientOperator.queryIamProjectById(projectId).getOrganizationId();
                List<Long> appServiceIds = new ArrayList<>();
                baseServiceClientOperator.listIamProjectByOrgId(organizationId)
                        .forEach(pro ->
                                baseListAll(pro.getId()).forEach(appServiceDTO -> appServiceIds.add(appServiceDTO.getId()))
                        );
                list.addAll(appServiceMapper.listShareApplicationService(appServiceIds, projectId, serviceType, params));
                Map<Long, List<AppServiceGroupInfoVO>> map = list.stream()
                        .filter(v -> !projectId.equals(v.getProjectId()))
                        .map(this::dtoToGroupInfoVO)
                        .collect(Collectors.groupingBy(AppServiceGroupInfoVO::getProjectId));

                for (Map.Entry<Long, List<AppServiceGroupInfoVO>> entry : map.entrySet()) {
                    ProjectDTO projectDTO = baseServiceClientOperator.queryIamProjectById(entry.getKey());
                    AppServiceGroupVO appServiceGroupVO = new AppServiceGroupVO();
                    appServiceGroupVO.setName(projectDTO.getName());
                    appServiceGroupVO.setCode(projectDTO.getCode());
                    appServiceGroupVO.setId(projectDTO.getId());
                    appServiceGroupVO.setAppServiceList(entry.getValue());
                    appServiceGroupList.add(appServiceGroupVO);
                }
                break;
            }
            case MARKET_SERVICE: {
                ProjectDTO projectDTO = baseServiceClientOperator.queryIamProjectById(projectId);
                List<Long> appServiceIds = baseServiceClientOperator.listServicesForMarket(projectDTO.getOrganizationId(), deployOnly);
                if (appServiceIds != null && !appServiceIds.isEmpty()) {
                    list.addAll(appServiceMapper.queryMarketDownloadApps(serviceType, null, appServiceIds,null));
                    Map<Long, List<AppServiceGroupInfoVO>> map = list.stream()
                            .map(this::dtoToGroupInfoVO)
                            .filter(appServiceGroupInfoVO -> appServiceGroupInfoVO.getMktAppId() != null)
                            .collect(Collectors.groupingBy(AppServiceGroupInfoVO::getMktAppId));

                    for (Map.Entry<Long, List<AppServiceGroupInfoVO>> entry : map.entrySet()) {
                        ApplicationDTO applicationDTO = baseServiceClientOperator.queryAppById(entry.getKey());
                        AppServiceGroupVO appServiceGroupVO = new AppServiceGroupVO();
                        appServiceGroupVO.setName(applicationDTO.getName());
                        appServiceGroupVO.setCode(applicationDTO.getCode());
                        appServiceGroupVO.setId(applicationDTO.getId());
                        appServiceGroupVO.setAppServiceList(entry.getValue());
                        appServiceGroupList.add(appServiceGroupVO);
                    }
                }
                break;
            }
            default: {
                throw new CommonException("error.list.deploy.app.service.type");
            }
        }
        return appServiceGroupList;
    }

    private List<AppServiceDTO> baseListAll(Long projectId) {
        AppServiceDTO appServiceDTO = new AppServiceDTO();
        appServiceDTO.setProjectId(projectId);
        return appServiceMapper.select(appServiceDTO);
    }

    private AppServiceDTO fromImportVoToDto(AppServiceImportVO appServiceImportVO) {
        AppServiceDTO appServiceDTO = new AppServiceDTO();
        BeanUtils.copyProperties(appServiceImportVO, appServiceDTO);
        appServiceDTO.setHarborConfigId(appServiceImportVO.getHarborConfigId());
        appServiceDTO.setChartConfigId(appServiceImportVO.getChartConfigId());
        return appServiceDTO;
    }

    private AppServiceGroupInfoVO dtoToGroupInfoVO(AppServiceDTO appServiceDTO) {
        AppServiceGroupInfoVO appServiceGroupInfoVO = new AppServiceGroupInfoVO();
        BeanUtils.copyProperties(appServiceDTO, appServiceGroupInfoVO);
        return appServiceGroupInfoVO;
    }

    private AppServiceGroupVO dtoToGroupVO(ApplicationDTO applicationDTO) {
        AppServiceGroupVO appServiceGroupVO = new AppServiceGroupVO();
        BeanUtils.copyProperties(applicationDTO, appServiceGroupVO);
        BeanUtils.copyProperties(applicationDTO, appServiceGroupVO);
        return appServiceGroupVO;
    }

    /**
     * ensure the repository url and access token are valid.
     *
     * @param gitPlatformType git platform type
     * @param repositoryUrl   repository url
     * @param accessToken     access token (Nullable)
     */
    private void checkRepositoryUrlAndToken(GitPlatformType gitPlatformType, String repositoryUrl, String
            accessToken) {
        Boolean validationResult = validateRepositoryUrlAndToken(gitPlatformType, repositoryUrl, accessToken);
        if (Boolean.FALSE.equals(validationResult)) {
            throw new CommonException("error.repository.token.invalid");
        } else if (validationResult == null) {
            throw new CommonException("error.repository.empty");
        }
    }

    private void initBranch(DevOpsAppServicePayload devOpsAppServicePayload, AppServiceDTO appServiceDTO, String branchName) {
        CommitDTO commitDTO = gitlabServiceClientOperator.queryCommit(devOpsAppServicePayload.getGitlabProjectId(), branchName, devOpsAppServicePayload.getUserId());
        DevopsBranchDTO devopsBranchDTO = new DevopsBranchDTO();
        devopsBranchDTO.setUserId(TypeUtil.objToLong(devOpsAppServicePayload.getUserId()));
        devopsBranchDTO.setAppServiceId(appServiceDTO.getId());
        devopsBranchDTO.setBranchName(branchName);
        devopsBranchDTO.setCheckoutCommit(commitDTO.getId());
        devopsBranchDTO.setCheckoutDate(commitDTO.getCommittedDate());
        devopsBranchDTO.setLastCommitUser(TypeUtil.objToLong(devOpsAppServicePayload.getUserId()));
        devopsBranchDTO.setLastCommitMsg(commitDTO.getMessage());
        devopsBranchDTO.setLastCommitDate(commitDTO.getCommittedDate());
        devopsBranchDTO.setLastCommit(commitDTO.getId());
        devopsBranchService.baseCreate(devopsBranchDTO);
    }

    private void replaceParams(String newServiceCode,
                               String newGroupName,
                               String applicationDir,
                               String oldServiceCode,
                               String oldGroupName) {
        try {
            File file = new File(gitUtil.getWorkingDirectory(applicationDir));
            Map<String, String> params = new HashMap<>();
            params.put("{{group.name}}", newGroupName);
            params.put("{{service.code}}", newServiceCode);
            params.put("the-oldService-name", oldServiceCode);
            params.put(oldGroupName, newGroupName);
            params.put(oldServiceCode, newServiceCode);
            FileUtil.replaceReturnFile(file, params);
        } catch (Exception e) {
            //删除模板
            gitUtil.deleteWorkingDirectory(applicationDir);
            throw new CommonException(e.getMessage(), e);
        }
    }


    @Override
    public String getToken(Integer gitlabProjectId, String applicationDir, UserAttrDTO userAttrDTO) {
        String accessToken = userAttrDTO.getGitlabToken();
        if (accessToken == null) {
            accessToken = gitlabServiceClientOperator.createProjectToken(gitlabProjectId,
                    applicationDir, TypeUtil.objToInteger(userAttrDTO.getGitlabUserId()));
            userAttrDTO.setGitlabToken(accessToken);
            userAttrService.baseUpdate(userAttrDTO);
        }
        return accessToken;
    }

    @Override
    public PageInfo<AppServiceVO> listAppByProjectId(Long projectId, Boolean doPage, PageRequest pageRequest, String params) {
        Map<String, Object> mapParams = TypeUtil.castMapParams(params);
        List<AppServiceDTO> appServiceDTOList = appServiceMapper.pageServiceByProjectId(projectId,
                TypeUtil.cast(mapParams.get(TypeUtil.SEARCH_PARAM)),
                TypeUtil.cast(mapParams.get(TypeUtil.PARAMS)));
        List<AppServiceVO> list = ConvertUtils.convertList(appServiceDTOList, AppServiceVO.class);
        if (doPage) {
            return PageInfoUtil.createPageFromList(list, pageRequest);
        } else {
            return new PageInfo<>(list);
        }

    }

    @Override
    public PageInfo<AppServiceVO> listAppServiceByIds(Set<Long> ids, Boolean doPage, PageRequest pageRequest, String params) {
        Map<String, Object> mapParams = TypeUtil.castMapParams(params);

        List<AppServiceDTO> appServiceDTOList = appServiceMapper.listAppServiceByIds(ids,
                TypeUtil.cast(mapParams.get(TypeUtil.SEARCH_PARAM)),
                TypeUtil.cast(mapParams.get(TypeUtil.PARAMS)));
        List<AppServiceVersionDTO> appServiceVersionDTOS = appServiceVersionService.listServiceVersionByAppServiceIds(ids, null, null);
        Map<Long, List<AppServiceVersionDTO>> appVerisonMap = appServiceVersionDTOS.stream().collect(Collectors.groupingBy(AppServiceVersionDTO::getAppServiceId));
        List<AppServiceVO> collect = appServiceDTOList.stream()
                .filter(v -> !CollectionUtils.isEmpty(appVerisonMap.get(v.getId())))
                .map(appServiceDTO -> dtoTOVo(appServiceDTO, appVerisonMap))
                .collect(Collectors.toList());
        List<AppServiceVO> appServiceVOS = appServiceDTOList.stream()
                .filter(v -> CollectionUtils.isEmpty(appVerisonMap.get(v.getId())))
                .map(appServiceDTO -> dtoTOVo(appServiceDTO, appVerisonMap))
                .collect(Collectors.toList());
        collect.addAll(appServiceVOS);
        if (doPage == null || doPage) {
            if (ObjectUtils.isEmpty(pageRequest.getSize())) {
                pageRequest.setSize(20);
            }
            return PageInfoUtil.createPageFromList(collect, pageRequest);
        } else {
            return new PageInfo<>(collect);
        }
    }

    @Override
    public List<ProjectVO> listProjectByShare(Long projectId, Boolean share) {
        List<AppServiceDTO> appServiceDTOList = new ArrayList<>();

        List<ProjectDTO> projectDTOS = new ArrayList<>();
        if (!StringUtils.isEmpty(share) && share) {
            Long organizationId = baseServiceClientOperator.queryIamProjectById(projectId).getOrganizationId();
            List<Long> projectIds = baseServiceClientOperator.listIamProjectByOrgId(organizationId).stream()
                    .filter(v -> v.getEnabled())
                    .filter(v -> !projectId.equals(v.getId()))
                    .map(ProjectDTO::getId).collect(Collectors.toList());
            List<AppServiceDTO> organizationAppServices = appServiceMapper.queryOrganizationShareApps(projectIds, null, null);
            List<AppServiceDTO> projectAppServices = appServiceMapper.listShareProjectApps(projectId, null, null);
            organizationAppServices.addAll(projectAppServices);
            if(!CollectionUtils.isEmpty(organizationAppServices)) {
            // 去重
            ArrayList<AppServiceDTO> collect = organizationAppServices.stream().collect(collectingAndThen(
                    toCollection(() -> new TreeSet<>(comparing(AppServiceDTO::getId))), ArrayList::new));
            Set<Long> ids = collect.stream().map(AppServiceDTO::getId).collect(Collectors.toSet());
            Map<Long, List<AppServiceVersionDTO>> versionMap = appServiceVersionService.listServiceVersionByAppServiceIds(ids, "share", null)
                    .stream().collect(groupingBy(AppServiceVersionDTO::getAppServiceId));
            Map<Long, List<AppServiceVersionDTO>> versionMap1 = appServiceVersionService.listServiceVersionByAppServiceIds(ids, "project", null)
                    .stream().collect(groupingBy(AppServiceVersionDTO::getAppServiceId));
            versionMap.putAll(versionMap1);
            Set<Long> projectsSet = collect.stream()
                    .filter(v -> !CollectionUtils.isEmpty(versionMap.get(v.getId())))
                    .map(AppServiceDTO::getProjectId).collect(Collectors.toSet());
            projectDTOS = baseServiceClientOperator.queryProjectsByIds(projectsSet);
            }
        } else {
            ProjectDTO projectDTO = baseServiceClientOperator.queryIamProjectById(projectId);
            List<Long> mktAppServiceIds = baseServiceClientOperator.listServicesForMarket(projectDTO.getOrganizationId(), false);
            if (mktAppServiceIds != null && !mktAppServiceIds.isEmpty()) {
                appServiceDTOList = appServiceMapper.queryMarketDownloadApps(null, null, mktAppServiceIds,null);
                Set<Long> appServiceIds = appServiceDTOList.stream().filter(v -> !ObjectUtils.isEmpty(v.getMktAppId())).map(AppServiceDTO::getMktAppId).collect(Collectors.toSet());
                List<ApplicationDTO> applicationDTOS = new ArrayList<>();
                appServiceIds.forEach(v -> {
                    ApplicationDTO applicationDTO = baseServiceClientOperator.queryAppById(v);
                    if (!ObjectUtils.isEmpty(applicationDTO)) {
                        applicationDTOS.add(applicationDTO);
                    }
                });
                if (!CollectionUtils.isEmpty(applicationDTOS)) {
                    projectDTOS = ConvertUtils.convertList(applicationDTOS, ProjectDTO.class);
                }
            }
        }
        return ConvertUtils.convertList(projectDTOS, ProjectVO.class);
    }

    @Override
    public List<AppServiceVO> listServiceByVersionIds(Set<Long> ids) {
        List<AppServiceDTO> appServiceDTOList = appServiceMapper.listServiceByVersionIds(ids);
        return ConvertUtils.convertList(appServiceDTOList, AppServiceVO.class);
    }

    private AppServiceVO dtoTOVo(AppServiceDTO appServiceDTO, Map<Long, List<AppServiceVersionDTO>> appVerisonMap) {
        AppServiceVO appServiceVO = new AppServiceVO();
        BeanUtils.copyProperties(appServiceDTO, appServiceVO);
        if (!appVerisonMap.isEmpty()) {
            appServiceVO.setAllAppServiceVersions(ConvertUtils.convertList(appVerisonMap.get(appServiceVO.getId()),
                    AppServiceVersionVO.class));
        }
        if (appServiceDTO.getFailed() != null && appServiceDTO.getFailed()) {
            appServiceVO.setStatus(AppServiceStatus.FAILED.getStatus());
        } else if (appServiceDTO.getActive() != null && !appServiceDTO.getActive()) {
            appServiceVO.setStatus(AppServiceStatus.DISABLE.getStatus());
        } else if ((appServiceDTO.getActive() != null && appServiceDTO.getActive()) && (appServiceDTO.getSynchro() != null && appServiceDTO.getSynchro()) && (appServiceDTO.getFailed() == null || !appServiceDTO.getFailed())) {
            appServiceVO.setStatus(AppServiceStatus.ENABLE.getStatus());
        } else if (appServiceDTO.getSynchro() != null && !appServiceDTO.getSynchro() && (!appServiceDTO.getFailed() || appServiceDTO.getFailed() == null)) {
            appServiceVO.setStatus(AppServiceStatus.ESTABLISH.getStatus());
        }
        return appServiceVO;
    }

    /**
     * 释放资源
     */
    private void releaseResources(File applicationWorkDir, Git repositoryGit) {
        if (repositoryGit != null) {
            repositoryGit.close();
        }
        FileUtil.deleteDirectory(applicationWorkDir);
    }


    /**
     * get application token (set a token if there is not one in gitlab)
     *
     * @param projectId gitlab project id
     * @param userId    gitlab user id
     * @return the application token that is stored in gitlab variables
     */
    private String getApplicationToken(Integer projectId, Integer userId) {
        List<VariableDTO> variables = gitlabServiceClientOperator.listVariable(projectId, userId);
        if (variables.isEmpty()) {
            String token = GenerateUUID.generateUUID();
            gitlabServiceClientOperator.createVariable(projectId, "Token", token, false, userId);
            return token;
        } else {
            return variables.get(0).getValue();
        }
    }

    /**
     * 处理当前项目成员对于此gitlab应用的权限
     *
     * @param devOpsAppServicePayload 此次操作相关信息
     */
    private void operateGitlabMemberPermission(DevOpsAppServicePayload devOpsAppServicePayload) {
        List<Long> iamUserIds = null;
        // 不跳过权限检查，则为gitlab项目分配项目成员权限
        if (!devOpsAppServicePayload.getSkipCheckPermission()) {
            iamUserIds = devOpsAppServicePayload.getUserIds();
        } else {
            // 跳过权限检查，项目下所有成员自动分配权限
            iamUserIds = baseServiceClientOperator.getAllMemberIdsWithoutOwner(devOpsAppServicePayload.getIamProjectId());
        }
        if (iamUserIds != null && !iamUserIds.isEmpty()) {
            List<UserAttrDTO> userAttrDTOList = userAttrService.baseListByUserIds(iamUserIds);
            userAttrDTOList.forEach(userAttrDTO -> {
                MemberDTO memberDTO = gitlabServiceClientOperator.queryGroupMember(devOpsAppServicePayload.getGroupId(), TypeUtil.objToInteger(userAttrDTO.getGitlabUserId()));
                if (memberDTO != null) {
                    //删除group中的权限
                    gitlabServiceClientOperator.deleteGroupMember(devOpsAppServicePayload.getGroupId(), TypeUtil.objToInteger(userAttrDTO.getGitlabUserId()));
                    List<Long> gitlabProjectIds = appServiceMapper.listGitlabProjectIdByAppPermission(TypeUtil.objToLong(devOpsAppServicePayload.getGroupId()), userAttrDTO.getIamUserId());
                    if (gitlabProjectIds != null && !gitlabProjectIds.isEmpty()) {
                        gitlabProjectIds.forEach(gitlabProjectId -> {
                            MemberDTO gitlabMemberDTO = gitlabServiceClientOperator.getProjectMember(devOpsAppServicePayload.getGitlabProjectId(), TypeUtil.objToInteger(userAttrDTO.getGitlabUserId()));
                            if (gitlabMemberDTO == null || gitlabMemberDTO.getId() == null) {
                                gitlabServiceClientOperator.createProjectMember(devOpsAppServicePayload.getGitlabProjectId(),
                                        new MemberDTO(TypeUtil.objToInteger(userAttrDTO.getGitlabUserId()), 30, ""));
                            }

                        });
                    }
                }else {
                    MemberDTO gitlabMemberDTO = gitlabServiceClientOperator.getProjectMember(devOpsAppServicePayload.getGitlabProjectId(), TypeUtil.objToInteger(userAttrDTO.getGitlabUserId()));
                    if (gitlabMemberDTO == null || gitlabMemberDTO.getId() == null) {
                        gitlabServiceClientOperator.createProjectMember(devOpsAppServicePayload.getGitlabProjectId(),
                                new MemberDTO(TypeUtil.objToInteger(userAttrDTO.getGitlabUserId()), 30, ""));
                    }
                }
            });
        }
    }


    /**
     * set project hook id for application
     *
     * @param appServiceDTO the application entity
     * @param projectId     the gitlab project id
     * @param token         the token for project hook
     * @param userId        the gitlab user id
     */
    @Override
    public void setProjectHook(AppServiceDTO appServiceDTO, Integer projectId, String token, Integer userId) {
        ProjectHookDTO projectHookDTO = ProjectHookDTO.allHook();
        projectHookDTO.setEnableSslVerification(true);
        projectHookDTO.setProjectId(projectId);
        projectHookDTO.setToken(token);
        String uri = !gatewayUrl.endsWith("/") ? gatewayUrl + "/" : gatewayUrl;
        uri += "devops/webhook";
        projectHookDTO.setUrl(uri);
        List<ProjectHookDTO> projectHookDTOS = gitlabServiceClientOperator.listProjectHook(projectId, userId);
        if (projectHookDTOS.isEmpty()) {
            appServiceDTO.setHookId(TypeUtil.objToLong(gitlabServiceClientOperator.createWebHook(
                    projectId, userId, projectHookDTO)
                    .getId()));
        } else {
            appServiceDTO.setHookId(TypeUtil.objToLong(projectHookDTOS.get(0).getId()));
        }
    }


    private void initApplicationParams(ProjectDTO projectDTO, OrganizationDTO
            organizationDTO, List<AppServiceDTO> applicationDTOS, String urlSlash) {
        List<String> projectKeys = new ArrayList<>();
        if (!sonarqubeUrl.equals("")) {
            SonarClient sonarClient = RetrofitHandler.getSonarClient(sonarqubeUrl, SONAR, userName, password);

            //校验sonarqube地址是否正确
            try {
                sonarClient.getUser().execute();
            } catch (IOException e) {
                if (e.getCause().getMessage().equals("Connection refused: connect")) {
                    throw new CommonException("error.connect.sonarqube.fail");
                }
            }

            try {
                Response<Projects> projectsResponse = sonarClient.listProject().execute();
                if (projectsResponse != null && projectsResponse.raw().code() == 200) {
                    projectKeys = projectsResponse.body().getComponents().stream().map(Component::getKey).collect(Collectors.toList());
                }
            } catch (IOException e) {
                LOGGER.info(e.getMessage(), e);
            }
        }

        for (AppServiceDTO t : applicationDTOS) {
            if (t.getGitlabProjectId() != null) {
                t.setRepoUrl(
                        gitlabUrl + urlSlash + organizationDTO.getCode() + "-" + projectDTO.getCode() + "/"
                                + t.getCode() + ".git");
                String key = String.format(SONAR_KEY, organizationDTO.getCode(), projectDTO.getCode(), t.getCode());
                if (!projectKeys.isEmpty() && projectKeys.contains(key)) {
                    t.setSonarUrl(sonarqubeUrl);
                }
            }
        }
    }

    private AppServiceRepVO dtoToRepVo(AppServiceDTO appServiceDTO) {
        AppServiceRepVO appServiceRepVO = new AppServiceRepVO();
        BeanUtils.copyProperties(appServiceDTO, appServiceRepVO);
        appServiceRepVO.setFail(appServiceDTO.getFailed());
        IamUserDTO createUser = baseServiceClientOperator.queryUserByUserId(appServiceDTO.getCreatedBy());
        IamUserDTO updateUser = baseServiceClientOperator.queryUserByUserId(appServiceDTO.getLastUpdatedBy());
        if (createUser != null) {
            appServiceRepVO.setCreateUserName(createUser.getRealName());
            appServiceRepVO.setCreateLoginName(createUser.getLoginName());
        }
        if (updateUser != null) {
            appServiceRepVO.setUpdateUserName(updateUser.getRealName());
            appServiceRepVO.setUpdateLoginName(updateUser.getLoginName());
        }
        appServiceRepVO.setGitlabProjectId(TypeUtil.objToLong(appServiceDTO.getGitlabProjectId()));
        return appServiceRepVO;


    }

    private DevopsUserPermissionVO iamUserTOUserPermissionVO(IamUserDTO iamUserDTO, String role, Date creationDate) {
        DevopsUserPermissionVO devopsUserPermissionVO = new DevopsUserPermissionVO();
        devopsUserPermissionVO.setIamUserId(iamUserDTO.getId());
        if (iamUserDTO.getLdap()) {
            devopsUserPermissionVO.setLoginName(iamUserDTO.getLoginName());
        } else {
            devopsUserPermissionVO.setLoginName(iamUserDTO.getEmail());
        }
        devopsUserPermissionVO.setRealName(iamUserDTO.getRealName());
        devopsUserPermissionVO.setRole(role);
        devopsUserPermissionVO.setCreationDate(creationDate);
        return devopsUserPermissionVO;
    }

    private ProjectVO dtoToProjectVO(ProjectDTO projectDTO) {
        ProjectVO projectVO = new ProjectVO();
        BeanUtils.copyProperties(projectDTO, projectVO);
        projectVO.setAppName(projectDTO.getApplicationVO().getName());
        return projectVO;
    }
}