import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Splits a Java class into members (fields, methods, nested classes, initialisers), each with its
 * leading comment/annotations, using a brace scanner that ignores braces in strings, chars and
 * comments. Used for the Table3D split.
 *
 *   java SplitTool.java list  <File.java>
 *       prints: index  firstLine-lastLine  lines  kind  name
 *   java SplitTool.java split <File.java> <map.txt> <outDir>
 *       map.txt lines: "<name-or-index> <TargetClass>"; members not listed go to the LAST target
 *       named on a line "default <TargetClass>". Writes <outDir>/<Target>.members.txt with the
 *       members' verbatim text, in original order, for assembling the new files.
 */
public class SplitTool {
    static final class Member {
        int start, end;      // 0-based line indices, inclusive (start includes leading comments)
        String kind, name;
    }

    public static void main(String[] a) throws Exception {
        List<String> lines = Files.readAllLines(Paths.get(a[1]), StandardCharsets.UTF_8);
        int[] body = classBody(lines);
        List<Member> ms = members(lines, body[0], body[1]);
        if (a[0].equals("list")) {
            for (int i = 0; i < ms.size(); i++) {
                Member m = ms.get(i);
                System.out.printf("%3d %5d-%-5d %4d %-7s %s%n", i, m.start + 1, m.end + 1, m.end - m.start + 1, m.kind, m.name);
            }
            System.out.println("class body lines " + (body[0] + 1) + "-" + (body[1] + 1));
            return;
        }
        Map<String, String> map = new LinkedHashMap<>();
        String def = null;
        for (String l : Files.readAllLines(Paths.get(a[2]), StandardCharsets.UTF_8)) {
            l = l.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            String[] p = l.split("\\s+");
            if (p[0].equals("default")) def = p[1];
            else map.put(p[0], p[1]);
        }
        Map<String, StringBuilder> out = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < ms.size(); i++) {
            Member m = ms.get(i);
            String key = map.containsKey(String.valueOf(i)) ? String.valueOf(i) : m.name;
            String target = map.getOrDefault(key, def);
            if (map.containsKey(key)) used.add(key);
            StringBuilder sb = out.computeIfAbsent(target, k -> new StringBuilder());
            for (int j = m.start; j <= m.end; j++) sb.append(lines.get(j)).append('\n');
            sb.append('\n');
        }
        for (String k : map.keySet()) if (!used.contains(k)) System.out.println("WARNING: map entry matched nothing: " + k);
        Path dir = Paths.get(a[3]);
        Files.createDirectories(dir);
        for (Map.Entry<String, StringBuilder> e : out.entrySet()) {
            Files.write(dir.resolve(e.getKey() + ".members.txt"), e.getValue().toString().getBytes(StandardCharsets.UTF_8));
            System.out.println(e.getKey() + ": " + e.getValue().toString().split("\n", -1).length + " lines");
        }
    }

    /** Line indices of the class's opening brace line+1 and closing brace line-1. */
    static int[] classBody(List<String> lines) {
        int open = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).matches("^public\\s+(final\\s+|abstract\\s+)*class\\s.*")) { open = i; break; }
        }
        int close = lines.size() - 1;
        while (!lines.get(close).trim().equals("}")) close--;
        return new int[] { open + 1, close - 1 };
    }

    /** Members at depth 0 of the class body. */
    static List<Member> members(List<String> lines, int from, int to) {
        List<Member> out = new ArrayList<>();
        int depth = 0;
        boolean inBlock = false;          // inside /* */
        int pendingStart = -1;            // first line of leading comments/annotations
        StringBuilder head = new StringBuilder();
        Member cur = null;
        for (int i = from; i <= to; i++) {
            String line = lines.get(i);
            String t = line.trim();
            if (depth == 0 && cur == null) {
                if (t.isEmpty() && !inBlock) { pendingStart = -1; head.setLength(0); continue; }
                if (pendingStart < 0) pendingStart = i;
            }
            // scan characters
            boolean inStr = false, inChr = false;
            for (int k = 0; k < line.length(); k++) {
                char c = line.charAt(k), n = k + 1 < line.length() ? line.charAt(k + 1) : 0;
                if (inBlock) { if (c == '*' && n == '/') { inBlock = false; k++; } continue; }
                if (inStr) { if (c == '\\') k++; else if (c == '"') inStr = false; continue; }
                if (inChr) { if (c == '\\') k++; else if (c == '\'') inChr = false; continue; }
                if (c == '/' && n == '/') break;
                if (c == '/' && n == '*') { inBlock = true; k++; continue; }
                if (c == '"') { inStr = true; continue; }
                if (c == '\'') { inChr = true; continue; }
                if (depth == 0 && cur == null) head.append(c);
                if (c == '{') {
                    if (depth == 0 && cur == null) { cur = start(pendingStart, head.toString()); }
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0 && cur != null) {
                        // a field initialiser like "= new X() { ... };" ends at the ';'
                        if (cur.kind.equals("field") && !line.substring(k).contains(";")) continue;
                        cur.end = i; out.add(cur); cur = null; pendingStart = -1; head.setLength(0);
                        break;
                    }
                } else if (c == ';' && depth == 0) {
                    if (cur == null) {
                        Member m = start(pendingStart, head.toString());
                        m.end = i; out.add(m);
                        pendingStart = -1; head.setLength(0);
                        break;
                    } else if (cur.kind.equals("field")) {
                        cur.end = i; out.add(cur); cur = null; pendingStart = -1; head.setLength(0);
                        break;
                    }
                }
            }
            if (depth == 0 && cur == null && head.length() > 0) head.append(' ');
        }
        return out;
    }

    static Member start(int startLine, String head) {
        Member m = new Member();
        m.start = startLine;
        String h = head.replaceAll("\\s+", " ").trim();
        if (h.matches(".*\\b(class|interface|enum|record)\\s+\\w+.*")) {
            m.kind = "type";
            java.util.regex.Matcher mt = java.util.regex.Pattern.compile("\\b(class|interface|enum|record)\\s+(\\w+)").matcher(h);
            m.name = mt.find() ? mt.group(2) : h;
        } else if (h.matches("^[^=]*\\w+\\s*\\([^)]*\\)?.*") && !h.contains("=")) {
            m.kind = "method";
            java.util.regex.Matcher mt = java.util.regex.Pattern.compile("(\\w+)\\s*\\(").matcher(h);
            m.name = mt.find() ? mt.group(1) : h;
        } else if (h.equals("static") || h.isEmpty()) {
            m.kind = "init"; m.name = "<init>";
        } else {
            m.kind = "field";
            String left = h.contains("=") ? h.substring(0, h.indexOf('=')) : h.replace(";", "");
            String[] w = left.trim().split("\\s+|,");
            m.name = w[w.length - 1];
        }
        return m;
    }
}
