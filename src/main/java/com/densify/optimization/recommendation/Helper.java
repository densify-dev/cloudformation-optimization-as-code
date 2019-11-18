package com.densify.optimization.recommendation;

import com.amazonaws.cloudformation.proxy.AmazonWebServicesClientProxy;
import com.amazonaws.cloudformation.proxy.Logger;
import com.amazonaws.cloudformation.proxy.OperationStatus;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.GetCallerIdentityRequest;
import com.amazonaws.services.securitytoken.model.GetCallerIdentityResult;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagement;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagementClientBuilder;
import com.amazonaws.services.simplesystemsmanagement.model.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

public class Helper {

    private static String densifyParameterName = "DensifyConnection";
    private static String recommendationParamNamePrefix = "Densify_Recommendation_";
    private static String timestampParamName = "DensifyRefreshTime";

    public static String getParameterNamePrefix() {
        return recommendationParamNamePrefix;
    }

    public static void setDensifyParameterName(String name) {
        densifyParameterName = name;
    }

    public static String retrieveAWSAccount(AmazonWebServicesClientProxy proxy) {
        AWSSecurityTokenService client = AWSSecurityTokenServiceClientBuilder.standard().build();
        GetCallerIdentityRequest request = new GetCallerIdentityRequest();

        GetCallerIdentityResult result = proxy.injectCredentialsAndInvoke(request, client::getCallerIdentity);

        return result.getAccount();
    }

    public static String retrieveFromParameterStore(String paramName, AmazonWebServicesClientProxy proxy, Logger logger) {
        AWSSimpleSystemsManagement client = AWSSimpleSystemsManagementClientBuilder.standard().build();
        GetParameterRequest request = new GetParameterRequest().withName(paramName).withWithDecryption(true);

        GetParameterResult result;
        // In case if parameter can not be found or other errors
        try {
            result = proxy.injectCredentialsAndInvoke(request, client::getParameter);
        } catch (Exception e) {
            logger.log(e.getMessage());
            System.out.print(e.getMessage());
            return null;
        }
        return result.getParameter().getValue();
    }

    public static String writeToParameterStore(String id, String value, boolean withEncryption, AmazonWebServicesClientProxy proxy) {
        AWSSimpleSystemsManagement client = AWSSimpleSystemsManagementClientBuilder.standard().build();

        ParameterType parameterType = withEncryption ? ParameterType.SecureString : ParameterType.String;
        PutParameterRequest request = new PutParameterRequest()
                .withName(id)
                .withValue(value)
                .withType(parameterType)
                .withOverwrite(true);
        PutParameterResult result;
        try {
            result = proxy.injectCredentialsAndInvoke(request, client::putParameter);
        } catch (ParameterMaxVersionLimitExceededException pmv){
            deleteFromParameterStore(id, proxy);
            result = proxy.injectCredentialsAndInvoke(request, client::putParameter);
        } catch (TooManyUpdatesException tmu) {
            return null;
        }
        return result.toString();
    }

    public static String deleteFromParameterStore(String id, AmazonWebServicesClientProxy proxy) {
        AWSSimpleSystemsManagement client = AWSSimpleSystemsManagementClientBuilder.standard().build();

        DeleteParameterRequest request = new DeleteParameterRequest()
                .withName(id);
        DeleteParameterResult result;
        try {
            result = proxy.injectCredentialsAndInvoke(request, client::deleteParameter);
        } catch (ParameterNotFoundException pnfe) {
            return "ParameterNotFound";
        }
        return result.toString();
    }

