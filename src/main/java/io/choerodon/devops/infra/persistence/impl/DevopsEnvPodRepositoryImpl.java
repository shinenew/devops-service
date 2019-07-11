package io.choerodon.devops.infra.persistence.impl;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.google.common.collect.Lists;
import io.choerodon.base.domain.PageRequest;
import io.choerodon.base.domain.Sort;
import io.choerodon.core.convertor.ConvertHelper;
import io.choerodon.core.convertor.ConvertPageHelper;
import io.choerodon.devops.api.vo.iam.entity.DevopsEnvPodE;
import io.choerodon.devops.domain.application.repository.DevopsEnvPodRepository;
import io.choerodon.devops.infra.util.TypeUtil;
import io.choerodon.devops.infra.dto.DevopsEnvPodDO;
import io.choerodon.devops.infra.mapper.DevopsEnvPodMapper;
import io.kubernetes.client.JSON;
import org.apache.commons.lang.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Created by Zenger on 2018/4/17.
 */
@Service
public class DevopsEnvPodRepositoryImpl implements DevopsEnvPodRepository {

    private static JSON json = new JSON();
    private DevopsEnvPodMapper devopsEnvPodMapper;

    public DevopsEnvPodRepositoryImpl(DevopsEnvPodMapper devopsEnvPodMapper) {
        this.devopsEnvPodMapper = devopsEnvPodMapper;
    }

    @Override
    public DevopsEnvPodE get(Long id) {
        return ConvertHelper.convert(devopsEnvPodMapper.selectByPrimaryKey(id), DevopsEnvPodE.class);
    }

    @Override
    public DevopsEnvPodE get(DevopsEnvPodE pod) {
        List<DevopsEnvPodDO> devopsEnvPodDOS =
                devopsEnvPodMapper.select(ConvertHelper.convert(pod, DevopsEnvPodDO.class));
        if (devopsEnvPodDOS.isEmpty()) {
            return null;
        }
        return ConvertHelper.convert(devopsEnvPodDOS.get(0),
                DevopsEnvPodE.class);
    }

    @Override
    public void insert(DevopsEnvPodE devopsEnvPodE) {
        DevopsEnvPodDO devopsEnvPodDO = new DevopsEnvPodDO();
        devopsEnvPodDO.setName(devopsEnvPodE.getName());
        devopsEnvPodDO.setNamespace(devopsEnvPodE.getNamespace());
        if (devopsEnvPodMapper.selectOne(devopsEnvPodDO) == null) {
            DevopsEnvPodDO pod = ConvertHelper.convert(devopsEnvPodE, DevopsEnvPodDO.class);
            devopsEnvPodMapper.insert(pod);
        }
    }

    @Override
    public void update(DevopsEnvPodE devopsEnvPodE) {
        devopsEnvPodMapper.updateByPrimaryKey(ConvertHelper.convert(devopsEnvPodE, DevopsEnvPodDO.class));
    }

    @Override
    public List<DevopsEnvPodE> selectByInstanceId(Long instanceId) {
        DevopsEnvPodDO devopsEnvPodDO = new DevopsEnvPodDO();
        devopsEnvPodDO.setAppInstanceId(instanceId);
        return ConvertHelper.convertList(devopsEnvPodMapper.select(devopsEnvPodDO), DevopsEnvPodE.class);
    }

    @Override
    public PageInfo<DevopsEnvPodE> listAppPod(Long projectId, Long envId, Long appId, Long instanceId, PageRequest pageRequest, String searchParam) {

        Sort sort = pageRequest.getSort();
        String sortResult = "";
        if (sort != null) {
            sortResult = Lists.newArrayList(pageRequest.getSort().iterator()).stream()
                    .map(t -> {
                        String property = t.getProperty();
                        if (property.equals("name")) {
                            property = "dp.`name`";
                        } else if (property.equals("ip")) {
                            property = "dp.ip";
                        } else if (property.equals("creationDate")) {
                            property = "dp.creation_date";
                        }

                        return property + " " + t.getDirection();
                    })
                    .collect(Collectors.joining(","));
        }
        PageInfo<DevopsEnvPodDO> devopsEnvPodDOPage;
        if (!StringUtils.isEmpty(searchParam)) {
            Map<String, Object> searchParamMap = json.deserialize(searchParam, Map.class);
            devopsEnvPodDOPage = PageHelper.startPage(
                    pageRequest.getPage(), pageRequest.getSize(), sortResult).doSelectPageInfo(() -> devopsEnvPodMapper.listAppPod(
                    projectId,
                    envId,
                    appId,
                    instanceId,
                    TypeUtil.cast(searchParamMap.get(TypeUtil.SEARCH_PARAM)),
                    TypeUtil.cast(searchParamMap.get(TypeUtil.PARAM))));
        } else {
            devopsEnvPodDOPage = PageHelper.startPage(
                    pageRequest.getPage(), pageRequest.getSize(), sortResult).doSelectPageInfo(() -> devopsEnvPodMapper.listAppPod(projectId, envId, appId, instanceId, null, null));
        }

        return ConvertPageHelper.convertPageInfo(devopsEnvPodDOPage, DevopsEnvPodE.class);
    }

    @Override
    public void deleteByName(String name, String namespace) {
        DevopsEnvPodDO devopsEnvPodDO = new DevopsEnvPodDO();
        devopsEnvPodDO.setName(name);
        devopsEnvPodDO.setNamespace(namespace);
        List<DevopsEnvPodDO> devopsEnvPodDOs = devopsEnvPodMapper.select(devopsEnvPodDO);
        if (!devopsEnvPodDOs.isEmpty()) {
            devopsEnvPodMapper.delete(devopsEnvPodDOs.get(0));
        }
    }

    @Override
    public DevopsEnvPodE getByNameAndEnv(String name, String namespace) {
        DevopsEnvPodDO devopsEnvPodDO = new DevopsEnvPodDO();
        devopsEnvPodDO.setName(name);
        devopsEnvPodDO.setNamespace(namespace);
        return ConvertHelper.convert(devopsEnvPodMapper.selectOne(devopsEnvPodDO), DevopsEnvPodE.class);
    }
}
