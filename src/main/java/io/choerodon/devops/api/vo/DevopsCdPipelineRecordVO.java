package io.choerodon.devops.api.vo;

import io.swagger.annotations.ApiModelProperty;
import java.util.Date;
import java.util.List;

public class DevopsCdPipelineRecordVO {

    private Long id;
    private Long pipelineId;
    private Long gitlabPipelineId;
    private String status;
    private String triggerType;
    private String bpmDefinition;
    private Long projectId;
    private String pipelineName;
    private String businessKey;
    private Boolean edited;
    private String errorInfo;

    @ApiModelProperty("触发用户")
    private String username;
    @ApiModelProperty("触发分支")
    private String gitlabTriggerRef;
    @ApiModelProperty("创建时间")
    private Date createdDate;
    @ApiModelProperty("结束时间")
    private Date finishedDate;
    @ApiModelProperty("执行耗时")
    private Long durationSeconds;


    private List<DevopsCdStageRecordVO> devopsCdStageRecordVOS;


    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPipelineId() {
        return pipelineId;
    }

    public void setPipelineId(Long pipelineId) {
        this.pipelineId = pipelineId;
    }

    public Long getGitlabPipelineId() {
        return gitlabPipelineId;
    }

    public void setGitlabPipelineId(Long gitlabPipelineId) {
        this.gitlabPipelineId = gitlabPipelineId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public String getBpmDefinition() {
        return bpmDefinition;
    }

    public void setBpmDefinition(String bpmDefinition) {
        this.bpmDefinition = bpmDefinition;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getPipelineName() {
        return pipelineName;
    }

    public void setPipelineName(String pipelineName) {
        this.pipelineName = pipelineName;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public void setBusinessKey(String businessKey) {
        this.businessKey = businessKey;
    }

    public Boolean getEdited() {
        return edited;
    }

    public void setEdited(Boolean edited) {
        this.edited = edited;
    }

    public String getErrorInfo() {
        return errorInfo;
    }

    public void setErrorInfo(String errorInfo) {
        this.errorInfo = errorInfo;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getGitlabTriggerRef() {
        return gitlabTriggerRef;
    }

    public void setGitlabTriggerRef(String gitlabTriggerRef) {
        this.gitlabTriggerRef = gitlabTriggerRef;
    }

    public Date getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(Date createdDate) {
        this.createdDate = createdDate;
    }

    public Date getFinishedDate() {
        return finishedDate;
    }

    public void setFinishedDate(Date finishedDate) {
        this.finishedDate = finishedDate;
    }

    public Long getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Long durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public List<DevopsCdStageRecordVO> getDevopsCdStageRecordVOS() {
        return devopsCdStageRecordVOS;
    }

    public void setDevopsCdStageRecordVOS(List<DevopsCdStageRecordVO> devopsCdStageRecordVOS) {
        this.devopsCdStageRecordVOS = devopsCdStageRecordVOS;
    }
}