/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2017-2018 ForgeRock AS.
 */


package com.onfido.onfidoRegistrationNode;

import static com.onfido.onfidoRegistrationNode.onfidoConstants.ATTRIBUTES_SHARED_STATE_KEY;
import static com.onfido.onfidoRegistrationNode.onfidoConstants.ONFIDO_CHECK_TYPE;
import static com.onfido.onfidoRegistrationNode.onfidoConstants.ONFIDO_DOCUMENT_REPORT;
import static com.onfido.onfidoRegistrationNode.onfidoConstants.ONFIDO_FACIAL_REPORT;
import static com.onfido.onfidoRegistrationNode.onfidoConstants.SETUP_DOM_SCRIPT;
import static com.onfido.onfidoRegistrationNode.onfidoConstants.USER_INFO_SHARED_STATE_KEY;
import static com.onfido.onfidoRegistrationNode.onfidoConstants.USER_NAMES_SHARED_STATE_KEY;
import static org.forgerock.json.JsonValue.field;
import static org.forgerock.json.JsonValue.json;
import static org.forgerock.json.JsonValue.object;
import static org.forgerock.openam.auth.node.api.Action.send;
import static org.forgerock.openam.auth.node.api.SharedStateConstants.USERNAME;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.TextOutputCallback;

import org.forgerock.json.JsonValue;
import org.forgerock.openam.annotations.sm.Attribute;
import org.forgerock.openam.auth.node.api.Action;
import org.forgerock.openam.auth.node.api.Node;
import org.forgerock.openam.auth.node.api.NodeProcessException;
import org.forgerock.openam.auth.node.api.SharedStateConstants;
import org.forgerock.openam.auth.node.api.SingleOutcomeNode;
import org.forgerock.openam.auth.node.api.TreeContext;
import org.forgerock.openam.core.CoreWrapper;
import org.forgerock.openam.sm.annotations.adapters.Password;
import org.forgerock.openam.utils.StringUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.assistedinject.Assisted;
import com.iplanet.sso.SSOException;
import com.iplanet.sso.SSOTokenManager;
import com.onfido.ApiClient;
import com.onfido.ApiException;
import com.onfido.Configuration;
import com.onfido.api.DefaultApi;
import com.onfido.auth.ApiKeyAuth;
import com.onfido.models.Applicant;
import com.onfido.models.Check;
import com.onfido.models.Report;
import com.onfido.models.SdkTokenRequest;
import com.onfido.models.SdkTokenResponse;
import com.sun.identity.authentication.callbacks.ScriptTextOutputCallback;
import com.sun.identity.idm.AMIdentity;
import com.sun.identity.idm.IdRepoException;

/**
 * TODO Add Javadoc
 */
@Node.Metadata(outcomeProvider = SingleOutcomeNode.OutcomeProvider.class,
        configClass = onfidoRegistrationNode.Config.class)
public class onfidoRegistrationNode extends SingleOutcomeNode {

    private final Logger logger = LoggerFactory.getLogger("amAuth");
    private final Config config;
    private final CoreWrapper coreWrapper;

    /**
     * Configuration for the node.
     */
    public interface Config {

        @Attribute(order = 100)
        @Password
        char[] onfidoToken();

        @Attribute(order = 200)
        default boolean JITProvisioning() {
            return false;
        }

        @Attribute(order = 250)
        default String onfidoJWTreferrer() {
            return "*://*/*";
        }

        @Attribute(order = 300)
        default biometricCheck biometricCheck() {
            return biometricCheck.None;
        }

        @Attribute(order = 400)
        default String onfidoApplicantIdAttribute() {
            return "title";
        }

        @Attribute(order = 500)
        default Map<String, String> attributeMappingConfiguration() {
            return new HashMap<String, String>() {{
                //key is id_token key value is ldap attribute name
                put("cn", onfidoConstants.FIRST_NAME);
                put("givenName", onfidoConstants.FIRST_NAME);
                put("sn", onfidoConstants.LAST_NAME);
                put("postalAddress", "address_line_1");
                put("city", "address_line_3");
                put("postalCode", "address_line_4");
                put("stateProvince", "address_line_5");
            }};
        }


        @Attribute(order = 600)
        default String onfidoWelcomeMessage() {
            return "Identity Verification";
        }

