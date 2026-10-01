package me.padej.jumper.workspace;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Globs over '/'-separated relative paths, the same on every OS: `*` - any characters but '/', `?` - one
 * such character, `**` - any number of folders (`scripts/**.jmp` and `scripts/** /*.jmp` both match
 * `scripts/a/b.jmp`), anything else literally. (java.nio's PathMatcher treats '\' differently on Windows.)
 */
final class Glob {
    private Glob() {}

    private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();

    static boolean matches(String glob, String path) {
        return CACHE.computeIfAbsent(glob, Glob::compile).matcher(path).matches();
    }

    static Pattern compile(String glob) {
        String g = glob.replace('\\', '/');
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*' && i + 1 < g.length() && g.charAt(i + 1) == '*') {
                i++;
                if (i + 1 < g.length() && g.charAt(i + 1) == '/') { i++; re.append("(?:.*/)?"); }
                else re.append(".*");
            } else if (c == '*') re.append("[^/]*");
            else if (c == '?') re.append("[^/]");
            else re.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(re.toString());
    }

    /** How specific a glob is: the length of its literal prefix (more specific wins between two hosts). */
    static int specificity(String glob) {
        int i = 0;
        while (i < glob.length() && "*?".indexOf(glob.charAt(i)) < 0) i++;
        return i;
    }
}
