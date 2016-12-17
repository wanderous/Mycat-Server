package io.mycat.migrate;
import com.alibaba.druid.util.JdbcUtils;
import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.*;
import com.google.common.base.Strings;
import io.mycat.MycatServer;
import io.mycat.backend.datasource.PhysicalDBNode;
import io.mycat.route.function.PartitionByCRC32PreSlot;
import io.mycat.server.util.SchemaUtil;
import io.mycat.sqlengine.OneRawSQLQueryResultHandler;
import io.mycat.sqlengine.SQLJob;
import io.mycat.util.DateUtil;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.mycat.util.dataMigrator.DataMigratorUtil.executeQuery;

public class BinlogStream {

    private static Logger logger = LoggerFactory.getLogger(BinlogStream.class);

    private final String hostname;
    private final int port;
    private final String username;
    private final String password;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private BinaryLogClient binaryLogClient;

    private long slaveID;
    private String binglogFile;
    private long binlogPos;

    private Set<String> databaseSet=new HashSet<>();



    private List<MigrateTask> migrateTaskList;

    public List<MigrateTask> getMigrateTaskList() {
        return migrateTaskList;
    }

    public void setMigrateTaskList(List<MigrateTask> migrateTaskList) {
        this.migrateTaskList = migrateTaskList;
        for (MigrateTask migrateTask : migrateTaskList) {
            databaseSet.add(migrateTask.getSchema().toLowerCase()) ;
        }
    }

    public long getSlaveID() {
        return slaveID;
    }

    public void setSlaveID(long slaveID) {
        this.slaveID = slaveID;
    }

    public String getBinglogFile() {
        return binglogFile;
    }

    public void setBinglogFile(String binglogFile) {
        this.binglogFile = binglogFile;
    }

    public long getBinlogPos() {
        return binlogPos;
    }

    public void setBinlogPos(long binlogPos) {
        this.binlogPos = binlogPos;
    }

    private volatile boolean groupEventsByTX = true;

    private Set<Long> ignoredServerIds = new HashSet<Long>();
    private Set<String> ignoredTables = new HashSet<String>();



