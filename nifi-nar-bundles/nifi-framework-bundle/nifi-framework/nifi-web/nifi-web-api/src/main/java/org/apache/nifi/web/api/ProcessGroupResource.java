/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.web.api;

import com.sun.jersey.api.core.ResourceContext;
import com.sun.jersey.multipart.FormDataParam;
import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiOperation;
import com.wordnik.swagger.annotations.ApiParam;
import com.wordnik.swagger.annotations.ApiResponse;
import com.wordnik.swagger.annotations.ApiResponses;
import com.wordnik.swagger.annotations.Authorization;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.authorization.AuthorizableLookup;
import org.apache.nifi.authorization.AuthorizeControllerServiceReference;
import org.apache.nifi.authorization.Authorizer;
import org.apache.nifi.authorization.ComponentAuthorizable;
import org.apache.nifi.authorization.ProcessGroupAuthorizable;
import org.apache.nifi.authorization.RequestAction;
import org.apache.nifi.authorization.SnippetAuthorizable;
import org.apache.nifi.authorization.TemplateContentsAuthorizable;
import org.apache.nifi.authorization.resource.Authorizable;
import org.apache.nifi.authorization.user.NiFiUser;
import org.apache.nifi.authorization.user.NiFiUserUtils;
import org.apache.nifi.bundle.BundleCoordinate;
import org.apache.nifi.connectable.ConnectableType;
import org.apache.nifi.controller.serialization.FlowEncodingVersion;
import org.apache.nifi.remote.util.SiteToSiteRestApiClient;
import org.apache.nifi.util.BundleUtils;
import org.apache.nifi.web.NiFiServiceFacade;
import org.apache.nifi.web.ResourceNotFoundException;
import org.apache.nifi.web.Revision;
import org.apache.nifi.web.api.dto.BundleDTO;
import org.apache.nifi.web.api.dto.ConnectionDTO;
import org.apache.nifi.web.api.dto.ControllerServiceDTO;
import org.apache.nifi.web.api.dto.FlowSnippetDTO;
import org.apache.nifi.web.api.dto.PositionDTO;
import org.apache.nifi.web.api.dto.ProcessGroupDTO;
import org.apache.nifi.web.api.dto.ProcessorConfigDTO;
import org.apache.nifi.web.api.dto.ProcessorDTO;
import org.apache.nifi.web.api.dto.RemoteProcessGroupDTO;
import org.apache.nifi.web.api.dto.TemplateDTO;
import org.apache.nifi.web.api.dto.flow.FlowDTO;
import org.apache.nifi.web.api.entity.ConnectionEntity;
import org.apache.nifi.web.api.entity.ConnectionsEntity;
import org.apache.nifi.web.api.entity.ControllerServiceEntity;
import org.apache.nifi.web.api.entity.CopySnippetRequestEntity;
import org.apache.nifi.web.api.entity.CreateTemplateRequestEntity;
import org.apache.nifi.web.api.entity.FlowEntity;
import org.apache.nifi.web.api.entity.FlowSnippetEntity;
import org.apache.nifi.web.api.entity.FunnelEntity;
import org.apache.nifi.web.api.entity.FunnelsEntity;
import org.apache.nifi.web.api.entity.InputPortsEntity;
import org.apache.nifi.web.api.entity.InstantiateTemplateRequestEntity;
import org.apache.nifi.web.api.entity.LabelEntity;
import org.apache.nifi.web.api.entity.LabelsEntity;
import org.apache.nifi.web.api.entity.OutputPortsEntity;
import org.apache.nifi.web.api.entity.PortEntity;
import org.apache.nifi.web.api.entity.ProcessGroupEntity;
import org.apache.nifi.web.api.entity.ProcessGroupsEntity;
import org.apache.nifi.web.api.entity.ProcessorEntity;
import org.apache.nifi.web.api.entity.ProcessorsEntity;
import org.apache.nifi.web.api.entity.RemoteProcessGroupEntity;
import org.apache.nifi.web.api.entity.RemoteProcessGroupsEntity;
import org.apache.nifi.web.api.entity.TemplateEntity;
import org.apache.nifi.web.api.request.ClientIdParameter;
import org.apache.nifi.web.api.request.LongParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * RESTful endpoint for managing a Group.
 */
@Path("/process-groups")
@Api(
        value = "/process-groups",
        description = "Endpoint for managing a Process Group."
)
public class ProcessGroupResource extends ApplicationResource {

    private static final Logger logger = LoggerFactory.getLogger(ProcessGroupResource.class);

    @Context
    private ResourceContext resourceContext;

    private NiFiServiceFacade serviceFacade;
    private Authorizer authorizer;

    private ProcessorResource processorResource;
    private InputPortResource inputPortResource;
    private OutputPortResource outputPortResource;
    private FunnelResource funnelResource;
    private LabelResource labelResource;
    private RemoteProcessGroupResource remoteProcessGroupResource;
    private ConnectionResource connectionResource;
    private TemplateResource templateResource;
    private ControllerServiceResource controllerServiceResource;

    /**
     * Populates the remaining fields in the specified process groups.
     *
     * @param processGroupEntities groups
     * @return group dto
     */
    public Set<ProcessGroupEntity> populateRemainingProcessGroupEntitiesContent(Set<ProcessGroupEntity> processGroupEntities) {
        for (ProcessGroupEntity processGroupEntity : processGroupEntities) {
            populateRemainingProcessGroupEntityContent(processGroupEntity);
        }
        return processGroupEntities;
    }

    /**
     * Populates the remaining fields in the specified process group.
     *
     * @param processGroupEntity group
     * @return group dto
     */
    public ProcessGroupEntity populateRemainingProcessGroupEntityContent(ProcessGroupEntity processGroupEntity) {
        processGroupEntity.setUri(generateResourceUri("process-groups", processGroupEntity.getId()));
        return processGroupEntity;
    }

    /**
     * Populates the remaining content of the specified snippet.
     */
    private FlowDTO populateRemainingSnippetContent(FlowDTO flow) {
        processorResource.populateRemainingProcessorEntitiesContent(flow.getProcessors());
        connectionResource.populateRemainingConnectionEntitiesContent(flow.getConnections());
        inputPortResource.populateRemainingInputPortEntitiesContent(flow.getInputPorts());
        outputPortResource.populateRemainingOutputPortEntitiesContent(flow.getOutputPorts());
        remoteProcessGroupResource.populateRemainingRemoteProcessGroupEntitiesContent(flow.getRemoteProcessGroups());
        funnelResource.populateRemainingFunnelEntitiesContent(flow.getFunnels());
        labelResource.populateRemainingLabelEntitiesContent(flow.getLabels());

        // go through each process group child and populate its uri
        if (flow.getProcessGroups() != null) {
            populateRemainingProcessGroupEntitiesContent(flow.getProcessGroups());
        }

        return flow;
    }

