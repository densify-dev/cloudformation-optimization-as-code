package com.densify.optimization.recommendation;

import software.amazon.cloudformation.proxy.*;

public class DeleteHandler extends BaseHandler<CallbackContext> {

    @Override
    public ProgressEvent<ResourceModel, CallbackContext> handleRequest(
            final AmazonWebServicesClientProxy proxy,
            final ResourceHandlerRequest<ResourceModel> request,
            final CallbackContext callbackContext,
            final Logger logger) {

        final ResourceModel model = request.getDesiredResourceState();
        logger.log("Deleting records for " + model.getProvisioningID());

        OperationStatus status = OperationStatus.SUCCESS;
        String msg = null;
        if (model.getPrimaryIdentifier() != null) {
            String deleteStatus = Helper.deleteFromParameterStore(Helper.getParameterNamePrefix() + model.getProvisioningID(), proxy);
            if (deleteStatus.equals("ParameterNotFound")) {
                status = OperationStatus.FAILED;
                msg = "Already deleted parameter for " + model.getProvisioningID();
            }
            logger.log("Successfully deleted records for " + model.getProvisioningID());
        }

        return ProgressEvent.<ResourceModel, CallbackContext>builder()
                .resourceModel(model)
                .message(msg)
                .status(status)
                .build();
    }
}
