package com.onfido.onfidoRegistrationNode;

class onfidoConstants {
    static final String ONFIDO_APPLICANT_ID = "onfidoApplicantID";
    static final String FIRST_NAME = "first_name";
    static final String LAST_NAME = "last_name";
    static final String HMAC_SHA1_ALGORITHM = "HmacSHA1";
    static final String UID = "uid";
    static final String FAIL_FLAG = "fail";
    static final String PASS_FLAG = "pass";
    static final String USER_INFO_SHARED_STATE_KEY = "userInfo";
    static final String USER_NAMES_SHARED_STATE_KEY = "userNames";
    static final String ATTRIBUTES_SHARED_STATE_KEY = "attributes";
    static final String ONFIDO_CHECK_TYPE = "express";
    static final String ONFIDO_DOCUMENT_REPORT = "document";
    static final String ONFIDO_FACIAL_REPORT = "facial_similarity";
    static String SETUP_DOM_SCRIPT =
            "var body=document.body;\n" +
                    "var script = document.createElement('script');\n" +

                    "document.getElementById('loginButton_0').style.display = 'none';\n" +
                    "var onfido_div = document.createElement(\"div\");\n" +
                    "onfido_div.id=\"onfido-mount\";\n" +
                    "script.src = '%s';\n" +


                    "var head = document.head; \n " +
                    "var link = document.createElement(\"link\");  \n" +
                    "     link.type = \"text/css\"; \n " +
                    "     link.rel = \"stylesheet\"; \n " +
                    "     link.href = '%s'; \n " +
                    "    head.appendChild(link); \n " +
                    "onfidoConfig=%s;\n" +
                    "           script.onload=function() {onfido=Onfido.init(onfidoConfig)};\n" +
                    "document.body.appendChild(script);\n";
}


