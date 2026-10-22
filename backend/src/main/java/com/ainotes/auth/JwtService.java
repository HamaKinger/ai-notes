package com.ainotes.auth;

import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * 签发 JWT。subject = 用户 id,便于 {@code Authentication#getName()} 直接拿到用户。
 */
@Service
public class JwtService {

    private static final String ISSUER = "ai-notes";
    private static final JwsHeader HEADER = JwsHeader.with(MacAlgorithm.HS256).build();

    private final JwtEncoder encoder;
    private final Duration ttl;

    public JwtService(JwtEncoder encoder,
                      @Value("${ai-notes.security.jwt.ttl:PT168H}") Duration ttl) {
        this.encoder = encoder;
        this.ttl = ttl;
    }

    public String issue(AppUser user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("email", user.getEmail())
                .claim("name", user.getDisplayName())
                .build();
        return encoder.encode(JwtEncoderParameters.from(HEADER, claims)).getTokenValue();
    }
}
