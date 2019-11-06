package io.choerodon.devops.app.service;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.data.domain.Pageable;
import io.choerodon.devops.api.vo.DevopsPvPermissionUpateVO;
import io.choerodon.devops.api.vo.DevopsPvVO;
import io.choerodon.devops.api.vo.ProjectReqVO;
import io.choerodon.devops.infra.dto.DevopsPvDTO;
import io.choerodon.devops.infra.dto.DevopsPvProPermissionDTO;

import java.util.List;

public interface DevopsPvServcie {

    /***
     * 查询根据Id查询单个PV信息
     */
    DevopsPvVO queryById(Long pvId);

    /**
     * 删除PV
     */
    void deletePvById(Long pvId);

    /***
     * 根据条件分页查询PV
     */
    PageInfo<DevopsPvVO> basePagePvByOptions(Boolean doPage, Pageable pageable, String params);

    /***
     * 创建PV
     * @param devopsPvDTO
     */
    void createPv(DevopsPvDTO devopsPvDTO);

    /**
     * 校验唯一性
     * @param devopsPvDTO
     */
    void baseCheckPv(DevopsPvDTO devopsPvDTO);

    /***
     * 根据Pv名称和集群的Id校验唯一性
     */
    void checkName(Long clusterId, String pvName);

    /***
     * 创建组织与PV的权限关联关系
     * @param update
     */
    void assignPermission(DevopsPvPermissionUpateVO update);

    /***
     * 更新PV表中的权限校验字段
     * @param update
     */
    void updateCheckPermission(DevopsPvPermissionUpateVO update);

    /**
     * 更新PV表字段
     */
    void updatePv(DevopsPvDTO devopsPvDTO);

    /**
     * 根据pvId查询pv
     */
    DevopsPvDTO baseQueryById(Long pvId);

    /**
     * 查询和PV没有绑定权限的项目
     * @param projectId
     * @return
     */
    List<ProjectReqVO> listNonRelatedProjects(Long projectId, Long pvId);


    void deleteRelatedProjectById(Long pvId, Long projectId);
}