    public static JSONArray getRequestHelper(String requestPrefix, Map<String, String> headers) {
        JSONArray ret;
        try {
            URL url = new URL(requestPrefix);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                return null;
            }

            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String inputLine;
            StringBuffer response = new StringBuffer();
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();

            if (response.toString().startsWith("[")) {
                ret = new JSONArray(response.toString());
            } else {
                ret = new JSONArray().put(new JSONObject(response.toString()));
            }
        } catch (Exception e) {
            ret = new JSONArray().put(new JSONObject().put("ErrorMessage", e.getMessage()));
        }
        return ret;
    }

    public static boolean recommendationIsFresh(String timeStr) {
        return LocalDateTime.now().isBefore(LocalDateTime.parse(timeStr).plusMinutes(2));
    }

    public static JSONObject matchRequestToRecommendationByUniqueIdentifier(String primaryIdentifier, JSONArray recommendations) {
        for (int i = 0; i < recommendations.length(); i++) {
            JSONObject recommendation = recommendations.getJSONObject(i);
            if (recommendation.has("name") && recommendation.getString("name").equals(primaryIdentifier)) {
                return recommendation;
            }
        }
        return null;
    }

    public static String doRecommendationLogic(JSONObject recommendation) {
        String recommendedType = recommendation.getString("currentType");

        if (recommendation.getString("implementationMethod").equals("Self Optimization")) {
            if (recommendation.getString("approvalType").equals("all")
                    || recommendation.getString("approvalType").equals(recommendation.getString("recommendedType"))) {
                recommendedType = recommendation.getString("recommendedType");
            }
        }
        return recommendedType;
    }

    public static JSONObject buildFallbackParameter(String fallbackType) {
        return new JSONObject()
                .put("currentType", fallbackType)
                .put("implementationMethod", "N/A")
                .put("recommendedType", "N/A")
                .put("predictedUptime", -1)
                .put("savingsEstimate", -1)
                .put("timestamp", LocalDateTime.now().toString());
    }

    public static void addTimestampToJson(JSONObject json) {
        json.put("timestamp", LocalDateTime.now().toString());
    }

    public static JSONObject getAndCheckDensifyInfo(AmazonWebServicesClientProxy proxy, Logger logger) {
        // Retrieve Densify information that is supposed to be saved in ParameterStore prior to running create or update
        String densifyConnectionInfoString = retrieveFromParameterStore(densifyParameterName, proxy, logger);
        if (densifyConnectionInfoString == null) {
            return new JSONObject()
                    .put("failed", OperationStatus.FAILED)
                    .put("msg", "Could not find parameter with Densify information " + densifyParameterName);
        }
        JSONObject densifyConnection;
        try {
            densifyConnection = new JSONObject(densifyConnectionInfoString);
        } catch (Exception jse) {
            logger.log("Looks like stored Densify connection information has incorrect format.\n " + jse.getMessage());
            return new JSONObject()
                    .put("status", OperationStatus.FAILED)
                    .put("msg", "Could not parse Densify connection info from ParameterStore");

        }

        return densifyConnection;
    }

    public static JSONObject connectAndGetRecommendation(JSONObject densifyConnection, ResourceModel model,
                                                         AmazonWebServicesClientProxy proxy, Logger logger) {
        // Establish connection to Densify and retrieve the recommendations
        DensifyAPI densify = new DensifyAPI(densifyConnection);

        String msg;

        // densifyState is true if update in ParameterStore is required, false if not and null if there was an error
        Boolean densifyNeedsUpdate = densify.checkAndUpdate(logger);
        if (densifyNeedsUpdate == null) {
            logger.log("Failed to establish connection to " + densify.getDensifyUrl());
            JSONObject ret = new JSONObject()
                    .put("failed", "failed")
                    .put("msg", "Could not establish connection with Densify server at " + densify.getDensifyUrl());
            if (model.getInstanceType() != null) {
                logger.log("Found fallback InstanceType = " + model.getInstanceType());
                ret.put("recommendation", buildFallbackParameter(model.getInstanceType()));
            } else {
                logger.log("No fallback instance type found");
            }
            return ret;
        }


        if (densifyNeedsUpdate) {
            logger.log("Update Densify information in the ParameterStore");
            writeToParameterStore(densifyParameterName, densify.toString(), true, proxy);
        }

        // TODO: this piece is only here for testing and demoing using demodb. Remove later if needed
        String awsAccount;
        if (model.getTestAWSAccount() == null) {
            awsAccount = retrieveAWSAccount(proxy);
        } else {
            awsAccount = model.getTestAWSAccount();
        }

        JSONArray recommendations = densify.getRecommendations(awsAccount, logger);

        if (recommendations == null) {
            JSONObject ret = new JSONObject();
            if (model.getInstanceType() != null) {
                ret.put("recommendation", buildFallbackParameter(model.getInstanceType()))
                        .put("msg", "Was not able to retrieve recommendations, use fallback InstanceType");
            } else {
                ret.put("failed", "failed")
                        .put("msg", "Could not retrieve recommendations and no Default type was specified in template");
            }
            return ret;
        }

        JSONObject recommendation = matchRequestToRecommendationByUniqueIdentifier(model.getProvisioningID(), recommendations);
        if (recommendation != null) {
            logger.log("Matched " + model.getProvisioningID() + " with " + recommendation.toString());
            addTimestampToJson(recommendation);
            msg = "Successfully found matching instance";
        } else if (model.getInstanceType() != null) {
            System.out.print(model);
            logger.log("Could not match " + model.getProvisioningID() + ". Use default type");
            recommendation = buildFallbackParameter(model.getInstanceType());
            msg = "could not find instance that has tag with value: " + model.getProvisioningID() + ". Use default type specified";
        } else {
            logger.log("Could not retrieve recommendation and default type is not specified in the resource template");
            recommendation = new JSONObject().put("failed", "failed");
            msg = "Can not connect to Densify and no Default was specified";
        }

        //For testing the read requests that require update
        if (model.getTestAWSAccount() != null) {
            recommendation.put("awsAccount", model.getTestAWSAccount());
        }

        return new JSONObject()
                .put("recommendation", recommendation)
                .put("msg", msg);
    }

    public static JSONObject refreshRecommendation(ResourceModel model, AmazonWebServicesClientProxy proxy, Logger logger) {
        JSONObject densifyConnection = getAndCheckDensifyInfo(proxy, logger);
        if (densifyConnection.has("failed")) {
            return densifyConnection;
        }

        JSONObject status = Helper.connectAndGetRecommendation(densifyConnection, model, proxy, logger);
        if (status.has("failed")) {
            return status;
        } else {
            Helper.writeToParameterStore(recommendationParamNamePrefix + model.getProvisioningID(),
                    status.getJSONObject("recommendation").toString(), false, proxy);
        }

        return status.getJSONObject("recommendation");
    }

    public static void setModelFields(ResourceModel model, JSONObject recommendation) {
        if (recommendation.has("currentType")) {
            model.setInstanceType(recommendation.getString("currentType"));
            model.setCurrentType(recommendation.getString("currentType"));
        }
        if (recommendation.has("recommendedType")) {
            model.setRecommendedType(recommendation.getString("recommendedType"));
        }
        if (recommendation.has("predictedUptime")) {
            model.setPredictedUptime(recommendation.get("predictedUptime").toString());
        }
        if (recommendation.has("savingsEstimate")) {
            model.setSavingsEstimate(recommendation.get("savingsEstimate").toString());
        }
    }

    public static void updateDensifyRefreshParameter(AmazonWebServicesClientProxy proxy, Logger logger) {
        String paramValue = retrieveFromParameterStore(timestampParamName, proxy, logger);
        if (paramValue == null) {
            logger.log(timestampParamName + " parameter does not exist");
            return;
        }
        LocalDateTime ts;
        try {
            ts = LocalDateTime.parse(paramValue);
        } catch (DateTimeParseException dtpe) {
            ts = null;
            logger.log(timestampParamName + "parameter has incorrect time format");
        }

        if (ts == null || LocalDateTime.now().isAfter(ts.plusMinutes(2))){
            logger.log("Update " + timestampParamName + " timestamp parameter");
            writeToParameterStore(timestampParamName, LocalDateTime.now().toString(), false, proxy);
        }
    }
}
