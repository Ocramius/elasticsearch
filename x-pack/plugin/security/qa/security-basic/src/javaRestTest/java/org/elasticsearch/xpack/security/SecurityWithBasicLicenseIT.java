/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.security;

import org.apache.http.HttpHeaders;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.test.rest.yaml.ObjectPath;
import org.elasticsearch.xpack.security.authc.InternalRealms;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

public class SecurityWithBasicLicenseIT extends SecurityInBasicRestTestCase {

    public void testWithBasicLicense() throws Exception {
        checkLicenseType("basic");
        checkSecurityEnabled(false);
        checkAuthentication();
        checkHasPrivileges();
        checkIndexWrite();

        final String apiKeyCredentials = getApiKeyCredentials();
        assertAuthenticateWithApiKey(apiKeyCredentials, true);

        assertFailToGetToken();
        // Service account token works independently to oauth2 token service
        final String bearerString = createServiceAccountToken();
        assertAuthenticateWithServiceAccountToken(bearerString);

        assertAddRoleWithDLS(false);
        assertAddRoleWithFLS(false);
    }

    public void testWithTrialLicense() throws Exception {
        startTrial();
        String accessToken = null;
        String apiKeyCredentials1 = null;
        String apiKeyCredentials2 = null;
        boolean keyRoleHasDlsFls = false;
        assertCreateIndex("index1");
        assertCreateIndex("index2");
        assertCreateIndex("index41");
        assertCreateIndex("index42");
        try {
            checkLicenseType("trial");
            checkSecurityEnabled(true);
            checkAuthentication();
            checkHasPrivileges();
            checkIndexWrite();
            accessToken = getAccessToken();
            apiKeyCredentials1 = getApiKeyCredentials();
            assertAuthenticateWithToken(accessToken, true);
            assertAuthenticateWithApiKey(apiKeyCredentials1, true);
            assertAddRoleWithDLS(true);
            assertAddRoleWithFLS(true);
            final Tuple<String, Boolean> tuple = assertCreateApiKeyWithDlsFls();
            apiKeyCredentials2 = tuple.v1();
            keyRoleHasDlsFls = tuple.v2();
            assertReadWithApiKey(apiKeyCredentials2, "/index*/_search", true);
        } finally {
            revertTrial();
            assertAuthenticateWithToken(accessToken, false);
            assertAuthenticateWithApiKey(apiKeyCredentials1, true);
            assertFailToGetToken();
            assertAddRoleWithDLS(false);
            assertAddRoleWithFLS(false);
            // Any indices with DLS/FLS cannot be searched with the API key when the license is on Basic
            assertReadWithApiKey(apiKeyCredentials2, "/index*/_search", false);
            assertReadWithApiKey(apiKeyCredentials2, "/index1,index2/_search", false);
            assertReadWithApiKey(apiKeyCredentials2, "/index41/_search", false == keyRoleHasDlsFls);
            assertReadWithApiKey(apiKeyCredentials2, "/index42/_search", true);
            assertReadWithApiKey(apiKeyCredentials2, "/index1/_doc/1", false);
        }
    }

    private void startTrial() throws IOException {
        Response response = client().performRequest(new Request("POST", "/_license/start_trial?acknowledge=true"));
        assertOK(response);
    }

    private void revertTrial() throws IOException {
        client().performRequest(new Request("POST", "/_license/start_basic?acknowledge=true"));
    }

    private void checkLicenseType(String type) throws Exception {
        assertBusy(() -> {
            try {
                Map<String, Object> license = getAsMap("/_license");
                assertThat(license, notNullValue());
                assertThat(ObjectPath.evaluate(license, "license.type"), equalTo(type));
            } catch (ResponseException e) {
                throw new AssertionError(e);
            }
        });
    }

    private void checkSecurityEnabled(boolean allowAllRealms) throws IOException {
        Map<String, Object> usage = getAsMap("/_xpack/usage");
        assertThat(usage, notNullValue());
        assertThat(ObjectPath.evaluate(usage, "security.available"), equalTo(true));
        assertThat(ObjectPath.evaluate(usage, "security.enabled"), equalTo(true));
        for (String realm : Arrays.asList("file", "native")) {
            assertThat(ObjectPath.evaluate(usage, "security.realms." + realm + ".available"), equalTo(true));
            assertThat(ObjectPath.evaluate(usage, "security.realms." + realm + ".enabled"), equalTo(true));
        }
        for (String realm : InternalRealms.getConfigurableRealmsTypes()) {
            if (realm.equals("file") == false && realm.equals("native") == false) {
                assertThat(ObjectPath.evaluate(usage, "security.realms." + realm + ".available"), equalTo(allowAllRealms));
                assertThat(ObjectPath.evaluate(usage, "security.realms." + realm + ".enabled"), equalTo(false));
            }
        }
    }

