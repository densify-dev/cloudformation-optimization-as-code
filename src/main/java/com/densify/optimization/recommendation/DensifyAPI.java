package com.densify.optimization.recommendation;

import com.amazonaws.cloudformation.proxy.AmazonWebServicesClientProxy;
import com.amazonaws.cloudformation.proxy.Logger;
import com.google.common.collect.ImmutableMap;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.time.LocalDateTime;

public class DensifyAPI {

    private final String densifyUrl;
    private final String densifyUsername;
    private final String densifyPassword;
    private String apiToken;
    private LocalDateTime timestamp;

    public DensifyAPI(JSONObject connectionInfo) {
        String url = connectionInfo.getString("DensifyUrl");

        //Fix densify URL in case if it does not start with https://
        if (!url.startsWith("https://")){
            url = "https://" + url;
        }

        this.densifyUrl = url;
        this.densifyUsername = connectionInfo.getString("DensifyUsername");
        this.densifyPassword = connectionInfo.getString("DensifyPassword");

        if (connectionInfo.has("apiToken")) {
            this.apiToken = connectionInfo.getString("apiToken");
            this.timestamp = LocalDateTime.parse(connectionInfo.getString("timestamp"));
            System.out.print("Found token: " + this.apiToken);
        } else {
            this.apiToken = null;
            System.out.print("No token info saved");
        }
    }

    public DensifyAPI(String densifyUrl, String densifyUsername, String densifyPassword) {
        this.densifyUrl = densifyUrl;
        this.densifyUsername = densifyUsername;
        this.densifyPassword = densifyPassword;
        this.apiToken = null;
    }

    private void setApiToken(String apiToken) {
        this.apiToken = apiToken;
        this.timestamp = LocalDateTime.now();
    }

    public String getDensifyUrl() {
        return this.densifyUrl;
    }

    private ImmutableMap<String, String> getCommonHeaders() {
        return ImmutableMap.of("Accept", "application/json",
                "Authorization", "Bearer " + apiToken);
    }

    private void updateDensifyInParameterStore(AmazonWebServicesClientProxy proxy) {
        JSONObject densifyJson = new JSONObject()
                .put("DensifyUrl", densifyUrl)
                .put("DensifyUsername", densifyUsername)
                .put("DensifyPassword", densifyPassword)
                .put("apiToken", apiToken)
                .put("timestamp", timestamp.toString());

        Helper.writeToParameterStore("DensifyConnection", densifyJson.toString(), true, proxy);
    }

    public String toString() {
        JSONObject json = new JSONObject()
                .put("DensifyUrl", densifyUrl)
                .put("DensifyUsername", densifyUsername)
                .put("DensifyPassword", densifyPassword);
        if (apiToken != null) {
            json.put("apiToken", apiToken);
            json.put("timestamp", timestamp.toString());
        }
        return json.toString();
    }


    public int ping(String densifyUrl, Logger logger) {
        //Fix densify URL in case if it does not start with https://
        if (!densifyUrl.startsWith("https://")){
            densifyUrl = "https://" + densifyUrl;
        }

        String requestString = densifyUrl + "/CIRBA/api/ping";

        int responseCode = -1;
        try {
            URL requestURL = new URL(requestString);
            HttpURLConnection connection = (HttpURLConnection) requestURL.openConnection();
            connection.setRequestMethod("GET");

            responseCode = connection.getResponseCode();
        } catch (UnknownHostException uhe) {
            logger.log("Unknown host " + requestString);
            return 404;
        } catch (IOException e) {
            logger.log(e.getMessage());
            return responseCode;
        }

        return responseCode;
    }

