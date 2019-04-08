package script.db

databaseChangeLog(logicalFilePath: 'dba/devops_pipeline_stage_record.groovy') {
    changeSet(author: 'scp', id: '2019-04-03-create-table') {
        createTable(tableName: "devops_pipeline_stage_record", remarks: '阶段') {
            column(name: 'id', type: 'BIGINT UNSIGNED', remarks: '主键，ID', autoIncrement: true) {
                constraints(primaryKey: true)
            }
            column(name: 'pipeline_record_id', type: 'BIGINT UNSIGNED', remarks: '工作台记录Id')
            column(name: 'status', type: 'VARCHAR(10)', remarks: '状态')
            column(name: 'trigger_type', type: 'VARCHAR(10)', remarks: '触发方式')
            column(name: 'is_parallel', type: 'TINYINT UNSIGNED', remarks: '是否并行')
            column(name: 'execution_time', type: 'DATETIME', remarks: '执行时间')
            column(name: 'project_id', type: 'BIGINT UNSIGNED', remarks: '项目Id')

            column(name: "object_version_number", type: "BIGINT UNSIGNED", defaultValue: "1")
            column(name: "created_by", type: "BIGINT UNSIGNED", defaultValue: "0")
            column(name: "creation_date", type: "DATETIME", defaultValueComputed: "CURRENT_TIMESTAMP")
            column(name: "last_updated_by", type: "BIGINT UNSIGNED", defaultValue: "0")
            column(name: "last_update_date", type: "DATETIME", defaultValueComputed: "CURRENT_TIMESTAMP")
        }
    }
}