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

import static com.onfido.onfidoRegistrationNode.onfidoConstants.FAIL_FLAG;
import static com.onfido.onfidoRegistrationNode.onfidoConstants.HMAC_SHA1_ALGORITHM;
import static com.onfido.onfidoRegistrationNode.onfidoConstants.PASS_FLAG;
import static org.forgerock.openam.auth.node.api.SharedStateConstants.REALM;

import org.forgerock.openam.annotations.sm.Attribute;
import org.forgerock.openam.auth.node.api.Action;
import org.forgerock.openam.auth.node.api.Node;
import org.forgerock.openam.auth.node.api.NodeProcessException;
import org.forgerock.openam.auth.node.api.SharedStateConstants;
import org.forgerock.openam.auth.node.api.SingleOutcomeNode;
import org.forgerock.openam.auth.node.api.TreeContext;
import org.forgerock.openam.cts.CTSPersistentStore;
import org.forgerock.openam.cts.api.filter.TokenFilter;
import org.forgerock.openam.cts.api.filter.TokenFilterBuilder;
import org.forgerock.openam.cts.exceptions.CoreTokenException;
import org.forgerock.openam.sm.annotations.adapters.Password;
import org.forgerock.openam.sm.datalayer.api.query.PartialToken;
import org.forgerock.openam.tokens.CoreTokenField;
import org.forgerock.openam.utils.StringUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.assistedinject.Assisted;
import com.iplanet.sso.SSOException;
import com.iplanet.sso.SSOToken;
import com.iplanet.sso.SSOTokenManager;
import com.sun.identity.idm.AMIdentity;
import com.sun.identity.idm.IdRepoException;
import com.sun.identity.idm.IdUtils;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Formatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;


@Node.Metadata(outcomeProvider = SingleOutcomeNode.OutcomeProvider.class, configClass = onfidoWebhookNode.Config.class)
public class onfidoWebhookNode extends SingleOutcomeNode {

    private final Logger logger = LoggerFactory.getLogger("amAuth");
    private final Config config;
    private final CTSPersistentStore ctsPersistentStore;


    /**
     * Configuration for the node.
     */
    public interface Config {

        @Attribute(order = 100)
        @Password
        char[] onfidoToken();

        @Attribute(order = 200)
        @Password
        char[] webhookToken();

        @Attribute(order = 300)
        default boolean lockUser() {return false;}

        @Attribute(order = 400)
        default String userFlagAttribute() {
            return "carLicense";
        }

        @Attribute(order = 450)
        default String onfidoApplicantIdAttribute() {return "title";}

        //needs to be SET of strings
        @Attribute(order = 500)
        default Set<String> breakDowns() {
            return new HashSet<String>() {{
                add("age_validation");
                add("compromised_document");
                add("data_comparison");
                add("data_validation");
                add("visual_authenticity");
                add("image_integrity");
            }};
        }
    }


    /**
     * Create the node using Guice injection. Just-in-time bindings can be used to obtain instances of other classes
     * from the plugin.
     *
     * @param config The service config.
     */
    @Inject
    public onfidoWebhookNode(@Assisted Config config, CTSPersistentStore ctsPersistentStore) {
        this.config = config;
        this.ctsPersistentStore = ctsPersistentStore;
    }

    private static String toHexString(byte[] bytes) {
        Formatter formatter = new Formatter();
        for (byte b : bytes) {
            formatter.format("%02x", b);
        }
        return formatter.toString();
    }

