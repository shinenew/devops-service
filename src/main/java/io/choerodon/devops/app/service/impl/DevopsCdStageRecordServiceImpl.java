package io.choerodon.devops.app.service.impl;

import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import io.choerodon.core.exception.CommonException;
import io.choerodon.devops.app.service.DevopsCdAuditRecordService;
import io.choerodon.devops.app.service.DevopsCdPipelineRecordService;
import io.choerodon.devops.app.service.DevopsCdStageRecordService;
import io.choerodon.devops.infra.constant.PipelineCheckConstant;
import io.choerodon.devops.infra.dto.DevopsCdStageRecordDTO;
import io.choerodon.devops.infra.enums.PipelineStatus;
import io.choerodon.devops.infra.enums.WorkFlowStatus;
import io.choerodon.devops.infra.mapper.DevopsCdStageRecordMapper;
import io.choerodon.devops.infra.util.TypeUtil;

/**
 * 〈功能简述〉
 * 〈〉
 *
 * @author wanghao
 * @since 2020/7/2 11:07
 */
@Service
public class DevopsCdStageRecordServiceImpl implements DevopsCdStageRecordService {

    private static final String SAVE_STAGE_RECORD_FAILED = "save.stage.record.failed";
    private static final String UPDATE_STAGE_RECORD_FAILED = "update.stage.record.failed";

    @Autowired
    private DevopsCdStageRecordMapper devopsCdStageRecordMapper;

    @Autowired
    @Lazy
    private DevopsCdPipelineRecordService devopsCdPipelineRecordService;

    @Autowired
    private DevopsCdAuditRecordService devopsCdAuditRecordService;


    @Override
    @Transactional
    public void save(DevopsCdStageRecordDTO devopsCdStageRecordDTO) {
        if (devopsCdStageRecordMapper.insert(devopsCdStageRecordDTO) != 1) {
            throw new CommonException(SAVE_STAGE_RECORD_FAILED);
        }
    }

    @Override
    public List<DevopsCdStageRecordDTO> queryByPipelineRecordId(Long pipelineRecordId) {
        DevopsCdStageRecordDTO recordDTO = new DevopsCdStageRecordDTO();
        recordDTO.setPipelineRecordId(pipelineRecordId);
        return devopsCdStageRecordMapper.select(recordDTO);
    }

    @Override
    public DevopsCdStageRecordDTO queryFirstByPipelineRecordId(Long pipelineRecordId) {
        return devopsCdStageRecordMapper.queryFirstByPipelineRecordId(pipelineRecordId);
    }

    @Override
    @Transactional
    public void update(DevopsCdStageRecordDTO devopsCdStageRecord) {
        if (devopsCdStageRecordMapper.updateByPrimaryKeySelective(devopsCdStageRecord) != 1) {
            throw new CommonException(UPDATE_STAGE_RECORD_FAILED);
        }
    }

    @Override
    public void updateStatusById(Long stageRecordId, String status) {
        DevopsCdStageRecordDTO recordDTO = devopsCdStageRecordMapper.selectByPrimaryKey(stageRecordId);
        recordDTO.setStatus(status);
        if (status.equals(WorkFlowStatus.FAILED.toValue())
                || status.equals(WorkFlowStatus.SUCCESS.toValue())
                || status.equals(WorkFlowStatus.STOP.toValue())) {
            recordDTO.setFinishedDate(new Date());
        }
        if (status.equals(WorkFlowStatus.RUNNING.toValue())) {
            recordDTO.setStartedDate(new Date());
        }
        if (devopsCdStageRecordMapper.updateByPrimaryKey(recordDTO) != 1) {
            throw new CommonException(UPDATE_STAGE_RECORD_FAILED);
        }
    }

    @Override
    public DevopsCdStageRecordDTO queryById(Long id) {
        Assert.notNull(id, PipelineCheckConstant.ERROR_STAGE_RECORD_ID_IS_NULL);
        return devopsCdStageRecordMapper.selectByPrimaryKey(id);
    }

    @Override
    @Transactional
    public void updateStageStatusFailed(Long stageRecordId) {
        DevopsCdStageRecordDTO devopsCdStageRecordDTO = queryById(stageRecordId);
        devopsCdStageRecordDTO.setStatus(PipelineStatus.FAILED.toValue());
        devopsCdStageRecordDTO.setFinishedDate(new Date());
        update(devopsCdStageRecordDTO);
    }

    @Override
    @Transactional
    public void updateStageStatusNotAudit(Long pipelineRecordId, Long stageRecordId) {
        // 更新阶段状态为待审核
        DevopsCdStageRecordDTO devopsCdStageRecordDTO = devopsCdStageRecordMapper.selectByPrimaryKey(stageRecordId);
        devopsCdStageRecordDTO.setStatus(PipelineStatus.NOT_AUDIT.toValue());
        devopsCdStageRecordDTO.setStartedDate(new Date());
        update(devopsCdStageRecordDTO);
        // 更新流水线状态为待审核0
        devopsCdPipelineRecordService.updateStatusById(pipelineRecordId, PipelineStatus.NOT_AUDIT.toValue());
        // 通知审核人员
        devopsCdAuditRecordService.sendStageAuditMessage(devopsCdStageRecordDTO);
    }
}
