package com.onfido.onfidoRegistrationNode;

import org.apache.commons.io.FileUtils;
import org.forgerock.openam.auth.node.api.NodeProcessException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONString;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Objects;
import okhttp3.OkHttpClient;
import okhttp3.Request;

class onfidoHelper {


    //TODO Not used, what config are we trying to load here?
    //Method to load in advanced configuration from file the configuration I've exposed via the widget is simple.
    //need to fix later date loads config file from file structure somehow
    private String getConfigFile(String path) {
        File file = null;
        try {
            file = new File(this.getClass().getResource(path).toURI());
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        try {
            return FileUtils.readFileToString(Objects.requireNonNull(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "error";
    }


    JSONObject buildSdkConfig(onfidoRegistrationNode.Config config) {
        JSONObject sdkConfiguration = new JSONObject();

        JSONObject stepsConfig = new JSONObject();
        JSONObject options = new JSONObject();

        ArrayList<Object> steps = new ArrayList<>();
        ArrayList<String> descriptions = new ArrayList<>();

        try {
            sdkConfiguration.put("useModal", true);
            sdkConfiguration.put("isModalOpen", true);

            sdkConfiguration.put("onModalRequestClose", new JSONFunction(
                    "function(){onfido.setOptions({isModalOpen: false}); window.location.reload(false); }"));
            sdkConfiguration.put("onComplete", new JSONFunction(
                    "function(data) {onfido.setOptions({isModalOpen: false}); document.getElementById" +
                            "('loginButton_0').click(); }"));

            stepsConfig.put("type", "welcome");
            options.put("title", config.onfidoWelcomeMessage());
            descriptions.add(config.onfidoWelcomeMessage());
            descriptions.add(config.onfidoHelpMessage());
            options.put("descriptions", descriptions);
            options.put("forceCrossDevice", false);

            stepsConfig.put("options", options);
            steps.add(stepsConfig);
            steps.add("document");

            if (!config.biometricCheck().toString().equals("None")) {


                if(config.biometricCheck().toString().equals("Live")) {
                    JSONObject faceOptions = new JSONObject();
                    faceOptions.put("type", "face");
                    faceOptions.put("options", new JSONObject().put("requestedVariant", "video"));
                    steps.add(faceOptions);
                }else{
                    steps.add("face");
                }


            }

            sdkConfiguration.put("steps", steps);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return sdkConfiguration;
    }

    //function to get report data and return it
    JSONObject getReportData(String checkId, String reportId, String token) throws NodeProcessException {
        Request request = new Request.Builder()
                .url("https://api.onfido.com/v2/checks/" + checkId + "/reports/" + reportId)
                .get()
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Token token=" + token)
                .build();

        try {
            return new JSONObject(Objects.requireNonNull(new OkHttpClient().newCall(request).execute().body())
                                         .string());
        } catch (IOException | JSONException e) {
            throw new NodeProcessException(e);
        }
    }

    boolean parseReport(JSONArray reports, String[] breakdowns) throws NodeProcessException {
        boolean docResult = false;
        boolean faceResult = true;
        for (int n = 0; n < reports.length(); n++) {

            try {
                JSONObject report = (JSONObject) reports.get(n);
                if (report.getString("name").equals("document")) {
                    if (report.getString("result").equals("clear")) {
                        docResult = true;
                    } else {
                        for (String category : breakdowns) {
                            report.getJSONObject("breakdown").getJSONObject(category).getString("result");
                        }
                        docResult = true;
                    }
                }
                else if (report.getString("name").equals("facial_similarity")) {
                    faceResult = report.getString("result").equals("clear");
                }

            } catch (JSONException e) {
                throw new NodeProcessException(e);
            }
        }
        return (docResult && faceResult);
    }

    String getApplicantId(String HREF) {
//    https://api.onfido.com/v2/applicants/4f5bd603-2475-4d95-86e9-5ce943dd7862/checks/39236dbe-5f98-4fb4-8ff0
//    -cb5a179b42a3

        return HREF.replaceAll("https://api.onfido.com/v2/applicants/", "").replaceAll("/checks.*", "");

    }

    JSONArray getReportsId(String checksHREF, String token) throws NodeProcessException {
        OkHttpClient client = new OkHttpClient();

        Request request = new Request.Builder()
                .url(checksHREF)
                .get()
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Token token=" + token)
                .build();

        String resStr;

        try {
            resStr = Objects.requireNonNull(client.newCall(request).execute().body()).string();
        } catch (IOException e) {
            throw new NodeProcessException(e);
        }
        try {
            return new JSONObject(resStr).getJSONArray("reports");
        } catch (JSONException e) {
            throw new NodeProcessException(e);
        }

    }

    //class that helps with passing JSONFunctions as value in JSONObject
    public static class JSONFunction implements JSONString {

        private String string;

        JSONFunction(String string) {
            this.string = string;
        }

        @Override
        public String toJSONString() {
            return string;
        }

    }

}


