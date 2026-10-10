package org.traccar.api.resource;

import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.traccar.api.BaseObjectResource;
import org.traccar.api.security.PermissionsService;
import org.traccar.dashboard.WalletTokenStore;
import org.traccar.helper.LogAction;
import org.traccar.config.Config;
import org.traccar.handler.ComputedAttributesHandler;
import org.traccar.model.Attribute;
import org.traccar.model.Permission;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.session.cache.CacheManager;
import org.traccar.session.ConnectionManager;
import org.traccar.storage.MemoryStorage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Request;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class WalletAttributeAccessTest {

    private static class TestResource extends AttributeResource {
        TestResource(MemoryStorage testStorage, PermissionsService permissions) {
            storage = testStorage;
            permissionsService = permissions;
        }

        @Override
        protected long getUserId() {
            return 1;
        }
    }

    private Attribute add(MemoryStorage storage, String key, String expression) {
        var attribute = new Attribute();
        attribute.setDescription(key);
        attribute.setAttribute(key);
        attribute.setExpression(expression);
        attribute.setType("string");
        attribute.setId(storage.addObject(attribute, new Request(new Columns.Exclude("id"))));
        storage.addPermission(new Permission(User.class, 1, Attribute.class, attribute.getId()));
        return attribute;
    }

    @Test
    public void testNonAdminCannotReadTokenEvenIfLinked() throws Exception {
        var storage = new MemoryStorage();
        var token = add(storage, "wallet_token", "'test-only-token'");
        add(storage, "label", "'vehicle'");
        var permissions = mock(PermissionsService.class);
        when(permissions.notAdmin(1)).thenReturn(true);
        var resource = new TestResource(storage, permissions);
        try (var result = resource.get(true, 0, 0, 0, false, 50, 0, null)) {
            assertEquals(List.of("label"), result.map(Attribute::getAttribute).toList());
        }
        assertThrows(ForbiddenException.class, () -> resource.getSingle(token.getId()));
    }

    @Test
    public void testAdminCanManageTokenThroughExistingAttributeApi() throws Exception {
        var storage = new MemoryStorage();
        var token = add(storage, "wallet_token", "'test-only-token'");
        var resource = new TestResource(storage, mock(PermissionsService.class));
        assertEquals(token, resource.getSingle(token.getId()).getEntity());
        try (var result = resource.get(true, 0, 0, 0, false, 50, 0, null)) {
            assertEquals(1, result.count());
        }
    }

    @Test
    public void testTokenIsSkippedDuringPositionProcessingEvenIfLinked() {
        var storage = new MemoryStorage();
        var token = add(storage, "wallet_token", "'test-only-token'");
        var normal = add(storage, "label", "'vehicle'");
        var cache = mock(CacheManager.class);
        when(cache.getDeviceObjects(1, Attribute.class)).thenReturn(Set.of(token, normal));
        var handler = new ComputedAttributesHandler.Late(new Config(), cache);
        var position = new Position();
        position.setDeviceId(1);
        handler.onPosition(position, processed -> { });
        assertFalse(position.hasAttribute("wallet_token"));
        assertEquals("vehicle", position.getString("label"));
    }
    @Test
    public void testExistingCreateAndUpdateWorkflowSuppliesToken() throws Exception {
        var storage = new MemoryStorage();
        var resource = new TestResource(storage, mock(PermissionsService.class));
        for (var entry : java.util.Map.of(
                "cacheManager", mock(CacheManager.class),
                "connectionManager", mock(ConnectionManager.class),
                "actionLogger", mock(LogAction.class)).entrySet()) {
            var field = BaseObjectResource.class.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(resource, entry.getValue());
        }
        var attribute = new Attribute();
        attribute.setDescription("BudgetBakers API token");
        attribute.setAttribute("wallet_token");
        attribute.setType("string");
        attribute.setExpression("'test-only-token'");
        assertEquals(200, resource.add(attribute).getStatus());
        var store = new WalletTokenStore(new Config(), storage);
        assertEquals("test-only-token", store.getToken());
        attribute.setExpression("'replacement-token'");
        assertEquals(200, resource.update(attribute).getStatus());
        assertEquals("replacement-token", store.getToken());
        var duplicate = new Attribute();
        duplicate.setAttribute("wallet_token");
        duplicate.setType("string");
        duplicate.setExpression("'another-token'");
        assertThrows(IllegalArgumentException.class, () -> resource.add(duplicate));
    }

}
