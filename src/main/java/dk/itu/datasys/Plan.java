package dk.itu.datasys;

import java.util.Objects;

import dk.itu.datasys.ops.Operator;

public record Plan(Operator root, StorageEngine.ScanStats scanStats) {
    public Plan {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(scanStats, "scanStats");
    }
}