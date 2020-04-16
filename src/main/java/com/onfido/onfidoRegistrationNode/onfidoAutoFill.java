package com.onfido.onfidoRegistrationNode;

import static org.forgerock.json.JsonValue.array;
import static org.forgerock.json.JsonValue.json;
import static org.forgerock.json.JsonValue.object;

import org.apache.commons.lang.StringUtils;
import org.forgerock.json.JsonValue;
import org.forgerock.openam.auth.node.api.NodeProcessException;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.onfido.ApiClient;
import com.onfido.ApiException;
import com.onfido.Configuration;
import com.onfido.api.DefaultApi;
import com.onfido.auth.ApiKeyAuth;
import com.onfido.models.Address;
import com.onfido.models.Applicant;
import com.onfido.models.Document;
import com.onfido.models.DocumentsList;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

class onfidoAutoFill {
    private final Logger logger = LoggerFactory.getLogger("amAuth");

    private String backId = null;
    private String frontId = null;

    JsonValue getIDAttributes(String applicantID, onfidoRegistrationNode.Config config) throws NodeProcessException {
        //this goes at top as all 2/3 rest calls use same header
//        JSONObject documentAttributes = new JSONObject();

        // Configure API key authorization: Token
        ApiKeyAuth tokenAuth = (ApiKeyAuth) Configuration.getDefaultApiClient().getAuthentication("Token");
        tokenAuth.setApiKey("token=" + new String(config.onfidoToken()));
        tokenAuth.setApiKeyPrefix("Token");



        DocumentsList documents;
        try {
            documents = new DefaultApi().listDocuments(applicantID);
        } catch (ApiException e) {
            throw new NodeProcessException(e);
        }
        for (Document currentDocument : documents.getDocuments()) {
            if (currentDocument.getSide().equals("back")) {
                backId = currentDocument.getId();
            } else {
                frontId = currentDocument.getId();
            }
        }

        //rest calls using OK rest back first.
        OkHttpClient client = new OkHttpClient();
        JsonValue jsonBackResponse = getJsonValue(client, new String(config.onfidoToken()), backId);
        if (jsonBackResponse != null) {
            return jsonBackResponse;
        }
        JsonValue jsonFrontResponse = getJsonValue(client, new String(config.onfidoToken()), frontId);
        if (jsonFrontResponse != null) {
            return jsonFrontResponse;
        }
        logger.error("Unable to get json attributes from Onfido Service");
        return null;
    }

    private JsonValue getJsonValue(OkHttpClient client, String onfidoToken, String id) throws NodeProcessException {
        Response response = getAutoFillResults(client, onfidoToken, id);
        if (response.body() != null && response.code() < 300) {
            JsonValue jsonResponse = json(object());
            try {
                JSONObject jsonObject = new JSONObject(
                        Objects.requireNonNull(response.body()).string()).getJSONObject("extracted_data");
                Iterator keys = jsonObject.keys();
                while (keys.hasNext()) {
                    String key = (String) keys.next();
                    jsonResponse.put(key, jsonObject.getString(key));
                }
                return jsonResponse;
            } catch (JSONException | IOException e) {
                throw new NodeProcessException(e);
            }
        }
        logger.debug("getJson value has failed for id {}", id);
        return null;
    }

    private Response getAutoFillResults(OkHttpClient client, String onfidoToken, String documentId)
            throws NodeProcessException {
        MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create( "{\"document_id\":\"" + documentId + "\"}", mediaType);
        Request request = new Request.Builder()
                .url("https://api.onfido.com/v2/ocr")
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Token token=" + onfidoToken)
                .build();

        try {
            return client.newCall(request).execute();
        } catch (IOException e) {
            throw new NodeProcessException(e);
        }
    }

    void populateOnfidoApplicant(String applicantID, JsonValue documentAttributes, onfidoRegistrationNode.Config config)
            throws NodeProcessException {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        ApiKeyAuth tokenAuth = (ApiKeyAuth) defaultClient.getAuthentication("Token");
        tokenAuth.setApiKey("token=" + new String(config.onfidoToken()));
        tokenAuth.setApiKeyPrefix("Token");
        DefaultApi api = new DefaultApi();
        Applicant applicant = new Applicant();
        Address address = new Address();
        String tempValue;
        if (!StringUtils.isEmpty((tempValue = documentAttributes.get("first_name").asString()))) {
            applicant.setFirstName(tempValue);
        }
        if (!StringUtils.isEmpty(tempValue = documentAttributes.get("date_of_birth").asString())) {
            applicant.setDob(LocalDate.parse(tempValue));
        }
        if (!StringUtils.isEmpty((tempValue = documentAttributes.get("gender").asString()))) {
            applicant.setGender(tempValue);
        }
        if (!StringUtils.isEmpty((tempValue = documentAttributes.get("last_name").asString()))) {
            applicant.setLastName(tempValue);
        }
        if (!StringUtils.isEmpty((tempValue = documentAttributes.get("middle_name").asString()))) {
            applicant.setMiddleName(tempValue);
        }
        boolean isAddressSet = false;
        if (!StringUtils.isEmpty((tempValue = documentAttributes.get("address_line_1").asString()))) {
            address.setBuildingNumber(tempValue.replaceAll("[^a-zA-Z]+", "").trim());
            address.setStreet(tempValue.replaceAll("^[0-9]+", "").trim());
            isAddressSet = true;
        }
        if (!StringUtils.isEmpty((tempValue = documentAttributes.get("address_line_3").asString()))) {
            address.setTown(tempValue);
            isAddressSet = true;
        }
        if (!StringUtils.isEmpty((tempValue = documentAttributes.get("address_line_4").asString()))) {
            address.setPostcode(tempValue);
            isAddressSet = true;
        }
        if (!StringUtils.isEmpty((tempValue = documentAttributes.get("issuing_state").asString()))) {
            address.setState(tempValue);
        }
        if (!StringUtils.isEmpty((tempValue = documentAttributes.get("issuing_country").asString()))) {
            address.setCountry(tempValue);
        }
        if (isAddressSet) {
            applicant.setAddresses(new ArrayList<Address>() {{
                add(address);
            }});
        }
        try {
            Applicant result = api.updateApplicant(applicantID, applicant);
            logger.debug("Result is: {}", result);
        } catch (ApiException e) {
            logger.error("Exception when calling DefaultApi#updateApplicant");
            throw new NodeProcessException(e);
        }
    }

    JsonValue prepareForProvisioningNode(String applicantID, String username, JsonValue documentAttributes,
                                         onfidoRegistrationNode.Config config) {

        JsonValue attributes = json(object());
        if (documentAttributes != null) {
            for (Map.Entry<String, String> entry : config.attributeMappingConfiguration().entrySet()) {

                if (documentAttributes.isDefined(entry.getValue())) {
                    attributes.put(entry.getKey(), new ArrayList<String>() {{
                        add(documentAttributes.get(entry.getValue()).asString());
                    }});
                }
            }
        }
        List<Object> usernameArray = array();
        usernameArray.add(username);
        List<Object> applicantIdArray = array();
        applicantIdArray.add(applicantID);

        attributes.put(onfidoConstants.UID, usernameArray);
        attributes.put(config.onfidoApplicantIdAttribute(), applicantIdArray);
        return attributes;

    }
}