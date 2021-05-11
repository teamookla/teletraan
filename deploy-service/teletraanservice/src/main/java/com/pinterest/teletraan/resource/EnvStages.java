/**
 * Copyright 2016 Pinterest, Inc.
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
package com.pinterest.teletraan.resource;

import com.pinterest.deployservice.bean.EnvironBean;
import com.pinterest.deployservice.bean.Resource;
import com.pinterest.deployservice.bean.Role;
import com.pinterest.deployservice.bean.EnvType;
import com.pinterest.deployservice.bean.TagBean;
import com.pinterest.deployservice.bean.TagTargetType;
import com.pinterest.deployservice.bean.TagValue;
import com.pinterest.deployservice.common.Constants;
import com.pinterest.deployservice.dao.EnvironDAO;
import com.pinterest.deployservice.handler.ConfigHistoryHandler;
import com.pinterest.deployservice.handler.EnvTagHandler;
import com.pinterest.deployservice.handler.EnvironHandler;
import com.pinterest.deployservice.handler.TagHandler;
import com.pinterest.teletraan.TeletraanServiceContext;
import com.pinterest.teletraan.exception.TeletaanInternalException;
import com.pinterest.teletraan.security.Authorizer;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;

import org.hibernate.validator.constraints.NotEmpty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.validation.Valid;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import java.util.UUID;

@Path("/v1/envs/{envName : [a-zA-Z0-9\\-_]+}/{stageName : [a-zA-Z0-9\\-_]+}")
@Api(tags = "Environments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EnvStages {

    public enum ActionType {
        ENABLE,
        DISABLE
    }

    private static final Logger LOG = LoggerFactory.getLogger(EnvStages.class);
    private EnvironDAO environDAO;
    private EnvironHandler environHandler;
    private ConfigHistoryHandler configHistoryHandler;
    private TagHandler tagHandler;
    private Authorizer authorizer;

    public EnvStages(TeletraanServiceContext context) throws Exception {
        environDAO = context.getEnvironDAO();
        environHandler = new EnvironHandler(context);
        configHistoryHandler = new ConfigHistoryHandler(context);
        tagHandler = new EnvTagHandler(context);
        authorizer = context.getAuthorizer();
    }

    @GET
    @ApiOperation(
        value = "Get an environment",
        notes = "Returns an environment object given environment and stage names",
        response = EnvironBean.class)
    public EnvironBean get(
        @ApiParam(value = "Environment name", required = true) @PathParam("envName") String envName,
        @ApiParam(value = "Stage name", required = true) @PathParam("stageName") String stageName)
        throws Exception {
        return Utils.getEnvStage(environDAO, envName, stageName);
    }

    @PUT
    @ApiOperation(
        value = "Update an environment",
        notes = "Update an environment given environment and stage names with a environment object")
    public void update(@Context SecurityContext sc,
                       @ApiParam(value = "Environment name", required = true) @PathParam("envName") String envName,
                       @ApiParam(value = "Stage name", required = true) @PathParam("stageName") String stageName,
                       @ApiParam(value = "Desired Environment object with updates", required = true)
                           EnvironBean environBean) throws Exception {
        EnvironBean origBean = Utils.getEnvStage(environDAO, envName, stageName);
        authorizer
            .authorize(sc, new Resource(origBean.getEnv_name(), Resource.Type.ENV), Role.OPERATOR);
        String operator = sc.getUserPrincipal().getName();
        try {
            environBean.validate();
        } catch (Exception e) {
            throw new TeletaanInternalException(Response.Status.BAD_REQUEST, e.toString());
        }
        if (origBean.getStage_type() != EnvType.DEFAULT && origBean.getStage_type() != environBean
            .getStage_type()) {
            throw new TeletaanInternalException(Response.Status.BAD_REQUEST,
                "Modification of stage type is not allowed!");
        }
        environBean.setEnv_name(origBean.getEnv_name());
        environBean.setStage_name(origBean.getStage_name());
        environHandler.updateStage(environBean, operator);
        configHistoryHandler
            .updateConfigHistory(origBean.getEnv_id(), Constants.TYPE_ENV_GENERAL, environBean,
                operator);
        configHistoryHandler.updateChangeFeed(Constants.CONFIG_TYPE_ENV, origBean.getEnv_id(),
            Constants.TYPE_ENV_GENERAL, operator);
        LOG.info("Successfully updated env {}/{} with {} by {}.",
            envName, stageName, environBean, operator);
    }

    @DELETE
    @ApiOperation(
        value = "Delete an environment",
        notes = "Deletes an environment given a environment and stage names")
    public void delete(@Context SecurityContext sc,
                       @ApiParam(value = "Environment name", required = true) @PathParam("envName") String envName,
                       @ApiParam(value = "Stage name", required = true) @PathParam("stageName") String stageName)
        throws Exception {
        EnvironBean origBean = Utils.getEnvStage(environDAO, envName, stageName);
        authorizer
            .authorize(sc, new Resource(origBean.getEnv_name(), Resource.Type.ENV), Role.OPERATOR);
        String operator = sc.getUserPrincipal().getName();
        environHandler.deleteEnvStage(envName, stageName, operator);
        LOG.info("Successfully deleted env {}/{} by {}.", envName, stageName, operator);
    }

    @POST
    @ApiOperation(
        value = "Sets the external_id on a stage",
        notes = "Sets the external_id column on a stage given the environment and stage names",
        response = EnvironBean.class
    )
    @Path("/external_id")
    public EnvironBean setExternalId(
        @ApiParam(value = "Environment name", required = true) @PathParam("envName") String envName,
        @ApiParam(value = "Stage name", required = true) @PathParam("stageName") String stageName,
        @ApiParam(value = "External id", required = true) String externalId)
        throws Exception {

        try {
            UUID uuid = UUID.fromString(externalId);
        } catch (Exception ex) {
            LOG.info("Invalid UUID supplied - {}.", externalId);
            throw new TeletaanInternalException(Response.Status.BAD_REQUEST, String.format(
                "Client supplied an invalid externalId - %s. Please retry with an externalId in the UUID format",
                externalId));
        }

        EnvironBean originalBean = environDAO.getByStage(envName, stageName);
        if (originalBean == null) {
            throw new TeletaanInternalException(Response.Status.NOT_FOUND,
                String.format("Environment %s/%s does not exist.", envName, stageName));
        }
        environDAO.setExternalId(originalBean, externalId);
        EnvironBean updatedBean = environDAO.getByStage(envName, stageName);
        String newExternalId = updatedBean.getExternal_id();

        LOG.info("Successfully updated Env/stage - {}/{} with externalid = {}", envName, stageName,
            newExternalId);
        return updatedBean;
    }

    // TODO: strongly prefer the route of PATCH endpoint on /:env/:stage that supports UPDATE partial EnvironBean object; not this route.
    // TODO: POST should not support update, it is not idempotent. POST is for creating new objects, see MDN summary of HTTP methods
    // TODO: This may already be possible with above POST endpoint, not sure why the external_id endpoint exists if env POST supports update
    //
    // TODO: This endpoint does patch the object, but it's too constrained. In the future we should pass a payload and patch the object
    // TODO: Alternatively, we could create a constrained PATCH endpoint on /:env/:stage that validates that it only can receive stage_is_sox.
    @PATCH
    @ApiOperation(
        value = "Sets stage_is_sox on a stage",
        notes = "Sets the stage_is_sox column on a stage given the environment and stage names",
        response = EnvironBean.class
    )
    @Path("/stage_is_sox")
    public EnvironBean setStageIsSOX(
        @ApiParam(value = "Environment name", required = true) @PathParam("envName") String envName,
        @ApiParam(value = "Stage name", required = true) @PathParam("stageName") String stageName,
        // TODO: Can this take a Boolean? Can remove validation if yes.
        @ApiParam(value = "stage_is_sox", required = true) String stage_is_sox)
        throws Exception {

        // TODO: what format is this coming in?
        //  prefer to get true/false from json true/false values rather than json strings if possible.
        // validate that we are only getting "true" or "false"
        if (!stage_is_sox.equalsIgnoreCase("true") || !stage_is_sox.equalsIgnoreCase("false")) {
            LOG.info("Invalid Boolean supplied for env/stage stage_is_sox - {}.", stage_is_sox);
            throw new TeletaanInternalException(Response.Status.BAD_REQUEST, String.format(
                "Client supplied an invalid Boolean value - %s. Please retry with 'true' or 'false'",
                stage_is_sox));
        }

        EnvironBean originalBean = environDAO.getByStage(envName, stageName);
        if (originalBean == null) {
            throw new TeletaanInternalException(Response.Status.NOT_FOUND,
                String.format("Environment %s/%s does not exist.", envName, stageName));
        }
        // TODO: Why isn't setStageIsSOX being picked up from EnvironDAO.java?
        environDAO.setStageIsSOX(originalBean, Boolean.valueOf(stage_is_sox));
        // TODO: what is the purpose of the following two lines? we don't validate, we just return to the log and endpoint
        // TODO: e.g. couldn't we just return the originalBean or does the local object not update with environDAO???
        EnvironBean updatedBean = environDAO.getByStage(envName, stageName);
        String new_stage_is_sox = updatedBean.getStageIsSOX();

        LOG.info("Successfully updated Env/stage - {}/{} with stage_is_sox = {}", envName,
            stageName, new_stage_is_sox);
        return updatedBean;
    }

    @POST
    @Path("/actions")
    public void action(@Context SecurityContext sc,
                       @PathParam("envName") String envName,
                       @PathParam("stageName") String stageName,
                       @NotEmpty @QueryParam("actionType") ActionType actionType,
                       @NotEmpty @QueryParam("description") String description) throws Exception {
        EnvironBean envBean = Utils.getEnvStage(environDAO, envName, stageName);
        authorizer
            .authorize(sc, new Resource(envBean.getEnv_name(), Resource.Type.ENV), Role.OPERATOR);
        String operator = sc.getUserPrincipal().getName();

        TagBean tagBean = new TagBean();
        switch (actionType) {
            case ENABLE:
                environHandler.enable(envBean, operator);
                tagBean.setValue(TagValue.ENABLE_ENV);
                break;
            case DISABLE:
                environHandler.disable(envBean, operator);
                tagBean.setValue(TagValue.DISABLE_ENV);
                break;
            default:
                throw new TeletaanInternalException(Response.Status.BAD_REQUEST,
                    "No action found.");
        }

        tagBean.setTarget_id(envBean.getEnv_id());
        tagBean.setTarget_type(TagTargetType.ENVIRONMENT);
        tagBean.setComments(description);
        tagHandler.createTag(tagBean, operator);
        LOG.info(String
            .format("Successfully updated action %s for %s/%s by %s", actionType, envName,
                stageName, operator));
    }
}