    private void checkAuthentication() throws IOException {
        final Map<String, Object> auth = getAsMap("/_security/_authenticate");
        // From file realm, configured in build.gradle
        assertThat(ObjectPath.evaluate(auth, "username"), equalTo("security_test_user"));
        assertThat(ObjectPath.evaluate(auth, "roles"), contains("security_test_role"));
    }

    private void checkHasPrivileges() throws IOException {
        final Request request = new Request("GET", "/_security/user/_has_privileges");
        request.setJsonEntity("{" +
            "\"cluster\": [ \"manage\", \"monitor\" ]," +
            "\"index\": [{ \"names\": [ \"index_allowed\", \"index_denied\" ], \"privileges\": [ \"read\", \"all\" ] }]" +
            "}");
        Response response = client().performRequest(request);
        final Map<String, Object> auth = entityAsMap(response);
        assertThat(ObjectPath.evaluate(auth, "username"), equalTo("security_test_user"));
        assertThat(ObjectPath.evaluate(auth, "has_all_requested"), equalTo(false));
        assertThat(ObjectPath.evaluate(auth, "cluster.manage"), equalTo(false));
        assertThat(ObjectPath.evaluate(auth, "cluster.monitor"), equalTo(true));
        assertThat(ObjectPath.evaluate(auth, "index.index_allowed.read"), equalTo(true));
        assertThat(ObjectPath.evaluate(auth, "index.index_allowed.all"), equalTo(false));
        assertThat(ObjectPath.evaluate(auth, "index.index_denied.read"), equalTo(false));
        assertThat(ObjectPath.evaluate(auth, "index.index_denied.all"), equalTo(false));
    }

    private void checkIndexWrite() throws IOException {
        final Request request1 = new Request("POST", "/index_allowed/_doc");
        request1.setJsonEntity("{ \"key\" : \"value\" }");
        Response response1 = client().performRequest(request1);
        final Map<String, Object> result1 = entityAsMap(response1);
        assertThat(ObjectPath.evaluate(result1, "_index"), equalTo("index_allowed"));
        assertThat(ObjectPath.evaluate(result1, "result"), equalTo("created"));

        final Request request2 = new Request("POST", "/index_denied/_doc");
        request2.setJsonEntity("{ \"key\" : \"value\" }");
        ResponseException e = expectThrows(ResponseException.class, () -> client().performRequest(request2));
        assertThat(e.getResponse().getStatusLine().getStatusCode(), equalTo(403));
        assertThat(e.getMessage(), containsString("unauthorized for user [security_test_user]"));
    }

    private Request buildGetTokenRequest() {
        final Request getToken = new Request("POST", "/_security/oauth2/token");
        getToken.setJsonEntity("{\"grant_type\" : \"password\",\n" +
            "  \"username\" : \"security_test_user\",\n" +
            "  \"password\" : \"security-test-password\"\n" +
            "}");
        return getToken;
    }

    private Request buildGetApiKeyRequest() {
        final Request getApiKey = new Request("POST", "/_security/api_key");
        getApiKey.setJsonEntity("{\"name\" : \"my-api-key\",\n" +
            "  \"expiration\" : \"2d\",\n" +
            "  \"role_descriptors\" : {} \n" +
            "}");
        return getApiKey;
    }

    private String getAccessToken() throws IOException {
        Response getTokenResponse = adminClient().performRequest(buildGetTokenRequest());
        assertThat(getTokenResponse.getStatusLine().getStatusCode(), equalTo(200));
        final Map<String, Object> tokens = entityAsMap(getTokenResponse);
        return ObjectPath.evaluate(tokens, "access_token").toString();
    }

    private String getApiKeyCredentials() throws IOException {
        Response getApiKeyResponse = adminClient().performRequest(buildGetApiKeyRequest());
        assertThat(getApiKeyResponse.getStatusLine().getStatusCode(), equalTo(200));
        final Map<String, Object> apiKeyResponseMap = entityAsMap(getApiKeyResponse);
        assertOK(getApiKeyResponse);
        return ObjectPath.evaluate(apiKeyResponseMap, "encoded").toString();
    }

    private void assertFailToGetToken() {
        ResponseException e = expectThrows(ResponseException.class, () -> adminClient().performRequest(buildGetTokenRequest()));
        assertThat(e.getResponse().getStatusLine().getStatusCode(), equalTo(403));
        assertThat(e.getMessage(), containsString("current license is non-compliant for [security tokens]"));
    }

