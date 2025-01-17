/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.client.security;

import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.Objects;

import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;

public final class DelegatePkiAuthenticationResponse {

    private final String accessToken;
    private final String type;
    private final TimeValue expiresIn;
    private final AuthenticateResponse authentication;

    public DelegatePkiAuthenticationResponse(String accessToken, String type, TimeValue expiresIn,
                                             AuthenticateResponse authentication) {
        this.accessToken = accessToken;
        this.type = type;
        this.expiresIn = expiresIn;
        this.authentication = authentication;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getType() {
        return type;
    }

    public TimeValue getExpiresIn() {
        return expiresIn;
    }

    public AuthenticateResponse getAuthentication() { return authentication; }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final DelegatePkiAuthenticationResponse that = (DelegatePkiAuthenticationResponse) o;
        return Objects.equals(accessToken, that.accessToken) &&
            Objects.equals(type, that.type) &&
            Objects.equals(expiresIn, that.expiresIn) &&
            Objects.equals(authentication, that.authentication);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accessToken, type, expiresIn, authentication);
    }

    @SuppressWarnings("unchecked")
    private static final ConstructingObjectParser<DelegatePkiAuthenticationResponse, Void> PARSER = new ConstructingObjectParser<>(
            "delegate_pki_response", true,
            args -> new DelegatePkiAuthenticationResponse((String) args[0], (String) args[1], TimeValue.timeValueSeconds((Long) args[2]),
                (AuthenticateResponse) args[3]));

    static {
        PARSER.declareString(constructorArg(), new ParseField("access_token"));
        PARSER.declareString(constructorArg(), new ParseField("type"));
        PARSER.declareLong(constructorArg(), new ParseField("expires_in"));
        PARSER.declareObject(constructorArg(), (p, c) -> AuthenticateResponse.fromXContent(p), new ParseField("authentication"));
    }

    public static DelegatePkiAuthenticationResponse fromXContent(XContentParser parser) throws IOException {
        return PARSER.parse(parser, null);
    }
}
