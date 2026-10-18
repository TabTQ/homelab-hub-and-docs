package com.homelabhub.docs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The page tree as plain data: who is whose parent, and the order among siblings. Positions are gapless (0..n-1) and every
 * move rewrites the positions of the two sibling lists it touches. No database here, so every rule is unit-tested.
 */
public final class DocTree {

    public record Node(long id, Long parentId, int position) {}

    /** A position change for one page, applied by the caller in one transaction. */
    public record Placement(long id, Long parentId, int position) {}

    private final Map<Long, Node> nodes = new HashMap<>();

    public DocTree(Collection<Node> all) {
        for (Node n : all) {
            nodes.put(n.id(), n);
        }
    }

    public boolean contains(long id) {
        return nodes.containsKey(id);
    }

    /** Children of {@code parentId} (null = top level) in order. */
    public List<Long> children(Long parentId) {
        return nodes.values().stream().filter(n -> Objects.equals(n.parentId(), parentId))
                .sorted(Comparator.comparingInt(Node::position).thenComparingLong(Node::id)).map(Node::id).toList();
    }

    /** {@code id} and everything below it. */
    public Set<Long> subtree(long id) {
        Set<Long> out = new HashSet<>();
        List<Long> todo = new ArrayList<>(List.of(id));
        while (!todo.isEmpty()) {
            long cur = todo.remove(todo.size() - 1);
            if (out.add(cur)) {
                todo.addAll(children(cur));
            }
        }
        return out;
    }

    /** Ids from the top level down to {@code id}'s parent. */
    public List<Long> ancestors(long id) {
        List<Long> out = new ArrayList<>();
        Node n = nodes.get(id);
        Set<Long> seen = new HashSet<>();
        while (n != null && n.parentId() != null && seen.add(n.parentId())) {
            out.add(0, n.parentId());
            n = nodes.get(n.parentId());
        }
        return out;
    }

    /**
     * Where a new page goes: after {@code afterId} among {@code parentId}'s children, or last when afterId is null.
     * Returns the placements of the siblings that shift, plus the new page's own position as the last element's
     * position value with id -1.
     */
    public List<Placement> insert(Long parentId, Long afterId) {
        requireParent(parentId);
        List<Long> sibs = new ArrayList<>(children(parentId));
        int at = indexAfter(sibs, afterId, sibs.size());
        sibs.add(at, -1L);
        return renumber(parentId, sibs);
    }

    /**
     * Moves {@code id} under {@code newParent} right after {@code afterId} (null = first). Refuses to move a page into its
     * own subtree. Returns every placement that changes.
     */
    public List<Placement> move(long id, Long newParent, Long afterId) {
        Node n = nodes.get(id);
        if (n == null) {
            throw new IllegalArgumentException("No such page.");
        }
        requireParent(newParent);
        if (newParent != null && subtree(id).contains(newParent)) {
            throw new IllegalArgumentException("A page cannot be moved into itself or one of its children.");
        }
        if (afterId != null && afterId == id) {
            throw new IllegalArgumentException("A page cannot be placed after itself.");
        }
        List<Placement> out = new ArrayList<>();
        List<Long> oldSibs = new ArrayList<>(children(n.parentId()));
        oldSibs.remove(id);
        List<Long> newSibs = Objects.equals(n.parentId(), newParent) ? oldSibs : new ArrayList<>(children(newParent));
        if (afterId != null && !newSibs.contains(afterId)) {
            throw new IllegalArgumentException("The page to place it after is not under that parent.");
        }
        int at = afterId == null ? 0 : newSibs.indexOf(afterId) + 1;
        newSibs.add(at, id);
        if (!Objects.equals(n.parentId(), newParent)) {
            out.addAll(renumber(n.parentId(), oldSibs));
        }
        out.addAll(renumber(newParent, newSibs));
        return out.stream().filter(p -> {
            Node cur = nodes.get(p.id());
            return cur == null || !Objects.equals(cur.parentId(), p.parentId()) || cur.position() != p.position();
        }).toList();
    }

    /**
     * Deleting {@code id} with "lift": its children take its place, in order, under its parent. Returns the placements of
     * the remaining siblings (the deleted page itself is not listed).
     */
    public List<Placement> lift(long id) {
        Node n = nodes.get(id);
        if (n == null) {
            throw new IllegalArgumentException("No such page.");
        }
        List<Long> sibs = new ArrayList<>(children(n.parentId()));
        int at = sibs.indexOf(id);
        sibs.remove(at);
        sibs.addAll(at, children(id));
        return renumber(n.parentId(), sibs);
    }

    /** Positions after {@code id} leaves its sibling list (delete with children, or to the Trash). */
    public List<Placement> remove(long id) {
        Node n = nodes.get(id);
        if (n == null) {
            throw new IllegalArgumentException("No such page.");
        }
        List<Long> sibs = new ArrayList<>(children(n.parentId()));
        sibs.remove(id);
        return renumber(n.parentId(), sibs);
    }

    private void requireParent(Long parentId) {
        if (parentId != null && !nodes.containsKey(parentId)) {
            throw new IllegalArgumentException("The parent page does not exist.");
        }
    }

    private static int indexAfter(List<Long> sibs, Long afterId, int whenNull) {
        if (afterId == null) {
            return whenNull;
        }
        int i = sibs.indexOf(afterId);
        if (i < 0) {
            throw new IllegalArgumentException("The page to place it after is not under that parent.");
        }
        return i + 1;
    }

    private static List<Placement> renumber(Long parentId, List<Long> ordered) {
        List<Placement> out = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            out.add(new Placement(ordered.get(i), parentId, i));
        }
        return out;
    }
}