    private void assertAuthenticateWithToken(String accessToken, boolean shouldSucceed) throws IOException {
        assertNotNull("access token cannot be null", accessToken);
        Request request = new Request("GET", "/_security/_authenticate");
        RequestOptions.Builder options = request.getOptions().toBuilder();
        options.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        request.setOptions(options);
        if (shouldSucceed) {
            Response authenticateResponse = client().performRequest(request);
            assertOK(authenticateResponse);
            assertEquals("security_test_user", entityAsMap(authenticateResponse).get("username"));
        } else {
            ResponseException e = expectThrows(ResponseException.class, () -> client().performRequest(request));
            assertThat(e.getResponse().getStatusLine().getStatusCode(), equalTo(401));
            assertThat(e.getMessage(), containsString(
                "unable to authenticate with provided credentials and anonymous access is not allowed for this request"));
        }
    }

    private void assertAuthenticateWithApiKey(String apiKeyCredentials, boolean shouldSucceed) throws IOException {
        assertNotNull("API Key credentials cannot be null", apiKeyCredentials);
        Request request = new Request("GET", "/_security/_authenticate");
        RequestOptions.Builder options = request.getOptions().toBuilder();
        options.addHeader(HttpHeaders.AUTHORIZATION, "ApiKey " + apiKeyCredentials);
        request.setOptions(options);
        if (shouldSucceed) {
            Response authenticateResponse = client().performRequest(request);
            assertOK(authenticateResponse);
            assertEquals("admin_user", entityAsMap(authenticateResponse).get("username"));
        } else {
            ResponseException e = expectThrows(ResponseException.class, () -> client().performRequest(request));
            assertThat(e.getResponse().getStatusLine().getStatusCode(), equalTo(401));
            assertThat(e.getMessage(), containsString("missing authentication credentials for REST request"));
        }
    }

    private String createServiceAccountToken() throws IOException {
        final Request request = new Request("POST", "_security/service/elastic/fleet-server/credential/token/api-token-1");
        final Response response = adminClient().performRequest(request);
        assertOK(response);
        @SuppressWarnings("unchecked")
        final Map<String, ?> tokenMap = (Map<String, ?>) responseAsMap(response).get("token");
        return String.valueOf(tokenMap.get("value"));
    }

    private void assertAuthenticateWithServiceAccountToken(String bearerString) throws IOException {
        Request request = new Request("GET", "/_security/_authenticate");
        request.setOptions(RequestOptions.DEFAULT.toBuilder().addHeader("Authorization", "Bearer " + bearerString));
        final Response response = client().performRequest(request);
        assertOK(response);
        assertEquals("elastic/fleet-server", responseAsMap(response).get("username"));
    }

    private void assertAddRoleWithDLS(boolean shouldSucceed) throws IOException {
        final Request addRole = new Request("POST", "/_security/role/dlsrole");
        addRole.setJsonEntity("{\n" +
            "  \"cluster\": [\"all\"],\n" +
            "  \"indices\": [\n" +
            "    {\n" +
            "      \"names\": [ \"index1\", \"index2\" ],\n" +
            "      \"privileges\": [\"all\"],\n" +
            "      \"query\": \"{\\\"match\\\": {\\\"title\\\": \\\"foo\\\"}}\" \n" +
            "    },\n" +
            "    {\n" +
            "      \"names\": [ \"index41\", \"index42\" ],\n" +
            "      \"privileges\": [\"read\"]\n" +
            "    }\n" +
            "  ],\n" +
            "  \"run_as\": [ \"other_user\" ],\n" +
            "  \"metadata\" : { // optional\n" +
            "    \"version\" : 1\n" +
            "  }\n" +
            "}");
        if (shouldSucceed) {
            Response addRoleResponse = adminClient().performRequest(addRole);
            assertThat(addRoleResponse.getStatusLine().getStatusCode(), equalTo(200));
        } else {
            ResponseException e = expectThrows(ResponseException.class, () -> adminClient().performRequest(addRole));
            assertThat(e.getResponse().getStatusLine().getStatusCode(), equalTo(403));
            assertThat(e.getMessage(), containsString("current license is non-compliant for [field and document level security]"));
        }
    }

