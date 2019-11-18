package com.densify.optimization.recommendation;

import software.amazon.cloudformation.proxy.*;
import org.json.JSONObject;

public class ReadHandler extends BaseHandler<CallbackContext> {

    @Override
    public ProgressEvent<ResourceModel, CallbackContext> handleRequest(
            final AmazonWebServicesClientProxy proxy,
            final ResourceHandlerRequest<ResourceModel> request,
            final CallbackContext callbackContext,
            final Logger logger) {

        final ResourceModel model = request.getDesiredResourceState();
        logger.log("Reading values for " + model.getProvisioningID());
        OperationStatus currentStatus = OperationStatus.SUCCESS;
        String msg = "";

        // Handle the dummy case
        if (model.getPrimaryIdentifier() == null)
            return ProgressEvent.<ResourceModel, CallbackContext>builder().resourceModel(model).status(OperationStatus.SUCCESS).build();

        if (model.getDensifyConnectionParameterName() != null) {
            Helper.setDensifyParameterName(model.getDensifyConnectionParameterName());
        }

        String recommendationString = Helper.retrieveFromParameterStore(
                Helper.getParameterNamePrefix() + model.getProvisioningID(), proxy, logger);

        if (recommendationString == null) {
            currentStatus = OperationStatus.FAILED;
            msg = "Could not find recommendationString for " + model.getProvisioningID();
        }

        // No reason to continue if there is no recommendation
        if (OperationStatus.FAILED.equals(currentStatus)) {
            return ProgressEvent.<ResourceModel, CallbackContext>builder().message(msg).status(currentStatus).build();
        }

        JSONObject recommendation = null;
        try {
            recommendation = new JSONObject(recommendationString);
            logger.log("Stored recommendation metadata: " + recommendation);
        } catch (Exception jse) {
            logger.log("Looks like stored recommendation has incorrect format.\n " + jse.getMessage());
            currentStatus = OperationStatus.FAILED;
            msg = "Could not parse recommendation from ParameterStore";
        }

        if (recommendation.has("awsAccount")) {
            model.setTestAWSAccount(recommendation.getString("awsAccount"));
        }

        if (!Helper.recommendationIsFresh(recommendation.getString("timestamp"))) {
            logger.log("Stored recommendation is old, trying to refresh it");
            System.out.print("Refresh recommendation");
            model.setInstanceType(recommendation.getString("currentType"));
            JSONObject refreshedRec = Helper.refreshRecommendation(model, proxy, logger);
            if (!refreshedRec.has("failed")) {
                recommendation = refreshedRec;
            }
            msg = "Successfully read the value";
        }

        Helper.setModelFields(model, recommendation);
        model.setInstanceType(Helper.doRecommendationLogic(recommendation));
        logger.log("Result model: " + model.toString());

        return ProgressEvent.<ResourceModel, CallbackContext>builder()
                .resourceModel(model)
                .message(msg)
                .status(currentStatus)
                .build();
    }
}
