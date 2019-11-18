package com.densify.optimization.recommendation;

import software.amazon.cloudformation.proxy.*;
import org.json.JSONObject;

public class CreateHandler extends BaseHandler<CallbackContext> {

    @Override
    public ProgressEvent<ResourceModel, CallbackContext> handleRequest(
            final AmazonWebServicesClientProxy proxy,
            final ResourceHandlerRequest<ResourceModel> request,
            final CallbackContext callbackContext,
            final Logger logger) {

        final ResourceModel model = request.getDesiredResourceState();
        logger.log("Creating Densify Recommendation resource for instance: " + model.getProvisioningID());

        OperationStatus currentStatus = OperationStatus.SUCCESS;
        String msg = "";

        // THIS SHOULD NOT HAPPEN
        // If primary identifier is somehow null, then just return what came in.
        if (model.getPrimaryIdentifier() == null) {
            return ProgressEvent.<ResourceModel, CallbackContext>builder()
                    .resourceModel(model)
                    .status(OperationStatus.SUCCESS)
                    .build();
        }

        if (model.getDensifyConnectionParameterName() != null) {
            Helper.setDensifyParameterName(model.getDensifyConnectionParameterName());
        }

        JSONObject densifyConnection = Helper.getAndCheckDensifyInfo(proxy, logger);
        if (densifyConnection.has("failed")) {
            currentStatus = OperationStatus.FAILED;
            msg = densifyConnection.getString("msg");
        }
        // Parameter with same name should not exist
        if (null != Helper.retrieveFromParameterStore(Helper.getParameterNamePrefix() + model.getProvisioningID(), proxy, logger)) {
            currentStatus = OperationStatus.FAILED;
            msg = "Parameter " + Helper.getParameterNamePrefix() + model.getProvisioningID() + " already exists!";
        }

        // If was unable to retrieve Densify, or parameter already exists, then makes no sense to continue
        if (OperationStatus.FAILED.equals(currentStatus)) {
            return ProgressEvent.<ResourceModel, CallbackContext>builder().message(msg).status(currentStatus).build();
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
                .message(msg)
                .status(currentStatus)
                .build();
    }
}