    /**
     * Retrieves the contents of the specified group.
     *
     * @param groupId The id of the process group.
     * @return A processGroupEntity.
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    @ApiOperation(
            value = "Gets a process group",
            response = ProcessGroupEntity.class,
            authorizations = {
                    @Authorization(value = "Read - /process-groups/{uuid}", type = "")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response getProcessGroup(
            @ApiParam(
                    value = "The process group id.",
                    required = false
            )
            @PathParam("id") final String groupId) {

        if (isReplicateRequest()) {
            return replicate(HttpMethod.GET);
        }

        // authorize access
        serviceFacade.authorizeAccess(lookup -> {
            final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
            processGroup.authorize(authorizer, RequestAction.READ, NiFiUserUtils.getNiFiUser());
        });

        // get this process group contents
        final ProcessGroupEntity entity = serviceFacade.getProcessGroup(groupId);
        populateRemainingProcessGroupEntityContent(entity);

        if (entity.getComponent() != null) {
            entity.getComponent().setContents(null);
        }

        return clusterContext(generateOkResponse(entity)).build();
    }

    /**
     * Updates the specified process group.
     *
     * @param httpServletRequest request
     * @param id                 The id of the process group.
     * @param requestProcessGroupEntity A processGroupEntity.
     * @return A processGroupEntity.
     */
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    @ApiOperation(
            value = "Updates a process group",
            response = ProcessGroupEntity.class,
            authorizations = {
                    @Authorization(value = "Write - /process-groups/{uuid}", type = "")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response updateProcessGroup(
            @Context final HttpServletRequest httpServletRequest,
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") final String id,
            @ApiParam(
                    value = "The process group configuration details.",
                    required = true
            ) final ProcessGroupEntity requestProcessGroupEntity) {

        if (requestProcessGroupEntity == null || requestProcessGroupEntity.getComponent() == null) {
            throw new IllegalArgumentException("Process group details must be specified.");
        }

        if (requestProcessGroupEntity.getRevision() == null) {
            throw new IllegalArgumentException("Revision must be specified.");
        }

        // ensure the same id is being used
        final ProcessGroupDTO requestProcessGroupDTO = requestProcessGroupEntity.getComponent();
        if (!id.equals(requestProcessGroupDTO.getId())) {
            throw new IllegalArgumentException(String.format("The process group id (%s) in the request body does "
                    + "not equal the process group id of the requested resource (%s).", requestProcessGroupDTO.getId(), id));
        }

        final PositionDTO proposedPosition = requestProcessGroupDTO.getPosition();
        if (proposedPosition != null) {
            if (proposedPosition.getX() == null || proposedPosition.getY() == null) {
                throw new IllegalArgumentException("The x and y coordinate of the proposed position must be specified.");
            }
        }

        if (isReplicateRequest()) {
            return replicate(HttpMethod.PUT, requestProcessGroupEntity);
        }

        // handle expects request (usually from the cluster manager)
        final Revision requestRevision = getRevision(requestProcessGroupEntity, id);
        return withWriteLock(
                serviceFacade,
                requestProcessGroupEntity,
                requestRevision,
                lookup -> {
                    Authorizable authorizable = lookup.getProcessGroup(id).getAuthorizable();
                    authorizable.authorize(authorizer, RequestAction.WRITE, NiFiUserUtils.getNiFiUser());
                },
                null,
                (revision, processGroupEntity) -> {
                    // update the process group
                    final ProcessGroupEntity entity = serviceFacade.updateProcessGroup(revision, processGroupEntity.getComponent());
                    populateRemainingProcessGroupEntityContent(entity);

                    return clusterContext(generateOkResponse(entity)).build();
                }
        );
    }

    /**
     * Removes the specified process group reference.
     *
     * @param httpServletRequest request
     * @param version            The revision is used to verify the client is working with the latest version of the flow.
     * @param clientId           Optional client id. If the client id is not specified, a new one will be generated. This value (whether specified or generated) is included in the response.
     * @param id                 The id of the process group to be removed.
     * @return A processGroupEntity.
     */
    @DELETE
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    @ApiOperation(
            value = "Deletes a process group",
            response = ProcessGroupEntity.class,
            authorizations = {
                    @Authorization(value = "Write - /process-groups/{uuid}", type = ""),
                    @Authorization(value = "Write - Parent Process Group - /process-groups/{uuid}", type = ""),
                    @Authorization(value = "Read - any referenced Controller Services by any encapsulated components - /controller-services/{uuid}", type = ""),
                    @Authorization(value = "Write - /{component-type}/{uuid} - For all encapsulated components", type = "")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response removeProcessGroup(
            @Context final HttpServletRequest httpServletRequest,
            @ApiParam(
                    value = "The revision is used to verify the client is working with the latest version of the flow.",
                    required = false
            )
            @QueryParam(VERSION) final LongParameter version,
            @ApiParam(
                    value = "If the client id is not specified, new one will be generated. This value (whether specified or generated) is included in the response.",
                    required = false
            )
            @QueryParam(CLIENT_ID) @DefaultValue(StringUtils.EMPTY) final ClientIdParameter clientId,
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") final String id) {

        // replicate if cluster manager
        if (isReplicateRequest()) {
            return replicate(HttpMethod.DELETE);
        }

        final ProcessGroupEntity requestProcessGroupEntity = new ProcessGroupEntity();
        requestProcessGroupEntity.setId(id);

        // handle expects request (usually from the cluster manager)
        final Revision requestRevision = new Revision(version == null ? null : version.getLong(), clientId.getClientId(), id);
        return withWriteLock(
                serviceFacade,
                requestProcessGroupEntity,
                requestRevision,
                lookup -> {
                    final ProcessGroupAuthorizable processGroupAuthorizable = lookup.getProcessGroup(id);

                    // ensure write to this process group and all encapsulated components including templates and controller services. additionally, ensure
                    // read to any referenced services by encapsulated components
                    authorizeProcessGroup(processGroupAuthorizable, authorizer, lookup, RequestAction.WRITE, true, true, true, false);

                    // ensure write permission to the parent process group, if applicable... if this is the root group the
                    // request will fail later but still need to handle authorization here
                    final Authorizable parentAuthorizable = processGroupAuthorizable.getAuthorizable().getParentAuthorizable();
                    if (parentAuthorizable != null) {
                        parentAuthorizable.authorize(authorizer, RequestAction.WRITE, NiFiUserUtils.getNiFiUser());
                    }
                },
                () -> serviceFacade.verifyDeleteProcessGroup(id),
                (revision, processGroupEntity) -> {
                    // delete the process group
                    final ProcessGroupEntity entity = serviceFacade.deleteProcessGroup(revision, processGroupEntity.getId());

                    // create the response
                    return clusterContext(generateOkResponse(entity)).build();
                }
        );
    }

    /**
     * Adds the specified process group.
     *
     * @param httpServletRequest request
     * @param groupId            The group id
     * @param requestProcessGroupEntity A processGroupEntity
     * @return A processGroupEntity
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/process-groups")
    @ApiOperation(
            value = "Creates a process group",
            response = ProcessGroupEntity.class,
            authorizations = {
                    @Authorization(value = "Write - /process-groups/{uuid}", type = "")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response createProcessGroup(
            @Context final HttpServletRequest httpServletRequest,
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") final String groupId,
            @ApiParam(
                    value = "The process group configuration details.",
                    required = true
            ) final ProcessGroupEntity requestProcessGroupEntity) {

        if (requestProcessGroupEntity == null || requestProcessGroupEntity.getComponent() == null) {
            throw new IllegalArgumentException("Process group details must be specified.");
        }

        if (requestProcessGroupEntity.getRevision() == null || (requestProcessGroupEntity.getRevision().getVersion() == null || requestProcessGroupEntity.getRevision().getVersion() != 0)) {
            throw new IllegalArgumentException("A revision of 0 must be specified when creating a new Process group.");
        }

        if (requestProcessGroupEntity.getComponent().getId() != null) {
            throw new IllegalArgumentException("Process group ID cannot be specified.");
        }

        final PositionDTO proposedPosition = requestProcessGroupEntity.getComponent().getPosition();
        if (proposedPosition != null) {
            if (proposedPosition.getX() == null || proposedPosition.getY() == null) {
                throw new IllegalArgumentException("The x and y coordinate of the proposed position must be specified.");
            }
        }

        if (requestProcessGroupEntity.getComponent().getParentGroupId() != null && !groupId.equals(requestProcessGroupEntity.getComponent().getParentGroupId())) {
            throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                    requestProcessGroupEntity.getComponent().getParentGroupId(), groupId));
        }
        requestProcessGroupEntity.getComponent().setParentGroupId(groupId);

        if (isReplicateRequest()) {
            return replicate(HttpMethod.POST, requestProcessGroupEntity);
        }

        return withWriteLock(
                serviceFacade,
                requestProcessGroupEntity,
                lookup -> {
                    final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
                    processGroup.authorize(authorizer, RequestAction.WRITE, NiFiUserUtils.getNiFiUser());
                },
                null,
                processGroupGroupEntity -> {
                    // set the processor id as appropriate
                    processGroupGroupEntity.getComponent().setId(generateUuid());

                    // create the process group contents
                    final Revision revision = getRevision(processGroupGroupEntity, processGroupGroupEntity.getComponent().getId());
                    final ProcessGroupEntity entity = serviceFacade.createProcessGroup(revision, groupId, processGroupGroupEntity.getComponent());
                    populateRemainingProcessGroupEntityContent(entity);

                    // generate a 201 created response
                    String uri = entity.getUri();
                    return clusterContext(generateCreatedResponse(URI.create(uri), entity)).build();
                }
        );
    }

    /**
     * Retrieves all the processors in this NiFi.
     *
     * @return A processorsEntity.
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/process-groups")
    @ApiOperation(
            value = "Gets all process groups",
            response = ProcessorsEntity.class,
            authorizations = {
                    @Authorization(value = "Read - /process-groups/{uuid}", type = "")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response getProcessGroups(
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") final String groupId) {

        if (isReplicateRequest()) {
            return replicate(HttpMethod.GET);
        }

        // authorize access
        serviceFacade.authorizeAccess(lookup -> {
            final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
            processGroup.authorize(authorizer, RequestAction.READ, NiFiUserUtils.getNiFiUser());
        });

        // get the process groups
        final Set<ProcessGroupEntity> entities = serviceFacade.getProcessGroups(groupId);

        // always prune the contents
        for (final ProcessGroupEntity entity : entities) {
            if (entity.getComponent() != null) {
                entity.getComponent().setContents(null);
            }
        }

        // create the response entity
        final ProcessGroupsEntity entity = new ProcessGroupsEntity();
        entity.setProcessGroups(populateRemainingProcessGroupEntitiesContent(entities));

        // generate the response
        return clusterContext(generateOkResponse(entity)).build();
    }

    // ----------
    // processors
    // ----------

    /**
     * Creates a new processor.
     *
     * @param httpServletRequest request
     * @param groupId            The group id
     * @param requestProcessorEntity    A processorEntity.
     * @return A processorEntity.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/processors")
    @ApiOperation(
            value = "Creates a new processor",
            response = ProcessorEntity.class,
            authorizations = {
                    @Authorization(value = "Write - /process-groups/{uuid}", type = ""),
                    @Authorization(value = "Read - any referenced Controller Services - /controller-services/{uuid}", type = ""),
                    @Authorization(value = "Write - if the Processor is restricted - /restricted-components", type = "")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response createProcessor(
            @Context final HttpServletRequest httpServletRequest,
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") final String groupId,
            @ApiParam(
                    value = "The processor configuration details.",
                    required = true
            ) final ProcessorEntity requestProcessorEntity) {

        if (requestProcessorEntity == null || requestProcessorEntity.getComponent() == null) {
            throw new IllegalArgumentException("Processor details must be specified.");
        }

        if (requestProcessorEntity.getRevision() == null || (requestProcessorEntity.getRevision().getVersion() == null || requestProcessorEntity.getRevision().getVersion() != 0)) {
            throw new IllegalArgumentException("A revision of 0 must be specified when creating a new Processor.");
        }

        final ProcessorDTO requestProcessor = requestProcessorEntity.getComponent();
        if (requestProcessor.getId() != null) {
            throw new IllegalArgumentException("Processor ID cannot be specified.");
        }

        if (StringUtils.isBlank(requestProcessor.getType())) {
            throw new IllegalArgumentException("The type of processor to create must be specified.");
        }

        final PositionDTO proposedPosition = requestProcessor.getPosition();
        if (proposedPosition != null) {
            if (proposedPosition.getX() == null || proposedPosition.getY() == null) {
                throw new IllegalArgumentException("The x and y coordinate of the proposed position must be specified.");
            }
        }

        if (requestProcessor.getParentGroupId() != null && !groupId.equals(requestProcessor.getParentGroupId())) {
            throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                    requestProcessor.getParentGroupId(), groupId));
        }
        requestProcessor.setParentGroupId(groupId);

        if (isReplicateRequest()) {
            return replicate(HttpMethod.POST, requestProcessorEntity);
        }

        return withWriteLock(
                serviceFacade,
                requestProcessorEntity,
                lookup -> {
                    final NiFiUser user = NiFiUserUtils.getNiFiUser();

                    final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
                    processGroup.authorize(authorizer, RequestAction.WRITE, user);

                    ComponentAuthorizable authorizable = null;
                    try {
                        authorizable = lookup.getConfigurableComponent(requestProcessor.getType(), requestProcessor.getBundle());

                        if (authorizable.isRestricted()) {
                            lookup.getRestrictedComponents().authorize(authorizer, RequestAction.WRITE, user);
                        }

                        final ProcessorConfigDTO config = requestProcessor.getConfig();
                        if (config != null && config.getProperties() != null) {
                            AuthorizeControllerServiceReference.authorizeControllerServiceReferences(config.getProperties(), authorizable, authorizer, lookup);
                        }
                    } finally {
                        if (authorizable != null) {
                            authorizable.cleanUpResources();
                        }
                    }
                },
                () -> serviceFacade.verifyCreateProcessor(requestProcessor),
                processorEntity -> {
                    final ProcessorDTO processor = processorEntity.getComponent();

                    // set the processor id as appropriate
                    processor.setId(generateUuid());

                    // create the new processor
                    final Revision revision = getRevision(processorEntity, processor.getId());
                    final ProcessorEntity entity = serviceFacade.createProcessor(revision, groupId, processor);
                    processorResource.populateRemainingProcessorEntityContent(entity);

                    // generate a 201 created response
                    String uri = entity.getUri();
                    return clusterContext(generateCreatedResponse(URI.create(uri), entity)).build();
                }
        );
    }

    /**
     * Retrieves all the processors in this NiFi.
     *
     * @param groupId group id
     * @return A processorsEntity.
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/processors")
    @ApiOperation(
            value = "Gets all processors",
            response = ProcessorsEntity.class,
            authorizations = {
                    @Authorization(value = "Read - /process-groups/{uuid}", type = "")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response getProcessors(
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") final String groupId) {

        if (isReplicateRequest()) {
            return replicate(HttpMethod.GET);
        }

        // authorize access
        serviceFacade.authorizeAccess(lookup -> {
            final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
            processGroup.authorize(authorizer, RequestAction.READ, NiFiUserUtils.getNiFiUser());
        });

        // get the processors
        final Set<ProcessorEntity> processors = serviceFacade.getProcessors(groupId);

        // create the response entity
        final ProcessorsEntity entity = new ProcessorsEntity();
        entity.setProcessors(processorResource.populateRemainingProcessorEntitiesContent(processors));

        // generate the response
        return clusterContext(generateOkResponse(entity)).build();
    }

    // -----------
    // input ports
    // -----------

    /**
     * Creates a new input port.
     *
     * @param httpServletRequest request
     * @param groupId            The group id
     * @param requestPortEntity         A inputPortEntity.
     * @return A inputPortEntity.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/input-ports")
    @ApiOperation(
            value = "Creates an input port",
            response = PortEntity.class,
            authorizations = {
                    @Authorization(value = "Write - /process-groups/{uuid}", type = "")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response createInputPort(
            @Context final HttpServletRequest httpServletRequest,
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") final String groupId,
            @ApiParam(
                    value = "The input port configuration details.",
                    required = true
            ) final PortEntity requestPortEntity) {

        if (requestPortEntity == null || requestPortEntity.getComponent() == null) {
            throw new IllegalArgumentException("Port details must be specified.");
        }

        if (requestPortEntity.getRevision() == null || (requestPortEntity.getRevision().getVersion() == null || requestPortEntity.getRevision().getVersion() != 0)) {
            throw new IllegalArgumentException("A revision of 0 must be specified when creating a new Input port.");
        }

        if (requestPortEntity.getComponent().getId() != null) {
            throw new IllegalArgumentException("Input port ID cannot be specified.");
        }

        final PositionDTO proposedPosition = requestPortEntity.getComponent().getPosition();
        if (proposedPosition != null) {
            if (proposedPosition.getX() == null || proposedPosition.getY() == null) {
                throw new IllegalArgumentException("The x and y coordinate of the proposed position must be specified.");
            }
        }

        if (requestPortEntity.getComponent().getParentGroupId() != null && !groupId.equals(requestPortEntity.getComponent().getParentGroupId())) {
            throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                    requestPortEntity.getComponent().getParentGroupId(), groupId));
        }
        requestPortEntity.getComponent().setParentGroupId(groupId);

        if (isReplicateRequest()) {
            return replicate(HttpMethod.POST, requestPortEntity);
        }

        return withWriteLock(
                serviceFacade,
                requestPortEntity,
                lookup -> {
                    final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
                    processGroup.authorize(authorizer, RequestAction.WRITE, NiFiUserUtils.getNiFiUser());
                },
                null,
                portEntity -> {
                    // set the processor id as appropriate
                    portEntity.getComponent().setId(generateUuid());

                    // create the input port and generate the json
                    final Revision revision = getRevision(portEntity, portEntity.getComponent().getId());
                    final PortEntity entity = serviceFacade.createInputPort(revision, groupId, portEntity.getComponent());
                    inputPortResource.populateRemainingInputPortEntityContent(entity);

                    // build the response
                    return clusterContext(generateCreatedResponse(URI.create(entity.getUri()), entity)).build();
                }
        );
    }

    /**
     * Retrieves all the of input ports in this NiFi.
     *
     * @return A inputPortsEntity.
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/input-ports")
    @ApiOperation(
            value = "Gets all input ports",
            response = InputPortsEntity.class,
            authorizations = {
                    @Authorization(value = "Read - /process-groups/{uuid}", type = "")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response getInputPorts(
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") final String groupId) {

        if (isReplicateRequest()) {
            return replicate(HttpMethod.GET);
        }

        // authorize access
        serviceFacade.authorizeAccess(lookup -> {
            final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
            processGroup.authorize(authorizer, RequestAction.READ, NiFiUserUtils.getNiFiUser());
        });

        // get all the input ports
        final Set<PortEntity> inputPorts = serviceFacade.getInputPorts(groupId);

        final InputPortsEntity entity = new InputPortsEntity();
        entity.setInputPorts(inputPortResource.populateRemainingInputPortEntitiesContent(inputPorts));

        // generate the response
        return clusterContext(generateOkResponse(entity)).build();
    }

    // ------------
    // output ports
    // ------------

    /**
     * Creates a new output port.
     *
     * @param httpServletRequest request
     * @param groupId            The group id
     * @param requestPortEntity         A outputPortEntity.
     * @return A outputPortEntity.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/output-ports")
    @ApiOperation(
            value = "Creates an output port",
            response = PortEntity.class,
            authorizations = {
                    @Authorization(value = "Write - /process-groups/{uuid}", type = "")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response createOutputPort(
            @Context final HttpServletRequest httpServletRequest,
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") final String groupId,
            @ApiParam(
                    value = "The output port configuration.",
                    required = true
            ) final PortEntity requestPortEntity) {

        if (requestPortEntity == null || requestPortEntity.getComponent() == null) {
            throw new IllegalArgumentException("Port details must be specified.");
        }

        if (requestPortEntity.getRevision() == null || (requestPortEntity.getRevision().getVersion() == null || requestPortEntity.getRevision().getVersion() != 0)) {
            throw new IllegalArgumentException("A revision of 0 must be specified when creating a new Output port.");
        }

        if (requestPortEntity.getComponent().getId() != null) {
            throw new IllegalArgumentException("Output port ID cannot be specified.");
        }

        final PositionDTO proposedPosition = requestPortEntity.getComponent().getPosition();
        if (proposedPosition != null) {
            if (proposedPosition.getX() == null || proposedPosition.getY() == null) {
                throw new IllegalArgumentException("The x and y coordinate of the proposed position must be specified.");
            }
        }

        if (requestPortEntity.getComponent().getParentGroupId() != null && !groupId.equals(requestPortEntity.getComponent().getParentGroupId())) {
            throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                    requestPortEntity.getComponent().getParentGroupId(), groupId));
        }
        requestPortEntity.getComponent().setParentGroupId(groupId);

        if (isReplicateRequest()) {
            return replicate(HttpMethod.POST, requestPortEntity);
        }

        return withWriteLock(
                serviceFacade,
                requestPortEntity,
                lookup -> {
                    final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
                    processGroup.authorize(authorizer, RequestAction.WRITE, NiFiUserUtils.getNiFiUser());
                },
                null,
                portEntity -> {
                    // set the processor id as appropriate
                    portEntity.getComponent().setId(generateUuid());

                    // create the output port and generate the json
                    final Revision revision = getRevision(portEntity, portEntity.getComponent().getId());
                    final PortEntity entity = serviceFacade.createOutputPort(revision, groupId, portEntity.getComponent());
                    outputPortResource.populateRemainingOutputPortEntityContent(entity);

                    // build the response
                    return clusterContext(generateCreatedResponse(URI.create(entity.getUri()), entity)).build();
                }
        );
    }

    /**
     * Retrieves all the of output ports in this NiFi.
     *
     * @return A outputPortsEntity.
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/output-ports")
    @ApiOperation(
            value = "Gets all output ports",
            response = OutputPortsEntity.class,
            authorizations = {
                    @Authorization(value = "Read - /process-groups/{uuid}", type = "")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response getOutputPorts(
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") final String groupId) {

        if (isReplicateRequest()) {
            return replicate(HttpMethod.GET);
        }

        // authorize access
        serviceFacade.authorizeAccess(lookup -> {
            final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
            processGroup.authorize(authorizer, RequestAction.READ, NiFiUserUtils.getNiFiUser());
        });

        // get all the output ports
        final Set<PortEntity> outputPorts = serviceFacade.getOutputPorts(groupId);

        // create the response entity
        final OutputPortsEntity entity = new OutputPortsEntity();
        entity.setOutputPorts(outputPortResource.populateRemainingOutputPortEntitiesContent(outputPorts));

        // generate the response
        return clusterContext(generateOkResponse(entity)).build();
    }

    // -------
    // funnels
    // -------

    /**
     * Creates a new Funnel.
     *
     * @param httpServletRequest request
     * @param groupId            The group id
     * @param requestFunnelEntity       A funnelEntity.
     * @return A funnelEntity.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/funnels")
    @ApiOperation(
            value = "Creates a funnel",
            response = FunnelEntity.class,
            authorizations = {
                    @Authorization(value = "Write - /process-groups/{uuid}", type = "")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response createFunnel(
            @Context final HttpServletRequest httpServletRequest,
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") final String groupId,
            @ApiParam(
                    value = "The funnel configuration details.",
                    required = true
            ) final FunnelEntity requestFunnelEntity) {

        if (requestFunnelEntity == null || requestFunnelEntity.getComponent() == null) {
            throw new IllegalArgumentException("Funnel details must be specified.");
        }

        if (requestFunnelEntity.getRevision() == null || (requestFunnelEntity.getRevision().getVersion() == null || requestFunnelEntity.getRevision().getVersion() != 0)) {
            throw new IllegalArgumentException("A revision of 0 must be specified when creating a new Funnel.");
        }

        if (requestFunnelEntity.getComponent().getId() != null) {
            throw new IllegalArgumentException("Funnel ID cannot be specified.");
        }

        final PositionDTO proposedPosition = requestFunnelEntity.getComponent().getPosition();
        if (proposedPosition != null) {
            if (proposedPosition.getX() == null || proposedPosition.getY() == null) {
                throw new IllegalArgumentException("The x and y coordinate of the proposed position must be specified.");
            }
        }

        if (requestFunnelEntity.getComponent().getParentGroupId() != null && !groupId.equals(requestFunnelEntity.getComponent().getParentGroupId())) {
            throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                    requestFunnelEntity.getComponent().getParentGroupId(), groupId));
        }
        requestFunnelEntity.getComponent().setParentGroupId(groupId);

        if (isReplicateRequest()) {
            return replicate(HttpMethod.POST, requestFunnelEntity);
        }

        return withWriteLock(
                serviceFacade,
                requestFunnelEntity,
                lookup -> {
                    final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
                    processGroup.authorize(authorizer, RequestAction.WRITE, NiFiUserUtils.getNiFiUser());
                },
                null,
                funnelEntity -> {
                    // set the processor id as appropriate
                    funnelEntity.getComponent().setId(generateUuid());

                    // create the funnel and generate the json
                    final Revision revision = getRevision(funnelEntity, funnelEntity.getComponent().getId());
                    final FunnelEntity entity = serviceFacade.createFunnel(revision, groupId, funnelEntity.getComponent());
                    funnelResource.populateRemainingFunnelEntityContent(entity);

                    // build the response
                    return clusterContext(generateCreatedResponse(URI.create(entity.getUri()), entity)).build();
                }
        );
    }

    /**
     * Retrieves all the of funnels in this NiFi.
     *
     * @return A funnelsEntity.
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/funnels")
    @ApiOperation(
            value = "Gets all funnels",
            response = FunnelsEntity.class,
            authorizations = {
                    @Authorization(value = "Read - /process-groups/{uuid}", type = "")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response getFunnels(
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") final String groupId) {

        if (isReplicateRequest()) {
            return replicate(HttpMethod.GET);
        }

        // authorize access
        serviceFacade.authorizeAccess(lookup -> {
            final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
            processGroup.authorize(authorizer, RequestAction.READ, NiFiUserUtils.getNiFiUser());
        });

        // get all the funnels
        final Set<FunnelEntity> funnels = serviceFacade.getFunnels(groupId);

        // create the response entity
        final FunnelsEntity entity = new FunnelsEntity();
        entity.setFunnels(funnelResource.populateRemainingFunnelEntitiesContent(funnels));

        // generate the response
        return clusterContext(generateOkResponse(entity)).build();
    }

    // ------
    // labels
    // ------

    /**
     * Creates a new Label.
     *
     * @param httpServletRequest request
     * @param groupId            The group id
     * @param requestLabelEntity        A labelEntity.
     * @return A labelEntity.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/labels")
    @ApiOperation(
            value = "Creates a label",
            response = LabelEntity.class,
            authorizations = {
                    @Authorization(value = "Write - /process-groups/{uuid}", type = "")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response createLabel(
            @Context final HttpServletRequest httpServletRequest,
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") final String groupId,
            @ApiParam(
                    value = "The label configuration details.",
                    required = true
            ) final LabelEntity requestLabelEntity) {

        if (requestLabelEntity == null || requestLabelEntity.getComponent() == null) {
            throw new IllegalArgumentException("Label details must be specified.");
        }

        if (requestLabelEntity.getRevision() == null || (requestLabelEntity.getRevision().getVersion() == null || requestLabelEntity.getRevision().getVersion() != 0)) {
            throw new IllegalArgumentException("A revision of 0 must be specified when creating a new Label.");
        }

        if (requestLabelEntity.getComponent().getId() != null) {
            throw new IllegalArgumentException("Label ID cannot be specified.");
        }

        final PositionDTO proposedPosition = requestLabelEntity.getComponent().getPosition();
        if (proposedPosition != null) {
            if (proposedPosition.getX() == null || proposedPosition.getY() == null) {
                throw new IllegalArgumentException("The x and y coordinate of the proposed position must be specified.");
            }
        }

        if (requestLabelEntity.getComponent().getParentGroupId() != null && !groupId.equals(requestLabelEntity.getComponent().getParentGroupId())) {
            throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                    requestLabelEntity.getComponent().getParentGroupId(), groupId));
        }
        requestLabelEntity.getComponent().setParentGroupId(groupId);

        if (isReplicateRequest()) {
            return replicate(HttpMethod.POST, requestLabelEntity);
        }

        return withWriteLock(
                serviceFacade,
                requestLabelEntity,
                lookup -> {
                    final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
                    processGroup.authorize(authorizer, RequestAction.WRITE, NiFiUserUtils.getNiFiUser());
                },
                null,
                labelEntity -> {
                    // set the processor id as appropriate
                    labelEntity.getComponent().setId(generateUuid());

                    // create the label and generate the json
                    final Revision revision = getRevision(labelEntity, labelEntity.getComponent().getId());
                    final LabelEntity entity = serviceFacade.createLabel(revision, groupId, labelEntity.getComponent());
                    labelResource.populateRemainingLabelEntityContent(entity);

                    // build the response
                    return clusterContext(generateCreatedResponse(URI.create(entity.getUri()), entity)).build();
                }
        );
    }

    /**
     * Retrieves all the of labels in this NiFi.
     *
     * @return A labelsEntity.
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/labels")
    @ApiOperation(
            value = "Gets all labels",
            response = LabelsEntity.class,
            authorizations = {
                    @Authorization(value = "Read - /process-groups/{uuid}", type = "")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response getLabels(
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") final String groupId) {

        if (isReplicateRequest()) {
            return replicate(HttpMethod.GET);
        }

        // authorize access
        serviceFacade.authorizeAccess(lookup -> {
            final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
            processGroup.authorize(authorizer, RequestAction.READ, NiFiUserUtils.getNiFiUser());
        });

        // get all the labels
        final Set<LabelEntity> labels = serviceFacade.getLabels(groupId);

        // create the response entity
        final LabelsEntity entity = new LabelsEntity();
        entity.setLabels(labelResource.populateRemainingLabelEntitiesContent(labels));

        // generate the response
        return clusterContext(generateOkResponse(entity)).build();
    }

    // ---------------------
    // remote process groups
    // ---------------------

    /**
     * Creates a new remote process group.
     *
     * @param httpServletRequest       request
     * @param groupId                  The group id
     * @param requestRemoteProcessGroupEntity A remoteProcessGroupEntity.
     * @return A remoteProcessGroupEntity.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/remote-process-groups")
    @ApiOperation(
            value = "Creates a new process group",
            response = RemoteProcessGroupEntity.class,
            authorizations = {
                    @Authorization(value = "Write - /process-groups/{uuid}", type = "")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response createRemoteProcessGroup(
            @Context final HttpServletRequest httpServletRequest,
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") final String groupId,
            @ApiParam(
                    value = "The remote process group configuration details.",
                    required = true
            ) final RemoteProcessGroupEntity requestRemoteProcessGroupEntity) {

        if (requestRemoteProcessGroupEntity == null || requestRemoteProcessGroupEntity.getComponent() == null) {
            throw new IllegalArgumentException("Remote process group details must be specified.");
        }

        if (requestRemoteProcessGroupEntity.getRevision() == null
                || (requestRemoteProcessGroupEntity.getRevision().getVersion() == null || requestRemoteProcessGroupEntity.getRevision().getVersion() != 0)) {
            throw new IllegalArgumentException("A revision of 0 must be specified when creating a new Remote process group.");
        }

        final RemoteProcessGroupDTO requestRemoteProcessGroupDTO = requestRemoteProcessGroupEntity.getComponent();

        if (requestRemoteProcessGroupDTO.getId() != null) {
            throw new IllegalArgumentException("Remote process group ID cannot be specified.");
        }

        if (requestRemoteProcessGroupDTO.getTargetUri() == null) {
            throw new IllegalArgumentException("The URI of the process group must be specified.");
        }

        final PositionDTO proposedPosition = requestRemoteProcessGroupDTO.getPosition();
        if (proposedPosition != null) {
            if (proposedPosition.getX() == null || proposedPosition.getY() == null) {
                throw new IllegalArgumentException("The x and y coordinate of the proposed position must be specified.");
            }
        }

        if (requestRemoteProcessGroupDTO.getParentGroupId() != null && !groupId.equals(requestRemoteProcessGroupDTO.getParentGroupId())) {
            throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                    requestRemoteProcessGroupDTO.getParentGroupId(), groupId));
        }
        requestRemoteProcessGroupDTO.setParentGroupId(groupId);

        if (isReplicateRequest()) {
            return replicate(HttpMethod.POST, requestRemoteProcessGroupEntity);
        }

        return withWriteLock(
                serviceFacade,
                requestRemoteProcessGroupEntity,
                lookup -> {
                    final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
                    processGroup.authorize(authorizer, RequestAction.WRITE, NiFiUserUtils.getNiFiUser());
                },
                null,
                remoteProcessGroupEntity -> {
                    final RemoteProcessGroupDTO remoteProcessGroupDTO = remoteProcessGroupEntity.getComponent();

                    // set the processor id as appropriate
                    remoteProcessGroupDTO.setId(generateUuid());

                    // parse the uri to check if the uri is valid
                    final String targetUris = remoteProcessGroupDTO.getTargetUris();
                    SiteToSiteRestApiClient.parseClusterUrls(targetUris);

                    // since the uri is valid, use it
                    remoteProcessGroupDTO.setTargetUris(targetUris);

                    // create the remote process group
                    final Revision revision = getRevision(remoteProcessGroupEntity, remoteProcessGroupDTO.getId());
                    final RemoteProcessGroupEntity entity = serviceFacade.createRemoteProcessGroup(revision, groupId, remoteProcessGroupDTO);
                    remoteProcessGroupResource.populateRemainingRemoteProcessGroupEntityContent(entity);

                    return clusterContext(generateCreatedResponse(URI.create(entity.getUri()), entity)).build();
                }
        );
    }

    /**
     * Retrieves all the of remote process groups in this NiFi.
     *
     * @return A remoteProcessGroupEntity.
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/remote-process-groups")
    @ApiOperation(
            value = "Gets all remote process groups",
            response = RemoteProcessGroupsEntity.class,
            authorizations = {
                    @Authorization(value = "Read - /process-groups/{uuid}", type = "")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response getRemoteProcessGroups(
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") final String groupId) {

        if (isReplicateRequest()) {
            return replicate(HttpMethod.GET);
        }

        // authorize access
        serviceFacade.authorizeAccess(lookup -> {
            final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
            processGroup.authorize(authorizer, RequestAction.READ, NiFiUserUtils.getNiFiUser());
        });

        // get all the remote process groups
        final Set<RemoteProcessGroupEntity> remoteProcessGroups = serviceFacade.getRemoteProcessGroups(groupId);

        // prune response as necessary
        for (RemoteProcessGroupEntity remoteProcessGroupEntity : remoteProcessGroups) {
            if (remoteProcessGroupEntity.getComponent() != null) {
                remoteProcessGroupEntity.getComponent().setContents(null);
            }
        }

        // create the response entity
        final RemoteProcessGroupsEntity entity = new RemoteProcessGroupsEntity();
        entity.setRemoteProcessGroups(remoteProcessGroupResource.populateRemainingRemoteProcessGroupEntitiesContent(remoteProcessGroups));

        // generate the response
        return clusterContext(generateOkResponse(entity)).build();
    }

    // -----------
    // connections
    // -----------

    /**
     * Creates a new connection.
     *
     * @param httpServletRequest request
     * @param groupId            The group id
     * @param requestConnectionEntity   A connectionEntity.
     * @return A connectionEntity.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/connections")
    @ApiOperation(
            value = "Creates a connection",
            response = ConnectionEntity.class,
            authorizations = {
                    @Authorization(value = "Write - /process-groups/{uuid}", type = ""),
                    @Authorization(value = "Write Source - /{component-type}/{uuid}", type = ""),
                    @Authorization(value = "Write Destination - /{component-type}/{uuid}", type = "")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response createConnection(
            @Context final HttpServletRequest httpServletRequest,
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") final String groupId,
            @ApiParam(
                    value = "The connection configuration details.",
                    required = true
            ) final ConnectionEntity requestConnectionEntity) {

        if (requestConnectionEntity == null || requestConnectionEntity.getComponent() == null) {
            throw new IllegalArgumentException("Connection details must be specified.");
        }

        if (requestConnectionEntity.getRevision() == null || (requestConnectionEntity.getRevision().getVersion() == null || requestConnectionEntity.getRevision().getVersion() != 0)) {
            throw new IllegalArgumentException("A revision of 0 must be specified when creating a new Connection.");
        }

        if (requestConnectionEntity.getComponent().getId() != null) {
            throw new IllegalArgumentException("Connection ID cannot be specified.");
        }

        final List<PositionDTO> proposedBends = requestConnectionEntity.getComponent().getBends();
        if (proposedBends != null) {
            for (final PositionDTO proposedBend : proposedBends) {
                if (proposedBend.getX() == null || proposedBend.getY() == null) {
                    throw new IllegalArgumentException("The x and y coordinate of the each bend must be specified.");
                }
            }
        }

        if (requestConnectionEntity.getComponent().getParentGroupId() != null && !groupId.equals(requestConnectionEntity.getComponent().getParentGroupId())) {
            throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                    requestConnectionEntity.getComponent().getParentGroupId(), groupId));
        }
        requestConnectionEntity.getComponent().setParentGroupId(groupId);

        // get the connection
        final ConnectionDTO requestConnection = requestConnectionEntity.getComponent();

        if (requestConnection.getSource() == null || requestConnection.getSource().getId() == null) {
            throw new IllegalArgumentException("The source of the connection must be specified.");
        }

        if (requestConnection.getSource().getType() == null) {
            throw new IllegalArgumentException("The type of the source of the connection must be specified.");
        }

        final ConnectableType sourceConnectableType;
        try {
            sourceConnectableType = ConnectableType.valueOf(requestConnection.getSource().getType());
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(String.format("Unrecognized source type %s. Expected values are [%s]",
                    requestConnection.getSource().getType(), StringUtils.join(ConnectableType.values(), ", ")));
        }

        if (requestConnection.getDestination() == null || requestConnection.getDestination().getId() == null) {
            throw new IllegalArgumentException("The destination of the connection must be specified.");
        }

        if (requestConnection.getDestination().getType() == null) {
            throw new IllegalArgumentException("The type of the destination of the connection must be specified.");
        }

        final ConnectableType destinationConnectableType;
        try {
            destinationConnectableType = ConnectableType.valueOf(requestConnection.getDestination().getType());
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(String.format("Unrecognized destination type %s. Expected values are [%s]",
                    requestConnection.getDestination().getType(), StringUtils.join(ConnectableType.values(), ", ")));
        }

        if (isReplicateRequest()) {
            return replicate(HttpMethod.POST, requestConnectionEntity);
        }

        return withWriteLock(
                serviceFacade,
                requestConnectionEntity,
                lookup -> {
                    // ensure write access to the group
                    final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
                    processGroup.authorize(authorizer, RequestAction.WRITE, NiFiUserUtils.getNiFiUser());

                    // explicitly handle RPGs differently as the connectable id can be ambiguous if self referencing
                    final Authorizable source;
                    if (ConnectableType.REMOTE_OUTPUT_PORT.equals(sourceConnectableType)) {
                        source = lookup.getRemoteProcessGroup(requestConnection.getSource().getGroupId());
                    } else {
                        source = lookup.getLocalConnectable(requestConnection.getSource().getId());
                    }

                    // ensure write access to the source
                    if (source == null) {
                        throw new ResourceNotFoundException("Cannot find source component with ID [" + requestConnection.getSource().getId() + "]");
                    }
                    source.authorize(authorizer, RequestAction.WRITE, NiFiUserUtils.getNiFiUser());

                    // explicitly handle RPGs differently as the connectable id can be ambiguous if self referencing
                    final Authorizable destination;
                    if (ConnectableType.REMOTE_INPUT_PORT.equals(destinationConnectableType)) {
                        destination = lookup.getRemoteProcessGroup(requestConnection.getDestination().getGroupId());
                    } else {
                        destination = lookup.getLocalConnectable(requestConnection.getDestination().getId());
                    }

                    // ensure write access to the destination
                    if (destination == null) {
                        throw new ResourceNotFoundException("Cannot find destination component with ID [" + requestConnection.getDestination().getId() + "]");
                    }

                    destination.authorize(authorizer, RequestAction.WRITE, NiFiUserUtils.getNiFiUser());
                },
                () -> serviceFacade.verifyCreateConnection(groupId, requestConnection),
                connectionEntity -> {
                    final ConnectionDTO connection = connectionEntity.getComponent();

                    // set the processor id as appropriate
                    connection.setId(generateUuid());

                    // create the new relationship target
                    final Revision revision = getRevision(connectionEntity, connection.getId());
                    final ConnectionEntity entity = serviceFacade.createConnection(revision, groupId, connection);
                    connectionResource.populateRemainingConnectionEntityContent(entity);

                    // extract the href and build the response
                    String uri = entity.getUri();
                    return clusterContext(generateCreatedResponse(URI.create(uri), entity)).build();
                }
        );
    }

    /**
     * Gets all the connections.
     *
     * @return A connectionsEntity.
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/connections")
    @ApiOperation(
            value = "Gets all connections",
            response = ConnectionsEntity.class,
            authorizations = {
                    @Authorization(value = "Read - /process-groups/{uuid}", type = "")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response getConnections(
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") String groupId) {

        if (isReplicateRequest()) {
            return replicate(HttpMethod.GET);
        }

        // authorize access
        serviceFacade.authorizeAccess(lookup -> {
            final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
            processGroup.authorize(authorizer, RequestAction.READ, NiFiUserUtils.getNiFiUser());
        });

        // all of the relationships for the specified source processor
        Set<ConnectionEntity> connections = serviceFacade.getConnections(groupId);

        // create the client response entity
        ConnectionsEntity entity = new ConnectionsEntity();
        entity.setConnections(connectionResource.populateRemainingConnectionEntitiesContent(connections));

        // generate the response
        return clusterContext(generateOkResponse(entity)).build();
    }

    // ----------------
    // snippet instance
    // ----------------

    /**
     * Copies the specified snippet within this ProcessGroup. The snippet instance that is instantiated cannot be referenced at a later time, therefore there is no
     * corresponding URI. Instead the request URI is returned.
     * <p>
     * Alternatively, we could have performed a PUT request. However, PUT requests are supposed to be idempotent and this endpoint is certainly not.
     *
     * @param httpServletRequest request
     * @param groupId            The group id
     * @param requestCopySnippetEntity  The copy snippet request
     * @return A flowSnippetEntity.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/snippet-instance")
    @ApiOperation(
            value = "Copies a snippet and discards it.",
            response = FlowSnippetEntity.class,
            authorizations = {
                    @Authorization(value = "Write - /process-groups/{uuid}", type = ""),
                    @Authorization(value = "Read - /{component-type}/{uuid} - For each component in the snippet and their descendant components", type = ""),
                    @Authorization(value = "Write - if the snippet contains any restricted Processors - /restricted-components", type = "")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response copySnippet(
            @Context HttpServletRequest httpServletRequest,
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") String groupId,
            @ApiParam(
                    value = "The copy snippet request.",
                    required = true
            ) CopySnippetRequestEntity requestCopySnippetEntity) {

        // ensure the position has been specified
        if (requestCopySnippetEntity == null || requestCopySnippetEntity.getOriginX() == null || requestCopySnippetEntity.getOriginY() == null) {
            throw new IllegalArgumentException("The  origin position (x, y) must be specified");
        }

        if (requestCopySnippetEntity.getSnippetId() == null) {
            throw new IllegalArgumentException("The snippet id must be specified.");
        }

        if (isReplicateRequest()) {
            return replicate(HttpMethod.POST, requestCopySnippetEntity);
        }

        return withWriteLock(
                serviceFacade,
                requestCopySnippetEntity,
                lookup -> {
                    final NiFiUser user = NiFiUserUtils.getNiFiUser();
                    final SnippetAuthorizable snippet = authorizeSnippetUsage(lookup, groupId, requestCopySnippetEntity.getSnippetId(), false);

                    // flag to only perform the restricted check once, atomic reference so we can mark final and use in lambda
                    final AtomicBoolean restrictedCheckPerformed = new AtomicBoolean(false);
                    final Consumer<ComponentAuthorizable> authorizeRestricted = authorizable -> {
                        if (authorizable.isRestricted() && restrictedCheckPerformed.compareAndSet(false, true)) {
                            lookup.getRestrictedComponents().authorize(authorizer, RequestAction.WRITE, user);
                        }
                    };

                    // consider each processor. note - this request will not create new controller services so we do not need to check
                    // for if there are not restricted controller services. it will however, need to authorize the user has access
                    // to any referenced services and this is done within authorizeSnippetUsage above.
                    snippet.getSelectedProcessors().stream().forEach(authorizeRestricted);
                    snippet.getSelectedProcessGroups().stream().forEach(processGroup -> {
                        processGroup.getEncapsulatedProcessors().forEach(authorizeRestricted);
                    });
                },
                null,
                copySnippetRequestEntity -> {
                    // copy the specified snippet
                    final FlowEntity flowEntity = serviceFacade.copySnippet(
                            groupId, copySnippetRequestEntity.getSnippetId(), copySnippetRequestEntity.getOriginX(), copySnippetRequestEntity.getOriginY(), getIdGenerationSeed().orElse(null));

                    // get the snippet
                    final FlowDTO flow = flowEntity.getFlow();

                    // prune response as necessary
                    for (ProcessGroupEntity childGroupEntity : flow.getProcessGroups()) {
                        childGroupEntity.getComponent().setContents(null);
                    }

                    // create the response entity
                    populateRemainingSnippetContent(flow);

                    // generate the response
                    return clusterContext(generateCreatedResponse(getAbsolutePath(), flowEntity)).build();
                }
        );
    }

    // -----------------
    // template instance
    // -----------------

    /**
     * Discovers the compatible bundle details for the components in the specified snippet.
     *
     * @param snippet the snippet
     */
    private void discoverCompatibleBundles(final FlowSnippetDTO snippet) {
        if (snippet.getProcessors() != null) {
            snippet.getProcessors().forEach(processor -> {
                final BundleCoordinate coordinate = BundleUtils.getCompatibleBundle(processor.getType(), processor.getBundle());
                processor.setBundle(new BundleDTO(coordinate.getGroup(), coordinate.getId(), coordinate.getVersion()));
            });
        }

        if (snippet.getControllerServices() != null) {
            snippet.getControllerServices().forEach(controllerService -> {
                final BundleCoordinate coordinate = BundleUtils.getCompatibleBundle(controllerService.getType(), controllerService.getBundle());
                controllerService.setBundle(new BundleDTO(coordinate.getGroup(), coordinate.getId(), coordinate.getVersion()));
            });
        }

        if (snippet.getProcessGroups() != null) {
            snippet.getProcessGroups().forEach(processGroup -> {
                discoverCompatibleBundles(processGroup.getContents());
            });
        }
    }

    /**
     * Instantiates the specified template within this ProcessGroup. The template instance that is instantiated cannot be referenced at a later time, therefore there is no
     * corresponding URI. Instead the request URI is returned.
     * <p>
     * Alternatively, we could have performed a PUT request. However, PUT requests are supposed to be idempotent and this endpoint is certainly not.
     *
     * @param httpServletRequest               request
     * @param groupId                          The group id
     * @param requestInstantiateTemplateRequestEntity The instantiate template request
     * @return A flowEntity.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/template-instance")
    @ApiOperation(
            value = "Instantiates a template",
            response = FlowEntity.class,
            authorizations = {
                    @Authorization(value = "Write - /process-groups/{uuid}", type = ""),
                    @Authorization(value = "Read - /templates/{uuid}", type = ""),
                    @Authorization(value = "Write - if the template contains any restricted components - /restricted-components", type = "")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response instantiateTemplate(
            @Context HttpServletRequest httpServletRequest,
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") String groupId,
            @ApiParam(
                    value = "The instantiate template request.",
                    required = true
            ) InstantiateTemplateRequestEntity requestInstantiateTemplateRequestEntity) {

        // ensure the position has been specified
        if (requestInstantiateTemplateRequestEntity == null || requestInstantiateTemplateRequestEntity.getOriginX() == null || requestInstantiateTemplateRequestEntity.getOriginY() == null) {
            throw new IllegalArgumentException("The origin position (x, y) must be specified.");
        }

        // ensure the template id was provided
        if (requestInstantiateTemplateRequestEntity.getTemplateId() == null) {
            throw new IllegalArgumentException("The template id must be specified.");
        }

        // ensure the template encoding version is valid
        if (requestInstantiateTemplateRequestEntity.getEncodingVersion() != null) {
            try {
                FlowEncodingVersion.parse(requestInstantiateTemplateRequestEntity.getEncodingVersion());
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException("The template encoding version is not valid. The expected format is <number>.<number>");
            }
        }

        // populate the encoding version if necessary
        if (requestInstantiateTemplateRequestEntity.getEncodingVersion() == null) {
            // if the encoding version is not specified, use the latest encoding version as these options were
            // not available pre 1.x, will be overridden if populating from the underlying template below
            requestInstantiateTemplateRequestEntity.setEncodingVersion(TemplateDTO.MAX_ENCODING_VERSION);
        }

        // populate the component bundles if necessary
        if (requestInstantiateTemplateRequestEntity.getSnippet() == null) {
            // get the desired template in order to determine the supported bundles
            final TemplateDTO requestedTemplate = serviceFacade.exportTemplate(requestInstantiateTemplateRequestEntity.getTemplateId());
            final FlowSnippetDTO requestTemplateContents = requestedTemplate.getSnippet();

            // determine the compatible bundles to use for each component in this template, this ensures the nodes in the cluster
            // instantiate the components from the same bundles
            discoverCompatibleBundles(requestTemplateContents);

            // update the requested template as necessary - use the encoding version from the underlying template
            requestInstantiateTemplateRequestEntity.setEncodingVersion(requestedTemplate.getEncodingVersion());
            requestInstantiateTemplateRequestEntity.setSnippet(requestTemplateContents);
        }

        if (isReplicateRequest()) {
            return replicate(HttpMethod.POST, requestInstantiateTemplateRequestEntity);
        }

        return withWriteLock(
                serviceFacade,
                requestInstantiateTemplateRequestEntity,
                lookup -> {
                    final NiFiUser user = NiFiUserUtils.getNiFiUser();

                    // ensure write on the group
                    final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
                    processGroup.authorize(authorizer, RequestAction.WRITE, user);

                    final Authorizable template = lookup.getTemplate(requestInstantiateTemplateRequestEntity.getTemplateId());
                    template.authorize(authorizer, RequestAction.READ, user);

                    // ensure read on the template
                    final TemplateContentsAuthorizable templateContents = lookup.getTemplateContents(requestInstantiateTemplateRequestEntity.getSnippet());

                    // flag to only perform the restricted check once, atomic reference so we can mark final and use in lambda
                    final AtomicBoolean restrictedCheckPerformed = new AtomicBoolean(false);
                    final Consumer<ComponentAuthorizable> authorizeRestricted = authorizable -> {
                        if (authorizable.isRestricted() && restrictedCheckPerformed.compareAndSet(false, true)) {
                            lookup.getRestrictedComponents().authorize(authorizer, RequestAction.WRITE, user);
                        }
                    };

                    // ensure restricted access if necessary
                    templateContents.getEncapsulatedProcessors().forEach(authorizeRestricted);
                    templateContents.getEncapsulatedControllerServices().forEach(authorizeRestricted);
                },
                () -> serviceFacade.verifyComponentTypes(requestInstantiateTemplateRequestEntity.getSnippet()),
                instantiateTemplateRequestEntity -> {
                    // create the template and generate the json
                    final FlowEntity entity = serviceFacade.createTemplateInstance(groupId, instantiateTemplateRequestEntity.getOriginX(), instantiateTemplateRequestEntity.getOriginY(),
                            instantiateTemplateRequestEntity.getEncodingVersion(), instantiateTemplateRequestEntity.getSnippet(), getIdGenerationSeed().orElse(null));

                    final FlowDTO flowSnippet = entity.getFlow();

                    // prune response as necessary
                    for (ProcessGroupEntity childGroupEntity : flowSnippet.getProcessGroups()) {
                        childGroupEntity.getComponent().setContents(null);
                    }

                    // create the response entity
                    populateRemainingSnippetContent(flowSnippet);

                    // generate the response
                    return clusterContext(generateCreatedResponse(getAbsolutePath(), entity)).build();
                }
        );
    }

    // ---------
    // templates
    // ---------

    private SnippetAuthorizable authorizeSnippetUsage(final AuthorizableLookup lookup, final String groupId, final String snippetId, final boolean authorizeTransitiveServices) {
        final NiFiUser user = NiFiUserUtils.getNiFiUser();

        // ensure write access to the target process group
        lookup.getProcessGroup(groupId).getAuthorizable().authorize(authorizer, RequestAction.WRITE, user);

        // ensure read permission to every component in the snippet including referenced services
        final SnippetAuthorizable snippet = lookup.getSnippet(snippetId);
        authorizeSnippet(snippet, authorizer, lookup, RequestAction.READ, true, authorizeTransitiveServices);
        return snippet;
    }

    /**
     * Creates a new template based off of the specified template.
     *
     * @param httpServletRequest          request
     * @param requestCreateTemplateRequestEntity request to create the template
     * @return A templateEntity
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/templates")
    @ApiOperation(
            value = "Creates a template and discards the specified snippet.",
            response = TemplateEntity.class,
            authorizations = {
                    @Authorization(value = "Write - /process-groups/{uuid}", type = ""),
                    @Authorization(value = "Read - /{component-type}/{uuid} - For each component in the snippet and their descendant components", type = "")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response createTemplate(
            @Context final HttpServletRequest httpServletRequest,
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") final String groupId,
            @ApiParam(
                    value = "The create template request.",
                    required = true
            ) final CreateTemplateRequestEntity requestCreateTemplateRequestEntity) {

        if (requestCreateTemplateRequestEntity.getSnippetId() == null) {
            throw new IllegalArgumentException("The snippet identifier must be specified.");
        }

        if (isReplicateRequest()) {
            return replicate(HttpMethod.POST, requestCreateTemplateRequestEntity);
        }

        return withWriteLock(
                serviceFacade,
                requestCreateTemplateRequestEntity,
                lookup -> {
                    authorizeSnippetUsage(lookup, groupId, requestCreateTemplateRequestEntity.getSnippetId(), true);
                },
                () -> serviceFacade.verifyCanAddTemplate(groupId, requestCreateTemplateRequestEntity.getName()),
                createTemplateRequestEntity -> {
                    // create the template and generate the json
                    final TemplateDTO template = serviceFacade.createTemplate(createTemplateRequestEntity.getName(), createTemplateRequestEntity.getDescription(),
                            createTemplateRequestEntity.getSnippetId(), groupId, getIdGenerationSeed());
                    templateResource.populateRemainingTemplateContent(template);

                    // build the response entity
                    final TemplateEntity entity = new TemplateEntity();
                    entity.setTemplate(template);

                    // build the response
                    return clusterContext(generateCreatedResponse(URI.create(template.getUri()), entity)).build();
                }
        );
    }

    /**
     * Imports the specified template.
     *
     * @param httpServletRequest request
     * @param in                 The template stream
     * @return A templateEntity or an errorResponse XML snippet.
     * @throws InterruptedException if interrupted
     */
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_XML)
    @Path("{id}/templates/upload")
    @ApiOperation(
            value = "Uploads a template",
            response = TemplateEntity.class,
            authorizations = {
                    @Authorization(value = "Write - /process-groups/{uuid}", type = "")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response uploadTemplate(
            @Context final HttpServletRequest httpServletRequest,
            @Context final UriInfo uriInfo,
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") final String groupId,
            @FormDataParam("template") final InputStream in) throws InterruptedException {

        // unmarshal the template
        final TemplateDTO template;
        try {
            JAXBContext context = JAXBContext.newInstance(TemplateDTO.class);
            Unmarshaller unmarshaller = context.createUnmarshaller();
            JAXBElement<TemplateDTO> templateElement = unmarshaller.unmarshal(new StreamSource(in), TemplateDTO.class);
            template = templateElement.getValue();
        } catch (JAXBException jaxbe) {
            logger.warn("An error occurred while parsing a template.", jaxbe);
            String responseXml = String.format("<errorResponse status=\"%s\" statusText=\"The specified template is not in a valid format.\"/>", Response.Status.BAD_REQUEST.getStatusCode());
            return Response.status(Response.Status.OK).entity(responseXml).type("application/xml").build();
        } catch (IllegalArgumentException iae) {
            logger.warn("Unable to import template.", iae);
            String responseXml = String.format("<errorResponse status=\"%s\" statusText=\"%s\"/>", Response.Status.BAD_REQUEST.getStatusCode(), iae.getMessage());
            return Response.status(Response.Status.OK).entity(responseXml).type("application/xml").build();
        } catch (Exception e) {
            logger.warn("An error occurred while importing a template.", e);
            String responseXml = String.format("<errorResponse status=\"%s\" statusText=\"Unable to import the specified template: %s\"/>",
                    Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), e.getMessage());
            return Response.status(Response.Status.OK).entity(responseXml).type("application/xml").build();
        }

        // build the response entity
        TemplateEntity entity = new TemplateEntity();
        entity.setTemplate(template);

        if (isReplicateRequest()) {
            // convert request accordingly
            final UriBuilder uriBuilder = uriInfo.getBaseUriBuilder();
            uriBuilder.segment("process-groups", groupId, "templates", "import");
            final URI importUri = uriBuilder.build();

            // change content type to XML for serializing entity
            final Map<String, String> headersToOverride = new HashMap<>();
            headersToOverride.put("content-type", MediaType.APPLICATION_XML);

            // Determine whether we should replicate only to the cluster coordinator, or if we should replicate directly
            // to the cluster nodes themselves.
            if (getReplicationTarget() == ReplicationTarget.CLUSTER_NODES) {
                return getRequestReplicator().replicate(HttpMethod.POST, importUri, entity, getHeaders(headersToOverride)).awaitMergedResponse().getResponse();
            } else {
                return getRequestReplicator().forwardToCoordinator(
                        getClusterCoordinatorNode(), HttpMethod.POST, importUri, entity, getHeaders(headersToOverride)).awaitMergedResponse().getResponse();
            }
        }

        // otherwise import the template locally
        return importTemplate(httpServletRequest, groupId, entity);
    }

    /**
     * Imports the specified template.
     *
     * @param httpServletRequest request
     * @param requestTemplateEntity     A templateEntity.
     * @return A templateEntity.
     */
    @POST
    @Consumes(MediaType.APPLICATION_XML)
    @Produces(MediaType.APPLICATION_XML)
    @Path("{id}/templates/import")
    @ApiOperation(
            value = "Imports a template",
            response = TemplateEntity.class,
            authorizations = {
                    @Authorization(value = "Write - /process-groups/{uuid}", type = "")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response importTemplate(
            @Context final HttpServletRequest httpServletRequest,
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") final String groupId,
            final TemplateEntity requestTemplateEntity) {

        // verify the template was specified
        if (requestTemplateEntity == null || requestTemplateEntity.getTemplate() == null || requestTemplateEntity.getTemplate().getSnippet() == null) {
            throw new IllegalArgumentException("Template details must be specified.");
        }

        if (isReplicateRequest()) {
            return replicate(HttpMethod.POST, requestTemplateEntity);
        }

        return withWriteLock(
                serviceFacade,
                requestTemplateEntity,
                lookup -> {
                    final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
                    processGroup.authorize(authorizer, RequestAction.WRITE, NiFiUserUtils.getNiFiUser());
                },
                () -> serviceFacade.verifyCanAddTemplate(groupId, requestTemplateEntity.getTemplate().getName()),
                templateEntity -> {
                    try {
                        // import the template
                        final TemplateDTO template = serviceFacade.importTemplate(templateEntity.getTemplate(), groupId, getIdGenerationSeed());
                        templateResource.populateRemainingTemplateContent(template);

                        // build the response entity
                        TemplateEntity entity = new TemplateEntity();
                        entity.setTemplate(template);

                        // build the response
                        return clusterContext(generateCreatedResponse(URI.create(template.getUri()), entity)).build();
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        logger.info("Unable to import template: " + e);
                        String responseXml = String.format("<errorResponse status=\"%s\" statusText=\"%s\"/>", Response.Status.BAD_REQUEST.getStatusCode(), e.getMessage());
                        return Response.status(Response.Status.OK).entity(responseXml).type("application/xml").build();
                    } catch (Exception e) {
                        logger.warn("An error occurred while importing a template.", e);
                        String responseXml = String.format("<errorResponse status=\"%s\" statusText=\"Unable to import the specified template: %s\"/>",
                                Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), e.getMessage());
                        return Response.status(Response.Status.OK).entity(responseXml).type("application/xml").build();
                    }
                }
        );
    }

    // -------------------
    // controller services
    // -------------------

    /**
     * Creates a new Controller Service.
     *
     * @param httpServletRequest      request
     * @param requestControllerServiceEntity A controllerServiceEntity.
     * @return A controllerServiceEntity.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/controller-services")
    @ApiOperation(
            value = "Creates a new controller service",
            response = ControllerServiceEntity.class,
            authorizations = {
                    @Authorization(value = "Write - /process-groups/{uuid}", type = ""),
                    @Authorization(value = "Read - any referenced Controller Services - /controller-services/{uuid}", type = ""),
                    @Authorization(value = "Write - if the Controller Service is restricted - /restricted-components", type = "")
            }
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                    @ApiResponse(code = 401, message = "Client could not be authenticated."),
                    @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                    @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response createControllerService(
            @Context final HttpServletRequest httpServletRequest,
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") final String groupId,
            @ApiParam(
                    value = "The controller service configuration details.",
                    required = true
            ) final ControllerServiceEntity requestControllerServiceEntity) {

        if (requestControllerServiceEntity == null || requestControllerServiceEntity.getComponent() == null) {
            throw new IllegalArgumentException("Controller service details must be specified.");
        }

        if (requestControllerServiceEntity.getRevision() == null
                || (requestControllerServiceEntity.getRevision().getVersion() == null || requestControllerServiceEntity.getRevision().getVersion() != 0)) {
            throw new IllegalArgumentException("A revision of 0 must be specified when creating a new Controller service.");
        }

        final ControllerServiceDTO requestControllerService = requestControllerServiceEntity.getComponent();
        if (requestControllerService.getId() != null) {
            throw new IllegalArgumentException("Controller service ID cannot be specified.");
        }

        if (StringUtils.isBlank(requestControllerService.getType())) {
            throw new IllegalArgumentException("The type of controller service to create must be specified.");
        }

        if (requestControllerService.getParentGroupId() != null && !groupId.equals(requestControllerService.getParentGroupId())) {
            throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                    requestControllerService.getParentGroupId(), groupId));
        }
        requestControllerService.setParentGroupId(groupId);

        if (isReplicateRequest()) {
            return replicate(HttpMethod.POST, requestControllerServiceEntity);
        }

        return withWriteLock(
                serviceFacade,
                requestControllerServiceEntity,
                lookup -> {
                    final NiFiUser user = NiFiUserUtils.getNiFiUser();

                    final Authorizable processGroup = lookup.getProcessGroup(groupId).getAuthorizable();
                    processGroup.authorize(authorizer, RequestAction.WRITE, user);

                    ComponentAuthorizable authorizable = null;
                    try {
                        authorizable = lookup.getConfigurableComponent(requestControllerService.getType(), requestControllerService.getBundle());

                        if (authorizable.isRestricted()) {
                            lookup.getRestrictedComponents().authorize(authorizer, RequestAction.WRITE, user);
                        }

                        if (requestControllerService.getProperties() != null) {
                            AuthorizeControllerServiceReference.authorizeControllerServiceReferences(requestControllerService.getProperties(), authorizable, authorizer, lookup);
                        }
                    } finally {
                        if (authorizable != null) {
                            authorizable.cleanUpResources();
                        }
                    }
                },
                () -> serviceFacade.verifyCreateControllerService(requestControllerService),
                controllerServiceEntity -> {
                    final ControllerServiceDTO controllerService = controllerServiceEntity.getComponent();

                    // set the processor id as appropriate
                    controllerService.setId(generateUuid());

                    // create the controller service and generate the json
                    final Revision revision = getRevision(controllerServiceEntity, controllerService.getId());
                    final ControllerServiceEntity entity = serviceFacade.createControllerService(revision, groupId, controllerService);
                    controllerServiceResource.populateRemainingControllerServiceEntityContent(entity);

                    // build the response
                    return clusterContext(generateCreatedResponse(URI.create(entity.getUri()), entity)).build();
                }
        );
    }

    // setters

    public void setServiceFacade(NiFiServiceFacade serviceFacade) {
        this.serviceFacade = serviceFacade;
    }

    public void setProcessorResource(ProcessorResource processorResource) {
        this.processorResource = processorResource;
    }

    public void setInputPortResource(InputPortResource inputPortResource) {
        this.inputPortResource = inputPortResource;
    }

    public void setOutputPortResource(OutputPortResource outputPortResource) {
        this.outputPortResource = outputPortResource;
    }

    public void setFunnelResource(FunnelResource funnelResource) {
        this.funnelResource = funnelResource;
    }

    public void setLabelResource(LabelResource labelResource) {
        this.labelResource = labelResource;
    }

    public void setRemoteProcessGroupResource(RemoteProcessGroupResource remoteProcessGroupResource) {
        this.remoteProcessGroupResource = remoteProcessGroupResource;
    }

    public void setConnectionResource(ConnectionResource connectionResource) {
        this.connectionResource = connectionResource;
    }

    public void setTemplateResource(TemplateResource templateResource) {
        this.templateResource = templateResource;
    }

    public void setControllerServiceResource(ControllerServiceResource controllerServiceResource) {
        this.controllerServiceResource = controllerServiceResource;
    }

    public void setAuthorizer(Authorizer authorizer) {
        this.authorizer = authorizer;
    }
}
