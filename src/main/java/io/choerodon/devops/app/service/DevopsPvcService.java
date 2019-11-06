package io.choerodon.devops.app.service;

import com.github.pagehelper.PageInfo;
import io.choerodon.devops.api.vo.DevopsPvcReqVO;
import io.choerodon.devops.api.vo.DevopsPvcRespVO;
import io.choerodon.devops.infra.dto.DevopsPvcDTO;
import org.springframework.data.domain.Pageable;

public interface DevopsPvcService {
    /**
     * 创建PVC
     *
     * @param projectId
     * @param devopsPvcReqVO
     * @return
     */
    DevopsPvcRespVO create(Long projectId, DevopsPvcReqVO devopsPvcReqVO);

    /**
     * 删除PVC
     *
     * @param envId
     * @param pvcId
     * @return
     */
    boolean delete(Long envId, Long pvcId);

    PageInfo<DevopsPvcRespVO> pageByOptions(Long projectId, Long envId, Pageable pageable, String params);

    /**
     * 检查PVC名称唯一性
     *
     * @param PvcName
     * @param envId
     */
    void baseCheckName(String PvcName, Long envId);

    /**
     * 通过环境id和名称查找pvc
     *
     * @param envId 环境id
     * @param name  pvc名称
     * @return pvc纪录
     */
    DevopsPvcDTO queryByEnvIdAndName(Long envId, String name);

    /**
     * 创建或者更新pvc
     *
     * @param userId         用户id
     * @param devopsPvcReqVO pvc相关信息
     */
    DevopsPvcDTO createOrUpdateByGitOps(Long userId, DevopsPvcReqVO devopsPvcReqVO);

    /**
     * GitOps逻辑中删除pvc
     *
     * @param pvcId pvc的ID
     */
    void deleteByGitOps(Long pvcId);

    void baseUpdate(DevopsPvcDTO devopsPvcDTO);

    DevopsPvcDTO queryById(Long pvcId);
}
