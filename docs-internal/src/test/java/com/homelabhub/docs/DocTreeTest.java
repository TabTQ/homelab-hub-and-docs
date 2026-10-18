package com.homelabhub.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DocTreeTest {

    //  1 Homelab
    //    2 Networking
    //      4 Tunnel
    //    3 Services
    //  5 Notes
    final List<DocTree.Node> nodes = List.of(
            new DocTree.Node(1, null, 0), new DocTree.Node(5, null, 1),
            new DocTree.Node(2, 1L, 0), new DocTree.Node(3, 1L, 1), new DocTree.Node(4, 2L, 0));
    final DocTree t = new DocTree(nodes);

    /** Applies placements and returns the resulting tree, to check the outcome rather than the steps. */
    static DocTree apply(List<DocTree.Node> before, List<DocTree.Placement> placements) {
        Map<Long, DocTree.Node> m = new HashMap<>();
        before.forEach(n -> m.put(n.id(), n));
        placements.forEach(p -> m.put(p.id(), new DocTree.Node(p.id(), p.parentId(), p.position())));
        return new DocTree(new ArrayList<>(m.values()));
    }

    @Test
    void childrenAreOrdered() {
        assertThat(t.children(null)).containsExactly(1L, 5L);
        assertThat(t.children(1L)).containsExactly(2L, 3L);
        assertThat(t.subtree(1)).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
        assertThat(t.ancestors(4)).containsExactly(1L, 2L);
    }

    @Test
    void insertAfterAndLast() {
        List<DocTree.Placement> p = t.insert(1L, 2L);
        assertThat(p).contains(new DocTree.Placement(-1, 1L, 1), new DocTree.Placement(3, 1L, 2));
        assertThat(t.insert(null, null)).contains(new DocTree.Placement(-1, null, 2));
        assertThatThrownBy(() -> t.insert(99L, null)).hasMessageContaining("parent");
        assertThatThrownBy(() -> t.insert(1L, 5L)).hasMessageContaining("not under that parent");
    }

    @Test
    void moveReordersWithinTheSameParent() {
        DocTree after = apply(nodes, t.move(2, 1L, 3L));
        assertThat(after.children(1L)).containsExactly(3L, 2L);
        DocTree first = apply(nodes, t.move(5, null, null));
        assertThat(first.children(null)).containsExactly(5L, 1L);
    }

    @Test
    void moveReparentsAndKeepsPositionsGapless() {
        DocTree after = apply(nodes, t.move(4, null, 1L));
        assertThat(after.children(null)).containsExactly(1L, 4L, 5L);
        assertThat(after.children(2L)).isEmpty();
        DocTree into = apply(nodes, t.move(5, 2L, 4L));
        assertThat(into.children(2L)).containsExactly(4L, 5L);
        assertThat(into.children(null)).containsExactly(1L);
    }

    @Test
    void moveRefusesCycles() {
        assertThatThrownBy(() -> t.move(1, 4L, null)).hasMessageContaining("into itself");
        assertThatThrownBy(() -> t.move(1, 1L, null)).hasMessageContaining("into itself");
        assertThatThrownBy(() -> t.move(2, 1L, 2L)).hasMessageContaining("after itself");
        assertThatThrownBy(() -> t.move(2, 1L, 5L)).hasMessageContaining("not under that parent");
    }

    @Test
    void liftPutsChildrenInThePagesPlace() {
        List<DocTree.Node> without1 = nodes.stream().filter(n -> n.id() != 1).toList();
        DocTree after = apply(without1, t.lift(1));
        assertThat(after.children(null)).containsExactly(2L, 3L, 5L);
        DocTree removed = apply(nodes.stream().filter(n -> n.id() != 2).toList(), t.remove(2));
        assertThat(removed.children(1L)).containsExactly(3L);
    }
}
