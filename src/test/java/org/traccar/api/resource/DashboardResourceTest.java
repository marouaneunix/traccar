package org.traccar.api.resource;

import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;
import org.traccar.api.security.PermissionsService;
import org.traccar.dashboard.WalletClient;
import org.traccar.model.Device;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class DashboardResourceTest {

    private static class TestResource extends DashboardResource {
        TestResource(PermissionsService permissions, Storage testStorage) {
            permissionsService = permissions;
            storage = testStorage;
        }

        @Override
        protected long getUserId() {
            return 1;
        }
    }

    private Device device(long id, String accountId) {
        var device = new Device();
        device.setId(id);
        device.set("accountId", accountId);
        return device;
    }

    private TestResource resource(PermissionsService permissions, WalletClient wallet, Device device) throws Exception {
        var storage = mock(Storage.class);
        when(storage.getObject(eq(Device.class), any(Request.class))).thenReturn(device);
        var resource = new TestResource(permissions, storage);
        var field = DashboardResource.class.getDeclaredField("walletClient");
        field.setAccessible(true);
        field.set(resource, wallet);
        return resource;
    }

    @Test
    public void testFinanceRequiresAdminBeforeAccessingWallet() throws Exception {
        var permissions = mock(PermissionsService.class);
        doThrow(new SecurityException("Administrator access required")).when(permissions).checkAdmin(anyLong());
        var wallet = mock(WalletClient.class);
        var resource = resource(permissions, wallet, device(1, "account-one"));
        assertThrows(SecurityException.class, () -> resource.getWalletStatus(1));
        assertThrows(SecurityException.class, () -> resource.getWalletRecords(1, new Date(0), new Date(1)));
        verifyNoInteractions(wallet);
    }

    @Test
    public void testSelectedDeviceDeterminesAccount() throws Exception {
        var wallet = mock(WalletClient.class);
        when(wallet.isConfigured()).thenReturn(true);
        var from = new Date(0);
        var to = new Date(1);
        var data = new WalletClient.WalletData("second-account", List.of(), "1970-01-01T00:00:00Z", null, false);
        when(wallet.getRecords("second-account", from.toInstant(), to.toInstant())).thenReturn(data);
        var permissions = mock(PermissionsService.class);
        var resource = resource(permissions, wallet, device(2, "  second-account  "));
        var response = resource.getWalletRecords(2, from, to);
        assertEquals(200, response.getStatus());
        assertEquals(data, response.getEntity());
        verify(permissions).checkPermission(Device.class, 1, 2);
        verify(wallet).getRecords("second-account", from.toInstant(), to.toInstant());
    }

    @Test
    public void testMissingAttributeDoesNotFetchFinancialData() throws Exception {
        var wallet = mock(WalletClient.class);
        when(wallet.isConfigured()).thenReturn(true);
        var resource = resource(mock(PermissionsService.class), wallet, device(1, null));
        var status = (Map<?, ?>) resource.getWalletStatus(1).getEntity();
        assertEquals(false, status.get("configured"));
        assertEquals("", status.get("accountId"));
        var response = resource.getWalletRecords(1, new Date(0), new Date(1));
        assertEquals(400, response.getStatus());
        assertEquals("walletAccountMissing", ((Map<?, ?>) response.getEntity()).get("error"));
        verify(wallet, never()).getRecords(any(), any(), any());
    }

    @Test
    public void testAttributeUpdatesAreUsedWithoutRestart() throws Exception {
        var wallet = mock(WalletClient.class);
        when(wallet.isConfigured()).thenReturn(true);
        var device = device(1, "first-account");
        var resource = resource(mock(PermissionsService.class), wallet, device);
        assertEquals("first-account", ((Map<?, ?>) resource.getWalletStatus(1).getEntity()).get("accountId"));
        device.set("accountId", "changed-account");
        assertEquals("changed-account", ((Map<?, ?>) resource.getWalletStatus(1).getEntity()).get("accountId"));
    }

    @Test
    public void testMissingDeviceIsRejected() throws Exception {
        var wallet = mock(WalletClient.class);
        var resource = resource(mock(PermissionsService.class), wallet, null);
        var error = assertThrows(NotFoundException.class, () -> resource.getWalletStatus(999));
        assertEquals(404, error.getResponse().getStatus());
        verifyNoInteractions(wallet);
    }

    @Test
    public void testDevicePermissionIsCheckedBeforeLookup() throws Exception {
        var permissions = mock(PermissionsService.class);
        doThrow(new SecurityException("Device access denied")).when(permissions).checkPermission(Device.class, 1, 2);
        var wallet = mock(WalletClient.class);
        var resource = resource(permissions, wallet, device(2, "second-account"));
        assertThrows(SecurityException.class, () -> resource.getWalletRecords(2, new Date(0), new Date(1)));
        verifyNoInteractions(wallet);
    }

    @Test
    public void testInvalidDatesAreRejectedBeforeExternalRequest() throws Exception {
        var wallet = mock(WalletClient.class);
        when(wallet.isConfigured()).thenReturn(true);
        var resource = resource(mock(PermissionsService.class), wallet, device(1, "account-one"));
        assertEquals(400, resource.getWalletRecords(1, null, new Date(1)).getStatus());
        assertEquals(400, resource.getWalletRecords(1, new Date(1), new Date(0)).getStatus());
        assertEquals(400, resource.getWalletRecords(1, new Date(0), new Date(367L * 86400000)).getStatus());
        verify(wallet, never()).getRecords(any(), any(), any());
    }
}