    @Override
    public Action process(TreeContext context) throws NodeProcessException {
        final HttpServletRequest request = context.request.servletRequest;
        String checksHREF;
        String checkId;
        JSONObject jsonBody;
        context.sharedState.put(SharedStateConstants.USERNAME, "anonymous");
        try {
            jsonBody = new JSONObject(request.getParameter("jsonContent"));
            checksHREF = jsonBody.getJSONObject("payload").getJSONObject("object").getString("href");
            checkId = jsonBody.getJSONObject("payload").getJSONObject("object").getString("id");
            logger.debug(checkId);
        } catch (JSONException e) {
            throw new NodeProcessException(e);
        }

        //Unformating of JsonContent
        if (isSigned(request.getParameter("jsonContent").replaceAll("\\s+(?![1-9a-zA-Z])", ""),
                     request.getHeader("X-Signature"))) {
            onfidoHelper onfidoHelper = new onfidoHelper();
            //get applicant id then get HREF from checks complete to pass to this call
            String applicantId = onfidoHelper.getApplicantId(checksHREF);

            JSONArray reports = new JSONArray();
            JSONArray reportIds = onfidoHelper.getReportsId(checksHREF, new String(config.onfidoToken()));
            for (int n = 0; n < reportIds.length(); n++) {
                try {
                    reports.put(onfidoHelper.getReportData(checkId, reportIds.getString(n), new String(config.onfidoToken())));
                } catch (JSONException e) {
                    throw new NodeProcessException(e);
                }
            }
            AMIdentity userIdentity;
            Set<String> userSearchAttributes = new HashSet<>();
            userSearchAttributes.add(config.onfidoApplicantIdAttribute());
            userIdentity = IdUtils.getIdentity(applicantId, context.sharedState.get(REALM).asString(),
                                               userSearchAttributes);
            logger.debug(userIdentity.getName());

            if (onfidoHelper.parseReport(reports, config.breakDowns().toArray(new String[0]))) {
                flagUser(userIdentity, PASS_FLAG);
                return goToNext().build();
            } else {

                flagUser(userIdentity, FAIL_FLAG);
                if (config.lockUser()) {
                    try {
                        TokenFilter filter = new TokenFilterBuilder()
                                .returnAttribute(CoreTokenField.STRING_FIVE)
                                .and().withAttribute(CoreTokenField.USER_ID, userIdentity.getUniversalId())
                                .build();
                        Collection<PartialToken> tokens = ctsPersistentStore.attributeQuery(filter);

                        while (tokens.iterator().hasNext()) {
                            String tokenString = tokens.iterator().next().getValue(CoreTokenField.STRING_FIVE)
                                                       .toString();
                            try {
                                SSOToken token = SSOTokenManager.getInstance().createSSOToken(tokenString);
                                SSOTokenManager.getInstance().logout(token);
                            } catch (SSOException e) {
                                tokens.remove(tokens.iterator().next());
                            }
                        }
                    } catch (CoreTokenException e) {
                        throw new NodeProcessException(e);
                    }

                    try {
                        userIdentity.setActiveStatus(false);
                    } catch (IdRepoException | SSOException e) {
                        throw new NodeProcessException(e);
                    }
                    logger.debug("Lock User");
                }
            }
            return goToNext().build();
        } else {
            throw new NodeProcessException("Hmac Signature Failure Invalid Request");
        }
    }

    private boolean isSigned(String data, String signature) throws NodeProcessException {
        SecretKeySpec signingKey = new SecretKeySpec(new String(config.webhookToken()).getBytes(), HMAC_SHA1_ALGORITHM);
        Mac mac;
        try {
            mac = Mac.getInstance(HMAC_SHA1_ALGORITHM);
            mac.init(signingKey);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new NodeProcessException(e);
        }
        return StringUtils.isEqualTo(signature, toHexString(mac.doFinal(data.getBytes())));
    }

    private void flagUser(AMIdentity userIdentity, String flag) throws NodeProcessException {
        Map<String, Set> attrMap = new HashMap<>();

        if (config.userFlagAttribute() != null || !config.userFlagAttribute().equals("")) {
            attrMap.put(config.userFlagAttribute(), new HashSet<String>() {{
                add(flag);
            }});
            try {
                userIdentity.setAttributes(attrMap);
                userIdentity.store();
            } catch (IdRepoException | SSOException e) {
                throw new NodeProcessException(e);
            }
            logger.debug("Flagging User");

        }


    }

}