    public BinlogStream(String hostname, int port, String username, String password) {
        this.hostname = hostname;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    public void setGroupEventsByTX(boolean groupEventsByTX) {
        this.groupEventsByTX = groupEventsByTX;
    }



    public void setIgnoredHostsIds(Set<Long> ignoredServerIds) {
        this.ignoredServerIds = ignoredServerIds;
    }

    public void setIgnoredTables(Set<String> ignoredTables) {
        this.ignoredTables = ignoredTables;
    }


    public void connect() throws IOException {
        allocateBinaryLogClient().connect();
        initTaskDate();
        scheduler.scheduleAtFixedRate(new BinlogIdleCheck(this),5,15, TimeUnit.SECONDS);
    }

    private void initTaskDate() {
        Date curDate=new Date();
        for (MigrateTask migrateTask : migrateTaskList) {
            migrateTask.setLastBinlogDate(curDate);
        }
    }

    public void connect(long timeoutInMilliseconds) throws IOException, TimeoutException {
        allocateBinaryLogClient().connect(timeoutInMilliseconds);
        initTaskDate();
        scheduler.scheduleAtFixedRate(new BinlogIdleCheck(this),5,15, TimeUnit.SECONDS);
    }

    private synchronized BinaryLogClient allocateBinaryLogClient() {
        if (isConnected()) {
            throw new IllegalStateException("MySQL replication stream is already open");
        }
        binaryLogClient = new BinaryLogClient(hostname, port, username, password);
        binaryLogClient.setBinlogFilename(getBinglogFile());
        binaryLogClient.setBinlogPosition(getBinlogPos());
        binaryLogClient.setServerId(getSlaveID());
        binaryLogClient.registerEventListener(new DelegatingEventListener());
        return binaryLogClient;
    }




    public synchronized boolean isConnected() {
        return binaryLogClient != null && binaryLogClient.isConnected();
    }





    public synchronized void disconnect() throws IOException {
        if (binaryLogClient != null) {
            binaryLogClient.disconnect();
            binaryLogClient = null;
        }
        scheduler.shutdown();
    }





    private final class DelegatingEventListener implements BinaryLogClient.EventListener {

        private final Map<Long, TableMapEventData> tablesById = new HashMap<Long, TableMapEventData>();
        private final Map<String, Map<Integer,Map<String, Object>>> tablesColumnMap = new HashMap<>();

        private boolean transactionInProgress;
        private String binlogFilename;


        //当发现ddl语句时 需要更新重新取列名
        private Map<Integer,Map<String, Object>> loadColumn(String database,String table)
        {
            Map<Integer,Map<String, Object>> rtn=new HashMap<>();
            List<Map<String, Object>> list=null;
            Connection con = null;
            try {
                con =  DriverManager.getConnection("jdbc:mysql://"+hostname+":"+port,username,password);
                list = executeQuery(con, "select  COLUMN_NAME, ORDINAL_POSITION, DATA_TYPE, CHARACTER_SET_NAME from INFORMATION_SCHEMA.COLUMNS where table_name='"+table+"' and TABLE_SCHEMA='"+database+"'");

            } catch (SQLException e) {
                throw new RuntimeException(e);
            }finally{
                JdbcUtils.close(con);
            }
            for (Map<String, Object> stringObjectMap : list) {
                BigInteger pos= (BigInteger) stringObjectMap.get("ORDINAL_POSITION");
                rtn.put(pos.intValue(),stringObjectMap);
            }
            return rtn;
        }

        @Override
        public void onEvent(Event event) {
            logger.debug(event.toString());
            EventType eventType = event.getHeader().getEventType();
            switch (eventType) {
                case TABLE_MAP:
                    TableMapEventData tableMapEventData = event.getData();
                    tablesById.put(tableMapEventData.getTableId(), tableMapEventData);
                    if(!tablesColumnMap.containsKey(tableMapEventData.getDatabase()+"."+tableMapEventData.getTable())) {
                        tablesColumnMap.put(tableMapEventData.getDatabase()+"."+tableMapEventData.getTable(),loadColumn(tableMapEventData.getDatabase(),tableMapEventData.getTable())) ;
                    }
                    break;
                case ROTATE:
                    RotateEventData data=    event.getData()  ;
                    binlogFilename=data.getBinlogFilename();
                    break;
                case PRE_GA_WRITE_ROWS:
                case WRITE_ROWS:
                case EXT_WRITE_ROWS:
                    handleWriteRowsEvent(event);
                    break;
                case PRE_GA_UPDATE_ROWS:
                case UPDATE_ROWS:
                case EXT_UPDATE_ROWS:
                    handleUpdateRowsEvent(event);
                    break;
                case PRE_GA_DELETE_ROWS:
                case DELETE_ROWS:
                case EXT_DELETE_ROWS:
                    handleDeleteRowsEvent(event);
                    break;
                case QUERY:
                    if (groupEventsByTX) {
                        QueryEventData queryEventData = event.getData();
                        String query = queryEventData.getSql();
                        if ("BEGIN".equals(query)) {
                            transactionInProgress = true;
                        }   else if(!query.startsWith("#")) {
                            handleOtherSqlEvent(event);
                        }
                    }
                    break;
                case XID:
                    if (groupEventsByTX) {
                        transactionInProgress = false;
                    }
                    break;
                default:
                    // ignore
            }
        }

        private void exeSql(MigrateTask task,String sql){
            if(task.isHaserror())
                return;
            OneRawSQLQueryResultHandler resultHandler = new OneRawSQLQueryResultHandler(new String[0], new SqlExecuteListener(task,sql,BinlogStream.this));
            resultHandler.setMark("binlog execute");
            PhysicalDBNode dn = MycatServer.getInstance().getConfig().getDataNodes().get(task.getTo());
            SQLJob sqlJob = new SQLJob(sql, dn.getDatabase(), resultHandler, dn.getDbPool().getSource());
            sqlJob.run();
        }

        private void handleOtherSqlEvent(Event event) {
            QueryEventData queryEventData = event.getData();
            logger.debug("receve sql:",queryEventData.getSql());
            SchemaUtil.SchemaInfo schemaInfo=SchemaUtil.parseSchema(queryEventData.getSql());
            if(isShouldBeFilter(queryEventData.getDatabase(),schemaInfo.table))
                return;
            String query = queryEventData.getSql();
            for (MigrateTask migrateTask : migrateTaskList) {
                if(schemaInfo.table.equalsIgnoreCase(migrateTask.getTable())
                        &&queryEventData.getDatabase().equalsIgnoreCase(migrateTask.getSchema())){
                     exeSql(migrateTask,query);
                }
            }


        }

        private boolean isShouldBeFilter(String database,String table)
        {
            if(Strings.isNullOrEmpty(database))
                return true;
            if(Strings.isNullOrEmpty(table))
                return true;
            if(!databaseSet.contains(database.toLowerCase())){
                return true;
            }
            for (MigrateTask migrateTask : migrateTaskList) {
                if(table.equalsIgnoreCase(migrateTask.getTable())){
                    return false;
                }
            }


            return true;
        }

        private void handleWriteRowsEvent(Event event) {
            WriteRowsEventData eventData = event.getData();
            TableMapEventData tableMapEvent = tablesById.get(eventData.getTableId());
            if(isShouldBeFilter(tableMapEvent.getDatabase(),tableMapEvent.getTable()))
                return;
            Map<Integer, Map<String, Object>> xxx=    tablesColumnMap.get(tableMapEvent.getDatabase()+"."+tableMapEvent.getTable());
            BitSet inculudeColumn= eventData.getIncludedColumns();
            StringBuilder sb=new StringBuilder("insert into  ");
            sb.append(tableMapEvent.getTable())  ;
            sb.append("(");
            int size=  inculudeColumn.length()   ;
            List<Serializable[]> rows = eventData.getRows();

            int slot=-1;
            for (int i = 0; i <size; i++) {
                int column=inculudeColumn.nextSetBit(i);
                Map<String, Object> coumnMap=   xxx.get(column+1);
                sb.append(coumnMap.get("COLUMN_NAME"));
                if(i!=size-1){
                    sb.append(",");
                }
            }
            sb.append(")  values  ");
            for (int i = 0; i < rows.size(); i++) {
                Serializable[]  value= rows.get(i);
                sb.append(" (");
                for (int y = 0; y<size; y++) {
                    int column=inculudeColumn.nextSetBit(y);
                    Map<String, Object> coumnMap=   xxx.get(column+1);
                    String dataType= (String) coumnMap.get("DATA_TYPE");
                    String columnName= (String) coumnMap.get("COLUMN_NAME");
                    if("_slot".equalsIgnoreCase(columnName)){
                         slot=((BigInteger) value[y]).intValue();
                    }
                    sb.append(convertBinlogValue(value[y],dataType));

                    if(y!=size-1){
                        sb.append(",");
                    }
                }
                sb.append(")");
                if(i!=rows.size()-1){
                    sb.append(",");
                }
            }

            checkIfExeSql(tableMapEvent, sb, slot);

        }

        private void checkIfExeSql(TableMapEventData tableMapEvent, StringBuilder sb, int slot) {
            for (MigrateTask migrateTask : migrateTaskList) {
                if(tableMapEvent.getTable().equalsIgnoreCase(migrateTask.getTable())
                        &&tableMapEvent.getDatabase().equalsIgnoreCase(migrateTask.getSchema())){
                    for (PartitionByCRC32PreSlot.Range range :migrateTask.getSlots()) {
                          if(range.end>=slot&&range.start<=slot) {
                              exeSql(migrateTask,sb.toString());
                          }
                    }

                }
            }
        }

        private Object convertBinlogValue(Serializable value,String dataType){
            if(value instanceof String )   {
                return   "'"+value+"'";
            }  else  if(value instanceof byte[] )   {
                //todo 需要确认编码
                return   "'"+new String((byte[]) value)+"'";
            }else    if(value instanceof Date )   {
                return   "'"+dateToString((Date)value,dataType)+"'";
            }else if(("date".equalsIgnoreCase(dataType))&&value instanceof Long)
            {
                return   "'"+dateToStringFromUTC((Long) value)+"'";
                //mariadb   date

            }
            else if("datetime".equalsIgnoreCase(dataType)&&value instanceof Long)
            {
                return   "'"+datetimeToStringFromUTC((Long) value)+"'";
                //mariadb   date

            }   else if(("timestamp".equalsIgnoreCase(dataType))&&value instanceof Long)
            {
                return   "'"+dateToString((Long) value)+"'";
                //mariadb   date

            }
            else{
               return value;
            }
        }

        private String dateToStringFromUTC(Long date){
            DateTime dt = new DateTime(date, DateTimeZone.UTC);
            return dt.toString(DateUtil.DATE_PATTERN_ONLY_DATE);
        }

        private String datetimeToStringFromUTC(Long date){
            DateTime dt = new DateTime(date, DateTimeZone.UTC);
            return dt.toString(DateUtil.DATE_PATTERN_FULL);
        }
        private String dateToString(Long date){
            DateTime dt = new DateTime(date);
            return dt.toString(DateUtil.DATE_PATTERN_FULL);
        }
        private String dateToString(Date date,String dateType){
            if("timestamp".equalsIgnoreCase(dateType))
            {
                DateTime dt = new DateTime(date);
                return dt.toString(DateUtil.DATE_PATTERN_FULL);
            }  else    if("datetime".equalsIgnoreCase(dateType))   {
                DateTime dt = new DateTime(date,DateTimeZone.UTC);
                return dt.toString(DateUtil.DATE_PATTERN_FULL);
            }else    if("date".equalsIgnoreCase(dateType))   {
                DateTime dt = new DateTime(date,DateTimeZone.UTC);
                return dt.toString(DateUtil.DATE_PATTERN_ONLY_DATE);
            }  else
            {
                DateTime dt = new DateTime(date);
                return dt.toString(DateUtil.DATE_PATTERN_FULL);
            }

        }
        private void handleUpdateRowsEvent(Event event) {
            UpdateRowsEventData eventData = event.getData();
            TableMapEventData tableMapEvent = tablesById.get(eventData.getTableId());
            if(isShouldBeFilter(tableMapEvent.getDatabase(),tableMapEvent.getTable()))
                return;
            Map<Integer, Map<String, Object>> xxx=    tablesColumnMap.get(tableMapEvent.getDatabase()+"."+tableMapEvent.getTable());
            BitSet inculudeColumn= eventData.getIncludedColumns();
            StringBuilder sb=new StringBuilder("update ");
            sb.append(tableMapEvent.getTable())  ;
            sb.append(" set ");
            int size=  inculudeColumn.length()   ;
            int slot=-1;
            Map.Entry<Serializable[], Serializable[]> rowMap=     eventData.getRows().get(0)  ;
            Serializable[] value=   rowMap.getValue();
            Serializable[] key=   rowMap.getKey();
            for (int i = 0; i <size; i++) {
                int column=inculudeColumn.nextSetBit(i);
                Map<String, Object> coumnMap=   xxx.get(column+1);
                sb.append(coumnMap.get("COLUMN_NAME"));
                sb.append("=");
                String dataType= (String) coumnMap.get("DATA_TYPE");
                sb.append(convertBinlogValue(value[i],dataType));

                if(i!=size-1){
                    sb.append(",");
                }
            }
            sb.append(" where ");

            BitSet includedColumnsBeforeUpdate= eventData.getIncludedColumnsBeforeUpdate();
            for (int i = 0; i <size; i++) {
                int column=includedColumnsBeforeUpdate.nextSetBit(i);
                Map<String, Object> coumnMap=   xxx.get(column+1);
                sb.append(coumnMap.get("COLUMN_NAME"));
                sb.append("=");
                String dataType= (String) coumnMap.get("DATA_TYPE");
                sb.append(convertBinlogValue(value[i],dataType));
                String columnName= (String) coumnMap.get("COLUMN_NAME");
                if("_slot".equalsIgnoreCase(columnName)){
                    slot=((BigInteger) value[i]).intValue();
                }
                if(i!=size-1){
                    sb.append(" and ");
                }
            }

             checkIfExeSql(tableMapEvent,sb,slot);


        }

        private void handleDeleteRowsEvent(Event event) {
            DeleteRowsEventData eventData = event.getData();
            TableMapEventData tableMapEvent = tablesById.get(eventData.getTableId());
            if(isShouldBeFilter(tableMapEvent.getDatabase(),tableMapEvent.getTable()))
                return;
            Map<Integer, Map<String, Object>> xxx=    tablesColumnMap.get(tableMapEvent.getDatabase()+"."+tableMapEvent.getTable());
            BitSet inculudeColumn= eventData.getIncludedColumns();
            StringBuilder sb=new StringBuilder("delete from ");
            sb.append(tableMapEvent.getTable())  ;
            int size=  inculudeColumn.length()   ;
            Serializable[]  value=     eventData.getRows().get(0)  ;

            sb.append(" where ");
              int slot=-1;
            for (int i = 0; i <size; i++) {
                int column=inculudeColumn.nextSetBit(i);
                Map<String, Object> coumnMap=   xxx.get(column+1);
                sb.append(coumnMap.get("COLUMN_NAME"));
                sb.append("=");
                String dataType= (String) coumnMap.get("DATA_TYPE");
                sb.append(convertBinlogValue(value[i],dataType));
                String columnName= (String) coumnMap.get("COLUMN_NAME");
                if("_slot".equalsIgnoreCase(columnName)){
                    slot=((BigInteger) value[i]).intValue();
                }
                if(i!=size-1){
                    sb.append(" and ");
                }
            }
            checkIfExeSql(tableMapEvent,sb,slot);

        }



    }

    public static void main(String[] args) {
        BinlogStream  stream=new BinlogStream("localhost",3301,"czn","MUXmux");
        try {
            stream.setSlaveID(23511);
            stream.setBinglogFile("mysql-bin.000005");
            stream.setBinlogPos(4);
            stream.connect();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
