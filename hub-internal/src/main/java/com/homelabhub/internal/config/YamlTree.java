package com.homelabhub.internal.config;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * A line-aware tree of a YAML document. We COMPOSE (build nodes) but never CONSTRUCT Java objects, so no YAML tag can
 * instantiate a class; aliases are refused outright (no billion-laughs); size and nesting are capped.
 */
final class YamlTree {

    sealed interface N permits M, L, S {
        int line();
    }

    record M(Map<String, N> map, Map<String, Integer> keyLines, int line) implements N {}

    record L(List<N> items, int line) implements N {}

    /** A scalar keeps its raw text: YAML 1.1 turns `No` or `on` into booleans, which must not corrupt names. */
    record S(String raw, String tag, int line) implements N {
        boolean isNull() {
            return tag.equals("null");
        }
    }

    record Result(N root, List<Issue> issues) {}

    static final int MAX_CHARS = 256 * 1024;

    private YamlTree() {}

    static Result parse(String text) {
        List<Issue> issues = new ArrayList<>();
        if (text.length() > MAX_CHARS) {
            issues.add(Issue.error("", 1, "The file is larger than " + (MAX_CHARS / 1024) + " KiB."));
            return new Result(null, issues);
        }
        LoaderOptions opts = new LoaderOptions();
        opts.setMaxAliasesForCollections(0);
        opts.setAllowRecursiveKeys(false);
        opts.setAllowDuplicateKeys(true); // we report duplicates ourselves, with a line number
        opts.setCodePointLimit(MAX_CHARS + 16);
        opts.setNestingDepthLimit(40);
        try {
            Node root = new Yaml(new SafeConstructor(opts)).compose(new StringReader(text));
            if (root == null) {
                issues.add(Issue.error("", 1, "The file is empty."));
                return new Result(null, issues);
            }
            return new Result(convert(root, "", issues), issues);
        } catch (MarkedYAMLException e) {
            int line = e.getProblemMark() != null ? e.getProblemMark().getLine() + 1 : 0;
            String problem = e.getProblem() != null ? e.getProblem() : e.getMessage();
            if (problem != null && problem.toLowerCase().contains("alias")) {
                problem = "YAML aliases and anchors are not supported here";
            }
            issues.add(Issue.error("", line, "YAML syntax error: " + problem));
            return new Result(null, issues);
        } catch (RuntimeException e) {
            issues.add(Issue.error("", 0, "Could not read the YAML: " + e.getClass().getSimpleName()));
            return new Result(null, issues);
        }
    }

    private static N convert(Node node, String path, List<Issue> issues) {
        int line = node.getStartMark().getLine() + 1;
        String tag = node.getTag().getValue();
        if (!tag.startsWith("tag:yaml.org,2002:")) {
            issues.add(Issue.error(path, line, "Unsupported YAML tag '" + tag + "'."));
        }
        if (node instanceof MappingNode mn) {
            Map<String, N> map = new LinkedHashMap<>();
            Map<String, Integer> keyLines = new LinkedHashMap<>();
            for (NodeTuple t : mn.getValue()) {
                if (!(t.getKeyNode() instanceof ScalarNode k)) {
                    issues.add(Issue.error(path, t.getKeyNode().getStartMark().getLine() + 1, "Keys must be plain words."));
                    continue;
                }
                String key = k.getValue();
                int kl = k.getStartMark().getLine() + 1;
                if (key.equals("<<") || k.getTag().getValue().endsWith(":merge")) {
                    issues.add(Issue.error(path, kl, "Merge keys (<<) are not supported."));
                    continue;
                }
                if (map.containsKey(key)) {
                    issues.add(Issue.error(join(path, key), kl, "Duplicate key '" + key + "'."));
                    continue;
                }
                keyLines.put(key, kl);
                map.put(key, convert(t.getValueNode(), join(path, key), issues));
            }
            return new M(map, keyLines, line);
        }
        if (node instanceof SequenceNode sn) {
            List<N> items = new ArrayList<>();
            int i = 0;
            for (Node child : sn.getValue()) {
                items.add(convert(child, path + "[" + i++ + "]", issues));
            }
            return new L(items, line);
        }
        ScalarNode sc = (ScalarNode) node;
        String shortTag = tag.startsWith("tag:yaml.org,2002:") ? tag.substring("tag:yaml.org,2002:".length()) : tag;
        return new S(sc.getValue(), shortTag, line);
    }

    static String join(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }
}