        @Attribute(order = 700)
        default String onfidoHelpMessage() {
            return "Thank you for using Onfido for Identity Verification";
        }

        @Attribute(order = 900)
        default String onfidoJSURL() {
            return "https://assets.onfido.com/web-sdk-releases/5.2.1/onfido.min.js";
        }

        @Attribute(order = 1000)
        default String onfidoCSSUrl() {
            return "https://assets.onfido.com/web-sdk-releases/5.2.1/style.css";
        }
    }


    /**
     * Create the node using Guice injection. Just-in-time bindings can be used to obtain instances of other classes
     * from the plugin.
     *
     * @param config The service config.
     */
    @Inject
    public onfidoRegistrationNode(@Assisted Config config, CoreWrapper coreWrapper) {
        this.config = config;
        this.coreWrapper = coreWrapper;
    }

    @Override
    public Action process(TreeContext context) throws NodeProcessException {

        if (context.getCallback(TextOutputCallback.class).isPresent()) {
            onfidoAutoFill autofill = new onfidoAutoFill();

            if (config.JITProvisioning()) {
                JsonValue idAttributes = autofill.getIDAttributes(
                        context.sharedState.get(onfidoConstants.ONFIDO_APPLICANT_ID).asString(), config);
                if (idAttributes != null) {
                    autofill.populateOnfidoApplicant(
                            context.sharedState.get(onfidoConstants.ONFIDO_APPLICANT_ID).asString(),
                            idAttributes, config);
                }
                context.sharedState.put(USER_INFO_SHARED_STATE_KEY, json(object(
                        field(ATTRIBUTES_SHARED_STATE_KEY, autofill.prepareForProvisioningNode(
                                context.sharedState.get(onfidoConstants.ONFIDO_APPLICANT_ID).asString(),
                                context.sharedState.get(USERNAME).asString(), idAttributes, config)),
                        field(USER_NAMES_SHARED_STATE_KEY,
                              context.sharedState
                                      .get(SharedStateConstants.USERNAME)
                                      .asString()))));
            }

            if (!context.request.ssoTokenId.isEmpty()) {

                AMIdentity userIdentity;

                try {
                    userIdentity = coreWrapper.getIdentity(
                            SSOTokenManager.getInstance().createSSOToken(context.request.ssoTokenId));
                } catch (IdRepoException | SSOException e) {
                    logger.error("Unable to find user identity for user with SSO token: {}",
                                 context.request.ssoTokenId);
                    throw new NodeProcessException(e);
                }
                String userName = userIdentity.getName();
                if (!userName.equals("anonymous")) {
                    JsonValue userAttributes = json(object());
                    try {
                        String firstNameAttributeName = "cn";
                        String lastNameAttributeName = "sn";
                        for (Map.Entry<String, String> entry : config.attributeMappingConfiguration().entrySet()) {
                            if (StringUtils.isEqualTo(entry.getValue(), onfidoConstants.FIRST_NAME)) {
                                firstNameAttributeName = entry.getKey();
                            }
                            if (StringUtils.isEqualTo(entry.getValue(), onfidoConstants.LAST_NAME)) {
                                lastNameAttributeName = entry.getKey();
                            }
                        }
                        userAttributes.put(onfidoConstants.FIRST_NAME, userIdentity.getAttribute(firstNameAttributeName)
                                                                                   .iterator().next());
                        userAttributes.put(onfidoConstants.LAST_NAME, userIdentity.getAttribute(lastNameAttributeName)
                                                                                  .iterator().next());
                        context.sharedState.put(SharedStateConstants.USERNAME, userIdentity.getAttribute(
                                onfidoConstants.UID).iterator().next());
                    } catch (IdRepoException | SSOException e) {
                        logger.error("Unable to map attributes");
                        throw new NodeProcessException(e);
                    }


                    autofill.populateOnfidoApplicant(
                            context.sharedState.get(onfidoConstants.ONFIDO_APPLICANT_ID).asString(),
                            userAttributes, config);

                    Map<String, Set> attrMap = new HashMap<String, Set>() {{
                        put(config.onfidoApplicantIdAttribute(), new HashSet<String>() {{
                            add(context.sharedState.get(onfidoConstants.ONFIDO_APPLICANT_ID).asString());
                        }});
                    }};

                    try {
                        userIdentity.setAttributes(attrMap);
                        userIdentity.store();
                        //get first name and last name from SSO token and update applicant
                        //use method that builds json object using keys we already know pass to onfidoUpdateApplicant
                        // method.
                    } catch (SSOException | IdRepoException e) {
                        logger.error(
                                "Unable to store attribute for user. Make sure the configuration for called Onfido " +
                                        "ApplicantID Attribute is a valid LDAP Attribute",
                                e);
                    }
                }
            }


            //submit checks here.
            Check check = new Check();
            check.setType(ONFIDO_CHECK_TYPE);

            Report report = new Report();
            report.setName(ONFIDO_DOCUMENT_REPORT);

            List<Report> reports = new ArrayList<Report>() {{
                add(report);
            }};
            if (!config.biometricCheck().toString().equals("None")) {
                Report report2 = new Report();
                report2.setName(ONFIDO_FACIAL_REPORT);
                if (config.biometricCheck().toString().equals("Live")) {
                    report2.setVariant("video");
                }
                reports.add(report2);
            }
            check.setReports(reports);
            check.setAsynchronous(true);

            if (logger.isDebugEnabled()) {
                logger.debug("Starting now {}", new Timestamp(System.currentTimeMillis()).toString());
            }
            try {
                logger.debug("Applicant ID: {}", context.sharedState.get(onfidoConstants.ONFIDO_APPLICANT_ID));
                Check newCheck = new DefaultApi().createCheck(context.sharedState.get(
                        onfidoConstants.ONFIDO_APPLICANT_ID).asString(), check);
                logger.debug(newCheck.toString());
            } catch (ApiException e) {
                logger.error("Exception creating an applicant/check");
                throw new NodeProcessException(e);
            }
            return goToNext().build();
        }
        //if no callbacks build call back to inject SDK to screen
        return buildCallbacks(context);
    }


