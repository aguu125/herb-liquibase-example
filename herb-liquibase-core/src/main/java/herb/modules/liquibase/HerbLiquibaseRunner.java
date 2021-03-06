package herb.modules.liquibase;

import liquibase.configuration.GlobalConfiguration;
import liquibase.exception.LiquibaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.text.SimpleDateFormat;
import java.util.*;

//通过spring boot的 配置文件信息
//调用liquibase MAIN ，使用命令行模式来调用
public class HerbLiquibaseRunner {

    private static final Logger log = LoggerFactory.getLogger(HerbLiquibaseRunner.class);

    private HerbLiquibaseProperties liquibaseProperties;
    private ProjectVersionsProperties versionsProperties;

    public HerbLiquibaseRunner(HerbLiquibaseProperties springLiquibase,
                               ProjectVersionsProperties versionsProperties) {
        if(versionsProperties==null){
            log.warn("can't find db/versions.yml");
        }
        this.versionsProperties = versionsProperties;
        this.liquibaseProperties = springLiquibase;
    }

    //扩展支持updateToVersion

    public void run(String[] args) {
        int errorLevel = 0;

        ProjectVersionsProperties.DatabaseVersionProperties changelogProperties = null;


        if ("updateToVersion".equalsIgnoreCase(args[0])) {
            try {

                if (args.length < 2 || StringUtils.hasText(args[1]) == false) {
                    log.error("updateToVersion 命令缺少版本参数.");
                    System.exit(-1);
                }

                String versionName = args[1];
                Optional<ProjectVersionsProperties.DatabaseVersionProperties> versionOpt =
                        versionsProperties.getVersions().stream()
                        .filter((prop) -> Objects.equals(prop.getVersion(), versionName))
                        .findFirst();

                if (versionOpt.isPresent()) {
                    changelogProperties = versionOpt.get();
                } else {
                    log.error("没有匹配的版本信息：{}", versionName);
                    System.exit(-1);
                }
                //重新构造args[]
                args = new String[]{"update"};
            } catch (Exception e) {
                log.error(e.getMessage(), e);
                System.exit(-1);
            }
        } else {
            //取最新的版本
            if (!CollectionUtils.isEmpty(versionsProperties.getVersions())) {
                changelogProperties
                        = versionsProperties.getVersions().get(versionsProperties.getVersions().size() - 1);
            }

        }

        if (changelogProperties == null) {
            log.error("changelogProperties is null");
            System.exit(-1);
        }else{
            log.info(" chosen version is  : [{}],changlog size is {}",
                    changelogProperties.getVersion(),
                    changelogProperties.getChangeLogs().size()
            );
        }

        //开始执行版本
        List<ProjectVersionsProperties.DatabaseChangeLogProp> databaseChangeLogs
                = changelogProperties.getChangeLogs();

        List<String> databaseReport= new ArrayList<>();

        int successCount = 0;

        //开始编译每个数据库，并执行该版本的changelogs
        for (int i = 0; i < databaseChangeLogs.size(); i++) {
            ProjectVersionsProperties.DatabaseChangeLogProp
                    databaseChangeLogProp = databaseChangeLogs.get(i);
            String dbName = databaseChangeLogProp.getDatabase();

            HerbDbLiquibaseProperties dbLiquibaseProperties = null;
            for (Map.Entry<String, HerbDbLiquibaseProperties> entry
                    : liquibaseProperties.getDatabases().entrySet()) {
                String databaseName = entry.getKey();
                if (databaseName.equals(dbName)) {
                    dbLiquibaseProperties = entry.getValue();
                    break;
                }
            }

            if (dbLiquibaseProperties == null) {
                log.error("不存在{} 的数据库配置信息", dbName);
                databaseReport.add(dbName+"\t\t\t FAIL,读取不到数据库配置信息");
                //System.exit(-1);
                continue;
            }

            log.info("================== start to run database 【{}】,command is {}  ===========", dbName, args);

            try {
                runForDatabase(databaseChangeLogProp, dbLiquibaseProperties, args);
                databaseReport.add(dbName+"\t\t\t OK.");
                successCount++;
            } catch (Throwable e) {
                log.error(e.getMessage(),e);
                databaseReport.add(dbName+"\t\t\t FAIL,执行发布异常！");
            }
        }

        StringBuilder message = new StringBuilder("\n----------------\n");
        message.append("\n 执行命令：");
        for(String arg:args){
            message.append(arg+" ");
        }
        message.append("\n 执行结果：");

        if(successCount == databaseChangeLogs.size()){
            message.append(" SUCCESS");
        }else{
            message.append(" FAIL");
        }

        message.append("\n 详细报告：");
        for(String dbReport:databaseReport){
            message.append("\n "+dbReport);
        }
        message.append("\n----------------\n");
        log.info(message.toString());
        if(successCount == databaseChangeLogs.size()) {
            System.exit(0);
        }else{
            System.exit(-1);
        }
    }

