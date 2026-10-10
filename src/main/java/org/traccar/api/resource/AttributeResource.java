/*
 * Copyright 2017 - 2026 Anton Tananaev (anton@traccar.org)
 * Copyright 2017 - 2018 Andrey Kunitsyn (andrey@traccar.org)
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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

import org.traccar.api.ExtendedObjectResource;
import org.traccar.dashboard.WalletTokenStore;
import org.traccar.storage.StorageException;
import org.traccar.model.Attribute;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.handler.ComputedAttributesHandler;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

@Path("attributes/computed")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AttributeResource extends ExtendedObjectResource<Attribute> {

    @Inject
    private CacheManager cacheManager;

    @Inject
    private ComputedAttributesHandler.Late computedAttributesHandler;

    public AttributeResource() {
        super(Attribute.class, "description", List.of("description"));
    }

    @Override
    protected Condition getFilterCondition() throws StorageException {
        return permissionsService.notAdmin(getUserId())
                ? new Condition.Compare("attribute", "<>", WalletTokenStore.ATTRIBUTE_NAME) : null;
    }

    @Override
    @Path("{id}")
    @GET
    public Response getSingle(@PathParam("id") long id) throws StorageException {
        permissionsService.checkPermission(Attribute.class, getUserId(), id);
        var attribute = storage.getObject(Attribute.class, new Request(
                new Columns.All(), new Condition.Equals("id", id)));
        if (WalletTokenStore.isTokenAttribute(attribute) && permissionsService.notAdmin(getUserId())) {
            throw new ForbiddenException();
        }
        return attribute != null ? Response.ok(attribute).build() : Response.status(Response.Status.NOT_FOUND).build();
    }

    private void validateWalletToken(Attribute entity) throws StorageException {
        if (WalletTokenStore.isTokenAttribute(entity)) {
            WalletTokenStore.parseToken(entity);
            try (var existing = storage.getObjectsStream(Attribute.class, new Request(
                    new Columns.All(), new Condition.Equals("attribute", WalletTokenStore.ATTRIBUTE_NAME)))) {
                if (existing.anyMatch(attribute -> attribute.getId() != entity.getId())) {
                    throw new IllegalArgumentException("Only one wallet_token computed attribute is allowed");
                }
            }
        }
    }

    @POST
    @Path("test")
    public Response test(@QueryParam("deviceId") long deviceId, Attribute entity) throws Exception {
        permissionsService.checkAdmin(getUserId());
        validateWalletToken(entity);
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);

        Position position = storage.getObject(Position.class, new Request(
                new Columns.All(),
                new Condition.LatestPositions(deviceId)));

        var key = new Object();
        try {
            cacheManager.addDevice(position.getDeviceId(), key);
            Object result = computedAttributesHandler.computeAttribute(entity, position);
            if (result != null) {
                return switch (entity.getType()) {
                    case "number", "boolean" -> Response.ok(result).build();
                    default -> Response.ok(result.toString()).build();
                };
            } else {
                return Response.noContent().build();
            }
        } finally {
            cacheManager.removeDevice(position.getDeviceId(), key);
        }
    }

    @POST
    public Response add(Attribute entity) throws Exception {
        permissionsService.checkAdmin(getUserId());
        validateWalletToken(entity);
        return super.add(entity);
    }

    @Path("{id}")
    @PUT
    public Response update(Attribute entity) throws Exception {
        permissionsService.checkAdmin(getUserId());
        validateWalletToken(entity);
        return super.update(entity);
    }

    @Path("{id}")
    @DELETE
    public Response remove(@PathParam("id") long id) throws Exception {
        permissionsService.checkAdmin(getUserId());
        return super.remove(id);
    }

}
