/*
 * Copyright 2026 Traccar contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.api.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.api.BaseResource;
import org.traccar.dashboard.WalletClient;
import org.traccar.model.Device;
import org.traccar.model.UserRestrictions;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.time.Duration;
import java.util.Date;
import java.util.Map;

@Path("dashboard")
@Produces(MediaType.APPLICATION_JSON)
public class DashboardResource extends BaseResource {

    @Inject
    private WalletClient walletClient;

    private Device getDevice(long deviceId) throws StorageException {
        if (deviceId <= 0) {
            throw new BadRequestException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "walletDeviceNotFound")).build());
        }
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        var device = storage.getObject(Device.class, new Request(
                new Columns.All(), new Condition.Equals("id", deviceId)));
        if (device == null) {
            throw new NotFoundException(Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "walletDeviceNotFound")).build());
        }
        return device;
    }

    private String getAccountId(Device device) {
        var value = device.getAttributes().get("accountId");
        return value instanceof String accountId ? accountId.trim() : "";
    }

    @Path("wallet/status")
    @GET
    public Response getWalletStatus(@QueryParam("deviceId") long deviceId) throws StorageException {
        permissionsService.checkAdmin(getUserId());
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        var device = getDevice(deviceId);
        String accountId = getAccountId(device);
        try {
            boolean tokenConfigured = walletClient.isConfigured();
            return Response.ok(Map.of(
                    "configured", tokenConfigured && !accountId.isEmpty(),
                    "tokenConfigured", tokenConfigured,
                    "deviceId", device.getId(),
                    "accountId", accountId)).header("Cache-Control", "no-store").build();
        } catch (StorageException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error",
                    "walletTokenDuplicate".equals(e.getMessage())
                            || "walletTokenAttributeInvalid".equals(e.getMessage())
                            ? e.getMessage() : "walletSettingsUnavailable")).build();
        }
    }

    @Path("wallet")
    @GET
    public Response getWalletRecords(
            @QueryParam("deviceId") long deviceId,
            @QueryParam("from") Date from, @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkAdmin(getUserId());
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        var device = getDevice(deviceId);
        String accountId = getAccountId(device);
        if (accountId.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "walletAccountMissing")).build();
        }
        if (!walletClient.isConfigured()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "walletNotConfigured")).build();
        }
        if (from == null || to == null || from.after(to)
                || Duration.between(from.toInstant(), to.toInstant()).compareTo(Duration.ofDays(366)) > 0) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "walletInvalidPeriod")).build();
        }
        try {
            return Response.ok(walletClient.getRecords(accountId, from.toInstant(), to.toInstant()))
                    .header("Cache-Control", "no-store").build();
        } catch (WalletClient.WalletException e) {
            var response = Response.status(e.getRetryAfter() > 0 ? 429 : 502).entity(Map.of("error", e.getCode()));
            if (e.getRetryAfter() > 0) {
                response.header("Retry-After", e.getRetryAfter());
            }
            return response.build();
        }
    }
}
