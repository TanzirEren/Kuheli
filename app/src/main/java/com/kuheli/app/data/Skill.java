package com.kuheli.app.data;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * A saved instruction set in the SKILL.md format used by Claude Code:
 * optional "---" front matter with name and description, then the instructions.
 */
public class Skill {
    public String name = "";
    public String description = "";
    public String body = "";
    public String raw = "";
    public boolean enabled = true;

    public static final String TEMPLATE =
            "---\nname: my-skill\ndescription: One line that says when this skill should be used\n---\n\n"
                    + "Write the instructions here. Use Markdown.\n";

    /** Lower-case letters, digits and hyphens, so the name works as a /command. */
    public static String slug(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        t = t.replaceAll("^-+|-+$", "");
        return t.length() > 48 ? t.substring(0, 48) : t;
    }

    public static Skill parse(String raw) {
        Skill s = new Skill();
        s.raw = raw == null ? "" : raw;
        String text = s.raw.replace("\r\n", "\n");
        s.body = text.trim();
        if (text.startsWith("---\n")) {
            int end = text.indexOf("\n---", 4);
            if (end > 0) {
                for (String line : text.substring(4, end).split("\n")) {
                    int c = line.indexOf(':');
                    if (c <= 0) continue;
                    String key = line.substring(0, c).trim().toLowerCase(Locale.ROOT);
                    String val = line.substring(c + 1).trim();
                    if (val.length() >= 2 && (val.startsWith("\"") && val.endsWith("\"")
                            || val.startsWith("'") && val.endsWith("'"))) {
                        val = val.substring(1, val.length() - 1);
                    }
                    if (key.equals("name")) s.name = slug(val);
                    else if (key.equals("description")) s.description = val;
                }
                int after = text.indexOf('\n', end + 1);
                s.body = (after < 0 ? "" : text.substring(after + 1)).trim();
            }
        }
        return s;
    }

    public JSONObject toEngine() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("description", description);
        o.put("body", body);
        o.put("enabled", enabled);
        return o;
    }
}
