package dk.itu.datasys.ops;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.Spec.ColumnSpec;

/** Inner equi-join that builds a hash table from one child and streams the other. */
public final class HashJoinOperator implements Operator {
    private static final Logger LOGGER = LoggerFactory.getLogger(HashJoinOperator.class);

    private final Operator build;
    private final Operator probe;
    private final int buildKey; 
    private final int probeKey;
    private final List<ColumnSpec> schema = new ArrayList<>();

    private final Map<Object, List<Object[]>> buildRowsByKey = new HashMap<>();
    private Object[] currentProbeRow;
    private List<Object[]> currentMatches = List.of();
    private int nextMatchIndex;
    
    private int rowsBuild;
    private int rowsProbe;
    private int rowsOut;

    public HashJoinOperator(Operator build, Operator probe, int buildKey, int probeKey) {
        this.build = Objects.requireNonNull(build, "build");
        this.probe = Objects.requireNonNull(probe, "probe");
        if (buildKey < 0 || probeKey < 0)
            throw new IllegalArgumentException("join key indexes must be non-negative");
        this.buildKey = buildKey;
        this.probeKey = probeKey;

        schema.addAll(build.schema());
        schema.addAll(probe.schema());
    }

    @Override
    public void open() {
        validateJoinKeys();
        buildRowsByKey.clear();
        currentProbeRow = null;
        currentMatches = List.of();
        nextMatchIndex = 0;

        rowsBuild = 0;
        rowsProbe = 0;
        rowsOut = 0;

        build.open();
        for (Object[] row; (row = build.next()) != null;) {
            rowsBuild++;
            buildRowsByKey.computeIfAbsent(row[buildKey], ignored -> new ArrayList<>()).add(row);   
        }

        probe.open();
    }

    @Override
    public List<ColumnSpec> schema() {
        return schema;
    }

    @Override
    public Object[] next() {
        while (true) {
            if (nextMatchIndex < currentMatches.size()) {  
                Object[] buildRow = currentMatches.get(nextMatchIndex++); 
                Object[] joinedRow = new Object[buildRow.length + currentProbeRow.length]; 
                System.arraycopy(buildRow, 0, joinedRow, 0, buildRow.length); 
                System.arraycopy(currentProbeRow, 0, joinedRow, buildRow.length, currentProbeRow.length);
                rowsOut++;
                return joinedRow;
            }

            currentProbeRow = probe.next();
            if (currentProbeRow == null) 
                return null;

            rowsProbe++;
            currentMatches = buildRowsByKey.get(currentProbeRow[probeKey]);
            nextMatchIndex = 0;
            if (currentMatches == null)
                currentMatches = List.of();
        }
    }

    @Override
    public void close() {
        build.close();
        probe.close();
        LOGGER.debug("rowsBuild={} rowsProbe={} rowsOut={}", rowsBuild, rowsProbe, rowsOut);
    }

    private void validateJoinKeys() {
        List<ColumnSpec> buildSchema = build.schema();
        List<ColumnSpec> probeSchema = probe.schema();
        if (buildKey >= buildSchema.size() || probeKey >= probeSchema.size())
            throw new IllegalArgumentException("join key index is outside the child schema");
        if (buildSchema.get(buildKey).type() != probeSchema.get(probeKey).type())
            throw new IllegalArgumentException("join key columns must have the same type");
    }
}
