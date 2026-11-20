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

    private final Operator buildChild;
    private final Operator probeChild;
    private final int buildColumn; 
    private final int probeColumn;
    private final List<ColumnSpec> schema = new ArrayList<>();

    private final Map<Object, List<Object[]>> hashMap = new HashMap<>();

    private Object[] currentProbeRow;
    private List<Object[]> currentMatches = List.of();
    private int nextMatchIndex;
    
    private int rowsBuild;
    private int rowsProbe;
    private int rowsOut;

    public HashJoinOperator(Operator buildChild, Operator probeChild, int buildColumn, int probeColumn) {
        this.buildChild = Objects.requireNonNull(buildChild, "build");
        this.probeChild = Objects.requireNonNull(probeChild, "probe");
        if (buildColumn < 0 || probeColumn < 0)
            throw new IllegalArgumentException("join key indexes must be non-negative");
        this.buildColumn = buildColumn;
        this.probeColumn = probeColumn;

        schema.addAll(buildChild.schema());
        schema.addAll(probeChild.schema());
    }

    @Override
    public void open() {
        if (buildColumn >= buildChild.schema().size() || probeColumn >= probeChild.schema().size())
            throw new IllegalArgumentException("join key index is outside the child schema");
        if (buildChild.schema().get(buildColumn).type() != probeChild.schema().get(probeColumn).type())
            throw new IllegalArgumentException("join key columns must have the same type");

        hashMap.clear();

        currentMatches = List.of();
        nextMatchIndex = 0;

        rowsBuild = 0;
        rowsProbe = 0;
        rowsOut = 0;

        buildChild.open();
        for (Object[] row; (row = buildChild.next()) != null;) {
            rowsBuild++;
            hashMap.computeIfAbsent(row[buildColumn], ignored -> new ArrayList<>()).add(row);   
        }

        probeChild.open();
    }

    @Override
    public List<ColumnSpec> schema() {
        return schema;
    }

    @Override
    public Object[] next() {
        // Check if we have any remaining matches for the current probe row
        if (nextMatchIndex == currentMatches.size()) {
            // Loop until we get the next probe row that has matches in the build table
            do {
                currentProbeRow = probeChild.next();
                if (currentProbeRow == null) 
                    return null;

                rowsProbe++;
                currentMatches = hashMap.get(currentProbeRow[probeColumn]);
            } while (currentMatches == null);

            nextMatchIndex = 0;
        }

        // Build the result from the next match
        Object[] buildRow = currentMatches.get(nextMatchIndex++); 
        Object[] joinedRow = new Object[schema.size()]; 
        System.arraycopy(buildRow, 0, joinedRow, 0, buildRow.length); 
        System.arraycopy(currentProbeRow, 0, joinedRow, buildRow.length, currentProbeRow.length);
        rowsOut++;
        return joinedRow;
    }

    @Override
    public void close() {
        buildChild.close();
        probeChild.close();
        LOGGER.debug("rowsBuild={} rowsProbe={} rowsOut={}", rowsBuild, rowsProbe, rowsOut);
    }
}
