package com.redis.caching.rdb;

import static com.redis.caching.util.TestUtils.createDirectory;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OracleTest extends AbstractOracleTest {

    private static final Logger LOGGER = LoggerFactory.getLogger("cache-aside-demo");
    private static final String instanceId = ManagementFactory.getRuntimeMXBean().getName();

    private Connection getOracleConnection() throws SQLException {
        return DriverManager.getConnection(oracleContainer.getJdbcUrl(), "hr",
                "hr");
    }

    private StatefulRedisConnection<String, String> getRedisConnection() {
        return RedisClient.create(redisEnterpriseContainer.getRedisURI()).connect();
    }

    @BeforeAll
    static void beforeAll() {
        LOGGER.info("Instance: {} Running beforeAll", instanceId);
        // Start Redis Enterprise container
        LOGGER.info("Instance: {} Starting Redis Image: {}", instanceId, redisImageName);
        redisEnterpriseContainer.start();
        LOGGER.info("Instance: {} {} container is running now", instanceId, redisEnterpriseContainer.getContainerName());

        // Start and setup Oracle container
        Path path = Paths.get(recoveryPath);
        if (!Files.exists(path)) {
            createDirectory(recoveryPath);
        }

        LOGGER.info("Instance: {} Starting Oracle Image: {}", instanceId, rdbImageName);
        oracleContainer.start();
        oracleContainer.waitUntilContainerStarted();
        LOGGER.info("Instance: {} {} container is running now", instanceId, oracleContainer.getContainerName());

        LOGGER.info("Instance: {} {} is running now", instanceId, oracleContainer.getContainerName());
    }

    @Test
    @Order(1)
    void testRedisConnection() {
        // Setup Redis Enterprise
        LOGGER.info("Instance: {} Redis Database is up! Now testing PING command..", instanceId);
        Instant startedAt = Instant.now();
        try (StatefulRedisConnection<String, String> redisConnection = getRedisConnection()) {
            assertEquals("PONG", redisConnection.sync().ping());
            LOGGER.info("Instance: {} Total Redis connection test time: {}", instanceId, Duration.between(startedAt, Instant.now()));
        }
    }

    @Test
    @Order(2)
    void testOracleConnection() {
        LOGGER.info("Instance: {} Oracle Database is up! Now testing JDBC URL and Test Query..", instanceId);

        LOGGER.info("Instance: {} JDBC URL: {}", instanceId, oracleContainer.getJdbcUrl());
        LOGGER.info("Instance: {} Query String: {}", instanceId, oracleContainer.getTestQueryString());

        Instant startedAt = Instant.now();
        String jdbcUrl = oracleContainer.getJdbcUrl();
        MatcherAssert.assertThat(jdbcUrl, containsString("@"));
        MatcherAssert.assertThat(jdbcUrl, containsString("/"));
        LOGGER.info("Instance: {} Total Oracle connection test time: {}", instanceId, Duration.between(startedAt, Instant.now()));
    }

    @Test
    @Order(3)
    public void testInsert() throws Exception {
        try (Connection oracleConnection = getOracleConnection()) {
            Statement statement = oracleConnection.createStatement();
            String dropQuery = "BEGIN EXECUTE IMMEDIATE 'DROP TABLE hr.emp'; " +
                    "EXCEPTION WHEN OTHERS THEN NULL; " +
                    "END;";
            String createQuery = "create table hr.emp(  \n" +
                    "  empno\t\tnumber(6,0),\n" +
                    "  fname\t\tvarchar2(30),\n" +
                    "  lname\t\tvarchar2(30),  \n" +
                    "  job\t\tvarchar2(40),\n" +
                    "  mgr\t\tnumber(4,0),\n" +
                    "  hiredate\tdate,\n" +
                    "  sal\t\tnumber(10,4),\n" +
                    "  comm\t\tnumber(10,4),\n" +
                    "  dept\t\tnumber(4,0),\n" +
                    "  constraint pk_emp primary key (empno)\n" +
                    ")";
            String insertQuery = "insert into hr.emp values (1, 'Virag', 'Tripathi', 'PFE', 19, (TO_DATE('2018-08-05 04:07:50', 'yyyy-MM-dd HH:mi:ss')), 90101.34, 1235.13, 96)\n";
            statement.execute(dropQuery);
            LOGGER.info("Instance: {} Creating EMP table", instanceId);
            statement.execute(createQuery);
            LOGGER.info("Instance: {} Inserting a record into EMP table", instanceId);
            statement.execute(insertQuery);
            statement.execute("SELECT empno FROM hr.emp");
            try (ResultSet resultSet = statement.getResultSet()) {
                resultSet.next();
                String empno = resultSet.getString(1);
                assertEquals("1", empno, "Value from hr.emp should equal real value");

                LOGGER.info("Instance: {} Employee {} record in Oracle", instanceId, empno);
                ResultSet rs = statement.executeQuery("SELECT * FROM hr.emp");
                ResultSetMetaData rsmd = rs.getMetaData();
                int columnsNumber = rsmd.getColumnCount();
                while (rs.next()) {
                    for (int i = 1; i <= columnsNumber; i++) {
                        String columnValue = rs.getString(i);
                        LOGGER.info("{} : {}", rsmd.getColumnName(i), columnValue);
                    }
                }
            }
        }
    }

    @Test
    @Order(4)
    public void testCacheAside() throws Exception {
        StatefulRedisConnection<String, String> redisConnection = getRedisConnection();
        Map<String, String> record = redisConnection.sync().hgetall("emp:1");
        Map<String, String> values = new HashMap<>();

        if (record.isEmpty()) {
            LOGGER.info("Instance: {} Employee 1 not found in Redis", instanceId);

            oracleContainer.withDatabaseName("ORCLPDB1");
            oracleContainer.withUsername("hr");
            oracleContainer.withPassword("hr");
            long rdbStartTime = System.currentTimeMillis();
            try (Connection connection = oracleContainer.createConnection("")) {
                Statement statement = connection.createStatement();
                statement.execute("SELECT * FROM hr.emp");
                try (ResultSet resultSet = statement.getResultSet()) {
                    resultSet.next();
                    String empno = String.valueOf(resultSet.getInt(1));
                    values.put("empno", empno);
                    values.put("fname", resultSet.getString(2));
                    values.put("lname", resultSet.getString(3));
                    values.put("job", resultSet.getString(4));
                    values.put("mgr", String.valueOf(resultSet.getInt(5)));
                    values.put("hiredate", String.valueOf(resultSet.getDate(6)));
                    values.put("sal", String.valueOf(resultSet.getDouble(7)));
                    values.put("comm", String.valueOf(resultSet.getDouble(8)));
                    values.put("dept", String.valueOf(resultSet.getInt(9)));

                    LOGGER.info("Instance: {} Employee {} found in Oracle", instanceId, empno);
                    LOGGER.info("Instance: {} Oracle Query time: {} ms", instanceId, System.currentTimeMillis() - rdbStartTime);

                    long redisStartTime = System.currentTimeMillis();
                    LOGGER.info("Instance: {} Caching Employee {} in Redis", instanceId, empno);
                    redisConnection.sync().hset("emp:" + empno, values);
                    LOGGER.info("Instance: {} Employee {} record in Redis {}", instanceId, empno, redisConnection.sync().hgetall("emp:1"));
                    LOGGER.info("Instance: {} Redis Query time: {} ms", instanceId, System.currentTimeMillis() - redisStartTime);
                }
            }
        }
    }

    @AfterAll
    static void afterAll() {
        LOGGER.info("Instance: {} running afterAll", instanceId);
    }

}
