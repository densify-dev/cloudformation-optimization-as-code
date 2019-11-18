package com.densify.optimization.recommendation;

import software.amazon.cloudformation.proxy.*;
import org.json.JSONObject;

public class UpdateHandler extends BaseHandler<CallbackContext> {

    @Override
    public ProgressEvent<ResourceModel, CallbackContext> handleRequest(
            final AmazonWebServicesClientProxy proxy,
            final ResourceHandlerRequest<ResourceModel> request,
            final CallbackContext callbackContext,
            final Logger logger) {

        final ResourceModel model = request.getDesiredResourceState();
        final ResourceModel prevModel = request.getPreviousResourceState();

        OperationStatus currentStatus = OperationStatus.SUCCESS;
        String msg = "";

        // If primary identifier is somehow null, then just return what came in
        if (model.getPrimaryIdentifier() == null) {
            return ProgressEvent.<ResourceModel, CallbackContext>builder()
                    .resourceModel(model)
                    .status(OperationStatus.SUCCESS)
                    .build();
        }

        if (model.getDensifyConnectionParameterName() != null) {
            Helper.setDensifyParameterName(model.getDensifyConnectionParameterName());
        }

        logger.log("Updating resource with logical ID: " + request.getLogicalResourceIdentifier());
        //First of all, need to delete currently existing parameter for this resource (if there is one)
        String prevRecommendationStr = null;

        if (prevModel != null && prevModel.getProvisioningID() != null) {
            prevRecommendationStr = Helper.retrieveFromParameterStore(Helper.getParameterNamePrefix() + prevModel.getProvisioningID(), proxy, logger);
            logger.log("Deleting old resource " + prevModel.getProvisioningID());
            String deleteStatus = Helper.deleteFromParameterStore(Helper.getParameterNamePrefix() + prevModel.getProvisioningID(), proxy);
            logger.log("Successfully deleted records for " + prevModel.getProvisioningID());
        }

        // Try to parse previous recommendation
        JSONObject prevRecommendation = null;
        try {
            if (prevRecommendationStr != null) {
                prevRecommendation = new JSONObject(prevRecommendationStr);
            }
        } catch (Exception e) {
            // Do nothing
        }
        // Now create new state of the resource
        JSONObject densifyConnection = Helper.getAndCheckDensifyInfo(proxy, logger);
        if (densifyConnection.has("failed")) {
            currentStatus = OperationStatus.FAILED;
            msg = densifyConnection.getString("msg");
        }
        // If was unable to retrieve Densify, or parameter already exists, then makes no sense to continue
        if (OperationStatus.FAILED.equals(currentStatus)) {
            return ProgressEvent.<ResourceModel, CallbackContext>builder().message(msg).status(currentStatus).build();
        }

        // If default instance type is not set, try to use one from previous recommendation
        if (model.getInstanceType() == null && prevRecommendation != null && prevRecommendation.has("currentType")){
            logger.log("No fallback size specified, use previous recommendation data");
            model.setInstanceType(prevRecommendation.getString("currentType"));
        }

        JSONObject status = Helper.connectAndGetRecommendation(densifyConnection, model, proxy, logger);
        if (status.has("failed") && !status.has("recommendation")) {
            currentStatus = OperationStatus.FAILED;
            Helper.writeToParameterStore(Helper.getParameterNamePrefix() + model.getProvisioningID(),
                    "failed to create", false, proxy);
            logger.log("Failed to generate InstanceType for " + model.getProvisioningID());

        } else {
            JSONObject recommendation = status.getJSONObject("recommendation");
            Helper.setModelFields(model, recommendation);
            Helper.writeToParameterStore(Helper.getParameterNamePrefix() + model.getProvisioningID(),
                    recommendation.toString(), false, proxy);

            if (model.getForceUpdate() != null) {
                Helper.updateDensifyRefreshParameter(proxy, logger);
            }
        }
        msg = status.getString("msg");

        return ProgressEvent.<ResourceModel, CallbackContext>builder()
                .resourceModel(model)
                .status(currentStatus)
                .message(msg)
                .build();
    }
}
