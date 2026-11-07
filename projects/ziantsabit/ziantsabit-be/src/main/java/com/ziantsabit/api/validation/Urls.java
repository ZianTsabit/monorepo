package com.ziantsabit.api.validation;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Django's URLValidator, ported: http(s) or ftp(s), a host that is a dotted
 * name with a TLD, {@code localhost}, or an IP literal, then an optional port
 * and path. Used for the cover-image fields, where a typo'd URL would otherwise
 * be stored and break an {@code <img>} on a public page.
 */
public final class Urls {

    private static final String UL = "\\u00a1-\\uffff";
    private static final String IPV4 =
            "(?:0|25[0-5]|2[0-4][0-9]|1[0-9]?[0-9]?|[1-9][0-9]?)"
                    + "(?:\\.(?:0|25[0-5]|2[0-4][0-9]|1[0-9]?[0-9]?|[1-9][0-9]?)){3}";
    private static final String IPV6 = "\\[[0-9a-f:.]+\\]";
    private static final String HOSTNAME = "[a-z" + UL + "0-9](?:[a-z" + UL + "0-9-]{0,61}[a-z" + UL + "0-9])?";
    private static final String DOMAIN = "(?:\\.(?!-)[a-z" + UL + "0-9-]{1,63}(?<!-))*";
    private static final String TLD = "\\.(?!-)(?:[a-z" + UL + "-]{2,63}|xn--[a-z0-9]{1,59})(?<!-)\\.?";
    private static final String HOST = "(" + HOSTNAME + DOMAIN + TLD + "|localhost)";

    private static final Pattern URL = Pattern.compile(
            "(?:http|https|ftp|ftps)://"
                    + "(?:[^\\s:@/]+(?::[^\\s:@/]*)?@)?"
                    + "(?:" + IPV4 + "|" + IPV6 + "|" + HOST + ")"
                    + "(?::([0-9]{1,5}))?"
                    + "(?:[/?#][^\\s]*)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private Urls() {
    }

    public static boolean isValid(String value) {
        if (value.length() > 2048) {
            return false;
        }
        Matcher match = URL.matcher(value);
        if (!match.matches()) {
            return false;
        }
        String port = match.group(2);
        return port == null || Integer.parseInt(port) <= 65535;
    }
}
