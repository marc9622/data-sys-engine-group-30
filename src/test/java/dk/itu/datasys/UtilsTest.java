package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

public class UtilsTest {
        @Test
        public void testPowerSetOf() {
            Set<Integer> input = Set.of(1, 2, 3);
            Set<Set<Integer>> expectedPowerSet = Set.of(
                Set.of(),
                Set.of(1),
                Set.of(2),
                Set.of(3),
                Set.of(1, 2),
                Set.of(1, 3),
                Set.of(2, 3),
                Set.of(1, 2, 3)
            );

            Set<Set<Integer>> actualPowerSet = Utils.Streams.powerSetOf(input).collect(Collectors.toSet());
            assertEquals(expectedPowerSet, actualPowerSet);
        }

        @Test
        public void testPermutationsOf() {
            Set<Integer> input = Set.of(1, 2, 3);
            Set<List<Integer>> expectedPermutations = Set.of(
                List.of(1, 2, 3),
                List.of(1, 3, 2),
                List.of(2, 1, 3),
                List.of(2, 3, 1),
                List.of(3, 1, 2),
                List.of(3, 2, 1)
            );

            Set<List<Integer>> actualPermutations = Utils.Streams.permutationsOf(input).collect(Collectors.toSet());
            assertEquals(expectedPermutations, actualPermutations);
        }
}
