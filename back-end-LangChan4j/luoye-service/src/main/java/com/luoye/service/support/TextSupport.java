package com.luoye.service.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/** 向量文本格式、哈希与敏感信息拦截。 */
public final class TextSupport {

    private static final Pattern SENSITIVE = Pattern.compile(
            "身份证|密码|secret|api[_-]?key|私钥|银行卡",
            Pattern.CASE_INSENSITIVE);

    private TextSupport() {
    }

    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean sensitive(String text) {
        return text != null && SENSITIVE.matcher(text).find();
    }

    public static float[] fit(float[] source, int dimension) {
        if (source == null) {
            return null;
        }
        if (source.length == dimension) {
            return source;
        }
        float[] fitted = new float[dimension];
        System.arraycopy(source, 0, fitted, 0, Math.min(source.length, dimension));
        return fitted;
    }

    public static String toPgVector(float[] vector) {
        if (vector == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder(vector.length * 8);
        builder.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        return builder.append(']').toString();
    }
}
