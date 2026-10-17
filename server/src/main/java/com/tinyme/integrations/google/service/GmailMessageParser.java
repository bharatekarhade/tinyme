package com.tinyme.integrations.google.service;

import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/** Converts Gmail's MIME tree into small, safe-to-return message records. */
public final class GmailMessageParser {
    public static final int MAX_MESSAGE_CHARS = 8_000;
    public static final int MAX_THREAD_CHARS = 8_000;
    private static final Pattern HTML_TAG = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern HTML_BREAK = Pattern.compile("(?i)<(br|/p|/div|/li|/tr)\\b[^>]*>");
    private static final Pattern HTML_QUOTED = Pattern.compile("(?is)<blockquote\\b.*");
    private static final Pattern HTML_NOISE = Pattern.compile("(?is)<(style|script|head)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x[0-9a-f]+|[0-9]+);", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_ENTITY = Pattern.compile("&([a-zA-Z]+);");
    /** The named entities notices use; payment emails write ¥ as &yen;. */
    private static final Map<String, String> NAMED = Map.ofEntries(
            Map.entry("nbsp", " "), Map.entry("ensp", " "), Map.entry("emsp", " "), Map.entry("thinsp", " "),
            Map.entry("lt", "<"), Map.entry("gt", ">"), Map.entry("quot", "\""), Map.entry("apos", "'"),
            Map.entry("yen", "¥"), Map.entry("euro", "€"), Map.entry("pound", "£"), Map.entry("cent", "¢"),
            Map.entry("copy", "©"), Map.entry("reg", "®"), Map.entry("trade", "™"), Map.entry("times", "×"),
            Map.entry("middot", "·"), Map.entry("bull", "•"), Map.entry("hellip", "…"), Map.entry("ndash", "–"),
            Map.entry("mdash", "—"), Map.entry("lsquo", "‘"), Map.entry("rsquo", "’"), Map.entry("ldquo", "“"),
            Map.entry("rdquo", "”"), Map.entry("laquo", "«"), Map.entry("raquo", "»"), Map.entry("rarr", "→"),
            Map.entry("zwj", ""), Map.entry("zwnj", ""), Map.entry("shy", ""));
    private static final Pattern CHARSET = Pattern.compile("(?i)(?:^|;)\\s*charset\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)'|([^;\\s]+))");

    public Message parse(JsonNode message, boolean includeBody, int bodyLimit) {
        return parse(message, includeBody, bodyLimit, true);
    }

    public Message parse(JsonNode message, boolean includeBody, int bodyLimit, boolean trimReplies) {
        String id = text(message, "id");
        String threadId = text(message, "threadId");
        Map<String, String> headers = headers(message.path("payload").path("headers"));
        List<Attachment> attachments = new ArrayList<>();
        String body = includeBody ? body(message.path("payload"), attachments) : null;
        if (includeBody) {
            if (trimReplies) body = trimQuoted(body);
            body = body.substring(0, Math.min(body.length(), Math.max(0, bodyLimit)));
        }
        else collectAttachments(message.path("payload"), attachments);
        return new Message(id, threadId, headers.get("from"), headers.get("to"), headers.get("cc"),
                headers.get("bcc"), headers.get("subject"), headers.get("date"),
                text(message, "snippet"), body, List.copyOf(attachments));
    }

    private static String body(JsonNode part, List<Attachment> attachments) {
        collectAttachments(part, attachments);
        String plain = firstBody(part, "text/plain");
        if (plain != null) return plain;
        String html = firstBody(part, "text/html");
        if (html == null) return "";
        html = HTML_NOISE.matcher(html).replaceAll("");
        html = HTML_QUOTED.matcher(html).replaceFirst("");
        html = HTML_BREAK.matcher(html).replaceAll("\n");
        html = HTML_TAG.matcher(html).replaceAll("");
        return decodeEntities(html);
    }

    private static String firstBody(JsonNode part, String mimeType) {
        // Gmail sends "filename": "" on every body part; only a named part is an attachment.
        String filename = text(part, "filename");
        if (mimeType.equalsIgnoreCase(text(part, "mimeType")) && (filename == null || filename.isBlank())) {
            String data = text(part.path("body"), "data");
            if (data != null) {
                try {
                    byte[] bytes = Base64.getUrlDecoder().decode(data);
                    String contentType = headers(part.path("headers")).get("content-type");
                    return new String(bytes, charset(contentType));
                } catch (IllegalArgumentException ignored) {
                    return "";
                }
            }
        }
        JsonNode parts = part.get("parts");
        if (parts != null && parts.isArray()) {
            for (JsonNode child : parts) {
                String found = firstBody(child, mimeType);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Charset charset(String contentType) {
        if (contentType != null) {
            Matcher matcher = CHARSET.matcher(contentType);
            if (matcher.find()) {
                String name = matcher.group(1) != null ? matcher.group(1)
                        : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
                try {
                    return Charset.forName(name);
                } catch (IllegalCharsetNameException | UnsupportedCharsetException ignored) {
                    // Fall back to UTF-8 when a sender uses an unknown or malformed charset.
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String decodeEntities(String value) {
        // Named first: "&amp;#39;" is the text "&#39;", not an apostrophe, so &amp; goes last.
        Matcher named = NAMED_ENTITY.matcher(value);
        StringBuilder plain = new StringBuilder();
        while (named.find()) {
            String replacement = NAMED.get(named.group(1));
            named.appendReplacement(plain, Matcher.quoteReplacement(replacement == null ? named.group() : replacement));
        }
        named.appendTail(plain);
        value = plain.toString();
        Matcher matcher = NUMERIC_ENTITY.matcher(value);
        StringBuffer decoded = new StringBuffer();
        while (matcher.find()) {
            String number = matcher.group(1);
            try {
                int codePoint = number.regionMatches(true, 0, "x", 0, 1)
                        ? Integer.parseInt(number.substring(1), 16) : Integer.parseInt(number);
                String replacement = Character.isValidCodePoint(codePoint)
                        && !(codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)
                        ? new String(Character.toChars(codePoint)) : matcher.group();
                matcher.appendReplacement(decoded, Matcher.quoteReplacement(replacement));
            } catch (NumberFormatException ignored) {
                matcher.appendReplacement(decoded, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(decoded);
        return decoded.toString().replace("&amp;", "&");
    }

    private static void collectAttachments(JsonNode part, List<Attachment> output) {
        String filename = text(part, "filename");
        if (filename != null && !filename.isBlank()) {
            String attachmentId = text(part.path("body"), "attachmentId");
            output.add(new Attachment(filename, text(part, "mimeType"), attachmentId));
        }
        JsonNode parts = part.get("parts");
        if (parts != null && parts.isArray()) for (JsonNode child : parts) collectAttachments(child, output);
    }

    private static Map<String, String> headers(JsonNode rows) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (rows != null && rows.isArray()) for (JsonNode row : rows) {
            String name = text(row, "name");
            String value = text(row, "value");
            if (name != null && value != null) headers.putIfAbsent(name.toLowerCase(Locale.ROOT), value);
        }
        return headers;
    }

    static String trimQuoted(String value) {
        if (value == null) return "";
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder clean = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.stripLeading();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (trimmed.startsWith(">") || lower.matches("on .{1,180} wrote:" )
                    || lower.startsWith("-----original message-----")
                    || lower.startsWith("begin forwarded message:")) break;
            if (!clean.isEmpty()) clean.append('\n');
            clean.append(line);
        }
        return clean.toString().strip();
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value == null || value.isNull() || !value.isString() ? null : value.asString();
    }

    public record Message(String id, String threadId, String from, String to, String cc, String bcc, String subject,
                          String date, String snippet, String body, List<Attachment> attachments) {}
    public record Attachment(String filename, String mimeType, String attachmentId) {}
}