    private int runForDatabase(ProjectVersionsProperties.DatabaseChangeLogProp databaseChangeLogProp,
                               HerbDbLiquibaseProperties properties, String[] args) throws LiquibaseException {

        String[] globalOptions = buildGlobalOptions(databaseChangeLogProp, properties);

        //如果执行更新，强制打tag
        if("update".equals(args[0])){
            SimpleDateFormat sf = new SimpleDateFormat("yyyyMMddHHmmss");
            String tagName= databaseChangeLogProp.getDatabase()+"_startUpdate_"+sf.format(new Date());
            runLiquibaseCommand(globalOptions,new String[]{"tag",tagName});
            runLiquibaseCommand(globalOptions,args);
        }else {
            runLiquibaseCommand(globalOptions, args);
        }



        return 0;
    }

    public void runLiquibaseCommand(String[] globalOptions,String[] args)throws LiquibaseException{

        final List<String> runArgs = new ArrayList<>();
        for (String globalOption : globalOptions) {
            runArgs.add(globalOption);
        }
        for (String arg : args) {
            runArgs.add(arg);
        }

        try {
            StringBuilder str = new StringBuilder();
            for(String arg: runArgs){
                str.append("\n  "+arg);
            }

            log.info("执行命令 \n liquibase {} ",str.toString());

            Main.run(runArgs.toArray(new String[runArgs.size()]));
        } catch (Throwable e) {
            throw e;
        }
    }



    /**
     *  生成liquibase global 的参数，对应liquibase.properties
     * @param databaseChangeLogProp
     * @param properties
     * @return
     */
    private String[] buildGlobalOptions(ProjectVersionsProperties.DatabaseChangeLogProp databaseChangeLogProp,
                                        HerbDbLiquibaseProperties properties) {
        List<String> globalOptions = new ArrayList<>();

        String driver = properties.getDriver();
        String url = properties.getUrl();
        String userName = properties.getUsername();
        String password = properties.getPassword();


        globalOptions.add(String.format("--driver=%s", driver));
        globalOptions.add(String.format("--url=%s", url));
        globalOptions.add(String.format("--username=%s", userName));
        globalOptions.add(String.format("--password=%s", password));


        String changeLogFile = databaseChangeLogProp.getChangeLog();
        globalOptions.add(String.format("--changeLogFile=%s", changeLogFile));


        String logTable = "lg_db_change_log";
        if (StringUtils.hasText(properties.getDatabaseChangeLogTable())) {
            logTable = properties.getDatabaseChangeLogTable().trim().toLowerCase();
        }
        String logLockTable = "lg_db_change_log_lock";
        if (StringUtils.hasText(properties.getDatabaseChangeLogLockTable())) {
            logLockTable = properties.getDatabaseChangeLogLockTable().trim().toLowerCase();
        }
        globalOptions.add(String.format("--%s=%s", GlobalConfiguration.DATABASECHANGELOG_TABLE_NAME, logTable));
        globalOptions.add(String.format("--%s=%s", GlobalConfiguration.DATABASECHANGELOGLOCK_TABLE_NAME, logLockTable));


//        globalOptions.add(String.format("--defaultsFile=%s","C://liquibase.properties"));


        return globalOptions.toArray(new String[globalOptions.size()]);
    }

}