    private void assertAddRoleWithFLS(boolean shouldSucceed) throws IOException {
        final Request addRole = new Request("POST", "/_security/role/flsrole");
        addRole.setJsonEntity("{\n" +
            "  \"cluster\": [\"all\"],\n" +
            "  \"indices\": [\n" +
            "    {\n" +
            "      \"names\": [ \"index1\", \"index2\" ],\n" +
            "      \"privileges\": [\"all\"],\n" +
            "      \"field_security\" : { // optional\n" +
            "        \"grant\" : [ \"title\", \"body\" ]\n" +
            "      }\n" +
            "    },\n" +
            "    {\n" +
            "      \"names\": [ \"index41\", \"index42\" ],\n" +
            "      \"privileges\": [\"read\"]\n" +
            "    }\n" +
            "  ],\n" +
            "  \"run_as\": [ \"other_user\" ],\n" +
            "  \"metadata\" : { // optional\n" +
            "    \"version\" : 1\n" +
            "  }\n" +
            "}");
        if (shouldSucceed) {
            Response addRoleResponse = adminClient().performRequest(addRole);
            assertThat(addRoleResponse.getStatusLine().getStatusCode(), equalTo(200));
        } else {
            ResponseException e = expectThrows(ResponseException.class, () -> adminClient().performRequest(addRole));
            assertThat(e.getResponse().getStatusLine().getStatusCode(), equalTo(403));
            assertThat(e.getMessage(), containsString("current license is non-compliant for [field and document level security]"));
        }
    }

    private void createUserWithDlsOrFlsRole() throws IOException {
        final Request request = new Request("PUT", "/_security/user/dls_fls_user");
        request.setJsonEntity("{\"password\":\"superstrongpassword\"," +
            "\"roles\":[\"" + (randomBoolean() ? "dlsrole" : "flsrole") + "\"]}");
        assertOK(adminClient().performRequest(request));
    }

    private Tuple<String, Boolean> assertCreateApiKeyWithDlsFls() throws IOException {
        createUserWithDlsOrFlsRole();

        final Request request = new Request("POST", "/_security/api_key");
        final boolean keyRoleHasDlsFls = randomBoolean();
        if (keyRoleHasDlsFls) {
            if (randomBoolean()) {
                request.setJsonEntity("{\"name\":\"my-key\",\"role_descriptors\":" +
                    "{\"a\":{\"indices\":[" +
                    "{\"names\":[\"index41\"],\"privileges\":[\"read\"]," +
                    "\"query\":{\"term\":{\"tag\":{\"value\":\"prod\"}}}}," +
                    "{\"names\":[\"index1\",\"index2\",\"index42\"],\"privileges\":[\"read\"]}" +
                    "]}}}");
            } else {
                request.setJsonEntity(
                    "{\"name\":\"my-key\",\"role_descriptors\":" +
                        "{\"a\":{\"indices\":[" +
                        "{\"names\":[\"index41\"],\"privileges\":[\"read\"]," +
                        "\"field_security\":{\"grant\":[\"tag\"]}}," +
                        "{\"names\":[\"index1\",\"index2\",\"index42\"],\"privileges\":[\"read\"]}" +
                        "]}}}");
            }
        } else {
            request.setJsonEntity("{\"name\":\"my-key\",\"role_descriptors\":" +
                "{\"a\":{\"indices\":[{\"names\":[\"index1\",\"index2\",\"index41\",\"index42\"],\"privileges\":[\"read\"]}]}}}");
        }
        request.setOptions(request.getOptions().toBuilder().addHeader("Authorization",
            basicAuthHeaderValue("dls_fls_user", new SecureString("superstrongpassword".toCharArray()))));

        final Response response = client().performRequest(request);
        assertOK(response);
        return new Tuple<>((String) responseAsMap(response).get("encoded"), keyRoleHasDlsFls);
    }

    private void assertCreateIndex(String indexName) throws IOException {
        final Request request = new Request("PUT", indexName);
        assertOK(adminClient().performRequest(request));
    }

    private void assertReadWithApiKey(String apiKeyCredentials, String path, boolean shouldSucceed) throws IOException {
        final Request request = new Request("GET", path);
        final RequestOptions.Builder options = request.getOptions().toBuilder();
        options.addHeader(HttpHeaders.AUTHORIZATION, "ApiKey " + apiKeyCredentials);
        request.setOptions(options);

        if (shouldSucceed) {
            assertOK(client().performRequest(request));
        } else {
            final ResponseException e = expectThrows(ResponseException.class, () -> client().performRequest(request));
            assertThat(e.getResponse().getStatusLine().getStatusCode(), equalTo(403));
            assertThat(e.getMessage(), containsString("current license is non-compliant for [field and document level security]"));
            assertThat(e.getMessage(), containsString("indices_with_dls_or_fls"));
        }
    }
}
