package com.jsav.chat.auth;

import com.jsav.chat.config.JwtProperties;
import com.jsav.chat.user.User;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

  private final JwtEncoder encoder;
  private final JwtProperties props;

  public JwtService(JwtEncoder encoder, JwtProperties props) {
    this.encoder = encoder;
    this.props = props;
  }

  /** El subject es el id del usuario. */
  public String issue(User user) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .subject(String.valueOf(user.getId()))
            .issuedAt(now)
            .expiresAt(now.plus(props.ttl()))
            .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
