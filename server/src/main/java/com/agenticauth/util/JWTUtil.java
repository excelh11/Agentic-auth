package com.agenticauth.util;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Map;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.InvalidClaimException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.log4j.Log4j2;

/**
 * F12 — 비대칭 서명(RS256) 전환.
 *
 * 예전에는 소스에 하드코딩된 대칭키(HS256)를 발급/검증에 그대로 재사용했다(K4).
 * 발급 쪽만 갖고 있어야 할 개인키와, 검증 쪽이 가져도 되는 공개키를 분리한다.
 *
 * 키는 환경변수 AAUTH_JWT_KEY_DIR (기본값 "./keys") 아래
 * jwt-private.key / jwt-public.key 로 저장·로드한다. 두 파일이 모두 있으면 그대로 읽고,
 * 없으면 새로 만들어 그 자리에 저장한다 — 서버를 재시작해도 같은 키를 계속 쓰기 위해서다.
 *
 * generateToken/validateToken 시그니처는 그대로다. 호출부(APILoginSuccessHandler,
 * JWTCheckFilter, APIRefreshController)는 손대지 않는다.
 */
@Log4j2
public class JWTUtil {

    private static final String PRIVATE_KEY_FILE = "jwt-private.key";
    private static final String PUBLIC_KEY_FILE = "jwt-public.key";

    private static final PrivateKey PRIVATE_KEY;
    private static final PublicKey PUBLIC_KEY;

    static {
        try {
            String keyDirPath = System.getenv().getOrDefault("AAUTH_JWT_KEY_DIR", "./keys");
            File keyDir = new File(keyDirPath);
            File privateKeyFile = new File(keyDir, PRIVATE_KEY_FILE);
            File publicKeyFile = new File(keyDir, PUBLIC_KEY_FILE);

            if (privateKeyFile.exists() && publicKeyFile.exists()) {

                PRIVATE_KEY = loadPrivateKey(privateKeyFile);
                PUBLIC_KEY = loadPublicKey(publicKeyFile);

                log.info("JWT RS256 키를 로드했다: " + keyDir.getAbsolutePath());

            } else {

                KeyPair keyPair = Keys.keyPairFor(SignatureAlgorithm.RS256);
                PRIVATE_KEY = keyPair.getPrivate();
                PUBLIC_KEY = keyPair.getPublic();

                saveKey(keyDir, privateKeyFile, PRIVATE_KEY.getEncoded());
                saveKey(keyDir, publicKeyFile, PUBLIC_KEY.getEncoded());

                log.info("JWT RS256 키가 없어 새로 생성해 저장했다: " + keyDir.getAbsolutePath()
                        + " (이 시점 이전에 발급된 HS256 토큰은 모두 무효화된다 — F12 승인된 파괴적 변경)");
            }

        } catch (Exception e) {
            throw new RuntimeException("JWT RS256 키 초기화 실패", e);
        }
    }

    private static PrivateKey loadPrivateKey(File file) throws Exception {
        byte[] bytes = Files.readAllBytes(file.toPath());
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(bytes);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    private static PublicKey loadPublicKey(File file) throws Exception {
        byte[] bytes = Files.readAllBytes(file.toPath());
        X509EncodedKeySpec spec = new X509EncodedKeySpec(bytes);
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    private static void saveKey(File dir, File file, byte[] encoded) throws IOException {
        if (!dir.exists()) {
            dir.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(encoded);
        }
    }

    public static String generateToken(Map<String, Object> valueMap, int min){

        String jwtStr = Jwts.builder()
            .setHeader(Map.of("typ","JWT"))
            .setClaims(valueMap)
            .setIssuedAt(Date.from(ZonedDateTime.now().toInstant()))
            .setExpiration(Date.from(ZonedDateTime.now().plusMinutes(min).toInstant()))
            .signWith(PRIVATE_KEY, SignatureAlgorithm.RS256)
            .compact();

        return jwtStr;
    }

    public static Map<String, Object> validateToken(String token) {

    Map<String, Object> claim = null;

    try{

      claim = Jwts.parserBuilder()
              .setSigningKey(PUBLIC_KEY)
              .build()
              .parseClaimsJws(token) // 파싱 및 검증, 실패 시 에러
              .getBody();

    }catch(MalformedJwtException malformedJwtException){
        throw new CustomJWTException("MalFormed");
    }catch(ExpiredJwtException expiredJwtException){
        throw new CustomJWTException("Expired");
    }catch(InvalidClaimException invalidClaimException){
        throw new CustomJWTException("Invalid");
    }catch(JwtException jwtException){
        throw new CustomJWTException("JWTError");
    }catch(Exception e){
        throw new CustomJWTException("Error");
    }
    return claim;
  }

}
