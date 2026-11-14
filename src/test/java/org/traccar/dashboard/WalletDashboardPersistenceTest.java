package org.traccar.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.model.User;
import org.traccar.storage.DatabaseStorage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WalletDashboardPersistenceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JdbcDataSource dataSource() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return dataSource;
    }

    private void migrate(JdbcDataSource dataSource, String changelog) throws Exception {
        try (var connection = dataSource.getConnection();
                var resources = new DirectoryResourceAccessor(new File("."))) {
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase(changelog, resources, database)) {
                liquibase.update(new Contexts());
            }
        }
    }

    private User user() throws Exception {
        var user = new User();
        user.setId(1);
        user.setName("Dashboard test");
        user.setEmail("dashboard@example.com");
        user.set("distanceUnit", "km");
        user.set("walletDashboard", objectMapper.writeValueAsString(Map.of(
                "accounts", Map.of("account-one", Map.of("overrides", Map.of("record-one", "operatingCost"))))));
        return user;
    }

    private void addOverrides(User user) throws Exception {
        var overrides = new LinkedHashMap<String, String>();
        for (int i = 0; i < 2000; i++) {
            overrides.put(UUID.randomUUID().toString(), "operatingCost");
        }
        user.set("walletDashboard", objectMapper.writeValueAsString(Map.of(
                "accounts", Map.of("account-one", Map.of("overrides", overrides)))));
        assertTrue(objectMapper.writeValueAsString(user.getAttributes()).length() > 65535);
    }

    private User reload(JdbcDataSource dataSource) throws Exception {
        var storage = new DatabaseStorage(new Config(), dataSource, objectMapper);
        return storage.getObject(User.class, new Request(new Columns.Include("id", "attributes"),
                new Condition.Equals("id", 1)));
    }

    @Test
    public void testUpgradePreservesSettingsAndAllowsMoreClassifications() throws Exception {
        var dataSource = dataSource();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE tc_users (id INT PRIMARY KEY, attributes VARCHAR(4000))");
        }
        var storage = new DatabaseStorage(new Config(), dataSource, objectMapper);
        var user = user();
        storage.addObject(user, new Request(new Columns.Include("id", "attributes")));
        var original = reload(dataSource).getAttributes();
        addOverrides(user);
        var request = new Request(new Columns.Include("attributes"), new Condition.Equals("id", 1));
        assertThrows(StorageException.class, () -> storage.updateObject(user, request));

        migrate(dataSource, "schema/changelog-wallet-dashboard.xml");
        assertEquals(original, reload(dataSource).getAttributes());
        storage.updateObject(user, request);
        assertEquals(user.getAttributes(), reload(dataSource).getAttributes());
        migrate(dataSource, "schema/changelog-wallet-dashboard.xml");
        assertEquals(user.getAttributes(), reload(dataSource).getAttributes());
    }

    @Test
    public void testFreshInstallationSupportsLargeClassifications() throws Exception {
        var dataSource = dataSource();
        migrate(dataSource, "schema/changelog-master.xml");
        var storage = new DatabaseStorage(new Config(), dataSource, objectMapper);
        var user = user();
        addOverrides(user);
        storage.addObject(user, new Request(new Columns.Include("id", "name", "email", "attributes")));
        assertEquals(user.getAttributes(), reload(dataSource).getAttributes());
    }
}