    private Action buildCallbacks(TreeContext context) throws NodeProcessException {
        ApiClient defaultClient = Configuration.getDefaultApiClient();


        // Configure API key authorization: Token
        ApiKeyAuth tokenAuth = (ApiKeyAuth) defaultClient.getAuthentication("Token");
        tokenAuth.setApiKey("token=" + new String(config.onfidoToken()));
        tokenAuth.setApiKeyPrefix("Token");

        // Limit the at-rest region, if needed (optional, see https://documentation.onfido.com/#regions)
        // defaultClient.setBasePath("https://api.us.onfido.com/v2");

        DefaultApi api = new DefaultApi();
        SdkTokenRequest sdkTokenRequest = new SdkTokenRequest();
        // Setting applicant details
        Applicant applicant = new Applicant();

        applicant.setFirstName("anonymous");
        applicant.setLastName("anonymous");

        JSONObject sdkConfiguration = new onfidoHelper().buildSdkConfig(config);

        Applicant newApplicant;
        try {
            newApplicant = api.createApplicant(applicant);
        } catch (ApiException e) {
            logger.error("Exception creating the applicant: {}", applicant);
            throw new NodeProcessException(e);
        }
        String applicantId = newApplicant.getId();
        context.sharedState.put(onfidoConstants.ONFIDO_APPLICANT_ID, newApplicant.getId());
        logger.debug("Applicant ID: {}" + applicantId);
        sdkTokenRequest.setApplicantId(applicantId);
        sdkTokenRequest.setReferrer(config.onfidoJWTreferrer());
        SdkTokenResponse sdkToken;
        try {
            sdkToken = api.generateSdkToken(sdkTokenRequest);
        } catch (ApiException e) {
            logger.error("Exception creating the SDKToken: {}", sdkTokenRequest);
            throw new NodeProcessException(e);
        }

        try {
            sdkConfiguration.put("token", sdkToken.getToken());
        } catch (JSONException e) {
            throw new NodeProcessException(e);
        }
        logger.debug("SDK Token is {}", sdkToken);

        return send(new ArrayList<Callback>() {{
            add(new ScriptTextOutputCallback(
                    String.format(SETUP_DOM_SCRIPT, config.onfidoJSURL(), config.onfidoCSSUrl(), sdkConfiguration)));
        }}).build();
    }


    public enum biometricCheck {
        None,
        Selfie,
        Live
    }
}
