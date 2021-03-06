package script.db.groovy

databaseChangeLog(logicalFilePath: 'dba/devops_project_config.groovy') {
    changeSet(author: 'scp', id: '2019-03-11-create-table') {
        createTable(tableName: "devops_project_config", remarks: '组件配置信息') {
            column(name: 'id', type: 'BIGINT UNSIGNED', remarks: '主键，ID', autoIncrement: true) {
                constraints(primaryKey: true)
            }
            column(name: 'project_id', type: 'BIGINT UNSIGNED', remarks: '项目ID')
            column(name: 'name', type: 'VARCHAR(50)', remarks: '配置名称')
            column(name: 'type', type: 'VARCHAR(10)', remarks: '配置类型')
            column(name: 'config', type: 'VARCHAR(255)', remarks: '配置')
            column(name: "object_version_number", type: "BIGINT UNSIGNED", defaultValue: "1")
            column(name: "created_by", type: "BIGINT UNSIGNED", defaultValue: "0")
            column(name: "creation_date", type: "DATETIME", defaultValueComputed: "CURRENT_TIMESTAMP")
            column(name: "last_updated_by", type: "BIGINT UNSIGNED", defaultValue: "0")
            column(name: "last_update_date", type: "DATETIME", defaultValueComputed: "CURRENT_TIMESTAMP")
        }

        addUniqueConstraint(tableName: 'devops_project_config',
                constraintName: 'uk_project_name', columnNames: 'project_id,name')
    }

    changeSet(author: 'scp', id: '2019-07-03-modify-column-type') {
        modifyDataType(tableName: 'devops_project_config', columnName: 'name', newDataType: 'VARCHAR(80)')

    }


    changeSet(author: 'sheep', id: '2019-8-05-rename-table') {


        dropUniqueConstraint(constraintName: "uk_project_name",tableName: "devops_project_config")

        addColumn(tableName: 'devops_project_config') {
            column(name: 'organization_id', type: 'BIGINT UNSIGNED', afterColumn: 'id', remarks: '组织ID')
            column(name: 'app_service_id', type: 'BIGINT UNSIGNED', afterColumn: 'id', remarks: '服务ID')
        }
        renameTable(newTableName: 'devops_config', oldTableName: 'devops_project_config')
    }

}