    // To view docs on Authorize method, please see www.densify.com/docs/Content/API_Guide/Authorize.htm
    public JSONObject authorize(Logger logger) {
        String authorizeRequest = densifyUrl + ":443/CIRBA/api/v2/authorize";
        JSONObject responseJson;
        try {
            URL authUrl = new URL(authorizeRequest);
            HttpURLConnection authConnection = (HttpURLConnection) authUrl.openConnection();

            authConnection.setRequestMethod("POST");
            authConnection.setRequestProperty("Accept", "application/json");
            authConnection.setRequestProperty("Content-type", "application/json");
            authConnection.setDoOutput(true);

            String reqBody = new JSONObject()
                    .put("userName", densifyUsername)
                    .put("pwd", densifyPassword)
                    .toString();

            try (OutputStream os = authConnection.getOutputStream()) {
                os.write(reqBody.getBytes("utf-8"));
            }

            int responseCode = authConnection.getResponseCode();

            if (responseCode == 200) {
                BufferedReader in = new BufferedReader(new InputStreamReader(authConnection.getInputStream()));

                String inputLine;
                StringBuffer response = new StringBuffer();

                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();

                responseJson = new JSONObject(response.toString());
            } else {
                responseJson = new JSONObject()
                        .put("status", responseCode)
                        .put("message", "Something went wrong, could not authorize");
            }
        } catch (Exception e) {
            logger.log(e.getMessage());
            responseJson = new JSONObject()
                    .put("status", -1)
                    .put("message", e.getMessage());
        }
        return responseJson;
    }

    public JSONArray retrieveAnalysisResult(String awsAccount, Logger logger) {
        String requestPrefix = densifyUrl + "/CIRBA/api/v2";

        // First need to retrieve information about all analyses
        String analysisRequest = requestPrefix + "/analysis/cloud/aws?analysisName=" + awsAccount;

        ImmutableMap<String, String> commonHeaders = ImmutableMap.of("Accept", "application/json",
                "Authorization", "Bearer " + apiToken);

        JSONArray accounts = Helper.getRequestHelper(analysisRequest, commonHeaders);

        if (accounts == null || accounts.length() < 1 || accounts.getJSONObject(0).has("ErrorMessage")) {
            logger.log("Something went wrong when trying to retrieve analysis info for account: " + awsAccount);
            return null;
        }

        // Now get the recommendations
        String recommendationsRequest = requestPrefix + accounts.getJSONObject(0).get("analysisResults").toString();

        return Helper.getRequestHelper(recommendationsRequest, commonHeaders);
    }

    public Boolean checkAndUpdate(Logger logger) {
        int pingResp = ping(densifyUrl, logger);
        if (pingResp != 200) {
            logger.log("Failed to ping Densify, ABORT!");
            return null;
        }

        if (apiToken == null || LocalDateTime.now().isAfter(timestamp.plusMinutes(5))) {
            JSONObject authorization = authorize(logger);
            if (!authorization.get("status").equals(200)) {
                logger.log("Failed to perform authorization:" + authorization.get("message"));
                return null;
            }
            setApiToken(authorization.get("apiToken").toString());
            logger.log("Set Densify api token to: " + apiToken);
            return true;
        }
        return false;
    }


    private void populateRecommendationsTags(JSONArray recommendations, Logger logger) {
        String requestPrefix = densifyUrl + ":443/CIRBA/api/v2/systems/";
        // for every system, retrieve attributes
        for (int i = 0; i < recommendations.length(); i++) {
            JSONObject recommendation = recommendations.getJSONObject(i);
            String requestUrl = requestPrefix + recommendation.get("entityId").toString();
            JSONArray response = Helper.getRequestHelper(requestUrl, getCommonHeaders());

            if (response == null || response.length() < 1 || response.getJSONObject(0).has("ErrorMessage")) {
                if (response.getJSONObject(0).has("ErrorMessage")) {
                    logger.log(response.getJSONObject(0).get("ErrorMessage").toString());
                }
                continue;
            }

            // retrieve attributes and look for ones that have id "attr_resource_tags" and save them as array in recommendation json
            JSONArray tags = new JSONArray();
            JSONArray attributes = response.getJSONObject(0).getJSONArray("attributes");
            for (int j = 0; j < attributes.length(); j++) {
                if (attributes.getJSONObject(j).get("id").toString().equals("attr_resource_tags")) {
                    tags.put(attributes.getJSONObject(j).get("value").toString());
                }
            }
            recommendation.put("tags", tags);
        }
    }

    public JSONArray getRecommendations(String awsAccount, Logger logger) {
        JSONArray recommendations = retrieveAnalysisResult(awsAccount, logger);

        if (recommendations == null || recommendations.length() < 1) {
            logger.log("Could not retrieve analysis information for " + awsAccount);
            return null;
        } else if (recommendations.getJSONObject(0).has("ErrorMessage")) {
            logger.log(recommendations.getJSONObject(0).get("ErrorMessage").toString());
            return null;
        }
        populateRecommendationsTags(recommendations, logger);

        return recommendations;
    }


}
